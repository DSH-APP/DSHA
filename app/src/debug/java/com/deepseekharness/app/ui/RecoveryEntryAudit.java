package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import java.io.File;
import java.util.function.BooleanSupplier;

/** 临时移开就绪标记验证恢复入口；持有维护互斥凭据，finally 恢复原标记，不触碰用户内容。 */
public final class RecoveryEntryAudit extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private void until(BooleanSupplier ready, String reason) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (!ready.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(50);
        check(ready.getAsBoolean(), reason);
    }
    private void ui(Runnable action) throws Exception {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new Exception("界面检查失败", failure[0]);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        HarnessController controller = HarnessController.get(getTargetContext());
        File marker = new File(controller.proot().getRootfsDir().getParentFile(), ".offline-extracted");
        File saved = new File(marker.getParentFile(), ".rc11-audit-ready-marker");
        var prefs = getTargetContext().getSharedPreferences(Constants.PREFS, 0);
        String original = prefs.getString("limited_entry_identity", null);
        boolean moved = false;
        EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("恢复入口验证");
        try {
            check(lease != null && !RuntimeTasks.isBusy() && !controller.isWebRunning() && !controller.isStarting(), "需结束运行任务再验证恢复入口");
            check(controller.isEnvironmentReady() && !saved.exists(), "只在现有健康环境验证，禁止覆盖旧测试标记");
            try (android.os.ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(
                    "am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
                 java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) { while (input.read() != -1) { } }
            check(marker.renameTo(saved), "临时保留原就绪标记"); moved = true;
            check(!controller.isEnvironmentReady(), "标记缺失时不能判断为就绪");
            Activity screen = startActivitySync(new Intent(getTargetContext(), ExtractActivity.class)
                    .putExtra("review_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            var field = ExtractActivity.class.getDeclaredField("enter"); field.setAccessible(true);
            android.widget.Button enter = (android.widget.Button) field.get(screen);
            check(enter.isEnabled() && enter.getVisibility() == View.VISIBLE, "维护未完成仍有进入主界面入口");
            MainActivity before = MainActivity.current;
            ui(enter::performClick);
            until(() -> MainActivity.current != null && MainActivity.current != before && !MainActivity.current.isDestroyed()
                    && MainActivity.current.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                    && MainActivity.current.findViewById(R.id.environment_recovery_banner) != null
                    && MainActivity.current.findViewById(R.id.environment_recovery_banner).getVisibility() == View.VISIBLE, "受限主界面展示恢复状态");
            check(!marker.exists(), "进入主界面不能伪造就绪标记");
            until(() -> MainActivity.current.findViewById(R.id.launch_start) != null && !MainActivity.current.findViewById(R.id.launch_start).isEnabled(), "Web 启动仍被门禁保护");
            var nav = (com.google.android.material.bottomnavigation.BottomNavigationView) MainActivity.current.findViewById(R.id.bottom_nav);
            for (int target : new int[]{R.id.nav_plugins, R.id.nav_terminal}) {
                ui(() -> nav.setSelectedItemId(target));
                until(() -> MainActivity.current.getSupportFragmentManager().findFragmentById(R.id.fragment_container) instanceof EnvironmentRecoveryFragment, "插件和终端展示恢复入口");
            }
            ui(() -> nav.setSelectedItemId(R.id.nav_settings));
            until(() -> MainActivity.current.findViewById(R.id.settings_tabs) != null, "受限状态可进入设置");
            boolean denied = false;
            try { controller.proot().requireUserRuntime(); } catch (java.io.IOException expected) { denied = true; }
            check(denied, "底层执行入口仍拒绝未就绪环境");
            result.putString("result", "PASS"); result.putInt("checks", checks);
        } catch (Throwable error) { result.putString("result", "FAIL: " + error); }
        finally {
            if (moved && !saved.renameTo(marker)) result.putString("result", "FAIL: 原标记仍在 " + saved);
            var edit = prefs.edit(); if (original == null) edit.remove("limited_entry_identity"); else edit.putString("limited_entry_identity", original); edit.commit();
            if (moved && !controller.isEnvironmentReady()) result.putString("result", "FAIL: 原标记恢复核验失败");
            if (lease != null) lease.close();
        }
        finish("PASS".equals(result.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
