package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.DeployStages;
import com.deepseekharness.app.util.UiText;

/**
 * 平板 / 横屏左栏的部署进度卡（设计稿 .capcard）：标题 + 状态标签 + 分段条 + 当前阶段或结果一行 +
 * “阶段与维护记录 ›”。IDLE 时为虚线、不可点的“运行环境 · 未开始”说明卡。只显示，不启动任务。
 */
final class DeployCard extends FrameLayout {
  private final OnboardingKit kit;
  private final LinearLayout body;
  private Runnable open;
  private DeployStages.View view = DeployStages.idle();

  DeployCard(Context context) {
    super(context);
    kit = new OnboardingKit(context);
    body = kit.column();
    body.setPadding(kit.dp(14), kit.dp(14), kit.dp(14), kit.dp(14));
    addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    setOnClickListener(
        v -> {
          if (open != null && this.view.visible()) open.run();
        });
    render(DeployStages.idle());
  }

  void setOnOpen(Runnable open) {
    this.open = open;
  }

  DeployStages.View view() {
    return view;
  }

  void render(DeployStages.View next) {
    view = next == null ? DeployStages.idle() : next;
    body.removeAllViews();
    if (!view.visible()) {
      setBackgroundResource(R.drawable.bg_onb_capcard_idle);
      setClickable(false);
      setFocusable(false);
      body.addView(
          kit.cardHeader(
              UiText.choose("运行环境", "Runtime environment"),
              kit.pill(UiText.choose("未开始", "Not started"), OnboardingKit.Tone.NEUTRAL)));
      TextView note =
          kit.text(
              UiText.choose(
                  "点「开始部署」后在后台部署，期间可以继续完成引导。",
                  "After you tap Start deployment, setup runs in the background and you can keep going."),
              12.5f,
              R.color.shell_text_secondary);
      kit.add(body, note, 8);
      setContentDescription(null);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
      return;
    }
    setBackgroundResource(cardBackground(view.phase));
    setClickable(true);
    setFocusable(true);
    body.addView(
        kit.cardHeader(view.title(), kit.pill(pillText(view), OnboardingKit.Tone.NEUTRAL)));
    TextView pill = (TextView) ((LinearLayout) body.getChildAt(0)).getChildAt(1);
    OnboardingKit.Tone tone = OnboardingKit.tone(view.phase);
    pill.setBackgroundResource(OnboardingKit.pillBackground(tone));
    pill.setTextColor(getContext().getColor(OnboardingKit.toneColor(tone)));
    if (view.total() > 0) kit.add(body, kit.segmentBar(view), 10);
    kit.add(body, kit.text(line(view), 12.5f, R.color.shell_text_secondary), 8);
    LinearLayout more = kit.row();
    TextView words =
        kit.bold(
            UiText.choose("阶段与维护记录", "Stages and maintenance log"), 12.5f, R.color.shell_accent);
    more.addView(words);
    View chevron = kit.icon(R.drawable.ic_ui2_chevron, R.color.shell_accent, 14);
    LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(kit.dp(14), kit.dp(14));
    chevronParams.leftMargin = kit.dp(4);
    more.addView(chevron, chevronParams);
    kit.add(body, more, 8);
    setContentDescription(
        String.format(
            java.util.Locale.ROOT,
            UiText.choose(
                "%1$s：%2$s。%3$s。点按查看阶段与维护记录",
                "%1$s: %2$s. %3$s. Tap for stages and the maintenance log"),
            view.title(),
            view.label(),
            line(view)));
    for (int i = 0; i < body.getChildCount(); i++)
      body.getChildAt(i)
          .setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
  }

  /** 状态标签：运行中为“n / m”，其余为结果短语。 */
  static String pillText(DeployStages.View view) {
    switch (view.phase) {
      case RUNNING:
        return view.counter().isEmpty() ? view.label() : view.counter();
      case SUCCEEDED:
        return view.mode == DeployStages.Mode.UPDATE
            ? UiText.choose("已更新", "Updated")
            : UiText.choose("已就绪", "Ready");
      case UNREADY:
        return UiText.choose("未通过", "Not passed");
      case PENDING:
        return UiText.choose("待恢复", "Needs recovery");
      default:
        return UiText.choose("未完成", "Incomplete");
    }
  }

  /** 卡片 / 面板中的“当前阶段或结果”一行。 */
  static String line(DeployStages.View view) {
    switch (view.phase) {
      case RUNNING:
        return view.current >= 0
            ? String.format(
                java.util.Locale.ROOT,
                UiText.choose("第 %1$d / %2$d 步 · %3$s", "Step %1$d of %2$d · %3$s"),
                view.current + 1,
                view.total(),
                view.stages.get(view.current).title())
            : view.label();
      case SUCCEEDED:
        return view.mode == DeployStages.Mode.UPDATE
            ? UiText.choose("新版运行时已通过隔离试运行。", "The new runtime passed its isolated trial.")
            : UiText.choose("运行环境已部署并通过检查。", "The runtime is deployed and passed its checks.");
      case UNREADY:
        return UiText.choose(
            "维护步骤已结束，但环境就绪检查未通过。", "Maintenance ended, but the readiness check did not pass.");
      case PENDING:
        return UiText.choose(
            "上次维护未完成，请先恢复原环境。",
            "The last maintenance did not finish. Recover the original environment first.");
      default:
        String reason = com.deepseekharness.app.util.MaintenanceErrorText.render(view.detail);
        int newline = reason.indexOf('\n');
        String first = newline < 0 ? reason : reason.substring(0, newline);
        return first.isEmpty() ? view.label() : first;
    }
  }

  static int cardBackground(DeployStages.Phase phase) {
    switch (phase) {
      case SUCCEEDED:
        return R.drawable.bg_onb_capcard_done;
      case FAILED:
      case PENDING:
        return R.drawable.bg_onb_capcard_bad;
      case UNREADY:
        return R.drawable.bg_onb_capcard_warn;
      default:
        return R.drawable.bg_onb_capcard_run;
    }
  }
}
