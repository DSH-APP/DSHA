package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** F3 卡内标签页：同时只有一个面板可见、切页不重建、保存键恢复、读屏有选中状态。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = CardTabsTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class CardTabsTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContextThemeWrapper context;

  @Before
  public void setUp() {
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
  }

  @Test
  public void onlyOnePaneIsVisibleAndTabClickSwitches() {
    LinearLayout card = new LinearLayout(context);
    View first = pane();
    View second = pane();
    card.addView(first);
    card.addView(second);
    CardTabs tabs =
        CardTabs.bind(
            card,
            List.of(
                new CardTabs.Tab("status", "状态", first), new CardTabs.Tab("tools", "工具", second)),
            null);
    assertNotNull(tabs);
    assertEquals("status", tabs.selectedKey());
    assertEquals(View.VISIBLE, first.getVisibility());
    assertEquals(View.GONE, second.getVisibility());
    // 第二个页签格子里第一个 TextView 就是可点标签。
    LinearLayout toolsCell = (LinearLayout) tabs.row().getChildAt(1);
    TextView toolsLabel = (TextView) toolsCell.getChildAt(0);
    toolsLabel.performClick();
    assertEquals("tools", tabs.selectedKey());
    assertEquals(View.GONE, first.getVisibility());
    assertEquals(View.VISIBLE, second.getVisibility());
    assertTrue(toolsLabel.isSelected());
    assertEquals("已选中", toolsLabel.getStateDescription().toString());
  }

  @Test
  public void savedKeyRestoresTheSameTab() {
    LinearLayout card = new LinearLayout(context);
    View first = pane();
    View second = pane();
    card.addView(first);
    card.addView(second);
    CardTabs tabs =
        CardTabs.bind(
            card,
            List.of(new CardTabs.Tab("a", "A", first), new CardTabs.Tab("b", "B", second)),
            "b");
    assertEquals(1, tabs.selectedIndex());
    assertEquals("b", tabs.selectedKey());
    assertEquals(View.GONE, first.getVisibility());
    assertEquals(View.VISIBLE, second.getVisibility());
  }

  @Test
  public void unknownSelectionIsRejected() {
    LinearLayout card = new LinearLayout(context);
    View panel = pane();
    card.addView(panel);
    CardTabs tabs = CardTabs.bind(card, List.of(new CardTabs.Tab("only", "唯一", panel)), null);
    assertFalse(tabs.select("missing"));
    assertFalse(tabs.select("only"));
    assertEquals("only", tabs.selectedKey());
  }

  @Test
  public void tabsSurviveCompactWidthAndLargeFont() {
    RuntimeEnvironment.setFontScale(1.3f);
    try {
      LinearLayout card = new LinearLayout(context);
      View first = pane();
      View second = pane();
      card.addView(first);
      card.addView(second);
      CardTabs tabs =
          CardTabs.bind(
              card,
              List.of(
                  new CardTabs.Tab("startup", "启动与修复", first),
                  new CardTabs.Tab("network", "网络与插件", second)),
              null);
      card.measure(
          View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST));
      card.layout(0, 0, 360, card.getMeasuredHeight());
      assertEquals(2, tabs.row().getChildCount());
      for (int index = 0; index < tabs.row().getChildCount(); index++)
        assertTrue(tabs.row().getChildAt(index).getMeasuredWidth() > 0);
    } finally {
      RuntimeEnvironment.setFontScale(1f);
    }
  }

  private View pane() {
    View pane = new View(context);
    pane.setLayoutParams(new LinearLayout.LayoutParams(-1, 100));
    return pane;
  }
}
