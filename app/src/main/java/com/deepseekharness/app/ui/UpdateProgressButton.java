package com.deepseekharness.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UpdateActionPolicy;
import com.deepseekharness.app.util.UpdateActionPolicy.Mode;

/**
 * 更新页主按钮：按钮本身就是进度条，宽高与位置始终不变。
 *
 * <p>IDLE 实心可点；SWEEP 浅底 + 流光（检查 / 总大小未知的下载）；FILL 从起始边向末端填充；FULL 填满 + 微光
 * （下载后核验 / 安装前重新校验）；WAIT 填满静止（等待打开系统安装器）。文字两层：底层按底色着色，上层按填充宽度裁切，
 * 跨过填充边界也看得清。只有填充增长时做过渡；回到可点或进入核验时直接跳到位，不倒放进度。
 */
public final class UpdateProgressButton extends FrameLayout {
  private static final long FILL_MS = 400;
  private static final long SWEEP_MS = 1300;

  private final TextView base;
  private final TextView lite;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path clip = new Path();
  private final RectF bounds = new RectF();
  private final Rect liteClip = new Rect();
  private final Matrix bandMatrix = new Matrix();
  private final float radius;
  private final int accent;
  private final int accentSoft;
  private final int onAccent;
  private final int line;

  private UpdateActionPolicy.Action action;
  private float fraction;
  private float sweep;
  private ValueAnimator fillAnimator;
  private ValueAnimator sweepAnimator;

  public UpdateProgressButton(Context context) {
    this(context, null);
  }

  public UpdateProgressButton(Context context, AttributeSet attrs) {
    super(context, attrs);
    setWillNotDraw(false);
    radius = getResources().getDimension(R.dimen.radius_control);
    accent = ContextCompat.getColor(context, R.color.shell_accent);
    accentSoft = ContextCompat.getColor(context, R.color.shell_accent_soft);
    onAccent = ContextCompat.getColor(context, R.color.shell_on_accent);
    line = ContextCompat.getColor(context, R.color.shell_line_strong);
    setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.shell_button_height));
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeWidth(getResources().getDisplayMetrics().density);
    stroke.setColor(line);
    base = label(context);
    lite = label(context);
    lite.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    base.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(base, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    addView(lite, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    GradientDrawable mask = new GradientDrawable();
    mask.setCornerRadius(radius);
    mask.setColor(0xFFFFFFFF);
    setForeground(
        new RippleDrawable(
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary_ripple)),
            null,
            mask));
    bind(
        UpdateActionPolicy.resolve(
            UpdateActionPolicy.Stage.IDLE, false, false, 0, 0, false, false, false),
        "");
  }

  private TextView label(Context context) {
    TextView view = new TextView(context);
    float dp = getResources().getDisplayMetrics().density;
    view.setGravity(Gravity.CENTER);
    view.setIncludeFontPadding(false);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    view.setMaxLines(2);
    view.setPadding(
        Math.round(16 * dp), Math.round(12 * dp), Math.round(16 * dp), Math.round(12 * dp));
    view.setDuplicateParentStateEnabled(true);
    return view;
  }

  /** 原位切换模式与文字；同一实例贯穿检查 → 下载 → 核验 → 安装。 */
  public void bind(UpdateActionPolicy.Action next, CharSequence text) {
    UpdateActionPolicy.Action previous = action;
    action = next;
    base.setText(text);
    lite.setText(text);
    setContentDescription(text);
    boolean clickable = next.clickable();
    setEnabled(clickable);
    setClickable(clickable);
    setFocusable(true);
    setAlpha(next.mode == Mode.WAIT ? 0.72f : 1f);
    float target = target(next);
    // 只有下载中填充增长才过渡；其它切换直接到位，避免“倒放”或从满格回落。
    boolean grow =
        previous != null
            && previous.mode == Mode.FILL
            && next.mode == Mode.FILL
            && target > fraction;
    if (fillAnimator != null) fillAnimator.cancel();
    if (grow && ValueAnimator.areAnimatorsEnabled()) {
      fillAnimator = ValueAnimator.ofFloat(fraction, target);
      fillAnimator.setDuration(FILL_MS);
      fillAnimator.setInterpolator(new DecelerateInterpolator());
      fillAnimator.addUpdateListener(
          animation -> {
            fraction = (float) animation.getAnimatedValue();
            applyLabels();
            invalidate();
          });
      fillAnimator.start();
    } else {
      fraction = target;
    }
    applyLabels();
    updateSweep();
    invalidate();
  }

  public UpdateActionPolicy.Action action() {
    return action;
  }

  /** 是否处于进度展示（流光 / 填充 / 核验）；WAIT 与可点状态不算。 */
  public boolean animating() {
    return action.mode == Mode.SWEEP || action.mode == Mode.FILL || action.mode == Mode.FULL;
  }

  /** 是否为确定进度；SWEEP / FULL 为不定进度。 */
  public boolean indeterminate() {
    return action.mode != Mode.FILL;
  }

  public CharSequence text() {
    return base.getText();
  }

  private static float target(UpdateActionPolicy.Action action) {
    switch (action.mode) {
      case FILL:
        return Math.max(0, action.percent) / 100f;
      case FULL:
      case WAIT:
        return 1f;
      default:
        return 0f;
    }
  }

  private void updateSweep() {
    boolean want =
        action != null
            && getVisibility() == VISIBLE
            && (action.mode == Mode.SWEEP || action.mode == Mode.FULL)
            && isAttachedToWindow()
            && ValueAnimator.areAnimatorsEnabled();
    if (!want) {
      if (sweepAnimator != null) sweepAnimator.cancel();
      sweepAnimator = null;
      return;
    }
    if (sweepAnimator != null) return;
    sweepAnimator = ValueAnimator.ofFloat(-1f, 1f);
    sweepAnimator.setDuration(SWEEP_MS);
    sweepAnimator.setRepeatCount(ValueAnimator.INFINITE);
    sweepAnimator.addUpdateListener(
        animation -> {
          sweep = (float) animation.getAnimatedValue();
          invalidate();
        });
    sweepAnimator.start();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    updateSweep();
  }

  @Override
  protected void onDetachedFromWindow() {
    if (sweepAnimator != null) sweepAnimator.cancel();
    sweepAnimator = null;
    if (fillAnimator != null) fillAnimator.end();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    bounds.set(0, 0, w, h);
    clip.reset();
    clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
    band.setShader(null); // 光带渐变按宽度重建
    if (action != null) applyLabels();
  }

  private boolean rtl() {
    return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    Mode mode = action.mode;
    int save = canvas.save();
    canvas.clipPath(clip);
    if (mode == Mode.IDLE) {
      paint.setShader(null);
      paint.setColor(accent);
      canvas.drawRect(bounds, paint);
    } else {
      paint.setShader(null);
      paint.setColor(accentSoft);
      canvas.drawRect(bounds, paint);
      float width = bounds.width() * fraction;
      if (width > 0) {
        paint.setColor(accent);
        if (rtl()) canvas.drawRect(bounds.right - width, 0, bounds.right, bounds.bottom, paint);
        else canvas.drawRect(0, 0, width, bounds.bottom, paint);
      }
      if (mode == Mode.SWEEP || mode == Mode.FULL) drawBand(canvas, mode == Mode.FULL);
    }
    canvas.restoreToCount(save);
    if (mode != Mode.IDLE) {
      float inset = stroke.getStrokeWidth() / 2f;
      canvas.drawRoundRect(
          inset, inset, bounds.right - inset, bounds.bottom - inset, radius, radius, stroke);
    }
  }

  /** 底层文字颜色跟随底色；上层浅色文字只露出填充部分。不在 onDraw 里改子视图，避免重绘循环。 */
  private void applyLabels() {
    Mode mode = action.mode;
    boolean solid = mode == Mode.IDLE || mode == Mode.FULL || mode == Mode.WAIT;
    base.setTextColor(solid ? onAccent : accent);
    lite.setTextColor(onAccent);
    int width = Math.round(getWidth() * fraction);
    if (mode == Mode.FILL && width > 0) {
      if (rtl()) liteClip.set(getWidth() - width, 0, getWidth(), getHeight());
      else liteClip.set(0, 0, width, getHeight());
      lite.setClipBounds(liteClip);
      lite.setVisibility(VISIBLE);
    } else {
      lite.setVisibility(INVISIBLE);
    }
  }

  /** 流光：一条半透明光带横穿按钮；FULL 用浅色叠在主色上，SWEEP 用主色叠在浅底上。 */
  private void drawBand(Canvas canvas, boolean onSolid) {
    float w = bounds.width();
    if (w <= 0) return;
    int color = onSolid ? 0x38FFFFFF : (accent & 0x00FFFFFF) | 0x38000000;
    if (band.getShader() == null || band.getColor() != color) {
      band.setColor(color);
      band.setShader(
          new LinearGradient(
              0,
              0,
              w,
              0,
              new int[] {0, color, 0},
              new float[] {0.3f, 0.5f, 0.7f},
              Shader.TileMode.CLAMP));
    }
    bandMatrix.setTranslate(w * sweep, 0);
    band.getShader().setLocalMatrix(bandMatrix);
    canvas.drawRect(bounds, band);
  }

  @Override
  public CharSequence getAccessibilityClassName() {
    return action != null && action.clickable()
        ? android.widget.Button.class.getName()
        : android.widget.ProgressBar.class.getName();
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    if (action == null || action.clickable()) return;
    if (action.mode == Mode.FILL)
      info.setRangeInfo(
          AccessibilityNodeInfo.RangeInfo.obtain(
              AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0, 100, action.percent));
  }

  @Override
  protected void onVisibilityChanged(View changedView, int visibility) {
    super.onVisibilityChanged(changedView, visibility);
    updateSweep();
  }
}
