package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

/**
 * DSH 启动画面圆环的原生复刻（dsh-web-frontend {@code _spinner}）：等宽底轨上一段 72° 弧，
 * 0.8 秒匀速转一圈。原生侧没有插件加载计数，弧长固定，不推算启动进度。
 *
 * <p>系统关闭动画或窗口不可见时只画静态圆弧，不持有无限 ValueAnimator。
 */
final class BootSpinnerView extends View {
  /** 与 DSH 相同的旋转周期与弧长。 */
  private static final long PERIOD_MS = 800L;

  private static final float ARC_DEGREES = 72f;

  private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF bounds = new RectF();
  private float stroke;
  private boolean animationsEnabled = true;
  private boolean windowVisible = true;

  BootSpinnerView(Context context) {
    super(context);
    for (Paint paint : new Paint[] {track, arc}) paint.setStyle(Paint.Style.STROKE);
    arc.setStrokeCap(Paint.Cap.BUTT);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    setStrokeWidth(2f * context.getResources().getDisplayMetrics().density);
  }

  void setColors(int trackColor, int arcColor) {
    track.setColor(trackColor);
    arc.setColor(arcColor);
    invalidate();
  }

  void setStrokeWidth(float px) {
    stroke = px;
    track.setStrokeWidth(px);
    arc.setStrokeWidth(px);
    invalidate();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    try {
      animationsEnabled =
          Settings.Global.getFloat(
                  getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
              > 0f;
    } catch (RuntimeException unreadable) {
      animationsEnabled = true;
    }
    invalidate();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    windowVisible = visibility == VISIBLE;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float half = stroke / 2f;
    float size =
        Math.min(
            getWidth() - getPaddingLeft() - getPaddingRight(),
            getHeight() - getPaddingTop() - getPaddingBottom());
    if (size <= stroke) return;
    float left = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - size) / 2f;
    float top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - size) / 2f;
    bounds.set(left + half, top + half, left + size - half, top + size - half);
    canvas.drawOval(bounds, track);
    boolean moving = animationsEnabled && windowVisible && isShown();
    float start = moving ? (SystemClock.uptimeMillis() % PERIOD_MS) * 360f / PERIOD_MS - 90f : -90f;
    canvas.drawArc(bounds, start, ARC_DEGREES, false, arc);
    if (moving) postInvalidateOnAnimation();
  }
}
