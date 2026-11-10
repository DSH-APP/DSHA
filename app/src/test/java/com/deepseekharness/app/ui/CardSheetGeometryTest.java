package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.SheetGeometry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * CardSheet 按 SheetGeometry 组装：手机竖屏贴底，平板按内容档定宽，手机横屏收窄或分栏。
 *
 * <p>只验证视图结构与窗口参数（mdpi，1px = 1dp），不验证真机渲染；真机核对按 ui-template-baseline §9。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = CardSheetGeometryTest.App.class)
public final class CardSheetGeometryTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private static Context context() {
    return new ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
  }

  private static CardPage page(Context c) {
    return new CardPage(c, "快捷按键", "应用于 PTY 会话。", true);
  }

  /** 内容远高于屏幕，用来核对 CardSheet 的贴底 0.9 上限。 */
  private static CardPage tallPage(Context c) {
    CardPage page = page(c);
    for (int card = 0; card < 3; card++) {
      LinearLayout group = page.card();
      for (int row = 0; row < 7; row++) page.kv(group, "项目" + (card + 1) + "." + (row + 1), "值");
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

  private static int windowWidth(AlertDialog dialog) {
    return dialog.getWindow().getAttributes().width;
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
  public void phonePortraitIsFullWidthBottomSheetWithoutLiftOutsideMain() {
    Context c = context();
    AlertDialog dialog = CardSheet.create(c, page(c), SheetGeometry.Size.L);
    var lp = dialog.getWindow().getAttributes();
    assertEquals(Gravity.BOTTOM, lp.gravity & Gravity.VERTICAL_GRAVITY_MASK);
    assertEquals(-1, lp.width);
    // 不是 MainActivity：没有悬浮胶囊，不再抬高 74dp（旧实现在终端里露出一条背景）。
    assertEquals(0, lp.y);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
  public void bottomCardSheetKeeps90PercentCapWhileActionDrawerUses72() {
    Context c = context();
    CardPage page = tallPage(c);
    CardSheet.create(c, page, SheetGeometry.Size.M);
    View container = (View) page.root.getParent();
    measure(container, 360, 740);
    // CardSheet 的贴底形态仍是 0.9×可用高（740 → 666）；动作抽屉单独收紧到 0.72（533）。
    assertEquals(666, container.getMeasuredHeight());
    assertEquals(533, SheetGeometry.bottomDrawerMaxHeight(740, 0));
  }

  @Test
  @Config(qualifiers = "zh-rCN-w780dp-h316dp-mdpi")
  public void phoneLandscapeShortContentIsCompactNotWide540() {
    Context c = context();
    AlertDialog choices = CardSheet.create(c, page(c), SheetGeometry.Size.S);
    assertEquals(400, windowWidth(choices));
    AlertDialog info = CardSheet.create(c, page(c), SheetGeometry.Size.M);
    assertEquals(480, windowWidth(info));
  }

  @Test
  @Config(qualifiers = "zh-rCN-w780dp-h316dp-mdpi")
  public void phoneLandscapeLongFormSplitsIntoTwoColumns() {
    Context c = context();
    CardPage page = page(c);
    AlertDialog dialog = CardSheet.create(c, page, SheetGeometry.Size.L);
    assertEquals(680, windowWidth(dialog));
    assertEquals(LinearLayout.HORIZONTAL, page.root.getOrientation());
    // 标题与副标题离开正文，和页脚一起在左栏；正文滚动区在右侧。
    assertNotSame(page.content, page.heading.getParent());
    assertNotSame(page.content, page.subtitle.getParent());
    View left = (View) page.footer.getParent();
    assertSame(page.root, left.getParent());
    assertEquals(SheetGeometry.sideColumnWidth(680), left.getLayoutParams().width);
    assertSame(page.root, page.scroll.getParent());
    // 后加的按钮仍进左栏页脚，并保持竖排。
    page.button(page.footer, "取消", false, () -> {});
    page.button(page.footer, "保存", true, () -> {});
    CardSheet.arrangeFooter(page, SheetGeometry.Mode.SIDE);
    assertEquals(LinearLayout.VERTICAL, page.footer.getOrientation());
  }

  @Test
  @Config(qualifiers = "zh-rCN-w800dp-h1236dp-mdpi")
  public void tabletPortraitWidthFollowsContentSize() {
    Context c = context();
    assertEquals(400, windowWidth(CardSheet.create(c, page(c), SheetGeometry.Size.S)));
    assertEquals(480, windowWidth(CardSheet.create(c, page(c), SheetGeometry.Size.M)));
    assertEquals(640, windowWidth(CardSheet.create(c, page(c), SheetGeometry.Size.L)));
  }

  @Test
  @Config(qualifiers = "zh-rCN-w1280dp-h756dp-mdpi")
  public void tabletLandscapeLongFormGetsExpandedWidth() {
    Context c = context();
    AlertDialog dialog = CardSheet.create(c, page(c), SheetGeometry.Size.L);
    assertEquals(720, windowWidth(dialog));
    assertEquals(Gravity.CENTER, dialog.getWindow().getAttributes().gravity);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w780dp-h316dp-mdpi")
  public void twoButtonFooterGoesInARowWithPrimaryOnTheRight() {
    Context c = context();
    CardPage page = page(c);
    CardSheet.create(c, page, SheetGeometry.Size.M);
    // 调用方先加主按钮也要换到右侧。
    Button save = page.button(page.footer, "保存", true, () -> {});
    Button cancel = page.button(page.footer, "取消", false, () -> {});
    CardSheet.arrangeFooter(page, SheetGeometry.Mode.COMPACT);
    assertEquals(LinearLayout.HORIZONTAL, page.footer.getOrientation());
    assertEquals(0, page.footer.indexOfChild(cancel));
    assertEquals(1, page.footer.indexOfChild(save));
    var p = (LinearLayout.LayoutParams) save.getLayoutParams();
    assertEquals(0, p.width);
    assertEquals(1f, p.weight, 0f);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
  public void customFooterRowsAndThreeButtonsAreLeftAlone() {
    Context c = context();
    CardPage three = page(c);
    for (String t : new String[] {"复制", "分享", "关闭"}) three.button(three.footer, t, false, () -> {});
    CardSheet.arrangeFooter(three, SheetGeometry.Mode.BOTTOM);
    assertEquals(LinearLayout.VERTICAL, three.footer.getOrientation());

    CardPage custom = page(c);
    LinearLayout row = new LinearLayout(c);
    custom.footer.addView(row);
    custom.button(custom.footer, "取消", false, () -> {});
    CardSheet.arrangeFooter(custom, SheetGeometry.Mode.BOTTOM);
    assertEquals(LinearLayout.VERTICAL, custom.footer.getOrientation());
  }
}
