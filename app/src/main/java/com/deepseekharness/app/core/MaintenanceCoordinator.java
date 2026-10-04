package com.deepseekharness.app.core;

import android.os.Looper;
import android.os.SystemClock;
import com.deepseekharness.app.backup.HostMaintenancePending;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.MaintenanceGate;
import com.deepseekharness.app.util.RuntimeTaskRegistry;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.io.IOException;

/** Android wiring for the single process-wide maintenance gate. */
public final class MaintenanceCoordinator {
  private static final MaintenanceGate GATE = MaintenanceGate.shared();

  private MaintenanceCoordinator() {}

  public interface Operation<T> {
    T run() throws Exception;
  }

  public static Object archiveLock() {
    return GATE.archiveLock();
  }

  public static boolean isExclusive() {
    return GATE.isExclusive();
  }

  public static boolean isOwner() {
    return GATE.isOwner();
  }

  public static boolean isEnvironmentTaskBusy() {
    return GATE.isExclusive() || EnvironmentTaskGate.isBusy();
  }

  public static HostMaintenancePending.Check inspectPending(File filesDir) {
    return HostMaintenancePending.inspect(filesDir);
  }

  public static boolean pending(File filesDir) {
    return HostMaintenancePending.blocked(filesDir);
  }

  public static boolean pending(HarnessController controller) {
    return pending(controller.proot().getRootfsDir().getParentFile().getParentFile());
  }

  public static <T> T snapshot(HarnessController controller, Operation<T> operation)
      throws Exception {
    return GATE.snapshot(new AndroidPorts(controller), operation::run);
  }

  public static <T> T exclusive(HarnessController controller, Operation<T> operation)
      throws Exception {
    return GATE.exclusive(new AndroidPorts(controller), operation::run);
  }

  /** The stop barrier is shared by manual stop, data transactions and old callers. */
  public static void stopWeb(HarnessController controller) throws Exception {
    if (Thread.holdsLock(GATE.archiveLock()))
      throw new IOException(UiText.text("等待 Web 停止前必须释放归档锁"));
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IOException(UiText.text("请在独立数据任务线程等待 Web 停止，不能阻塞界面线程"));
    controller.stopWeb(message -> {});
    long deadline = SystemClock.elapsedRealtime() + 45_000;
    do {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedException(UiText.text("等待停止被中断"));
      if (!controller.isStarting() && !controller.isStopping()) {
        String error = controller.lastStopError();
        if (error != null && !error.isEmpty()) throw new IOException(error);
        if (controller.isWebStoppedForMaintenance()) return;
      }
      Thread.sleep(100);
    } while (SystemClock.elapsedRealtime() < deadline);
    throw new IOException(UiText.text("等待 Web 或它启动的后台进程退出超时；原环境未移动，请结束运行任务后重试"));
  }

  private static final class AndroidPorts implements MaintenanceGate.Ports {
    private final HarnessController controller;

    AndroidPorts(HarnessController controller) {
      this.controller = controller;
    }

    @Override
    public boolean ownsExecutionTicket() {
      return EnvironmentTaskGate.ownsCurrentThread();
    }

    @Override
    public MaintenanceGate.Ticket acquireExecutionTicket(String kind) {
      EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire(UiText.text(kind));
      if (lease == null) return null;
      return new MaintenanceGate.Ticket() {
        @Override
        public <T> T run(MaintenanceGate.Operation<T> operation) throws Exception {
          return lease.run(operation::run);
        }

        @Override
        public void close() {
          lease.close();
        }
      };
    }

    @Override
    public MaintenanceGate.Scope beginWork() {
      RuntimeTasks work = RuntimeTasks.begin("数据维护");
      return work::close;
    }

    @Override
    public void stopTerminals() throws Exception {
      if (Looper.myLooper() == Looper.getMainLooper())
        throw new IOException(UiText.text("请在维护任务线程等待终端退出"));
      TerminalSessionOwner.shared().closeAllAndConfirm(5000);
    }

    @Override
    public void stopWeb() throws Exception {
      MaintenanceCoordinator.stopWeb(controller);
    }

    @Override
    public MaintenanceGate.Scope tryEnterMaintenance() {
      RuntimeTaskRegistry.Maintenance fence = RuntimeTasks.tryEnterMaintenance();
      return fence == null ? null : fence::close;
    }

    @Override
    public void confirmOtherProcesses() throws IOException {
      com.deepseekharness.app.runtime.BoundedGuestSessions.reapExited(
          controller.context().getFilesDir());
    }

    @Override
    public void releaseRecoveryTools() {
      controller.proot().releaseRecoveryTools();
    }

    @Override
    public long elapsedRealtime() {
      return SystemClock.elapsedRealtime();
    }

    @Override
    public void pause(long millis) throws InterruptedException {
      Thread.sleep(millis);
    }
  }
}
