package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.TerminalSessionOwner;
import com.deepseekharness.app.util.UiText;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** 实际Activity视图/Back/重建；fake只替代终端输入面，不运行真实guest。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = TerminalNavigationTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalActivityLifecycleTest {
  public static class Host extends TerminalActivity {
    @Override
    protected Fragment createPane(boolean pty, boolean skipInitial) {
      var pane = new FakePane();
      Bundle args = new Bundle();
      args.putBoolean("pty", pty);
      args.putBoolean("terminal_skip_initial_create", skipInitial);
      pane.setArguments(args);
      return pane;
    }
  }

  public static class FakePane extends Fragment implements TerminalPane {
    static int creates, closes, input, focuses;
    boolean selection;

    public FakePane() {
      super(android.R.layout.simple_list_item_1);
    }

    public void newSession() {
      creates++;
    }

    public boolean selectSession(long id) {
      return true;
    }

    public void closeSession(long id) {
      closes++;
    }

    public void changeFont(int value) {}

    public void showKeyboard() {}

    public void paste() {
      input++;
    }

    public void restoreInputFocus() {
      focuses++;
    }

    public boolean selectionBack() {
      boolean before = selection;
      selection = false;
      return before;
    }

    public boolean isPty() {
      return getArguments().getBoolean("pty");
    }
  }

  @Before
  public void prepare() {
    new ConfigStore(RuntimeEnvironment.getApplication()).setWelcomed(true);
    RuntimeEnvironment.getApplication()
        .getSharedPreferences("deepseekharness", 0)
        .edit()
        .putBoolean("terminal_workbench", false)
        .apply();
    FakePane.creates = FakePane.closes = FakePane.input = FakePane.focuses = 0;
  }

  @Test
  public void minimalHostKeepsTerminalAndPageBackDoesNotCloseOrWrite() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    assertEquals(
        View.GONE, host.get().findViewById(R.id.terminal_workspace_toolbar).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(View.VISIBLE, host.get().findViewById(R.id.terminal_menu_ball).getVisibility());
    host.get().getOnBackPressedDispatcher().onBackPressed();
    assertTrue(host.get().isFinishing());
    host.pause().stop().destroy();
    assertEquals(0, FakePane.closes);
    assertEquals(0, FakePane.input);
    assertEquals(0, FakePane.creates);
  }

  @Test
  public void floatingBallOpensSessionsDrawerAndBackClosesItFirst() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    measure(host.get(), host.get().findViewById(R.id.terminal_workspace), 400, 900);
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_scrim).getVisibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        host.get().findViewById(R.id.terminal_content).getImportantForAccessibility());
    assertEquals(
        ViewGroup.FOCUS_BLOCK_DESCENDANTS,
        ((ViewGroup) host.get().findViewById(R.id.terminal_content)).getDescendantFocusability());
    assertFalse(host.get().isFinishing());
    host.get().findViewById(R.id.terminal_sessions_close).performClick();
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
        host.get().findViewById(R.id.terminal_content).getImportantForAccessibility());
    assertEquals(
        ViewGroup.FOCUS_BEFORE_DESCENDANTS,
        ((ViewGroup) host.get().findViewById(R.id.terminal_content)).getDescendantFocusability());
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    host.get().getOnBackPressedDispatcher().onBackPressed();
    assertFalse(host.get().isFinishing());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    host.get().getOnBackPressedDispatcher().onBackPressed();
    assertTrue(host.get().isFinishing());
    host.pause().stop().destroy();
    assertEquals(0, FakePane.closes);
    assertEquals(0, FakePane.input);
    assertEquals(0, FakePane.creates);
  }

  @Test
  @Config(qualifiers = "w400dp-h900dp")
  public void touchModeDrawerTakesFocusFromTheTerminalWithoutChangingButtonTouchBehavior() {
    var instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
    instrumentation.setInTouchMode(true);
    var host = Robolectric.buildActivity(Host.class).create().start().resume().visible();
    try {
      setTouchMode(host.get(), true);
      var workspace = host.get().findViewById(R.id.terminal_workspace);
      settleLayout(host.get(), workspace, 400, 900);
      View input = focusablePaneView(host.get());
      assertTrue(input.isInTouchMode());
      assertTrue(input.requestFocus());
      host.get().openSessions();
      settleLayout(host.get(), workspace, 400, 900);
      ViewGroup sidebar = host.get().findViewById(R.id.terminal_sessions_panel);
      assertFalse(input.hasFocus());
      assertFalse(input.requestFocus());
      assertSame(sidebar, sidebar.findFocus());
      assertFalse(host.get().findViewById(R.id.terminal_sessions_close).isFocusableInTouchMode());
      assertFalse(host.get().findViewById(R.id.terminal_sessions_create).isFocusableInTouchMode());
      assertFocusVisibleWithin((ViewGroup) workspace, sidebar);
      host.get().findViewById(R.id.terminal_sessions_close).performClick();
      assertTrue(input.requestFocus());
      assertEquals(0, FakePane.creates + FakePane.closes + FakePane.input);
    } finally {
      host.pause().stop().destroy();
      instrumentation.setInTouchMode(true);
    }
  }

  @Test
  public void inlineRefreshKeepsInputFocusButNarrowingToADrawerReclaimsIt() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume().visible();
    try {
      setTouchMode(host.get(), true);
      var workspace = host.get().findViewById(R.id.terminal_workspace);
      settleLayout(host.get(), workspace, 900, 700);
      host.get().openSessions();
      settleLayout(host.get(), workspace, 900, 700);
      View input = focusablePaneView(host.get());
      assertTrue(input.requestFocus());
      host.get().onTerminalPaneChanged();
      settleLayout(host.get(), workspace, 900, 700);
      assertTrue(input.hasFocus());
      assertFocusVisibleWithin((ViewGroup) workspace, input);

      settleLayout(host.get(), workspace, 400, 700);
      assertFalse(input.hasFocus());
      assertFalse(input.requestFocus());
      ViewGroup sidebar = host.get().findViewById(R.id.terminal_sessions_panel);
      View focused = sidebar.findFocus();
      assertNotNull(focused);
      assertFocusVisibleWithin((ViewGroup) workspace, focused);
      assertEquals(0, FakePane.creates + FakePane.closes + FakePane.input);
    } finally {
      host.pause().stop().destroy();
    }
  }

  @Test
  public void workbenchMenuOpensDrawerBelowExpandedBreakpoint() {
    RuntimeEnvironment.getApplication()
        .getSharedPreferences("deepseekharness", 0)
        .edit()
        .putBoolean("terminal_workbench", true)
        .apply();
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    float density = host.get().getResources().getDisplayMetrics().density;
    int width = Math.round(400 * density), height = Math.round(1200 * density);
    workspace.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    workspace.layout(0, 0, width, height);
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_workspace_toolbar).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_menu_ball).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_workspace_menu).getVisibility());
    host.get().findViewById(R.id.terminal_workspace_menu).performClick();
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void expandedWorkbenchUsesInlineSidebarAndNoDrawer() {
    RuntimeEnvironment.getApplication()
        .getSharedPreferences("deepseekharness", 0)
        .edit()
        .putBoolean("terminal_workbench", true)
        .apply();
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    float density = host.get().getResources().getDisplayMetrics().density;
    int width = Math.round(900 * density), height = Math.round(1200 * density);
    workspace.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    workspace.layout(0, 0, width, height);
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_menu_ball).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_workspace_menu).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void openDrawerStateSurvivesSameProcessRecreateWithoutCreatingSessions() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    var next = Robolectric.buildActivity(Host.class).create(saved).start().resume();
    assertEquals(
        View.VISIBLE, next.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(0, FakePane.creates);
    assertEquals(0, FakePane.closes);
    assertEquals(0, FakePane.input);
    next.pause().stop().destroy();
  }

  @Test
  public void selectionBackAndImeBackEachConsumeOnlyOneLayer() throws Exception {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var pane =
        (FakePane) host.get().getSupportFragmentManager().findFragmentById(R.id.terminal_content);
    pane.selection = true;
    host.get().getOnBackPressedDispatcher().onBackPressed();
    assertFalse(host.get().isFinishing());
    assertFalse(pane.selection);
    var ime = TerminalActivity.class.getDeclaredField("imeVisible");
    ime.setAccessible(true);
    ime.setBoolean(host.get(), true);
    host.get().getOnBackPressedDispatcher().onBackPressed();
    assertFalse(host.get().isFinishing());
    assertEquals(0, FakePane.input);
    ime.setBoolean(host.get(), false);
    host.pause().stop().destroy();
  }

  @Test
  public void sameProcessRecreateKeepsOwnerAndDoesNotCreateCommands() {
    var owner = TerminalSessionOwner.shared();
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    var next = Robolectric.buildActivity(Host.class).create(saved).start().resume();
    assertSame(owner, TerminalSessionOwner.shared());
    assertEquals(0, FakePane.creates);
    assertEquals(0, FakePane.closes);
    assertEquals(0, FakePane.input);
    next.pause().stop().destroy();
  }

  @Test
  public void minimalTabletWideOpensInlineSidebarAndKeepsTerminalAccessible() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    measure(host.get(), workspace, 900, 700);
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_close).getVisibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
        host.get().findViewById(R.id.terminal_content).getImportantForAccessibility());
    assertFalse(host.get().isFinishing());
    host.get().getOnBackPressedDispatcher().onBackPressed();
    assertFalse(host.get().isFinishing());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    host.get().findViewById(R.id.terminal_sessions_close).performClick();
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void minimalRtlInlineHidesBallInsteadOfOverlappingPanel() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    workspace.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    measure(host.get(), workspace, 900, 700);
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_menu_ball).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_close).getVisibility());
    host.get().findViewById(R.id.terminal_sessions_close).performClick();
    assertEquals(View.VISIBLE, host.get().findViewById(R.id.terminal_menu_ball).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void minimalTabletPortraitUsesModalDrawer() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    measure(host.get(), workspace, 640, 900);
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        host.get().findViewById(R.id.terminal_content).getImportantForAccessibility());
    assertEquals(
        View.GONE, host.get().findViewById(R.id.terminal_sessions_close_all).getVisibility());
    host.get().findViewById(R.id.terminal_sessions_close).performClick();
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void restoredSidebarSwitchesBetweenDrawerAndInlineWithoutCreatingSessions() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    measure(host.get(), workspace, 640, 900);
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    var next = Robolectric.buildActivity(Host.class).create(saved).start().resume();
    var nextWorkspace = next.get().findViewById(R.id.terminal_workspace);
    measure(next.get(), nextWorkspace, 900, 700);
    assertEquals(
        View.VISIBLE, next.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(View.GONE, next.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
        next.get().findViewById(R.id.terminal_content).getImportantForAccessibility());
    measure(next.get(), nextWorkspace, 640, 900);
    assertEquals(
        View.VISIBLE, next.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        next.get().findViewById(R.id.terminal_content).getImportantForAccessibility());
    assertEquals(0, FakePane.creates);
    assertEquals(0, FakePane.closes);
    assertEquals(0, FakePane.input);
    next.pause().stop().destroy();
  }

  @Test
  public void workbenchExpandedAddsSettingsAndHelpToTheTopbar() {
    RuntimeEnvironment.getApplication()
        .getSharedPreferences("deepseekharness", 0)
        .edit()
        .putBoolean("terminal_workbench", true)
        .apply();
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    measure(host.get(), workspace, 900, 700);
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_workspace_settings).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_workspace_help).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_workspace_menu).getVisibility());
    measure(host.get(), workspace, 400, 700);
    assertEquals(
        View.GONE, host.get().findViewById(R.id.terminal_workspace_settings).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_workspace_help).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_workspace_menu).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void shortWindowsHideSecondaryChromeAndUseDenseFooterActions() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    // 640dp 窗口的平板抽屉保持 336dp，仅由高度触发短窗形态，与窄抽屉分支解耦。
    measure(host.get(), workspace, 640, 470);
    // 侧栏不再有副标题、会话统计与底部说明行，短窗只切换动作组。
    assertEquals(
        View.VISIBLE,
        host.get().findViewById(R.id.terminal_sessions_actions_dense).getVisibility());
    assertEquals(
        View.GONE, host.get().findViewById(R.id.terminal_sessions_actions).getVisibility());
    measure(host.get(), workspace, 640, 700);
    assertEquals(
        View.GONE, host.get().findViewById(R.id.terminal_sessions_actions_dense).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_actions).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void narrowDrawerUsesSingleLineDenseActionsAndTwelveDpGridThenRestoresWideLayout() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    float density = density(host.get());
    // 400dp 窗口的手机抽屉实宽 224dp（< 240dp）：即使高度充足也走 dense 短标签。
    measure(host.get(), workspace, 400, 900);
    host.get().openSessions();
    settleLayout(host.get(), workspace, 400, 900);

    ViewGroup wide = host.get().findViewById(R.id.terminal_sessions_actions);
    ViewGroup dense = host.get().findViewById(R.id.terminal_sessions_actions_dense);
    assertEquals(View.GONE, wide.getVisibility());
    assertEquals(View.VISIBLE, dense.getVisibility());
    // 按使用频率排列：键盘、粘贴、回到输入在前，显示、主界面、说明在后。
    String[][] labels = {
      {"键盘", "Keyboard"}, {"粘贴", "Paste"},
      {"聚焦输入", "Focus"}, {"显示", "Display"},
      {"主界面", "Home"}, {"说明", "Help"}
    };
    String[][] descriptions = {
      {"显示键盘", "Keyboard"}, {"粘贴文本", "Paste"},
      {"回到输入", "Focus input"}, {"显示设置", "Display"},
      {"返回主界面", "Home"}, {"说明", "Help"}
    };
    assertEquals(3, dense.getChildCount());
    int action = 0;
    for (int row = 0; row < dense.getChildCount(); row++) {
      ViewGroup line = (ViewGroup) dense.getChildAt(row);
      assertEquals(2, line.getChildCount());
      for (int column = 0; column < line.getChildCount(); column++) {
        TextView button = (TextView) line.getChildAt(column);
        assertEquals(
            UiText.choose(labels[action][0], labels[action][1]), button.getText().toString());
        assertEquals(
            UiText.choose(descriptions[action][0], descriptions[action][1]),
            button.getContentDescription().toString());
        assertEquals(1, button.getMaxLines());
        assertFalse(button.getText().toString().contains("\n"));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
        int gap = Math.round(3 * density);
        assertEquals(Math.round(48 * density), params.height);
        assertEquals(Math.round(48 * density), button.getHeight());
        // 首/末按钮外缘 margin 0，相邻按钮各留 3dp，上下各 3dp。
        assertEquals(column == 0 ? 0 : gap, params.getMarginStart());
        assertEquals(column == line.getChildCount() - 1 ? 0 : gap, params.getMarginEnd());
        assertEquals(gap, params.topMargin);
        assertEquals(gap, params.bottomMargin);
        assertEquals(Math.round(3 * density), button.getPaddingLeft());
        assertEquals(Math.round(3 * density), button.getPaddingRight());
        action++;
      }
    }
    assertEquals(6, action);

    // 标题栏只剩标识、“会话”、结束全部与关闭；窄抽屉两侧贴齐 12dp 网格。
    ViewGroup header =
        (ViewGroup) host.get().findViewById(R.id.terminal_sessions_close).getParent();
    assertEquals(Math.round(12 * density), header.getPaddingLeft());
    assertEquals(Math.round(12 * density), header.getPaddingRight());
    assertSame(header, host.get().findViewById(R.id.terminal_sessions_close_all).getParent());
    ViewGroup footer = (ViewGroup) dense.getParent();
    assertEquals(Math.round(12 * density), footer.getPaddingLeft());
    assertEquals(Math.round(12 * density), footer.getPaddingRight());
    ViewGroup scroll = host.get().findViewById(R.id.terminal_sessions_scroll);
    ViewGroup list = (ViewGroup) scroll.getChildAt(0);
    assertEquals(Math.round(12 * density), list.getPaddingLeft());
    assertEquals(Math.round(12 * density), list.getPaddingRight());

    // 宽度回到 336dp 平板抽屉：恢复全标签与宽侧栏边距，短标签组重新隐藏。
    measure(host.get(), workspace, 640, 900);
    settleLayout(host.get(), workspace, 640, 900);
    assertEquals(View.VISIBLE, wide.getVisibility());
    assertEquals(View.GONE, dense.getVisibility());
    assertEquals(Math.round(16 * density), header.getPaddingLeft());
    assertEquals(Math.round(6 * density), header.getPaddingRight());
    assertEquals(Math.round(12 * density), footer.getPaddingLeft());
    assertEquals(Math.round(10 * density), list.getPaddingLeft());
    TextView denseFocus = host.get().findViewById(R.id.terminal_sessions_action_focus_dense);
    assertEquals(2, denseFocus.getMaxLines());
    assertEquals(
        UiText.choose("返回主界面", "Home"),
        ((TextView) host.get().findViewById(R.id.terminal_sessions_action_home))
            .getText()
            .toString());
    host.pause().stop().destroy();
  }

  @Test
  public void focusActionClosesSidebarAndRestoresInputOnFirstClick() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    measure(host.get(), host.get().findViewById(R.id.terminal_workspace), 400, 900);
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    // 224dp 窄抽屉只显示 dense 短标签组，“聚焦输入”在 dense 组里。
    View focus = host.get().findViewById(R.id.terminal_sessions_action_focus_dense);
    assertNotNull(focus);
    assertEquals(View.VISIBLE, focus.getVisibility());
    assertFalse(focus.isFocusableInTouchMode());
    focus.performClick();
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    org.robolectric.shadows.ShadowLooper.idleMainLooper();
    assertEquals(1, FakePane.focuses);
    host.pause().stop().destroy();
  }

  @Test
  public void sidebarShowsOneMixedListAndHeaderStopIconOnlyWithSessions() throws Exception {
    var owner = TerminalSessionOwner.shared();
    var opened = new java.util.concurrent.CountDownLatch(1);
    var tab = startFakeSimple(owner, opened, new java.util.concurrent.CountDownLatch(0));
    assertTrue(opened.await(2, java.util.concurrent.TimeUnit.SECONDS));
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    try {
      measure(host.get(), host.get().findViewById(R.id.terminal_workspace), 640, 900);
      host.get().findViewById(R.id.terminal_menu_ball).performClick();
      View panel = host.get().findViewById(R.id.terminal_sessions_panel);
      // 不再有分组标题与会话统计；会话直接出现在唯一的列表里，状态在整行描述中。
      assertFalse(containsText(panel, UiText.choose("PTY · 交互会话", "PTY · interactive sessions")));
      assertFalse(containsText(panel, UiText.choose("简易 · 按行命令", "Simple · line commands")));
      assertTrue(
          containsText(panel, UiText.format(UiText.choose("简易 %d", "Simple %d"), tab.number)));
      assertFalse(containsText(panel, UiText.choose("暂无会话", "No sessions")));
      View stop = host.get().findViewById(R.id.terminal_sessions_close_all);
      assertEquals(View.VISIBLE, stop.getVisibility());
      assertEquals(
          UiText.choose("结束全部终端会话并核验", "End all terminal sessions and verify"),
          stop.getContentDescription().toString());
    } finally {
      host.pause().stop().destroy();
      owner.closeSimple(tab.id, 2000);
    }
  }

  @Test
  public void createAndHelpDialogsUseCardsAndKeepAllChoices() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    try {
      measure(host.get(), host.get().findViewById(R.id.terminal_workspace), 640, 900);
      host.get().findViewById(R.id.terminal_menu_ball).performClick();
      host.get().findViewById(R.id.terminal_sessions_create).performClick();
      var create =
          (androidx.appcompat.app.AlertDialog)
              org.robolectric.shadows.ShadowDialog.getLatestDialog();
      assertTrue(create.isShowing());
      for (int id :
          new int[] {
            R.id.terminal_create_pty, R.id.terminal_create_simple, R.id.terminal_create_cli
          }) {
        View option = create.findViewById(id);
        assertNotNull(option);
        assertTrue(option.isClickable());
        assertTrue(option.getMinimumHeight() >= Math.round(48 * density(host.get())));
        assertFalse(option.getContentDescription().toString().isEmpty());
      }
      // 卡片点击即新建：对话框关闭并走原 createInMode 路径（测试宿主中由 FakePane 计数）。
      create.findViewById(R.id.terminal_create_simple).performClick();
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      assertFalse(create.isShowing());

      host.get().openSessions();
      View help =
          host.get().findViewById(R.id.terminal_sessions_action_help).getVisibility()
                  == View.VISIBLE
              ? host.get().findViewById(R.id.terminal_sessions_action_help)
              : host.get().findViewById(R.id.terminal_sessions_action_help_dense);
      help.performClick();
      var dialog =
          (androidx.appcompat.app.AlertDialog)
              org.robolectric.shadows.ShadowDialog.getLatestDialog();
      assertTrue(dialog.isShowing());
      View content = dialog.getWindow().getDecorView();
      assertTrue(containsText(content, UiText.choose("返回不结束进程", "Back keeps processes")));
      assertTrue(containsText(content, UiText.choose("结束需确认", "Ending needs confirmation")));
      assertTrue(containsText(content, UiText.choose("运行中", "Running")));
      assertTrue(containsText(content, UiText.choose("已退出", "Exited")));
      dialog.dismiss();
    } finally {
      host.pause().stop().destroy();
    }
  }

  @Test
  public void backgroundSimpleStateRefreshesSidebarWhilePtyPaneIsVisible() throws Exception {
    var owner = TerminalSessionOwner.shared();
    var opened = new java.util.concurrent.CountDownLatch(1);
    var finish = new java.util.concurrent.CountDownLatch(1);
    var tab = startFakeSimple(owner, opened, finish);
    assertTrue(opened.await(2, java.util.concurrent.TimeUnit.SECONDS));
    RuntimeEnvironment.getApplication()
        .getSharedPreferences("deepseekharness", 0)
        .edit()
        .putBoolean(PtyTerminalFragment.KEY_PTY, true)
        .apply();
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var failed = new java.util.concurrent.CountDownLatch(1);
    Runnable observeFailure =
        () -> {
          if (tab.value.session.state()
              == com.deepseekharness.app.util.TerminalSession.State.FAILED) failed.countDown();
        };
    owner.addSimpleStateObserver(observeFailure);
    try {
      Fragment pane =
          host.get().getSupportFragmentManager().findFragmentById(R.id.terminal_content);
      assertTrue(((FakePane) pane).isPty());
      measure(host.get(), host.get().findViewById(R.id.terminal_workspace), 400, 900);
      host.get().findViewById(R.id.terminal_menu_ball).performClick();
      View sessions = host.get().findViewById(R.id.terminal_sessions_panel);
      assertTrue(
          containsDescription(
              sessions, com.deepseekharness.app.util.UiText.choose("启动中", "Starting")));
      finish.countDown();
      assertTrue(failed.await(2, java.util.concurrent.TimeUnit.SECONDS));
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      assertTrue(
          containsDescription(
              sessions,
              com.deepseekharness.app.util.UiText.choose("失败 · 请检查", "Failed · inspect session")));
    } finally {
      finish.countDown();
      owner.removeSimpleStateObserver(observeFailure);
      host.pause().stop().destroy();
      owner.closeSimple(tab.id, 2000);
    }
  }

  @Test
  public void endAllWaitsForCreationAndRechecksTheConfirmedSessionList() throws Exception {
    var owner = TerminalSessionOwner.shared();
    var opened = new java.util.concurrent.CountDownLatch(1);
    var tab = startFakeSimple(owner, opened, new java.util.concurrent.CountDownLatch(0));
    assertTrue(opened.await(2, java.util.concurrent.TimeUnit.SECONDS));
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    com.deepseekharness.app.core.TerminalRuntimeProtection.Ticket pending = null;
    com.deepseekharness.app.util.TerminalTabs.Tab<TerminalSessionOwner.SimpleTab> later = null;
    try {
      measure(host.get(), host.get().findViewById(R.id.terminal_workspace), 400, 900);
      host.get().findViewById(R.id.terminal_menu_ball).performClick();
      View button = host.get().findViewById(R.id.terminal_sessions_close_all);
      assertTrue(button.isEnabled());

      pending = prepareUnstartedPty(owner);
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      assertFalse(button.isEnabled());
      assertTrue(
          containsText(
              host.get().findViewById(R.id.terminal_sessions_panel),
              com.deepseekharness.app.util.UiText.choose(
                  "终端会话正在创建，请稍后再结束全部。",
                  "A terminal session is being created. Wait before ending all sessions.")));
      enterUnpublishedPtyCreation(owner, pending);
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      assertEquals(1, owner.protectionSnapshot().creating);
      assertFalse(button.isEnabled());
      finishUnpublishedPtyCreation(owner, pending);
      pending = null;
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      assertTrue(button.isEnabled());

      button.performClick();
      var dialog =
          (androidx.appcompat.app.AlertDialog)
              org.robolectric.shadows.ShadowDialog.getLatestDialog();
      assertTrue(dialog.isShowing());
      // A request accepted while the confirmation was open must not be left out.
      pending = prepareUnstartedPty(owner);
      enterUnpublishedPtyCreation(owner, pending);
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      assertNotNull(owner.simpleTabs().find(tab.id));
      assertFalse(tab.isClosing());
      assertEquals(1, owner.protectionSnapshot().creating);
      finishUnpublishedPtyCreation(owner, pending);
      pending = null;
      org.robolectric.shadows.ShadowLooper.idleMainLooper();

      button.performClick();
      dialog =
          (androidx.appcompat.app.AlertDialog)
              org.robolectric.shadows.ShadowDialog.getLatestDialog();
      assertTrue(dialog.isShowing());
      var laterOpened = new java.util.concurrent.CountDownLatch(1);
      later = startFakeSimple(owner, laterOpened, new java.util.concurrent.CountDownLatch(0));
      assertTrue(laterOpened.await(2, java.util.concurrent.TimeUnit.SECONDS));
      dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      assertNotNull(owner.simpleTabs().find(tab.id));
      assertNotNull(owner.simpleTabs().find(later.id));
      assertFalse(tab.isClosing());
      assertFalse(later.isClosing());
      assertEquals(
          com.deepseekharness.app.util.UiText.choose(
              "会话列表已改变，请重新确认结束全部。",
              "The session list changed. Confirm again before ending all sessions."),
          org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    } finally {
      if (pending != null && !pending.cancel()) finishUnpublishedPtyCreation(owner, pending);
      host.pause().stop().destroy();
      owner.closeSimple(tab.id, 2000);
      if (later != null) owner.closeSimple(later.id, 2000);
    }
  }

  private static com.deepseekharness.app.core.TerminalRuntimeProtection.Ticket prepareUnstartedPty(
      TerminalSessionOwner owner) {
    return owner
        .protection()
        .prepare(
            owner.epoch(),
            com.deepseekharness.app.core.TerminalRuntimeProtection.Kind.PTY,
            new com.deepseekharness.app.core.TerminalRuntimeProtection.Admission() {
              @Override
              public void onReady(
                  com.deepseekharness.app.core.TerminalRuntimeProtection.Ticket ticket) {}

              @Override
              public void onRejected(
                  com.deepseekharness.app.core.TerminalRuntimeProtection.Ticket ticket,
                  String reason) {}
            });
  }

  private static void enterUnpublishedPtyCreation(
      TerminalSessionOwner owner,
      com.deepseekharness.app.core.TerminalRuntimeProtection.Ticket ticket)
      throws Exception {
    assertTrue(owner.protection().promoted(ticket.id(), new Object(), () -> true));
    owner
        .protection()
        .beginCreate(
            ticket, owner.epoch(), com.deepseekharness.app.core.TerminalRuntimeProtection.Kind.PTY);
  }

  private static void finishUnpublishedPtyCreation(
      TerminalSessionOwner owner,
      com.deepseekharness.app.core.TerminalRuntimeProtection.Ticket ticket)
      throws Exception {
    var finish =
        com.deepseekharness.app.core.TerminalRuntimeProtection.class.getDeclaredMethod(
            "finishCreate", com.deepseekharness.app.core.TerminalRuntimeProtection.Ticket.class);
    finish.setAccessible(true);
    finish.invoke(owner.protection(), ticket);
  }

  private static com.deepseekharness.app.util.TerminalTabs.Tab<TerminalSessionOwner.SimpleTab>
      startFakeSimple(
          TerminalSessionOwner owner,
          java.util.concurrent.CountDownLatch opened,
          java.util.concurrent.CountDownLatch finish)
          throws Exception {
    var protection = owner.protection();
    var ticket =
        protection.prepare(
            owner.epoch(),
            com.deepseekharness.app.core.TerminalRuntimeProtection.Kind.SIMPLE,
            new com.deepseekharness.app.core.TerminalRuntimeProtection.Admission() {
              @Override
              public void onReady(
                  com.deepseekharness.app.core.TerminalRuntimeProtection.Ticket t) {}

              @Override
              public void onRejected(
                  com.deepseekharness.app.core.TerminalRuntimeProtection.Ticket t, String reason) {}
            });
    assertTrue(protection.promoted(ticket.id(), new Object(), () -> true));
    return owner.createSimple(
        ticket,
        new com.deepseekharness.app.util.TerminalSession.Backend() {
          @Override
          public Process open() throws Exception {
            opened.countDown();
            if (!finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
              throw new java.io.IOException("fixture timed out");
            throw new java.io.IOException("fixture did not start a process");
          }

          @Override
          public void terminate(Process process, long group) {}
        });
  }

  /** 会话状态已从可见胶囊移入整行无障碍描述；按描述片段查找。 */
  private static boolean containsDescription(View root, String fragment) {
    CharSequence description = root.getContentDescription();
    if (description != null && description.toString().contains(fragment)) return true;
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++)
        if (containsDescription(group.getChildAt(i), fragment)) return true;
    }
    return false;
  }

  private static boolean containsText(View root, String expected) {
    if (root instanceof android.widget.TextView
        && expected.contentEquals(((android.widget.TextView) root).getText())) return true;
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++)
        if (containsText(group.getChildAt(i), expected)) return true;
    }
    return false;
  }

  @Test
  public void sessionMenuControlsClickOnFirstTouchWithoutFocusDetour() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    measure(host.get(), host.get().findViewById(R.id.terminal_workspace), 400, 900);
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    View create = host.get().findViewById(R.id.terminal_sessions_create);
    assertNotNull(create);
    // 触摸模式下首击只聚焦、不点击：新建会话与结束会话的控件都不能带这个焦点。
    assertFalse(create.isFocusableInTouchMode());
    assertFalse(host.get().findViewById(R.id.terminal_sessions_close).isFocusableInTouchMode());
    host.pause().stop().destroy();
  }

  @Test
  public void staleProcessBundleDoesNotRecoverOldIdentityOrAutocreate() {
    Bundle saved = new Bundle();
    saved.putString("terminal_owner", "old-process");
    var host = Robolectric.buildActivity(Host.class).create(saved).start().resume();
    Fragment pane = host.get().getSupportFragmentManager().findFragmentById(R.id.terminal_content);
    assertTrue(pane.getArguments().getBoolean("terminal_skip_initial_create"));
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_workspace_status).getVisibility());
    assertEquals(0, FakePane.creates);
    assertEquals(0, FakePane.input);
    host.pause().stop().destroy();
  }

  @Test
  public void veryShortSidebarScrollsSessionsAndFooterTogetherAndRestoresFocus() {
    // The close button deliberately does not focus in touch mode so the first tap clicks it.
    // Exercise focus restoration using the keyboard/DPAD mode in which it can own focus.
    var instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
    instrumentation.setInTouchMode(false);
    org.robolectric.android.controller.ActivityController<Host> host = null;
    try {
      // Attach a real window: an unattached Robolectric view always reports the resource-default
      // touch mode and has no ViewRoot to carry keyboard focus.
      host = Robolectric.buildActivity(Host.class).create().start().resume().visible();
      var workspace = host.get().findViewById(R.id.terminal_workspace);
      measure(host.get(), workspace, 320, 900);
      host.get().openSessions();
      settleLayout(host.get(), workspace, 320, 900);
      android.widget.ScrollView scroll = host.get().findViewById(R.id.terminal_sessions_scroll);
      View create = host.get().findViewById(R.id.terminal_sessions_create);
      assertFalse(isDescendant(scroll, create));
      View close = host.get().findViewById(R.id.terminal_sessions_close);
      // Robolectric's Instrumentation updates its window-manager state but does not deliver the
      // corresponding change to this attached ViewRoot. Apply Android's local transition too.
      Object viewRoot =
          org.robolectric.util.ReflectionHelpers.callInstanceMethod(close, "getViewRootImpl");
      assertNotNull(viewRoot);
      org.robolectric.util.ReflectionHelpers.callInstanceMethod(
          viewRoot,
          "ensureTouchModeLocally",
          org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, false));
      assertFalse(close.isInTouchMode());
      assertTrue(close.isFocusable());
      assertFalse(close.isFocusableInTouchMode());
      assertTrue(close.requestFocus());
      assertTrue(close.hasFocus());
      settleLayout(host.get(), workspace, 320, 180);
      assertTrue(isDescendant(scroll, create));
      assertTrue(
          isDescendant(scroll, host.get().findViewById(R.id.terminal_sessions_actions_dense)));
      assertTrue(scroll.getHeight() >= Math.round(179 * density(host.get())));
      assertFocusVisibleWithin((ViewGroup) workspace, close);
      assertTrue(scroll.canScrollVertically(1));
      assertTrue(
          containsText(scroll, com.deepseekharness.app.util.UiText.choose("暂无会话", "No sessions")));
      scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
      assertTrue(scroll.getScrollY() > 0);
      View focus = host.get().findViewById(R.id.terminal_sessions_action_focus_dense);
      focus.performClick();
      assertEquals(1, FakePane.focuses);
      assertEquals(
          View.GONE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
      assertEquals(0, FakePane.creates + FakePane.closes + FakePane.input);
    } finally {
      try {
        if (host != null) host.pause().stop().destroy();
      } finally {
        instrumentation.setInTouchMode(true);
      }
    }
  }

  @Test
  public void resizingAndRefreshingKeepsTheFocusedBottomHelpFullyVisible() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume().visible();
    try {
      setTouchMode(host.get(), false);
      var workspace = host.get().findViewById(R.id.terminal_workspace);
      settleLayout(host.get(), workspace, 800, 500);
      host.get().openSessions();
      settleLayout(host.get(), workspace, 800, 500);
      android.widget.ScrollView scroll = host.get().findViewById(R.id.terminal_sessions_scroll);
      View help = host.get().findViewById(R.id.terminal_sessions_action_help_dense);
      assertFalse(isDescendant(scroll, help));
      assertTrue(help.requestFocus());
      assertFocusVisibleWithin((ViewGroup) workspace, help);

      settleLayout(host.get(), workspace, 800, 180);
      assertTrue(isDescendant(scroll, help));
      assertTrue(scroll.getScrollY() > 0);
      assertFocusVisibleWithin((ViewGroup) workspace, help);
      host.get().onTerminalPaneChanged();
      settleLayout(host.get(), workspace, 800, 180);
      assertFocusVisibleWithin((ViewGroup) workspace, help);

      settleLayout(host.get(), workspace, 800, 500);
      assertFalse(isDescendant(scroll, help));
      assertFocusVisibleWithin((ViewGroup) workspace, help);
      assertEquals(0, FakePane.creates + FakePane.closes + FakePane.input);
    } finally {
      try {
        host.pause().stop().destroy();
      } finally {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .setInTouchMode(true);
      }
    }
  }

  @Test
  public void refreshingSessionControlsPreservesTheirIdentityAndVisibleFocus() throws Exception {
    var owner = TerminalSessionOwner.shared();
    var opened = new java.util.concurrent.CountDownLatch(1);
    var tab = startFakeSimple(owner, opened, new java.util.concurrent.CountDownLatch(0));
    assertTrue(opened.await(2, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(owner.renameSimple(tab.id, "focus-fixture"));
    var host = Robolectric.buildActivity(Host.class).create().start().resume().visible();
    try {
      setTouchMode(host.get(), false);
      var workspace = host.get().findViewById(R.id.terminal_workspace);
      settleLayout(host.get(), workspace, 800, 180);
      host.get().openSessions();
      settleLayout(host.get(), workspace, 800, 180);
      String label =
          com.deepseekharness.app.util.UiText.choose("简易", "Simple") + " · focus-fixture";
      String[] descriptions = {
        com.deepseekharness.app.util.UiText.format(
            com.deepseekharness.app.util.UiText.choose("重命名 %s", "Rename %s"), label),
        com.deepseekharness.app.util.UiText.format(
            com.deepseekharness.app.util.UiText.choose("结束 %s 的进程", "End process for %s"), label)
      };
      View input = focusablePaneView(host.get());
      for (String description : descriptions) {
        View before = findByDescription(workspace, description);
        assertNotNull(before);
        assertTrue(before.requestFocus());
        host.get().onTerminalPaneChanged();
        settleLayout(host.get(), workspace, 800, 180);
        View after = findByDescription(workspace, description);
        assertNotNull(after);
        assertNotSame(before, after);
        assertFocusVisibleWithin((ViewGroup) workspace, after);
        assertFalse(input.hasFocus());
      }

      owner.closeSimple(tab.id, 2000);
      host.get().onTerminalPaneChanged();
      settleLayout(host.get(), workspace, 800, 180);
      View close = host.get().findViewById(R.id.terminal_sessions_close);
      assertFocusVisibleWithin((ViewGroup) workspace, close);
      assertFalse(input.hasFocus());
      assertEquals(0, FakePane.creates + FakePane.closes + FakePane.input);
    } finally {
      try {
        host.pause().stop().destroy();
        owner.closeSimple(tab.id, 2000);
      } finally {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .setInTouchMode(true);
      }
    }
  }

  @Test
  public void largeFontsUseOneColumnAndKeepFooterInsideTheShortViewportScroll() {
    RuntimeEnvironment.setFontScale(2f);
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    try {
      var workspace = host.get().findViewById(R.id.terminal_workspace);
      // 侧栏去掉统计与说明行后更矮；用 480dp 短窗保证 2 倍字体下内容仍溢出、需要整栏滚动。
      measure(host.get(), workspace, 320, 480);
      host.get().openSessions();
      settleLayout(host.get(), workspace, 320, 480);
      android.widget.ScrollView scroll = host.get().findViewById(R.id.terminal_sessions_scroll);
      ViewGroup actions = host.get().findViewById(R.id.terminal_sessions_actions_dense);
      assertTrue(isDescendant(scroll, actions));
      // 窄抽屉在 2 倍字体下仍保持单行、固定 48dp 等高；宽度不足时按策略降到 1 列 6 行。
      assertEquals(6, actions.getChildCount());
      for (int i = 0; i < actions.getChildCount(); i++) {
        ViewGroup row = (ViewGroup) actions.getChildAt(i);
        assertEquals(1, row.getChildCount());
        android.widget.TextView action = (android.widget.TextView) row.getChildAt(0);
        assertEquals(Math.round(48 * density(host.get())), action.getHeight());
        assertEquals(1, action.getMaxLines());
        assertNotNull(action.getContentDescription());
      }
      assertTrue(scroll.getHeight() > 0);
      assertTrue(scroll.canScrollVertically(1));
    } finally {
      host.pause().stop().destroy();
      RuntimeEnvironment.setFontScale(1f);
    }
  }

  @Test
  public void imePaddingAndRtlKeepTheSidebarReachableAndReturnToPinnedLayout() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    try {
      var workspace = host.get().findViewById(R.id.terminal_workspace);
      workspace.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
      measure(host.get(), workspace, 400, 900);
      host.get().openSessions();
      settleLayout(host.get(), workspace, 400, 900);
      android.widget.ScrollView scroll = host.get().findViewById(R.id.terminal_sessions_scroll);
      View create = host.get().findViewById(R.id.terminal_sessions_create);
      assertFalse(isDescendant(scroll, create));
      workspace.setPadding(0, 0, 0, Math.round(720 * density(host.get())));
      settleLayout(host.get(), workspace, 400, 900);
      assertTrue(isDescendant(scroll, create));
      View sidebar = host.get().findViewById(R.id.terminal_sessions_panel);
      assertEquals(View.LAYOUT_DIRECTION_RTL, sidebar.getLayoutDirection());
      assertEquals(workspace.getWidth(), sidebar.getRight());
      assertTrue(scroll.getHeight() > 0);
      assertTrue(scroll.getHeight() <= Math.round(180 * density(host.get())));
      workspace.setPadding(0, 0, 0, 0);
      settleLayout(host.get(), workspace, 400, 900);
      assertFalse(isDescendant(scroll, create));
      host.get().findViewById(R.id.terminal_sessions_close).performClick();
      assertTrue(host.get().findViewById(R.id.terminal_menu_ball).hasFocus());
      assertEquals(0, FakePane.creates + FakePane.closes + FakePane.input);
    } finally {
      host.pause().stop().destroy();
    }
  }

  @Test
  public void packageMenuBridgesOpenSessionsAndDisplaySettings() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    try {
      host.get().openSessions();
      assertEquals(
          View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
      host.get().showDisplaySettings();
      var dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
      assertNotNull(dialog);
      assertTrue(dialog.isShowing());
      assertEquals(0, FakePane.creates + FakePane.closes + FakePane.input);
    } finally {
      host.pause().stop().destroy();
    }
  }

  private static void setTouchMode(Host host, boolean touchMode) {
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        .setInTouchMode(touchMode);
    // 与主分支的关闭按钮夹具一致：Robolectric 需将模式同步给已附着的 ViewRoot。
    View decor = host.getWindow().getDecorView();
    Object viewRoot =
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(decor, "getViewRootImpl");
    assertNotNull(viewRoot);
    org.robolectric.util.ReflectionHelpers.callInstanceMethod(
        viewRoot,
        "ensureTouchModeLocally",
        org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, touchMode));
    assertEquals(touchMode, decor.isInTouchMode());
  }

  private static View focusablePaneView(Host host) {
    View input =
        host.getSupportFragmentManager().findFragmentById(R.id.terminal_content).requireView();
    input.setFocusableInTouchMode(true);
    return input;
  }

  private static View findByDescription(View root, String description) {
    CharSequence actual = root.getContentDescription();
    if (actual != null && description.contentEquals(actual)) return root;
    if (root instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        View match = findByDescription(group.getChildAt(i), description);
        if (match != null) return match;
      }
    }
    return null;
  }

  private static void assertFocusVisibleWithin(ViewGroup workspace, View focused) {
    assertTrue("控件应保留输入焦点", focused.hasFocus());
    assertTrue("控件必须有可见尺寸", focused.getWidth() > 0 && focused.getHeight() > 0);
    assertEquals(View.VISIBLE, focused.getVisibility());
    // 不依赖宿主 DecorView 的模拟屏幕大小；逐层检查实际布局、padding 与 ScrollView 裁剪。
    for (android.view.ViewParent parent = focused.getParent();
        parent instanceof ViewGroup group;
        parent = group.getParent()) {
      assertEquals(View.VISIBLE, group.getVisibility());
      Rect bounds = new Rect(0, 0, focused.getWidth(), focused.getHeight());
      group.offsetDescendantRectToMyCoords(focused, bounds);
      Rect viewport =
          new Rect(
              group.getScrollX() + group.getPaddingLeft(),
              group.getScrollY() + group.getPaddingTop(),
              group.getScrollX() + group.getWidth() - group.getPaddingRight(),
              group.getScrollY() + group.getHeight() - group.getPaddingBottom());
      assertTrue("焦点 " + bounds + " 必须完整位于 " + viewport, viewport.contains(bounds));
      if (group == workspace) return;
    }
    fail("焦点控件已离开当前工作区");
  }

  private static float density(android.app.Activity activity) {
    return activity.getResources().getDisplayMetrics().density;
  }

  private static boolean isDescendant(ViewGroup ancestor, View child) {
    for (android.view.ViewParent parent = child.getParent();
        parent != null;
        parent = parent.getParent()) {
      if (parent == ancestor) return true;
    }
    return false;
  }

  private static void settleLayout(
      android.app.Activity activity, View workspace, int widthDp, int heightDp) {
    // 第一遍窗口回调更新抽屉/内联形态，后两遍验证实际子树测量结果已稳定。
    for (int pass = 0; pass < 3; pass++) {
      workspace.forceLayout();
      measure(activity, workspace, widthDp, heightDp);
    }
  }

  private static void measure(
      android.app.Activity activity, View workspace, int widthDp, int heightDp) {
    float density = activity.getResources().getDisplayMetrics().density;
    int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
    workspace.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    workspace.layout(0, 0, width, height);
  }
}
