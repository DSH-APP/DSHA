package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.DeployStages;
import com.deepseekharness.app.util.MaintenanceFooterPolicy;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.button.MaterialButton;

/**
 * 运行环境维护页底栏的视图绑定：主操作行（主按钮 / 进度面板 + 受限入口）与一行三个独立描边次要入口。
 *
 * <p>只负责外观；点击监听仍由 ExtractActivity 设置，门禁判断不经过本类。
 */
final class ExtractFooter {
  private final Activity activity;
  private final OnboardingKit kit;
  private final View footerRoot;
  private final LinearLayout actions;
  private final Button retry, enter, logs, copy, emergency;
  private final View busy;
  private final TextView busyLabel, busyCounter;
  private final boolean sideBySide;
  private boolean stackedLinks;
  private boolean allowRetry = true;
  private final java.util.Map<Button, Integer> icons = new java.util.HashMap<>();
  private String lastKey;

  ExtractFooter(Activity activity, OnboardingKit kit) {
    this(activity, kit, activity.findViewById(R.id.extract_footer));
  }

  /** 复用同一底栏布局的其他页面（首次引导完成页）传自己的根视图，视图 ID 仍由布局提供。 */
  ExtractFooter(Activity activity, OnboardingKit kit, View footerRoot) {
    this.activity = activity;
    this.kit = kit;
    this.footerRoot = footerRoot;
    actions = footerRoot.findViewById(R.id.extract_actions);
    retry = footerRoot.findViewById(R.id.extract_retry);
    enter = footerRoot.findViewById(R.id.extract_enter);
    busy = footerRoot.findViewById(R.id.extract_busy);
    busyLabel = footerRoot.findViewById(R.id.extract_busy_label);
    busyCounter = footerRoot.findViewById(R.id.extract_busy_counter);
    logs = footerRoot.findViewById(R.id.extract_logs);
    copy = footerRoot.findViewById(R.id.extract_copy_support);
    emergency = footerRoot.findViewById(R.id.extract_emergency);
    Configuration c = activity.getResources().getConfiguration();
    sideBySide = MaintenanceFooterPolicy.sideBySide(c.screenWidthDp, c.screenHeightDp);
    arrange();
    stackedLinks = MaintenanceFooterPolicy.stackedLinks(c.fontScale, c.screenWidthDp);
    bindLinks(stackedLinks);
  }

  Button retry() {
    return retry;
  }

  Button enter() {
    return enter;
  }

  Button logs() {
    return logs;
  }

  Button copy() {
    return copy;
  }

  Button emergency() {
    return emergency;
  }

  /** 竖屏：主操作在上、受限入口在下；并排：受限入口在左、主操作在右（主操作靠近右手拇指）。 */
  private void arrange() {
    if (!sideBySide) return;
    actions.setOrientation(LinearLayout.HORIZONTAL);
    actions.removeView(enter);
    actions.addView(enter, 0);
    for (View v : new View[] {enter, retry, busy}) {
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
      lp.gravity = android.view.Gravity.CENTER_VERTICAL;
      if (v != enter) lp.setMarginStart(kit.dp(8));
      v.setLayoutParams(lp);
    }
    // 并排时两个按钮同高，受限入口按主按钮高度对齐。
    enter.setMinHeight(
        activity.getResources().getDimensionPixelSize(R.dimen.extract_primary_height));
    enter.setMinimumHeight(enter.getMinHeight());
    if (kit.shortScreen())
      footerRoot.setPadding(
          footerRoot.getPaddingLeft(), kit.dp(8), footerRoot.getPaddingRight(), kit.dp(6));
  }

  /** 就绪页只需要“进入主界面”：隐藏降级的主操作，避免首次部署完成后再摆一个重建按钮。 */
  void setAllowRetry(boolean allowRetry) {
    this.allowRetry = allowRetry;
    lastKey = null;
  }

  private void bindLinks(boolean stacked) {
    link(
        logs,
        R.drawable.ic_recovery_document,
        UiText.choose("维护记录", "Record"),
        UiText.choose("查看本次维护记录", "View maintenance record"),
        stacked);
    link(
        copy,
        R.drawable.ic_ui_copy,
        UiText.choose("复制给支持", "Copy for support"),
        UiText.choose("复制本机环境摘要给支持", "Copy device summary for support"),
        stacked);
    link(
        emergency,
        R.drawable.ic_recovery_shield,
        UiText.choose("应急 DSH", "Emergency DSH"),
        UiText.choose("启动应急 DSH", "Start emergency DSH"),
        stacked);
  }

  private void link(Button b, int icon, String label, String description, boolean stacked) {
    b.setText(label);
    b.setContentDescription(description);
    icons.put(b, icon);
    int padding = kit.dp(stacked ? 2 : 5);
    if (b instanceof MaterialButton) {
      // 图标跟随文字：横排贴在文字前方、竖排贴在文字上方，整体在按钮内居中。
      // compound drawable 固定贴按钮边缘，三列等宽时图标与文字会离得很远。
      MaterialButton m = (MaterialButton) b;
      m.setIconGravity(
          stacked ? MaterialButton.ICON_GRAVITY_TEXT_TOP : MaterialButton.ICON_GRAVITY_TEXT_START);
      m.setIconPadding(padding);
      m.setIconSize(activity.getResources().getDimensionPixelSize(R.dimen.extract_link_icon));
    } else {
      b.setCompoundDrawablePadding(padding);
    }
  }

  /** 次要入口着色：图标与文字同色；强调项同时换成告警 / 提醒描边与浅底。 */
  private void tint(Button b, int colorRes, int backgroundRes) {
    b.setBackgroundResource(backgroundRes);
    int color = activity.getColor(colorRes);
    b.setTextColor(color);
    Integer icon = icons.get(b);
    Drawable d = icon == null ? null : activity.getDrawable(icon);
    if (d == null) return;
    d = d.mutate();
    if (b instanceof MaterialButton) {
      MaterialButton m = (MaterialButton) b;
      m.setIcon(d);
      m.setIconTint(ColorStateList.valueOf(color));
      return;
    }
    // 非 Material 主题下的退路：compound drawable 只能贴边。
    int size = activity.getResources().getDimensionPixelSize(R.dimen.extract_link_icon);
    d.setBounds(0, 0, size, size);
    d.setTint(color);
    if (stackedLinks) b.setCompoundDrawablesRelative(null, d, null, null);
    else b.setCompoundDrawablesRelative(d, null, null, null);
  }

  /**
   * 按维护状态渲染底栏。
   *
   * @param retryLabel RuntimeMaintenanceStarter.retryLabel 给出的主操作文案
   * @param retryEnabled 当前没有其他执行锁（插件查询、终端等）
   */
  void render(
      MaintenanceFooterPolicy.Model model,
      DeployStages.View view,
      String retryLabel,
      boolean retryEnabled) {
    String key =
        model.primary
            + ":"
            + model.entry
            + ":"
            + model.emphasis
            + ":"
            + retryLabel
            + ":"
            + retryEnabled
            + ":"
            + allowRetry
            + ":"
            + view.completed()
            + "/"
            + view.total()
            + ":"
            + view.current
            + ":"
            + UiText.language();
    if (key.equals(lastKey)) return;
    lastKey = key;
    renderPrimary(model, view, retryLabel, retryEnabled);
    renderEntry(model);
    boolean logsHot = model.emphasis == MaintenanceFooterPolicy.Emphasis.LOGS;
    boolean emergencyHot = model.emphasis == MaintenanceFooterPolicy.Emphasis.EMERGENCY;
    tint(
        logs,
        logsHot ? R.color.shell_warn : R.color.shell_accent,
        logsHot ? R.drawable.bg_extract_chip_warn : R.drawable.bg_extract_chip);
    tint(copy, R.color.shell_accent, R.drawable.bg_extract_chip);
    tint(
        emergency,
        emergencyHot ? R.color.shell_bad : R.color.shell_accent,
        emergencyHot ? R.drawable.bg_extract_chip_bad : R.drawable.bg_extract_chip);
  }

  private void renderPrimary(
      MaintenanceFooterPolicy.Model model,
      DeployStages.View view,
      String retryLabel,
      boolean retryEnabled) {
    boolean progress = model.primary == MaintenanceFooterPolicy.Primary.PROGRESS;
    busy.setVisibility(progress ? View.VISIBLE : View.GONE);
    retry.setVisibility(progress || !allowRetry ? View.GONE : View.VISIBLE);
    if (progress) {
      String label = view.visible() ? view.label() : UiText.choose("维护中", "Maintaining");
      String counter = view.counter();
      busyLabel.setText(label);
      busyCounter.setText(counter);
      busyCounter.setVisibility(counter.isEmpty() ? View.GONE : View.VISIBLE);
      busy.setContentDescription(counter.isEmpty() ? label : label + " " + counter);
      Drawable bg = busy.getBackground();
      if (bg instanceof LayerDrawable) {
        Drawable fill = ((LayerDrawable) bg).findDrawableByLayerId(R.id.extract_busy_fill);
        if (fill != null)
          fill.setLevel(
              MaintenanceFooterPolicy.progressLevel(
                  view.completed(), view.total(), view.phase == DeployStages.Phase.RUNNING));
      }
      return;
    }
    retry.setText(retryLabel);
    retry.setEnabled(retryEnabled);
    switch (model.primary) {
      case DANGER:
        retry.setBackgroundResource(R.drawable.bg_btn_danger);
        retry.setTextColor(
            activity.getColor(retryEnabled ? R.color.shell_on_accent : R.color.shell_text_muted));
        break;
      case DEMOTED:
        retry.setBackgroundResource(R.drawable.bg_btn);
        retry.setTextColor(
            activity.getColor(retryEnabled ? R.color.shell_text : R.color.shell_text_muted));
        break;
      default:
        retry.setBackgroundResource(R.drawable.bg_btn_primary);
        // 原 ShellPrimaryButton 禁用时仍是白字配浅灰底；这里改为可读的次要文字色。
        retry.setTextColor(
            activity.getColor(retryEnabled ? R.color.shell_on_accent : R.color.shell_text_muted));
    }
  }

  private void renderEntry(MaintenanceFooterPolicy.Model model) {
    boolean primary = model.entry == MaintenanceFooterPolicy.Entry.PRIMARY;
    String full = primary ? UiText.text("进入主界面") : UiText.text("进入受限主界面 · 查看日志与配置");
    enter.setText(primary || !sideBySide ? full : UiText.choose("进入受限主界面", "Limited interface"));
    enter.setContentDescription(full);
    switch (model.entry) {
      case PRIMARY:
        enter.setBackgroundResource(R.drawable.bg_btn_primary);
        enter.setTextColor(activity.getColor(R.color.shell_on_accent));
        break;
      case ACCENT_OUTLINE:
        enter.setBackgroundResource(R.drawable.bg_extract_outline_accent);
        enter.setTextColor(activity.getColor(R.color.shell_accent));
        break;
      default:
        enter.setBackgroundResource(R.drawable.bg_btn);
        enter.setTextColor(activity.getColor(R.color.shell_text));
    }
    // 竖屏就绪：进入主界面在上（主），重建在下（次）。
    if (!sideBySide) {
      int want = primary ? 0 : actions.indexOfChild(busy) + 1;
      if (actions.indexOfChild(enter) != want) {
        actions.removeView(enter);
        actions.addView(enter, Math.min(want, actions.getChildCount()));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) enter.getLayoutParams();
        LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) retry.getLayoutParams();
        lp.topMargin = primary ? 0 : kit.dp(8);
        rp.topMargin = primary ? kit.dp(8) : 0;
        enter.setLayoutParams(lp);
        retry.setLayoutParams(rp);
      }
    } else {
      // 并排就绪：主操作（进入主界面）放右侧。
      int want = primary ? actions.getChildCount() - 1 : 0;
      if (actions.indexOfChild(enter) != want) {
        actions.removeView(enter);
        actions.addView(enter, primary ? actions.getChildCount() : 0);
        for (View v : new View[] {enter, retry, busy}) {
          LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
          lp.setMarginStart(actions.indexOfChild(v) == 0 ? 0 : kit.dp(8));
          v.setLayoutParams(lp);
        }
      }
    }
  }
}
