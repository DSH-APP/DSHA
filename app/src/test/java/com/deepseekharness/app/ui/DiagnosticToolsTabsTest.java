package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DiagnosticRepository;
import com.deepseekharness.app.util.UiText;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** F3：诊断页工具卡的「启动与恢复 / 网络与插件」页签只显示一个面板，旋转后恢复同一页签。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = DiagnosticToolsTabsTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DiagnosticToolsTabsTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ActivityController<DiagnosticActivity> controller;
  private String previousLanguage;

  @Before
  public void setUp() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
  }

  @After
  public void tearDown() {
    if (controller != null) controller.pause().stop().destroy();
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void onlyOneToolPaneIsVisibleAndTabSwitches() {
    DiagnosticActivity page = open(null);
    assertEquals(View.VISIBLE, page.findViewById(R.id.diagnostic_tools_startup).getVisibility());
    assertEquals(View.GONE, page.findViewById(R.id.diagnostic_tools_network).getVisibility());

    tab(page, 1).performClick();
    ShadowLooper.shadowMainLooper().idle();

    assertEquals(View.GONE, page.findViewById(R.id.diagnostic_tools_startup).getVisibility());
    assertEquals(View.VISIBLE, page.findViewById(R.id.diagnostic_tools_network).getVisibility());
    assertTrue(tab(page, 1).isSelected());
    assertFalse(tab(page, 0).isSelected());
    // 未选中的面板 GONE：读屏不会读到隐藏的启动工具行。
    assertEquals(
        View.GONE, ((View) page.findViewById(R.id.diagnostic_history).getParent()).getVisibility());
  }

  @Test
  public void toolRowsKeepTheirIdsInsideThePanes() {
    DiagnosticActivity page = open(null);
    assertNotNull(page.findViewById(R.id.diagnostic_history));
    assertNotNull(page.findViewById(R.id.diagnostic_recovery));
    assertNotNull(page.findViewById(R.id.diagnostic_network));
    assertNotNull(page.findViewById(R.id.diagnostic_repair));
    assertNotNull(page.findViewById(R.id.diagnostic_plugins));
    tab(page, 1).performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(View.VISIBLE, page.findViewById(R.id.diagnostic_network).getVisibility());
    assertEquals(View.VISIBLE, page.findViewById(R.id.diagnostic_plugins).getVisibility());
  }

  @Test
  public void selectedTabSurvivesRecreation() {
    DiagnosticActivity page = open(null);
    tab(page, 1).performClick();
    ShadowLooper.shadowMainLooper().idle();
    Bundle saved = new Bundle();
    controller.pause().saveInstanceState(saved).stop().destroy();
    controller = Robolectric.buildActivity(DiagnosticActivity.class).setup(saved).visible();
    ShadowLooper.shadowMainLooper().idle();
    DiagnosticActivity restored = controller.get();
    assertEquals(View.GONE, restored.findViewById(R.id.diagnostic_tools_startup).getVisibility());
    assertEquals(
        View.VISIBLE, restored.findViewById(R.id.diagnostic_tools_network).getVisibility());
    assertTrue(tab(restored, 1).isSelected());
  }

  private DiagnosticActivity open(Bundle saved) {
    ActivityController<DiagnosticActivity> built =
        Robolectric.buildActivity(DiagnosticActivity.class);
    if (saved == null) controller = built.setup().visible();
    else controller = built.setup(saved).visible();
    ShadowLooper.shadowMainLooper().idle();
    new ViewModelProvider(controller.get()).get(DiagnosticRepository.class);
    return controller.get();
  }

  /** 工具卡第一个子视图是 CardTabs 注入的页签行。 */
  private static TextView tab(DiagnosticActivity page, int index) {
    LinearLayout tools = page.findViewById(R.id.diagnostic_tools);
    HorizontalScrollView scroller = (HorizontalScrollView) tools.getChildAt(0);
    LinearLayout row = (LinearLayout) scroller.getChildAt(0);
    LinearLayout cell = (LinearLayout) row.getChildAt(index);
    return (TextView) cell.getChildAt(0);
  }
}
