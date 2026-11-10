package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.VirtualScreenPreviewPolicy;
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

/** 宿主页预览：默认提示条、收起持久化、三档切换；传输层保持空闲。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = VirtualScreenForegroundTest.App.class,
    qualifiers = "w384dp-h853dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class VirtualScreenForegroundTest {
  /** 不注册全局 Activity 生命周期，避免真实 HarnessService 初始化依赖设备路径。 */
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  /** 普通宿主页：内容容器是 FrameLayout，与真实 ForegroundActivity 的挂载点一致。 */
  public static final class Host extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
      super.onCreate(saved);
      setTheme(R.style.Theme_DeepseekHarness);
      setContentView(new FrameLayout(this));
    }
  }

  private ActivityController<Host> controller;
  private Host host;
  private VirtualScreenManager manager;
  private SharedPreferences prefs;

  @Before
  public void setUp() throws Exception {
    UiText.setLanguage("zh");
    controller = Robolectric.buildActivity(Host.class).setup();
    host = controller.get();
    prefs = RuntimeEnvironment.getApplication().getSharedPreferences(Constants.PREFS, 0);
    prefs.edit().clear().commit();
    manager =
        new VirtualScreenManager(
            RuntimeEnvironment.getApplication(),
            () -> 100L,
            new VirtualScreenManager.Visuals() {
              public void present() {}

              public void stopped(long fence) {}
            });
    // 只做 UI 决策：没有 token 时 targetStatus 立即返回失败，不建立任何设备连接。
    field(manager, "generation", "display-a");
    field(manager, "activeEpoch", 0L);
    idle();
  }

  @After
  public void tearDown() {
    manager.foreground().pause(host);
    idle();
    prefs.edit().clear().commit();
    controller.destroy();
  }

  private static void idle() {
    ShadowLooper.idleMainLooper();
  }

  private static void field(Object owner, String name, Object value) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private View strip() {
    return host.findViewById(R.id.vscreen_preview_strip);
  }

  private View panel() {
    return host.findViewById(R.id.vscreen_preview_panel);
  }

  private void show() {
    manager.foreground().resume(host);
    idle();
  }

  private static View findByDescription(View root, String description) {
    CharSequence own = root.getContentDescription();
    if (own != null && description.contentEquals(own)) return root;
    if (root instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findByDescription(group.getChildAt(i), description);
        if (found != null) return found;
      }
    return null;
  }

  @Test
  public void defaultModeShowsTheStripAndPauseRemovesIt() {
    show();
    View strip = strip();
    assertNotNull("默认档位应在宿主页顶部显示提示条", strip);
    CharSequence description = strip.getContentDescription();
    assertNotNull(description);
    assertTrue("无目标时应显示运行中文案", description.toString().contains("虚拟屏操作中"));
    assertEquals("提示条热区应达到 40dp", 40, strip.getLayoutParams().height);
    // 宿主页离开前台（Activity onPause）时移除；重新 resume 再出现。
    manager.foreground().pause(host);
    idle();
    assertNull(strip());
    show();
    assertNotNull(strip());
  }

  @Test
  public void collapsePersistsAcrossGenerationsUntilSettingsReopens() throws Exception {
    show();
    assertNotNull(strip());
    View close = findByDescription(strip(), "收起虚拟屏提示条");
    assertNotNull("提示条应有明确的收起入口", close);
    assertTrue(close.performClick());
    idle();
    assertNull("收起后立即隐藏", strip());
    assertTrue("收起意图持久化", prefs.getBoolean(VirtualScreenPreviewPolicy.KEY_COLLAPSED, false));
    // 换代次或重启不会自己回来：不再按 generation 记忆。
    field(manager, "generation", "display-b");
    manager.foreground().present();
    idle();
    assertNull(strip());
    // 设置里重新选择档位 = 选择即显示。
    VirtualScreenPreviewSettings.setMode(host, VirtualScreenPreviewPolicy.Mode.STRIP);
    manager.foreground().refresh();
    idle();
    assertNotNull(strip());
    assertFalse(prefs.getBoolean(VirtualScreenPreviewPolicy.KEY_COLLAPSED, false));
  }

  @Test
  public void offHidesEverythingAndFullPreviewUsesTheOldWindow() {
    VirtualScreenPreviewSettings.setMode(host, VirtualScreenPreviewPolicy.Mode.OFF);
    show();
    assertNull(strip());
    assertNull(panel());
    VirtualScreenPreviewSettings.setMode(host, VirtualScreenPreviewPolicy.Mode.FULL);
    manager.foreground().refresh();
    idle();
    assertNull(strip());
    assertNotNull("完整预览档位应恢复旧的页内小窗", panel());
    // 回到默认档位：小窗移除、提示条出现，两种模式不同时出现。
    VirtualScreenPreviewSettings.setMode(host, VirtualScreenPreviewPolicy.Mode.STRIP);
    manager.foreground().refresh();
    idle();
    assertNull(panel());
    assertNotNull(strip());
  }

  @Test
  public void stoppedRemovesTheStrip() {
    show();
    assertNotNull(strip());
    manager.foreground().stopped(manager.epoch());
    idle();
    assertNull(strip());
  }
}
