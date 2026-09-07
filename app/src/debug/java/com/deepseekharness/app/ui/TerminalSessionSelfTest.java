package com.deepseekharness.app.ui;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ShellQuote;
import com.deepseekharness.app.util.TerminalSession;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 真实 Ubuntu 普通管道测试；独立 TerminalSession，绝不调用用户终端的 getSession/shutdownShell。 */
final class TerminalSessionSelfTest {
    private TerminalSessionSelfTest() { }

    static void run(ProotBootstrap proot, Consumer<String> transcript, Consumer<String> result) throws Exception {
        if (!proot.isEnvironmentReady()) throw new IOException("环境未就绪；自测不会安装或重置环境");
        String name = "dsha-terminal-self-" + UUID.randomUUID().toString().replace("-", "");
        File parent = new File(proot.getRootfsDir(), "tmp").getCanonicalFile();
        File folder = new File(parent, name).getCanonicalFile();
        require(folder.getParentFile().equals(parent) && folder.mkdir(), "无法创建独立终端夹具目录");
        String guest = "/tmp/" + name + "/";
        String[] names = {"group", "child", "survived", "cancelled-next", "queued-exit", "idle-exit"};
        OwnedBackend backend = new OwnedBackend(TerminalFragment.terminalBackend(proot));
        List<TerminalSession.State> states = new CopyOnWriteArrayList<>();
        TerminalSession terminal = new TerminalSession(backend, transcript, states::add);
        Throwable failure = null;
        try {
            // wait 等待真实 sleep 子进程，仍为普通管道；同时记录 PID，不能只看提示文案判定取消。
            send(terminal, "printf '%s\\n' \"$$\" > " + q(guest + "group")
                    + "; sleep 30 & child=$!; printf '%s\\n' \"$child\" > " + q(guest + "child")
                    + "; wait \"$child\"; printf 'UNEXPECTED\\n' > " + q(guest + "survived"));
            File childFile = new File(folder, "child");
            until(() -> childFile.length() > 1, 45000, "独立 shell 没有启动 sleep");
            long group = pid(new File(folder, "group")), child = pid(childFile);
            backend.rememberFirstGroup(group);
            require(terminal.state() == TerminalSession.State.BUSY, "sleep 执行时会话未标记忙碌");
            String alive = proot.execAndRead("if kill -0 " + child + " 2>/dev/null; then printf CHILD_ALIVE; fi", 5000);
            require(alive.contains("CHILD_ALIVE"), "取消前 sleep 子进程没有存活");
            send(terminal, "printf 'AFTER_CANCEL\\n' >> " + q(guest + "cancelled-next"));
            long cancelAt = System.nanoTime();
            terminal.cancelAndRestart();
            until(() -> read(new File(folder, "cancelled-next")).equals("AFTER_CANCEL\n")
                    && terminal.state() == TerminalSession.State.READY, 25000, "取消后新 shell 未执行待发命令");
            long cancelMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelAt);
            require(cancelMs < 25000 && !new File(folder, "survived").exists(), "sleep 自然结束或旧命令继续执行，取消无效");
            // 僵尸已不能继续执行；hidepid 不可读时仍需 kill -0 确认消失，不能将未知当成功。
            String stopped = proot.execAndRead("if ! kill -0 " + child + " 2>/dev/null; then printf CHILD_STOPPED; "
                    + "else state=$(ps -o stat= -p " + child + " 2>/dev/null); case \"$state\" in *Z*) printf CHILD_STOPPED;; esac; fi", 5000);
            require(stopped.contains("CHILD_STOPPED"), "未确认 sleep 子进程已停止");
            require(states.contains(TerminalSession.State.STOPPING) && backend.count() >= 2,
                    "缺少中止状态或没有创建新的独立 shell");
            result.accept("terminal_sleep=PASS：真实 sleep PID 已停止，重启耗时 " + cancelMs + "ms，排队命令执行一次");

            // exit 发出后立刻提交；后一个屏障命令确保第一条已完整消费，核对文件内容防止重复。
            send(terminal, "exit");
            send(terminal, "printf 'FIRST\\n' >> " + q(guest + "queued-exit"));
            send(terminal, "printf 'SECOND\\n' >> " + q(guest + "queued-exit"));
            File queued = new File(folder, "queued-exit");
            until(() -> read(queued).contains("SECOND\n") && terminal.state() == TerminalSession.State.READY,
                    30000, "exit 后立即排队的首条命令未完成");
            require(read(queued).equals("FIRST\nSECOND\n"), "exit 后命令丢失、重复或乱序：" + read(queued));

            // 已经观察到退出，再敲首条命令；覆盖旧实现清空输入框但直接 return 的路径。
            send(terminal, "exit");
            until(() -> terminal.state() == TerminalSession.State.STOPPED, 20000, "未观察到 shell 正常退出");
            send(terminal, "printf 'FIRST_AFTER_STOP\\n' >> " + q(guest + "idle-exit"));
            File first = new File(folder, "idle-exit");
            until(() -> first.length() > 0 && terminal.state() == TerminalSession.State.READY,
                    30000, "已退出会话的首条命令丢失");
            require(read(first).equals("FIRST_AFTER_STOP\n"), "已退出会话的首条命令重复");
            require(read(new File(folder, "cancelled-next")).equals("AFTER_CANCEL\n"), "重启时重放了已执行的命令");
            result.accept("terminal_exit=PASS：exit 后立即排队、退出后重新输入两条路径均不丢失、不重复");
        } catch (Throwable error) {
            failure = error;
        } finally {
            try {
                backend.preventNewShells();
                terminal.shutdown();
                try {
                    until(() -> terminal.state() == TerminalSession.State.STOPPED && backend.allExited(),
                            25000, "独立终端清理未结束");
                } catch (AssertionError timeout) {
                    backend.closeOwned();
                    terminal.shutdown();
                    until(() -> terminal.state() == TerminalSession.State.STOPPED && backend.allExited(),
                            10000, "独立终端清理失败");
                }
                for (String file : names) {
                    File item = new File(folder, file);
                    require(!item.exists() || item.delete(), "无法清除本次终端夹具：" + file);
                }
                require(folder.delete(), "独立夹具目录没有清空");
                result.accept("terminal_cleanup=PASS：仅回收本次创建的 shell/子进程，临时目录已删除");
            } catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
                result.accept("terminal_cleanup=FAIL：本次夹具保留于 " + folder.getAbsolutePath());
            }
        }
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure != null) throw new AssertionError("终端自测失败", failure);
    }

    private static void send(TerminalSession terminal, String command) {
        require(terminal.submit(command), "测试命令未被接受");
    }
    private static String q(String value) { return ShellQuote.arg(value); }
    private static long pid(File file) {
        long value = Long.parseLong(read(file).trim());
        require(value > 1 && value <= Integer.MAX_VALUE, "测试进程号无效");
        return value;
    }
    private static String read(File file) {
        if (!file.isFile()) return "";
        try {
            require(file.length() <= 4096, "测试输出文件超出预期大小");
            return new String(Compat.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new AssertionError("无法读取终端夹具", error); }
    }
    private static void until(BooleanSupplier condition, long timeoutMs, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        require(condition.getAsBoolean(), message);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    /** 保存本次工厂返回的 Process 对象及组号；失败收尾也不按名称扫描或关闭用户会话。 */
    private static final class OwnedBackend implements TerminalSession.Backend {
        final TerminalSession.Backend delegate;
        final List<Process> processes = new ArrayList<>();
        final Map<Process, Long> groups = new IdentityHashMap<>();
        boolean closing;
        OwnedBackend(TerminalSession.Backend delegate) { this.delegate = delegate; }
        @Override public AutoCloseable beginLifetime() throws Exception { return delegate.beginLifetime(); }
        @Override public Process open() throws Exception {
            synchronized (this) { if (closing) throw new IOException("测试正在收尾，不再创建 shell"); }
            Process process = delegate.open();
            boolean discard;
            synchronized (this) { processes.add(process); discard = closing; }
            if (discard) {
                try { delegate.terminate(process, 0); }
                catch (Exception error) { throw new TerminalSession.UncertainStart(error); }
                throw new IOException("测试收尾时回收尚未握手的 shell");
            }
            return process;
        }
        synchronized void preventNewShells() { closing = true; }
        @Override public void terminate(Process process, long group) throws Exception {
            synchronized (this) {
                require(processes.contains(process), "拒绝回收非本次创建的进程");
                groups.put(process, group);
            }
            delegate.terminate(process, group);
        }
        @Override public void write(Process process, String text) throws IOException { delegate.write(process, text); }
        synchronized void rememberFirstGroup(long group) { groups.put(processes.get(0), group); }
        synchronized int count() { return processes.size(); }
        synchronized boolean allExited() {
            for (Process process : processes) if (Compat.isAlive(process)) return false;
            return true;
        }
        void closeOwned() throws Exception {
            List<Process> owned;
            synchronized (this) { owned = new ArrayList<>(processes); }
            for (Process process : owned) {
                long group;
                synchronized (this) { group = groups.getOrDefault(process, 0L); }
                if (Compat.isAlive(process)) delegate.terminate(process, group);
            }
        }
    }
}
