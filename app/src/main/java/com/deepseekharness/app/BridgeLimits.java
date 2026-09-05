package com.deepseekharness.app;

import java.io.IOException;
import java.io.Reader;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.*;

/** 本机回环也会收到其他 App 的连接；连接数、排队量、请求头大小必须有硬上限。 */
final class BridgeLimits {
    private BridgeLimits() { }

    static final class HeaderReader {
        private final Reader reader;
        private int remaining = 64 * 1024;
        private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        HeaderReader(Reader reader) { this.reader = reader; }
        String readLine() throws IOException {
            StringBuilder line = new StringBuilder();
            for (;;) {
                if (--remaining < 0 || line.length() > 16 * 1024 || System.nanoTime() > deadline) {
                    throw new IOException("请求头超出限制");
                }
                int c = reader.read();
                if (c == -1) return line.length() == 0 ? null : line.toString();
                if (c == '\n') return line.toString();
                if (c != '\r') line.append((char) c);
            }
        }
    }

    /** 无无界队列；停止时把运行中及排队中的 socket 一起关闭，解除阻塞 read。 */
    static final class Workers extends ThreadPoolExecutor {
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        Workers(int count, String name) {
            super(count, count, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> {
                Thread t = new Thread(r, name); t.setDaemon(true); return t;
            }, new AbortPolicy());
        }
        void submitSocket(Socket socket, Runnable job) {
            sockets.add(socket);
            try {
                execute(() -> {
                    try { job.run(); }
                    finally { close(socket); sockets.remove(socket); }
                });
            } catch (RejectedExecutionException fullOrStopped) {
                sockets.remove(socket);
                close(socket);
            }
        }
        @Override public java.util.List<Runnable> shutdownNow() {
            java.util.List<Runnable> queued = super.shutdownNow();
            for (Socket s : sockets) close(s);
            sockets.clear();
            return queued;
        }
        private static void close(Socket s) { try { s.close(); } catch (IOException ignored) { } }
    }
}
