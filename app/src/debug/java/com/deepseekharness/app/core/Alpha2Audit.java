package com.deepseekharness.app.core;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.SensitiveData;
import java.util.ArrayList;
import java.util.UUID;

/** 旧配置类型与连续六次启动回归；不发送模型请求，不改用户会话。 */
public final class Alpha2Audit extends Instrumentation {
    private int checks;
    private void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private void note(String text) { Bundle out = new Bundle(); out.putString("stream", text + "\n"); sendStatus(0, out); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); Context app = getTargetContext(); HarnessController controller = HarnessController.get(app);
        SharedPreferences fixture = app.getSharedPreferences("alpha2-config-" + UUID.randomUUID(), 0);
        boolean started = false;
        try {
            check(!controller.isWebRunning() && !controller.isStarting() && !RuntimeTasks.isBusy(), "实际 Web 或后台任务正在运行");
            Context isolated = new ContextWrapper(app) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) { return fixture; }
            };
            fixture.edit().putInt(Constants.KEY_PORT, 3087).putInt(Constants.KEY_AUTO_BACKUP, 5)
                    .putInt("backup_launch_count", 4).putString(Constants.KEY_DESKTOP_MODE, "true").commit();
            ConfigStore old = new ConfigStore(isolated);
            check(old.getPortInt() == 3087 && old.isDesktopMode(), "旧整型端口和字符串布尔未兼容");
            check(!old.exportBackupSettings().has("autoBackupLaunches"), "备份不应再导出自动启动备份选项");
            check(!fixture.contains(Constants.KEY_AUTO_BACKUP) && !fixture.contains("backup_launch_count"), "旧启动计数未清理");
            old.beginRestoreSettings(); old.setPort("3089"); old.finishRestoreSettings(true);
            check(fixture.getAll().get(Constants.KEY_PORT) instanceof Integer && old.getPortInt() == 3087, "回滚丢失原始数字类型");
            old.importBackupSettings(new org.json.JSONObject("{\"port\":3097,\"autoBackupLaunches\":5}"));
            check(old.getPortInt() == 3097 && !fixture.contains(Constants.KEY_AUTO_BACKUP), "旧备份重新启用了自动备份");
            note("旧配置类型、配置备份及恢复回滚通过");
            check(controller.isEnvironmentReady(), "请先完成新版运行时更新");
            long backup = controller.config().getLastBackupSuccess();
            runOnMainSync(() -> app.startActivity(new Intent(app, com.deepseekharness.app.ui.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            waitForIdleSync();
            ArrayList<Long> times = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                long began = SystemClock.elapsedRealtime();
                check(controller.startWeb(null), "启动请求未接受"); started = true;
                while (controller.getWebAuthUrl().isEmpty() && SystemClock.elapsedRealtime() - began < 60000) Thread.sleep(50);
                check(!controller.getWebAuthUrl().isEmpty(), "鉴权未就绪：" + controller.config().getWebFailureReason());
                long elapsed = SystemClock.elapsedRealtime() - began; times.add(elapsed);
                check(controller.config().getLastBackupSuccess() == backup, "连续启动不应创建自动备份");
                note("第 " + (i + 1) + " 次启动鉴权就绪：" + elapsed + "ms；未创建备份");
                BackupManager.stopWebForMaintenance(controller); started = false;
            }
            long began = SystemClock.elapsedRealtime();
            for (int i = 0; i < 20; i++) controller.proot().ensureRuntimeFiles();
            result.putLong("twenty_prepare_ms", SystemClock.elapsedRealtime() - began);
            result.putString("startup_ms", times.toString()); result.putString("result", "PASS"); result.putInt("checks", checks);
        } catch (Throwable error) { result.putString("failure", SensitiveData.redact(android.util.Log.getStackTraceString(error))); }
        finally {
            fixture.edit().clear().commit();
            if (started) try { BackupManager.stopWebForMaintenance(controller); } catch (Exception ignored) { }
            finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
        }
    }
}
