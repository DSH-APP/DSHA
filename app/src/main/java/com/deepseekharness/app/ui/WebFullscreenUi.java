package com.deepseekharness.app.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.deepseekharness.app.R;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** 两种网页内核共用安全显示区；系统栏、挖孔和输入法都不能覆盖正文。 */
public final class WebFullscreenUi {
  /** 避免全局页面边距处理覆盖网页的全屏设置。 */
  public interface Host {
    default Object imeBridgeIdentity() {
      return null;
    }

    default boolean sendImeFrame(Object expected, org.json.JSONObject frame) {
      return false;
    }
  }

  private static final Map<Activity, WeakReference<ContentInsets>> IME = new WeakHashMap<>();
  private static final java.util.concurrent.atomic.AtomicLong IME_EPOCH =
      new java.util.concurrent.atomic.AtomicLong();

  public static void acknowledgeIme(
      Activity activity, Object identity, long frame, boolean supported) {
    activity.runOnUiThread(
        () -> {
          var ref = IME.get(activity);
          if (ref != null && ref.get() != null) ref.get().acknowledge(identity, frame, supported);
        });
  }

  public static void cancelIme(Activity activity) {
    var ref = IME.get(activity);
    if (ref != null && ref.get() != null) {
      ref.get().forgetKeyboard();
      ref.get().render();
    }
  }

  public static void refreshIme(Activity activity) {
    View content = activity.findViewById(android.R.id.content);
    if (content != null) ViewCompat.requestApplyInsets(content);
  }

  private WebFullscreenUi() {}

  public static void install(Activity activity) {
    // 旧 Android 的 FLAG_FULLSCREEN 会阻止 adjustResize，不能依赖输入法出现时再取消全屏。
    activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
    WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
    View content = activity.findViewById(android.R.id.content);
    activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
    new ContentInsets(activity, content);
    applySystemBars(activity);
    ViewCompat.requestApplyInsets(content);
  }

  /** 容器统一处理尺寸，子浏览器不能再次扣除同一份安全区和输入法边距。 */
  static final class ContentInsets extends WindowInsetsAnimationCompat.Callback
      implements OnApplyWindowInsetsListener,
          ViewTreeObserver.OnWindowFocusChangeListener,
          View.OnAttachStateChangeListener {
    private final Activity activity;
    private final View content;
    private final Set<WindowInsetsAnimationCompat> imeAnimations =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private ViewTreeObserver focusObserver;
    private Insets safe = Insets.NONE;
    private int keyboard;
    private boolean attached;
    private int committedKeyboard;
    private boolean expanded;
    private Object bridgeIdentity;
    private boolean bridgeSupported;
    private long epoch = IME_EPOCH.incrementAndGet(), sequence, inFlight;
    private org.json.JSONObject pendingFrame;
    private boolean canceled = true;
    private int observedWidth;

    ContentInsets(Activity activity, View content) {
      super(DISPATCH_MODE_STOP);
      this.activity = activity;
      this.content = content;
      IME.put(activity, new WeakReference<>(this));
      // Core 1.9 先调用同一 View 的 API21 动画适配，再派发普通边距监听。
      // 此处消费仍保留兼容动画，并避免 WebView 再扣一次输入法边距。
      ViewCompat.setWindowInsetsAnimationCallback(content, this);
      ViewCompat.setOnApplyWindowInsetsListener(content, this);
      content.addOnAttachStateChangeListener(this);
      if (ViewCompat.isAttachedToWindow(content)) onViewAttachedToWindow(content);
    }

    private boolean ownsKeyboard() {
      return attached
          && content.hasWindowFocus()
          && !activity.isFinishing()
          && !activity.isDestroyed()
          && !PictureInPictureActivity.showing(activity);
    }

    private void forgetKeyboard() {
      imeAnimations.clear();
      keyboard = 0;
      committedKeyboard = 0;
      expanded = false;
      cancelFrame();
    }

    @Override
    public WindowInsetsCompat onApplyWindowInsets(View view, WindowInsetsCompat insets) {
      checkWidth();
      safe = safeInsets(insets);
      if (!ownsKeyboard()) forgetKeyboard();
      // prepare 与 start 之间的 onApply 已是终态；先保留当前输入法高度，
      // 等 progress 提供实际起始或中间帧，不能提前跳到终点。
      else if (imeAnimations.isEmpty())
        keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
      render();
      if (ownsKeyboard()) offerFrame(imeAnimations.isEmpty() ? "end" : "frame");
      return WindowInsetsCompat.CONSUMED;
    }

    @Override
    public void onPrepare(WindowInsetsAnimationCompat animation) {
      if (!ownsKeyboard()) {
        forgetKeyboard();
        render();
      } else if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) {
        imeAnimations.add(animation);
        expanded |= bridgeSupported && inFlight == 0 && pendingFrame == null;
        render();
        offerFrame("frame");
      }
    }

    @Override
    public WindowInsetsCompat onProgress(
        WindowInsetsCompat insets, List<WindowInsetsAnimationCompat> runningAnimations) {
      checkWidth();
      if (attached) safe = safeInsets(insets);
      if (!ownsKeyboard()) forgetKeyboard();
      else if (runningAnimations.stream().anyMatch(imeAnimations::contains)) {
        keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        offerFrame("frame");
      }
      // 动画帧只更新绝对可见区域；不逐帧改变浏览器尺寸或整体平移网页。
      if (!ownsKeyboard()) render();
      return WindowInsetsCompat.CONSUMED;
    }

    @Override
    public void onEnd(WindowInsetsAnimationCompat animation) {
      if (imeAnimations.remove(animation) && imeAnimations.isEmpty() && attached) {
        expanded = false;
        render();
        offerFrame("end");
        // 取消也会结束动画；请求窗口当前边距，不重放先前缓存的终态。
        // 新派发到达前保留最后一帧的实际高度。
        ViewCompat.requestApplyInsets(content);
      }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
      if (!hasFocus) {
        forgetKeyboard();
        render();
      } else if (attached) ViewCompat.requestApplyInsets(content);
    }

    @Override
    public void onViewAttachedToWindow(View view) {
      attached = true;
      focusObserver = content.getViewTreeObserver();
      focusObserver.addOnWindowFocusChangeListener(this);
      ViewCompat.requestApplyInsets(content);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
      attached = false;
      if (focusObserver != null && focusObserver.isAlive())
        focusObserver.removeOnWindowFocusChangeListener(this);
      focusObserver = null;
      forgetKeyboard();
      render();
    }

    private void render() {
      int nativeKeyboard = imeAnimations.isEmpty() ? keyboard : expanded ? 0 : committedKeyboard;
      if (imeAnimations.isEmpty()) committedKeyboard = keyboard;
      Insets padding =
          PictureInPictureActivity.showing(activity)
              ? Insets.NONE
              : Insets.of(safe.left, safe.top, safe.right, Math.max(safe.bottom, nativeKeyboard));
      if (content.getPaddingLeft() != padding.left
          || content.getPaddingTop() != padding.top
          || content.getPaddingRight() != padding.right
          || content.getPaddingBottom() != padding.bottom)
        content.setPadding(padding.left, padding.top, padding.right, padding.bottom);
    }

    private void checkWidth() {
      int width = content.getWidth();
      if (observedWidth > 0 && width > 0 && width != observedWidth) {
        forgetKeyboard();
        render();
        ViewCompat.requestApplyInsets(content);
      }
      if (width > 0) observedWidth = width;
    }

    private void offerFrame(String phase) {
      if (!(activity instanceof Host host)) return;
      Object identity = host.imeBridgeIdentity();
      if (identity != bridgeIdentity) {
        cancelFrame();
        bridgeIdentity = identity;
        bridgeSupported = false;
      }
      if (identity == null) return;
      canceled = false;
      int width = content.getWidth() - safe.left - safe.right;
      int height = content.getHeight() - safe.top - Math.max(safe.bottom, keyboard);
      if (width <= 0 || height <= 0) return;
      try {
        pendingFrame =
            new org.json.JSONObject()
                .put("frameId", ++sequence)
                .put("epoch", epoch)
                .put("width", width)
                .put("height", height)
                .put("phase", phase);
        flushFrame(host);
      } catch (org.json.JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    private void flushFrame(Host host) {
      if (inFlight != 0 || pendingFrame == null) return;
      org.json.JSONObject frame = pendingFrame;
      pendingFrame = null;
      inFlight = frame.optLong("frameId");
      boolean sent;
      try {
        sent = host.sendImeFrame(bridgeIdentity, frame);
      } catch (RuntimeException unavailable) {
        sent = false;
      }
      if (!sent) {
        inFlight = 0;
        bridgeSupported = false;
      }
    }

    private void acknowledge(Object identity, long frame, boolean supported) {
      if (identity != bridgeIdentity || frame != inFlight || inFlight == 0) return;
      inFlight = 0;
      bridgeSupported = supported;
      if (supported && !imeAnimations.isEmpty() && ownsKeyboard() && !expanded) {
        // hide 只在边界扩容一次；网页 resize 按同一绝对目标保持 composer 的位置。
        expanded = true;
        render();
      }
      if (activity instanceof Host host) flushFrame(host);
    }

    private void cancelFrame() {
      if (canceled && inFlight == 0 && pendingFrame == null) return;
      canceled = true;
      epoch = IME_EPOCH.incrementAndGet();
      pendingFrame = null;
      inFlight = 0;
      bridgeSupported = false;
      if (bridgeIdentity != null && activity instanceof Host host) {
        try {
          host.sendImeFrame(
              bridgeIdentity,
              new org.json.JSONObject()
                  .put("phase", "cancel")
                  .put("epoch", epoch)
                  .put("frameId", ++sequence));
        } catch (org.json.JSONException impossible) {
          throw new IllegalStateException(impossible);
        } catch (RuntimeException unavailable) {
          /* 页面或端口已退休，不把旧帧发给新页面。 */
        }
      }
    }
  }

  private static Insets safeInsets(WindowInsetsCompat insets) {
    Insets bars =
        insets.getInsets(
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
    // 焦点或输入法切换时仍保留稳定状态栏、挖孔与小窗标题栏安全区。
    Insets stableTop =
        insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.statusBars()
                | WindowInsetsCompat.Type.displayCutout()
                | WindowInsetsCompat.Type.captionBar());
    return Insets.max(bars, stableTop);
  }

  public static void applySystemBars(Activity activity) {
    if (PictureInPictureActivity.showing(activity)) return;
    WindowInsetsControllerCompat controller =
        WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
    int color =
        activity instanceof PictureInPictureActivity host
            ? host.browserThemeColor()
            : activity.getColor(R.color.surface);
    View content = activity.findViewById(android.R.id.content);
    if (content != null) {
      int left = content.getPaddingLeft(), top = content.getPaddingTop();
      int right = content.getPaddingRight(), bottom = content.getPaddingBottom();
      content.setBackgroundColor(color);
      content.setPadding(left, top, right, bottom);
    }
    controller.setAppearanceLightStatusBars(
        androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5);
    controller.setAppearanceLightNavigationBars(
        androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5);
    activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
    activity
        .getWindow()
        .setNavigationBarColor(
            android.os.Build.VERSION.SDK_INT >= 26
                ? color
                : activity.getColor(R.color.terminal_surface));
    controller.setSystemBarsBehavior(
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    // 显示状态栏，正文由 insets 避让；底部仍可通过手势唤出系统导航。
    controller.show(WindowInsetsCompat.Type.statusBars());
    controller.hide(WindowInsetsCompat.Type.navigationBars());
  }

  /** 当前实例核验后的主题色只改变绘制背景和图标，不重新加载页面。 */
  public static void applyThemeColor(Activity activity, int color) {
    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
    if (activity instanceof PictureInPictureActivity host) host.applyBrowserTheme(color);
    applySystemBars(activity);
  }
}
