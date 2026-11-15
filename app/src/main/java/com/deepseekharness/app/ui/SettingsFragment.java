package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;

import java.util.function.Supplier;

/**
 * 设置页：模块入口（安装/配置/数据与备份）+ 其他（更新/自检/重新解压/关于）。
 */
public class SettingsFragment extends Fragment {

  private static final TabOption[] TAB_OPTIONS = {
    new TabOption("安装", "", InstallFragment::new, R.drawable.ic_ui2_box),
    new TabOption("配置", "", ConfigFragment::new, R.drawable.ic_settings),
    new TabOption("数据与备份", "", WorkspaceFragment::new, R.drawable.ic_ui2_folder),
    new TabOption("设备能力授权", "", DeviceGrantsFragment::new, R.drawable.ic_ui_shield),
    new TabOption("任务状态上岛", "", IslandFragment::new, R.drawable.ic_ui2_box),
    new TabOption("GitHub 登录", "", GitHubLoginFragment::new, R.drawable.ic_ui_link),
  };

  private static final String STATE_SELECTED = "settings_selected";
  private static final String KEY_OVERLAY = "overlay";
  private static final String KEY_ABOUT = "about";
  private static final String DEFAULT_KEY = "tab0";

  /** 双栏左侧可选中的行：key → 行视图。单栏时不使用选中态。 */
  private final java.util.Map<String, View> selectable = new java.util.LinkedHashMap<>();

  private boolean twoPane;
  private String selectedKey = DEFAULT_KEY;

  /** 页脚「技术说明」的折叠状态；旋转/切页/返回后保留，用户折叠后不再被默认展开覆盖。 */
  private DisclosureCard.Memory disclosure = new DisclosureCard.Memory();

  /** 分组吸顶与折叠状态（F4）：设置页三组，收起后正文不进读屏。 */
  private GroupedList.Memory groups = new GroupedList.Memory();

  /** 屏幕常亮/省电模式互斥的编程式开关同步；置位期间不再触发持久化、服务刷新与提示。 */
  private boolean syncingPowerSwitches;

  private final androidx.fragment.app.FragmentManager.OnBackStackChangedListener detailStack =
      this::notifyHost;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    twoPane = SettingsPanes.twoPane(requireContext());
    if (savedInstanceState != null)
      selectedKey = savedInstanceState.getString(STATE_SELECTED, DEFAULT_KEY);
    // 从双栏旋转/分屏到单栏：上次恢复的详情页不再有可见容器，直接丢弃，回到整页列表。
    if (!twoPane && !getChildFragmentManager().getFragments().isEmpty()) {
      var children = getChildFragmentManager();
      children.popBackStackImmediate(
          null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
      var removal = children.beginTransaction();
      for (Fragment child : children.getFragments()) removal.remove(child);
      removal.commitNow();
    }
    getChildFragmentManager().addOnBackStackChangedListener(detailStack);
  }

  @Override
  public void onDestroy() {
    getChildFragmentManager().removeOnBackStackChangedListener(detailStack);
    super.onDestroy();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString(STATE_SELECTED, selectedKey);
    disclosure.save(outState, "disclosure");
    groups.save(outState, "groups");
  }

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View v = inflater.inflate(R.layout.fragment_settings, container, false);
    PressFeedback.attachButtons(v);
    selectable.clear();
    if (savedInstanceState != null) {
      disclosure = DisclosureCard.Memory.restore(savedInstanceState, "disclosure");
      groups = GroupedList.Memory.restore(savedInstanceState, "groups");
    }

    LinearLayout tabs = v.findViewById(R.id.settings_tabs);
    int optionCount = TAB_OPTIONS.length;
    for (int i = 0; i < optionCount; i++) {
      if (i > 0) {
        View line = divider();
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
        lineParams.leftMargin = dp(44);
        tabs.addView(line, lineParams);
      }
      tabs.addView(buildRow(i), new LinearLayout.LayoutParams(-1, -2));
    }
    com.deepseekharness.app.core.ConfigStore config =
        HarnessController.get(requireContext()).config();
    com.google.android.material.materialswitch.MaterialSwitch eco =
        v.findViewById(R.id.settings_eco);
    com.google.android.material.materialswitch.MaterialSwitch keepOn =
        v.findViewById(R.id.settings_keep_screen_on_switch);

    TextView powerTitle = v.findViewById(R.id.settings_power_title);
    powerTitle.setText(com.deepseekharness.app.util.UiText.text("省电模式"));
    TextView powerHint = v.findViewById(R.id.settings_power_hint);
    eco.setContentDescription(com.deepseekharness.app.util.UiText.text("省电模式"));
    eco.setChecked(config.isEcoMode());
    // 行高恒定：副标题不再随开关出现/消失，避免开关在行内上下漂移。
    if (powerHint != null) powerHint.setVisibility(View.GONE);

    TextView keepTitle = v.findViewById(R.id.settings_keep_screen_on_title);
    keepTitle.setText(com.deepseekharness.app.util.UiText.text("屏幕常亮"));
    keepOn.setContentDescription(com.deepseekharness.app.util.UiText.text("屏幕常亮"));
    keepOn.setChecked(config.isKeepScreenOn());

    // 两个开关硬互斥：开启任一边都会强制关闭另一边；关闭只改自己，不误伤另一边。
    eco.setOnCheckedChangeListener(
        (button, checked) -> {
          if (syncingPowerSwitches) return;
          applyPowerDecision(
              config,
              eco,
              keepOn,
              com.deepseekharness.app.util.PowerModePolicy.afterEcoModeToggle(
                  config.isKeepScreenOn(), config.isEcoMode(), checked));
        });
    keepOn.setOnCheckedChangeListener(
        (button, checked) -> {
          if (syncingPowerSwitches) return;
          applyPowerDecision(
              config,
              eco,
              keepOn,
              com.deepseekharness.app.util.PowerModePolicy.afterKeepScreenOnToggle(
                  config.isKeepScreenOn(), config.isEcoMode(), checked));
        });

    // 隐藏后台：只改最近任务可见性，不动常驻通知、前台服务与 ADB 保活。
    TextView hiddenTitle = v.findViewById(R.id.settings_hidden_recents_title);
    hiddenTitle.setText(com.deepseekharness.app.util.UiText.text("隐藏后台"));
    TextView hiddenHint = v.findViewById(R.id.settings_hidden_recents_hint);
    hiddenHint.setText(com.deepseekharness.app.util.UiText.text("离开后从最近任务移除；系统保留当前卡片时不显示内容"));
    com.google.android.material.materialswitch.MaterialSwitch hiddenSwitch =
        v.findViewById(R.id.settings_hidden_recents_switch);
    hiddenSwitch.setContentDescription(com.deepseekharness.app.util.UiText.text("隐藏后台"));
    hiddenSwitch.setChecked(config.isHideFromRecents());

    // 严格模式是「隐藏后台」的实验性子项：离开应用即结束 UI 任务，当前卡片也不再保留；
    // dsh 进程、终端会话、ADB 保活与常驻通知都不受影响。放在父开关关闭时隐藏。
    TextView strictTitle = v.findViewById(R.id.settings_hidden_recents_strict_title);
    strictTitle.setText(com.deepseekharness.app.util.UiText.text("严格模式（实验）"));
    TextView strictHint = v.findViewById(R.id.settings_hidden_recents_strict_hint);
    strictHint.setText(com.deepseekharness.app.util.UiText.text("离开应用即结束界面，卡片不再保留；重新打开会重建页面"));
    com.google.android.material.materialswitch.MaterialSwitch strictSwitch =
        v.findViewById(R.id.settings_hidden_recents_strict_switch);
    strictSwitch.setContentDescription(com.deepseekharness.app.util.UiText.text("严格模式（实验）"));
    strictSwitch.setChecked(config.isHideRecentsStrict());
    View strictRow = v.findViewById(R.id.settings_hidden_recents_strict);
    View strictDivider = v.findViewById(R.id.settings_hidden_recents_strict_divider);
    Runnable applyStrictVisibility =
        () -> {
          int visibility = config.isHideFromRecents() ? View.VISIBLE : View.GONE;
          strictRow.setVisibility(visibility);
          strictDivider.setVisibility(visibility);
        };
    applyStrictVisibility.run();
    strictSwitch.setOnCheckedChangeListener(
        (button, checked) -> config.setHideRecentsStrict(checked));
    strictRow.setOnClickListener(x -> strictSwitch.toggle());

    hiddenSwitch.setOnCheckedChangeListener(
        (button, checked) -> {
          config.setHideFromRecents(checked);
          // 截图遮挡跟随父开关立即重放；任务排除结果回读失败时提示下次生效。
          RecentsPrivacyGuard.refreshAll();
          applyStrictVisibility.run();
          if (!BrowserTasks.applyRecentsPreference(requireContext()))
            android.widget.Toast.makeText(
                    requireContext(),
                    com.deepseekharness.app.util.UiText.text("已保存，隐藏将在下次生效"),
                    android.widget.Toast.LENGTH_LONG)
                .show();
        });
    v.findViewById(R.id.settings_hidden_recents).setOnClickListener(x -> hiddenSwitch.toggle());

    String version = "unknown";
    try {
      version =
          requireContext()
              .getPackageManager()
              .getPackageInfo(requireContext().getPackageName(), 0)
              .versionName;
    } catch (Exception ignored) {
    }
    TextView ver = v.findViewById(R.id.settings_ver);
    // 页脚技术说明（B3）：版本留在摘要上，包名与系统版本收进折叠区（不创建隐藏子视图）。
    ver.setText(com.deepseekharness.app.util.UiText.format("技术说明 · DSHA v%s", version));
    TechNote.Model tech = new TechNote.Model();
    tech.key = "settings-tech";
    tech.detail =
        requireContext().getPackageName() + " · Android " + android.os.Build.VERSION.RELEASE;
    TechNote.bind(ver, tech, disclosure, null);
    TextView updateSub = v.findViewById(R.id.settings_update_sub);
    updateSub.setText(com.deepseekharness.app.util.UiText.text("稳定版与预览版更新"));

    View about = v.findViewById(R.id.settings_about);
    selectable.put(KEY_ABOUT, about);
    about.setOnClickListener(x -> select(KEY_ABOUT));
    v.findViewById(R.id.settings_update).setOnClickListener(x -> checkUpdate());
    v.findViewById(R.id.settings_selftest).setOnClickListener(x -> runSelftest());

    // 语言入口：标题在中文界面下也带英文「Language」（只写「语言 · 简体中文」时，
    // 非中文用户根本认不出这是语言开关）；右侧只显示当前生效语言，不常显「跟随系统」说明。
    String preference = config.getUiLanguagePreference();
    String effective = config.getUiLanguage();
    String currentLabel = com.deepseekharness.app.util.UiLanguagePreference.nativeName(effective);
    String summary = currentLabel;
    LinearLayout appearance = v.findViewById(R.id.settings_appearance);
    TextView languageSummary = v.findViewById(R.id.settings_language);
    languageSummary.setText(summary);
    // 方案 A：跟随系统卡片 + 母语名网格，点选即生效。
    View.OnClickListener openLanguageDialog =
        x -> LanguagePicker.show(requireContext(), preference);
    appearance.setOnClickListener(openLanguageDialog);
    languageSummary.setOnClickListener(openLanguageDialog);
    // 主题入口只在设置里：独立页面，主界面顶栏不再直接切换。
    ((TextView) v.findViewById(R.id.settings_theme_title))
        .setText(com.deepseekharness.app.util.UiText.text("主题与外观"));
    v.findViewById(R.id.settings_theme)
        .setOnClickListener(x -> ThemeActivity.open(requireContext()));
    renderThemeSummary(v);
    View overlayRow = v.findViewById(R.id.settings_overlay);
    selectable.put(KEY_OVERLAY, overlayRow);
    overlayRow.setOnClickListener(x -> select(KEY_OVERLAY));
    // F4：三组（环境 / 体验 / 支持）标题吸顶并可折叠；折叠状态在旋转与分屏后保留。
    installGroups(v);
    if (!twoPane) return v;
    return twoPaneShell(v, savedInstanceState);
  }

  private void installGroups(View root) {
    android.widget.ScrollView scroll = root instanceof android.widget.ScrollView view ? view : null;
    if (scroll == null) return;
    GroupedList.install(
        scroll,
        java.util.List.of(
            new GroupedList.Group(
                "environment",
                root.findViewById(R.id.settings_section_environment),
                root.findViewById(R.id.settings_tabs)),
            new GroupedList.Group(
                "experience",
                root.findViewById(R.id.settings_section_experience),
                root.findViewById(R.id.settings_group_experience)),
            new GroupedList.Group(
                "support",
                root.findViewById(R.id.settings_section_support),
                root.findViewById(R.id.settings_group_support))),
        groups,
        null);
  }

  @Override
  public void onResume() {
    super.onResume();
    // 从主题页返回且明暗未变时不会重建，这里刷新摘要。
    if (getView() != null) renderThemeSummary(getView());
  }

  /** 摘要显示偏好；跟随系统时补上当前实际明暗。 */
  private void renderThemeSummary(View root) {
    TextView sub = root.findViewById(R.id.settings_theme_sub);
    if (sub == null) return;
    String dark = com.deepseekharness.app.util.UiText.text("深色");
    String light = com.deepseekharness.app.util.UiText.text("浅色");
    // 右侧只显示当前生效明暗，不常显「跟随系统」说明；偏好本身在主题页可见。
    String mode = ThemeController.isDark(requireContext()) ? dark : light;
    String palette = ThemeController.palette(requireContext());
    // 标准色不重复写出，其余配色附在明暗后面。
    sub.setText(
        com.deepseekharness.app.util.UiThemePalette.needsOverlay(palette)
            ? com.deepseekharness.app.util.UiText.format(
                "%s · %s", mode, ThemeActivity.paletteName(palette))
            : mode);
  }

  // ---------------------------------------------------------------- 方案 A 双栏

  boolean isTwoPane() {
    return twoPane && getView() != null && getView().findViewById(R.id.settings_detail) != null;
  }

  /** 左侧列表固定宽度、右侧详情占余下宽度；详情容器 id 固定，子页可跨旋转恢复。 */
  private View twoPaneShell(View list, @Nullable Bundle savedInstanceState) {
    android.content.Context context = requireContext();
    list.setId(R.id.settings_list_scroll);
    for (View row : selectable.values()) {
      row.setBackgroundResource(R.drawable.bg_settings_row);
      row.setForeground(context.getDrawable(R.drawable.bg_action_plain));
    }
    LinearLayout shell = new LinearLayout(context);
    shell.setOrientation(LinearLayout.HORIZONTAL);
    shell.setBackgroundColor(context.getColor(R.color.shell_bg));
    shell.addView(list, new LinearLayout.LayoutParams(dp(LIST_PANE_DP), -1));
    View line = divider();
    shell.addView(line, new LinearLayout.LayoutParams(dp(1), -1));
    android.widget.FrameLayout detail = new android.widget.FrameLayout(context);
    detail.setId(R.id.settings_detail);
    shell.addView(detail, new LinearLayout.LayoutParams(0, -1, 1f));
    // 首次进入：直接给右侧一个默认详情；旋转/恢复时由 FragmentManager 自行恢复原详情页。
    if (getChildFragmentManager().findFragmentById(R.id.settings_detail) == null)
      shell.post(
          () -> {
            if (isAdded() && !getChildFragmentManager().isStateSaved()) showDetail(selectedKey);
          });
    markSelected();
    return shell;
  }

  private static final int LIST_PANE_DP = 320;

  /** 单栏：整页推进（原行为）；双栏：右侧详情替换并清空详情内的下一级返回栈。 */
  private void select(String key) {
    if (!isTwoPane()) {
      Fragment page = create(key);
      if (page == null) return;
      UiMotion.page(requireContext(), getParentFragmentManager().beginTransaction())
          .replace(R.id.fragment_container, page)
          .addToBackStack("settings")
          .commit();
      return;
    }
    if (key.equals(selectedKey)
        && getChildFragmentManager().getBackStackEntryCount() == 0
        && getChildFragmentManager().findFragmentById(R.id.settings_detail) != null) return;
    showDetail(key);
  }

  private void showDetail(String key) {
    Fragment page = create(key);
    if (page == null) {
      key = DEFAULT_KEY;
      page = create(key);
    }
    selectedKey = key;
    markSelected();
    var children = getChildFragmentManager();
    if (children.isStateSaved()) return;
    children.popBackStackImmediate(
        null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    UiMotion.page(requireContext(), children.beginTransaction())
        .replace(R.id.settings_detail, page)
        .commit();
    notifyHost();
  }

  /** 详情页内再打开下一级页面（由 {@link SettingsPanes#open} 调用）。 */
  void pushDetail(Fragment page) {
    var children = getChildFragmentManager();
    if (children.isStateSaved()) return;
    UiMotion.page(requireContext(), children.beginTransaction())
        .replace(R.id.settings_detail, page)
        .addToBackStack("settings_detail")
        .commit();
  }

  /** 系统返回 / 顶栏返回：先退详情内的下一级页面。返回 true 表示已处理。 */
  boolean popDetail() {
    if (!isTwoPane()) return false;
    var children = getChildFragmentManager();
    if (children.getBackStackEntryCount() == 0 || children.isStateSaved()) return false;
    children.popBackStackImmediate();
    return true;
  }

  boolean hasNestedDetail() {
    return isTwoPane() && getChildFragmentManager().getBackStackEntryCount() > 0;
  }

  @Nullable
  Fragment shownDetail() {
    return isTwoPane() ? getChildFragmentManager().findFragmentById(R.id.settings_detail) : null;
  }

  @Nullable
  private Fragment create(String key) {
    if (KEY_OVERLAY.equals(key)) return new OverlayFragment();
    if (KEY_ABOUT.equals(key)) return new AboutFragment();
    if (key.startsWith("tab")) {
      try {
        int index = Integer.parseInt(key.substring(3));
        if (index >= 0 && index < TAB_OPTIONS.length && TAB_OPTIONS[index].factory != null)
          return TAB_OPTIONS[index].factory.get();
      } catch (NumberFormatException ignored) {
      }
    }
    return null;
  }

  private void markSelected() {
    // View 的选中态会同步到无障碍节点（TalkBack 读出「已选择」），不另加委托。
    for (java.util.Map.Entry<String, View> entry : selectable.entrySet())
      entry.getValue().setSelected(twoPane && entry.getKey().equals(selectedKey));
  }

  private void notifyHost() {
    if (getActivity() instanceof MainActivity)
      ((MainActivity) getActivity()).onSettingsDetailChanged();
  }

  private void runSelftest() {
    startActivity(new Intent(requireContext(), DiagnosticActivity.class));
  }

  private void checkUpdate() {
    startActivity(new Intent(requireContext(), UpdateActivity.class));
  }

  private LinearLayout buildRow(final int index) {
    TabOption opt = TAB_OPTIONS[index];
    LinearLayout row = new LinearLayout(requireContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(56));
    row.setPadding(dp(16), dp(12), dp(16), dp(12));
    row.setFocusable(true);
    // 行内按压反馈沿用主题 ripple；卡片圆角由父容器 bg_shell_card 承担。
    row.setBackgroundResource(R.drawable.bg_action_plain);

    LinearLayout body = new LinearLayout(requireContext());
    body.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams bodyParams =
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    bodyParams.leftMargin = dp(12);
    bodyParams.rightMargin = dp(8);
    body.setLayoutParams(bodyParams);

    TextView title = new TextView(requireContext());
    title.setText(
        index == 0
            ? getString(R.string.ui2_install_environment)
            : index == 1
                ? getString(R.string.ui2_runtime_config)
                : com.deepseekharness.app.util.UiText.text(opt.title));
    title.setTextSize(14);
    title.setTextColor(requireContext().getColor(R.color.shell_text));
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setIncludeFontPadding(false);

    TextView sub = new TextView(requireContext());
    sub.setText(com.deepseekharness.app.util.UiText.text(opt.sub));
    sub.setTextSize(12);
    sub.setTextColor(requireContext().getColor(R.color.shell_text_muted));
    sub.setIncludeFontPadding(false);
    sub.setMaxLines(2);
    sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams slp =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    slp.topMargin = dp(4);
    sub.setLayoutParams(slp);

    body.addView(title);
    // 入口行只保留名称；状态摘要（主题/语言）与开关行以外的说明文案不再常显。
    if (!opt.sub.isEmpty()) body.addView(sub);

    android.widget.ImageView icon = new android.widget.ImageView(requireContext());
    icon.setImageResource(opt.icon);
    icon.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            requireContext().getColor(R.color.shell_accent)));
    icon.setBackgroundResource(R.drawable.bg_logo);
    icon.setPadding(dp(7), dp(7), dp(7), dp(7));
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
    row.addView(body);

    android.widget.ImageView chevron = new android.widget.ImageView(requireContext());
    chevron.setImageResource(R.drawable.ic_ui2_chevron);
    chevron.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            requireContext().getColor(R.color.shell_text_muted)));
    chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(chevron, new LinearLayout.LayoutParams(dp(16), dp(16)));

    if (twoPane) {
      // 双栏左侧列表较窄：说明收成一行，完整含义看右侧详情标题与页内说明。
      sub.setMaxLines(1);
      row.setMinimumHeight(dp(52));
    }
    if (opt.activity == null) selectable.put("tab" + index, row);
    row.setOnClickListener(
        v -> {
          if (opt.activity != null) {
            startActivity(new Intent(requireContext(), opt.activity));
          } else {
            select("tab" + index);
          }
        });
    return row;
  }

  /**
   * 落地屏幕常亮/省电模式互斥决策：先写持久化，再同步两个开关与服务/窗口。
   * 编程式 setChecked 只在 {@link #syncingPowerSwitches} 保护下执行，不会再次进入本方法或刷新服务。
   */
  private void applyPowerDecision(
      com.deepseekharness.app.core.ConfigStore config,
      com.google.android.material.materialswitch.MaterialSwitch eco,
      com.google.android.material.materialswitch.MaterialSwitch keepOn,
      com.deepseekharness.app.util.PowerModePolicy.Decision decision) {
    if (!decision.changed()) return;
    if (decision.keepScreenOn != decision.previousKeepScreenOn)
      config.setKeepScreenOn(decision.keepScreenOn);
    if (decision.ecoMode != decision.previousEcoMode) config.setEcoMode(decision.ecoMode);
    syncingPowerSwitches = true;
    try {
      eco.setChecked(decision.ecoMode);
      keepOn.setChecked(decision.keepScreenOn);
    } finally {
      syncingPowerSwitches = false;
    }
    if (decision.keepScreenOn != decision.previousKeepScreenOn) {
      android.app.Activity activity = getActivity();
      if (activity != null) ModernAndroidUi.applyKeepScreenOn(activity);
    }
    if (decision.ecoMode != decision.previousEcoMode)
      com.deepseekharness.app.HarnessService.refreshPowerMode(requireContext());
    if (decision.disabledEcoMode)
      android.widget.Toast.makeText(
              requireContext(),
              com.deepseekharness.app.util.UiText.choose("已关闭省电模式", "Eco mode turned off"),
              android.widget.Toast.LENGTH_SHORT)
          .show();
    else if (decision.disabledKeepScreenOn)
      android.widget.Toast.makeText(
              requireContext(),
              com.deepseekharness.app.util.UiText.choose("已关闭屏幕常亮", "Keep screen on turned off"),
              android.widget.Toast.LENGTH_SHORT)
          .show();
  }

  private View divider() {
    View line = new View(requireContext());
    line.setBackgroundColor(requireContext().getColor(R.color.shell_line));
    return line;
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  private static final class TabOption {
    final String title;
    final String sub;
    final Supplier<Fragment> factory;
    final Class<? extends android.app.Activity> activity;
    final int icon;

    TabOption(String title, String sub, Supplier<Fragment> factory, int icon) {
      this.title = title;
      this.sub = sub;
      this.factory = factory;
      this.activity = null;
      this.icon = icon;
    }

    TabOption(String title, String sub, Class<? extends android.app.Activity> activity, int icon) {
      this.title = title;
      this.sub = sub;
      this.factory = null;
      this.activity = activity;
      this.icon = icon;
    }
  }
}
