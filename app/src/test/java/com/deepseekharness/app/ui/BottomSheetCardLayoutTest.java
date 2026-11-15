package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/**
 * 底部抽屉布局：短屏（360×480dp）占满可用高度、正文滚动而页脚常驻；1.3 倍字体下不横向溢出；
 * RTL（阿拉伯语）下仍贴底整宽、返回键照常先收抽屉。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = BottomSheetCardLayoutTest.App.class,
    qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class BottomSheetCardLayoutTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  /** 1.3 倍字体：短屏与大字体同时成立，正文只能靠滚动，不能撑破页面。 */
  public static final class ZoomedHost extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context base) {
      Configuration configuration = new Configuration(base.getResources().getConfiguration());
      configuration.fontScale = 1.3f;
      super.attachBaseContext(base.createConfigurationContext(configuration));
    }

    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
    }
  }

  private static ActivityController<ZoomedHost> host() {
    return Robolectric.buildActivity(ZoomedHost.class).setup();
  }

  /** 内容高于一屏：3 张卡 × 7 行，短屏必须滚动。 */
  private static CardPage tallPage(Context context) {
    CardPage page = new CardPage(context, "备份详情", "备份记录的时间、大小、编号、结果与下一步操作都在这里；返回键先收起抽屉。", true);
    for (int card = 0; card < 3; card++) {
      android.widget.LinearLayout group = page.card();
      for (int row = 0; row < 7; row++)
        page.kv(
            group,
            "项目" + (card + 1) + "." + (row + 1),
            "值 " + (card + 1) + "-" + (row + 1) + " · 一段较长的说明文字");
    }
    page.button(page.footer, "关闭", false, () -> {});
    return page;
  }

  private static void measure(View view, int width, int height) {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
    view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
  }

  private static void assertFits(View view, int width, String path) {
    if (view.getVisibility() == View.GONE) return;
    assertTrue(
        path + " overflow: " + view.getClass().getSimpleName() + " " + view.getMeasuredWidth(),
        view.getMeasuredWidth() <= width);
    if (view instanceof ViewGroup group) {
      for (int index = 0; index < group.getChildCount(); index++)
        assertFits(group.getChildAt(index), width, path + "/" + index);
    }
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h400dp-mdpi")
  public void shortScreenFillsHeightAndScrollsWhileFooterStays() {
    ActivityController<ZoomedHost> controller = host();
    ZoomedHost activity = controller.get();
    CardPage page = tallPage(activity);
    BottomSheetCard.Sheet sheet = BottomSheetCard.show(activity, page);
    try {
      View container = sheet.container();
      measure(container, 360, 400);
      // 短屏：整页占满可用高度（400 - 12dp 顶边距），正文滚动、页脚常驻。
      assertEquals(388, container.getMeasuredHeight());
      assertEquals(388, page.root.getHeight());
      assertTrue("正文滚动区必须有高度", page.scroll.getHeight() > 0);
      assertTrue("内容高于可视区时可滚动", page.content.getHeight() > page.scroll.getHeight());
      assertTrue("页脚在滚动区下方", page.footer.getTop() >= page.scroll.getBottom());
      assertTrue(page.footer.getBottom() <= container.getHeight());
      assertFits(container, 360, "container");
      assertTrue(sheet.handleBack());
      assertFalse(sheet.isShowing());
    } finally {
      sheet.dismiss();
      controller.pause().stop().destroy();
    }
  }

  @Test
  public void shortContentWrapsOnTallScreen() {
    ActivityController<ZoomedHost> controller = host();
    ZoomedHost activity = controller.get();
    CardPage page = new CardPage(activity, "筛选", "插件排序方式", true);
    page.kv(page.card(), "当前", "名称 A→Z");
    page.button(page.footer, "关闭", false, () -> {});
    BottomSheetCard.Sheet sheet = BottomSheetCard.show(activity, page);
    try {
      View container = sheet.container();
      measure(container, 360, 740);
      // 普通屏幕：按内容收紧（不超过 72% 首屏上限），不强行铺满。
      assertTrue(container.getMeasuredHeight() > 0);
      assertTrue(container.getMeasuredHeight() < 740);
      assertFits(container, 360, "tall");
    } finally {
      sheet.dismiss();
      controller.pause().stop().destroy();
    }
  }

  @Test
  public void tallContentIsCappedAt72PercentOfAvailableHeight() {
    ActivityController<ZoomedHost> controller = host();
    ZoomedHost activity = controller.get();
    CardPage page = tallPage(activity);
    BottomSheetCard.Sheet sheet = BottomSheetCard.show(activity, page);
    try {
      View container = sheet.container();
      measure(container, 360, 740);
      // 宿主不是 MainActivity（没有悬浮胶囊，lift=0）：首屏上限 0.72×740 = 533dp。
      assertEquals(533, container.getMeasuredHeight());
      assertEquals(533, page.root.getHeight());
      assertTrue("正文滚动区必须有高度", page.scroll.getHeight() > 0);
      assertTrue("内容高于首屏上限时可滚动", page.content.getHeight() > page.scroll.getHeight());
      assertTrue("页脚常驻在首屏内", page.footer.getBottom() <= container.getHeight());
      assertFits(container, 360, "capped");
    } finally {
      sheet.dismiss();
      controller.pause().stop().destroy();
    }
  }

  @Test
  @Config(qualifiers = "ar-rSA-ldrtl-w360dp-h740dp-mdpi")
  public void rtlDrawerStaysBottomFullWidthAndBackCloses() {
    ActivityController<ZoomedHost> controller = host();
    ZoomedHost activity = controller.get();
    CardPage page = tallPage(activity);
    BottomSheetCard.Sheet sheet = BottomSheetCard.show(activity, page);
    try {
      View container = sheet.container();
      container.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
      measure(container, 360, 740);
      assertEquals(View.LAYOUT_DIRECTION_RTL, container.getLayoutDirection());
      assertEquals(360, container.getMeasuredWidth());
      assertFits(container, 360, "rtl");
      assertTrue(page.footer.getChildCount() >= 1);
      assertTrue(sheet.handleBack());
      assertFalse(sheet.isShowing());
    } finally {
      sheet.dismiss();
      controller.pause().stop().destroy();
    }
  }
}
