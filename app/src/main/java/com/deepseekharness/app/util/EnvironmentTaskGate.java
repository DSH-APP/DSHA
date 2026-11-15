package com.deepseekharness.app.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 备份、恢复、维护与安装共用的进程级原子任务凭据；获取不阻塞，也不执行停止操作。
 *
 * <p>独占租约用于会切换或替换共享环境的操作；共享租约用于插件只读查询、DSH 启动和终端创建等
 * 可以并存的正常使用。维护/安装/备份取得独占租约时，已有共享使用者必须先退出。
 */
public final class EnvironmentTaskGate {
  private EnvironmentTaskGate() {}

  private static Lease active;
  private static final Map<Lease, Boolean> sharedLeases = new LinkedHashMap<>();
  private static final ThreadLocal<Lease> current = new ThreadLocal<>();

  /** 独占租约；已有独占或共享使用者时拒绝，不等待。 */
  public static synchronized Lease tryAcquire(String kind) {
    if (active != null || !sharedLeases.isEmpty()) return null;
    active = new Lease(kind, false);
    return active;
  }

  /** 共享租约；只与独占租约互斥，多个共享使用者可以并存。 */
  public static synchronized Lease tryAcquireShared(String kind) {
    if (active != null) return null;
    Lease lease = new Lease(kind, true);
    sharedLeases.put(lease, Boolean.TRUE);
    return lease;
  }

  /** 仅表示是否有独占环境任务；普通共享使用不再显示为“环境忙”。 */
  public static synchronized boolean isBusy() {
    return active != null;
  }

  public static synchronized boolean hasSharedUse() {
    return !sharedLeases.isEmpty();
  }

  public static synchronized int sharedCount() {
    return sharedLeases.size();
  }

  public static synchronized String sharedKinds() {
    StringBuilder result = new StringBuilder();
    for (Lease lease : sharedLeases.keySet()) {
      if (result.length() > 0) result.append("、");
      result.append(lease.kind);
    }
    return result.toString();
  }

  public static synchronized String activeKind() {
    return active == null ? "" : active.kind;
  }

  /** 当前线程是否在任一租约中；共享租约内的只读来源解析需要这个判断。 */
  public static synchronized boolean ownsCurrentThread() {
    return current.get() != null;
  }

  /** 当前线程是否持有独占租约；维护凭据、ADB 与进程保留只能使用这个判断。 */
  public static synchronized boolean ownsExclusiveCurrentThread() {
    Lease lease = current.get();
    return lease != null && !lease.shared;
  }

  public static synchronized boolean canExecuteCurrentThread() {
    Lease lease = current.get();
    return lease != null && !lease.shared && active == lease && active.retained == null;
  }

  /** Keep the current exact exclusive task lease after returning to UI. */
  public static void retainCurrentUntilExit(Process process) {
    Lease lease;
    synchronized (EnvironmentTaskGate.class) {
      if (!ownsExclusiveCurrentThread()) throw new IllegalStateException("TASK_LEASE_REQUIRED");
      lease = active;
    }
    lease.retain(process);
  }

  public interface Operation<T> {
    T run() throws Exception;
  }

  public static final class Lease implements AutoCloseable {
    public final String kind;
    private final boolean shared;
    private Thread runner;
    private boolean closed;
    private Process retained;

    private Lease(String kind, boolean shared) {
      this.kind = kind == null ? com.deepseekharness.app.util.UiText.text("环境任务") : kind;
      this.shared = shared;
    }

    private void retain(Process process) {
      synchronized (EnvironmentTaskGate.class) {
        if (shared || process == null || closed || active != this || current.get() != this)
          throw new IllegalStateException("TASK_PROCESS_REQUIRED");
        if (retained != null) {
          if (retained != process) throw new IllegalStateException("TASK_PROCESS_CHANGED");
          return;
        }
        retained = process;
      }
      Thread watcher = new Thread(() -> waitForRetainedExit(process), "dsha-environment-exit");
      watcher.setDaemon(true);
      watcher.start();
    }

    private void waitForRetainedExit(Process process) {
      // Read only this exact Process, outside the global gate monitor. A transient failed probe or
      // interrupted wait keeps the lease and the observer; it never authorizes a retry or signal.
      RetainedProcessObserver.awaitExit(process, null);
      boolean interrupted = Thread.interrupted();
      try {
        while (true) {
          synchronized (EnvironmentTaskGate.class) {
            if (retained != process) return;
            // Process exit is monotonic, but the original Java operation may still be unwinding.
            // Both facts are required, and a late old observer cannot release a newer lease.
            if (runner == null) {
              retained = null;
              closed = true;
              if (active == this) active = null;
              return;
            }
          }
          try {
            Thread.sleep(1000);
          } catch (InterruptedException retry) {
            interrupted = true;
          }
        }
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }

    /** 可先在 UI 获取再转交 worker；同一凭据不允许两个 worker 同时执行。 */
    public <T> T run(Operation<T> operation) throws Exception {
      Lease previous;
      synchronized (EnvironmentTaskGate.class) {
        if (closed || runner != null)
          throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("任务凭据已释放或正在使用"));
        if (shared ? !sharedLeases.containsKey(this) : active != this)
          throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("任务凭据已释放或正在使用"));
        previous = current.get();
        runner = Thread.currentThread();
        current.set(this);
      }
      try {
        return operation.run();
      } finally {
        synchronized (EnvironmentTaskGate.class) {
          if (previous == null) current.remove();
          else current.set(previous);
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
        if (shared) sharedLeases.remove(this);
        else if (active == this) active = null;
      }
    }
  }
}
