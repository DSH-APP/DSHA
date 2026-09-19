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
        public final String tail;
        public final long totalBytes;
        public final int maxBytes;
        public final int exitCode;
        public final boolean timedOut, truncated;
        private Result(ProcessOutputCapture bytes, int exitCode, boolean timedOut, boolean truncated) {
            this.output = bytes.head();
            this.tail = bytes.tail();
            this.totalBytes = bytes.totalBytes();
            this.maxBytes = bytes.headLimit();
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.truncated = truncated;
        }
        public String diagnostic() {
            String head = output.substring(0, Math.min(output.length(), 1200));
            String end = tail.substring(Math.max(0, tail.length() - 2400));
            return "exit=" + exitCode + " timedOut=" + timedOut + " bytes=" + totalBytes
                    + " maxBytes=" + maxBytes + " truncated=" + truncated
                    + "\nHEAD:\n" + head + "\nTAIL:\n" + end;
        }
    }

    /** terminate 由平台提供，只终止本次创建的进程；避免依赖 Android 26 的 Process API。 */
    public static Result collect(Process process, long timeoutMillis, int maxBytes,
                                 Consumer<Process> terminate) throws IOException, InterruptedException {
        return collect(process, timeoutMillis, maxBytes, terminate, null);
    }

    /** 按 UTF-8 完整行反馈进度；行缓冲有界，回调不会改变原有输出与退出语义。 */
    public static Result collect(Process process, long timeoutMillis, int maxBytes,
                                 Consumer<Process> terminate, Consumer<String> onLine) throws IOException, InterruptedException {
        if (timeoutMillis <= 0 || maxBytes < 0) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("无效的进程限制"));
        final long started = System.nanoTime();
        final long budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        ProcessOutputCapture bytes = new ProcessOutputCapture(maxBytes, Math.min(maxBytes, 8192));
        InputStream input = process.getInputStream();
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean truncated = false;
        try {
            // 此入口不接受交互输入，及时给读 stdin 的命令 EOF。
            process.getOutputStream().close();
            while (true) {
                if (Thread.interrupted()) throw new InterruptedException(com.deepseekharness.app.util.UiText.text("命令等待已中断"));
                long remaining = budget - (System.nanoTime() - started);
                if (remaining <= 0) return new Result(bytes, -1, true, truncated);
                // Process 的管道只读已到达的字节，不创建可能永久卡在 read() 的收集线程。
                int available = input.available();
                if (available > 0) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, available));
                    if (count > 0) {
                        bytes.write(buffer, 0, count);
                        if (onLine != null) {
                            for (int i = 0; i < count; i++) {
                                if (buffer[i] == '\n') {
                                    onLine.accept(new String(line.toByteArray(), StandardCharsets.UTF_8));
                                    line.reset();
                                } else if (line.size() < 16384) line.write(buffer[i]);
                            }
                        }
                        truncated = bytes.truncated();
                        continue; // 每个块都重新核对总期限，达到输出上限后继续排空管道。
                    }
                }
                Integer code = exitCode(process);
                if (code != null) {
                    // 进程可能在上一次 available() 后退出，再收一次尾部；不等继承管道的子进程。
                    if (input.available() > 0) continue;
                    if (onLine != null && line.size() > 0)
                        onLine.accept(new String(line.toByteArray(), StandardCharsets.UTF_8));
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
