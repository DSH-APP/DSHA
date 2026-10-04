package com.deepseekharness.app.util;

/** 备份、恢复、维护与安装共用的进程级原子任务凭据；获取不阻塞，也不执行停止操作。 */
public final class EnvironmentTaskGate {
  private EnvironmentTaskGate() {}

  private static Lease active;
  private static final ThreadLocal<Lease> current = new ThreadLocal<>();

  public static synchronized Lease tryAcquire(String kind) {
    if (active != null) return null;
    active = new Lease(kind);
    return active;
  }

  public static synchronized boolean isBusy() {
    return active != null;
  }

  public static synchronized String activeKind() {
    return active == null ? "" : active.kind;
  }

  public static synchronized boolean ownsCurrentThread() {
    return active != null && current.get() == active;
  }

  public static synchronized boolean canExecuteCurrentThread() {
    return ownsCurrentThread() && active.retained == null;
  }

  /** Keep the current exact task lease after returning to UI; never block the owner in an infinite retry loop. */
  public static void retainCurrentUntilExit(Process process) {
    Lease lease;
    synchronized (EnvironmentTaskGate.class) {
      if (!ownsCurrentThread()) throw new IllegalStateException("TASK_LEASE_REQUIRED");
      lease = active;
    }
    lease.retain(process);
  }

  public interface Operation<T> {
    T run() throws Exception;
  }

  public static final class Lease implements AutoCloseable {
    public final String kind;
    private Thread runner;
    private boolean closed;
    private Process retained;

    private Lease(String kind) {
      this.kind = kind == null ? com.deepseekharness.app.util.UiText.text("环境任务") : kind;
    }

    private void retain(Process process) {
      synchronized (EnvironmentTaskGate.class) {
        if (process == null || closed || active != this || current.get() != this)
          throw new IllegalStateException("TASK_PROCESS_REQUIRED");
        if (retained != null) {
          if (retained != process) throw new IllegalStateException("TASK_PROCESS_CHANGED");
          return;
        }
        retained = process;
      }
      Thread watcher =
          new Thread(
              () -> {
                for (; ; ) {
                  try {
                    synchronized (EnvironmentTaskGate.class) {
                      if (retained == process
                          && runner == null
                          && ProcessTermination.exited(process)) {
                        retained = null;
                        closed = true;
                        if (active == this) active = null;
                        return;
                      }
                    }
                    Thread.sleep(1000);
                  } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                  } catch (RuntimeException unknown) {
                    return;
                  } // Retain the lease when exit evidence itself is unavailable.
                }
              },
              "dsha-environment-exit");
      watcher.setDaemon(true);
      watcher.start();
    }

    /** 可先在 UI 获取再转交 worker；同一凭据不允许两个 worker 同时执行。 */
    public <T> T run(Operation<T> operation) throws Exception {
      synchronized (EnvironmentTaskGate.class) {
        if (closed || active != this || runner != null)
          throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("任务凭据已释放或正在使用"));
        runner = Thread.currentThread();
        current.set(this);
      }
      try {
        return operation.run();
      } finally {
        synchronized (EnvironmentTaskGate.class) {
          current.remove();
          runner = null;
        }
      }
    }

    @Override
    public void close() {
      synchronized (EnvironmentTaskGate.class) {
        if (closed) return;
        if (retained != null) return;
        if (runner != null)
          throw new IllegalStateException(
              com.deepseekharness.app.util.UiText.text("任务仍在执行，不能提前释放环境锁"));
        closed = true;
        if (active == this) active = null;
      }
    }
  }
}
