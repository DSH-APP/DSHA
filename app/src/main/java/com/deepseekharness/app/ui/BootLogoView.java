package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * 开机加载页的终端提示符标志：「›」箭头 + 下划线，参考 2026-11 交互稿的描线动画。
 *
 * <ul>
 *   <li>箭头延迟 0.1 秒、下划线延迟 0.4 秒，各 1 秒描完，曲线 cubic-bezier(.25, 1, .5, 1)；
 *   <li>描完后静止，不持有无限动画；光晕由 {@link BootScreen} 的全屏层负责。
 * </ul>
 *
 * <p>{@code play=false}（重建、系统关闭动画）时直接画完成态。坐标在 100×100 视框内定义后等比缩放。
 */
final class BootLogoView extends View {
  /** 比参考稿略紧凑：最短可见 1.2 秒内箭头描完、下划线基本描完。 */
  static final long ARROW_DELAY_MS = 100L, LINE_DELAY_MS = 400L, DRAW_MS = 1_000L;

  private static final Interpolator DRAW = new PathInterpolator(0.25f, 1f, 0.5f, 1f);

  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path arrow = new Path(), line = new Path(), segment = new Path();
  private final PathMeasure measure = new PathMeasure();
  private boolean play;
  private long startedAt = -1;

  BootLogoView(Context context) {
    super(context);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeCap(Paint.Cap.ROUND);
    stroke.setStrokeJoin(Paint.Join.ROUND);
    stroke.setColor(Color.BLACK);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  void setAccent(int color) {
    stroke.setColor(color);
    invalidate();
  }

  /** 开始描线；{@code play=false} 时直接显示完成态。 */
  void start(boolean play) {
    this.play = play;
    startedAt = play ? SystemClock.uptimeMillis() : -1;
    invalidate();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    float scale = Math.min(w, h) / 100f;
    float ox = (w - 100f * scale) / 2f, oy = (h - 100f * scale) / 2f;
    // 视框坐标与参考稿一致：M30 25 L65 50 L30 75；直线 (55,75) → (80,75)。
    arrow.reset();
    arrow.moveTo(ox + 30 * scale, oy + 25 * scale);
    arrow.lineTo(ox + 65 * scale, oy + 50 * scale);
    arrow.lineTo(ox + 30 * scale, oy + 75 * scale);
    line.reset();
    line.moveTo(ox + 55 * scale, oy + 75 * scale);
    line.lineTo(ox + 80 * scale, oy + 75 * scale);
    stroke.setStrokeWidth(4 * scale);
  }

  private static float progress(long elapsed, long delay) {
    if (elapsed <= delay) return 0f;
    return DRAW.getInterpolation(Math.min(1f, (elapsed - delay) / (float) DRAW_MS));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    long elapsed = play && startedAt >= 0 ? SystemClock.uptimeMillis() - startedAt : Long.MAX_VALUE;
    drawPartial(canvas, arrow, progress(elapsed, ARROW_DELAY_MS));
    drawPartial(canvas, line, progress(elapsed, LINE_DELAY_MS));
    if (elapsed < LINE_DELAY_MS + DRAW_MS && isShown()) postInvalidateOnAnimation();
  }

  /** 每条路径都是单一轮廓（折线 / 直线），按长度比例截取前段。 */
  private void drawPartial(Canvas canvas, Path path, float fraction) {
    if (fraction <= 0f) return;
    if (fraction >= 1f) {
      canvas.drawPath(path, stroke);
      return;
    }
    measure.setPath(path, false);
    segment.reset();
    measure.getSegment(0f, measure.getLength() * fraction, segment, true);
    canvas.drawPath(segment, stroke);
  }
}
