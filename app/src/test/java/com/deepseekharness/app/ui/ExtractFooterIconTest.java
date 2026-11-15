package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.DeployStages;
import com.deepseekharness.app.util.MaintenanceFooterPolicy;
import com.google.android.material.button.MaterialButton;
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
 * 维护页底栏三个次要入口：图标跟随文字居中，而不是贴在按钮最左边（build255 回归）。
 *
 * <p>mdpi 下 1dp = 1px。只验证布局参数与图标偏移，不验证真机渲染。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = ExtractFooterIconTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ExtractFooterIconTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  public static final class Host extends AppCompatActivity {
    ExtractFooter footer;

    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      setContentView(R.layout.activity_extract);
      footer = new ExtractFooter(this, new OnboardingKit(this));
      footer.render(
          MaintenanceFooterPolicy.decide(false, false, false, false, true),
          DeployStages.idle(),
          "重建",
          true);
    }
  }

  private ActivityController<Host> host;

  @After
  public void tearDown() {
    if (host != null) host.pause().stop().destroy();
  }

  private Host start() {
    host = Robolectric.buildActivity(Host.class).setup().visible();
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

  private static Button[] links(ExtractFooter f) {
    return new Button[] {f.logs(), f.copy(), f.emergency()};
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
  public void iconsSitBesideTextInsteadOfButtonEdge() {
    Host a = start();
    layout(a.getWindow().getDecorView(), 360);
    for (Button b : links(a.footer)) {
      assertTrue(b instanceof MaterialButton);
      MaterialButton m = (MaterialButton) b;
      assertEquals(MaterialButton.ICON_GRAVITY_TEXT_START, m.getIconGravity());
      Drawable icon = m.getIcon();
      assertNotNull(icon);
      assertEquals(16, m.getIconSize());
      // 图标在按钮内向文字一侧偏移；旧实现贴在左内边距处（偏移为 0）。
      assertTrue(b.getText() + " icon offset " + icon.getBounds(), icon.getBounds().left > 0);
      assertTrue(b.getWidth() > 0);
    }
  }

  @Test
  @Config(qualifiers = "zh-rCN-w330dp-h740dp-mdpi")
  public void stackedLinksPutIconAboveText() {
    Host a = start();
    layout(a.getWindow().getDecorView(), 330);
    for (Button b : links(a.footer)) {
      MaterialButton m = (MaterialButton) b;
      assertEquals(MaterialButton.ICON_GRAVITY_TEXT_TOP, m.getIconGravity());
      assertNotNull(m.getIcon());
      Drawable[] relative = b.getCompoundDrawablesRelative();
      assertNull("横排槽位不应残留图标", relative[0]);
      assertNotNull("竖排图标在文字上方", relative[1]);
    }
  }
}
