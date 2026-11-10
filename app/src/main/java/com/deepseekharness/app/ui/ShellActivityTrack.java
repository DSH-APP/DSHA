package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;

/**
 * 波浪活动条（Material 3 波浪进度条形态）：只表达真实的进行 / 完成 / 需处理状态，不推算启动百分比。
 *
 * <ul>
 *   <li>进行中：平直底轨上，一段单线波浪从起始侧向末端循环行进，波纹同时流动；
 *   <li>就绪：波浪段铺满整条并压平，过渡成一条实心成功色直线；
 *   <li>需处理：静态提醒色实线；空闲：只有平直底轨。
 * </ul>
 *
 * <p>只画一条前景线，不叠引导波（避免重影）。帧回调推进，不持有无限 ValueAnimator；系统关闭动画或视图不可见时
 * 保持静态。高度不足以画出波形时振幅收为 0，退化为直线。颜色全部取自 shell_* token。
 */
public final class ShellActivityTrack extends View {

  /** 没有进行中的真实任务：只显示底轨。 */
  public static final int IDLE = 0;

  /** 正在启动 / 停止 / 核对：波浪段行进。 */
  public static final int RUNNING = 1;

  /** 已就绪：压平成实心成功色直线。 */
  public static final int SETTLED = 2;

  /** 需要处理（原实例待确认、环境待恢复）：静态提醒色实线。 */
  public static final int ERROR = 3;

  /** 波浪段从起始侧走到末端的一个周期。 */
  private static final long TRAVEL_PERIOD_MS = 2000L;

  /** 波纹自身流动一个波长的时长。 */
  private static final long RIPPLE_PERIOD_MS = 1000L;

  /** 进行中 → 就绪：铺满并压平的过渡时长。 */
  private static final long SETTLE_MS = 600L;

  private static final float WAVELENGTH_DP = 28f;
  private static final float AMPLITUDE_DP = 3f;
  private static final float STROKE_DP = 4f;

  /** 波浪段长度占轨道宽度的比例。 */
  private static final float SEGMENT_FRACTION = 0.4f;

  /** 波浪段与两侧底轨之间的间隙。 */
  private static final float GAP_DP = 4f;

  private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private final float density;
  private final int trackColor;
  private final int runningColor;
  private final int settledColor;
  private final int errorColor;

  private int state = IDLE;
  private int previous = IDLE;
  private long stateStartMs;

  /** 进行中最后一帧波浪段的起止（占轨道比例），压平过渡从这里出发。 */
  private float lastSegStart = 0.3f, lastSegEnd = 0.3f + SEGMENT_FRACTION;

  private boolean animationsEnabled = true;
  private boolean windowVisible = true;

  public ShellActivityTrack(Context context) {
    this(context, null);
  }

  public ShellActivityTrack(Context context, AttributeSet attrs) {
    super(context, attrs);
    density = context.getResources().getDisplayMetrics().density;
    trackColor = context.getColor(com.deepseekharness.app.R.color.shell_line_strong);
    runningColor = context.getColor(com.deepseekharness.app.R.color.shell_accent);
    settledColor = context.getColor(com.deepseekharness.app.R.color.shell_good);
    errorColor = context.getColor(com.deepseekharness.app.R.color.shell_warn);
    for (Paint paint : new Paint[] {trackPaint, linePaint}) {
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
    }
    trackPaint.setColor(trackColor);
    refreshAnimationSetting();
  }

  /** 设置展示状态；同一状态重复设置不会重置相位或启动新的重绘循环。 */
  public void setState(int nextState) {
    int normalized =
        nextState == RUNNING || nextState == SETTLED || nextState == ERROR ? nextState : IDLE;
    if (normalized == state) return;
    previous = state;
    state = normalized;
    stateStartMs = android.os.SystemClock.uptimeMillis();
    refreshAnimationSetting();
    invalidate();
  }

  public int state() {
    return state;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    // 重新附着时不重放「压平」过渡：直接显示当前状态的最终形态。
    previous = state;
    stateStartMs = android.os.SystemClock.uptimeMillis();
    refreshAnimationSetting();
    invalidate();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    // 退到后台时停掉帧回调；回到可见时下一帧重新按真实状态绘制。
    windowVisible = visibility == VISIBLE;
    invalidate();
  }

  /** 只在状态或附着状态变化时读取，避免每帧查询系统设置。 */
  private void refreshAnimationSetting() {
    try {
      animationsEnabled =
          Settings.Global.getFloat(
                  getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
              > 0f;
    } catch (RuntimeException ignored) {
      // 读取设置失败按开启处理；仍然不会伪造任何任务状态。
      animationsEnabled = true;
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    int width = getWidth();
    int height = getHeight();
    if (width <= 0 || height <= 0) return;
    float stroke = Math.min(STROKE_DP * density, height);
    trackPaint.setStrokeWidth(stroke);
    linePaint.setStrokeWidth(stroke);
    float mid = height / 2f;
    float left = stroke / 2f;
    float right = width - stroke / 2f;
    float span = Math.max(0f, right - left);
    float amplitude = Math.max(0f, Math.min(AMPLITUDE_DP * density, (height - stroke) / 2f));
    boolean live = animationsEnabled && windowVisible && isShown();
    long now = android.os.SystemClock.uptimeMillis();
    if (stateStartMs <= 0) stateStartMs = now;
    long elapsed = now - stateStartMs;

    canvas.save();
    // RTL 时镜像：波浪段从阅读起始侧走向末端。
    if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f, width / 2f, 0f);

    if (state == IDLE) {
      canvas.drawLine(left, mid, right, mid, trackPaint);
    } else if (state == ERROR) {
      linePaint.setColor(errorColor);
      canvas.drawLine(left, mid, right, mid, linePaint);
    } else if (state == SETTLED) {
      float t = previous == RUNNING && live ? Math.min(1f, elapsed / (float) SETTLE_MS) : 1f;
      float eased = 1f - (1f - t) * (1f - t);
      // 从进行中最后的波浪段出发：两端向外铺满，振幅收到 0，颜色过渡到成功色。
      float segStart = left + span * lastSegStart * (1f - eased);
      float segEnd = left + span * (lastSegEnd + (1f - lastSegEnd) * eased);
      linePaint.setColor(
          androidx.core.graphics.ColorUtils.blendARGB(runningColor, settledColor, eased));
      if (eased < 1f) drawTrackAround(canvas, left, right, mid, segStart, segEnd, stroke);
      drawWave(canvas, segStart, segEnd, mid, amplitude * (1f - eased), 0f);
      if (t < 1f) postInvalidateOnAnimation();
    } else {
      linePaint.setColor(runningColor);
      float segLength = span * SEGMENT_FRACTION;
      float travel = live ? elapsed % TRAVEL_PERIOD_MS / (float) TRAVEL_PERIOD_MS : 0.3f;
      // 波浪段从起始侧外滑入，再从末端滑出；超出轨道的部分裁掉。
      float segStart = left - segLength + (span + segLength) * travel;
      float segEnd = segStart + segLength;
      float drawStart = Math.max(left, segStart);
      float drawEnd = Math.min(right, segEnd);
      if (span > 0f) {
        lastSegStart = (drawStart - left) / span;
        lastSegEnd = Math.max(lastSegStart, (drawEnd - left) / span);
      }
      float ripple = live ? elapsed % RIPPLE_PERIOD_MS / (float) RIPPLE_PERIOD_MS : 0f;
      drawTrackAround(canvas, left, right, mid, drawStart, drawEnd, stroke);
      if (drawEnd > drawStart) drawWave(canvas, drawStart, drawEnd, mid, amplitude, ripple);
      if (live) postInvalidateOnAnimation();
    }
    canvas.restore();
  }

  /** 波浪段两侧留出间隙后画平直底轨。 */
  private void drawTrackAround(
      Canvas canvas,
      float left,
      float right,
      float mid,
      float segStart,
      float segEnd,
      float stroke) {
    float gap = GAP_DP * density + stroke;
    if (segEnd <= segStart) {
      canvas.drawLine(left, mid, right, mid, trackPaint);
      return;
    }
    float beforeEnd = segStart - gap;
    if (beforeEnd > left) canvas.drawLine(left, mid, beforeEnd, mid, trackPaint);
    float afterStart = segEnd + gap;
    if (afterStart < right) canvas.drawLine(afterStart, mid, right, mid, trackPaint);
  }

  /** 在 [start, end] 内画一段正弦近似波；amplitude 为 0 时就是直线。ripple 为 0..1 的波纹相位。 */
  private void drawWave(
      Canvas canvas, float start, float end, float mid, float amplitude, float ripple) {
    if (end <= start) return;
    if (amplitude < 0.5f) {
      canvas.drawLine(start, mid, end, mid, linePaint);
      return;
    }
    float wavelength = WAVELENGTH_DP * density;
    float step = Math.max(1f, density * 1.5f);
    path.rewind();
    double k = 2 * Math.PI / wavelength;
    double shift = 2 * Math.PI * ripple;
    path.moveTo(start, mid + (float) (amplitude * Math.sin(k * start - shift)));
    for (float x = start + step; x < end; x += step)
      path.lineTo(x, mid + (float) (amplitude * Math.sin(k * x - shift)));
    path.lineTo(end, mid + (float) (amplitude * Math.sin(k * end - shift)));
    canvas.drawPath(path, linePaint);
  }
}
