package com.deepseekharness.app.core;

import android.app.Application;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.data.DownloadsExport;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 错误日志下载独立于环境探针：Web 无法启动、环境维护中也能生成文件。 */
public final class ErrorLogRepository extends AndroidViewModel {
  private static final java.util.concurrent.ExecutorService IO =
      Executors.newSingleThreadExecutor();

  public static final class State {
    public final boolean busy;
    public final String message;
    public final Uri uri;

    State(boolean busy, String message, Uri uri) {
      this.busy = busy;
      this.message = message;
      this.uri = uri;
    }
  }

  public final MutableLiveData<State> state = new MutableLiveData<>();
  private final AtomicBoolean working = new AtomicBoolean();

  public ErrorLogRepository(@NonNull Application app) {
    super(app);
    android.content.SharedPreferences saved = app.getSharedPreferences("dsha_log_exports", 0);
    String uri = saved.getString("last_uri", ""), name = saved.getString("last_name", "");
    state.setValue(
        new State(
            false,
            uri.isEmpty()
                ? com.deepseekharness.app.util.UiText.choose(
                    "导出启动状态与错误类别。原始运行输出保留在应用内的诊断页，不进入默认导出。",
                    "Export startup states and error categories. Raw runtime output stays in the app's diagnostics and is excluded from the default export.")
                : com.deepseekharness.app.util.UiText.format("上次保存：%s（点按查看）", name),
            uri.isEmpty() ? null : Uri.parse(uri)));
  }

  public static String filename() {
    return "DSHA-error-log-"
        + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
            .format(new java.util.Date())
        + ".txt";
  }

  public void download() {
    export(null);
  }

  public void export(Uri destination) {
    if (!working.compareAndSet(false, true)) return;
    State previous = state.getValue();
    state.setValue(
        new State(
            true,
            com.deepseekharness.app.util.UiText.text("正在收集并保存错误日志…"),
            previous == null ? null : previous.uri));
    IO.execute(
        () -> {
          File staged = null;
          try {
            String name = filename();
            staged = File.createTempFile("dsha-error-log-", ".txt", getApplication().getCacheDir());
            Compat.write(staged, collect(getApplication()).getBytes(StandardCharsets.UTF_8));
            Uri uri;
            if (destination == null) {
              DownloadsExport.Result written =
                  DownloadsExport.write(getApplication(), staged, name);
              uri = written.uri;
              name = written.displayName;
            } else {
              com.deepseekharness.app.util.FileIntegrity.Result written;
              try (InputStream in = new java.io.FileInputStream(staged);
                  OutputStream out =
                      getApplication().getContentResolver().openOutputStream(destination, "wt")) {
                if (out == null)
                  throw new java.io.IOException(
                      com.deepseekharness.app.util.UiText.text("无法写入所选位置"));
                written = com.deepseekharness.app.util.FileIntegrity.copy(in, out, staged.length());
              }
              try (InputStream in =
                  getApplication().getContentResolver().openInputStream(destination)) {
                if (!written.matches(
                    com.deepseekharness.app.util.FileIntegrity.copy(in, null, staged.length())))
                  throw new java.io.IOException(
                      com.deepseekharness.app.util.UiText.text("保存后的日志校验失败"));
              }
              uri = destination;
              try (android.database.Cursor cursor =
                  getApplication()
                      .getContentResolver()
                      .query(
                          uri,
                          new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                          null,
                          null,
                          null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
              }
              try {
                getApplication()
                    .getContentResolver()
                    .takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
              } catch (SecurityException ignored) {
              }
            }
            getApplication()
                .getSharedPreferences("dsha_log_exports", 0)
                .edit()
                .putString("last_uri", uri.toString())
                .putString("last_name", name)
                .apply();
            state.postValue(
                new State(
                    false,
                    com.deepseekharness.app.util.UiText.format(
                        destination == null
                            ? "已保存：Download/DSHA/%s\n点按此处查看日志"
                            : "已保存到所选位置：%s\n点按此处查看日志",
                        name),
                    uri));
          } catch (Exception error) {
            state.postValue(
                new State(
                    false,
                    com.deepseekharness.app.util.UiText.format(
                        "保存失败：%s\n可点「另存为」选择位置后重试。",
                        SensitiveData.redact(String.valueOf(error.getMessage()))),
                    previous == null ? null : previous.uri));
          } finally {
            if (staged != null) staged.delete();
            working.set(false);
          }
        });
  }

  public static String collect(android.content.Context context) {
    StringBuilder text = new StringBuilder(com.deepseekharness.app.util.UiText.text("DSHA 错误日志\n"));
    text.append(com.deepseekharness.app.util.UiText.text("生成时间："))
        .append(
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT)
                .format(new java.util.Date()))
        .append('\n');
    text.append(com.deepseekharness.app.util.UiText.text("版本："))
        .append(BuildConfig.VERSION_NAME)
        .append(" / ")
        .append(BuildConfig.VERSION_CODE)
        .append(
            BuildConfig.LOW_ANDROID
                ? com.deepseekharness.app.util.UiText.text(" / 兼容版\n")
                : com.deepseekharness.app.util.UiText.text(" / 标准版\n"));
    text.append(com.deepseekharness.app.util.UiText.text("设备："))
        .append(android.os.Build.MANUFACTURER)
        .append(' ')
        .append(android.os.Build.MODEL)
        .append(" / Android ")
        .append(android.os.Build.VERSION.RELEASE)
        .append('\n');
    ConfigStore config = new ConfigStore(context);
    text.append("failureStage=").append(category(config.getDiagnosticFailureStage())).append('\n');
    text.append("failureRecorded=")
        .append(!config.getDiagnosticFailureReason().isEmpty())
        .append('\n');
    text.append("installFailureRecorded=")
        .append(ColdInstallDiagnostics.read(context).contains("FAILED"))
        .append('\n');
    text.append("runtimeTrialFailureRecorded=")
        .append(!com.deepseekharness.app.runtime.RuntimeTrial.latestFailure(context).isEmpty())
        .append('\n');
    var emergency = com.deepseekharness.app.recovery.RecoveryController.get(context).snapshot();
    text.append(
            com.deepseekharness.app.util.UiText.choose(
                "\n=== 独立应急 DSH ===\n", "\n=== Independent emergency DSH ===\n"))
        .append("state=")
        .append(emergency.state)
        .append(" generation=")
        .append(emergency.generation)
        .append(" errorCode=")
        .append(category(emergency.errorCode))
        .append("\n");
    var current = HarnessController.get(context).startupDiagnostics().snapshot();
    text.append("currentStartup: stage=")
        .append(category(current.stage))
        .append(" browserReady=")
        .append(current.browserReady)
        .append(" safe=")
        .append(current.safe)
        .append(" issueCount=")
        .append(current.issues.size())
        .append('\n');
    text.append(
        com.deepseekharness.app.util.UiText.choose(
            "\n=== 最近五次启动 ===\n", "\n=== Last five starts ===\n"));
    for (com.deepseekharness.app.util.StartupHistoryStore.Entry entry :
        HarnessController.get(context).startupDiagnostics().history())
      text.append(entry.started)
          .append(" / ")
          .append(category(entry.status))
          .append(" / ")
          .append(category(entry.stage))
          .append(" / safe=")
          .append(entry.safe)
          .append('\n');
    text.append(
        com.deepseekharness.app.util.UiText.choose(
            "\n原始进程输出、插件消息与日志正文未进入默认报告；可在应用内诊断页查看。分享前仍请检查设备型号等信息。\n",
            "\nRaw process output, plugin messages and log bodies are excluded from this default report; inspect them in the app's diagnostics. Review device details before sharing.\n"));
    return SensitiveData.redact(text.toString());
  }

  private static String category(String value) {
    if (value == null || value.isEmpty()) return "none";
    switch (value) {
      case "starting":
      case "ready":
      case "failed":
      case "stopped":
      case "interrupted":
        return value;
      default:
        return "recorded";
    }
  }
}
