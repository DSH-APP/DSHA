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
  private final com.deepseekharness.app.util.BrowserPresentationState presentation =
      new com.deepseekharness.app.util.BrowserPresentationState();
  private int browserThemeColor;
  private boolean restoredPresentation;
  private boolean userHidden;
  private SharedPreferences picturePreferences;
  private boolean leavingForActivity;
  private boolean pictureExitRequested;
  private int restoredViewportWidth, restoredViewportHeight;
  private PendingIntent pictureToggle;
  private boolean pictureReceiverRegistered;
  private long pictureGeneration;
  // WebView/Gecko 在输入法和系统栏变化时会产生多次 layout；只在参数真的变化时通知系统，
  // 避免每一帧重新构造 PictureInPictureParams 造成页面抖动。
  private boolean pictureParamsApplied;
  private int appliedViewportWidth = -1, appliedViewportHeight = -1;
  private boolean appliedAutoEnter, appliedAllowed;
  private String appliedPictureLayout = "";
  private final android.os.Handler pictureHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable pictureMonitor =
      new Runnable() {
        @Override
        public void run() {
          if (!showing(PictureInPictureActivity.this) || isFinishing() || isDestroyed()) return;
          var controller =
              com.deepseekharness.app.core.HarnessController.get(PictureInPictureActivity.this);
          if (!PictureInPicturePolicy.sessionActive(
              pictureGeneration,
              controller.getWebGeneration(),
              controller.isStopping(),
              controller.isUserStopped())) {
            // This generation is already stopped; do not leave an empty scaled PiP task for
            // the next generation. A shared legacy task retains its other native activities.
            BrowserTasks.finishEmptyHost(PictureInPictureActivity.this);
            return;
          }
          // 只读内存状态，不探测端口、不查询进程；停止或更换 Web 后收起旧小窗。
          pictureHandler.postDelayed(this, 500);
        }
      };
  // 每个网页实例独立的私有动作；外部应用不能通过广播切换页面。
  private final String pictureAction =
      com.deepseekharness.app.BuildConfig.APPLICATION_ID + ".pip." + java.util.UUID.randomUUID();
  private final BroadcastReceiver pictureReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (!pictureAction.equals(intent.getAction())
              || !showing(PictureInPictureActivity.this)
              || pictureFrame == null
              || isFinishing()) return;
          int[] viewport = pictureFrame.pictureViewport();
          new ConfigStore(PictureInPictureActivity.this)
              .setPictureInPictureLayout(viewport[0] > viewport[1] ? "portrait" : "landscape");
        }
      };
  private final SharedPreferences.OnSharedPreferenceChangeListener picturePreferenceListener =
      (prefs, key) -> {
        if (Constants.KEY_PICTURE_IN_PICTURE_LAYOUT.equals(key)
            || Constants.KEY_PICTURE_IN_PICTURE_USER_CHOICE.equals(key)) refreshPictureInPicture();
      };

  @androidx.annotation.ChecksSdkIntAtLeast(api = 26)
  public static boolean supported(Context context) {
    return Build.VERSION.SDK_INT >= 26
        && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
  }

  public static boolean allowed(Context context) {
    if (!supported(context)) return false;
    try {
      AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
      int mode =
          ops == null
              ? AppOpsManager.MODE_ERRORED
              : ops.checkOpNoThrow(
                  AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                  android.os.Process.myUid(),
                  context.getPackageName());
      return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  public static boolean showing(Activity activity) {
    return Build.VERSION.SDK_INT >= 26 && activity.isInPictureInPictureMode();
  }

  /**
   * 系统进入/退出 PiP 时可能先回调 onPause/onStop，再通知
   * onPictureInPictureModeChanged。网页在这段过渡期间仍由 SystemUI 绘制，不能
   * 因为 Activity 暂停就清掉刷新率或停掉浏览器渲染。
   */
  protected final boolean pictureInPictureActiveOrTransitioning() {
    return showing(this) || (pictureFrame != null && pictureFrame.isFrozen());
  }

  protected abstract boolean pictureInPictureContentReady();

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    restoredPresentation = saved != null;
    presentation.restore(saved == null ? "FULL" : saved.getString("browser-presentation", "FULL"));
    userHidden = saved != null && saved.getBoolean("browser-user-hidden", false);
    if (showing(this)) presentation.picture(true);
    browserThemeColor = getColor(R.color.surface);
    if (saved != null) {
      restoredViewportWidth = saved.getInt("pip-viewport-width");
      restoredViewportHeight = saved.getInt("pip-viewport-height");
      pictureGeneration = saved.getLong("pip-web-generation");
    }
    picturePreferences = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
    picturePreferences.registerOnSharedPreferenceChangeListener(picturePreferenceListener);
    if (supported(this)) {
      ContextCompat.registerReceiver(
          this,
          pictureReceiver,
          new IntentFilter(pictureAction),
          ContextCompat.RECEIVER_NOT_EXPORTED);
      pictureReceiverRegistered = true;
      pictureToggle =
          PendingIntent.getBroadcast(
              this,
              0,
              new Intent(pictureAction).setPackage(getPackageName()),
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
  }

  @Override
  public void setContentView(int layout) {
    pictureFrame = new PictureInPictureFrame(this);
    pictureFrame.restoreViewport(restoredViewportWidth, restoredViewportHeight);
    pictureFrame.setPictureLayout(new ConfigStore(this).getPictureInPictureLayout());
    pictureFrame.setCompact(showing(this));
    android.view.View webpage = getLayoutInflater().inflate(layout, pictureFrame, false);
    pictureFrame.addView(webpage, new android.widget.FrameLayout.LayoutParams(-1, -1));
    super.setContentView(pictureFrame);
    if (restoredPresentation) updatePresentationLayout();
    else receivePresentationIntent();
    pictureFrame.addOnLayoutChangeListener(
        (view, l, t, r, b, oldL, oldT, oldR, oldB) -> refreshPictureInPicture());
  }

  private boolean mayEnter() {
    boolean userEnabled = new ConfigStore(this).isPictureInPictureEnabled();
    if (!userEnabled) return false;
    KeyguardManager guard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
    var controller = com.deepseekharness.app.core.HarnessController.get(this);
    return presentation.mode() != com.deepseekharness.app.util.BrowserPresentationState.Mode.HIDDEN
        && pictureFrame != null
        && !controller.isStopping()
        && !controller.isUserStopped()
        && controller.getWebGeneration() > 0
        && (guard == null || !guard.isKeyguardLocked())
        && PictureInPicturePolicy.canEnter(
            supported(this),
            userEnabled,
            allowed(this),
            pictureInPictureContentReady(),
            leavingForActivity,
            isFinishing() || isDestroyed());
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
        String title =
            getString(
                landscape
                    ? R.string.picture_in_picture_portrait
                    : R.string.picture_in_picture_landscape);
        RemoteAction toggle =
            new RemoteAction(
                Icon.createWithResource(
                    this, landscape ? R.drawable.ic_pip_portrait : R.drawable.ic_pip_landscape),
                title,
                title,
                pictureToggle);
        builder.setActions(java.util.Collections.singletonList(toggle));
      }
      Rect bounds = new Rect();
      if (!showing(this) && pictureFrame.getGlobalVisibleRect(bounds) && !bounds.isEmpty())
        builder.setSourceRectHint(bounds);
    }
    if (Build.VERSION.SDK_INT >= 31)
      builder.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(false);
    return builder.build();
  }

  protected final void refreshPictureInPicture() {
    if (!supported(this) || pictureFrame == null || isDestroyed()) return;
    ConfigStore config = new ConfigStore(this);
    String pictureLayout = config.getPictureInPictureLayout();
    pictureFrame.setPictureLayout(pictureLayout);
    // 系统许可只是能力边界；Android 12+ 自动进入也必须先有本机明确开启。
    boolean systemAllowed = config.isPictureInPictureEnabled() && allowed(this);
    if (!systemAllowed && !showing(this)) synchronizePictureViewport();
    boolean autoEnter = !showing(this) && mayEnter();
    int[] viewport = pictureFrame.pictureViewport();
    if (!pictureParamsApplied
        || viewport[0] != appliedViewportWidth
        || viewport[1] != appliedViewportHeight
        || autoEnter != appliedAutoEnter
        || systemAllowed != appliedAllowed
        || !pictureLayout.equals(appliedPictureLayout)) {
      try {
        setPictureInPictureParams(parameters(autoEnter));
        pictureParamsApplied = true;
        appliedViewportWidth = viewport[0];
        appliedViewportHeight = viewport[1];
        appliedAutoEnter = autoEnter;
        appliedAllowed = systemAllowed;
        appliedPictureLayout = pictureLayout;
      } catch (RuntimeException unavailable) {
        android.util.Log.w("DSHA-PiP", "画中画参数未应用：" + unavailable.getClass().getSimpleName());
      }
    }
    if (showing(this) && !systemAllowed && !pictureExitRequested) hideConversation();
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

  /** 返回原生启动页，不隐式进入画中画。 */
  protected final void leavePreview() {
    hideConversation();
  }

  protected final boolean conversationHidden() {
    return presentation.mode() == com.deepseekharness.app.util.BrowserPresentationState.Mode.HIDDEN;
  }

  /** 隐藏和替换入口先撤销排队路由及鉴权；浏览器实例继续保留。 */
  protected void cancelConversationRequests() {}

  protected final void hideConversation() {
    if (isFinishing() || isDestroyed()) return;
    BrowserTasks.nativeNavigation();
    boolean inPicture = showing(this);
    if (inPicture) pictureExitRequested = true;
    presentation.hide();
    userHidden = true;
    leavingForActivity = true;
    cancelConversationRequests();
    if (pictureFrame != null && !showing(this)) pictureFrame.resumeViewport();
    refreshPictureInPicture();
    var input =
        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    if (input != null && pictureFrame != null)
      input.hideSoftInputFromWindow(pictureFrame.getWindowToken(), 0);
    // 保留任务及 ViewModel；再次进入接管同一页面、草稿和浏览器会话。
    if (inPicture) {
      // 先将同一 singleTask 从 PiP 展开，再显式返回原生主页；保留当前网页。
      startActivity(
          new Intent(this, getClass())
              .addFlags(
                  Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                      | Intent.FLAG_ACTIVITY_SINGLE_TOP)
              .putExtra(BrowserConversationRoute.MODE, "HIDDEN"));
    } else hideConversationTask();
  }

  private void hideConversationTask() {
    BrowserTasks.openMain(this);
  }

  protected final void receivePresentationIntent() {
    String requested = getIntent().getStringExtra(BrowserConversationRoute.MODE);
    if ("HIDDEN".equals(requested)) {
      presentation.hide();
      userHidden = true;
      leavingForActivity = true;
      cancelConversationRequests();
      updatePresentationLayout();
      if (!showing(this)) hideConversationTask();
      return;
    }
    if (requested != null || conversationHidden()) showConversation();
    else updatePresentationLayout();
  }

  private void showConversation() {
    presentation.show();
    userHidden = false;
    leavingForActivity = false;
    if (getIntent() != null) getIntent().putExtra(BrowserConversationRoute.MODE, "FULL");
    synchronizePictureViewport();
    updatePresentationLayout();
    WebFullscreenUi.applySystemBars(this);
    android.view.View content = findViewById(android.R.id.content);
    if (content != null) ViewCompat.requestApplyInsets(content);
    refreshPictureInPicture();
  }

  public final void applyBrowserTheme(int color) {
    browserThemeColor = color;
    updatePresentationLayout();
  }

  public final int browserThemeColor() {
    return browserThemeColor;
  }

  private void updatePresentationLayout() {
    if (pictureFrame != null) pictureFrame.setBackgroundColor(browserThemeColor);
  }

  /** A reused task can return full screen before its PiP mode callback is delivered. */
  private void synchronizePictureViewport() {
    if (pictureFrame == null) return;
    boolean inPicture = showing(this);
    pictureFrame.setCompact(inPicture);
    if (!inPicture) pictureFrame.resumeViewport();
  }

  @Override
  protected void onUserLeaveHint() {
    super.onUserLeaveHint();
    if (!leavingForActivity && !conversationHidden()) BrowserTasks.rememberBackground(this);
    // Android 8–11 没有 auto-enter；回桌面或切换应用时在这里进入。
    if (Build.VERSION.SDK_INT >= 26 && Build.VERSION.SDK_INT < 31) enterIfAllowed();
    else if (pictureFrame != null && mayEnter()) pictureFrame.freezeViewport();
  }

  @Override
  public boolean onPictureInPictureRequested() {
    return Build.VERSION.SDK_INT >= 26 && enterIfAllowed();
  }

  @Override
  public void onPictureInPictureModeChanged(boolean inPicture, Configuration config) {
    if (!inPicture) pictureExitRequested = false;
    presentation.picture(inPicture);
    updatePresentationLayout();
    if (pictureFrame != null) pictureFrame.setCompact(inPicture);
    super.onPictureInPictureModeChanged(inPicture, config);
    WebFullscreenUi.applySystemBars(this);
    if (inPicture && pictureFrame != null) {
      pictureGeneration =
          com.deepseekharness.app.core.HarnessController.get(this).getWebGeneration();
      android.view.inputmethod.InputMethodManager input =
          (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
      if (input != null) input.hideSoftInputFromWindow(pictureFrame.getWindowToken(), 0);
    }
    android.view.View content = findViewById(android.R.id.content);
    if (content != null) ViewCompat.requestApplyInsets(content);
    pictureHandler.removeCallbacks(pictureMonitor);
    if (inPicture) pictureHandler.post(pictureMonitor);
    refreshPictureInPicture();
    if (!inPicture && conversationHidden() && userHidden) hideConversationTask();
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (conversationHidden() && !userHidden) {
      presentation.show();
      updatePresentationLayout();
      WebFullscreenUi.applySystemBars(this);
    }
    if (showing(this)) {
      pictureHandler.removeCallbacks(pictureMonitor);
      pictureHandler.post(pictureMonitor);
    }
  }

  @Override
  protected void onStop() {
    // Android launchers need not deliver onUserLeaveHint before stopping this task.
    // Preserve the page-return intent before the ordinary hidden presentation is applied.
    if (!leavingForActivity && !conversationHidden()) BrowserTasks.rememberBackground(this);
    if (!pictureInPictureActiveOrTransitioning()) {
      presentation.hide();
      cancelConversationRequests();
    }
    pictureHandler.removeCallbacks(pictureMonitor);
    super.onStop();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    cancelConversationRequests();
    super.onNewIntent(intent);
    setIntent(intent);
    if (BrowserConversationRoute.current(
        intent, com.deepseekharness.app.core.HarnessController.get(this).getReadyWebPageIdentity()))
      receivePresentationIntent();
  }

  @Override
  protected void onResume() {
    super.onResume();
    leavingForActivity = conversationHidden();
    synchronizePictureViewport();
    refreshPictureInPicture();
    if (conversationHidden() && userHidden && !showing(this)) hideConversationTask();
    else BrowserTasks.browserResumed(this);
  }

  /** 文件选择、系统浏览器、恢复页等由应用主动打开时，不留下多余的小窗。 */
  private void suspendForActivity() {
    // A chooser or framework startActivity(request=-1) is not an explicit native destination.
    // Only Back/Home/native destination cancels the retained Home-return intent.
    leavingForActivity = true;
    refreshPictureInPicture();
  }

  @Override
  public void startActivityForResult(Intent intent, int request, Bundle options) {
    suspendForActivity();
    try {
      super.startActivityForResult(intent, request, options);
    } catch (RuntimeException error) {
      leavingForActivity = false;
      refreshPictureInPicture();
      throw error;
    }
  }

  @Override
  public void startIntentSenderForResult(
      IntentSender sender,
      int request,
      Intent intent,
      int mask,
      int values,
      int extraFlags,
      Bundle options)
      throws IntentSender.SendIntentException {
    suspendForActivity();
    try {
      super.startIntentSenderForResult(sender, request, intent, mask, values, extraFlags, options);
    } catch (IntentSender.SendIntentException | RuntimeException error) {
      leavingForActivity = false;
      refreshPictureInPicture();
      throw error;
    }
  }

  @Override
  protected void onDestroy() {
    pictureHandler.removeCallbacks(pictureMonitor);
    if (picturePreferences != null)
      picturePreferences.unregisterOnSharedPreferenceChangeListener(picturePreferenceListener);
    if (pictureReceiverRegistered) {
      unregisterReceiver(pictureReceiver);
      pictureReceiverRegistered = false;
    }
    if (pictureToggle != null) {
      pictureToggle.cancel();
      pictureToggle = null;
    }
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putString("browser-presentation", presentation.mode().name());
    out.putBoolean("browser-user-hidden", userHidden);
    if (pictureFrame != null) {
      out.putInt("pip-viewport-width", pictureFrame.viewportWidth());
      out.putInt("pip-viewport-height", pictureFrame.viewportHeight());
    }
    out.putLong("pip-web-generation", pictureGeneration);
    super.onSaveInstanceState(out);
  }
}
