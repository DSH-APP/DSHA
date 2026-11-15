package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatImageButton;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.UiText;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** 终端外壳三向态、图标化、主题开关与六个底部动作的宿主视图合同。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = TerminalNavigationTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalThemeTest {
  private static final int LIGHT_PANEL = 0xFFFFFFFF;
  private static final int LIGHT_PANEL_TEXT = 0xFF152033;
  private static final int LIGHT_PANEL_TEXT_STRONG = 0xFF0D1727;
  private static final int NIGHT_PANEL = 0xFF0B0F16;
  private static final int NIGHT_PANEL_TEXT = 0xFFE4E8F0;
  private static final int NIGHT_PANEL_TEXT_STRONG = 0xFFF2F5FA;

  @Before
  public void prepare() {
    UiText.setLanguage("zh");
    new ConfigStore(RuntimeEnvironment.getApplication()).setWelcomed(true);
    new ConfigStore(RuntimeEnvironment.getApplication()).setUiTheme("system");
    RuntimeEnvironment.getApplication()
        .getSharedPreferences("deepseekharness", 0)
        .edit()
        .putBoolean("terminal_workbench", false)
        .apply();
    TerminalActivityLifecycleTest.FakePane.creates = 0;
    TerminalActivityLifecycleTest.FakePane.closes = 0;
    TerminalActivityLifecycleTest.FakePane.input = 0;
  }

  @After
  public void reset() {
    new ConfigStore(RuntimeEnvironment.getApplication()).setUiTheme("system");
    UiText.setLanguage("zh");
  }

  @Test
  @Config(qualifiers = "notnight")
  public void lightShellUsesLightTokensAndKeepsOutputBlack() {
    setTheme("light");
    var controller = buildHost();
    TerminalActivity host = controller.get();
    measure(host, 400, 900);
    openDrawer(host);

    assertEquals(LIGHT_PANEL, colorOf(host.findViewById(R.id.terminal_sessions_panel)));
    assertEquals(LIGHT_PANEL, colorOf(host.findViewById(R.id.terminal_workspace_toolbar)));
    assertEquals(
        LIGHT_PANEL_TEXT_STRONG,
        ((TextView) host.findViewById(R.id.terminal_sessions_title)).getCurrentTextColor());
    assertEquals(Color.BLACK, colorOf(host.findViewById(R.id.terminal_workspace)));
    assertEquals(
        LIGHT_PANEL_TEXT,
        ((TextView) host.findViewById(R.id.terminal_menu_ball)).getCurrentTextColor());
    assertEquals(0, TerminalActivityLifecycleTest.FakePane.creates);
    assertEquals(0, TerminalActivityLifecycleTest.FakePane.closes);
    controller.pause().stop().destroy();
  }

  @Test
  @Config(qualifiers = "night")
  public void nightShellUsesDarkTokensAndKeepsOutputBlack() {
    setTheme("dark");
    var controller = buildHost();
    TerminalActivity host = controller.get();
    measure(host, 400, 900);
    openDrawer(host);

    assertEquals(NIGHT_PANEL, colorOf(host.findViewById(R.id.terminal_sessions_panel)));
    assertEquals(NIGHT_PANEL, colorOf(host.findViewById(R.id.terminal_workspace_toolbar)));
    assertEquals(
        NIGHT_PANEL_TEXT_STRONG,
        ((TextView) host.findViewById(R.id.terminal_sessions_title)).getCurrentTextColor());
    assertEquals(Color.BLACK, colorOf(host.findViewById(R.id.terminal_workspace)));
    assertEquals(
        NIGHT_PANEL_TEXT,
        ((TextView) host.findViewById(R.id.terminal_menu_ball)).getCurrentTextColor());
    assertNotEquals(0xFF0C100E, colorOf(host.findViewById(R.id.terminal_sessions_panel)));
    controller.pause().stop().destroy();
  }

  @Test
  @Config(qualifiers = "notnight")
  public void footerKeepsAllSixActionsInWideAndDenseLayouts() {
    setTheme("light");
    var controller = buildHost();
    TerminalActivity host = controller.get();
    // 640dp 窗口的平板抽屉为 336dp（≥240dp），高窗下才应显示全标签动作组。
    measure(host, 640, 900);
    openDrawer(host);

    assertEquals(View.VISIBLE, host.findViewById(R.id.terminal_sessions_actions).getVisibility());
    assertEquals(
        View.GONE, host.findViewById(R.id.terminal_sessions_actions_dense).getVisibility());
    assertFooterActions(host, false);
    assertNotNull(host.findViewById(R.id.terminal_sessions_close_all));
    // 主题切换从终端侧栏移出：侧栏只保留会话与输入相关动作。
    assertNull(host.findViewById(R.id.terminal_sessions_theme));

    measure(host, 640, 470);
    assertEquals(View.GONE, host.findViewById(R.id.terminal_sessions_actions).getVisibility());
    assertEquals(
        View.VISIBLE, host.findViewById(R.id.terminal_sessions_actions_dense).getVisibility());
    assertFooterActions(host, true);
    assertEquals(0, TerminalActivityLifecycleTest.FakePane.creates);
    controller.pause().stop().destroy();
  }

  @Test
  @Config(qualifiers = "notnight")
  public void tabletLandscapeUsesColumnsThatFitItsSidebarWidth() {
    setTheme("light");
    var controller = buildHost();
    TerminalActivity host = controller.get();
    measure(host, 900, 700);
    openDrawer(host);

    assertEquals(View.GONE, host.findViewById(R.id.terminal_sessions_actions).getVisibility());
    assertEquals(
        View.VISIBLE, host.findViewById(R.id.terminal_sessions_actions_dense).getVisibility());
    assertFooterActions(host, true);
    TextView home = host.findViewById(R.id.terminal_sessions_action_home_dense);
    assertEquals(Math.round(48 * density(host)), home.getMinHeight());
    assertEquals(2, home.getMaxLines());
    android.view.ViewGroup actions = host.findViewById(R.id.terminal_sessions_actions_dense);
    assertEquals(3, actions.getChildCount());
    assertEquals(2, ((android.view.ViewGroup) actions.getChildAt(0)).getChildCount());
    controller.pause().stop().destroy();
  }

  @Test
  @Config(qualifiers = "night")
  public void sidebarNoLongerExposesThemeControls() {
    setTheme("dark");
    var controller = buildHost();
    TerminalActivity host = controller.get();
    measure(host, 400, 900);
    openDrawer(host);
    assertNull(host.findViewById(R.id.terminal_sessions_theme));
    assertNull(host.findViewById(R.id.terminal_theme_system));
    assertNull(host.findViewById(R.id.terminal_theme_light));
    assertNull(host.findViewById(R.id.terminal_theme_dark));
    assertEquals("dark", new ConfigStore(host).getUiTheme());
    controller.pause().stop().destroy();
  }

  @Test
  @Config(qualifiers = "notnight")
  public void iconActionsExposeDescriptionsAndVectorIcons() {
    setTheme("light");
    var controller = buildHost();
    TerminalActivity host = controller.get();
    measure(host, 400, 900);
    openDrawer(host);

    assertTrue(host.findViewById(R.id.terminal_workspace_back) instanceof AppCompatImageButton);
    for (int id :
        new int[] {
          R.id.terminal_workspace_back,
          R.id.terminal_workspace_settings,
          R.id.terminal_workspace_help,
          R.id.terminal_workspace_menu,
          R.id.terminal_sessions_close,
          R.id.terminal_sessions_close_all
        }) {
      View view = host.findViewById(id);
      assertNotNull(view);
      assertNotNull(view.getContentDescription());
      assertFalse(view.getContentDescription().toString().isEmpty());
    }
    // “结束全部”只剩停止图标：必须是带无障碍名称的图标按钮，不再是文字胶囊。
    View closeAll = host.findViewById(R.id.terminal_sessions_close_all);
    assertTrue(closeAll instanceof AppCompatImageButton);
    assertNotNull(((AppCompatImageButton) closeAll).getDrawable());
    for (int id :
        new int[] {
          R.id.terminal_sessions_action_home,
          R.id.terminal_sessions_action_display,
          R.id.terminal_sessions_action_keyboard,
          R.id.terminal_sessions_action_paste,
          R.id.terminal_sessions_action_focus,
          R.id.terminal_sessions_action_help
        }) {
      TextView action = host.findViewById(id);
      assertNotNull(action);
      assertNotNull(action.getCompoundDrawablesRelative()[0]);
    }
    controller.pause().stop().destroy();
  }

  private void setTheme(String preference) {
    new ConfigStore(RuntimeEnvironment.getApplication()).setUiTheme(preference);
  }

  private void openDrawer(TerminalActivity host) {
    host.findViewById(R.id.terminal_menu_ball).performClick();
    assertEquals(View.VISIBLE, host.findViewById(R.id.terminal_sessions_panel).getVisibility());
  }

  private void assertFooterActions(TerminalActivity host, boolean dense) {
    for (int id :
        dense
            ? new int[] {
              R.id.terminal_sessions_action_home_dense,
              R.id.terminal_sessions_action_display_dense,
              R.id.terminal_sessions_action_keyboard_dense,
              R.id.terminal_sessions_action_paste_dense,
              R.id.terminal_sessions_action_focus_dense,
              R.id.terminal_sessions_action_help_dense
            }
            : new int[] {
              R.id.terminal_sessions_action_home,
              R.id.terminal_sessions_action_display,
              R.id.terminal_sessions_action_keyboard,
              R.id.terminal_sessions_action_paste,
              R.id.terminal_sessions_action_focus,
              R.id.terminal_sessions_action_help
            }) {
      TextView action = host.findViewById(id);
      assertNotNull(action);
      assertEquals(View.VISIBLE, action.getVisibility());
      assertTrue(action.isClickable());
      assertTrue(action.getMinHeight() >= Math.round(43.5f * density(host)));
    }
  }

  private static int colorOf(View view) {
    var drawable = view.getBackground();
    assertTrue(drawable instanceof ColorDrawable);
    return ((ColorDrawable) drawable).getColor();
  }

  private static float density(TerminalActivity host) {
    return host.getResources().getDisplayMetrics().density;
  }

  private static org.robolectric.android.controller.ActivityController<
          TerminalActivityLifecycleTest.Host>
      buildHost() {
    return Robolectric.buildActivity(TerminalActivityLifecycleTest.Host.class)
        .create()
        .start()
        .resume();
  }

  private static void measure(TerminalActivity host, int widthDp, int heightDp) {
    View workspace = host.findViewById(R.id.terminal_workspace);
    float density = host.getResources().getDisplayMetrics().density;
    int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
    workspace.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    workspace.layout(0, 0, width, height);
  }
}
