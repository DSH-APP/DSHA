package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import androidx.appcompat.view.ContextThemeWrapper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 手机竖屏底部胶囊只显示图标并压缩高度；横屏保留文字；平板走左栏不受影响。
 *
 * <p>只验证资源选择与布局参数，不验证真机渲染。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = ShellNavCompactPortraitTest.App.class)
public final class ShellNavCompactPortraitTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private static BottomNavigationView inflateNav() {
    var context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    View root = LayoutInflater.from(context).inflate(R.layout.activity_main, null, false);
    BottomNavigationView nav = root.findViewById(R.id.bottom_nav);
    assertNotNull(nav);
    return nav;
  }

  private static int px(int dimen) {
    return RuntimeEnvironment.getApplication().getResources().getDimensionPixelSize(dimen);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h740dp-port-mdpi")
  public void phonePortraitShowsIconsOnlyWithCompactCapsule() {
    BottomNavigationView nav = inflateNav();
    assertEquals(NavigationBarView.LABEL_VISIBILITY_UNLABELED, nav.getLabelVisibilityMode());
    assertEquals(52, px(R.dimen.shell_nav_height));
    // 页面底部留白与固定栏让位跟随压缩后的胶囊，不留旧 64dp 的空档。
    assertEquals(62, px(R.dimen.shell_nav_clearance));
    assertEquals(
        px(R.dimen.shell_nav_height) + px(R.dimen.shell_nav_margin_bottom),
        px(R.dimen.shell_nav_clearance));
    assertTrue(px(R.dimen.shell_page_bottom) > px(R.dimen.shell_nav_clearance));
    // 文字隐藏后仍保留菜单标题，供无障碍朗读与长按提示。
    for (int i = 0; i < nav.getMenu().size(); i++)
      assertFalse(nav.getMenu().getItem(i).getTitle().toString().isEmpty());
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h740dp-port-night-mdpi")
  public void phonePortraitNightAlsoCompact() {
    assertEquals(
        NavigationBarView.LABEL_VISIBILITY_UNLABELED, inflateNav().getLabelVisibilityMode());
    assertEquals(52, px(R.dimen.shell_nav_height));
  }

  @Test
  @Config(qualifiers = "zh-rCN-w740dp-h360dp-land-mdpi")
  public void phoneLandscapeKeepsLabels() {
    assertEquals(NavigationBarView.LABEL_VISIBILITY_LABELED, inflateNav().getLabelVisibilityMode());
    assertEquals(64, px(R.dimen.shell_nav_height));
    assertEquals(74, px(R.dimen.shell_nav_clearance));
  }

  @Test
  @Config(qualifiers = "zh-rCN-w800dp-h1280dp-port-mdpi")
  public void tabletPortraitKeepsRailClearance() {
    Resources res = RuntimeEnvironment.getApplication().getResources();
    // 平板胶囊隐藏，sw600dp 的 0dp 让位优先于 port。
    assertEquals(0, res.getDimensionPixelSize(R.dimen.shell_nav_clearance));
    assertEquals(View.GONE, inflateNav().getVisibility());
  }
}
