package com.deepseekharness.app.ui;

import android.view.View;
import android.view.ViewGroup;
import com.deepseekharness.app.util.OnboardingMotionPlan;
import com.deepseekharness.app.util.OnboardingMotionPlan.Kind;

/**
 * 首次引导动效的视图执行层：步骤正文错峰滑入、页脚上浮、步骤条填充与勾选弹出。
 *
 * <p>何时动、往哪动由 {@link OnboardingMotionPlan} 决定；这里只用属性动画改 alpha / translation /
 * scale，不改布局、不拦截触摸，动画进行中按钮照常可点，无障碍树不受影响。系统关闭动画时调用方得到 {@link
 * Kind#NONE}，本类不做任何事。
 */
final class OnboardingMotion {
  private OnboardingMotion() {}

  /** 正文逐项滑入：横移距离按步骤方向，首次入场改为纵向上浮。 */
  static void content(ViewGroup parent, Kind kind) {
    if (parent == null || kind == Kind.NONE) return;
    float density = parent.getResources().getDisplayMetrics().density;
    boolean rtl = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    float dx = OnboardingMotionPlan.slideSign(kind, rtl) * 20f * density;
    float dy = (kind == Kind.INTRO ? 14f : 4f) * density;
    long duration = OnboardingMotionPlan.duration(kind);
    for (int i = 0, n = parent.getChildCount(); i < n; i++) {
      View child = parent.getChildAt(i);
      if (child == null || child.getVisibility() != View.VISIBLE) continue;
      enter(child, dx, dy, OnboardingMotionPlan.delay(kind, i), duration);
    }
  }

  /** 页脚按钮区上浮淡入（页脚底色与分隔线不动）；容器跨步骤复用，新动画接替旧动画，不会停在半透明。 */
  static void footer(View footer, Kind kind) {
    if (footer == null || kind == Kind.NONE) return;
    float density = footer.getResources().getDisplayMetrics().density;
    long delay = kind == Kind.INTRO ? 2 * OnboardingMotionPlan.INTRO_STAGGER_MS : 0L;
    enter(footer, 0f, 10f * density, delay, OnboardingMotionPlan.duration(kind));
  }

  /** 手机步骤条：新点亮的分段从行首方向横向填满。 */
  static void segments(ViewGroup bar, Kind kind, int from, int to) {
    if (bar == null || kind == Kind.NONE) return;
    int[] range = OnboardingMotionPlan.filledSegments(kind, from, to);
    boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    for (int i = Math.max(0, range[0]); i <= range[1] && i < bar.getChildCount(); i++) {
      View segment = bar.getChildAt(i);
      int order = i - range[0];
      segment.addOnLayoutChangeListener(
          new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
              v.removeOnLayoutChangeListener(this);
              v.setPivotX(rtl ? v.getWidth() : 0f);
            }
          });
      segment.setPivotX(rtl ? segment.getWidth() : 0f);
      segment.setScaleX(0f);
      segment
          .animate()
          .scaleX(1f)
          .setStartDelay(order * 70L)
          .setDuration(320L)
          .setInterpolator(UiMotion.standard())
          .start();
    }
  }

  /** 当前步骤标记的轻微弹出，跟随步骤过渡。 */
  static void pop(View view, Kind kind) {
    if (kind == Kind.NONE) return;
    pop(view, kind == Kind.INTRO ? 120L : 60L);
  }

  /** 勾选框在用户点按后的轻微弹出（0.6 → 1），确认操作已生效；系统关闭动画时不动。 */
  static void popIfEnabled(View view) {
    if (view != null && UiMotion.animationsEnabled(view.getContext())) pop(view, 0L);
  }

  private static void pop(View view, long delay) {
    if (view == null) return;
    view.animate().cancel();
    view.setScaleX(0.6f);
    view.setScaleY(0.6f);
    view.animate()
        .scaleX(1f)
        .scaleY(1f)
        .setStartDelay(delay)
        .setDuration(260L)
        .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
        .start();
  }

  private static void enter(View view, float dx, float dy, long delay, long duration) {
    view.animate().cancel();
    view.setAlpha(0f);
    view.setTranslationX(dx);
    view.setTranslationY(dy);
    view.animate()
        .alpha(1f)
        .translationX(0f)
        .translationY(0f)
        .setStartDelay(delay)
        .setDuration(duration)
        .setInterpolator(UiMotion.standard())
        .withEndAction(
            () -> {
              // 被取消或复用时保证落在静态位置。
              view.setAlpha(1f);
              view.setTranslationX(0f);
              view.setTranslationY(0f);
            })
        .start();
  }
}
