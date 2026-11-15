package com.deepseekharness.app.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.DeployStages;
import com.deepseekharness.app.util.UiText;
import java.util.Locale;

/**
 * 顶栏部署胶囊（设计稿 .cap）：圆点 + 主标签 + “· 阶段” + “n/m” + 箭头。只显示，不启动或重试任务；
 * 点按由调用方打开详情面板。窄屏（屏宽 &lt; 370dp）不显示阶段副标签；IDLE 隐藏。
 */
final class DeployCapsule extends FrameLayout {
  private final OnboardingKit kit;
  private final LinearLayout body;
  private final View dot;
  private final TextView label, sub, counter;
  private final ImageView chevron;
  private ObjectAnimator pulse;
  private Runnable open;
  private DeployStages.View view = DeployStages.idle();
  private float maxWidthFraction = 0.62f;

  DeployCapsule(Context context) {
    super(context);
    kit = new OnboardingKit(context);
    setClickable(true);
    setFocusable(true);
    setMinimumHeight(kit.dp(34));
    body = kit.row();
    body.setPadding(kit.dp(11), 0, kit.dp(10), 0);
    body.setMinimumHeight(kit.dp(34));
    dot = new View(context);
    dot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    body.addView(dot, new LinearLayout.LayoutParams(kit.dp(8), kit.dp(8)));
    label = kit.bold("", 12.5f, R.color.shell_text);
    label.setSingleLine(true);
    body.addView(label, gap(7));
    sub = kit.text("", 12.5f, R.color.shell_text_secondary);
    sub.setSingleLine(true);
    sub.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams subParams = gap(5);
    subParams.width = 0;
    subParams.weight = 1;
    body.addView(sub, subParams);
    counter = kit.bold("", 11, R.color.shell_text_muted);
    counter.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
    counter.setSingleLine(true);
    body.addView(counter, gap(6));
    chevron = kit.icon(R.drawable.ic_ui2_chevron, R.color.shell_text_muted, 14);
    LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(kit.dp(14), kit.dp(14));
    chevronParams.leftMargin = kit.dp(5);
    body.addView(chevron, chevronParams);
    for (int i = 0; i < body.getChildCount(); i++)
      body.getChildAt(i).setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(
        body,
        new LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
    setOnClickListener(
        v -> {
          if (open != null && this.view.visible()) open.run();
        });
    setVisibility(GONE);
  }

  private LinearLayout.LayoutParams gap(int dp) {
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    params.leftMargin = kit.dp(dp);
    return params;
  }

  void setOnOpen(Runnable open) {
    this.open = open;
  }

  /** 最大宽度占屏宽比例（手机约 62%，平板顶栏约 46%）。 */
  void setMaxWidthFraction(float fraction) {
    maxWidthFraction = Math.max(0.2f, Math.min(1f, fraction));
    requestLayout();
  }

  DeployStages.View view() {
    return view;
  }

  void render(DeployStages.View next) {
    view = next == null ? DeployStages.idle() : next;
    if (!view.visible()) {
      stopPulse();
      setVisibility(GONE);
      return;
    }
    DeployStages.Phase phase = view.phase;
    int ink = ink(phase);
    setBackgroundResource(capsuleBackground(phase));
    dot.setBackgroundResource(dotBackground(phase));
    String main = view.label();
    String detail = subText(view);
    boolean narrow = getResources().getConfiguration().screenWidthDp < 370;
    // 文本变化 150ms 淡入；胶囊从隐藏变可见时整块淡入（审计 B-09）；状态点 pulse 已合规。
    UiMotion.crossFadeText(label, main);
    label.setTextColor(getContext().getColor(ink));
    UiMotion.crossFadeText(sub, detail.isEmpty() ? "" : "· " + detail);
    sub.setVisibility(detail.isEmpty() || narrow ? GONE : VISIBLE);
    String count = counterText(view);
    UiMotion.crossFadeTimerText(counter, count);
    counter.setVisibility(count.isEmpty() ? GONE : VISIBLE);
    chevron.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            getContext()
                .getColor(phase == DeployStages.Phase.RUNNING ? R.color.shell_text_muted : ink)));
    setContentDescription(description(view));
    boolean wasHidden = getVisibility() != VISIBLE;
    setVisibility(VISIBLE);
    if (wasHidden) UiMotion.reveal(this);
    if (phase == DeployStages.Phase.RUNNING) startPulse();
    else stopPulse();
  }

  /** 运行中为当前阶段；失败 / 未通过 / 待恢复提示点按查看。 */
  static String subText(DeployStages.View view) {
    switch (view.phase) {
      case RUNNING:
        return view.sub();
      case FAILED:
      case UNREADY:
      case PENDING:
        return UiText.choose("点此查看", "View details");
      default:
        return "";
    }
  }

  static String counterText(DeployStages.View view) {
    if (view.phase != DeployStages.Phase.RUNNING || view.current < 0) return "";
    return (view.current + 1) + "/" + view.total();
  }

  static String description(DeployStages.View view) {
    String stage = view.phase == DeployStages.Phase.RUNNING ? view.sub() : "";
    if (stage.isEmpty())
      return String.format(
          Locale.ROOT,
          UiText.choose("%1$s：%2$s，点按查看详情", "%1$s: %2$s. Tap for details"),
          view.title(),
          view.label());
    return String.format(
        Locale.ROOT,
        UiText.choose("%1$s：%2$s，%3$s，点按查看详情", "%1$s: %2$s, %3$s. Tap for details"),
        view.title(),
        view.label(),
        stage);
  }

  static int ink(DeployStages.Phase phase) {
    switch (phase) {
      case SUCCEEDED:
        return R.color.shell_good;
      case FAILED:
      case PENDING:
        return R.color.shell_bad;
      case UNREADY:
        return R.color.shell_warn;
      default:
        return R.color.shell_text;
    }
  }

  static int capsuleBackground(DeployStages.Phase phase) {
    switch (phase) {
      case SUCCEEDED:
        return R.drawable.bg_onb_capsule_done;
      case FAILED:
      case PENDING:
        return R.drawable.bg_onb_capsule_bad;
      case UNREADY:
        return R.drawable.bg_onb_capsule_warn;
      default:
        return R.drawable.bg_onb_capsule_run;
    }
  }

  static int dotBackground(DeployStages.Phase phase) {
    switch (phase) {
      case SUCCEEDED:
        return R.drawable.bg_onb_dot_done;
      case FAILED:
      case PENDING:
        return R.drawable.bg_onb_dot_bad;
      case UNREADY:
        return R.drawable.bg_onb_dot_warn;
      default:
        return R.drawable.bg_onb_dot_run;
    }
  }

  private void startPulse() {
    if (pulse != null || !isAttachedToWindow() || !UiMotion.animationsEnabled(getContext())) return;
    pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.35f, 1f);
    pulse.setDuration(1600L);
    pulse.setRepeatCount(ValueAnimator.INFINITE);
    pulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
    pulse.start();
  }

  private void stopPulse() {
    if (pulse != null) pulse.cancel();
    pulse = null;
    dot.setAlpha(1f);
  }

  boolean pulsing() {
    return pulse != null;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (view.phase == DeployStages.Phase.RUNNING && getVisibility() == VISIBLE) startPulse();
  }

  @Override
  protected void onDetachedFromWindow() {
    stopPulse();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int limit = Math.round(getResources().getDisplayMetrics().widthPixels * maxWidthFraction);
    int mode = MeasureSpec.getMode(widthSpec);
    int size = MeasureSpec.getSize(widthSpec);
    if (mode == MeasureSpec.UNSPECIFIED || size > limit)
      widthSpec = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST);
    super.onMeasure(widthSpec, heightSpec);
  }

  /** 无障碍：只朗读整句描述，角色为按钮。 */
  @Override
  public CharSequence getAccessibilityClassName() {
    return android.widget.Button.class.getName();
  }

  static float spToPx(Context context, float sp) {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, sp, context.getResources().getDisplayMetrics());
  }
}
