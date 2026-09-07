package com.deepseekharness.app.util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 普通进程管道的有界收集器：不等 EOF，输出不停或子进程继承管道也不能拖过总期限。 */
public final class BoundedProcessRunner {
    private BoundedProcessRunner() { }

    public static final class Result {
        public final String output;
        public final int exitCode;
        public final boolean timedOut, truncated;
        private Result(ByteArrayOutputStream bytes, int exitCode, boolean timedOut, boolean truncated) {
            this.output = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.truncated = truncated;
        }
    }

    /** terminate 由平台提供，只终止本次创建的进程；避免依赖 Android 26 的 Process API。 */
    public static Result collect(Process process, long timeoutMillis, int maxBytes,
                                 Consumer<Process> terminate) throws IOException, InterruptedException {
        if (timeoutMillis <= 0 || maxBytes < 0) throw new IllegalArgumentException("无效的进程限制");
        final long started = System.nanoTime();
        final long budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        InputStream input = process.getInputStream();
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        try {
            // 此入口不接受交互输入，及时给读 stdin 的命令 EOF。
            process.getOutputStream().close();
            while (true) {
                if (Thread.interrupted()) throw new InterruptedException("命令等待已中断");
                long remaining = budget - (System.nanoTime() - started);
                if (remaining <= 0) return new Result(bytes, -1, true, truncated);
                // Process 的管道只读已到达的字节，不创建可能永久卡在 read() 的收集线程。
                int available = input.available();
                if (available > 0) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, available));
                    if (count > 0) {
                        int kept = Math.min(count, maxBytes - bytes.size());
                        bytes.write(buffer, 0, kept);
                        truncated |= kept < count;
                        continue; // 每个块都重新核对总期限，达到输出上限后继续排空管道。
                    }
                }
                Integer code = exitCode(process);
                if (code != null) {
                    // 进程可能在上一次 available() 后退出，再收一次尾部；不等继承管道的子进程。
                    if (input.available() > 0) continue;
                    return new Result(bytes, code, false, truncated);
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
            }
        } finally {
            try {
                if (exitCode(process) == null) terminate.accept(process);
            } finally {
                close(input);
                close(process.getErrorStream());
                close(process.getOutputStream());
            }
        }
    }

    private static Integer exitCode(Process process) {
        try { return process.exitValue(); }
        catch (IllegalThreadStateException running) { return null; }
    }

    private static void close(Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }
}
