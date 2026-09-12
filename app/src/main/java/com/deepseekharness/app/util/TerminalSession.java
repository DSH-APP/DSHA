package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 普通管道终端：独立进程组取消，完成回执串行发送，旧进程事件不能影响新会话。 */
public final class TerminalSession {
    public enum State { STOPPED, STARTING, READY, BUSY, STOPPING, FAILED }

    public interface Backend {
        /** 必须在任何环境准备和进程创建之前原子登记；清理成功之前不得释放。 */
        default AutoCloseable beginLifetime() throws Exception { return null; }
        Process open() throws Exception;
        /** 必须终止本会话的进程组并确认启动器退出；失败抛异常，不能假报重启成功。 */
        void terminate(Process process, long processGroup) throws Exception;
        /** 宿主可在写入前原子取得短环境凭据；被门禁拒绝时必须保证一个字节都未发送。 */
        default void write(Process process, String text) throws IOException {
            process.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }
    }

    /** 环境门禁拒绝（与可能已经部分发送的普通 IOException 区分）。 */
    public static final class EnvironmentUnavailable extends IOException {
        public EnvironmentUnavailable(String message) { super(message); }
    }

    /** 下层可能已 fork，却在返回 Process 句柄前失败；不能把它当作确定的未启动。 */
    public static final class UncertainStart extends IOException {
        public UncertainStart(Throwable cause) { super(com.deepseekharness.app.util.UiText.text("启动失败且无法确认子进程已回收，保留环境占用"), cause); }
    }

    private static final int MAX_COMMAND = 16 * 1024;
    private final Backend backend;
    private final Consumer<String> output;
    private final Consumer<State> changed;
    /** 大量输出时给读线程背压，取消不能排在无界的输出任务之后。 */
    private final Semaphore outputSlots = new Semaphore(16);
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "terminal-control");
        thread.setDaemon(true);
        return thread;
    });
    /** 下列可变会话状态仅由 control 线程访问。 */
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private Run active;
    private long commandId;
    private volatile State state = State.STOPPED;
    private volatile boolean permanentlyClosing, disposed;

    private static final class Run {
        final String prefix = "\036DSHA_" + UUID.randomUUID().toString().replace("-", "") + ":";
        final String resultVariable = "__dsha_exit_" + prefix.substring(6, prefix.length() - 1);
        final StringBuilder incoming = new StringBuilder();
        final long openedAt = System.nanoTime();
        Process process;
        AutoCloseable lifetime;
        long group, inFlight, exitedAt;
        boolean ready, stopRequested, restartAfterStop, uncertainStart;
    }

    public TerminalSession(Backend backend, Consumer<String> output, Consumer<State> changed) {
        this.backend = backend;
        this.output = output;
        this.changed = changed;
    }

    public State state() { return state; }
    private boolean submitControl(Runnable action) {
        try {control.execute(action);return true;}
        catch(java.util.concurrent.RejectedExecutionException error){if(disposed||permanentlyClosing)return false;throw error;}
    }
    public void ensureStarted() { if(!permanentlyClosing)submitControl(()->{if(!permanentlyClosing)start();}); }

    /** 接受后仅发送一次。未接受时调用方必须保留输入框文本。 */
    public boolean submit(String command) {
        if (permanentlyClosing || command == null || command.trim().isEmpty() || command.length() > MAX_COMMAND
                || command.indexOf('\0') >= 0) return false;
        return submitControl(() -> {
            if(permanentlyClosing)return;
            pending.addLast(command);
            emit("$ " + command + "\n");
            if (active == null) start();
            pump();
        });
    }

    public void cancelAndRestart() {
        if(permanentlyClosing)return;
        submitControl(() -> {
            if(permanentlyClosing)return;
            if (active == null) { start(); return; }
            requestStop(true);
        });
    }

    public void shutdown() {
        if(!disposed)submitControl(this::shutdownNow);
    }

    /** 维护线程在移动 rootfs 前使用；先确认关闭请求执行过，再等进程回收完成。 */
    public boolean shutdownAndWait(long timeoutMs) throws InterruptedException {
        if(disposed)return true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        java.util.concurrent.CountDownLatch dispatched = new java.util.concurrent.CountDownLatch(1);
        if(!submitControl(() -> { try { shutdownNow(); } finally { dispatched.countDown(); } }))return disposed;
        if (!dispatched.await(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS)) return false;
        while (state != State.STOPPED && state != State.FAILED && System.nanoTime() < deadline) Thread.sleep(25);
        return state == State.STOPPED;
    }

    /** 用户移除标签时确认进程退出，再释放控制线程；失败仍保留会话，允许重试关闭。 */
    public synchronized boolean disposeAndWait(long timeoutMs) throws InterruptedException {
        if(disposed)return true;
        permanentlyClosing=true;
        try {
            if(!shutdownAndWait(timeoutMs))return false;
            disposed=true;control.shutdown();return true;
        } finally {if(!disposed)permanentlyClosing=false;}
    }

    private void deliverExit(Run run, Exception failure) {
        try {control.execute(()->exited(run,failure));}
        catch(java.util.concurrent.RejectedExecutionException error){if(!disposed)throw error;}
    }

    private void shutdownNow() {
        if (!pending.isEmpty()) emit(com.deepseekharness.app.util.UiText.text("[关闭会话：取消 ") + pending.size() + com.deepseekharness.app.util.UiText.text(" 条尚未发送的命令]\n"));
        pending.clear();
        if (active != null) requestStop(false);
        else change(State.STOPPED);
    }

    private void start() {
        if (active != null) return;
        Run run = new Run();
        active = run;
        change(State.STARTING);
        emit(com.deepseekharness.app.util.UiText.text("[正在启动简易终端；命令按顺序等待发送]\n"));
        try {
            run.lifetime = backend.beginLifetime();
            run.process = backend.open();
            // 不改运行时环境：沿用 Backend 的交互入口，仅给此 shell 建立独立 session/group。
            String script = "printf '" + escapedPrefix(run) + "READY:%s\\037' \"$$\"; "
                    + "exec /bin/bash --noprofile --norc";
            write(run, "exec /usr/bin/setsid /bin/bash --noprofile --norc -c " + ShellQuote.arg(script) + "\n");
            Thread reader = new Thread(() -> read(run), "terminal-output");
            reader.setDaemon(true);
            reader.start();
            poll(run);
        } catch (Exception error) {
            emit(com.deepseekharness.app.util.UiText.text("[终端启动失败：") + error + com.deepseekharness.app.util.UiText.text("；未发送的命令保留，重试后继续]\n"));
            if (error instanceof UncertainStart) {
                run.uncertainStart = true;
                change(State.FAILED);
                emit(com.deepseekharness.app.util.UiText.text("[无法取得进程句柄，禁止重启会话或移动环境；请结束应用进程后再恢复]\n"));
            } else if (run.process == null) {
                try { releaseLifetime(run); active = null; }
                catch (Exception cleanup) { emit(com.deepseekharness.app.util.UiText.text("[终端启动清理失败，保留环境占用：") + cleanup + "]\n"); }
                change(State.FAILED);
            }
            else {
                run.stopRequested = true;
                run.restartAfterStop = false;
                stop(run);
            }
        }
    }

    private static String escapedPrefix(Run run) { return "\\036" + run.prefix.substring(1); }

    private void read(Run run) {
        // 与安装读取策略一致：不在空管道上阻塞 read，避免读线程与 Process 回收/close 互等。
        // UTF-8 解码器跨批次保留不完整字符，不能直接逐批 new String 破坏中文和 emoji。
        try (InputStream input = run.process.getInputStream()) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            ByteBuffer bytes = ByteBuffer.allocate(8192);
            CharBuffer chars = CharBuffer.allocate(8192);
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException(com.deepseekharness.app.util.UiText.text("终端读取被中断"));
                int available = input.available();
                if (available > 0) {
                    int count = input.read(bytes.array(), bytes.position(), Math.min(available, bytes.remaining()));
                    if (count < 0) break;
                    if (count > 0) {
                        bytes.position(bytes.position() + count);
                        decode(run, decoder, bytes, chars, false);
                        continue;
                    }
                }
                if (!alive(run.process)) {
                    if (input.available() > 0) continue;
                    break;
                }
                Thread.sleep(20);
            }
            decode(run, decoder, bytes, chars, true);
            java.nio.charset.CoderResult flushed = decoder.flush(chars);
            if (flushed.isError()) flushed.throwException();
            enqueueOutput(run, chars);
            deliverExit(run, null);
        } catch (IOException error) {
            deliverExit(run, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            deliverExit(run, error);
        }
    }

    private void decode(Run run, CharsetDecoder decoder, ByteBuffer bytes, CharBuffer chars, boolean end)
            throws IOException, InterruptedException {
        bytes.flip();
        java.nio.charset.CoderResult result = decoder.decode(bytes, chars, end);
        if (result.isError()) result.throwException();
        // 输入缓冲最多 8192 字节，输出缓冲同长，UTF-8 不会产生更长的字符序列。
        if (result.isOverflow()) throw new IOException(com.deepseekharness.app.util.UiText.text("终端解码缓冲区溢出"));
        bytes.compact();
        enqueueOutput(run, chars);
    }

    private void enqueueOutput(Run run, CharBuffer chars) throws InterruptedException {
        chars.flip();
        String chunk = chars.toString();
        chars.clear();
        if (chunk.isEmpty()) return;
        outputSlots.acquire();
        try { control.execute(() -> {
            try { receive(run, chunk); }
            finally { outputSlots.release(); }
        }); } catch(java.util.concurrent.RejectedExecutionException error) { outputSlots.release();if(!disposed)throw error; }
    }

    private void receive(Run run, String chunk) {
        if (active != run) return;
        run.incoming.append(chunk);
        while (active == run) {
            int begin = run.incoming.indexOf(run.prefix);
            if (begin < 0) {
                // 保留可能被分包截断的协议前缀，其余输出立即显示（含无换行输出）。
                int keep = Math.min(run.prefix.length() - 1, run.incoming.length());
                while (keep > 0 && !run.prefix.startsWith(run.incoming.substring(run.incoming.length() - keep))) keep--;
                emit(run.incoming.substring(0, run.incoming.length() - keep));
                run.incoming.delete(0, run.incoming.length() - keep);
                return;
            }
            if (begin > 0) { emit(run.incoming.substring(0, begin)); run.incoming.delete(0, begin); }
            int end = run.incoming.indexOf("\037", run.prefix.length());
            if (end < 0) {
                if (run.incoming.length() > 256) {
                    emit(run.incoming.substring(0, 1)); run.incoming.deleteCharAt(0); continue;
                }
                return;
            }
            String message = run.incoming.substring(run.prefix.length(), end);
            run.incoming.delete(0, end + 1);
            if (message.startsWith("READY:") && !run.ready) {
                try { run.group = Long.parseLong(message.substring(6)); }
                catch (NumberFormatException ignored) { continue; }
                if (run.group <= 1) { run.group = 0; continue; }
                run.ready = true;
                if (run.stopRequested) { stop(run); return; }
                change(State.READY);
                emit("[会话已就绪；中止会重启 shell，cd/export 状态随重启清除]\n");
                pump();
            } else if (run.inFlight != 0 && message.startsWith("DONE:" + run.inFlight + ":")) {
                run.inFlight = 0;
                if (!run.stopRequested) { change(State.READY); pump(); }
            }
        }
    }

    private void pump() {
        Run run = active;
        if (run == null || !run.ready || run.stopRequested || run.inFlight != 0 || pending.isEmpty()) return;
        if (!alive(run.process)) { exited(run, null); return; }
        String command = pending.removeFirst();
        long id = ++commandId;
        run.inFlight = id;
        change(State.BUSY);
        try {
            // 整行先解析，再 eval；read 等程序不会误吞完成标记。需要交互输入时使用 PTY。
            // 回执不能把上一条命令的 $? 改成 printf 的 0；用独立子 shell 恢复退出码。
            write(run, "eval " + ShellQuote.arg(command) + "; " + run.resultVariable + "=$?; builtin printf '" + escapedPrefix(run)
                    + "DONE:" + id + ":%s\\037' \"$" + run.resultVariable + "\"; (exit \"$" + run.resultVariable + "\")\n");
        } catch (EnvironmentUnavailable blocked) {
            pending.addFirst(command);
            run.inFlight = 0;
            emit("[" + blocked.getMessage() + com.deepseekharness.app.util.UiText.text("；命令尚未发送，已保留。完成后请重试重启]\n"));
            requestStop(false);
        } catch (IOException error) {
            // 管道部分写入后无法证明命令是否执行，绝不自动重发可能有副作用的命令。
            emit(com.deepseekharness.app.util.UiText.text("[发送结果不确定，未自动重发：") + command + com.deepseekharness.app.util.UiText.text("；请检查执行结果。") + error + "]\n");
            requestStop(true);
        }
    }

    private void requestStop(boolean restart) {
        Run run = active;
        if (run == null) return;
        boolean retryFailedStop = state == State.FAILED;
        run.stopRequested = true;
        run.restartAfterStop = restart;
        change(State.STOPPING);
        emit(restart ? com.deepseekharness.app.util.UiText.text("[正在中止本会话进程组并重启；尚未发送的命令保留]\n") : com.deepseekharness.app.util.UiText.text("[正在关闭简易终端]\n"));
        // 等待启动握手拿到本组 PID；此时还未发送任何用户命令，不能误杀容器启动器代替取消。
        if (run.ready || retryFailedStop || !alive(run.process)) stop(run);
    }

    private void stop(Run run) {
        if (active != run) return;
        if (run.uncertainStart) {
            change(State.FAILED);
            emit(com.deepseekharness.app.util.UiText.text("[未确认启动进程已回收，继续保留环境占用，不创建新会话]\n"));
            return;
        }
        try {
            if (run.process != null) backend.terminate(run.process, run.group);
            releaseLifetime(run);
            active = null;
            change(State.STOPPED);
            emit(com.deepseekharness.app.util.UiText.text("[旧会话已结束]\n"));
            if (run.restartAfterStop) start();
        } catch (Exception error) {
            change(State.FAILED);
            emit(com.deepseekharness.app.util.UiText.text("[中止失败，未创建新会话：") + error + com.deepseekharness.app.util.UiText.text("；待发命令保留，请再次点中止重试]\n"));
        }
    }

    private static void releaseLifetime(Run run) throws Exception {
        if (run.lifetime != null) {
            run.lifetime.close();
            run.lifetime = null;
        }
    }

    private void exited(Run run, Exception failure) {
        if (active != run) return;
        emit(run.incoming.toString());
        run.incoming.setLength(0);
        if (failure != null) emit(com.deepseekharness.app.util.UiText.text("[终端读取失败：") + failure + "]\n");
        emit(com.deepseekharness.app.util.UiText.text("[会话已退出；已发送的命令不会自动重放]\n"));
        run.stopRequested = true;
        // 初始化失败不能自动重试，否则缺少 setsid 等环境问题会不断创建进程。
        run.restartAfterStop = run.ready && (run.restartAfterStop || !pending.isEmpty());
        stop(run);
    }

    private void poll(Run run) {
        control.schedule(() -> {
            if (active != run) return;
            long now = System.nanoTime();
            if (!alive(run.process)) {
                if (run.exitedAt == 0) run.exitedAt = now;
                // 给输出读取线程留时间提交最后一段；后台子进程持有 stdout 时不能一直等 EOF。
                if (now - run.exitedAt > TimeUnit.MILLISECONDS.toNanos(500)) { exited(run, null); return; }
            } else if (!run.ready && now - run.openedAt > TimeUnit.SECONDS.toNanos(15)) {
                emit(com.deepseekharness.app.util.UiText.text("[终端初始化超时，未发送任何用户命令]\n"));
                run.stopRequested = true;
                stop(run);
                return;
            }
            poll(run);
        }, 100, TimeUnit.MILLISECONDS);
    }

    private void write(Run run, String text) throws IOException {
        backend.write(run.process, text);
    }

    private static boolean alive(Process process) {
        if (process == null) return false;
        try { process.exitValue(); return false; }
        catch (IllegalThreadStateException running) { return true; }
    }

    private void change(State value) {
        state = value;
        try { changed.accept(value); } catch (RuntimeException ignored) { }
    }

    private void emit(String text) {
        if (text.isEmpty()) return;
        try { output.accept(text); } catch (RuntimeException ignored) { }
    }
}
