package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.util.ScreenModeFeedback;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.VirtualScreenPreviewPolicy;
import com.deepseekharness.app.vscreen.VirtualScreenPreviewSettings;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 屏幕操作（独立页面）：无障碍设置、识屏模式、暂停、确认方式与敏感界面交接、虚拟识屏。
 *
 * <p>从设备能力授权页拆出：入口仍在该页，开关与二次确认都在这里完成。
 */
public final class ScreenOperationFragment extends Fragment {
  private TextView screenModeStatus;
  private TextView screenPolicyStatus, screenGlobalBanner, screenRulesRow, sensitivePolicyRow;
  private MaterialSwitch screenPause;
  private android.widget.RadioGroup screenSegment;
  private android.widget.RadioButton[] screenModeButtons;
  private boolean updating;
  private boolean screenModeBusy;

  /** 折叠披露状态：旋转/切页/刷新都保留；用户折叠后不再被默认展开覆盖。 */
  private DisclosureCard.Memory disclosure = new DisclosureCard.Memory();

  private final DisclosureCard.Model screenPolicyModel = new DisclosureCard.Model();
  private Accordion accordion;

  /** 仅测试注入：识屏模式切换器与执行线程；生产默认走会阻塞的 ScreenMode.request。 */
  static volatile ScreenModeSwitch screenModeSwitch = ScreenOperationFragment::requestScreenMode;

  static volatile java.util.concurrent.Executor screenModeExecutor =
      command -> new Thread(command, "screen-mode").start();

  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

  private static final com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode[] SCREEN_MODES =
      com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.values();

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    View view = inflater.inflate(R.layout.fragment_screen_operation, container, false);
    PressFeedback.attachButtons(view);
    Context ctx = requireContext();
    if (saved != null) disclosure = DisclosureCard.Memory.restore(saved, "disclosure");
    accordion = new Accordion(disclosure);
    bindScreenLabelDisclosure(ctx, view);
    View.OnClickListener setup =
        button -> startActivity(new Intent(requireContext(), AccessibilitySetupActivity.class));
    android.widget.Button setupButton = view.findViewById(R.id.computer_use_accessibility);
    // 行尾短按钮只写「设置」；完整入口名「设置屏幕操作权限」交给读屏。
    setupButton.setText(UiText.text("设置"));
    setupButton.setContentDescription(getString(R.string.ui134_screen_setup));
    setupButton.setOnClickListener(setup);
    android.widget.Button resetup = view.findViewById(R.id.screen_resetup);
    resetup.setText(UiText.choose("重新设置", "Set up again"));
    resetup.setOnClickListener(setup);
    addScreenModeControls(ctx, view);
    addScreenPolicyControls(ctx, view);
    // 虚拟屏是另一项能力：单独一行，点进虚拟屏页（页内有创建与使用说明）。
    if (com.deepseekharness.app.vscreen.VirtualScreenManager.supported(ctx)) {
      view.findViewById(R.id.screen_virtual_group).setVisibility(View.VISIBLE);
      ((TextView) view.findViewById(R.id.screen_virtual_title))
          .setText(UiText.choose("虚拟识屏", "Virtual screen"));
      ((TextView) view.findViewById(R.id.screen_virtual_sub))
          .setText(UiText.choose("独立屏幕操作", "Operate apps on a separate screen"));
      view.findViewById(R.id.screen_virtual_row)
          .setOnClickListener(
              row ->
                  startActivity(
                      new Intent(
                          ctx, com.deepseekharness.app.vscreen.VirtualScreenActivity.class)));
      bindVirtualPreviewRow(ctx, view);
    }
    refreshScreenMode();
    refreshScreenPolicy();
    return view;
  }

  /**
   * 虚拟屏预览三档：提示条（默认）/ 完整预览（旧页内小窗）/ 关闭。收起意图在这里被「选择即
   * 重新显示」清除，所以收起后仍能从本页恢复；档位与收起都不按虚拟屏代次记忆。
   */
  private void bindVirtualPreviewRow(Context ctx, View page) {
    TextView title = page.findViewById(R.id.screen_virtual_preview_title);
    if (title == null) return;
    title.setText(UiText.choose("虚拟屏预览", "Virtual screen preview"));
    renderVirtualPreviewMode(ctx, page);
    page.findViewById(R.id.screen_virtual_preview_row)
        .setOnClickListener(v -> chooseVirtualPreviewMode(ctx));
  }

  private void renderVirtualPreviewMode(Context ctx, View page) {
    if (page == null) return;
    TextView sub = page.findViewById(R.id.screen_virtual_preview_sub);
    if (sub == null) return;
    VirtualScreenPreviewPolicy.Mode mode = VirtualScreenPreviewSettings.mode(ctx);
    boolean collapsed =
        mode != VirtualScreenPreviewPolicy.Mode.OFF && VirtualScreenPreviewSettings.collapsed(ctx);
    String label = previewModeLabel(mode);
    sub.setText(
        collapsed ? String.format(UiText.choose("已收起 · %s", "Collapsed · %s"), label) : label);
  }

  private static String previewModeLabel(VirtualScreenPreviewPolicy.Mode mode) {
    switch (mode) {
      case FULL:
        return UiText.choose("完整预览（页内小窗）", "Full preview (in-page window)");
      case OFF:
        return UiText.choose("关闭，不显示预览", "Off, no preview");
      default:
        return UiText.choose("提示条", "Strip");
    }
  }

  private void chooseVirtualPreviewMode(Context ctx) {
    VirtualScreenPreviewPolicy.Mode[] modes = {
      VirtualScreenPreviewPolicy.Mode.STRIP,
      VirtualScreenPreviewPolicy.Mode.FULL,
      VirtualScreenPreviewPolicy.Mode.OFF
    };
    CharSequence[] items = {
      UiText.choose("提示条（推荐）", "Strip (recommended)"),
      UiText.choose("完整预览（旧版行为）", "Full preview (old behavior)"),
      UiText.choose("关闭", "Off")
    };
    VirtualScreenPreviewPolicy.Mode current = VirtualScreenPreviewSettings.mode(ctx);
    int selected =
        current == VirtualScreenPreviewPolicy.Mode.FULL
            ? 1
            : current == VirtualScreenPreviewPolicy.Mode.OFF ? 2 : 0;
    android.widget.LinearLayout body = new android.widget.LinearLayout(ctx);
    body.setOrientation(android.widget.LinearLayout.VERTICAL);
    int pad = dp(18);
    body.setPadding(pad, dp(4), pad, dp(4));
    androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
    for (int i = 0; i < modes.length; i++) {
      SelectableCard.Model model = new SelectableCard.Model();
      model.title = items[i];
      model.selected = i == selected;
      final int which = i;
      View card =
          SelectableCard.single(
              ctx,
              model,
              () -> {
                if (holder[0] != null) holder[0].dismiss();
                VirtualScreenPreviewSettings.setMode(ctx, modes[which]);
                View page = getView();
                if (page != null) renderVirtualPreviewMode(ctx, page);
                com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx)
                    .foreground()
                    .refresh();
              });
      android.widget.LinearLayout.LayoutParams params =
          new android.widget.LinearLayout.LayoutParams(-1, -2);
      if (i > 0) params.topMargin = dp(6);
      body.addView(card, params);
    }
    android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
    scroll.addView(body);
    holder[0] =
        new DshaDialogBuilder(ctx)
            .setTitle(UiText.choose("虚拟屏预览", "Virtual screen preview"))
            .setMessage(
                UiText.choose(
                    "提示条只在顶部显示正在操作的应用；完整预览显示页内小窗；关闭后不再显示预览。收起后也在这里重新打开。",
                    "The strip only shows the app being operated at the top; full preview shows an in-page window; off hides previews. Reopen a collapsed preview here."))
            .setView(scroll)
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .show();
  }

  /**
   * 识屏模式（关闭 / 实体 / 虚拟，互斥）：卡片顶部一行显示当前模式与缺项，点按选择。确认方式、暂停与撤销仍是全局设置。
   */
  private void addScreenModeControls(Context ctx, View page) {
    android.widget.LinearLayout card = page.findViewById(R.id.screen_card);
    android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
    row.setId(R.id.screen_mode_row);
    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(44));
    row.setBackgroundResource(R.drawable.bg_action_plain);
    TextView label = new TextView(ctx, null, 0, R.style.ShellRowTitle);
    label.setText(UiText.choose("识屏模式", "Screen mode"));
    label.setTextSize(13);
    row.addView(label, new android.widget.LinearLayout.LayoutParams(-2, -2));
    android.widget.Space spacer = new android.widget.Space(ctx);
    row.addView(spacer, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    screenModeStatus = new TextView(ctx, null, 0, R.style.ShellStatusPill);
    screenModeStatus.setId(R.id.screen_mode_status);
    screenModeStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    android.widget.LinearLayout.LayoutParams statusParams =
        new android.widget.LinearLayout.LayoutParams(-2, -2);
    statusParams.setMarginStart(dp(12));
    row.addView(screenModeStatus, statusParams);
    row.setOnClickListener(v -> chooseScreenMode(ctx));
    card.addView(
        row,
        card.indexOfChild(page.findViewById(R.id.screen_status_row)) + 1,
        new android.widget.LinearLayout.LayoutParams(-1, -2));
    refreshScreenMode();
  }

  private static String missingText(java.util.List<String> missing) {
    java.util.List<String> parts = new java.util.ArrayList<>();
    for (String code : missing) {
      switch (code) {
        case com.deepseekharness.app.util.ScreenCapabilityGate.MISSING_ACCESSIBILITY:
          parts.add(UiText.choose("无障碍", "accessibility"));
          break;
        case com.deepseekharness.app.util.ScreenCapabilityGate.MISSING_DEVICE_CHANNEL:
          parts.add(UiText.choose("设备通道", "device channel"));
          break;
        case com.deepseekharness.app.util.ScreenCapabilityGate.MISSING_API:
          parts.add("Android 11+");
          break;
        case com.deepseekharness.app.util.ScreenCapabilityGate.MISSING_OVERLAY:
          parts.add(UiText.choose("悬浮窗", "overlay"));
          break;
        default:
          parts.add(code);
      }
    }
    return String.join(UiText.choose("、", ", "), parts);
  }

  /** 某模式的一行说明：可开启 / 缺什么 / 将用哪个通道。 */
  private static String verdictLine(
      Context ctx, com.deepseekharness.app.util.ScreenModePolicy.Mode mode) {
    var verdict = com.deepseekharness.app.screen.ScreenMode.verdict(ctx, mode);
    if (!verdict.allowed)
      return String.format(UiText.choose("需要：%s", "Needs: %s"), missingText(verdict.missing));
    String line =
        verdict.channel.isEmpty()
            ? UiText.choose("可开启", "Ready")
            : String.format(UiText.choose("可开启 · 通道 %s", "Ready · channel %s"), verdict.channel);
    if (mode == com.deepseekharness.app.util.ScreenModePolicy.Mode.VIRTUAL
        && verdict.degraded.contains(
            com.deepseekharness.app.util.ScreenCapabilityGate.MISSING_ACCESSIBILITY))
      line += UiText.choose(" · 无无障碍时节点与中文输入不可用", " · nodes and Unicode input need accessibility");
    return line;
  }

  private void refreshScreenMode() {
    if (screenModeStatus == null || getContext() == null) return;
    Context ctx = requireContext();
    var mode = com.deepseekharness.app.screen.ScreenMode.current(ctx);
    String text = ScreenModeFeedback.modeName(mode);
    String reason = com.deepseekharness.app.screen.ScreenMode.lastReason(ctx);
    if (mode == com.deepseekharness.app.util.ScreenModePolicy.Mode.OFF && !reason.isEmpty())
      text += UiText.choose(" · 授权丢失已自动关闭", " · turned off after a permission was lost");
    screenModeStatus.setText(text);
    View page = getView();
    if (page != null
        && page.findViewById(R.id.screen_virtual_sub) != null
        && com.deepseekharness.app.vscreen.VirtualScreenManager.supported(ctx))
      ((TextView) page.findViewById(R.id.screen_virtual_sub))
          .setText(verdictLine(ctx, com.deepseekharness.app.util.ScreenModePolicy.Mode.VIRTUAL));
  }

  private void chooseScreenMode(Context ctx) {
    if (screenModeBusy) {
      toast(UiText.choose("正在切换识屏模式，请稍候", "Switching screen mode; please wait"));
      return;
    }
    var modes = com.deepseekharness.app.util.ScreenModePolicy.Mode.values();
    var current = com.deepseekharness.app.screen.ScreenMode.current(ctx);
    CharSequence[] items = new CharSequence[modes.length];
    for (int i = 0; i < modes.length; i++)
      items[i] =
          modes[i] == com.deepseekharness.app.util.ScreenModePolicy.Mode.OFF
              ? ScreenModeFeedback.modeName(modes[i])
              : ScreenModeFeedback.modeName(modes[i]) + "\n" + verdictLine(ctx, modes[i]);
    new DshaDialogBuilder(ctx)
        .setTitle(UiText.choose("识屏模式", "Screen mode"))
        .setSingleChoiceItems(
            items,
            current.ordinal(),
            (dialog, which) -> {
              dialog.dismiss();
              var target = modes[which];
              if (ScreenModeFeedback.needsConfirmation(current, target))
                confirmScreenMode(ctx, target);
              else applyScreenMode(ctx, target);
            })
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .show();
  }

  /**
   * 切到实体 / 虚拟前的二次确认：说明读屏范围与可撤销。确认方式设为「全部允许」也不跳过这里——模式开关必须让用户
   * 清楚自己刚授权了哪块屏。
   */
  private void confirmScreenMode(
      Context ctx, com.deepseekharness.app.util.ScreenModePolicy.Mode target) {
    if (!isAdded() || getContext() == null) return;
    new DshaDialogBuilder(ctx)
        .setTitle(ScreenModeFeedback.confirmTitle(target))
        .setMessage(ScreenModeFeedback.confirmMessage(target))
        .setPositiveButton(
            ScreenModeFeedback.confirmAction(target),
            (dialog, which) -> applyScreenMode(ctx, target))
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .show();
  }

  private void applyScreenMode(
      Context ctx, com.deepseekharness.app.util.ScreenModePolicy.Mode target) {
    Context app = ctx.getApplicationContext();
    String orientation =
        getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ? "landscape"
            : "portrait";
    screenModeBusy = true;
    if (screenModeStatus != null) screenModeStatus.setText(UiText.choose("切换中…", "Switching…"));
    screenModeExecutor.execute(
        () -> {
          final SwitchOutcome done = screenModeSwitch.request(app, target, orientation);
          main.post(
              () -> {
                screenModeBusy = false;
                refreshScreenMode();
                if (getContext() == null) return;
                var current = com.deepseekharness.app.screen.ScreenMode.current(app);
                ScreenModeFeedback.Result result =
                    ScreenModeFeedback.result(
                        target,
                        current,
                        done == null ? null : done.outcome,
                        done == null ? "" : done.code);
                if (screenModeStatus != null) screenModeStatus.setText(result.status);
                if (!result.toast.isEmpty()) toast(result.toast);
              });
        });
  }

  /** 切换结果（不携带 ScreenMode.Result，便于界面层单测注入）。 */
  static final class SwitchOutcome {
    final com.deepseekharness.app.util.ScreenModePolicy.Switch outcome;
    final String code;

    SwitchOutcome(com.deepseekharness.app.util.ScreenModePolicy.Switch outcome, String code) {
      this.outcome = outcome;
      this.code = code == null ? "" : code;
    }
  }

  @FunctionalInterface
  interface ScreenModeSwitch {
    SwitchOutcome request(
        Context app, com.deepseekharness.app.util.ScreenModePolicy.Mode target, String orientation);
  }

  private static SwitchOutcome requestScreenMode(
      Context app, com.deepseekharness.app.util.ScreenModePolicy.Mode target, String orientation) {
    try {
      var result = com.deepseekharness.app.screen.ScreenMode.request(app, target, orientation);
      return new SwitchOutcome(result.outcome, result.code);
    } catch (RuntimeException failed) {
      return null;
    }
  }

  private void addScreenPolicyControls(Context ctx, View page) {
    screenPause = page.findViewById(R.id.screen_pause);
    screenPause.setText(UiText.choose("暂停助手屏幕操作", "Pause assistant screen actions"));
    screenPause.setOnCheckedChangeListener(
        (button, paused) -> {
          if (updating) return;
          com.deepseekharness.app.HttpShellService.setScreenPaused(ctx, paused);
          refreshScreenPolicy();
        });
    ((TextView) page.findViewById(R.id.screen_policy_label))
        .setText(UiText.choose("确认方式", "Confirmation"));
    screenPolicyStatus = page.findViewById(R.id.screen_policy_status);
    android.widget.LinearLayout host = page.findViewById(R.id.screen_policy_cards);
    screenGlobalBanner = page.findViewById(R.id.screen_policy_global);
    String[] names = {
      UiText.choose("全部手动", "Ask always"),
      UiText.choose("按应用", "By app"),
      UiText.choose("全部允许", "Allow all")
    };
    String[] spoken = new String[SCREEN_MODES.length];
    for (int i = 0; i < SCREEN_MODES.length; i++)
      spoken[i] = UiText.format("%s, %s", names[i], screenModeDescription(i));
    screenSegment = new android.widget.RadioGroup(ctx);
    // 「确认方式」不再占一行标签：作为分段的读屏标题。
    screenSegment.setContentDescription(UiText.choose("确认方式", "Confirmation"));
    screenSegment.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    screenSegment.setBackgroundResource(R.drawable.bg_plugin_segment_track);
    screenSegment.setPadding(dp(3), dp(3), dp(3), dp(3));
    screenModeButtons =
        GrantsUi.buildSegment(
            ctx,
            screenSegment,
            names,
            spoken,
            new int[] {screenTone(0), screenTone(1), screenTone(2)},
            index -> pickScreenMode(SCREEN_MODES[index]));
    host.addView(screenSegment, new android.widget.LinearLayout.LayoutParams(-1, -2));
    screenRulesRow = new TextView(ctx);
    screenRulesRow.setTextSize(13);
    screenRulesRow.setTextColor(ctx.getColor(R.color.shell_accent));
    screenRulesRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
    screenRulesRow.setMinHeight(dp(40));
    screenRulesRow.setPadding(dp(4), 0, dp(8), 0);
    screenRulesRow.setBackgroundResource(R.drawable.bg_action_plain);
    screenRulesRow.setOnClickListener(v -> showScreenRules());
    host.addView(screenRulesRow, new android.widget.LinearLayout.LayoutParams(-1, -2));
    // 敏感界面交接（T119）：独立一行，不依赖「按应用」确认方式是否启用。
    sensitivePolicyRow = new TextView(ctx);
    sensitivePolicyRow.setTextSize(13);
    sensitivePolicyRow.setTextColor(ctx.getColor(R.color.shell_accent));
    sensitivePolicyRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
    sensitivePolicyRow.setMinHeight(dp(40));
    sensitivePolicyRow.setPadding(dp(4), 0, dp(8), 0);
    sensitivePolicyRow.setBackgroundResource(R.drawable.bg_action_plain);
    sensitivePolicyRow.setContentDescription(
        UiText.choose("敏感界面处理方式", "Sensitive screen handling"));
    sensitivePolicyRow.setOnClickListener(v -> showSensitiveScreenPolicy());
    host.addView(sensitivePolicyRow, new android.widget.LinearLayout.LayoutParams(-1, -2));
    android.widget.Button revoke = page.findViewById(R.id.screen_revoke);
    revoke.setText(UiText.choose("撤销预授权", "Revoke"));
    revoke.setContentDescription(
        UiText.choose("暂停并撤销全部预授权", "Pause and revoke all preauthorizations"));
    revoke.setOnClickListener(button -> confirmScreenRevoke());
    refreshScreenPolicy();
  }

  private static String screenModeDescription(int index) {
    return switch (index) {
      case 0 -> UiText.choose("每次读屏和操作前都询问你", "Ask before every observation and action");
      case 1 -> UiText.choose("指定应用免确认，其他应用仍询问", "Chosen apps skip confirmation; others still ask");
      default ->
          UiText.choose(
              "所有应用免确认，但敏感界面仍按「敏感界面交接」处理",
              "No confirmation for any app, but sensitive screens still follow sensitive screen handling");
    };
  }

  private static String screenModeBadge(int index) {
    return switch (index) {
      case 0 -> UiText.choose("推荐", "Recommended");
      case 2 -> UiText.choose("高风险", "High risk");
      default -> "";
    };
  }

  /** 屏幕确认方式：未变化不处理；全部允许必须先过红色确认。分段始终按已保存值重画。 */
  private void pickScreenMode(com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode mode) {
    if (!isAdded()) return;
    var current = new DeviceGrants(requireContext()).screenPolicy().mode;
    refreshScreenPolicy();
    if (current == mode) return;
    if (mode == com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.ALLOW_ALL)
      confirmScreenAllowAll();
    else saveScreenMode(mode);
  }

  private static int screenTone(int index) {
    return index == 0 ? R.color.shell_good : index == 2 ? R.color.shell_warn : R.color.shell_accent;
  }

  private void saveScreenMode(com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode mode) {
    if (!isAdded()) return;
    var grants = new DeviceGrants(requireContext());
    if (!grants.setScreenPolicy(mode, grants.screenPolicy().rules))
      toast(UiText.text("授权保存失败，请重试"));
    // 放宽到「全部允许」后直接展开规则说明，让安全后果与作用范围可见（B4）。
    if (mode == com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.ALLOW_ALL) {
      if (accordion == null) disclosure.setExpanded("screen-policy", true);
      else accordion.open("screen-policy");
    }
    refreshScreenPolicy();
  }

  /** 红色警告确认（无倒计时）：点空白不关闭，「取消」在最右，确认按钮为红色。 */
  private void showRedConfirm(
      String titleText,
      String warningText,
      String noteText,
      String confirmText,
      Runnable onConfirm) {
    final Context ctx = requireContext();
    int bad = ctx.getColor(R.color.shell_bad);
    android.text.SpannableString title = new android.text.SpannableString(titleText);
    title.setSpan(
        new android.text.style.ForegroundColorSpan(bad),
        0,
        title.length(),
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    TextView warning = new TextView(ctx);
    warning.setBackgroundResource(R.drawable.bg_root_policy_warning);
    warning.setTextColor(bad);
    warning.setTextSize(14);
    warning.setTypeface(warning.getTypeface(), android.graphics.Typeface.BOLD);
    warning.setLineSpacing(dp(3), 1f);
    warning.setPadding(dp(14), dp(12), dp(14), dp(12));
    warning.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_section_warning, 0, 0, 0);
    warning.setCompoundDrawablePadding(dp(10));
    warning.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(bad));
    warning.setText(warningText);
    android.widget.LinearLayout body = new android.widget.LinearLayout(ctx);
    body.setOrientation(android.widget.LinearLayout.VERTICAL);
    body.setPadding(dp(24), dp(8), dp(24), 0);
    body.addView(warning, new android.widget.LinearLayout.LayoutParams(-1, -2));
    if (noteText != null) {
      TextView note = new TextView(ctx);
      note.setTextSize(12);
      note.setTextColor(ctx.getColor(R.color.shell_text_secondary));
      note.setText(noteText);
      android.widget.LinearLayout.LayoutParams noteParams =
          new android.widget.LinearLayout.LayoutParams(-1, -2);
      noteParams.topMargin = dp(10);
      body.addView(note, noteParams);
    }
    android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
    scroll.addView(body);
    androidx.appcompat.app.AlertDialog dialog =
        new DshaDialogBuilder(ctx)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(UiText.text("取消"), null)
            .setNegativeButton(
                confirmText,
                (d, which) -> {
                  if (isAdded()) onConfirm.run();
                })
            .create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.show();
    android.widget.Button confirm =
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
    if (confirm != null) confirm.setTextColor(bad);
  }

  private void confirmScreenAllowAll() {
    showRedConfirm(
        UiText.choose("全部允许屏幕操作？", "Allow all screen actions?"),
        UiText.choose(
            "助手将可在绝大多数应用里读屏、点击和输入且不再询问；敏感界面（密码、验证码、支付、系统安全）仍按「敏感界面交接」处理，密码文本读写始终阻断。",
            "The assistant can observe, tap and type in most apps without asking; sensitive screens (passwords, one-time codes, payments, system security) still follow sensitive screen handling, and password text read/write stays blocked."),
        UiText.choose(
            "可随时改回全部手动，或用暂停开关立即停止。",
            "You can switch back to confirming every action, or pause at any time."),
        UiText.choose("仍要全部允许", "Allow all anyway"),
        () -> saveScreenMode(com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.ALLOW_ALL));
  }

  private void confirmScreenRevoke() {
    final Context ctx = requireContext();
    showRedConfirm(
        UiText.choose("暂停并撤销全部预授权？", "Pause and revoke all preauthorizations?"),
        UiText.choose(
            "立即暂停助手屏幕操作，确认方式改回全部手动，清空已保存的应用规则，敏感界面恢复默认询问。",
            "Screen actions pause now, confirmation returns to every action, saved app rules are cleared, and sensitive screens return to asking."),
        UiText.choose(
            "无障碍等系统权限不受影响；之后可关闭暂停开关继续。",
            "Accessibility and other Android permissions are unaffected. Turn off the pause switch to continue later."),
        UiText.choose("撤销", "Revoke"),
        () -> {
          boolean persisted =
              new DeviceGrants(ctx)
                      .setScreenPolicy(
                          com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.MANUAL,
                          java.util.Map.of())
                  && new DeviceGrants(ctx).resetSensitiveScreenPolicy();
          com.deepseekharness.app.HttpShellService.setScreenPaused(ctx, true);
          com.deepseekharness.app.HttpShellService.revokeScreenGrant(ctx);
          refreshScreenPolicy();
          toast(
              persisted
                  ? UiText.choose(
                      "已暂停屏幕操作并撤销预授权；可点恢复后继续",
                      "Screen actions are paused and preauthorizations revoked. Use Resume to continue")
                  : UiText.text("授权保存失败，请重试"));
        });
  }

  private void refreshScreenPolicy() {
    if (screenModeButtons == null || !isAdded()) return;
    Context ctx = requireContext();
    var policy = new DeviceGrants(ctx).screenPolicy();
    boolean global = new DeviceGrants(ctx).consentPolicy().globalAllow();
    int selected = 0;
    updating = true;
    for (int i = 0; i < screenModeButtons.length; i++) {
      boolean on = SCREEN_MODES[i] == policy.mode;
      if (on) selected = i;
      screenModeButtons[i].setChecked(on);
    }
    updating = false;
    // 全局权限开启时规则暂不生效：分段变淡但仍可编辑。
    screenSegment.setAlpha(global ? 0.55f : 1f);
    boolean custom =
        policy.mode == com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.CUSTOM;
    screenRulesRow.setVisibility(custom ? View.VISIBLE : View.GONE);
    screenRulesRow.setText(
        policy.rules.isEmpty()
            ? UiText.choose("添加免确认的应用 ›", "Add apps that skip confirmation ›")
            : UiText.format("已配置 %s 个应用 ›", String.valueOf(policy.rules.size())));
    String badge = screenModeBadge(selected);
    String summary =
        badge.isEmpty()
            ? screenModeDescription(selected)
            : UiText.format("%s · %s", screenModeDescription(selected), badge);
    // 全局权限开启时规则本就不生效：说明行只留提示，不再叠一层模式描述。
    if (global)
      summary =
          UiText.choose("全局权限开启中，以下规则暂不生效", "Global permission is on; these rules are paused");
    // 收起态保留安全后果；「全部允许」属于放宽档位，默认展开完整规则（B4）。
    boolean allowAll =
        policy.mode == com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.ALLOW_ALL;
    screenPolicyModel.key = "screen-policy";
    screenPolicyModel.detail =
        UiText.choose(
            "仅设置 DSHA 的屏幕确认；不会授予系统权限。全部允许只省普通确认，敏感界面仍按「敏感界面交接」询问或交接，密码文本读写始终阻断。自定义中未配置的应用仍询问。规则保存在本机，不随备份迁移。",
            "These rules only control DSHA confirmations and do not grant system permissions. Allow all skips ordinary confirmations only; sensitive screens still ask or hand off, and password text read/write stays blocked. Unconfigured apps still ask in custom mode. Rules stay on this device and are excluded from backups.");
    // 收起态不再常显安全说明（已收进展开区 detail）；仅放宽档位保留一句后果。
    screenPolicyModel.consequence =
        allowAll
            ? UiText.choose(
                "敏感界面仍按「敏感界面交接」处理，密码文本读写保持阻断",
                "Sensitive screens still follow sensitive screen handling and password text stays blocked")
            : null;
    screenPolicyModel.consequenceTone = allowAll ? R.color.shell_warn : R.color.shell_text_muted;
    screenPolicyModel.defaultExpanded = allowAll;
    screenPolicyStatus.setText(summary);
    if (accordion == null) {
      DisclosureCard.bind(screenPolicyStatus, screenPolicyModel, disclosure, null);
    } else {
      DisclosureCard.bind(
          screenPolicyStatus,
          screenPolicyModel,
          accordion.state(),
          () -> accordion.onToggle("screen-policy"));
      accordion.register("screen-policy", this::refreshScreenPolicy);
    }
    screenPolicyStatus.setTextColor(
        ctx.getColor(global ? R.color.shell_warn : R.color.shell_text_muted));
    updating = true;
    screenPause.setChecked(com.deepseekharness.app.HttpShellService.screenPaused());
    updating = false;
    View page = getView();
    if (page != null) refreshA11yStatus(page.findViewById(R.id.config_a11y_status));
    refreshSensitivePolicy();
  }

  private String screenRuleLabel(com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule rule) {
    return rule == com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ALLOW
        ? UiText.choose("允许", "Allow")
        : UiText.choose("询问", "Ask");
  }

  /** 敏感界面行摘要：默认全询问；只有用户改过交接或名单时才列出非默认项。 */
  private void refreshSensitivePolicy() {
    if (sensitivePolicyRow == null || !isAdded()) return;
    Context ctx = requireContext();
    var policy = new DeviceGrants(ctx).sensitiveScreenPolicy();
    java.util.List<String> handoffCategories = new java.util.ArrayList<>();
    java.util.List<String> offCategories = new java.util.ArrayList<>();
    for (var category : com.deepseekharness.app.util.SensitiveScreenPolicy.Category.values()) {
      if (policy.mode(category) == com.deepseekharness.app.util.SensitiveScreenPolicy.Mode.HANDOFF)
        handoffCategories.add(sensitiveCategoryName(category));
      else if (policy.mode(category) == com.deepseekharness.app.util.SensitiveScreenPolicy.Mode.OFF)
        offCategories.add(sensitiveCategoryName(category));
    }
    java.util.List<String> parts = new java.util.ArrayList<>();
    if (!offCategories.isEmpty())
      parts.add(
          UiText.format(
              UiText.choose("%s 不启用", "%s off"),
              String.join(UiText.choose("、", ", "), offCategories)));
    if (!handoffCategories.isEmpty())
      parts.add(
          UiText.format(
              UiText.choose("%s 交接", "%s hand off"),
              String.join(UiText.choose("、", ", "), handoffCategories)));
    if (!policy.handoffApps().isEmpty())
      parts.add(
          UiText.format(
              UiText.choose("强制交接 %s 个应用", "%s apps always hand off"),
              String.valueOf(policy.handoffApps().size())));
    if (!policy.trustApps().isEmpty())
      parts.add(
          UiText.format(
              UiText.choose("免交接 %s 个应用", "%s apps skip hand off"),
              String.valueOf(policy.trustApps().size())));
    String text =
        parts.isEmpty()
            ? UiText.choose(
                "敏感界面默认询问 · 密码文本读写阻断 ›",
                "Sensitive screens ask by default · password text blocked ›")
            : UiText.format(
                UiText.choose("敏感界面：%s ›", "Sensitive screens: %s ›"),
                String.join(UiText.choose(" · ", " · "), parts));
    sensitivePolicyRow.setText(text);
  }

  private static String sensitiveCategoryName(
      com.deepseekharness.app.util.SensitiveScreenPolicy.Category category) {
    switch (category) {
      case PASSWORD:
        return UiText.choose("密码", "password");
      case OTP:
        return UiText.choose("验证码", "one-time code");
      case PAYMENT:
        return UiText.choose("支付", "payment");
      case SYSTEM_SECURITY:
        return UiText.choose("系统安全", "system security");
      case SECURE_UNKNOWN:
      default:
        return UiText.choose("无法识别", "unverified");
    }
  }

  private static String sensitiveModeName(
      com.deepseekharness.app.util.SensitiveScreenPolicy.Mode mode) {
    switch (mode) {
      case HANDOFF:
        return UiText.choose("交接", "Hand off");
      case OFF:
        return UiText.choose("不启用", "Off");
      case ASK:
      default:
        return UiText.choose("询问", "Ask");
    }
  }

  /** 敏感界面处理：类别逐项选择询问/交接，并可维护强制交接与信任应用名单。 */
  private void showSensitiveScreenPolicy() {
    if (!isAdded()) return;
    final Context ctx = requireContext();
    final boolean global = new DeviceGrants(ctx).consentPolicy().globalAllow();
    var policy = new DeviceGrants(ctx).sensitiveScreenPolicy();
    androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
    var content = new android.widget.LinearLayout(ctx);
    content.setOrientation(android.widget.LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(4), dp(20), 0);
    TextView note = new TextView(ctx);
    note.setTextSize(12);
    note.setTextColor(ctx.getColor(R.color.shell_text_secondary));
    note.setPadding(0, 0, 0, dp(8));
    note.setText(
        UiText.choose(
            "敏感界面默认先询问。设为「交接」会暂停助手，由你本人在手机上完成；恢复后助手必须重新观察，不会重放上一动作。设为「不启用」只关闭整屏分档，回到普通确认策略，密码文本读写仍始终阻断；全部允许也不能解除。",
            "Sensitive screens ask before each action by default. Hand off pauses the assistant so you can act on the phone yourself; after resuming, the assistant must observe again and never replays the previous action. Off only disables tiered sensitive-screen handling and returns to ordinary confirmations; password text read/write stays blocked, even with Allow all."));
    content.addView(note, new android.widget.LinearLayout.LayoutParams(-1, -2));
    for (var category : com.deepseekharness.app.util.SensitiveScreenPolicy.Category.values()) {
      TextView row =
          sensitiveDialogRow(
              ctx, sensitiveCategoryName(category), sensitiveModeName(policy.mode(category)));
      row.setOnClickListener(
          v -> {
            if (holder[0] != null) holder[0].dismiss();
            chooseSensitiveMode(category);
          });
      content.addView(row, new android.widget.LinearLayout.LayoutParams(-1, -2));
    }
    TextView handoffAppsRow =
        sensitiveDialogRow(
            ctx,
            UiText.choose("强制交接的应用", "Always hand off"),
            UiText.format(
                UiText.choose("%s 个 ›", "%s ›"), String.valueOf(policy.handoffApps().size())));
    handoffAppsRow.setOnClickListener(
        v -> {
          if (holder[0] != null) holder[0].dismiss();
          showSensitiveAppList(true);
        });
    content.addView(handoffAppsRow, new android.widget.LinearLayout.LayoutParams(-1, -2));
    TextView trustAppsRow =
        sensitiveDialogRow(
            ctx,
            UiText.choose("免交接的应用", "Skip hand off"),
            UiText.format(
                UiText.choose("%s 个 ›", "%s ›"), String.valueOf(policy.trustApps().size())));
    trustAppsRow.setOnClickListener(
        v -> {
          if (holder[0] != null) holder[0].dismiss();
          showSensitiveAppList(false);
        });
    content.addView(trustAppsRow, new android.widget.LinearLayout.LayoutParams(-1, -2));
    if (global) {
      TextView warning = new TextView(ctx);
      warning.setTextSize(12);
      warning.setTextColor(ctx.getColor(R.color.shell_warn));
      warning.setPadding(0, dp(8), 0, 0);
      warning.setText(
          UiText.choose(
              "全局权限开启中：「全部允许」不会关闭这里的敏感界面确认，也不会解除密码文本阻断。",
              "Global permission is on: Allow all does not disable these sensitive-screen confirmations or password text blocking."));
      content.addView(warning, new android.widget.LinearLayout.LayoutParams(-1, -2));
    }
    var scroll = new android.widget.ScrollView(ctx);
    scroll.setFillViewport(true);
    scroll.addView(content);
    holder[0] =
        new DshaDialogBuilder(ctx)
            .setTitle(UiText.choose("敏感界面交接", "Sensitive screen handling"))
            .setView(scroll)
            .setNegativeButton(UiText.choose("关闭", "Close"), null)
            .show();
  }

  private TextView sensitiveDialogRow(Context ctx, String title, String value) {
    TextView row = new TextView(ctx);
    row.setText(UiText.format(UiText.choose("%s：%s ›", "%s: %s ›"), title, value));
    row.setTextSize(15);
    row.setTextColor(ctx.getColor(R.color.shell_text));
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    row.setMinHeight(dp(48));
    row.setPadding(dp(4), dp(6), dp(4), dp(6));
    row.setBackgroundResource(R.drawable.bg_action_plain);
    row.setClickable(true);
    row.setFocusable(true);
    return row;
  }

  private void chooseSensitiveMode(
      com.deepseekharness.app.util.SensitiveScreenPolicy.Category category) {
    if (!isAdded()) return;
    final Context ctx = requireContext();
    var modes =
        new com.deepseekharness.app.util.SensitiveScreenPolicy.Mode[] {
          com.deepseekharness.app.util.SensitiveScreenPolicy.Mode.ASK,
          com.deepseekharness.app.util.SensitiveScreenPolicy.Mode.HANDOFF,
          com.deepseekharness.app.util.SensitiveScreenPolicy.Mode.OFF
        };
    var current = new DeviceGrants(ctx).sensitiveScreenPolicy().mode(category);
    int selected = 0;
    for (int i = 0; i < modes.length; i++) if (modes[i] == current) selected = i;
    CharSequence[] items = {
      UiText.choose("询问\n每次动作前确认", "Ask\nConfirm before each action"),
      UiText.choose("交接\n暂停助手，由你本人操作", "Hand off\nPause the assistant and act yourself"),
      UiText.choose(
          "不启用\n只关闭整屏分档，密码文本仍阻断", "Off\nDisable tiered handling only; password text stays blocked")
    };
    new DshaDialogBuilder(ctx)
        .setTitle(
            UiText.format(UiText.choose("%s处理方式", "%s handling"), sensitiveCategoryName(category)))
        .setSingleChoiceItems(
            items,
            selected,
            (dialog, which) -> {
              dialog.dismiss();
              if (!isAdded()) return;
              Context context = requireContext();
              if (!new DeviceGrants(context).setSensitiveScreenMode(category, modes[which]))
                toast(UiText.choose("策略保存失败，请重试", "Could not save the policy; try again"));
              refreshSensitivePolicy();
            })
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .show();
  }

  /** 维护某个名单：点应用移出，「添加应用」走应用选择器，也可手动输入包名。 */
  private void showSensitiveAppList(boolean handoff) {
    if (!isAdded()) return;
    final Context ctx = requireContext();
    var policy = new DeviceGrants(ctx).sensitiveScreenPolicy();
    var apps = handoff ? policy.handoffApps() : policy.trustApps();
    androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
    var list = new android.widget.LinearLayout(ctx);
    list.setOrientation(android.widget.LinearLayout.VERTICAL);
    list.setPadding(dp(20), dp(4), dp(20), dp(4));
    if (apps.isEmpty()) {
      TextView empty = new TextView(ctx);
      empty.setTextSize(13);
      empty.setTextColor(ctx.getColor(R.color.shell_text_secondary));
      empty.setPadding(0, dp(8), 0, dp(8));
      empty.setText(
          UiText.choose(
              "尚未添加应用；这里只改变敏感界面的处理方式，不授予任何权限。",
              "No apps yet. This only changes how sensitive screens are handled; it grants no permission."));
      list.addView(empty);
    }
    for (String pkg : apps) {
      View row =
          ScreenAppPicker.row(
              ctx, ScreenAppPicker.describe(ctx, pkg), UiText.choose("移出", "Remove"), this::dp);
      row.setOnClickListener(
          v -> {
            var grants = new DeviceGrants(ctx);
            var current = grants.sensitiveScreenPolicy();
            var nextTrust = new java.util.LinkedHashSet<>(current.trustApps());
            var nextHandoff = new java.util.LinkedHashSet<>(current.handoffApps());
            if (handoff) nextHandoff.remove(pkg);
            else nextTrust.remove(pkg);
            if (!grants.setSensitiveScreenApps(nextTrust, nextHandoff))
              toast(UiText.choose("应用名单保存失败，请重试", "Could not save the app list; try again"));
            if (holder[0] != null) holder[0].dismiss();
            refreshSensitivePolicy();
          });
      list.addView(row, new android.widget.LinearLayout.LayoutParams(-1, -2));
    }
    var scroll = new android.widget.ScrollView(ctx);
    scroll.addView(list);
    int height = Math.round(getResources().getDisplayMetrics().heightPixels * 0.45f);
    var container = new android.widget.FrameLayout(ctx);
    container.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, height));
    holder[0] =
        new DshaDialogBuilder(ctx)
            .setTitle(
                handoff
                    ? UiText.choose("强制交接的应用", "Apps that always hand off")
                    : UiText.choose("免交接的应用", "Apps that skip hand off"))
            .setView(container)
            .setNegativeButton(
                UiText.choose("添加应用", "Add app"), (dialog, which) -> pickSensitiveApp(handoff))
            .setPositiveButton(UiText.choose("关闭", "Close"), null)
            .show();
  }

  private void pickSensitiveApp(boolean handoff) {
    if (!isAdded()) return;
    var policy = new DeviceGrants(requireContext()).sensitiveScreenPolicy();
    java.util.Map<String, String> badges = new java.util.HashMap<>();
    for (String pkg : handoff ? policy.handoffApps() : policy.trustApps())
      badges.put(pkg, UiText.choose("已添加", "Added"));
    ScreenAppPicker.show(
        requireContext(),
        badges,
        pkg -> addSensitiveApp(handoff, pkg),
        () -> manualSensitiveApp(handoff));
  }

  private void addSensitiveApp(boolean handoff, String pkg) {
    if (!isAdded() || pkg == null || pkg.isEmpty()) return;
    Context ctx = requireContext();
    var policy = new DeviceGrants(ctx).sensitiveScreenPolicy();
    var trust = new java.util.LinkedHashSet<>(policy.trustApps());
    var handoffApps = new java.util.LinkedHashSet<>(policy.handoffApps());
    if (handoff) {
      trust.remove(pkg);
      handoffApps.add(pkg);
    } else {
      handoffApps.remove(pkg);
      trust.add(pkg);
    }
    if (!new DeviceGrants(ctx).setSensitiveScreenApps(trust, handoffApps))
      toast(UiText.choose("应用名单保存失败，请重试", "Could not save the app list; try again"));
    refreshSensitivePolicy();
  }

  private void manualSensitiveApp(boolean handoff) {
    if (!isAdded()) return;
    Context ctx = requireContext();
    var input = new androidx.appcompat.widget.AppCompatEditText(ctx);
    input.setSingleLine(true);
    input.setHint(UiText.choose("精确应用包名", "Exact app package name"));
    new DshaDialogBuilder(ctx)
        .setTitle(UiText.choose("输入包名", "Enter package name"))
        .setView(input)
        .setPositiveButton(
            UiText.choose("保存", "Save"),
            (dialog, which) -> {
              String pkg = input.getText().toString().trim();
              if (!com.deepseekharness.app.util.ScreenConfirmationPolicy.validPackage(pkg)) {
                toast(UiText.choose("应用包名无效，未保存", "Invalid package name; not saved"));
                return;
              }
              addSensitiveApp(handoff, pkg);
            })
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .show();
  }

  /** 已配置的应用规则：图标 + 名称 + 包名 + 当前规则；点行编辑，「添加应用」打开应用选择器。 */
  private void showScreenRules() {
    final Context ctx = requireContext();
    var policy = new DeviceGrants(ctx).screenPolicy();
    android.widget.LinearLayout list = new android.widget.LinearLayout(ctx);
    list.setOrientation(android.widget.LinearLayout.VERTICAL);
    list.setPadding(dp(20), dp(4), dp(20), dp(4));
    androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
    if (policy.rules.isEmpty()) {
      TextView empty = new TextView(ctx);
      empty.setTextSize(13);
      empty.setTextColor(ctx.getColor(R.color.shell_text_secondary));
      empty.setPadding(0, dp(8), 0, dp(8));
      empty.setText(
          UiText.choose(
              "暂无应用规则；其他应用仍会询问",
              "No app rules yet. Added apps follow their rule; other apps still ask."));
      list.addView(empty);
    }
    for (var entry : policy.rules.entrySet()) {
      String pkg = entry.getKey();
      View row =
          ScreenAppPicker.row(
              ctx, ScreenAppPicker.describe(ctx, pkg), screenRuleLabel(entry.getValue()), this::dp);
      row.setOnClickListener(
          v -> {
            if (holder[0] != null) holder[0].dismiss();
            editScreenRule(pkg, false);
          });
      list.addView(row, new android.widget.LinearLayout.LayoutParams(-1, -2));
    }
    android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
    scroll.addView(list);
    holder[0] =
        new DshaDialogBuilder(ctx)
            .setTitle(UiText.choose("应用确认规则", "App confirmation rules"))
            .setView(scroll)
            .setNegativeButton(UiText.choose("添加应用", "Add app"), (dialog, which) -> pickScreenApp())
            .setPositiveButton(UiText.text("知道了"), null)
            .show();
  }

  private void pickScreenApp() {
    if (!isAdded()) return;
    var policy = new DeviceGrants(requireContext()).screenPolicy();
    java.util.Map<String, String> badges = new java.util.HashMap<>();
    for (var entry : policy.rules.entrySet())
      badges.put(entry.getKey(), screenRuleLabel(entry.getValue()));
    ScreenAppPicker.show(
        requireContext(),
        badges,
        pkg -> {
          if (isAdded()) editScreenRule(pkg, !badges.containsKey(pkg));
        },
        () -> {
          if (isAdded()) editScreenRule("", true);
        });
  }

  /**
   * 编辑单个应用规则。pkg 为空时手动输入包名；从选择器新加的应用默认「允许」（加规则的目的就是免确认）。
   */
  private void editScreenRule(String existing, boolean fresh) {
    var ctx = requireContext();
    var content = new android.widget.LinearLayout(ctx);
    content.setOrientation(android.widget.LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(4), dp(20), 0);
    var pkg = new androidx.appcompat.widget.AppCompatEditText(ctx);
    pkg.setSingleLine(true);
    pkg.setHint(UiText.choose("精确应用包名", "Exact app package name"));
    pkg.setText(existing);
    if (existing.isEmpty()) content.addView(pkg);
    else {
      View header =
          ScreenAppPicker.row(ctx, ScreenAppPicker.describe(ctx, existing), null, this::dp);
      header.setClickable(false);
      header.setFocusable(false);
      header.setBackground(null);
      content.addView(header);
    }
    var allow = new MaterialSwitch(ctx);
    allow.setText(
        UiText.choose(
            "允许该应用免确认（敏感界面仍按「敏感界面交接」处理）",
            "Allow this app without confirmation (sensitive screens still follow sensitive screen handling)"));
    allow.setChecked(
        fresh
            || new DeviceGrants(ctx).screenPolicy().rules.get(existing)
                == com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ALLOW);
    content.addView(allow);
    var scroll = new android.widget.ScrollView(ctx);
    scroll.setFillViewport(true);
    scroll.addView(content);
    var builder =
        new DshaDialogBuilder(ctx)
            .setTitle(UiText.choose("编辑应用确认规则", "Edit app confirmation rules"))
            .setView(scroll)
            .setPositiveButton(
                UiText.text("保存"),
                (dialog, which) -> {
                  String key = pkg.getText().toString().trim();
                  if (!com.deepseekharness.app.util.ScreenConfirmationPolicy.validPackage(key)) {
                    toast(
                        UiText.choose("应用包名无效，规则未保存", "Invalid package name. Rule was not saved"));
                    return;
                  }
                  var grants = new DeviceGrants(ctx);
                  var current = grants.screenPolicy();
                  var rules = new java.util.LinkedHashMap<>(current.rules);
                  rules.put(
                      key,
                      allow.isChecked()
                          ? com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ALLOW
                          : com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ASK);
                  if (!grants.setScreenPolicy(current.mode, rules))
                    toast(UiText.text("授权保存失败，请重试"));
                  refreshScreenPolicy();
                })
            .setNegativeButton(UiText.text("取消"), null);
    if (!fresh && !existing.isEmpty())
      builder.setNeutralButton(
          UiText.choose("删除规则", "Remove rule"),
          (dialog, which) -> {
            var grants = new DeviceGrants(ctx);
            var current = grants.screenPolicy();
            var rules = new java.util.LinkedHashMap<>(current.rules);
            rules.remove(existing);
            if (!grants.setScreenPolicy(current.mode, rules)) toast(UiText.text("授权保存失败，请重试"));
            refreshScreenPolicy();
          });
    builder.show();
  }

  /** 「屏幕操作」标签折叠说明：只放一条操作顺序提示，不再挂 ⓘ。 */
  private void bindScreenLabelDisclosure(Context ctx, View page) {
    GrantsUi.bindLabelInfo(
        page.findViewById(R.id.grants_screen_label),
        "screen-help",
        UiText.choose(
            "让助手读屏、点击和输入。先完成屏幕操作设置，再在对话中描述任务。",
            "Let the assistant observe, tap and type. Set up screen control, then describe the task in a conversation."),
        false,
        accordion);
  }

  @Override
  public void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    disclosure.save(outState, "disclosure");
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshScreenMode();
    refreshScreenPolicy();
    View page = getView();
    if (page != null) {
      refreshA11yStatus(page.findViewById(R.id.config_a11y_status));
      if (com.deepseekharness.app.vscreen.VirtualScreenManager.supported(requireContext()))
        renderVirtualPreviewMode(requireContext(), page);
    }
  }

  @Override
  public void onDestroyView() {
    screenModeStatus = null;
    screenPolicyStatus = null;
    screenGlobalBanner = null;
    screenRulesRow = null;
    sensitivePolicyRow = null;
    screenPause = null;
    screenSegment = null;
    screenModeButtons = null;
    super.onDestroyView();
  }

  private int dp(int value) {
    return GrantsUi.dp(requireContext(), value);
  }

  private void toast(String value) {
    if (getContext() != null) Toast.makeText(getContext(), value, Toast.LENGTH_LONG).show();
  }

  /**
   * 状态行：分别显示系统开关、实际连接与暂停。已连接时设置入口降为「重新设置」文字按钮，
   * 未连接时显示主按钮「设置屏幕操作 · 两步完成」。
   */
  private void refreshA11yStatus(TextView status) {
    if (status == null || !isAdded()) return;
    String st = DshaAccessibilityService.enabledState(requireContext());
    boolean enabled = "YES".equals(st);
    boolean connected = enabled && DshaAccessibilityService.connected();
    boolean paused = com.deepseekharness.app.HttpShellService.screenPaused();
    if (connected && paused) {
      status.setText(
          UiText.choose("已暂停：助手不能读屏和操作", "Paused: the assistant cannot observe or act for now"));
    } else if (enabled) {
      status.setText(
          connected
              ? UiText.choose("已连接", "Connected. Computer Use is available in conversations.")
              : UiText.choose(
                  "等待连接", "Enabled, waiting for the system to connect. Try toggling the service."));
    } else if ("NO".equals(st)) {
      status.setText(UiText.choose("未开启", "Not set up"));
    } else {
      status.setText(UiText.choose("尚未连接", "Not connected"));
    }
    int color =
        connected && !paused
            ? R.color.shell_accent
            : connected
                ? R.color.shell_warn
                : enabled ? R.color.shell_text_muted : R.color.shell_bad;
    status.setTextColor(requireContext().getColor(color));
    View page = getView();
    if (page == null) return;
    page.findViewById(R.id.computer_use_accessibility)
        .setVisibility(connected ? View.GONE : View.VISIBLE);
    page.findViewById(R.id.screen_resetup).setVisibility(connected ? View.VISIBLE : View.GONE);
  }
}
