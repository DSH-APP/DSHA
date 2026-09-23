package com.deepseekharness.app.core;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.core.UpdateRepository.State;
import com.deepseekharness.app.core.UpdateRepository.Stage;
import com.deepseekharness.app.util.ResumableDownload;
import com.deepseekharness.app.util.UpdatePolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 版本清单、可取消下载及 APK 核验；不持有 Activity，旋转不会重新下载。 */
public final class UpdateEngine {
    private static UpdateEngine instance;
    private final android.content.Context context;
    interface Transport { HttpURLConnection open(String url, long offset) throws Exception; }
    private final Transport transport;
    public static synchronized UpdateEngine get(android.content.Context context) {
        if (instance == null) instance = new UpdateEngine(context.getApplicationContext());
        return instance;
    }
    public static final String FEED = "https://dsha.cc/api/updates.json";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;
    private volatile String channel;
    // 预览通道也可选中稳定版，来源必须记录所选通道，不能从 release.channel 推断。
    private volatile String candidateChannel;
    private boolean startupChecked;
    private volatile boolean startupNotice;
    private volatile UpdatePolicy.Release candidate;
    private volatile File verifiedApk;
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(com.deepseekharness.app.util.UiText.text("尚未检查更新"), false, 0, 0, null, null));
    private volatile String phase = "";
    private volatile String stopReason = "";
    private volatile boolean runningDownload;
    private volatile boolean checking;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    UpdateEngine(android.content.Context app) {
        this(app, null);
    }
    UpdateEngine(android.content.Context app, Transport source) {
        context = app;
        transport = source == null ? this::connect : source;
        channel = prefs().getString("channel", UpdatePolicy.defaultChannel(BuildConfig.VERSION_NAME));
        if (!UpdatePolicy.PREVIEW.equals(channel)) channel = UpdatePolicy.STABLE;
        restore();
    }
    private android.content.SharedPreferences prefs() { return context.getSharedPreferences("dsha-updates", 0); }
    public boolean hasTask() { return candidate != null || busy.get(); }
    public boolean shouldResume() { return currentCandidate() && "downloading".equals(phase); }
    public LiveData<State> state() { return state; }
    public String channel() { return channel; }
    public void setChannel(String value) {
        if (busy.get() || channel.equals(value)) return;
        if (!UpdatePolicy.STABLE.equals(value) && !UpdatePolicy.PREVIEW.equals(value)) return;
        channel = value;
        startupNotice = false;
        context.getSharedPreferences("dsha-updates", 0).edit().putString("channel", value).apply();
        check();
    }
    public void cancel() { stop(com.deepseekharness.app.util.UiText.text("已取消下载，进度已保留"), "cancelled"); }
    /** 格式化前终止并收敛更新线程，避免它在偏好和私有目录清空后又写回旧候选。 */
    public void stopForFactoryReset() throws IOException {
        String reason=com.deepseekharness.app.util.UiText.choose("格式化已停止更新任务","Formatting stopped the update task");
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())stop(reason,"cancelled");
        else {
            java.util.concurrent.CountDownLatch requested=new java.util.concurrent.CountDownLatch(1);
            main.post(()->{try{stop(reason,"cancelled");}finally{requested.countDown();}});
            try{if(!requested.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("FORMAT_UPDATE_STOP_TIMEOUT");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("FORMAT_UPDATE_INTERRUPTED");}
        }
        long deadline=android.os.SystemClock.elapsedRealtime()+10_000;
        while((busy.get()||checking||runningDownload)&&android.os.SystemClock.elapsedRealtime()<deadline){
            try{Thread.sleep(25);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("FORMAT_UPDATE_INTERRUPTED");}
        }
        if(busy.get()||checking||runningDownload)throw new IOException("FORMAT_UPDATE_STILL_RUNNING");
        candidate=null;candidateChannel=null;verifiedApk=null;phase="";stopReason="";startupChecked=false;startupNotice=false;
        state.postValue(new State(com.deepseekharness.app.util.UiText.choose("等待重新检查更新","Waiting for a fresh update check"),false,0,0,null,null));
    }
    private interface Task { String run() throws Exception; }
    private void submit(String message, Task task) {
        if (!busy.compareAndSet(false, true)) return;
        checking = true;
        cancelled = false;
        state.setValue(snapshot(message, true));
        IO.execute(() -> {
            String result;
            try { result = task.run(); }
            catch (Exception error) {
                result = cancelled ? com.deepseekharness.app.util.UiText.text("已取消，可重新检查或下载") : com.deepseekharness.app.util.UiText.text("检查") + channelName(channel) + com.deepseekharness.app.util.UiText.text("失败：")
                        + com.deepseekharness.app.util.SensitiveData.redact(error.getMessage())
                        + (currentCandidate() ? com.deepseekharness.app.util.UiText.text("；保留此通道上次结果，可重试检查或继续使用已下载包") : com.deepseekharness.app.util.UiText.text("；可重新检查"));
            }
            connection = null;
            DiagnosticLog.record(context, "APP_UPDATE", result);
            final String done = result;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                busy.set(false);
                checking = false;
                finishState(verifiedApk != null ? "ready" : "available", done);
            });
        });
    }
    /** 每个应用进程最多自动检查一次；旋转、主题重建及从 Web 返回不重复请求。 */
    public void checkOnStartup(boolean enabled) {
        if (!enabled || startupChecked) return;
        startupChecked = true;
        if (busy.get() || shouldResume()) return;
        check(true);
    }
    /** 未真正显示的提示继续保留，打开 Web 或旋转打断 Snackbar 动画也不会吞掉提示。 */
    public UpdatePolicy.Release startupNotice() {
        if (!startupNotice || busy.get() || !currentCandidate()) return null;
        return candidate;
    }
    public void markStartupNoticeShown(UpdatePolicy.Release shown) {
        if (shown != null && candidate == shown) startupNotice = false;
    }
    public void check() {
        check(false);
    }
    private void check(boolean startup) {
        final String requestedChannel = channel;
        submit(com.deepseekharness.app.util.UiText.text("正在检查") + (UpdatePolicy.PREVIEW.equals(channel) ? com.deepseekharness.app.util.UiText.text("预览版") : com.deepseekharness.app.util.UiText.text("稳定版")) + com.deepseekharness.app.util.UiText.text("更新…"), () -> {
            HttpURLConnection conn = transport.open(FEED, 0); connection = conn;
            byte[] raw;
            try (InputStream input = conn.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) {
                    ensureActive();
                    if (bytes.size() + n > 1024 * 1024) throw new IOException(com.deepseekharness.app.util.UiText.text("更新清单过大"));
                    bytes.write(buffer, 0, n);
                }
                raw = bytes.toByteArray();
            } finally { conn.disconnect(); }
            JSONObject feed = new JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            if (feed.optInt("schemaVersion") != 1) throw new IOException(com.deepseekharness.app.util.UiText.text("更新清单版本不兼容"));
            JSONArray releases = feed.getJSONArray("releases");
            ArrayList<UpdatePolicy.Release> options = new ArrayList<>();
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.getJSONObject(i);
                JSONArray artifacts = release.getJSONArray("artifacts");
                for (int j = 0; j < artifacts.length(); j++) {
                    JSONObject apk = artifacts.getJSONObject(j);
                    options.add(new UpdatePolicy.Release(release.getInt("versionCode"), release.getString("version"),
                            release.getString("channel"), apk.getString("flavor"), apk.getInt("minSdk"),
                            apk.getString("abi"), apk.getString("url"), apk.getString("sha256"), apk.getLong("bytes"),
                            release.optString("notes"), release.getString("pageUrl")));
                }
            }
            ensureActive();
            UpdatePolicy.Release selected = UpdatePolicy.select(options, BuildConfig.VERSION_CODE, BuildConfig.LOW_ANDROID ? "low" : "standard", Build.VERSION.SDK_INT, requestedChannel);
            if (!requestedChannel.equals(candidateChannel) || candidate == null || selected == null
                    || candidate.versionCode != selected.versionCode || !candidate.version.equals(selected.version)
                    || candidate.bytes != selected.bytes || !candidate.sha256.equalsIgnoreCase(selected.sha256)) verifiedApk = null;
            candidate = selected;
            candidateChannel = selected == null ? null : requestedChannel;
            startupNotice = startup && selected != null;
            return candidate != null ? com.deepseekharness.app.util.UiText.text("发现新版本 ") + candidate.version : com.deepseekharness.app.util.UiText.text("此通道暂无适合当前设备的更新；当前版本码 ") + BuildConfig.VERSION_CODE;
        });
    }
    public void download() {
        if (!currentCandidate() || !candidate.valid() || !busy.compareAndSet(false, true)) return;
        cancelled = false; stopReason = ""; verifiedApk = null;
        try {
            save("downloading", com.deepseekharness.app.util.UiText.text("正在准备下载…"));
            state.setValue(new State(com.deepseekharness.app.util.UiText.text("正在准备下载，可离开此页面"), Stage.DOWNLOADING,
                    partial().length(), candidate.bytes, candidate, null, channel, candidateChannel));
            androidx.core.content.ContextCompat.startForegroundService(context,
                    new android.content.Intent(context, com.deepseekharness.app.UpdateDownloadService.class));
        } catch (Exception error) { startFailed(error); }
    }

    public void startFailed(Exception error) {
        runningDownload = false; busy.set(false);
        finishState("paused", com.deepseekharness.app.util.UiText.text("下载尚未开始：") + com.deepseekharness.app.util.SensitiveData.redact(error.getMessage()) + com.deepseekharness.app.util.UiText.text("；请在更新页重试"));
    }

    /** 由前台服务调用；进程重建后从持久清单和实际文件长度恢复。 */
    public synchronized void runDownload() {
        if (runningDownload || !shouldResume()) return;
        runningDownload = true; busy.set(true); cancelled = false; stopReason = "";
        final UpdatePolicy.Release release = candidate;
        state.setValue(new State(com.deepseekharness.app.util.UiText.text("正在恢复下载…"), Stage.DOWNLOADING,
                partial().length(), release.bytes, release, null, channel, candidateChannel));
        IO.execute(() -> {
            String message, completedPhase;
            try {
                File partial = partial(), apk = apk();
                File directory = partial.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法创建更新目录"));
                if (directory.getUsableSpace() < Math.max(0, release.bytes - partial.length()) + 32L * 1024 * 1024)
                    throw new IOException(com.deepseekharness.app.util.UiText.text("存储不足，请留出剩余下载大小加 32 MiB 空间"));
                ResumableDownload.transfer(partial, release.bytes, release.sha256, offset -> {
                    HttpURLConnection conn = transport.open(release.url, offset); connection = conn; return conn;
                },
                        new ResumableDownload.Progress() {
                    private long last;
                    public void check() throws IOException { ensureActive(); }
                    public void changed(long n, long total) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - last >= 500 || n == total) {
                            state.postValue(new State(com.deepseekharness.app.util.UiText.text("正在下载，离开页面后继续"), Stage.DOWNLOADING,
                                    n, total, release, null, channel, candidateChannel)); last = now;
                        }
                    }
                });
                ensureActive();
                state.postValue(new State(com.deepseekharness.app.util.UiText.text("正在核验包名、版本与签名…"), Stage.VERIFYING,
                        release.bytes, release.bytes, release, null, channel, candidateChannel));
                validatePackage(partial, release);
                ensureActive();
                android.system.Os.rename(partial.getAbsolutePath(), apk.getAbsolutePath());
                verifiedApk = apk;
                message = com.deepseekharness.app.util.UiText.text("下载及校验完成，点击「安装更新」继续"); completedPhase = "ready";
            } catch (Exception error) {
                verifiedApk = null;
                message = cancelled ? stopReason : com.deepseekharness.app.util.UiText.text("下载暂停：") + com.deepseekharness.app.util.SensitiveData.redact(error.getMessage()) + com.deepseekharness.app.util.UiText.text("；可继续下载");
                completedPhase = cancelled ? (com.deepseekharness.app.util.UiText.text("已取消下载，进度已保留").equals(stopReason) ? "cancelled" : "paused") : "paused";
            }
            connection = null;
            final String done = message, savedPhase = completedPhase;
            main.post(() -> {
                runningDownload = false; busy.set(false);
                finishState(savedPhase, done);
            });
        });
    }

    public void pause(String reason) { stop(reason, "paused"); }
    private void stop(String reason, String nextPhase) {
        cancelled = true; stopReason = reason;
        try { save(nextPhase, reason); } catch (Exception ignored) { }
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
        if (!runningDownload && !checking) {
            busy.set(false);
            finishState(nextPhase, reason);
        }
    }

    private File partial() { return new File(context.getFilesDir(), "updates/" + candidate.sha256.toLowerCase(java.util.Locale.ROOT) + ".part"); }
    private File apk() { return new File(context.getFilesDir(), "updates/" + candidate.sha256.toLowerCase(java.util.Locale.ROOT) + ".apk"); }

    private boolean currentCandidate() { return candidate != null && channel.equals(candidateChannel); }
    public static String channelName(String value) { return UpdatePolicy.PREVIEW.equals(value) ? com.deepseekharness.app.util.UiText.text("预览通道") : com.deepseekharness.app.util.UiText.text("稳定通道"); }
    private State snapshot(String message, boolean working) {
        boolean matches = currentCandidate();
        if (candidate != null) {
            message += candidateChannel == null ? com.deepseekharness.app.util.UiText.text("\n已保留 ") + candidate.version
                    + com.deepseekharness.app.util.UiText.text(" 及已有下载文件，但无法确认查询来源；请联网重新检查，确认当前通道后才能下载/安装")
                    : matches ? com.deepseekharness.app.util.UiText.text("\n候选来源：") + channelName(candidateChannel)
                    : com.deepseekharness.app.util.UiText.text("\n已保留 ") + candidate.version + com.deepseekharness.app.util.UiText.text("（来源：") + channelName(candidateChannel)
                    + com.deepseekharness.app.util.UiText.text("），与当前") + channelName(channel) + com.deepseekharness.app.util.UiText.text("不匹配；切回来源通道或重新检查后才能下载/安装");
        }
        return new State(message, working ? Stage.CHECKING : Stage.IDLE,
                !matches ? 0 : verifiedApk != null ? candidate.bytes : partial().length(),
                matches ? candidate.bytes : 0, matches ? candidate : null, matches ? verifiedApk : null,
                channel, candidateChannel);
    }

    private void finishState(String nextPhase, String message) {
        try { save(nextPhase, message); }
        catch (Exception error) { message += com.deepseekharness.app.util.UiText.text("；保存任务状态失败，下次需要重新检查"); }
        DiagnosticLog.record(context, "APP_UPDATE", message);
        state.setValue(snapshot(message, false));
    }

    private void save(String nextPhase, String message) throws Exception {
        JSONObject doc = new JSONObject();
        if (candidate != null) {
            UpdatePolicy.Release r = candidate;
            doc.put("code", r.versionCode).put("version", r.version).put("channel", r.channel).put("flavor", r.flavor)
                    .put("minSdk", r.minSdk).put("abi", r.abi).put("url", r.url).put("sha256", r.sha256)
                    .put("bytes", r.bytes).put("notes", r.notes).put("pageUrl", r.pageUrl)
                    // 空字符串明确表示未知；后续恢复不能把它再次当成可推断的旧字段缺失。
                    .put("checkedChannel", candidateChannel == null ? "" : candidateChannel);
        }
        if (!prefs().edit().putString("task", doc.toString()).putString("phase", nextPhase).putString("message", message).commit())
            throw new IOException(com.deepseekharness.app.util.UiText.text("任务状态无法写入存储"));
        phase = nextPhase;
    }

    private void restore() {
        try {
            JSONObject doc = new JSONObject(prefs().getString("task", "{}"));
            if (!doc.has("code")) return;
            UpdatePolicy.Release r = new UpdatePolicy.Release(doc.getInt("code"), doc.getString("version"), doc.getString("channel"),
                    doc.getString("flavor"), doc.getInt("minSdk"), doc.getString("abi"), doc.getString("url"), doc.getString("sha256"),
                    doc.getLong("bytes"), doc.optString("notes"), doc.getString("pageUrl"));
            candidateChannel = UpdatePolicy.restoreCheckedChannel(doc.has("checkedChannel"),
                    doc.optString("checkedChannel", ""), r.channel);
            // 未知来源仅保留设备兼容的候选和文件，currentCandidate 会阻止使用。
            candidate = UpdatePolicy.select(java.util.Collections.singletonList(r), BuildConfig.VERSION_CODE,
                    BuildConfig.LOW_ANDROID ? "low" : "standard", Build.VERSION.SDK_INT,
                    candidateChannel == null ? UpdatePolicy.PREVIEW : candidateChannel);
            if (candidate == null) return;
            phase = prefs().getString("phase", "paused");
            verifiedApk = "ready".equals(phase) && apk().isFile() ? apk() : null;
            State restored = snapshot(verifiedApk != null ? com.deepseekharness.app.util.UiText.text("安装包已保留，安装前将重新校验") : com.deepseekharness.app.util.UiText.text("更新候选已恢复，可重新检查或继续下载"), false);
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) state.setValue(restored);
            else state.postValue(restored);
        } catch (Exception ignored) { candidate = null; candidateChannel = null; verifiedApk = null; }
    }

    public File installableApk() throws Exception {
        UpdatePolicy.Release release = candidate;
        File file = verifiedApk;
        String sourceChannel = candidateChannel;
        if (!isCurrentInstall(file, release, sourceChannel)) throw new IOException(com.deepseekharness.app.util.UiText.text("候选来源与当前通道不匹配或任务已改变，请重新检查"));
        if (release == null || file == null || !file.isFile()) throw new IOException(com.deepseekharness.app.util.UiText.text("请先下载并校验安装包"));
        try {
            ResumableDownload.verify(file, release.bytes, release.sha256);
            validatePackage(file, release);
        } catch (Exception error) {
            main.post(() -> {
                if (verifiedApk == file && candidate == release) {
                    verifiedApk = null;
                    finishState("paused", com.deepseekharness.app.util.UiText.text("安装前校验失败：") + com.deepseekharness.app.util.SensitiveData.redact(error.getMessage()));
                }
            });
            throw error;
        }
        if (!isCurrentInstall(file, release, sourceChannel)) throw new IOException(com.deepseekharness.app.util.UiText.text("校验期间通道或候选已改变，请重试安装"));
        return file;
    }
    /** 领取安装结果时再次核对身份，阻止校验期间发生的检查/切通道竞态。 */
    boolean isCurrentInstall(File file, UpdatePolicy.Release release, String sourceChannel) {
        return !busy.get() && currentCandidate() && release != null && candidate == release
                && file != null && file.equals(verifiedApk) && channel.equals(sourceChannel);
    }
    void validatePackage(File apk, UpdatePolicy.Release release) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo next = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
        if (next == null || !installed.packageName.equals(next.packageName)) throw new IOException(com.deepseekharness.app.util.UiText.text("安装包不是 DSHA"));
        long code = Build.VERSION.SDK_INT >= 28 ? next.getLongVersionCode() : next.versionCode;
        if (code != release.versionCode || code <= BuildConfig.VERSION_CODE) throw new IOException(com.deepseekharness.app.util.UiText.text("安装包版本不匹配或不是更新版本"));
        if (Build.VERSION.SDK_INT >= 24 && next.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) throw new IOException(com.deepseekharness.app.util.UiText.text("安装包不支持当前 Android 版本"));
        String expectedVersion = release.version + (BuildConfig.LOW_ANDROID ? "low" : "");
        if (!expectedVersion.equals(next.versionName)) throw new IOException(com.deepseekharness.app.util.UiText.text("安装包不是当前高/低安卓版本"));
        Signature[] oldSign = signatures(installed), newSign = signatures(next);
        if (oldSign.length == 0 || newSign.length != oldSign.length) throw new IOException(com.deepseekharness.app.util.UiText.text("安装包签名不匹配"));
        ArrayList<String> oldHashes = new ArrayList<>(), newHashes = new ArrayList<>();
        for (Signature sig : oldSign) oldHashes.add(hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())));
        for (Signature sig : newSign) newHashes.add(hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())));
        java.util.Collections.sort(oldHashes); java.util.Collections.sort(newHashes);
        if (!oldHashes.equals(newHashes)) throw new IOException(com.deepseekharness.app.util.UiText.text("签名与已安装版本不同，已阻止安装"));
    }
    private Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) return info.signingInfo == null ? new Signature[0] : info.signingInfo.getApkContentsSigners();
        return info.signatures == null ? new Signature[0] : info.signatures;
    }
    private HttpURLConnection connect(String target) throws Exception { return connect(target, 0); }
    private HttpURLConnection connect(String target, long offset) throws Exception {
        for (int i = 0; i < 6; i++) {
            ensureActive();
            if (!UpdatePolicy.https(target)) throw new IOException(com.deepseekharness.app.util.UiText.text("更新地址必须使用 HTTPS"));
            HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection(); connection = conn;
            ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(TrustedNetwork.sockets(context));
            conn.setConnectTimeout(15000); conn.setReadTimeout(30000); conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "DSHA/" + BuildConfig.VERSION_NAME);
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) conn.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = conn.getResponseCode();
            if (code >= 300 && code <= 399) {
                String location = conn.getHeaderField("Location"); conn.disconnect();
                if (location == null) throw new IOException(com.deepseekharness.app.util.UiText.text("下载重定向缺少地址"));
                target = new URL(new URL(target), location).toString(); continue;
            }
            if (code != 200 && !(offset > 0 && code == 206)) { conn.disconnect(); throw new IOException("HTTP " + code + com.deepseekharness.app.util.UiText.text("，请稍后重试或使用发布页下载")); }
            return conn;
        }
        throw new IOException(com.deepseekharness.app.util.UiText.text("下载重定向次数过多"));
    }
    private void ensureActive() throws IOException { if (cancelled) throw new IOException(com.deepseekharness.app.util.UiText.text("已取消")); }
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return s.toString();
    }

}
