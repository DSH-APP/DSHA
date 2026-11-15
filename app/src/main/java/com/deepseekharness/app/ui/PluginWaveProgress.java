package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.util.WaveMotion;

/**
 * 插件页头部波浪进度（方案 W3「弹性追赶」），原型见 docs/design/plugin-header-20261101/preview.html。
 *
 * <p>几何沿用启动页 {@link ShellActivityTrack} 的单线波浪，在其上增加确定态：
 *
 * <ul>
 *   <li>不确定态（排队 / 校验 / 安装等无总量阶段）：波浪段头尾各走一条缓动，段长伸缩，波纹速度随头部速度变化；
 *   <li>确定态：只跟随真实 percent，换挡时回弹追赶；最近一次跳变越大波纹越急，长时间无新数据时放缓；
 *   <li>成功：从当前段铺满并压平成好色直线，停 700ms 后淡出；失败：停在真实 percent 压平成坏色，停 900ms。
 * </ul>
 *
 * <p>对外接口与 {@link PluginTaskProgress} 一致。空闲时为 INVISIBLE 而不是 GONE：状态条为波浪预留固定车道， 任务开始 /
 * 结束不顶动下方列表。相位来自时间，帧回调推进；系统关闭动画或视图不可见时不逐帧重绘。 回弹只影响显示，读屏与 {@link #percent()} 始终是真实值。颜色全部取 shell_*
 * token。
 */
public final class PluginWaveProgress extends View {

  private static final float STROKE_DP = 4f;
  private static final float AMPLITUDE_DP = 3.4f;
  private static final float WAVELENGTH_DP = 26f;
  private static final float GAP_DP = 4f;

  /** 波纹基准周期：与 ShellActivityTrack 的 1.0s 一致。 */
  private static final float RIPPLE_PERIOD_MS = 1000f;

  /** 关闭动画时不确定态停在的相位：段落在轨道中部。 */
  private static final float STATIC_PHASE = 0.45f;

  private static final int[] MILESTONES = {25, 50, 75, 100};

  private static final int IDLE = 0;
  private static final int INDETERMINATE = 1;
  private static final int DETERMINATE = 2;
  private static final int SUCCESS = 3;
  private static final int FAILURE = 4;

  private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private final float density;
  private final int trackColor;
  private final int accentColor;
  private final int goodColor;
  private final int badColor;

  private int mode = IDLE;
  private int percent = -1;
  private float fromPercent;
  private float lastJump;
  private long fillStartedMs;
  private long modeStartedMs;
  private long terminalStartedMs;
  private long lastFrameMs;

  /** 波纹相位（以波长为单位，只取小数部分）。 */
  private float ripple;

  /** 最近一帧波浪段的起止（轨道比例）；收束从这里出发，不跳帧。 */
  private float segmentStart = 0.3f, segmentEnd = 0.5f;

  private float terminalFromStart, terminalFromEnd;
  private CharSequence label = "";
  private boolean animationsEnabled = true;
  private boolean windowVisible = true;
  private final Runnable hideTask = this::finishTerminal;

  public PluginWaveProgress(Context context) {
    this(context, null);
  }

  public PluginWaveProgress(Context context, AttributeSet attrs) {
    super(context, attrs);
    density = context.getResources().getDisplayMetrics().density;
    trackColor = context.getColor(com.deepseekharness.app.R.color.shell_line_strong);
    accentColor = context.getColor(com.deepseekharness.app.R.color.shell_accent);
    goodColor = context.getColor(com.deepseekharness.app.R.color.shell_good);
    badColor = context.getColor(com.deepseekharness.app.R.color.shell_bad);
    for (Paint paint : new Paint[] {trackPaint, linePaint}) {
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
    }
    refreshAnimationSetting();
  }

  /**
   * 设置任务进度；percent 小于 0 表示没有总量，切换为不确定态。同一模式重复设置相同 percent 不重置相位。
   *
   * @param nextLabel 无障碍语境，通常是当前状态文字
   */
  public void setTask(int nextPercent, CharSequence nextLabel) {
    if (mode == SUCCESS || mode == FAILURE) removeCallbacks(hideTask);
    label = nextLabel == null ? "" : nextLabel;
    long now = SystemClock.uptimeMillis();
    if (nextPercent < 0) {
      if (mode != INDETERMINATE) {
        mode = INDETERMINATE;
        percent = -1;
        modeStartedMs = now;
        refreshAnimationSetting();
      }
    } else {
      int clamped = Math.max(0, Math.min(100, nextPercent));
      if (mode != DETERMINATE) {
        // 从不确定态换挡从 0 开始，避免上一轮 100% 闪一帧。
        fromPercent = 0f;
        percent = clamped;
        lastJump = clamped / 100f;
        fillStartedMs = now;
        modeStartedMs = now;
        mode = DETERMINATE;
        refreshAnimationSetting();
      } else if (clamped != percent) {
        int before = percent;
        fromPercent = displayedPercent(now);
        lastJump = (clamped - before) / 100f;
        percent = clamped;
        fillStartedMs = now;
        announceMilestone(before, clamped);
      }
    }
    if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
    invalidate();
  }

  /** 任务结束：成功铺满压平成好色，失败停在本次见过的最大真实 percent；没有百分比的失败显示整条坏色。 */
  public void finish(boolean success, int lastPercent) {
    if (mode == SUCCESS || mode == FAILURE) return;
    refreshAnimationSetting();
    long now = SystemClock.uptimeMillis();
    if (mode == INDETERMINATE) {
      terminalFromStart = WaveMotion.clamp01(segmentStart);
      terminalFromEnd = Math.max(terminalFromStart, WaveMotion.clamp01(segmentEnd));
    } else if (mode == DETERMINATE) {
      terminalFromStart = 0f;
      terminalFromEnd = displayedPercent(now) / 100f;
    } else {
      terminalFromStart = 0f;
      terminalFromEnd = 0f;
    }
    int target = success || lastPercent <= 0 ? 100 : lastPercent;
    percent = Math.max(0, Math.min(100, target));
    mode = success ? SUCCESS : FAILURE;
    terminalStartedMs = now;
    label = "";
    if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
    invalidate();
    removeCallbacks(hideTask);
    postDelayed(hideTask, WaveMotion.terminalDuration(success, animationsEnabled));
  }

  /** 立即回到空闲，不给出收束；保留车道（INVISIBLE）。 */
  public void hide() {
    hideNow();
  }

  /** 成功 / 失败收束是否仍在进行。 */
  public boolean finishing() {
    return mode == SUCCESS || mode == FAILURE;
  }

  /** 当前真实百分比；不确定态返回 -1。 */
  public int percent() {
    return percent;
  }

  /** 是否处于没有总量的不确定态。 */
  public boolean indeterminate() {
    return mode == INDETERMINATE;
  }

  private void finishTerminal() {
    if (mode == SUCCESS || mode == FAILURE) hideNow();
  }

  private void hideNow() {
    removeCallbacks(hideTask);
    mode = IDLE;
    percent = -1;
    fromPercent = 0f;
    lastJump = 0f;
    label = "";
    if (getVisibility() != INVISIBLE) setVisibility(INVISIBLE);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    refreshAnimationSetting();
    lastFrameMs = 0;
    invalidate();
  }

  @Override
  protected void onDetachedFromWindow() {
    if (mode == SUCCESS || mode == FAILURE) hideNow();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    // 退到后台时停掉帧回调；回到可见时按真实状态继续，相位不补跑。
    windowVisible = visibility == VISIBLE;
    lastFrameMs = 0;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    int width = getWidth();
    int height = getHeight();
    if (mode == IDLE || width <= 0 || height <= 0) return;
    long now = SystemClock.uptimeMillis();
    long frame = lastFrameMs == 0 ? 16 : Math.max(0, Math.min(64, now - lastFrameMs));
    lastFrameMs = now;
    boolean live = animationsEnabled && windowVisible && isShown();
    float stroke = Math.min(STROKE_DP * density, height);
    trackPaint.setStrokeWidth(stroke);
    linePaint.setStrokeWidth(stroke);
    float amplitude = Math.max(0f, Math.min(AMPLITUDE_DP * density, (height - stroke) / 2f));
    float mid = height / 2f;
    float left = stroke / 2f;
    float right = width - stroke / 2f;
    float span = Math.max(0f, right - left);
    float gap = GAP_DP * density + stroke;

    canvas.save();
    // RTL 时镜像：进度从阅读起始侧推进。
    if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f, width / 2f, 0f);
    trackPaint.setColor(trackColor);
    trackPaint.setAlpha(Color.alpha(trackColor));
    boolean again = live;

    if (mode == INDETERMINATE) {
      float cycle = WaveMotion.INDETERMINATE_CYCLE_MS;
      float phase = live ? (now - modeStartedMs) % (long) cycle / cycle : STATIC_PHASE;
      float[] segment = WaveMotion.indeterminateSegment(phase);
      float drawStart = Math.max(left, left + span * segment[0]);
      float drawEnd = Math.min(right, left + span * segment[1]);
      if (span > 0f) {
        segmentStart = (drawStart - left) / span;
        segmentEnd = Math.max(segmentStart, (drawEnd - left) / span);
      }
      if (live) advanceRipple(frame, WaveMotion.indeterminateRippleSpeed(phase));
      drawTrackAround(canvas, left, right, mid, drawStart, drawEnd, gap);
      linePaint.setColor(accentColor);
      if (drawEnd > drawStart) drawWave(canvas, drawStart, drawEnd, mid, amplitude);
    } else if (mode == DETERMINATE) {
      float shown = displayedPercent(now);
      float fill = left + span * shown / 100f;
      if (live)
        advanceRipple(frame, WaveMotion.determinateRippleSpeed(lastJump, now - fillStartedMs));
      if (fill + gap < right) canvas.drawLine(fill + gap, mid, right, mid, trackPaint);
      linePaint.setColor(accentColor);
      drawWave(canvas, left, fill, mid, amplitude * WaveMotion.determinateAmplitude(shown / 100f));
    } else {
      boolean success = mode == SUCCESS;
      long elapsed = now - terminalStartedMs;
      float settle = WaveMotion.settle(elapsed, animationsEnabled);
      float alpha = WaveMotion.terminalAlpha(elapsed, success, animationsEnabled);
      float endFraction = success ? 1f : percent / 100f;
      float start = left + span * terminalFromStart * (1f - settle);
      float end = left + span * (terminalFromEnd + (endFraction - terminalFromEnd) * settle);
      if (settle < 1f || !success) {
        trackPaint.setAlpha(Math.round(Color.alpha(trackColor) * alpha));
        drawTrackAround(canvas, left, right, mid, start, end, gap);
      }
      linePaint.setColor(
          androidx.core.graphics.ColorUtils.blendARGB(
              accentColor, success ? goodColor : badColor, settle));
      linePaint.setAlpha(Math.round(255 * alpha));
      if (live) advanceRipple(frame, 1f - settle);
      drawWave(canvas, start, end, mid, amplitude * (1f - settle));
      again = live && elapsed < WaveMotion.terminalDuration(success, true) && alpha > 0f;
    }
    canvas.restore();
    if (again) postInvalidateOnAnimation();
  }

  private void advanceRipple(long frameMs, float speed) {
    ripple = (ripple + frameMs / RIPPLE_PERIOD_MS * speed) % 1f;
  }

  /** 波浪段两侧留出间隙后画平直底轨。 */
  private void drawTrackAround(
      Canvas canvas, float left, float right, float mid, float start, float end, float gap) {
    if (end <= start) {
      canvas.drawLine(left, mid, right, mid, trackPaint);
      return;
    }
    if (start - gap > left) canvas.drawLine(left, mid, start - gap, mid, trackPaint);
    if (end + gap < right) canvas.drawLine(end + gap, mid, right, mid, trackPaint);
  }

  /** [start, end] 内的正弦波，两端渐收振幅；振幅过小时退化为直线。 */
  private void drawWave(Canvas canvas, float start, float end, float mid, float amplitude) {
    if (end <= start) return;
    if (amplitude < 0.5f) {
      canvas.drawLine(start, mid, end, mid, linePaint);
      return;
    }
    float wavelength = WAVELENGTH_DP * density;
    float step = Math.max(1f, density * 1.5f);
    float taper = WaveMotion.TAPER_DP * density;
    double k = 2 * Math.PI / wavelength;
    double shift = 2 * Math.PI * ripple;
    path.rewind();
    path.moveTo(start, mid);
    for (float x = start + step; x < end; x += step)
      path.lineTo(
          x,
          mid
              + (float)
                  (amplitude * WaveMotion.taper(x, start, end, taper) * Math.sin(k * x - shift)));
    path.lineTo(end, mid);
    canvas.drawPath(path, linePaint);
  }

  private float displayedPercent(long now) {
    if (mode == IDLE || mode == INDETERMINATE || percent < 0) return 0f;
    if (!animationsEnabled) return percent;
    float value = WaveMotion.fill(fromPercent, percent, now - fillStartedMs);
    return Math.max(0f, Math.min(100f, value));
  }

  private void announceMilestone(int before, int after) {
    if (before < 0 || after <= before) return;
    for (int milestone : MILESTONES) {
      if (before < milestone && after >= milestone) {
        announceForAccessibility(description(after));
        return;
      }
    }
  }

  private CharSequence description(int value) {
    if (label.length() == 0) return value + "%";
    return label + " · " + value + "%";
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClassName(android.widget.ProgressBar.class.getName());
    if (mode == DETERMINATE || mode == SUCCESS || mode == FAILURE) {
      // 报告真实 percent，不报告回弹中的显示值。
      int value = Math.max(0, Math.min(100, percent));
      info.setRangeInfo(
          AccessibilityNodeInfo.RangeInfo.obtain(
              AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, value));
      info.setContentDescription(description(value));
    } else if (mode == INDETERMINATE) {
      info.setContentDescription(label);
    }
  }

  /** 只在状态或附着状态变化时读取，避免每帧查询系统设置。 */
  private void refreshAnimationSetting() {
    try {
      animationsEnabled =
          Settings.Global.getFloat(
                  getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
              > 0f;
    } catch (RuntimeException ignored) {
      animationsEnabled = true;
    }
  }
}
