package com.deepseekharness.app.ui;

import android.content.Context;
import com.deepseekharness.app.core.HarnessController;

/** 仅用于显示的后台快照。真正执行入口仍重新校验磁盘和维护门禁。 */
final class EnvironmentUiStatus {
  static final class Snapshot {
    final boolean known, ready, recovery, repairs;

    Snapshot(boolean known, boolean ready, boolean recovery, boolean repairs) {
      this.known = known;
      this.ready = ready;
      this.recovery = recovery;
      this.repairs = repairs;
    }
  }

  private static volatile Snapshot value = new Snapshot(false, false, false, false);
  private static final java.util.concurrent.ExecutorService IO =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  private static long requested;
  private static boolean running;

  static synchronized Snapshot get(Context context) {
    long now = android.os.SystemClock.elapsedRealtime();
    if (!running && (requested == 0 || now - requested >= 2500)) {
      running = true;
      requested = now;
      Context app = context.getApplicationContext();
      IO.execute(
          () -> {
            try {
              HarnessController controller = HarnessController.get(app);
              boolean ready = controller.isEnvironmentReady();
              value =
                  new Snapshot(
                      true,
                      ready,
                      // 受限提示与主界面入口同一判定：进行中的后台事务由维护横幅表达；
                      // 补丁级覆盖后旧树仍“兼容”，但同样要留出回到维护页的入口。
                      com.deepseekharness.app.util.RuntimeUpgradeState.maintenanceRequired(
                              ready,
                              com.deepseekharness.app.core.EnvironmentAccess.runtimeLatest(
                                  controller))
                          || MainEntryMaintenance.blocksEntry(controller),
                      com.deepseekharness.app.core.StartupRepairs.pending(app));
            } finally {
              synchronized (EnvironmentUiStatus.class) {
                running = false;
              }
            }
          });
    }
    return value;
  }
}
