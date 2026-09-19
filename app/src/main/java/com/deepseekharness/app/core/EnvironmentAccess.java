package com.deepseekharness.app.core;

/** 主界面与执行入口共用恢复状态；进入界面不会写入环境就绪标记。 */
public final class EnvironmentAccess {
    private EnvironmentAccess() { }
    public static boolean needsRecovery(HarnessController controller) {
        return !controller.isEnvironmentReady()
                || com.deepseekharness.app.BackupManager.hasPendingMaintenance(controller);
    }
    public static boolean runtimeLatest(HarnessController controller){
        try{var installed=controller.proot().installedRuntimeDescriptor();return installed!=null&&installed.latest(controller.proot().expectedRuntimeDescriptor());}
        catch(java.io.IOException error){return false;}
    }
    /** 新受管候选只自动尝试一次；失败后仍允许使用通过健康确认的兼容前代。 */
    public static boolean shouldAttemptRuntimeUpdate(HarnessController controller){
        if(runtimeLatest(controller))return false;
        try{
            String candidate="runtime:"+controller.proot().expectedRuntimeDescriptor().id();
            return com.deepseekharness.app.util.EnvironmentIdentity.shouldBlockRuntimeStart(false,true,
                    com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy(),com.deepseekharness.app.BackupManager.hasPendingMaintenance(controller),candidate,
                    controller.context().getSharedPreferences("dsha_environment_upgrade",android.content.Context.MODE_PRIVATE).getString("attempted_identity",""));
        }catch(java.io.IOException error){
            // 不能认证 APK 自带的运行时描述时，不得由 START_STICKY/看门狗启动旧 Web。
            return com.deepseekharness.app.util.EnvironmentIdentity.shouldBlockRuntimeStart(false,false,false,false,"","");
        }
    }
}
