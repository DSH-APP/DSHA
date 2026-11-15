package com.deepseekharness.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.ProgressState;
import com.deepseekharness.app.util.UiText;
import java.util.Locale;

/**
 * 确定性进度（E1）：条 / 环两种形态 + 取消 + 完成态。
 *
 * <p>只显示真实的百分比：调用方给出的是后端上报的已完成量；总量未知时用
 * {@link #setIndeterminate}，不显示假百分比。到达终态（完成 / 失败 / 取消）后立即停止填充
 * 动画与取消按钮，不再有任何循环动画；系统关闭动画时直接落值。
 *
 * <p>条用于页内步骤（恢复向导、备份任务），环用于窄卡片里的短任务；两种形态共享同一状态机
 * {@link ProgressState} 与同一套读屏播报，不是两套实现。
 */
final class DeterminateProgress extends LinearLayout {
  enum Mode {
    BAR,
    RING
  }

  /** 填充动画时长：只影响显示，读屏与状态机始终是真实值。 */
  private static final long FILL_MS = 200L;

  private final Mode mode;
  private final ImageView terminal;
  private final TextView label;
  private final TextView value;
  private final TextView detail;
  private final ProgressBar bar;
  private final RingView ring;
  private final TextView ringValue;
  private final FrameLayout ringHost;
  private final LinearLayout cancelRow;
  private final TextView cancel;
  private final ProgressState state = new ProgressState();
  private ValueAnimator fillAnimator;
  private Runnable cancelAction;
  private boolean indeterminate;
  private int shownPercent;

  DeterminateProgress(Context context) {
    this(context, Mode.BAR);
  }

  DeterminateProgress(Context context, Mode mode) {
    super(context);
    this.mode = mode == null ? Mode.BAR : mode;
    setOrientation(VERTICAL);

    LinearLayout header = new LinearLayout(context);
    header.setOrientation(HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    terminal = new ImageView(context);
    terminal.setVisibility(GONE);
    terminal.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    header.addView(terminal, new LinearLayout.LayoutParams(dp(20), dp(20)));
    label = new TextView(context);
    label.setTextSize(14f);
    label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
    label.setIncludeFontPadding(false);
    label.setTextColor(context.getColor(R.color.shell_text));
    label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1f);
    labelParams.leftMargin = dp(6);
    header.addView(label, labelParams);
    value = new TextView(context);
    value.setTextSize(13f);
    value.setIncludeFontPadding(false);
    value.setGravity(Gravity.END);
    value.setTextColor(context.getColor(R.color.shell_text_secondary));
    value.setTypeface(android.graphics.Typeface.MONOSPACE);
    header.addView(value);
    if (this.mode == Mode.RING) value.setVisibility(GONE);
    addView(header, new LinearLayout.LayoutParams(-1, -2));

    bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
    bar.setMax(100);
    bar.setProgressTintList(ColorStateList.valueOf(context.getColor(R.color.shell_accent)));
    bar.setProgressBackgroundTintList(
        ColorStateList.valueOf(context.getColor(R.color.shell_raised)));
    LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(6));
    barParams.topMargin = dp(10);
    bar.setLayoutParams(barParams);
    if (this.mode == Mode.BAR) addView(bar);
    else bar.setVisibility(GONE);

    ringHost = new FrameLayout(context);
    ring = new RingView(context);
    ringHost.addView(ring, new FrameLayout.LayoutParams(dp(56), dp(56)));
    ringValue = new TextView(context);
    ringValue.setTextSize(12f);
    ringValue.setTypeface(android.graphics.Typeface.MONOSPACE);
    ringValue.setIncludeFontPadding(false);
    ringValue.setTextColor(context.getColor(R.color.shell_text));
    ringValue.setGravity(Gravity.CENTER);
    ringHost.addView(ringValue, new FrameLayout.LayoutParams(-1, -1));
    ringHost.setVisibility(this.mode == Mode.RING ? VISIBLE : GONE);
    if (this.mode == Mode.RING) {
      LinearLayout.LayoutParams ringParams = new LinearLayout.LayoutParams(-2, -2);
      ringParams.topMargin = dp(10);
      ringParams.gravity = Gravity.CENTER_HORIZONTAL;
      addView(ringHost, ringParams);
    }

    detail = new TextView(context);
    detail.setTextSize(12.5f);
    detail.setIncludeFontPadding(false);
    detail.setLineSpacing(dp(2), 1f);
    detail.setTextColor(context.getColor(R.color.shell_text_secondary));
    detail.setVisibility(GONE);
    LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
    detailParams.topMargin = dp(6);
    addView(detail, detailParams);

    cancelRow = new LinearLayout(context);
    cancelRow.setOrientation(HORIZONTAL);
    cancelRow.setGravity(Gravity.END);
    cancel = new TextView(context);
    cancel.setTextSize(13f);
    cancel.setTypeface(cancel.getTypeface(), android.graphics.Typeface.BOLD);
    cancel.setIncludeFontPadding(false);
    cancel.setTextColor(context.getColor(R.color.shell_accent));
    cancel.setBackgroundResource(R.drawable.bg_action_plain);
    cancel.setGravity(Gravity.CENTER);
    cancel.setMinHeight(dp(40));
    cancel.setPadding(dp(10), 0, dp(10), 0);
    cancel.setOnClickListener(view -> requestCancel());
    cancelRow.addView(cancel);
    LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1, -2);
    cancelParams.topMargin = dp(4);
    cancelRow.setVisibility(GONE);
    addView(cancelRow, cancelParams);

    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  /** 条 / 环的选择由宿主页面决定；两种形态的语义与播报一致。 */
  Mode mode() {
    return mode;
  }

  ProgressState.Phase phase() {
    return state.phase();
  }

  int percent() {
    return state.percent();
  }

  boolean running() {
    return state.running();
  }

  void setLabel(CharSequence text) {
    label.setText(text == null ? "" : text);
  }

  void setDetail(CharSequence text) {
    CharSequence next = text == null ? "" : text;
    detail.setText(next);
    detail.setVisibility(next.length() == 0 ? GONE : VISIBLE);
  }

  /** 真实百分比：只用调用方上报的已完成量，组件自己不做任何时间推算。 */
  void setPercent(int percent) {
    boolean started = state.phase() == ProgressState.Phase.IDLE && state.start();
    if (!state.running()) return;
    if (indeterminate) {
      indeterminate = false;
      bar.setIndeterminate(false);
      updateIndeterminateVisibility();
    }
    if (state.report(percent) || started) renderPercent();
  }

  /** 总量未知：只表示“正在做”，不显示百分比，也不假装快满了。 */
  void setIndeterminate() {
    if (state.phase() == ProgressState.Phase.IDLE) state.start();
    if (!state.running() || indeterminate) return;
    indeterminate = true;
    bar.setIndeterminate(true);
    updateIndeterminateVisibility();
  }

  /** 取消动作：按钮点击后立即禁用并等待后端收尾；重复点击不会再触发。 */
  void setCancelAction(CharSequence text, Runnable action) {
    cancelAction = action;
    cancel.setText(text == null ? UiText.text("取消") : text);
    cancel.setEnabled(state.running() && !state.cancelRequested());
    cancelRow.setVisibility(state.running() ? VISIBLE : GONE);
  }

  /** 直接渲染终态（结果页新建组件时使用）；未开始则先进入运行态再落终态。 */
  void complete(CharSequence completionLabel) {
    if (state.phase() == ProgressState.Phase.IDLE) state.start();
    if (!state.complete()) return;
    if (completionLabel != null) label.setText(completionLabel);
    stopAnimations();
    indeterminate = false;
    bar.setIndeterminate(false);
    animateTo(100);
    terminal.setImageResource(R.drawable.ic_onb_check);
    terminal.setImageTintList(ColorStateList.valueOf(getContext().getColor(R.color.shell_good)));
    terminal.setVisibility(VISIBLE);
    terminal.setContentDescription(UiText.text("已完成"));
    cancelRow.setVisibility(GONE);
    updateIndeterminateVisibility();
    announce(completionLabel);
  }

  void fail(CharSequence failureLabel) {
    if (state.phase() == ProgressState.Phase.IDLE) state.start();
    if (!state.fail()) return;
    if (failureLabel != null) label.setText(failureLabel);
    stopAnimations();
    indeterminate = false;
    bar.setIndeterminate(false);
    terminal.setImageResource(R.drawable.ic_onb_alert);
    terminal.setImageTintList(ColorStateList.valueOf(getContext().getColor(R.color.shell_bad)));
    terminal.setVisibility(VISIBLE);
    terminal.setContentDescription(UiText.text("失败"));
    cancelRow.setVisibility(GONE);
    updateIndeterminateVisibility();
    announce(failureLabel);
  }

  void cancelled(CharSequence cancelledLabel) {
    if (state.phase() == ProgressState.Phase.IDLE) state.start();
    if (!state.cancelled()) return;
    if (cancelledLabel != null) label.setText(cancelledLabel);
    stopAnimations();
    indeterminate = false;
    bar.setIndeterminate(false);
    terminal.setImageResource(R.drawable.ic_onb_alert);
    terminal.setImageTintList(
        ColorStateList.valueOf(getContext().getColor(R.color.shell_text_muted)));
    terminal.setVisibility(VISIBLE);
    terminal.setContentDescription(UiText.text("已取消"));
    cancelRow.setVisibility(GONE);
    updateIndeterminateVisibility();
    announce(cancelledLabel);
  }

  /** 清空为下一轮任务；只重置显示与状态机，不保留上一轮的终态。 */
  void reset() {
    stopAnimations();
    state.reset();
    indeterminate = false;
    bar.setIndeterminate(false);
    bar.setProgress(0);
    shownPercent = 0;
    value.setText("");
    label.setText("");
    setDetail("");
    terminal.setVisibility(GONE);
    cancelRow.setVisibility(GONE);
    cancel.setEnabled(true);
    cancelAction = null;
  }

  @Override
  protected void onDetachedFromWindow() {
    stopAnimations();
    super.onDetachedFromWindow();
  }

  private void requestCancel() {
    if (!state.requestCancel()) return;
    cancel.setEnabled(false);
    cancel.setText(UiText.choose("正在取消…", "Cancelling…"));
    cancel.setStateDescription(UiText.choose("等待当前作业收尾", "Waiting for the task to stop"));
    if (cancelAction != null) cancelAction.run();
  }

  boolean cancelVisible() {
    return cancelRow.getVisibility() == VISIBLE;
  }

  boolean terminalVisible() {
    return terminal.getVisibility() == VISIBLE;
  }

  CharSequence valueText() {
    return mode == Mode.BAR ? value.getText() : ringValue.getText();
  }

  CharSequence cancelText() {
    return cancel.getText();
  }

  private void renderPercent() {
    int target = state.percent();
    if (!UiMotion.animationsEnabled(getContext())) {
      shownPercent = target;
      bar.setProgress(target);
      ring.setProgress(target);
      renderValues(target);
      return;
    }
    animateTo(target);
  }

  private void animateTo(int target) {
    stopAnimations();
    if (!UiMotion.animationsEnabled(getContext()) || target == shownPercent) {
      shownPercent = target;
      bar.setProgress(target);
      ring.setProgress(target);
      renderValues(target);
      return;
    }
    fillAnimator = ValueAnimator.ofInt(shownPercent, target);
    fillAnimator.setDuration(FILL_MS);
    fillAnimator.setInterpolator(UiMotion.standard());
    fillAnimator.addUpdateListener(
        running -> {
          shownPercent = (Integer) running.getAnimatedValue();
          bar.setProgress(shownPercent);
          ring.setProgress(shownPercent);
          renderValues(shownPercent);
        });
    fillAnimator.start();
  }

  private void stopAnimations() {
    if (fillAnimator != null) {
      fillAnimator.cancel();
      fillAnimator = null;
    }
  }

  private void renderValues(int shown) {
    if (state.phase() != ProgressState.Phase.RUNNING || indeterminate) {
      value.setText("");
      ringValue.setText("");
      return;
    }
    String text = String.format(Locale.ROOT, "%d%%", Math.max(0, Math.min(100, shown)));
    value.setText(text);
    ringValue.setText(text);
  }

  private void updateIndeterminateVisibility() {
    boolean showValue = !indeterminate && state.running();
    value.setVisibility(showValue && mode == Mode.BAR ? VISIBLE : GONE);
    ringValue.setVisibility(showValue && mode == Mode.RING ? VISIBLE : GONE);
    if (indeterminate) {
      value.setText("");
      ringValue.setText("");
    }
  }

  private void announce(CharSequence text) {
    if (text == null || text.length() == 0) return;
    if (isAttachedToWindow()) announceForAccessibility(text);
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    if (state.running()) {
      info.setContentDescription(
          label.getText()
              + (indeterminate
                  ? ""
                  : " " + String.format(Locale.ROOT, "%d%%", Math.max(0, state.percent()))));
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  /** 环：真实进度的弧；无循环动画。 */
  private final class RingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private int progress;

    RingView(Context context) {
      super(context);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeWidth(dp(4));
    }

    void setProgress(int value) {
      progress = Math.max(0, Math.min(100, value));
      invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float stroke = paint.getStrokeWidth();
      arc.set(stroke, stroke, getWidth() - stroke, getHeight() - stroke);
      boolean done = state.phase() == ProgressState.Phase.COMPLETED;
      paint.setColor(getContext().getColor(done ? R.color.shell_good : R.color.shell_raised));
      canvas.drawArc(arc, -90f, 360f, false, paint);
      paint.setColor(
          getContext()
              .getColor(
                  state.phase() == ProgressState.Phase.FAILED
                      ? R.color.shell_bad
                      : done ? R.color.shell_good : R.color.shell_accent));
      canvas.drawArc(arc, -90f, 360f * progress / 100f, false, paint);
    }
  }
}
