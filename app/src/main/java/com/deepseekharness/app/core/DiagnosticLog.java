package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** 只记录应用操作阶段及脱敏结果；不采集模型消息、终端输入或配置全文。 */
public final class DiagnosticLog {
    private static final Object LOCK = new Object();
    private DiagnosticLog() { }
    public static void record(Context context, String stage, String message) {
        synchronized (LOCK) {
            try {
                String safe = SensitiveData.redact(String.valueOf(message)).replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
                if (safe.length() > 800) safe = safe.substring(0, 800) + "…";
                File file = new File(context.getFilesDir(), "diagnostic-events.txt");
                String old = file.isFile() ? new String(com.deepseekharness.app.util.Compat.readAllBytes(file), StandardCharsets.UTF_8) : "";
                if (old.length() > 24000) old = old.substring(old.indexOf('\n', old.length() - 18000) + 1);
                String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date())
                        + " [" + stage + "] " + safe.replace('\n', ' ') + "\n";
                com.deepseekharness.app.util.Compat.write(file, (old + line).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
    }
    public static String read(Context context) {
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), "diagnostic-events.txt");
                return file.isFile() ? SensitiveData.redact(com.deepseekharness.app.util.TextLogTail.read(file, 32 * 1024)) : "暂无失败或操作记录\n";
            } catch (Exception e) { return "无法读取操作记录\n"; }
        }
    }
    public static void installCrashHandler(Context context) {
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            StringBuilder summary = new StringBuilder(error.getClass().getName());
            StackTraceElement[] trace = error.getStackTrace();
            for (int i = 0; i < Math.min(12, trace.length); i++) summary.append("\n at ").append(trace[i]);
            record(app, "CRASH", summary.toString());
            if (previous != null) previous.uncaughtException(thread, error);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
        });
    }
}
