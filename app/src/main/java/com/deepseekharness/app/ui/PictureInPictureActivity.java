package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.PictureInPictureParams;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.content.ContextCompat;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.PictureInPicturePolicy;

/** 仅供两种 DSH 网页预览使用；原生启动、配置和终端 Activity 不继承此类。 */
public abstract class PictureInPictureActivity extends AppCompatActivity {
    private PictureInPictureFrame pictureFrame;
    private SharedPreferences picturePreferences;
    private boolean leavingForActivity;
    private int restoredViewportWidth, restoredViewportHeight;
    private PendingIntent pictureToggle;
    private boolean pictureReceiverRegistered;
    private long pictureGeneration;
    private final android.os.Handler pictureHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pictureMonitor = new Runnable() {
        @Override public void run() {
            if (!showing(PictureInPictureActivity.this) || isFinishing() || isDestroyed()) return;
            var controller = com.deepseekharness.app.core.HarnessController.get(PictureInPictureActivity.this);
            if (!PictureInPicturePolicy.sessionActive(pictureGeneration, controller.getWebGeneration(),
                    controller.isStopping(), controller.isUserStopped())) { finish(); return; }
            // 只读内存状态，不探测端口、不查询进程；停止或更换 Web 后收起旧小窗。
            pictureHandler.postDelayed(this, 500);
        }
    };
    // 每个网页实例独立的私有动作；外部应用不能通过广播切换页面。
    private final String pictureAction = com.deepseekharness.app.BuildConfig.APPLICATION_ID + ".pip." + java.util.UUID.randomUUID();
    private final BroadcastReceiver pictureReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!pictureAction.equals(intent.getAction()) || !showing(PictureInPictureActivity.this)
                    || pictureFrame == null || isFinishing()) return;
            int[] viewport = pictureFrame.pictureViewport();
            new ConfigStore(PictureInPictureActivity.this).setPictureInPictureLayout(
                    viewport[0] > viewport[1] ? "portrait" : "landscape");
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener picturePreferenceListener = (prefs, key) -> {
        if (Constants.KEY_PICTURE_IN_PICTURE.equals(key) || Constants.KEY_PICTURE_IN_PICTURE_LAYOUT.equals(key)) refreshPictureInPicture();
    };

    @androidx.annotation.ChecksSdkIntAtLeast(api = 26)
    public static boolean supported(Context context) {
        return Build.VERSION.SDK_INT >= 26 && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }
    public static boolean allowed(Context context) {
        if (!supported(context)) return false;
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops == null ? AppOpsManager.MODE_ERRORED : ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT;
        } catch (RuntimeException unavailable) { return false; }
    }
    public static boolean showing(Activity activity) {
        return Build.VERSION.SDK_INT >= 26 && activity.isInPictureInPictureMode();
    }
    protected abstract boolean pictureInPictureContentReady();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            restoredViewportWidth = saved.getInt("pip-viewport-width");
            restoredViewportHeight = saved.getInt("pip-viewport-height");
            pictureGeneration = saved.getLong("pip-web-generation");
        }
        picturePreferences = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
        picturePreferences.registerOnSharedPreferenceChangeListener(picturePreferenceListener);
        if (supported(this)) {
            ContextCompat.registerReceiver(this, pictureReceiver, new IntentFilter(pictureAction), ContextCompat.RECEIVER_NOT_EXPORTED);
            pictureReceiverRegistered = true;
            pictureToggle = PendingIntent.getBroadcast(this, 0, new Intent(pictureAction).setPackage(getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
    }
    @Override public void setContentView(int layout) {
        pictureFrame = new PictureInPictureFrame(this);
        pictureFrame.restoreViewport(restoredViewportWidth, restoredViewportHeight);
        pictureFrame.setPictureLayout(new ConfigStore(this).getPictureInPictureLayout());
        pictureFrame.setCompact(showing(this));
        getLayoutInflater().inflate(layout, pictureFrame, true);
        super.setContentView(pictureFrame);
        pictureFrame.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> refreshPictureInPicture());
    }
    private boolean mayEnter() {
        KeyguardManager guard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        var controller = com.deepseekharness.app.core.HarnessController.get(this);
        return pictureFrame != null && !controller.isStopping() && !controller.isUserStopped() && controller.getWebGeneration() > 0
                && (guard == null || !guard.isKeyguardLocked())
                && PictureInPicturePolicy.canEnter(new ConfigStore(this).isPictureInPictureEnabled(),
                supported(this), allowed(this), pictureInPictureContentReady(), leavingForActivity, isFinishing() || isDestroyed());
    }
    @androidx.annotation.RequiresApi(26)
    private PictureInPictureParams parameters(boolean autoEnter) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        if (pictureFrame != null) {
            int[] viewport = pictureFrame.pictureViewport();
            int[] ratio = PictureInPicturePolicy.aspectRatio(viewport[0], viewport[1]);
            builder.setAspectRatio(new Rational(ratio[0], ratio[1]));
            if (pictureToggle != null) {
                boolean landscape = viewport[0] > viewport[1];
                String title = getString(landscape ? R.string.picture_in_picture_portrait : R.string.picture_in_picture_landscape);
                RemoteAction toggle = new RemoteAction(Icon.createWithResource(this,
                        landscape ? R.drawable.ic_pip_portrait : R.drawable.ic_pip_landscape), title, title, pictureToggle);
                builder.setActions(java.util.Collections.singletonList(toggle));
            }
            Rect bounds = new Rect();
            if (!showing(this) && pictureFrame.getGlobalVisibleRect(bounds) && !bounds.isEmpty()) builder.setSourceRectHint(bounds);
        }
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(false);
        return builder.build();
    }
    protected final void refreshPictureInPicture() {
        if (!supported(this) || pictureFrame == null || isDestroyed()) return;
        ConfigStore config = new ConfigStore(this);
        if (showing(this) && !config.isPictureInPictureEnabled()) { finish(); return; }
        pictureFrame.setPictureLayout(config.getPictureInPictureLayout());
        try { setPictureInPictureParams(parameters(!showing(this) && mayEnter())); }
        catch (RuntimeException unavailable) { android.util.Log.w("DSHA-PiP", "画中画参数未应用：" + unavailable.getClass().getSimpleName()); }
    }
    @androidx.annotation.RequiresApi(26)
    private boolean enterIfAllowed() {
        if (!mayEnter() || showing(this)) return false;
        pictureFrame.freezeViewport();
        try {
            if (enterPictureInPictureMode(parameters(false))) return true;
            pictureFrame.resumeViewport();
        } catch (RuntimeException unavailable) {
            pictureFrame.resumeViewport();
            android.util.Log.w("DSHA-PiP", "系统未接受画中画：" + unavailable.getClass().getSimpleName());
        }
        return false;
    }
    /** 网页已处理弹层与历史返回后调用，保留正在运行的 DSH 网页小窗。 */
    protected final void leavePreview() {
        if (Build.VERSION.SDK_INT >= 26 && enterIfAllowed()) return;
        finish();
    }
    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (mayEnter()) pictureFrame.freezeViewport();
        if (Build.VERSION.SDK_INT >= 26 && Build.VERSION.SDK_INT < 31) enterIfAllowed();
    }
    @Override public boolean onPictureInPictureRequested() {
        if (Build.VERSION.SDK_INT >= 26) enterIfAllowed();
        return true;
    }
    @Override public void onPictureInPictureModeChanged(boolean inPicture, Configuration config) {
        if (pictureFrame != null) pictureFrame.setCompact(inPicture);
        super.onPictureInPictureModeChanged(inPicture, config);
        if (inPicture && pictureFrame != null) {
            pictureGeneration = com.deepseekharness.app.core.HarnessController.get(this).getWebGeneration();
            android.view.inputmethod.InputMethodManager input = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (input != null) input.hideSoftInputFromWindow(pictureFrame.getWindowToken(), 0);
        }
        android.view.View content = findViewById(android.R.id.content);
        if (content != null) ViewCompat.requestApplyInsets(content);
        pictureHandler.removeCallbacks(pictureMonitor);
        if (inPicture) pictureHandler.post(pictureMonitor);
        refreshPictureInPicture();
    }
    @Override protected void onStart() {
        super.onStart();
        if (showing(this)) { pictureHandler.removeCallbacks(pictureMonitor); pictureHandler.post(pictureMonitor); }
    }
    @Override protected void onStop() {
        pictureHandler.removeCallbacks(pictureMonitor);
        super.onStop();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
    }
    @Override protected void onResume() {
        super.onResume(); leavingForActivity = false;
        if (pictureFrame != null) pictureFrame.resumeViewport();
        refreshPictureInPicture();
    }
    /** 文件选择、系统浏览器、恢复页等由应用主动打开时，不留下多余的小窗。 */
    private void suspendForActivity() { leavingForActivity = true; refreshPictureInPicture(); }
    @Override public void startActivityForResult(Intent intent, int request, Bundle options) {
        suspendForActivity();
        try { super.startActivityForResult(intent, request, options); }
        catch (RuntimeException error) { leavingForActivity = false; refreshPictureInPicture(); throw error; }
    }
    @Override public void startIntentSenderForResult(IntentSender sender, int request, Intent intent,
                                                    int mask, int values, int extraFlags, Bundle options) throws IntentSender.SendIntentException {
        suspendForActivity();
        try { super.startIntentSenderForResult(sender, request, intent, mask, values, extraFlags, options); }
        catch (IntentSender.SendIntentException | RuntimeException error) { leavingForActivity = false; refreshPictureInPicture(); throw error; }
    }
    @Override protected void onDestroy() {
        pictureHandler.removeCallbacks(pictureMonitor);
        if (picturePreferences != null) picturePreferences.unregisterOnSharedPreferenceChangeListener(picturePreferenceListener);
        if (pictureReceiverRegistered) { unregisterReceiver(pictureReceiver); pictureReceiverRegistered = false; }
        if (pictureToggle != null) { pictureToggle.cancel(); pictureToggle = null; }
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (pictureFrame != null) {
            out.putInt("pip-viewport-width", pictureFrame.viewportWidth());
            out.putInt("pip-viewport-height", pictureFrame.viewportHeight());
        }
        out.putLong("pip-web-generation", pictureGeneration);
        super.onSaveInstanceState(out);
    }
}
