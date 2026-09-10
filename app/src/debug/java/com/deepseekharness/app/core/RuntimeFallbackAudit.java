package com.deepseekharness.app.core;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.SensitiveData;

/** 真退出码触发一次兼容启动；仅替换测试创建的第一轮进程，后续运行真实 proot。 */
public final class RuntimeFallbackAudit extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); android.content.Context app = getTargetContext();
        final int[] failures = {0};
        ProotBootstrap bootstrap = new ProotBootstrap(app) {
            @Override public Process execRootfs(String command) throws java.io.IOException {
                if (command.contains("exec dsh ")) {
                    failures[0]++;
                    return new ProcessBuilder("/system/bin/sh", "-c", "printf 'DSHA_TEST_PROROOT_EXIT\\n'; exit 139")
                            .redirectErrorStream(true).start();
                }
                return super.execRootfs(command);
            }
        };
        HarnessController controller = new HarnessController(app, bootstrap);
        try {
            if (!controller.config().isProroot()) throw new AssertionError("请在使用 proroot 的测试机运行");
            BackupManager.stopWebForMaintenance(controller);
            long began = SystemClock.elapsedRealtime();
            if (!controller.startWeb(null)) throw new AssertionError("启动请求未接受");
            while (controller.getWebAuthUrl().isEmpty() && SystemClock.elapsedRealtime()-began < 120000) Thread.sleep(100);
            if (controller.getWebAuthUrl().isEmpty()) throw new AssertionError(controller.startupDiagnostics().snapshot().log);
            if (failures[0] != 1 || !controller.isWebCompatibilityFallback()) throw new AssertionError("没有单次切换 proot");
            if (!controller.config().isProroot()) throw new AssertionError("重试修改了用户设置");
            if (!StartupDiagnostics.lastFailure(app).contains("139")) throw new AssertionError("上次失败输出被成功重试覆盖");
            result.putString("result", "PASS"); result.putLong("fallback_auth_ms", SystemClock.elapsedRealtime()-began);
            result.putString("trace", controller.startupDiagnostics().snapshot().log);
        } catch (Throwable error) { result.putString("failure", SensitiveData.redact(android.util.Log.getStackTraceString(error))); }
        finally {
            try {
                BackupManager.stopWebForMaintenance(controller);
                if (!controller.isWebStoppedForMaintenance()) throw new AssertionError("兼容进程停止未完成");
            } catch (Throwable error) { result.putString("failure", "最终停止：" + SensitiveData.redact(String.valueOf(error))); }
            finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
        }
    }
}
