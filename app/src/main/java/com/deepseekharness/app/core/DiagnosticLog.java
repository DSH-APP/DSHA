package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** 只记录应用操作阶段及脱敏结果；不采集模型消息、终端输入或配置全文。 */
public final class DiagnosticLog {
  private static final Object LOCK = new Object();

  private DiagnosticLog() {}

  public static void record(Context context, String stage, String message) {
    synchronized (LOCK) {
      try {
        String safe =
            SensitiveData.redact(String.valueOf(message)).replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
        if (safe.length() > 800) safe = safe.substring(0, 800) + "…";
        var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
        File folder =
            com.deepseekharness.app.util.ColdInstallPackages.privateFiles(
                fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
        String line =
            new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)
                    .format(new java.util.Date())
                + " ["
                + stage.replace('\n', ' ')
                + "] "
                + safe.replace('\n', ' ')
                + "\n";
        DiagnosticHistory.write(fs, folder, line);
      } catch (Exception ignored) {
      }
    }
  }

  public static String read(Context context) {
    synchronized (LOCK) {
      try {
        var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
        File folder =
            com.deepseekharness.app.util.ColdInstallPackages.privateFiles(
                fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
        String value = DiagnosticHistory.read(fs, folder);
        return value.isEmpty()
            ? com.deepseekharness.app.util.UiText.text("暂无失败或操作记录\n")
            : SensitiveData.redact(value);
      } catch (Exception e) {
        return com.deepseekharness.app.util.UiText.text("无法读取操作记录\n");
      }
    }
  }

  public static void installCrashHandler(Context context) {
    Context app = context.getApplicationContext();
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          StringBuilder summary = new StringBuilder(error.getClass().getName());
          StackTraceElement[] trace = error.getStackTrace();
          for (int i = 0; i < Math.min(12, trace.length); i++)
            summary.append("\n at ").append(trace[i]);
          record(app, "CRASH", summary.toString());
          if (previous != null) previous.uncaughtException(thread, error);
          else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
          }
        });
  }
}
