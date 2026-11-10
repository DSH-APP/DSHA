package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * 插件任务进度条（方案 F）：不确定态呼吸、确定态流光。
 *
 * <p>确定态只跟随真实 percent，填充走 240ms 缓动并叠一道低对比流光；不确定态用 26% 宽的呼吸光带，
 * 不暗示百分比。帧推进沿用 {@link ShellActivityTrack} 的时间相位 + {@code postInvalidateOnAnimation}，
 * 系统关闭动画或视图不可见时不再逐帧重绘；颜色只取 shell_* token，不在这里另造同类色。
 */
public final class PluginTaskProgress extends View {

  /** 呼吸周期：与设计源 F 方案的 2.0s 一致。 */
  private static final long BREATHE_MS = 2000L;

  /** 确定态流光周期：2.2s linear。 */
  private static final long SHEEN_MS = 2200L;

  /** 真实 percent 变化时的填充时长：240ms。 */
  private static final long FILL_MS = 240L;

  /** 呼吸光带占整条宽度的比例。 */
  private static final float BREATHE_WIDTH_FRACTION = 0.26f;

  /** 减少动效时保留的静态光带宽度（设计源 34%）。 */
  private static final float STATIC_BAND_FRACTION = 0.34f;

  /** 流光带宽占已填充宽度的比例。 */
  private static final float SHEEN_WIDTH_FRACTION = 0.58f;

  /** 流光起点（相对填充宽度）与整段行程；行程 = 58% 带宽 × CSS 的 285%。 */
  private static final float SHEEN_START_FRACTION = -0.65f;

  private static final float SHEEN_TRAVEL_FRACTION = 0.58f * 2.85f;

  /** 只在跨过这些百分比时播报，避免每 1% 调一次无障碍播报。 */
  private static final int[] MILESTONES = {25, 50, 75, 100};

  /** 没有任务：不绘制，由页面把视图设为 GONE。 */
  private static final int IDLE = 0;

  /** 没有总量（排队 / 校验 / 安装等）：呼吸，不填充。 */
  private static final int INDETERMINATE = 1;

  /** 有真实 percent：填充 + 流光。 */
  private static final int DETERMINATE = 2;

  /** 已到 100%：好色收束，短暂停留后淡出。 */
  private static final int SUCCESS = 3;

  /** 失败：坏色停在当前 percent，短暂停留后淡出。 */
  private static final int FAILURE = 4;

  /** 完成停留时长：700ms。 */
  private static final long SUCCESS_HOLD_MS = 700L;

  /** 失败停留时长：900ms。 */
  private static final long FAILURE_HOLD_MS = 900L;

  /** 收束淡出时长：240ms。 */
  private static final long FADE_MS = 240L;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private final Path fillClip = new Path();

  /** 与设计源 cubic-bezier(.22, 1, .36, 1) 对齐。 */
  private final Interpolator fillEase = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

  private final int trackColor;
  private final int accentColor;
  private final int goodColor;
  private final int badColor;
  private final int sheenColor;

  private int mode = IDLE;
  private int percent = -1;
  private int fromPercent;
  private long fillStartedMs;
  private long modeStartedMs;
  private CharSequence label = "";
  private boolean animationsEnabled = true;
  private LinearGradient sheenShader;
  private int sheenShaderWidth = -1;
  private final Runnable fadeOutTask = this::fadeOut;
  private final Runnable finishTerminalTask = this::finishTerminal;

  public PluginTaskProgress(Context context) {
    this(context, null);
  }

  public PluginTaskProgress(Context context, AttributeSet attrs) {
    super(context, attrs);
    trackColor = context.getColor(com.deepseekharness.app.R.color.shell_raised);
    accentColor = context.getColor(com.deepseekharness.app.R.color.shell_accent);
    goodColor = context.getColor(com.deepseekharness.app.R.color.shell_good);
    badColor = context.getColor(com.deepseekharness.app.R.color.shell_bad);
    sheenColor = context.getColor(com.deepseekharness.app.R.color.shell_sheen);
    refreshAnimationSetting();
  }

  /**
   * 设置任务进度；percent 小于 0 表示没有总量，切换为不确定态。
   *
   * <p>同一模式重复设置相同的 percent 不会重置填充或呼吸相位；label 作为无障碍语境，
   * 通常是当前状态文字。
   */
  public void setTask(int nextPercent, CharSequence nextLabel) {
    if (mode == SUCCESS || mode == FAILURE) {
      animate().cancel();
      removeCallbacks(fadeOutTask);
      removeCallbacks(finishTerminalTask);
      setAlpha(1f);
    }
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
        // 从不确定态换挡时从 0 开始，避免上一轮 100% 闪一帧。
        fromPercent = 0;
        percent = clamped;
        fillStartedMs = now;
        modeStartedMs = now;
        mode = DETERMINATE;
        refreshAnimationSetting();
      } else if (clamped != percent) {
        int before = percent;
        fromPercent = Math.round(displayedPercent(now));
        percent = clamped;
        fillStartedMs = now;
        announceMilestone(before, clamped);
      }
    }
    if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
    invalidate();
  }

  /**
   * 任务结束：成功好色收束到 100%，失败坏色停在当前 percent；两者短暂停留后淡出。
   *
   * <p>lastPercent 是本次任务见过的最大真实百分比；没有百分比时失败显示整条坏色。
   */
  public void finish(boolean success, int lastPercent) {
    if (mode == SUCCESS || mode == FAILURE) return;
    refreshAnimationSetting();
    long now = SystemClock.uptimeMillis();
    fromPercent = Math.round(displayedPercent(now));
    int target = success ? 100 : lastPercent;
    if (!success && lastPercent <= 0) target = 100;
    percent = Math.max(0, Math.min(100, target));
    fillStartedMs = now;
    mode = success ? SUCCESS : FAILURE;
    label = "";
    sheenShader = null;
    sheenShaderWidth = -1;
    if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
    invalidate();
    long hold = success ? SUCCESS_HOLD_MS : FAILURE_HOLD_MS;
    if (animationsEnabled) postDelayed(fadeOutTask, FILL_MS + hold);
    else postDelayed(finishTerminalTask, hold);
  }

  /** 立即隐藏，不给出成功 / 失败收束；用于没有明确结果的状态。 */
  public void hide() {
    hideNow();
  }

  /** 成功 / 失败收束是否仍在进行。 */
  public boolean finishing() {
    return mode == SUCCESS || mode == FAILURE;
  }

  private void finishTerminal() {
    if (mode == SUCCESS || mode == FAILURE) hideNow();
  }

  private void fadeOut() {
    if (mode != SUCCESS && mode != FAILURE) return;
    animate().alpha(0f).setDuration(FADE_MS).withEndAction(this::finishTerminal).start();
  }

  private void hideNow() {
    animate().cancel();
    removeCallbacks(fadeOutTask);
    removeCallbacks(finishTerminalTask);
    setAlpha(1f);
    mode = IDLE;
    percent = -1;
    fromPercent = 0;
    label = "";
    sheenShader = null;
    sheenShaderWidth = -1;
    if (getVisibility() != GONE) setVisibility(GONE);
  }

  /** 当前目标百分比；不确定态返回 -1。测试与页面状态核对使用。 */
  public int percent() {
    return percent;
  }

  /** 是否处于没有总量的不确定态。 */
  public boolean indeterminate() {
    return mode == INDETERMINATE;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    refreshAnimationSetting();
    invalidate();
  }

  @Override
  protected void onDetachedFromWindow() {
    if (mode == SUCCESS || mode == FAILURE) hideNow();
    sheenShader = null;
    sheenShaderWidth = -1;
    super.onDetachedFromWindow();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (visibility == View.VISIBLE) invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    int width = getWidth();
    int height = getHeight();
    if (mode == IDLE || width <= 0 || height <= 0) return;
    long now = SystemClock.uptimeMillis();
    float radius = height / 2f;
    paint.setShader(null);
    paint.setAlpha(255);
    paint.setColor(trackColor);
    canvas.drawRoundRect(0f, 0f, width, height, radius, radius, paint);

    if (mode == SUCCESS || mode == FAILURE) {
      float shown = displayedPercent(now);
      float fillWidth = width * shown / 100f;
      if (fillWidth > 0.5f) {
        rect.set(0f, 0f, fillWidth, height);
        paint.setColor(mode == SUCCESS ? goodColor : badColor);
        paint.setAlpha(255);
        canvas.drawRoundRect(rect, radius, radius, paint);
      }
      if (animationsEnabled && isShown() && now < fillStartedMs + FILL_MS)
        postInvalidateOnAnimation();
      return;
    }

    if (mode == INDETERMINATE) {
      drawBreathing(canvas, width, height, radius, now);
      if (animationsEnabled && isShown()) postInvalidateOnAnimation();
      return;
    }

    float shown = displayedPercent(now);
    float fillWidth = width * shown / 100f;
    if (fillWidth > 0.5f) {
      rect.set(0f, 0f, fillWidth, height);
      paint.setColor(accentColor);
      paint.setAlpha(255);
      canvas.drawRoundRect(rect, radius, radius, paint);
      if (animationsEnabled && isShown()) drawSheen(canvas, fillWidth, height, radius, now);
    }
    if (animationsEnabled && isShown()) postInvalidateOnAnimation();
  }

  private void drawBreathing(Canvas canvas, int width, int height, float radius, long now) {
    float fraction;
    int alpha;
    if (animationsEnabled) {
      float phase = (now - modeStartedMs) % BREATHE_MS / (float) BREATHE_MS;
      float wave = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * phase);
      fraction = BREATHE_WIDTH_FRACTION * (0.82f + 0.40f * wave);
      alpha = Math.round(255 * (0.32f + 0.60f * wave));
    } else {
      fraction = STATIC_BAND_FRACTION;
      alpha = Math.round(255 * 0.55f);
    }
    float half = width * fraction / 2f;
    float center = width / 2f;
    paint.setShader(null);
    paint.setColor(accentColor);
    paint.setAlpha(alpha);
    rect.set(center - half, 0f, center + half, height);
    canvas.drawRoundRect(rect, radius, radius, paint);
    paint.setAlpha(255);
  }

  private void drawSheen(Canvas canvas, float fillWidth, int height, float radius, long now) {
    float bandWidth = Math.max(1f, fillWidth * SHEEN_WIDTH_FRACTION);
    int band = Math.max(1, Math.round(bandWidth));
    if (sheenShader == null || sheenShaderWidth != band) {
      int transparent = sheenColor & 0x00FFFFFF;
      sheenShader =
          new LinearGradient(
              0f,
              0f,
              band,
              0f,
              new int[] {transparent, sheenColor, transparent},
              new float[] {0f, 0.5f, 1f},
              Shader.TileMode.CLAMP);
      sheenShaderWidth = band;
    }
    float phase = (now - modeStartedMs) % SHEEN_MS / (float) SHEEN_MS;
    float start = (SHEEN_START_FRACTION + phase * SHEEN_TRAVEL_FRACTION) * fillWidth;
    fillClip.reset();
    fillClip.addRoundRect(0f, 0f, fillWidth, height, radius, radius, Path.Direction.CW);
    canvas.save();
    canvas.clipPath(fillClip);
    canvas.translate(start, 0f);
    paint.setShader(sheenShader);
    paint.setAlpha(255);
    canvas.drawRect(0f, 0f, bandWidth, height, paint);
    canvas.restore();
    paint.setShader(null);
  }

  private float displayedPercent(long now) {
    if (mode == IDLE || mode == INDETERMINATE || percent < 0) return 0f;
    if (!animationsEnabled) return percent;
    float fraction = (now - fillStartedMs) / (float) FILL_MS;
    if (fraction <= 0f) return fromPercent;
    if (fraction >= 1f) return percent;
    return fromPercent + (percent - fromPercent) * fillEase.getInterpolation(fraction);
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
      int shown = Math.round(displayedPercent(SystemClock.uptimeMillis()));
      shown = Math.max(0, Math.min(100, shown));
      info.setRangeInfo(
          AccessibilityNodeInfo.RangeInfo.obtain(
              AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, shown));
      info.setContentDescription(description(shown));
    } else if (mode == INDETERMINATE) {
      // RangeInfo.INDETERMINATE 需要 API 36；minSdk 30 只用状态文字表达未知总量。
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
      // 读取设置失败按开启处理；仍然不会伪造任何任务状态。
      animationsEnabled = true;
    }
  }
}
