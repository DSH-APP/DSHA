package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.deepseekharness.app.util.UiThemePreference;

/**
 * 亮暗切换时先截当前窗口，Activity recreate 后用圆形揭示收回旧画面，避免整屏闪黑。
 * minSdk 23 可用；截图用 View.draw（PixelCopy 异步，recreate 前等不到结果）。
 */
public final class ThemeTransition {
  private static final long DURATION_MS = 420L;

  @Nullable private static Bitmap pending;
  private static int originX;
  private static int originY;
  private static long capturedAt;

  private ThemeTransition() {}

  /** 当前偏好切过去后实际亮暗是否会变。跟随系统且系统同色时返回 false。 */
  public static boolean wouldChangeAppearance(Context context, String mode) {
    boolean nowDark = ThemeController.isDark(context);
    boolean nextDark = UiThemePreference.isDark(mode, systemNight());
    return nowDark != nextDark;
  }

  /** 系统夜模式。AppCompat 的强制亮/暗只作用于 Activity 配置，Resources.getSystem() 仍是系统值。 */
  static boolean systemNight() {
    return (android.content.res.Resources.getSystem().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
  }

  /** 若目标模式会改变实际亮暗，则截图并记下锚点。返回是否捕获成功。 */
  public static boolean capture(@NonNull Activity activity, @Nullable View origin) {
    recycle();
    Window window = activity.getWindow();
    View decor = window == null ? null : window.getDecorView();
    if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0) return false;
    int[] loc = new int[2];
    if (origin != null && origin.getWidth() > 0) {
      origin.getLocationInWindow(loc);
      originX = loc[0] + origin.getWidth() / 2;
      originY = loc[1] + origin.getHeight() / 2;
    } else {
      originX = decor.getWidth() / 2;
      originY = decor.getHeight() / 2;
    }
    Bitmap bitmap = drawView(decor);
    if (bitmap == null) return false;
    pending = bitmap;
    capturedAt = android.os.SystemClock.uptimeMillis();
    return true;
  }

  public static void select(
      @NonNull Activity activity, @Nullable View origin, @NonNull String mode) {
    if (!wouldChangeAppearance(activity, mode)) {
      recycle();
      ThemeController.select(activity, mode);
      return;
    }
    capture(activity, origin);
    ThemeController.select(activity, mode);
  }

  /** 新 Activity 建好内容后叠旧截图并反向圆形收缩。无截图时为空操作。 */
  public static void playIfPending(@NonNull Activity activity) {
    Bitmap bitmap = pending;
    if (bitmap == null || bitmap.isRecycled()) {
      pending = null;
      return;
    }
    // 截图超过 3 秒说明不是这次重建带来的，直接丢弃。
    if (android.os.SystemClock.uptimeMillis() - capturedAt > 3000L) {
      recycle();
      return;
    }
    Window window = activity.getWindow();
    if (window == null) {
      recycle();
      return;
    }
    View decor = window.getDecorView();
    if (!(decor instanceof ViewGroup)) {
      recycle();
      return;
    }
    ViewGroup root = (ViewGroup) decor;
    ImageView overlay = new ImageView(activity);
    overlay.setImageBitmap(bitmap);
    overlay.setScaleType(ImageView.ScaleType.FIT_XY);
    overlay.setClickable(true);
    overlay.setFocusable(true);
    overlay.setLayoutParams(
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    root.addView(overlay);
    if (overlay.getWidth() > 0 && overlay.getHeight() > 0) {
      overlay.post(() -> startReveal(root, overlay, bitmap));
      return;
    }
    // View.post 在首次布局之前就会执行，宽高仍为 0；等到第一次布局完成再开始。
    overlay.addOnLayoutChangeListener(
        new View.OnLayoutChangeListener() {
          @Override
          public void onLayoutChange(
              View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            if (r - l <= 0 || b - t <= 0) return;
            v.removeOnLayoutChangeListener(this);
            v.post(() -> startReveal(root, overlay, bitmap));
          }
        });
  }

  private static void startReveal(ViewGroup root, ImageView overlay, Bitmap bitmap) {
    if (overlay.getParent() == null || overlay.getWidth() <= 0 || overlay.getHeight() <= 0) {
      finish(root, overlay, bitmap);
      return;
    }
    int cx = clamp(originX, 0, overlay.getWidth());
    int cy = clamp(originY, 0, overlay.getHeight());
    float startRadius =
        (float)
            Math.hypot(
                Math.max(cx, overlay.getWidth() - cx), Math.max(cy, overlay.getHeight() - cy));
    try {
      android.animation.Animator reveal =
          ViewAnimationUtils.createCircularReveal(overlay, cx, cy, startRadius, 0f);
      reveal.setDuration(DURATION_MS);
      reveal.addListener(
          new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
              finish(root, overlay, bitmap);
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
              finish(root, overlay, bitmap);
            }
          });
      reveal.start();
    } catch (RuntimeException ignored) {
      clipFallback(overlay, cx, cy, startRadius, () -> finish(root, overlay, bitmap));
    }
  }

  private static void clipFallback(
      ImageView overlay, int cx, int cy, float startRadius, Runnable done) {
    final float[] radius = {startRadius};
    overlay.setOutlineProvider(
        new ViewOutlineProvider() {
          @Override
          public void getOutline(View view, Outline outline) {
            int r = Math.max(0, (int) radius[0]);
            outline.setOval(cx - r, cy - r, cx + r, cy + r);
          }
        });
    overlay.setClipToOutline(true);
    android.animation.ValueAnimator animator =
        android.animation.ValueAnimator.ofFloat(startRadius, 0f);
    animator.setDuration(DURATION_MS);
    animator.addUpdateListener(
        a -> {
          radius[0] = (Float) a.getAnimatedValue();
          overlay.invalidateOutline();
        });
    animator.addListener(
        new android.animation.AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(android.animation.Animator animation) {
            done.run();
          }

          @Override
          public void onAnimationCancel(android.animation.Animator animation) {
            done.run();
          }
        });
    animator.start();
  }

  private static void finish(ViewGroup root, ImageView overlay, Bitmap bitmap) {
    try {
      root.removeView(overlay);
    } catch (RuntimeException ignored) {
    }
    overlay.setImageDrawable(null);
    if (pending == bitmap) pending = null;
    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
  }

  public static void recycle() {
    Bitmap bitmap = pending;
    pending = null;
    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
  }

  @Nullable
  private static Bitmap drawView(View decor) {
    try {
      Bitmap bitmap =
          Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(bitmap);
      decor.draw(canvas);
      return bitmap;
    } catch (OutOfMemoryError | RuntimeException e) {
      return null;
    }
  }

  private static int clamp(int value, int min, int max) {
    return value < min ? min : Math.min(value, max);
  }
}
