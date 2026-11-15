package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** 不主动读取业务正文；自由格式操作错误仍可能引用业务内容，导出前须检查。 */
public final class DiagnosticLog {
  private static final Object LOCK = new Object();

  private DiagnosticLog() {}

  public static void record(Context context, String stage, String message) {
    synchronized (LOCK) {
      try {
        String safe =
            com.deepseekharness.app.util.DiagnosticOperationSummary.safe(String.valueOf(message))
                .replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
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
    try {
      String value = readExportTail(context);
      return value.isEmpty()
          ? com.deepseekharness.app.util.UiText.text("暂无失败或操作记录\n")
          : com.deepseekharness.app.util.LogCompactor.compact(value, 8000);
    } catch (java.io.IOException | RuntimeException error) {
      return com.deepseekharness.app.util.UiText.text("无法读取操作记录\n");
    }
  }

  /** Bounded original operation records, without display-only clue/context duplication. */
  public static String readExportTail(Context context) throws java.io.IOException {
    synchronized (LOCK) {
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      File folder =
          com.deepseekharness.app.util.ColdInstallPackages.privateFiles(
              fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
      return exportTail(fs, folder);
    }
  }

  static String exportTail(com.deepseekharness.app.backup.BackupFileSystem fs, File folder)
      throws java.io.IOException {
    return com.deepseekharness.app.util.DiagnosticOperationSummary.history(
        DiagnosticHistory.read(fs, folder));
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
