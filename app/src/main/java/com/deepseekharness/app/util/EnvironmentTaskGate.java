package com.deepseekharness.app.util;

/** 备份、恢复、维护与安装共用的进程级原子任务凭据；获取不阻塞，也不执行停止操作。 */
public final class EnvironmentTaskGate {
    private EnvironmentTaskGate() { }
    private static Lease active;
    private static final ThreadLocal<Lease> current = new ThreadLocal<>();
    public static synchronized Lease tryAcquire(String kind) {
        if (active != null) return null;
        active = new Lease(kind); return active;
    }
    public static synchronized boolean isBusy() { return active != null; }
    public static synchronized String activeKind() { return active == null ? "" : active.kind; }
    public static synchronized boolean ownsCurrentThread() { return active != null && current.get() == active; }
    public interface Operation<T> { T run() throws Exception; }
    public static final class Lease implements AutoCloseable {
        public final String kind;
        private Thread runner;
        private boolean closed;
        private Lease(String kind) { this.kind = kind == null ? "环境任务" : kind; }
        /** 可先在 UI 获取再转交 worker；同一凭据不允许两个 worker 同时执行。 */
        public <T> T run(Operation<T> operation) throws Exception {
            synchronized (EnvironmentTaskGate.class) {
                if (closed || active != this || runner != null) throw new IllegalStateException("任务凭据已释放或正在使用");
                runner = Thread.currentThread(); current.set(this);
            }
            try { return operation.run(); }
            finally {
                synchronized (EnvironmentTaskGate.class) { current.remove(); runner = null; }
            }
        }
        @Override public void close() {
            synchronized (EnvironmentTaskGate.class) {
                if (closed) return;
                if (runner != null) throw new IllegalStateException("任务仍在执行，不能提前释放环境锁");
                closed = true;
                if (active == this) active = null;
            }
        }
    }
}
