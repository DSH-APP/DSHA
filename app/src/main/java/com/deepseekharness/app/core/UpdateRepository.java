package com.deepseekharness.app.core;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.util.UpdatePolicy;
import java.io.File;

/** 页面只订阅应用范围的下载状态；关闭页面不会取消前台下载。 */
public final class UpdateRepository extends AndroidViewModel {
    public static final String FEED = UpdateEngine.FEED;
    private final UpdateEngine engine;
    private static final java.util.concurrent.ExecutorService VERIFY = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.Executor verifyExecutor;
    private final java.util.concurrent.Callable<File> verifyApk;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final MutableLiveData<InstallState> installation = new MutableLiveData<>(new InstallState(false, null, null));
    private long installRequest;
    private boolean cleared;
    private UpdatePolicy.Release installRelease;
    private String installChannel;
    public enum Stage { IDLE, CHECKING, DOWNLOADING, VERIFYING }
    public static final class State {
        public final String message;
        public final boolean busy;
        public final long downloaded, total;
        public final UpdatePolicy.Release release;
        public final File apk;
        public final String channel, sourceChannel;
        public final Stage stage;
        State(String text, boolean busy, long n, long total, UpdatePolicy.Release release, File apk) {
            this(text, busy, n, total, release, apk, null, null);
        }
        State(String text, boolean busy, long n, long total, UpdatePolicy.Release release, File apk,
              String channel, String sourceChannel) {
            this(text, busy ? Stage.DOWNLOADING : Stage.IDLE, n, total, release, apk, channel, sourceChannel);
        }
        State(String text, Stage stage, long n, long total, UpdatePolicy.Release release, File apk,
              String channel, String sourceChannel) {
            message=text; this.stage=stage; busy=stage != Stage.IDLE; downloaded=n; this.total=total; this.release=release; this.apk=apk;
            this.channel=channel; this.sourceChannel=sourceChannel;
        }
    }
    public static final class InstallState {
        public final boolean verifying;
        public final File apk;
        public final String error;
        InstallState(boolean verifying, File apk, String error) { this.verifying=verifying; this.apk=apk; this.error=error; }
        public boolean pending() { return verifying || apk != null; }
    }
    public UpdateRepository(@NonNull Application app) { this(app, UpdateEngine.get(app), VERIFY, null); }
    // 可控执行器用于校验中重建、离开页面和重复领取的确定性自测。
    UpdateRepository(Application app, UpdateEngine engine, java.util.concurrent.Executor executor,
                     java.util.concurrent.Callable<File> verifier) {
        super(app); this.engine=engine; verifyExecutor=executor; verifyApk=verifier == null ? engine::installableApk : verifier;
    }
    public LiveData<State> state() { return engine.state(); }
    public String channel() { return engine.channel(); }
    public void setChannel(String channel) { if (!installationPending()) engine.setChannel(channel); }
    public boolean hasTask() { return engine.hasTask(); }
    public void check() { if (!installationPending()) engine.check(); }
    public void download() { if (!installationPending()) engine.download(); }
    public void cancel() { if (!installationPending()) engine.cancel(); }
    public File installableApk() throws Exception { return engine.installableApk(); }
    public LiveData<InstallState> installation() { return installation; }
    public boolean installationPending() { return installation.getValue().pending(); }
    public void requestInstall() {
        State current = state().getValue();
        if (cleared || installationPending() || current == null || current.busy) return;
        if (current.apk == null || current.release == null) { installFailed(com.deepseekharness.app.util.UiText.text("请先在当前通道下载并校验安装包")); return; }
        installRelease = current.release;
        installChannel = engine.channel();
        long request = ++installRequest;
        installation.setValue(new InstallState(true, null, null));
        verifyExecutor.execute(() -> {
            File apk = null; String error = null;
            try { apk = verifyApk.call(); }
            catch (Exception failure) { error = com.deepseekharness.app.util.SensitiveData.redact(failure.getMessage()); }
            final File result = apk;
            final String message = error;
            main.post(() -> {
                if (cleared || request != installRequest) return;
                installation.setValue(new InstallState(false, result,
                        result == null ? com.deepseekharness.app.util.UiText.text("安装前校验失败：") + (message == null ? com.deepseekharness.app.util.UiText.text("无法读取安装包") : message) + com.deepseekharness.app.util.UiText.text("；请重试") : null));
            });
        });
    }
    /** 新 Activity 接手同一请求，且结果只领取一次；只有前台页面负责打开系统安装器。 */
    public File takeInstallReady() {
        InstallState current = installation.getValue();
        if (cleared || current.verifying || current.apk == null) return null;
        if (!engine.isCurrentInstall(current.apk, installRelease, installChannel)) {
            installFailed(com.deepseekharness.app.util.UiText.text("通道或候选已改变，请重新检查后重试安装")); return null;
        }
        installation.setValue(new InstallState(false, null, null));
        return current.apk;
    }
    public void installFailed(String reason) {
        installation.setValue(new InstallState(false, null, com.deepseekharness.app.util.SensitiveData.redact(reason)));
    }
    public void restoreInterruptedInstall(boolean wasPending) {
        InstallState current = installation.getValue();
        if (wasPending && !current.pending() && current.error == null)
            installFailed(com.deepseekharness.app.util.UiText.text("安装前校验已因进程重建中断，请点击「安装更新」重试"));
    }
    @Override protected void onCleared() {
        cleared = true; installRequest++;
        super.onCleared();
    }
}
