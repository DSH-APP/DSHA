package com.deepseekharness.app;

import android.content.Context;
import android.util.Base64;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Root 直接通道；仅在用户开启开关并执行设备操作时请求 su，不需要 ADB。 */
public final class RootShell {
    private static volatile String lastStatus = com.deepseekharness.app.util.UiText.text("尚未验证 root 授权");
    private RootShell() { }
    public static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(Constants.PREFS, 0).getBoolean(Constants.KEY_ALLOW_ROOT_SHELL, false);
    }
    private static String executable() {
        for (String path : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"})
            if (new File(path).canExecute()) return path;
        return null;
    }
    public static boolean present() { return executable() != null; }
    public static String status(Context ctx) {
        return !enabled(ctx) ? com.deepseekharness.app.util.UiText.text("未启用 · 开启后可直接申请 root 授权")
                : !present() ? com.deepseekharness.app.util.UiText.text("未找到 su，可使用 Shizuku 或 ADB 通道") : lastStatus;
    }
    public static String exec(Context ctx, String command, int authorizedSmsUser) {
        if (!enabled(ctx)) return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
        DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect(command);
        if (!plan.allowed()) return plan.reason + "\n[EXIT=126]";
        if (plan.kind == DeviceShellPolicy.Kind.SENSITIVE_READ && (authorizedSmsUser < 0 || !new com.deepseekharness.app.core.DeviceGrants(ctx).smsReadAllowed()))
            return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 短信读取尚未经过原生授权\n[EXIT=126]");
        String su = executable();
        if (su == null) return com.deepseekharness.app.util.UiText.text("[ROOT_UNAVAILABLE] 未找到 su\n[EXIT=124]");
        Process process = null;
        try {
            // 命令通过标准输入传递；su 的 shell 参数只含固定入口与已安装 APK 路径。
            String entry = "CLASSPATH=" + ShellQuote.arg(ctx.getApplicationInfo().sourceDir)
                    + " /system/bin/app_process /system/bin " + RootShellMain.class.getName();
            process = new ProcessBuilder(su, "-c", entry).redirectErrorStream(true).start();
            try (java.io.OutputStream input = process.getOutputStream()) {
                input.write(new org.json.JSONObject().put("command", command).put("smsUser", authorizedSmsUser)
                        .toString().getBytes(StandardCharsets.UTF_8));
            }
            BoundedProcessRunner.Result result = BoundedProcessRunner.collect(process, 65_000, 400_000, Compat::destroy);
            int marker = result.output.lastIndexOf(RootShellMain.RESULT);
            if (!result.timedOut && !result.truncated && marker >= 0) {
                String encoded = result.output.substring(marker + RootShellMain.RESULT.length()).trim();
                String output = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                lastStatus = com.deepseekharness.app.util.UiText.text("root 通道已响应 · 设备命令仍受策略保护");
                return output;
            }
            lastStatus = result.timedOut ? com.deepseekharness.app.util.UiText.text("root 授权或执行超时，请在 root 管理器查看 DSHA 授权")
                    : com.deepseekharness.app.util.UiText.text("root 授权或执行失败：") + SensitiveData.redact(result.output.trim());
            return "[EXECUTION_UNKNOWN] " + lastStatus + com.deepseekharness.app.util.UiText.text("。未取得完整结果，不会自动重试\n[EXIT=125]");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return com.deepseekharness.app.util.UiText.text("[EXECUTION_UNKNOWN] root 请求已中断，不会自动重试\n[EXIT=125]");
        } catch (Throwable e) {
            lastStatus = com.deepseekharness.app.util.UiText.text("root 通道失败：") + SensitiveData.redact(String.valueOf(e));
            return "[EXECUTION_UNKNOWN] " + lastStatus + "\n[EXIT=125]";
        } finally {
            if (process != null) {
                try { process.exitValue(); } catch (IllegalThreadStateException running) { Compat.destroy(process); }
                try { process.getOutputStream().close(); } catch (java.io.IOException ignored) { }
                try { process.getInputStream().close(); } catch (java.io.IOException ignored) { }
                try { process.getErrorStream().close(); } catch (java.io.IOException ignored) { }
            }
        }
    }
}
