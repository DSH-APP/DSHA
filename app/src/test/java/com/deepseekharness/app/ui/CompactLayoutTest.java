package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.skills.SkillCatalogueFixture;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/**
 * 手机竖屏（360dp）与宽屏的布局回归：方案 A 改过的子页在窄屏下不横向溢出，技能页按宽度切换单列 / 并排。
 *
 * <p>mdpi 下 1dp = 1px，直接按 dp 宽度测量。只验证布局，不验证真机渲染。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = CompactLayoutTest.App.class,
    qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class CompactLayoutTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  public static final class Host extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      FrameLayout content = new FrameLayout(this);
      content.setId(R.id.fragment_container);
      setContentView(content);
    }
  }

  private ActivityController<Host> host;

  @After
  public void tearDown() {
    if (host != null) host.pause().stop().destroy();
  }

  private Host start(Fragment page) {
    host = Robolectric.buildActivity(Host.class).setup().visible();
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, page)
        .commitNow();
    ShadowLooper.shadowMainLooper().idle();
    return host.get();
  }

  private static void layout(View root, int width) {
    for (int pass = 0; pass < 3; pass++) {
      root.measure(
          View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST));
      root.layout(0, 0, width, root.getMeasuredHeight());
      ShadowLooper.shadowMainLooper().idle();
    }
  }

  /** 所有可见子视图的左右边界都在页面宽度内（不计滚动容器内部的纵向延伸）。 */
  private static void assertNoHorizontalOverflow(View root, int width) {
    List<String> overflow = new ArrayList<>();
    collectOverflow(root, 0, width, overflow);
    assertTrue("horizontal overflow: " + overflow, overflow.isEmpty());
  }

  private static void collectOverflow(View view, int left, int width, List<String> out) {
    if (view.getVisibility() != View.VISIBLE) return;
    int start = left + view.getLeft();
    int end = start + view.getWidth();
    if (start < -1 || end > width + 1) {
      String label =
          view instanceof TextView ? String.valueOf(((TextView) view).getText()) : view.toString();
      out.add(label + " [" + start + "," + end + "]");
      return;
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        collectOverflow(group.getChildAt(i), start - view.getScrollX(), width, out);
  }

  private static List<View> tagged(View view, String prefix, List<View> out) {
    if (view.getTag() instanceof String tag && tag.startsWith(prefix)) out.add(view);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) tagged(group.getChildAt(i), prefix, out);
    return out;
  }

  private SkillsFragment skills(int width, int count) throws Exception {
    return skills(width, count, 0, 0);
  }

  private SkillsFragment skills(int width, int count, int disabled, int trashed) throws Exception {
    SkillsFragment page = new SkillsFragment();
    page.disableAutoLoadForTest();
    start(page);
    String[] names = new String[count];
    for (int i = 0; i < count; i++) names[i] = "fixture-skill-" + (i + 1);
    page.showCatalogueForTest(SkillCatalogueFixture.withStates(1, disabled, trashed, names));
    layout(page.requireView(), width);
    return page;
  }

  private static int checkedSwitches(View root, boolean checked) {
    int count = 0;
    for (View view : tagged(root, "skill.switch:", new ArrayList<>()))
      if (((android.widget.CompoundButton) view).isChecked() == checked) count++;
    return count;
  }

  @Test
  public void skillsPhonePortraitIsSingleColumnWithoutOverflow() throws Exception {
    SkillsFragment page = skills(360, 3);
    View root = page.requireView();
    assertEquals(3, tagged(root, "skill:", new ArrayList<>()).size());
    // 单列：没有右侧详情卡片，行内带箭头，详情走底部弹层。
    assertEquals(0, tagged(root, "skills.detail", new ArrayList<>()).size());
    assertNoHorizontalOverflow(root, 360);
  }

  @Test
  public void skillsPhoneShowsSwitchesDisabledGroupAndRecentlyDeleted() throws Exception {
    SkillsFragment page = skills(360, 3, 1, 2);
    View root = page.requireView();
    assertEquals(3, tagged(root, "skill:", new ArrayList<>()).size());
    assertEquals(2, checkedSwitches(root, true));
    assertEquals(1, checkedSwitches(root, false));
    assertEquals(2, tagged(root, "skills.count", new ArrayList<>()).size());
    assertEquals(1, tagged(root, "skills.trash", new ArrayList<>()).size());
    assertNoHorizontalOverflow(root, 360);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w720dp-h1000dp-mdpi")
  public void skillsTabletPortraitIsSingleColumnWideRows() throws Exception {
    // 平板竖屏（设置子页约 700dp）：单列宽行，详情走居中弹层，不挤成两栏。
    SkillsFragment page = skills(720, 3, 1, 1);
    View root = page.requireView();
    assertEquals(3, tagged(root, "skill:", new ArrayList<>()).size());
    assertEquals(0, tagged(root, "skills.detail", new ArrayList<>()).size());
    assertEquals(3, tagged(root, "skill.switch:", new ArrayList<>()).size());
    assertNoHorizontalOverflow(root, 720);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w900dp-h700dp-mdpi")
  public void skillsWideShowsListAndDetailSideBySide() throws Exception {
    SkillsFragment page = skills(900, 3);
    View root = page.requireView();
    List<View> rows = tagged(root, "skill:", new ArrayList<>());
    assertEquals(3, rows.size());
    List<View> detail = tagged(root, "skills.detail", new ArrayList<>());
    assertEquals(1, detail.size());
    // 并排：详情在列表右侧，首项默认选中；详情带状态卡与删除入口。
    int[] rowAt = new int[2], detailAt = new int[2];
    rows.get(0).getLocationInWindow(rowAt);
    detail.get(0).getLocationInWindow(detailAt);
    assertTrue(detailAt[0] > rowAt[0] + rows.get(0).getWidth() - 1);
    assertTrue(rows.get(0).isSelected());
    assertEquals(1, tagged(detail.get(0), "skills.state", new ArrayList<>()).size());
    assertEquals(1, tagged(detail.get(0), "skills.delete", new ArrayList<>()).size());
    assertNoHorizontalOverflow(root, 900);
  }

  @Test
  public void skillTierThresholds() {
    assertEquals(SkillsFragment.Tier.NARROW, SkillsFragment.tierFor(599));
    assertEquals(SkillsFragment.Tier.MEDIUM, SkillsFragment.tierFor(600));
    assertEquals(SkillsFragment.Tier.MEDIUM, SkillsFragment.tierFor(705));
    assertEquals(SkillsFragment.Tier.WIDE, SkillsFragment.tierFor(780));
  }

  @Test
  public void skillsEmptyStateFitsPhone() throws Exception {
    SkillsFragment page = skills(360, 0);
    assertNoHorizontalOverflow(page.requireView(), 360);
  }

  @Test
  public void overlayPagePhonePortraitHasNoOverflow() {
    Host activity = start(new OverlayFragment());
    View root =
        activity
            .getSupportFragmentManager()
            .findFragmentById(R.id.fragment_container)
            .requireView();
    layout(root, 360);
    assertNoHorizontalOverflow(root, 360);
  }
}
