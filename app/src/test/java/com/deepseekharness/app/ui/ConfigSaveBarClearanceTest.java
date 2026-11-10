package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import androidx.appcompat.view.ContextThemeWrapper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 运行配置页保存栏固定在页底、不随内容滚动；手机上主界面的底部导航胶囊浮在内容之上
 * （activity_main 的 FrameLayout 叠放），保存栏必须用 shell_nav_clearance 让出胶囊，
 * 否则「保存」整条被盖住。平板走左侧导航栏，没有胶囊，保存栏贴页底。
 *
 * <p>只验证布局参数，不验证真机渲染；配置页单独渲染时没有胶囊，LayoutAudit 量不到这处重叠。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = ConfigSaveBarClearanceTest.App.class)
public final class ConfigSaveBarClearanceTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private static View inflate() {
    var context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    return LayoutInflater.from(context).inflate(R.layout.fragment_config, null, false);
  }

  private static int saveBarBottomMargin(View root) {
    View bar = root.findViewById(R.id.config_save_bar);
    assertNotNull(bar);
    // 位置不变：保存栏仍是页面根的直接子项，在滚动区之外。
    assertSame(root, bar.getParent());
    assertTrue(root.findViewById(R.id.config_scroll) instanceof ScrollView);
    return ((ViewGroup.MarginLayoutParams) bar.getLayoutParams()).bottomMargin;
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
  public void phoneSaveBarClearsTheFloatingNavigationCapsule() {
    Resources res = RuntimeEnvironment.getApplication().getResources();
    int capsule =
        res.getDimensionPixelSize(R.dimen.shell_nav_height)
            + res.getDimensionPixelSize(R.dimen.shell_nav_margin_bottom);
    int margin = saveBarBottomMargin(inflate());
    assertTrue("保存栏底边距 " + margin + "px 小于悬浮胶囊占用 " + capsule + "px，保存按钮会被盖住", margin >= capsule);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w800dp-h1280dp-mdpi")
  public void tabletSaveBarSitsOnThePageBottom() {
    assertEquals(0, saveBarBottomMargin(inflate()));
  }
}
