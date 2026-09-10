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
import com.deepseekharness.app.util.TextLogTail;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 错误日志下载独立于环境探针：Web 无法启动、环境维护中也能生成文件。 */
public final class ErrorLogRepository extends AndroidViewModel {
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor();
    public static final class State {
        public final boolean busy;
        public final String message;
        public final Uri uri;
        State(boolean busy, String message, Uri uri) { this.busy = busy; this.message = message; this.uri = uri; }
    }
    public final MutableLiveData<State> state = new MutableLiveData<>();
    private final AtomicBoolean working = new AtomicBoolean();
    public ErrorLogRepository(@NonNull Application app) {
        super(app);
        android.content.SharedPreferences saved = app.getSharedPreferences("dsha_log_exports", 0);
        String uri = saved.getString("last_uri", ""), name = saved.getString("last_name", "");
        state.setValue(new State(false, uri.isEmpty() ? "日志保存在本机，导出时隐藏常见密钥字段。" : "上次保存：" + name + "（点按查看）", uri.isEmpty() ? null : Uri.parse(uri)));
    }
    public static String filename() {
        return "DSHA-error-log-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + ".txt";
    }
    public void download() { export(null); }
    public void export(Uri destination) {
        if (!working.compareAndSet(false, true)) return;
        State previous = state.getValue();
        state.setValue(new State(true, "正在收集并保存错误日志…", previous == null ? null : previous.uri));
        IO.execute(() -> {
            File staged = null;
            try {
                String name = filename();
                staged = File.createTempFile("dsha-error-log-", ".txt", getApplication().getCacheDir());
                Compat.write(staged, collect(getApplication()).getBytes(StandardCharsets.UTF_8));
                Uri uri;
                if (destination == null) {
                    DownloadsExport.Result written = DownloadsExport.write(getApplication(), staged, name);
                    uri = written.uri; name = written.displayName;
                } else {
                    com.deepseekharness.app.util.FileIntegrity.Result written;
                    try (InputStream in = new java.io.FileInputStream(staged);
                         OutputStream out = getApplication().getContentResolver().openOutputStream(destination, "wt")) {
                        if (out == null) throw new java.io.IOException("无法写入所选位置");
                        written = com.deepseekharness.app.util.FileIntegrity.copy(in, out, staged.length());
                    }
                    try (InputStream in = getApplication().getContentResolver().openInputStream(destination)) {
                        if (!written.matches(com.deepseekharness.app.util.FileIntegrity.copy(in, null, staged.length())))
                            throw new java.io.IOException("保存后的日志校验失败");
                    }
                    uri = destination;
                    try (android.database.Cursor cursor = getApplication().getContentResolver().query(uri,
                            new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                    }
                    try { getApplication().getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
                    catch (SecurityException ignored) { }
                }
                getApplication().getSharedPreferences("dsha_log_exports", 0).edit().putString("last_uri", uri.toString()).putString("last_name", name).apply();
                state.postValue(new State(false, (destination == null ? "已保存：Download/DSHA/" : "已保存到所选位置：") + name + "\n点按此处查看日志", uri));
            } catch (Exception error) {
                state.postValue(new State(false, "保存失败：" + SensitiveData.redact(String.valueOf(error.getMessage()))
                        + "\n可点「另存为」选择位置后重试。", previous == null ? null : previous.uri));
            } finally { if (staged != null) staged.delete(); working.set(false); }
        });
    }
    public static String collect(android.content.Context context) {
        StringBuilder text = new StringBuilder("DSHA 错误日志\n");
        text.append("生成时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT).format(new java.util.Date())).append('\n');
        text.append("版本：").append(BuildConfig.VERSION_NAME).append(" / ").append(BuildConfig.VERSION_CODE).append(BuildConfig.LOW_ANDROID ? " / 兼容版\n" : " / 标准版\n");
        text.append("设备：").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" / Android ").append(android.os.Build.VERSION.RELEASE).append('\n');
        ConfigStore config = new ConfigStore(context);
        text.append("最近失败阶段：").append(config.getWebFailureStage()).append('\n')
                .append("最近失败原因：").append(config.getWebFailureReason()).append('\n');
        text.append("\n=== 应用操作、崩溃与页面错误 ===\n").append(DiagnosticLog.read(context));
        text.append("\n=== 本轮启动时间线 ===\n").append(HarnessController.get(context).startupDiagnostics().snapshot().log);
        text.append("\n=== 上次失败（后续成功启动仍保留）===\n").append(StartupDiagnostics.lastFailure(context));
        text.append("\n=== 最近运行输出（最多 256 KiB）===\n");
        File log = new File(context.getFilesDir(), "linux/ubuntu/root/dsh-web.log");
        try {
            if (Compat.isSymbolicLink(log)) text.append("运行日志路径为符号链接，未读取。\n");
            else if (log.isFile()) text.append(TextLogTail.read(log, 256 * 1024));
            else text.append("暂无 Web 运行日志。\n");
        } catch (Exception error) { text.append("运行日志读取失败：").append(error.getClass().getSimpleName()).append('\n'); }
        text.append("\n仅保存于所选本机位置，没有自动上传；未读取配置全文、对话文件或终端历史。\n");
        return SensitiveData.redact(text.toString());
    }
}
