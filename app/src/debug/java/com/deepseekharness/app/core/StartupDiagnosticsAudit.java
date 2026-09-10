package com.deepseekharness.app.core;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 真机验证普通/安全启动、时间线和原配置保留；不调用模型。 */
public final class StartupDiagnosticsAudit extends Instrumentation {
    private int checks;
    private Bundle args;
    private final StringBuilder audit = new StringBuilder();
    private void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private void note(String text) {
        audit.append(SensitiveData.redact(text)).append('\n');
        Bundle out = new Bundle();
        out.putString("stream", SensitiveData.redact(text.contains("_TRACE")
                ? text.lines().filter(line -> line.contains("_TRACE") || line.contains("耗时") || line.contains("CACHED")).collect(java.util.stream.Collectors.joining("\n")) : text) + "\n");
        sendStatus(0, out);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args = args; start(); }
    private Activity openPage(HarnessController controller, String cookie) throws Exception {
        // low 的 WebPreviewActivity 会立即转交 Gecko 并 finish；不能用 startActivitySync 等待它 idle。
        String expected = "true".equals(args.getString("gecko"))
                ? "com.deepseekharness.app.ui.GeckoPreviewActivity" : "com.deepseekharness.app.ui.WebPreviewActivity";
        ActivityMonitor monitor = addMonitor(expected, null, false);
        try {
            runOnMainSync(() -> getTargetContext().startActivity(com.deepseekharness.app.ui.WebPreviewActivity.intent(
                    getTargetContext(), controller.getWebAuthUrl(), cookie).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)));
            Activity opened = waitForMonitorWithTimeout(monitor, 15000);
            check(opened != null, "预览入口没有打开 " + expected);
            return opened;
        } finally { removeMonitor(monitor); }
    }
    private void foreground() throws Exception {
        try (android.os.ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(
                "am start -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
             java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024]; while (in.read(buffer) != -1) { }
        }
        Thread.sleep(600);
    }
    private void awaitAuth(HarnessController c, boolean safe) throws Exception {
        long began = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - began < 90000) {
            if (c.startupDiagnostics().snapshot().safe == safe && !c.getWebAuthUrl().isEmpty()) {
                note((safe ? "SAFE_AUTH " : "NORMAL_AUTH ") + (SystemClock.elapsedRealtime()-began) + "ms"); return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("测试等待结束（仅停止测试）：" + c.startupDiagnostics().snapshot().log);
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); HarnessController c = HarnessController.get(getTargetContext());
        android.content.SharedPreferences prefs = getTargetContext().getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, 0);
        boolean hadGecko = prefs.contains(com.deepseekharness.app.util.Constants.KEY_GECKO_CORE);
        boolean oldGecko = Boolean.parseBoolean(args.getString("restore_gecko",
                String.valueOf(prefs.getBoolean(com.deepseekharness.app.util.Constants.KEY_GECKO_CORE, false))));
        Activity page = null;
        try {
            foreground();
            if ("true".equals(args.getString("gecko"))) prefs.edit().putBoolean(com.deepseekharness.app.util.Constants.KEY_GECKO_CORE, true).commit();
            check(c.isEnvironmentReady(), "环境尚未就绪");
            BackupManager.stopWebForMaintenance(c);
            check(c.startWeb(null), "普通启动未入队"); awaitAuth(c, false);
            var first = c.startupDiagnostics().snapshot();
            check(first.log.contains("配置检查："), "缺少实际插件清单");
            check(first.log.contains("耗时"), "缺少阶段耗时");
            check(first.issues.isEmpty(), "普通启动出现异常：" + first.issues);
            note("NORMAL_TRACE\n" + first.log);
            String cookie = c.exchangeDshAuthCookie(); check(cookie != null, "鉴权失败");
            page = openPage(c, cookie);
            long began = SystemClock.elapsedRealtime();
            while (!c.startupDiagnostics().snapshot().browserReady && SystemClock.elapsedRealtime()-began < 45000) Thread.sleep(100);
            check(c.startupDiagnostics().snapshot().browserReady, "网页未报告就绪：" + c.startupDiagnostics().snapshot().log);
            note("BROWSER_READY " + (SystemClock.elapsedRealtime()-began) + "ms");
            Activity closing = page; runOnMainSync(closing::finish); page = null;
            File manifest = new File(c.proot().getRootfsDir(), "root/.dsh/profiles/web/package.json");
            byte[] original = Compat.readAllBytes(manifest);
            // 在正在启动时发恢复请求，验证此前按钮无效的路径。
            BackupManager.stopWebForMaintenance(c);
            check(c.startWeb(null), "复现慢启动请求未入队");
            c.recoverWeb(true, null, null); awaitAuth(c, true);
            check(Arrays.equals(original, Compat.readAllBytes(manifest)), "安全启动改写了原插件清单");
            check(c.startupDiagnostics().snapshot().issues.isEmpty(), "安全启动异常");
            note("SAFE_TRACE\n" + c.startupDiagnostics().snapshot().log);
            cookie = c.exchangeDshAuthCookie(); check(cookie != null, "安全界面鉴权失败");
            foreground();
            page = openPage(c, cookie);
            began = SystemClock.elapsedRealtime();
            while (!c.startupDiagnostics().snapshot().browserReady && SystemClock.elapsedRealtime()-began < 45000) Thread.sleep(100);
            check(c.startupDiagnostics().snapshot().browserReady, "安全网页未报告就绪");
            note("SAFE_BROWSER_READY " + page.getClass().getSimpleName());
            Activity safePage = page; runOnMainSync(safePage::finish); page = null;
            BackupManager.stopWebForMaintenance(c);
            check(c.isWebStoppedForMaintenance(), "安全进程未按 PID 停止");
            check(c.startWeb(null), "普通恢复未入队"); awaitAuth(c, false);
            check(c.startupDiagnostics().snapshot().log.contains("RUNTIME_LINKS_CACHED"), "未命中依赖链接缓存");
            check(Arrays.equals(original, Compat.readAllBytes(manifest)), "普通恢复改写原插件清单");
            note("WARM_TRACE\n" + c.startupDiagnostics().snapshot().log);
            foreground();
            Thread.sleep(1200);
            android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(getTargetContext().getCacheDir(), "startup-launch.png"))) {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
                screenshot.recycle();
            }
            result.putString("result", "PASS"); result.putInt("checks", checks);
        } catch (Throwable error) {
            result.putString("failure", SensitiveData.redact(android.util.Log.getStackTraceString(error)));
        } finally {
            if (page != null) { Activity closing = page; runOnMainSync(closing::finish); }
            try {
                BackupManager.stopWebForMaintenance(c);
                if (!c.isWebStoppedForMaintenance()) throw new AssertionError("最终停止未完成");
            } catch (Throwable error) { result.putString("failure", "最终停止：" + SensitiveData.redact(String.valueOf(error))); }
            try {
                Compat.write(new File(getTargetContext().getCacheDir(), "startup-diagnostics-last.log"),
                        audit.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
            android.content.SharedPreferences.Editor restore = prefs.edit();
            if (hadGecko) restore.putBoolean(com.deepseekharness.app.util.Constants.KEY_GECKO_CORE, oldGecko);
            else restore.remove(com.deepseekharness.app.util.Constants.KEY_GECKO_CORE);
            restore.commit();
            finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
        }
    }
}
