package com.deepseekharness.app.core;

/** 主界面与执行入口共用恢复状态；进入界面不会写入环境就绪标记。 */
public final class EnvironmentAccess {
    private EnvironmentAccess() { }
    public static boolean needsRecovery(HarnessController controller) {
        return !controller.isEnvironmentReady()
                || com.deepseekharness.app.BackupManager.hasPendingMaintenance(controller);
    }
}
