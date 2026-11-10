package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import androidx.core.content.ContextCompat;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.DeployStages;
import com.deepseekharness.app.util.StageSegmentMotion;

/**
 * 阶段分段条：段由空变满时从左向右填充，运行中的当前段叠一条流光，结束时颜色淡变。
 *
 * <p>页面每秒重建分段条，动画进度存放在页面持有的 {@link StageSegmentMotion.Tracker} 里，以单调时钟续画；
 * 系统关闭动画时直接画终态，不显示流光。颜色取自与原分段 drawable 相同的主题色。
 *
 * <p>与 build276 引导动效合并（用户 2026-11-03 选方案 3）：当前段是否“正在执行”沿用
 * {@link OnboardingKit#running}（与阶段列表加载圈同一口径）；窗口不可见或视图隐藏时停止重绘，
 * RTL 布局下段序与流光方向镜像。原 DeployRunSegment 的这些行为由本类承接。
 */
final class StageSegmentBar extends View {
  private final StageSegmentMotion.Tracker tracker;
  private final boolean[] shimmer;
  private final boolean animate;
  private final int track;
  private final int heightPx;
  private final float gapPx;
  private final float radiusPx;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private boolean windowVisible = true;

  StageSegmentBar(
      Context context, StageSegmentMotion.Tracker tracker, DeployStages.View view, float density) {
    super(context);
    this.tracker = tracker;
    animate = UiMotion.animationsEnabled(context);
    track = ContextCompat.getColor(context, R.color.shell_raised);
    heightPx = Math.round(6f * density);
    gapPx = 3f * density;
    radiusPx = 3f * density;
    int total = view.total();
    boolean[] filled = new boolean[total];
    int[] colors = new int[total];
    shimmer = new boolean[total];
    for (int i = 0; i < total; i++) {
      DeployStages.Step step = view.steps[i];
      filled[i] = StageSegmentMotion.filled(step);
      colors[i] = ContextCompat.getColor(context, colorRes(view, step));
      shimmer[i] = OnboardingKit.running(view, i);
    }
    tracker.update(
        view.taskId + ":" + view.mode.name(), filled, colors, SystemClock.uptimeMillis());
    setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  /** 与原 segmentDrawable 一致的颜色语义。 */
  private static int colorRes(DeployStages.View view, DeployStages.Step step) {
    switch (view.phase) {
      case SUCCEEDED:
        return R.color.shell_good;
      case UNREADY:
        return R.color.shell_warn;
      default:
        break;
    }
    return step == DeployStages.Step.FAILED ? R.color.shell_bad : R.color.shell_accent;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    setMeasuredDimension(
        getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
        resolveSize(heightPx, heightMeasureSpec));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    int n = tracker.size();
    if (n == 0) return;
    // 关闭动画时把时钟推到所有动画结束之后，直接画终态。
    long now = SystemClock.uptimeMillis() + (animate ? 0L : 60_000L);
    float h = getHeight();
    float width = (getWidth() - gapPx * (n - 1)) / n;
    boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    // 窗口不可见或视图隐藏时照常画当前帧，但不叠流光、不续订重绘。
    boolean moving = animate && windowVisible && isShown();
    boolean running = false;
    for (int i = 0; i < n; i++) {
      float left = (rtl ? n - 1 - i : i) * (width + gapPx);
      rect.set(left, 0f, left + width, h);
      paint.setColor(track);
      canvas.drawRoundRect(rect, radiusPx, radiusPx, paint);
      float fill = tracker.fillFraction(i, now);
      if (fill <= 0f) continue;
      int color = tracker.displayColor(i, now);
      paint.setColor(color);
      canvas.save();
      if (rtl) canvas.clipRect(left + width * (1f - fill), 0f, left + width, h);
      else canvas.clipRect(left, 0f, left + width * fill, h);
      canvas.drawRoundRect(rect, radiusPx, radiusPx, paint);
      canvas.restore();
      if (moving && shimmer[i] && fill >= 1f) {
        running = true;
        drawShimmer(canvas, left, width, h, now, rtl);
      }
    }
    if (moving && (running || tracker.animating(now)) && isAttachedToWindow())
      postInvalidateOnAnimation();
  }

  /** 第 i 段是否叠流光（仅部署进行中的当前段）；供测试核对与阶段列表口径一致。 */
  boolean shimmering(int i) {
    return i >= 0 && i < shimmer.length && shimmer[i];
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    windowVisible = visibility == VISIBLE;
    invalidate();
  }

  /** 白色半透明光带，裁剪在当前段圆角内。 */
  private void drawShimmer(Canvas canvas, float left, float width, float h, long now, boolean rtl) {
    float offset = StageSegmentMotion.shimmerCenter(now);
    float center = left + width * (rtl ? 1f - offset : offset);
    float half = width * StageSegmentMotion.SHIMMER_WIDTH / 2f;
    glow.setShader(
        new LinearGradient(
            center - half,
            0f,
            center + half,
            0f,
            new int[] {0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF},
            null,
            Shader.TileMode.CLAMP));
    canvas.save();
    canvas.clipRect(left, 0f, left + width, h);
    canvas.drawRoundRect(rect, radiusPx, radiusPx, glow);
    canvas.restore();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (animate) postInvalidateOnAnimation();
  }
}
