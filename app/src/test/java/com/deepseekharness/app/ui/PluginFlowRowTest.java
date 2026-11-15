package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.view.View;
import com.deepseekharness.app.DshaApp;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 头部流式行的折行、贴尾、拉满与 RTL；固定尺寸子项，不依赖文字测量。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi", application = PluginFlowRowTest.App.class)
public final class PluginFlowRowTest {
  /**
   * 不执行真实 {@code DshaApp.onCreate}：那会按系统语言改写 {@code UiText} 全局静态并在测试 JVM
   * 内跨类泄漏（en_US 的 CI 上会让随后依赖中文默认值的用例失败）。与其余界面测试保持同一约定。
   */
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private PluginFlowRow row(boolean stretch) {
    android.util.AttributeSet attrs =
        org.robolectric.Robolectric.buildAttributeSet()
            .addAttribute(com.deepseekharness.app.R.attr.flowGapHorizontal, "8dp")
            .addAttribute(com.deepseekharness.app.R.attr.flowGapVertical, "6dp")
            .addAttribute(
                com.deepseekharness.app.R.attr.flowStretchWrapped, String.valueOf(stretch))
            .build();
    return new PluginFlowRow(RuntimeEnvironment.getApplication(), attrs);
  }

  private static View child(PluginFlowRow parent, int width, int height) {
    View view = new View(parent.getContext());
    parent.addView(view, new PluginFlowRow.LayoutParams(width, height));
    return view;
  }

  private static void layout(View view, int width) {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
  }

  @Test
  public void fitsOnOneLineAndPinsLastChildToTheEnd() {
    PluginFlowRow row = row(true);
    View title = child(row, 80, 30);
    View segment = child(row, 150, 44);
    layout(row, 320);
    assertEquals(44, row.getMeasuredHeight());
    assertEquals(0, title.getLeft());
    assertEquals("标题在行内垂直居中", 7, title.getTop());
    assertEquals("末项贴行尾", 320 - 150, segment.getLeft());
    assertEquals(150, segment.getWidth());
  }

  @Test
  public void wrapsInsteadOfSqueezingAndStretchesTheLoneLastChild() {
    PluginFlowRow row = row(true);
    View title = child(row, 80, 30);
    View segment = child(row, 260, 44);
    layout(row, 320);
    assertEquals("两行 + 行距", 30 + 6 + 44, row.getMeasuredHeight());
    assertEquals(0, title.getLeft());
    assertEquals(0, segment.getLeft());
    assertEquals(36, segment.getTop());
    assertEquals("换行后独占一行拉满", 320, segment.getWidth());
  }

  @Test
  public void withoutStretchWrappedLastChildKeepsItsWidthAtLineStart() {
    PluginFlowRow row = row(false);
    View a = child(row, 120, 40);
    View b = child(row, 120, 40);
    View c = child(row, 120, 40);
    layout(row, 300);
    assertEquals(0, a.getLeft());
    assertEquals(128, b.getLeft());
    assertEquals("第三项换行，不压缩", 0, c.getLeft());
    assertEquals(46, c.getTop());
    assertEquals(120, c.getWidth());
  }

  @Test
  public void goneChildrenTakeNoSpaceAndRtlMirrors() {
    PluginFlowRow row = row(false);
    View a = child(row, 100, 40);
    View hidden = child(row, 400, 40);
    hidden.setVisibility(View.GONE);
    View b = child(row, 60, 40);
    row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    layout(row, 300);
    assertEquals("隐藏项不触发折行", 40, row.getMeasuredHeight());
    assertEquals("RTL：首项靠右", 200, a.getLeft());
    assertEquals("RTL：末项贴行尾（左侧）", 0, b.getLeft());
  }
}
