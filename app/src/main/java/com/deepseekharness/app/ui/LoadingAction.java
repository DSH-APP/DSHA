package com.deepseekharness.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.Button;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 加载中按钮（A2）：在按钮左侧内联一个 16dp 转圈，忙碌期间锁定按钮宽度（不改文字），
 * 防止文案或控件尺寸跳动；系统关闭动画时显示静态弧，不用假进度掩盖真实状态。
 *
 * <p>异步流程用 {@link #start} / {@link #stop} 包住；同步动作可直接 {@link #run}。
 * 忙碌期间再次 start 返回 false，调用方据此避免重复提交。
 */
final class LoadingAction {
  private LoadingAction() {}

  private static final Map<Button, State> STATES = new WeakHashMap<>();

  static final class State {
    boolean busy;
    CharSequence label;
    int width;
    int minWidth;
    int maxWidth;
  }

  /** 开始忙碌态；返回 false 表示已有任务或按钮不可用。 */
  static boolean start(Button button) {
    if (button == null || !button.isEnabled()) return false;
    State state = STATES.computeIfAbsent(button, ignored -> new State());
    if (state.busy) return false;
    state.busy = true;
    state.label = button.getText();
    state.width = button.getMeasuredWidth();
    state.minWidth = button.getMinWidth();
    state.maxWidth = button.getMaxWidth();
    Context context = button.getContext();
    SpinnerDrawable spinner = new SpinnerDrawable(context);
    button.setCompoundDrawablesRelativeWithIntrinsicBounds(spinner, null, null, null);
    button.setCompoundDrawablePadding(dp(context, 8));
    if (state.width > 0) button.setMinWidth(Math.max(state.width, state.minWidth));
    button.setEnabled(false);
    button.setClickable(false);
    button.setStateDescription(UiText.choose("进行中", "In progress"));
    spinner.start();
    return true;
  }

  /** 结束忙碌态；未处于忙碌时无操作。 */
  static void stop(Button button) {
    if (button == null) return;
    State state = STATES.get(button);
    if (state == null || !state.busy) return;
    state.busy = false;
    Drawable[] drawables = button.getCompoundDrawablesRelative();
    if (drawables[0] instanceof SpinnerDrawable spinner) spinner.stop();
    button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
    button.setMinWidth(state.minWidth);
    button.setMaxWidth(state.maxWidth);
    if (state.label != null) button.setText(state.label);
    button.setEnabled(true);
    button.setClickable(true);
    button.setStateDescription(null);
  }

  /** 同步动作：成功后自动结束忙碌态；异常也恢复，按钮不会永久卡住。 */
  static boolean run(Button button, Runnable action) {
    if (action == null || !start(button)) return false;
    try {
      action.run();
      return true;
    } finally {
      stop(button);
    }
  }

  static boolean busy(Button button) {
    State state = button == null ? null : STATES.get(button);
    return state != null && state.busy;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  /** 转圈弧：真实指示「任务进行中」，结束即停；系统关闭动画时保持静态弧。 */
  static final class SpinnerDrawable extends Drawable {
    private static final long PERIOD_MS = 900L;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Context context;
    private final int size;
    private ValueAnimator animator;
    private float angle;

    SpinnerDrawable(Context context) {
      this.context = context;
      this.size = dp(context, 16);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeWidth(Math.max(1f, dp(context, 2)));
      paint.setColor(context.getColor(R.color.shell_accent));
    }

    void start() {
      stop();
      if (!UiMotion.animationsEnabled(context)) {
        angle = 0f;
        invalidateSelf();
        return;
      }
      animator = ValueAnimator.ofFloat(0f, 360f);
      animator.setDuration(PERIOD_MS);
      animator.setRepeatCount(ValueAnimator.INFINITE);
      animator.addUpdateListener(
          running -> {
            angle = (Float) running.getAnimatedValue();
            invalidateSelf();
          });
      animator.start();
    }

    void stop() {
      if (animator != null) {
        animator.cancel();
        animator = null;
      }
      angle = 0f;
      invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
      float stroke = paint.getStrokeWidth();
      arc.set(stroke, stroke, getBounds().width() - stroke, getBounds().height() - stroke);
      canvas.save();
      canvas.rotate(angle, getBounds().width() / 2f, getBounds().height() / 2f);
      canvas.drawArc(arc, -90f, 270f, false, paint);
      canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
      paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
      paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
      return size;
    }

    @Override
    public int getIntrinsicHeight() {
      return size;
    }
  }
}
