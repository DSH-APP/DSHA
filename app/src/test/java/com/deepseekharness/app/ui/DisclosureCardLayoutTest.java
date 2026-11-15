package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/**
 * 折叠披露在 360dp 宽、1.3 倍字体下不横向溢出：摘要与展开后的详情都按父宽换行，
 * 后果行最多三行；展开不改变外层滚动容器的能力（组件自身不固定高度）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = DisclosureCardLayoutTest.App.class,
    qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class DisclosureCardLayoutTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private static void animationScales(float value) {
    android.provider.Settings.Global.putFloat(
        RuntimeEnvironment.getApplication().getContentResolver(),
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        value);
    android.provider.Settings.Global.putFloat(
        RuntimeEnvironment.getApplication().getContentResolver(),
        android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
        value);
  }

  private static Context zoomed(float fontScale) {
    Context base = RuntimeEnvironment.getApplication();
    Configuration configuration = new Configuration(base.getResources().getConfiguration());
    configuration.fontScale = fontScale;
    return new ContextThemeWrapper(
        base.createConfigurationContext(configuration), R.style.Theme_DeepseekHarness);
  }

  private static int[] measure(View view) {
    int width = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY);
    int height = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.AT_MOST);
    view.measure(width, height);
    view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    return new int[] {view.getMeasuredWidth(), view.getMeasuredHeight()};
  }

  private static void assertFits(View view, int width, String where) {
    if (view.getVisibility() == View.GONE) return;
    assertTrue(
        where + " overflow: " + view.getClass().getSimpleName() + " " + view.getMeasuredWidth(),
        view.getMeasuredWidth() <= width);
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++)
        assertFits(group.getChildAt(i), width, where + "/" + i);
    }
  }

  /** 页 → 摘要 + 折叠容器；折叠容器第 2 个子项是详情容器（收起时为空）。 */
  private static TextView detailsOf(View scroll) {
    ViewGroup page = (ViewGroup) ((ViewGroup) scroll).getChildAt(0);
    if (page.getChildCount() < 2) return null;
    ViewGroup holder = (ViewGroup) page.getChildAt(1);
    if (holder.getChildCount() < 2) return null;
    ViewGroup details = (ViewGroup) holder.getChildAt(1);
    return details.getChildCount() == 0 ? null : (TextView) details.getChildAt(0);
  }

  private static View build(float fontScale) {
    animationScales(0f);
    Context context = zoomed(fontScale);
    ScrollView scroll = new ScrollView(context);
    scroll.setFillViewport(true);
    LinearLayout page = new LinearLayout(context);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(20, 18, 20, 90);
    scroll.addView(page, new LinearLayout.LayoutParams(-1, -2));

    TextView summary = new TextView(context);
    summary.setText("恢复核对未完成，Web 实例尚未确认。");
    summary.setTextSize(13f);
    page.addView(summary, new LinearLayout.LayoutParams(-1, -2));
    DisclosureCard.Model model = new DisclosureCard.Model();
    model.key = "layout";
    model.detail =
        "原 Web 进程在上次退出时没有留下可核验的关闭回执，因此这次启动不能直接复用旧实例。"
            + "先回到启动页确认服务状态；如果仍然待确认，请停止服务后重新启动，原数据和已有备份都会保留。";
    model.consequence = "未确认前不会自动进入 Web，也不会重放上次的操作。";
    DisclosureCard.bind(summary, model, new DisclosureCard.Memory(), null);
    return scroll;
  }

  @Test
  public void collapsedAndExpandedFitNarrowScreenAtNormalFont() {
    View scroll = build(1f);
    measure(scroll);
    assertFits(scroll, 360, "collapsed");
    assertNull("收起时不创建详情", detailsOf(scroll));
    View page = ((ViewGroup) ((ViewGroup) scroll).getChildAt(0));
    TextView summary = (TextView) ((ViewGroup) page).getChildAt(0);
    summary.performClick();
    measure(scroll);
    assertFits(scroll, 360, "expanded");
    TextView detail = detailsOf(scroll);
    assertNotNull(detail);
    assertEquals(1f, detail.getAlpha(), 0.001f);
  }

  @Test
  public void expandedDetailsFitAt1Point3FontScale() {
    View scroll = build(1.3f);
    measure(scroll);
    View page = ((ViewGroup) ((ViewGroup) scroll).getChildAt(0));
    TextView summary = (TextView) ((ViewGroup) page).getChildAt(0);
    summary.performClick();
    measure(scroll);
    int[] size = measure(scroll);
    assertTrue("展开后仍应有内容高度", size[1] > 0);
    assertFits(scroll, 360, "expanded@1.3");
    TextView detail = detailsOf(scroll);
    assertNotNull(detail);
    assertEquals(
        "说明按父宽换行，不按字符宽度撑开", ViewGroup.LayoutParams.MATCH_PARENT, detail.getLayoutParams().width);
  }

  @Test
  public void consequenceIsBoundedToThreeLines() {
    Context context = zoomed(1.3f);
    LinearLayout page = new LinearLayout(context);
    page.setOrientation(LinearLayout.VERTICAL);
    TextView summary = new TextView(context);
    summary.setText("摘要");
    page.addView(summary, new LinearLayout.LayoutParams(-1, -2));
    DisclosureCard.Model model = new DisclosureCard.Model();
    model.key = "consequence";
    model.detail = "完整说明";
    model.consequence = "仅能访问应用私有目录；其他应用私有目录与系统目录仍受 Android 限制。";
    DisclosureCard.bind(summary, model, new DisclosureCard.Memory(), null);
    measure(page);
    TextView consequence = null;
    for (int i = 0; i < page.getChildCount(); i++) {
      View child = page.getChildAt(i);
      if (child instanceof LinearLayout holder && holder.getChildCount() > 0)
        consequence = (TextView) holder.getChildAt(0);
    }
    assertNotNull(consequence);
    assertEquals(3, consequence.getMaxLines());
    assertFits(page, 360, "consequence");
  }
}
