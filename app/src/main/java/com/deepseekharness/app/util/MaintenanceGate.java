package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Platform-independent ordering and ownership of host data maintenance. */
public final class MaintenanceGate {
  private static final MaintenanceGate SHARED = new MaintenanceGate();
  private final Object archiveLock = new Object();
  private final AtomicBoolean exclusive = new AtomicBoolean();
  private final ThreadLocal<Boolean> owner = new ThreadLocal<>();

  public static MaintenanceGate shared() {
    return SHARED;
  }

  public Object archiveLock() {
    return archiveLock;
  }

  public boolean isExclusive() {
    return exclusive.get();
  }

  public boolean isOwner() {
    return Boolean.TRUE.equals(owner.get());
  }

  public interface Operation<T> {
    T run() throws Exception;
  }

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  public interface Ticket extends Scope {
    <T> T run(Operation<T> operation) throws Exception;
  }

  public interface Ports {
    boolean ownsExecutionTicket();

    Ticket acquireExecutionTicket(String kind) throws Exception;

    Scope beginWork();

    void stopTerminals() throws Exception;

    void stopWeb() throws Exception;

    Scope tryEnterMaintenance();

    void confirmOtherProcesses() throws Exception;

    void releaseRecoveryTools();

    long elapsedRealtime();

    void pause(long millis) throws InterruptedException;
  }

  public <T> T snapshot(Ports ports, Operation<T> operation) throws Exception {
    if (!ports.ownsExecutionTicket()) {
      Ticket ticket = ports.acquireExecutionTicket("数据快照");
      if (ticket == null) throw new IOException(UiText.text("有安装、备份、恢复或维护任务正在进行"));
      try (ticket) {
        return ticket.run(() -> snapshot(ports, operation));
      }
    }
    synchronized (archiveLock) {
      try (Scope work = ports.beginWork()) {
        return operation.run();
      }
    }
  }

  public <T> T exclusive(Ports ports, Operation<T> operation) throws Exception {
    if (Thread.holdsLock(archiveLock))
      throw new IOException(UiText.text("不能在快照或归档回调内停止 Web；请先结束快照，再开始恢复或维护"));
    if (!ports.ownsExecutionTicket()) {
      Ticket ticket = ports.acquireExecutionTicket("数据维护");
      if (ticket == null) throw new IOException(UiText.text("有安装、备份、恢复或维护任务正在进行"));
      try (ticket) {
        return ticket.run(() -> exclusive(ports, operation));
      }
    }
    if (!exclusive.compareAndSet(false, true))
      throw new IOException(UiText.text("已有备份、恢复或维护任务，请等待完成"));
    try {
      ports.stopTerminals();
      ports.stopWeb();
      Scope fence = ports.tryEnterMaintenance();
      long deadline = ports.elapsedRealtime() + 3000;
      while (fence == null && ports.elapsedRealtime() < deadline) {
        ports.pause(50);
        fence = ports.tryEnterMaintenance();
      }
      if (fence == null)
        throw new IOException(UiText.text("Web 已停止，但终端或后台任务仍在运行。请结束这些任务后重试；原环境未移动，数据未覆盖。"));
      try (Scope held = fence) {
        ports.confirmOtherProcesses();
        synchronized (archiveLock) {
          owner.set(true);
          try (Scope work = ports.beginWork()) {
            return operation.run();
          } finally {
            try {
              ports.releaseRecoveryTools();
            } finally {
              owner.remove();
            }
          }
        }
      }
    } finally {
      exclusive.set(false);
    }
  }
}
