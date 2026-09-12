package com.deepseekharness.app.ui;

import android.app.Application;
import android.net.Uri;
import android.os.Bundle;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.util.WebPreviewPolicy;
import com.deepseekharness.app.util.WebTransferPolicy;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.UUID;

/** 两个内核共用的下载与 SAF 写入。跨旋转保留任务，只在真正退出时取消。 */
public final class WebDownloadModel extends AndroidViewModel {
    public record State(String phase, String message, long bytes, long total) { }
    public final MutableLiveData<State> state = new MutableLiveData<>(new State("idle", "", 0, -1));
    private volatile boolean cancelled, busy;
    private volatile InputStream input;
    private volatile HttpURLConnection connection;
    private volatile File staged;
    private String name = "download.bin";
    public volatile boolean pickerOpen;
    private volatile Uri lastSavedUri;
    public WebDownloadModel(Application application) { super(application); }
    public String fileName() { return name; }
    public boolean isBusy() { return busy; }
    public Uri lastSavedUri() { return lastSavedUri; }
    public synchronized void saveState(Bundle out) {
        if (staged != null && staged.isFile()) {
            out.putString("web-file-stage",staged.getName()); out.putString("web-file-name",name);
            out.putLong("web-file-size",staged.length()); out.putBoolean("web-file-picker",pickerOpen);
        } else if (busy) out.putBoolean("web-file-interrupted",true);
    }
    public synchronized void restoreState(Bundle saved) {
        if (saved == null || busy || staged != null || !"idle".equals(state.getValue().phase())) return;
        String id=saved.getString("web-file-stage","");
        if (id.matches("[0-9a-fA-F-]{36}\\.part")) {
            File candidate=new File(new File(getApplication().getCacheDir(),"web-downloads"),id);
            if (candidate.isFile() && candidate.length()==saved.getLong("web-file-size",-1)) {
                staged=candidate;name=WebTransferPolicy.fileName(saved.getString("web-file-name"));pickerOpen=saved.getBoolean("web-file-picker");
                state.setValue(new State("ready",com.deepseekharness.app.util.UiText.text("下载完成，选择保存位置"),candidate.length(),candidate.length()));return;
            }
        }
        if (!id.isEmpty() || saved.getBoolean("web-file-interrupted")) state.setValue(new State("error",com.deepseekharness.app.util.UiText.text("下载被系统中断，请重新导出"),0,-1));
    }
    public synchronized boolean download(String base, String url, String cookie, String filename,
                                         long expected, InputStream supplied) {
        if (busy || staged != null || pickerOpen) { close(supplied); return false; }
        if (!(supplied != null ? WebPreviewPolicy.pageDownload(base,url) : WebPreviewPolicy.sameService(base, url))) {
            close(supplied); state.setValue(new State("error", com.deepseekharness.app.util.UiText.text("此下载不是当前本机服务提供的文件"),0,-1)); return false;
        }
        busy = true; cancelled = false; name = WebTransferPolicy.fileName(filename);
        state.setValue(new State("downloading", com.deepseekharness.app.util.UiText.text("正在下载 ") + name, 0, expected));
        new Thread(() -> {
            File file = null;
            State result;
            try (RuntimeTasks ignored = RuntimeTasks.begin()) {
                File dir = new File(getApplication().getCacheDir(), "web-downloads");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法创建下载缓存"));
                file = new File(dir, UUID.randomUUID() + ".part");
                long total = expected;
                if (supplied != null) input = supplied;
                else {
                    String next = url;
                    for (int redirects = 0; ; redirects++) {
                        if (!WebPreviewPolicy.sameService(base, next)) throw new IOException(com.deepseekharness.app.util.UiText.text("下载跳转到了其他服务"));
                        checkCancelled();
                        connection = (HttpURLConnection) new URL(next).openConnection();
                        connection.setInstanceFollowRedirects(false);
                        connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
                        connection.setRequestProperty("Accept-Encoding", "identity");
                        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                        int status = connection.getResponseCode();
                        if (status >= 300 && status <= 399 && redirects < 3) {
                            String location = connection.getHeaderField("Location");
                            if (location == null) throw new IOException(com.deepseekharness.app.util.UiText.text("下载跳转地址缺失"));
                            next = new URL(new URL(next), location).toString(); disconnect(connection); continue;
                        }
                        if (status != 200) throw new IOException(com.deepseekharness.app.util.UiText.text("服务返回 HTTP ") + status);
                        try { total = Long.parseLong(connection.getHeaderField("Content-Length")); } catch(Exception ignoredLength) { total = -1; }
                        input = connection.getInputStream(); break;
                    }
                }
                WebTransferPolicy.checkSize(0, total, WebTransferPolicy.DOWNLOAD_LIMIT, false);
                if (total > 0 && dir.getUsableSpace() < total + 16L*1024*1024) throw new IOException(com.deepseekharness.app.util.UiText.text("存储空间不足"));
                long bytes = 0, last = 0;
                try (InputStream in = input; FileOutputStream out = new FileOutputStream(file)) {
                    if (in == null) throw new IOException(com.deepseekharness.app.util.UiText.text("下载响应没有文件内容"));
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        checkCancelled(); bytes += n;
                        WebTransferPolicy.checkSize(bytes, total, WebTransferPolicy.DOWNLOAD_LIMIT, false);
                        out.write(buffer, 0, n);
                        if (android.os.SystemClock.elapsedRealtime() - last > 200) {
                            last = android.os.SystemClock.elapsedRealtime();
                            state.postValue(new State("downloading", com.deepseekharness.app.util.UiText.text("正在下载 ") + name, bytes, total));
                        }
                    }
                    out.getFD().sync();
                }
                checkCancelled(); WebTransferPolicy.checkSize(bytes, total, WebTransferPolicy.DOWNLOAD_LIMIT, true);
                synchronized (this) { checkCancelled(); staged = file; file = null; }
                result = new State("ready", com.deepseekharness.app.util.UiText.text("下载完成，选择保存位置"), bytes, bytes);
            } catch (Exception error) { result = failure(error); }
            finally { close(input); input = null; disconnect(connection); connection = null;
                if (file != null) file.delete(); }
            finishState(result);
        }, "web-file-download").start();
        return true;
    }
    public synchronized void save(Uri uri) {
        pickerOpen = false;
        if (uri == null) { discard(); state.setValue(new State("idle", com.deepseekharness.app.util.UiText.text("已取消保存"),0,-1)); return; }
        if (busy) return;
        if (staged == null) {
            try { android.provider.DocumentsContract.deleteDocument(getApplication().getContentResolver(),uri); } catch(Exception ignored) { }
            state.setValue(new State("error",com.deepseekharness.app.util.UiText.text("下载缓存已丢失，请重新导出"),0,-1)); return;
        }
        busy = true; cancelled = false;
        final File file = staged;
        state.setValue(new State("saving", com.deepseekharness.app.util.UiText.text("正在保存并校验文件"), 0, file.length()));
        new Thread(() -> {
            State status;
            try (RuntimeTasks ignored = RuntimeTasks.begin()) {
                MessageDigest source = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new FileInputStream(file); OutputStream out = getApplication().getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException(com.deepseekharness.app.util.UiText.text("无法写入所选位置"));
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) { checkCancelled(); out.write(buffer,0,n); source.update(buffer,0,n); }
                }
                MessageDigest result = MessageDigest.getInstance("SHA-256"); long count = 0;
                try (InputStream in = getApplication().getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException(com.deepseekharness.app.util.UiText.text("保存后无法读取校验"));
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) { checkCancelled(); count += n; result.update(buffer,0,n); }
                }
                if (count != file.length() || !MessageDigest.isEqual(source.digest(), result.digest())) throw new IOException(com.deepseekharness.app.util.UiText.text("保存校验失败，请更换位置重试"));
                lastSavedUri = uri; discard(); status = new State("done", com.deepseekharness.app.util.UiText.text("已保存：") + name, count, count);
            } catch (Exception error) {
                // CreateDocument 返回本次文件；失败仅清理本次输出，缓存仍可重试。
                try { android.provider.DocumentsContract.deleteDocument(getApplication().getContentResolver(), uri); } catch (Exception ignored) { }
                status = failure(error);
            }
            finishState(status);
        }, "web-file-save").start();
    }
    public void cancel() {
        cancelled = true;
        // Android 自带 OkHttp 的 InputStream 不能在主线程与 read() 并发 close()。
        // 连接取消交给独立线程；写入线程仍负责最终关闭、删除与取消检查。
        HttpURLConnection c = connection; InputStream stream = input;
        new Thread(() -> { if (c != null) { disconnect(c); }
            else close(stream); },"web-download-cancel").start();
    }
    public synchronized void discard() { if (staged != null) staged.delete(); staged = null; }
    public void acknowledge() { if (!busy) { discard(); state.setValue(new State("idle", "",0,-1)); } }
    public boolean canRetrySave() { return staged != null; }
    public void retrySave() { if (!busy && staged != null) state.setValue(new State("ready",com.deepseekharness.app.util.UiText.text("选择保存位置"),staged.length(),staged.length())); }
    private void checkCancelled() throws IOException { if (cancelled) throw new IOException(com.deepseekharness.app.util.UiText.text("已取消")); }
    private State failure(Exception error) { return new State("error", cancelled ? com.deepseekharness.app.util.UiText.text("已取消下载或保存") : com.deepseekharness.app.util.UiText.text("文件处理失败：") + error.getMessage(),0,-1); }
    private void finishState(State value) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { busy = false; state.setValue(value); });
    }
    private static void close(InputStream in) { if (in != null) try { in.close(); } catch (Exception ignored) { } }
    private static void disconnect(HttpURLConnection c) { if (c != null) try { c.disconnect(); } catch(RuntimeException ignored) { } }
    @Override protected synchronized void onCleared() { cancel(); discard(); }
}
