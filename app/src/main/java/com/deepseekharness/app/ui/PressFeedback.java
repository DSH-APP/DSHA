package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import com.deepseekharness.app.R;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 按压反馈（A1/C1/C2）：行只补状态层，按钮轻微缩放，大卡片做圆角形变。
 *
 * <p>三种变体是「相似但按场景不同」：列表行不加缩放（避免整行位移），主按钮缩放 0.985
 * （120–140ms，文字跟随控件中心缩放，不重排版），大卡片按压时圆角 18→22dp。
 * 系统关闭动画时不缩放/不变形；禁用控件不缩放，并可用 {@link #disabled} 把原因写进读屏。
 */
final class PressFeedback {
  private PressFeedback() {}

  enum Variant {
    ROW,
    BUTTON,
    CARD
  }

  private static final Map<View, Variant> ATTACHED = new WeakHashMap<>();

  static void attach(View view, Variant variant) {
    if (view == null || variant == null || ATTACHED.containsKey(view)) return;
    ATTACHED.put(view, variant);
    switch (variant) {
      case BUTTON -> UiMotion.pressScale(view);
      case ROW -> stateLayer(view);
      case CARD -> card(view);
    }
  }

  /** 只给按钮补按压缩放：覆盖程序化按钮与 XML 页面新增按钮，不碰自定义触摸区。 */
  static void attachButtons(View root) {
    if (root == null) return;
    if (root instanceof Button) {
      attach(root, Variant.BUTTON);
      return;
    }
    if (root instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) attachButtons(group.getChildAt(i));
    }
  }

  /** 行状态层：已有水波背景的保留，没有的补一层可点行的状态层。 */
  private static void stateLayer(View view) {
    Context context = view.getContext();
    if (view.getForeground() != null) return;
    if (view.getBackground() instanceof RippleDrawable) return;
    try {
      view.setForeground(context.getDrawable(R.drawable.bg_action_plain));
    } catch (RuntimeException ignored) {
      /* 主题缺资源时保持原背景，不影响点击。 */
    }
  }

  /** 卡片圆角形变：克隆卡片背景（不改共享 drawable），按压 18→22dp。 */
  private static void card(View view) {
    Drawable background = view.getBackground();
    GradientDrawable shape = null;
    if (background instanceof GradientDrawable gradient) {
      Drawable copy =
          gradient.getConstantState() == null
              ? null
              : gradient.getConstantState().newDrawable().mutate();
      if (copy instanceof GradientDrawable cloned) {
        shape = cloned;
        view.setBackground(cloned);
      }
    } else if (background != null && background.getConstantState() != null) {
      Drawable copy = background.getConstantState().newDrawable().mutate();
      view.setBackground(copy);
    }
    if (shape == null) return;
    float rest = view.getResources().getDimension(R.dimen.shell_launch_radius_card);
    float pressed = Math.round(rest + 4f * view.getResources().getDisplayMetrics().density);
    GradientDrawable target = shape;
    view.setOnTouchListener(
        (v, event) -> {
          if (!UiMotion.animationsEnabled(v.getContext()) || !v.isEnabled()) return false;
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> UiMotion.cornerMorph(v, target, pressed);
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                UiMotion.cornerMorph(v, target, rest);
            default -> {}
          }
          return false;
        });
  }

  /** 禁用反馈：禁用不缩放，并把原因写进读屏与状态描述；恢复时清掉。 */
  static void disabled(View view, boolean disabled, CharSequence reason) {
    if (view == null) return;
    view.setEnabled(!disabled);
    if (disabled) {
      CharSequence why = reason == null ? "" : reason.toString().trim();
      view.setStateDescription(android.text.TextUtils.isEmpty(why) ? null : why);
      view.setAlpha(0.45f);
    } else {
      view.setStateDescription(null);
      view.setAlpha(1f);
    }
  }
}
