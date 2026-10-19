package com.deepseekharness.app.ui;

import com.google.gson.JsonObject;
import org.junit.Test;
import org.junit.After;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import androidx.fragment.app.FragmentActivity;
import androidx.compose.ui.test.junit4.ComposeTestRule;
import androidx.compose.ui.test.junit4.AndroidComposeTestRule_androidKt;
import androidx.compose.ui.test.SemanticsNodeInteraction;
import static androidx.compose.ui.test.FiltersKt.*;
import static androidx.compose.ui.test.ActionsKt.*;
import static androidx.compose.ui.test.AssertionsKt.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.ui.miuix.MiuixLaunch;
import com.deepseekharness.app.ui.miuix.MiuixNavigation;
import com.deepseekharness.app.ui.miuix.MiuixSettings;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 原生界面状态恢复及 Miuix 展示契约，不启动 DSH 或操作用户文件。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = android.app.Application.class,
    qualifiers = "zh-rCN-w393dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ModelSetupDraftTest {
  /** 提供页面所需的应用级对象，不执行生产 Application 的后台初始化。 */
  public static final class UiApplication extends com.deepseekharness.app.DshaApp {
    @Override
    public void onCreate() {}
  }

  @Test
  public void discoveryRemainsPendingAcrossRecreationUntilUserConsumesIt() {
    ModelSetupActivity.Draft retained = new ModelSetupActivity.Draft();
    retained.entry = new JsonObject();

    assertTrue(retained.shouldShowDiscovery(7));
    // Activity 旋转复用同一个 ViewModel；只显示对话框不能提前消费结果。
    assertTrue(retained.shouldShowDiscovery(7));

    retained.consumeDiscovery(7);
    assertFalse(retained.shouldShowDiscovery(7));
    assertTrue(retained.shouldShowDiscovery(8));
  }

  @Test
  public void discoveryIsNotShownAfterEditorWasClosed() {
    ModelSetupActivity.Draft retained = new ModelSetupActivity.Draft();
    retained.entry = new JsonObject();
    assertTrue(retained.shouldShowDiscovery(1));
    retained.clear();
    assertFalse(retained.shouldShowDiscovery(1));
  }

  @Rule
  public final ComposeTestRule compose =
      AndroidComposeTestRule_androidKt.createEmptyComposeRule(
          kotlin.coroutines.EmptyCoroutineContext.INSTANCE);

  private ActivityController<FragmentActivity> host;

  private FragmentActivity activity() {
    if (host == null) {
      host = Robolectric.buildActivity(FragmentActivity.class);
      host.get().setTheme(R.style.Theme_DeepseekHarness);
      host.setup().visible();
      com.deepseekharness.app.util.UiText.setLanguage("zh");
    }
    return host.get();
  }

  private void show(android.view.View view) {
    activity().setContentView(view);
    compose.waitForIdle();
  }

  private SemanticsNodeInteraction text(String value) {
    return compose.onNode(hasText(value, false, false), false);
  }

  @After
  public void closePage() {
    if (host != null) host.pause().stop().destroy();
    com.deepseekharness.app.util.UiText.setLanguage("zh");
  }

  @Test
  public void navigationCallsOwnerBeforeChangingSelectionAndCanReject() {
    MiuixNavigation nav = new MiuixNavigation(activity());
    AtomicInteger calls = new AtomicInteger();
    nav.setOnItemSelectedListener(
        id -> {
          assertEquals(R.id.nav_launch, nav.getSelectedItemId());
          calls.incrementAndGet();
          return id != R.id.nav_terminal;
        });
    nav.setSelectedItemId(R.id.nav_terminal);
    assertEquals(R.id.nav_launch, nav.getSelectedItemId());
    nav.setSelectedItemId(R.id.nav_settings);
    assertEquals(R.id.nav_settings, nav.getSelectedItemId());
    nav.setSelectedItemId(-1);
    assertEquals(2, calls.get());
  }

  @Test
  public void navigationRestoresSelectionWithoutDispatchingAnotherPage() {
    MiuixNavigation old = new MiuixNavigation(activity());
    old.setSelectedItemId(R.id.nav_terminal);
    old.setId(R.id.bottom_nav);
    android.util.SparseArray<android.os.Parcelable> state = new android.util.SparseArray<>();
    old.saveHierarchyState(state);
    MiuixNavigation restored = new MiuixNavigation(activity());
    AtomicInteger calls = new AtomicInteger();
    restored.setOnItemSelectedListener(
        id -> {
          calls.incrementAndGet();
          return true;
        });
    restored.setId(R.id.bottom_nav);
    restored.restoreHierarchyState(state);
    assertEquals(R.id.nav_terminal, restored.getSelectedItemId());
    assertEquals(0, calls.get());
    show(restored);
    assertIsSelected(text(activity().getString(R.string.ui_m0021)));
    performClick(text(activity().getString(R.string.ui_m0023)));
    assertEquals(R.id.nav_settings, restored.getSelectedItemId());
    assertEquals(1, calls.get());
  }

  @Test
  public void launchDisablesAnInFlightActionAndRendersTheFailure() {
    MiuixLaunch page = new MiuixLaunch(activity());
    AtomicInteger calls = new AtomicInteger();
    page.start.setOnClickListener(
        v -> {
          calls.incrementAndGet();
          page.start.setEnabled(false);
        });
    show(page);
    String start = page.start.getText();
    performClick(text(start));
    assertIsNotEnabled(text(start));
    performClick(text(start));
    assertEquals(1, calls.get());
    page.status.setText("鉴权失败，请重试");
    page.status.setVisibility(android.view.View.VISIBLE);
    page.start.setEnabled(true);
    assertIsDisplayed(text("鉴权失败，请重试"));
    assertIsEnabled(text(start));
  }

  @Test
  public void settingsRoutesAndEcoSwitchUseTheirRealCallbacks() {
    AtomicInteger target = new AtomicInteger();
    AtomicInteger changed = new AtomicInteger();
    MiuixSettings page =
        new MiuixSettings(
            activity(),
            "test",
            "跟随系统 · 简体中文",
            false,
            target::set,
            value -> changed.set(value ? 1 : -1));
    show(page);
    performClick(text(activity().getString(R.string.ui2_runtime_config)));
    assertEquals(R.id.settings_config, target.get());
    performScrollTo(text("省电模式"));
    performClick(text("省电模式"));
    assertEquals(1, changed.get());
    text("熄屏空闲 1 分钟后减少保活；有任务时继续运行。").assertExists("省电说明随开关改变");
    performScrollTo(text(activity().getString(R.string.ui_language_title)));
    performClick(text(activity().getString(R.string.ui_language_title)));
    assertEquals(R.id.settings_appearance, target.get());
  }

  @Test
  public void cardPageCanRenderInsideTheExistingDialogAndDispatchEntry() {
    CardPage page = new CardPage(activity(), "模型", "保留现有模型表单");
    AtomicInteger calls = new AtomicInteger();
    page.entry(page.card(), "选择模型", "只更新草稿", R.drawable.ic_settings, calls::incrementAndGet);
    androidx.appcompat.app.AlertDialog dialog = CardSheet.create(activity(), page);
    try {
      dialog.show();
      compose.waitForIdle();
      performClick(text("选择模型"));
      assertEquals(1, calls.get());
    } finally {
      dialog.dismiss();
    }
  }

  @Test
  public void launchLogKeepsItsOwnScrollAndSelection() throws Exception {
    MiuixLaunch page = new MiuixLaunch(activity());
    page.logText.setTextColor(android.graphics.Color.MAGENTA);
    page.logText.setText("本地布局测试日志 · sample output\n".repeat(1000));
    show(page);
    assertTrue(page.logText.isTextSelectable());
    page.logScroll.scrollTo(0, 100);
    assertFalse(page.logScroll.shouldFollowEnd());
    page.logText.append("new line\n");
    compose.waitForIdle();
    assertEquals(100, page.logScroll.getScrollY());
    int end =
        page.logScroll.getChildAt(0).getHeight()
            - page.logScroll.getHeight()
            + page.logScroll.getPaddingTop()
            + page.logScroll.getPaddingBottom();
    for (int scroll : new int[] {100, end}) {
      page.logScroll.scrollTo(0, scroll);
      compose.waitForIdle();
      int[] pagePosition = new int[2];
      int[] logPosition = new int[2];
      page.getLocationInWindow(pagePosition);
      page.logScroll.getLocationInWindow(logPosition);
      android.graphics.Rect viewport =
          new android.graphics.Rect(
              logPosition[0] - pagePosition[0],
              logPosition[1] - pagePosition[1],
              logPosition[0] - pagePosition[0] + page.logScroll.getWidth(),
              logPosition[1] - pagePosition[1] + page.logScroll.getHeight());
      android.graphics.Bitmap image =
          android.graphics.Bitmap.createBitmap(
              page.getWidth(), page.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
      try {
        page.draw(new android.graphics.Canvas(image));
        int logPixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
          for (int x = 0; x < image.getWidth(); x++) {
            if (image.getPixel(x, y) != android.graphics.Color.MAGENTA) continue;
            logPixels++;
            assertTrue(
                "长日志不能绘制到操作卡片上：" + viewport + " pixel=" + x + "," + y, viewport.contains(x, y));
          }
        }
        assertTrue(
            "日志框内仍须有可读的日志 scroll=" + page.logScroll.getScrollY() + " rect=" + viewport,
            logPixels > 20);
      } finally {
        image.recycle();
      }
    }
    assertTrue(page.logScroll.shouldFollowEnd());
    screenshot("launch-long-log", page);
  }

  @Test
  public void launchLightScreenshot() throws Exception {
    MiuixLaunch page = new MiuixLaunch(activity());
    show(page);
    assertIsDisplayed(text(page.start.getText()));
    screenshot("launch-light", page);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w393dp-h850dp-night-mdpi")
  public void settingsDarkScreenshot() throws Exception {
    MiuixSettings page =
        new MiuixSettings(activity(), "173", "跟随系统 · 简体中文", false, ignored -> {}, ignored -> {});
    show(page);
    assertIsDisplayed(text(activity().getString(R.string.ui2_runtime_config)));
    screenshot("settings-dark", page);
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h360dp-mdpi")
  public void englishShortWindowWithLargeTextKeepsActionsReachable() throws Exception {
    FragmentActivity owner = activity();
    android.content.res.Configuration config =
        new android.content.res.Configuration(owner.getResources().getConfiguration());
    config.fontScale = 1.6f;
    owner.getResources().updateConfiguration(config, owner.getResources().getDisplayMetrics());
    com.deepseekharness.app.util.UiText.setLanguage("en");
    MiuixLaunch page = new MiuixLaunch(owner);
    show(page);
    // show() 只挂载已有界面；确保运行时文案与资源均处于英文。
    com.deepseekharness.app.util.UiText.setLanguage("en");
    performScrollTo(text(page.start.getText()));
    assertIsDisplayed(text(page.start.getText()));
    performScrollTo(text(page.recovery.getText()));
    assertIsDisplayed(text(page.recovery.getText()));
    screenshot("launch-short-large", page);
  }

  @Test
  @Config(sdk = Config.OLDEST_SDK)
  @GraphicsMode(GraphicsMode.Mode.LEGACY)
  public void minimumSupportedSdkCanRenderAndInvokeThePrimaryAction() {
    MiuixLaunch page = new MiuixLaunch(activity());
    AtomicInteger calls = new AtomicInteger();
    page.start.setOnClickListener(ignored -> calls.incrementAndGet());
    show(page);
    performClick(text(page.start.getText()));
    assertEquals(1, calls.get());
  }

  @Test
  public void nativePageLayoutsRenderWithTheirOriginalControlIds() throws Exception {
    int[][] pages = {
      {R.layout.fragment_config, R.id.config_save},
      {R.layout.fragment_device_grants, R.id.config_adb_pair},
      {R.layout.fragment_workspace, R.id.workspace_backup},
      {R.layout.fragment_install, R.id.install_btn},
      {R.layout.activity_update, R.id.update_check},
      {R.layout.activity_diagnostics, R.id.diagnostic_refresh},
      {R.layout.activity_adb_pair, R.id.adb_pair_start},
      {R.layout.activity_plugin_install, R.id.link_install_manage},
      {R.layout.activity_extract, R.id.extract_detail},
      {R.layout.fragment_terminal, R.id.term_input},
      {R.layout.fragment_pty_terminal, R.id.pty_view},
      {R.layout.activity_web_preview, R.id.web_retry}
    };
    for (int[] spec : pages) {
      android.view.View page = activity().getLayoutInflater().inflate(spec[0], null);
      assertNotNull("Java 页面必须仍能同步查到控件", page.findViewById(spec[1]));
      if (spec[0] == R.layout.activity_web_preview)
        page.findViewById(R.id.web_error_panel).setVisibility(android.view.View.VISIBLE);
      show(page);
      assertTrue(page.getWidth() > 0);
      assertTrue(page.getHeight() > 0);
      screenshot(
          ((activity().getResources().getConfiguration().uiMode
                          & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                      == android.content.res.Configuration.UI_MODE_NIGHT_YES
                  ? "dark-"
                  : "")
              + activity().getResources().getResourceEntryName(spec[0]),
          page);
    }
  }

  @Test
  public void pluginCardsSeparateLongStatusFromActionsAndKeepCallbacks() throws Exception {
    android.view.View page = activity().getLayoutInflater().inflate(R.layout.item_plugin, null);
    android.widget.TextView name = page.findViewById(R.id.pluginName);
    android.widget.TextView state = page.findViewById(R.id.pluginStatus);
    name.setText("@example/a-long-plugin-name-without-short-alias");
    state.setText("加载期间内容变化；检查后重新启用 · 3.1.0\n可更新：3.2.0");
    ((android.widget.TextView) page.findViewById(R.id.pluginDesc))
        .setText("插件说明与来源保持原文；这是用于布局验证的示例。".repeat(3));
    android.widget.CompoundButton toggle = page.findViewById(R.id.pluginSwitch);
    AtomicInteger changed = new AtomicInteger();
    toggle.setOnCheckedChangeListener((button, checked) -> changed.incrementAndGet());
    android.widget.ScrollView scroll = new android.widget.ScrollView(activity());
    scroll.setBackgroundColor(activity().getColor(R.color.surface));
    scroll.addView(page);
    show(scroll);
    assertNotSame(state.getParent(), toggle.getParent());
    toggle.performClick();
    assertEquals(1, changed.get());
    toggle.setEnabled(false);
    assertFalse(toggle.isEnabled());
    assertTrue(name.getLineCount() > 1);
    screenshot("plugin-long-card", scroll);
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h360dp-mdpi")
  public void pageActionsRemainReachableWithLargeTextInLandscape() throws Exception {
    FragmentActivity owner = activity();
    android.content.res.Configuration config =
        new android.content.res.Configuration(owner.getResources().getConfiguration());
    config.fontScale = 2f;
    owner.getResources().updateConfiguration(config, owner.getResources().getDisplayMetrics());
    int[][] pages = {
      {R.layout.fragment_config, R.id.config_workspace_entry},
      {R.layout.fragment_device_grants, R.id.config_battery_opt},
      {R.layout.fragment_install, R.id.install_btn},
      {R.layout.activity_update, R.id.update_browser},
      {R.layout.activity_diagnostics, R.id.diagnostic_steps},
      {R.layout.activity_adb_pair, R.id.adb_pair_help}
    };
    for (int[] spec : pages) {
      android.view.View page = owner.getLayoutInflater().inflate(spec[0], null);
      show(page);
      android.view.View action = page.findViewById(spec[1]);
      action.requestRectangleOnScreen(
          new android.graphics.Rect(0, 0, action.getWidth(), action.getHeight()), true);
      compose.waitForIdle();
      android.graphics.Rect visible = new android.graphics.Rect();
      assertTrue(
          owner.getResources().getResourceEntryName(spec[1]), action.getGlobalVisibleRect(visible));
      assertTrue(visible.height() >= 40);
      screenshot("large-" + owner.getResources().getResourceEntryName(spec[0]), page);
    }
  }

  @Test
  @Config(qualifiers = "zh-rCN-w320dp-h480dp-mdpi")
  public void longCardPageFooterScrollsWithTheFormAndKeepsItsLastAction() throws Exception {
    CardPage page = new CardPage(activity(), "存储与文件", "查看占用，然后选择维护操作。");
    page.kv(page.card(), "工作目录", "/root/Documents/" + "长目录名称/".repeat(24));
    AtomicInteger calls = new AtomicInteger();
    android.widget.Button last = null;
    for (int i = 0; i < 8; i++)
      last =
          page.button(
              page.footer, "第 " + (i + 1) + " 项操作 · 清理前保留原有确认", false, calls::incrementAndGet);
    show(page.root);
    last.requestRectangleOnScreen(
        new android.graphics.Rect(0, 0, last.getWidth(), last.getHeight()), true);
    compose.waitForIdle();
    assertTrue(page.scroll.getScrollY() > 0);
    assertTrue(last.getGlobalVisibleRect(new android.graphics.Rect()));
    last.performClick();
    assertEquals(1, calls.get());
    screenshot("card-actions-short", page.root);
  }

  @Test
  public void miuixPanelKeepsFieldsAvailableBeforeAttachAndFollowsContentHeight() {
    android.widget.LinearLayout form = new android.widget.LinearLayout(activity());
    form.setOrientation(android.widget.LinearLayout.VERTICAL);
    android.widget.EditText input = new android.widget.EditText(activity());
    input.setId(R.id.config_port);
    input.setText("3080");
    form.addView(input);
    android.view.View panel = com.deepseekharness.app.ui.miuix.MiuixUi.card(activity(), form);
    assertSame(input, panel.findViewById(R.id.config_port));
    show(panel);
    assertEquals("3080", input.getText().toString());
    panel.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.AT_MOST));
    assertEquals("卡片背景不能把短表单撑成一屏", form.getMeasuredHeight(), panel.getMeasuredHeight());
    panel.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(
            0, android.view.View.MeasureSpec.UNSPECIFIED));
    assertTrue(panel.getMeasuredHeight() >= input.getMeasuredHeight());
    form.setVisibility(android.view.View.GONE);
    panel.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(
            0, android.view.View.MeasureSpec.UNSPECIFIED));
    assertEquals("隐藏表单不能留下空卡片", 0, panel.getMeasuredHeight());
  }

  @Test
  @Config(qualifiers = "zh-rCN-w1024dp-h768dp-mdpi")
  public void tabletFormsLimitLineLengthWithoutTruncatingLongValues() throws Exception {
    CardPage page = new CardPage(activity(), "应用信息", "");
    android.widget.LinearLayout card = page.card();
    page.kv(card, "当前版本", "0.2.24-20261014.1308-dsh0.2.0-rc.2".repeat(6));
    show(page.root);
    assertTrue(
        page.content.getWidth() - page.content.getPaddingLeft() - page.content.getPaddingRight()
            <= 720);
    android.widget.LinearLayout row = (android.widget.LinearLayout) card.getChildAt(0);
    android.widget.TextView value = (android.widget.TextView) row.getChildAt(1);
    assertTrue(value.getLineCount() > 1);
    assertTrue(value.isTextSelectable());
    screenshot("tablet-long-values", page.root);
  }

  @Test
  public void actionRowsStackLongButtonsAndReturnToColumnsWhenThereIsSpace() {
    ActionRow row = new ActionRow(activity());
    for (int i = 0; i < 2; i++) {
      android.widget.Button action = new android.widget.Button(activity());
      action.setText("检查已有连接并重新授权");
      row.addView(action, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    }
    row.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(
            0, android.view.View.MeasureSpec.UNSPECIFIED));
    assertEquals(android.widget.LinearLayout.VERTICAL, row.getOrientation());
    for (int i = 0; i < 2; i++) ((android.widget.Button) row.getChildAt(i)).setText("检查");
    row.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(
            0, android.view.View.MeasureSpec.UNSPECIFIED));
    assertEquals(android.widget.LinearLayout.HORIZONTAL, row.getOrientation());
  }

  @Test
  public void welcomeAndRecoveryPagesRenderTheirOwnSections() throws Exception {
    for (int layout :
        new int[] {
          R.layout.welcome_page1,
          R.layout.welcome_page2,
          R.layout.welcome_page3,
          R.layout.plugin_list_header,
          R.layout.plugin_list_footer
        }) {
      android.view.View page = activity().getLayoutInflater().inflate(layout, null);
      if (!(page instanceof android.widget.ScrollView)) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity());
        scroll.addView(page);
        page = scroll;
      }
      page.setBackgroundColor(activity().getColor(R.color.surface));
      show(page);
      screenshot(activity().getResources().getResourceEntryName(layout), page);
    }
    StartupRecoveryLayout recovery = new StartupRecoveryLayout(activity(), "模型设置需要检查", () -> {});
    show(recovery.root);
    assertEquals(android.view.View.GONE, recovery.pluginCard.getVisibility());
    screenshot("startup-recovery", recovery.root);
    OnboardingUi onboarding = new OnboardingUi(activity(), "4 / 7", "为第一次启动做准备", "按需配置模型与设备权限。");
    onboarding.row("系统与版本", "Android 13 · Standard");
    onboarding.primary("继续模型配置", () -> {});
    compose.waitForIdle();
    assertSame("引导内容与主操作在同一滚动区域", onboarding.body.getParent(), onboarding.footer.getParent());
    assertEquals(
        0, ((android.view.ViewGroup) onboarding.body.getParent()).indexOfChild(onboarding.body));
    screenshot("onboarding-prepare", onboarding.page);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w393dp-h850dp-night-mdpi")
  public void nativePagesAlsoRenderInDarkMode() throws Exception {
    nativePageLayoutsRenderWithTheirOriginalControlIds();
  }

  @Test
  public void configPageBindsRealChoicesAndExplainsAnInvalidPort() throws Exception {
    activity()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(android.R.id.content, new ConfigFragment())
        .commitNow();
    compose.waitForIdle();
    android.widget.CompoundButton automatic = activity().findViewById(R.id.config_port_auto);
    android.widget.EditText port = activity().findViewById(R.id.config_port);
    android.widget.Button save = activity().findViewById(R.id.config_save);
    DshaSelectView runtime = activity().findViewById(R.id.config_runtime_choice);
    assertNotNull(runtime.getSelectedItem());
    automatic.setChecked(false);
    port.setText("70000");
    assertFalse(save.isEnabled());
    assertNotNull("禁用保存时就近解释原因", port.getError());
    port.setText("3080");
    assertTrue(save.isEnabled());
    assertNull(port.getError());
    screenshot("config-bound", activity().findViewById(android.R.id.content));
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h360dp-mdpi")
  public void welcomePagerKeepsRoomWhenItsActionsUseLargeText() throws Exception {
    FragmentActivity owner = activity();
    android.content.res.Configuration config =
        new android.content.res.Configuration(owner.getResources().getConfiguration());
    config.fontScale = 2f;
    owner.getResources().updateConfiguration(config, owner.getResources().getDisplayMetrics());
    android.view.View page = owner.getLayoutInflater().inflate(R.layout.activity_welcome, null);
    show(page);
    assertTrue("介绍页仍有可滚动的阅读空间", page.findViewById(R.id.welcome_pager).getHeight() >= 80);
    screenshot("welcome-short-large", page);
  }

  @Test
  @Config(sdk = Config.OLDEST_SDK)
  @GraphicsMode(GraphicsMode.Mode.LEGACY)
  public void minimumSupportedSdkKeepsNativeFieldsInsideMiuixCards() {
    miuixPanelKeepsFieldsAvailableBeforeAttachAndFollowsContentHeight();
  }

  @Test
  @Config(qualifiers = "zh-rCN-w320dp-h480dp-mdpi")
  public void aLongCardDialogKeepsItsClosingActionReachable() {
    CardPage page = new CardPage(activity(), "检查备份内容", "原件保留，确认前不会开始恢复。");
    page.card().addView(page.text("数据范围与位置\n".repeat(50), 15, R.color.text));
    androidx.appcompat.app.AlertDialog dialog = CardSheet.create(activity(), page);
    android.widget.Button close = page.button(page.footer, "取消", false, dialog::dismiss);
    try {
      CardSheet.show(dialog, activity());
      compose.waitForIdle();
      close.requestRectangleOnScreen(
          new android.graphics.Rect(0, 0, close.getWidth(), close.getHeight()), true);
      compose.waitForIdle();
      assertTrue(close.getGlobalVisibleRect(new android.graphics.Rect()));
      close.performClick();
      assertFalse(dialog.isShowing());
    } finally {
      dialog.dismiss();
    }
  }

  @Test
  @Config(application = UiApplication.class)
  public void virtualScreenControlsCanRenderWithoutStartingADeviceSession() throws Exception {
    // 只调用创建阶段，不进入 onResume，不订阅设备帧或点击控制按钮。
    ActivityController<com.deepseekharness.app.vscreen.VirtualScreenActivity> screen =
        Robolectric.buildActivity(com.deepseekharness.app.vscreen.VirtualScreenActivity.class)
            .create();
    try {
      android.view.ViewGroup content = screen.get().findViewById(android.R.id.content);
      android.view.View page = content.getChildAt(0);
      content.removeView(page);
      show(page);
      assertTrue(page.getHeight() > 0);
      screenshot("virtual-screen-controls", page);
    } finally {
      screen.destroy();
    }
  }

  private void screenshot(String name, android.view.View view) throws Exception {
    compose.waitForIdle();
    android.graphics.Bitmap image =
        android.graphics.Bitmap.createBitmap(
            view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
    view.draw(new android.graphics.Canvas(image));
    if (name.equals("settings-dark")) {
      int readablePixels = 0;
      for (int y = 20; y < 60; y++)
        for (int x = 20; x < 140; x++)
          if (android.graphics.Color.red(image.getPixel(x, y)) > 150) readablePixels++;
      assertTrue("深色页标题必须有可读的前景色", readablePixels > 30);
    }
    java.io.File directory = new java.io.File("build/miuix-preview");
    assertTrue(directory.isDirectory() || directory.mkdirs());
    try (java.io.FileOutputStream output =
        new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
      assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
    } finally {
      image.recycle();
    }
  }
}
