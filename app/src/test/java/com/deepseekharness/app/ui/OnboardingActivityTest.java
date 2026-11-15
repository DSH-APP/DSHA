package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.DeployStages;
import com.deepseekharness.app.util.PermissionGoals;
import com.deepseekharness.app.util.UiText;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

/**
 * 实际 OnboardingActivity 生命周期：首次进入、已就绪直达主界面、旋转保留步骤与用途。
 * 环境检查、部署监视与授权面板以影子替换，不初始化 Ubuntu、不启动任务、不读系统授权。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = OnboardingActivityTest.App.class,
    shadows = {
      OnboardingActivityTest.Environment.class,
      OnboardingActivityTest.Monitor.class,
      OnboardingActivityTest.Panel.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class OnboardingActivityTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = OnboardingEnvironment.class, isInAndroidSdk = false)
  public static final class Environment {
    static boolean ready;

    @Implementation
    protected static boolean readyForMain(Context context) {
      return ready;
    }

    @Implementation
    protected static long requiredBytes(Context context) {
      return 3L << 30;
    }

    @Implementation
    protected static long usableBytes(Context context) {
      return 20L << 30;
    }

    @Implementation
    protected static boolean installed(Context context) {
      return false;
    }
  }

  /** 部署监视器：不读 BackupTask、不轮询；当前视图与就绪状态由用例设置。 */
  @Implements(value = DeployMonitor.class, isInAndroidSdk = false)
  public static final class Monitor {
    static DeployStages.View view = DeployStages.idle();
    static boolean ready;

    @Implementation
    protected void __constructor__(
        androidx.lifecycle.LifecycleOwner owner,
        Context context,
        java.util.function.Consumer<com.deepseekharness.app.util.DeployStages.View> listener) {}

    @Implementation
    protected com.deepseekharness.app.util.DeployStages.View current() {
      return view;
    }

    @Implementation
    protected boolean environmentReady() {
      return ready;
    }

    @Implementation
    protected void refreshNow() {}
  }

  /** 授权面板：不读系统授权，只给出空视图与 0 / 6。 */
  @Implements(value = PermissionPanel.class, isInAndroidSdk = false)
  public static final class Panel {
    private View view;

    @Implementation
    protected void __constructor__(
        androidx.appcompat.app.AppCompatActivity activity,
        java.util.Set<String> goals,
        boolean environmentReady) {
      view = new View(activity);
    }

    @Implementation
    protected View view() {
      return view;
    }

    @Implementation
    protected void refresh() {}

    @Implementation
    protected void setEnvironmentReady(boolean ready) {}

    @Implementation
    protected int grantedCount() {
      return 0;
    }

    @Implementation
    protected java.util.List<String[]> summary() {
      return new java.util.ArrayList<>();
    }

    @Implementation
    protected int recommendedDone() {
      return 0;
    }

    @Implementation
    protected int recommendedTotal() {
      return 0;
    }

    @Implementation
    protected java.util.List<String> recommendedMissingNames() {
      return new java.util.ArrayList<>();
    }
  }

  private String previousLanguage;

  @Before
  public void prepare() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    Context app = RuntimeEnvironment.getApplication();
    ConfigStore config = new ConfigStore(app);
    config.setWelcomed(false);
    config.setCheckUpdate(false);
    app.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
    Environment.ready = false;
    Monitor.view = DeployStages.idle();
    Monitor.ready = false;
  }

  @After
  public void restoreLanguage() {
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void firstEntryShowsWelcomeStepWithoutStartingAnything() {
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    assertFalse(page.isFinishing());
    assertEquals(OnboardingActivity.WELCOME, page.step);
    View root = page.findViewById(android.R.id.content);
    assertTrue(hasText(root, "1 / 4"));
    assertTrue(hasText(root, "开始部署") || hasText(root, "无法部署") || hasText(root, "继续"));
    assertTrue(hasText(root, "部署前检查"));
    assertTrue(hasText(root, "网页内核") || hasText(root, "点此查看架构、空间与网页内核") || hasText(root, "可用"));
    assertTrue(hasText(root, "复制给支持"));
    assertFalse("未点开始前不能写入 welcomed", new ConfigStore(page).isWelcomed());
    var shadow =
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowActivity>extract(page);
    assertNull(shadow.getNextStartedActivity());
    host.pause().stop().destroy();
  }

  @Test
  public void welcomedAndReadyEnvironmentGoesStraightToMain() {
    new ConfigStore(RuntimeEnvironment.getApplication()).setWelcomed(true);
    Environment.ready = true;
    var host = Robolectric.buildActivity(OnboardingActivity.class).create();
    OnboardingActivity page = host.get();
    var shadow =
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowActivity>extract(page);
    Intent next = shadow.getNextStartedActivity();
    assertNotNull(next);
    assertEquals(MainActivity.class.getName(), next.getComponent().getClassName());
    assertFalse("直达主界面不是首次部署完成，不能预约自动启动 Web", next.hasExtra(FirstRunWebStart.EXTRA));
    assertFalse("受限入口标记只由用户显式选择", next.getBooleanExtra("limited_entry", false));
    assertTrue((next.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0);
    assertTrue(page.isFinishing());
    host.destroy();
  }

  @Test
  public void unwelcomedEntryCannotSkipPastWelcomeEvenWithSavedStep() {
    Context app = RuntimeEnvironment.getApplication();
    app.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt("step", 3)
        .commit();
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    assertEquals(OnboardingActivity.WELCOME, page.step);
    page.show(OnboardingActivity.FINISH);
    assertEquals(OnboardingActivity.WELCOME, page.step);
    host.pause().stop().destroy();
  }

  /**
   * 回归：点“开始部署”后被清后台，进程重建时必须回到引导的原步骤（部署未完成），不能因为 welcomed
   * 已写入而直接进入主界面。welcomed 只在离开引导进入主界面时写入。
   */
  @Test
  public void processKillAfterDeployStartResumesOnboardingInsteadOfMain() {
    Context app = RuntimeEnvironment.getApplication();
    app.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(OnboardingActivity.KEY_DEPLOY_STARTED, true)
        .putInt("step", OnboardingActivity.PERMS)
        .commit();
    // 部署被打断：环境未就绪。即使就绪检查本身返回 true，未 welcomed 时也不能被转走。
    Environment.ready = true;
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    assertFalse(page.isFinishing());
    assertEquals(OnboardingActivity.PERMS, page.step);
    assertFalse("开始部署不写 welcomed", new ConfigStore(page).isWelcomed());
    var shadow =
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowActivity>extract(page);
    assertNull("引导未完成，不能转入主界面", shadow.getNextStartedActivity());
    host.pause().stop().destroy();
  }

  @Test
  public void rotationKeepsStepAndGoalSelection() {
    new ConfigStore(RuntimeEnvironment.getApplication()).setWelcomed(true);
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    page.show(OnboardingActivity.GOALS);
    page.goals.add(PermissionGoals.SCREEN);
    assertEquals(OnboardingActivity.GOALS, page.step);
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    var restoredHost =
        Robolectric.buildActivity(OnboardingActivity.class).create(saved).start().resume();
    OnboardingActivity restored = restoredHost.get();
    assertEquals(OnboardingActivity.GOALS, restored.step);
    assertTrue(restored.goals.contains(PermissionGoals.SCREEN));
    assertTrue("基础用途始终选中", restored.goals.contains(PermissionGoals.BASE));
    assertTrue(hasText(restored.findViewById(android.R.id.content), "2 / 4"));
    restored.getOnBackPressedDispatcher().onBackPressed();
    assertEquals("第 2 步返回上一步而不是退出", OnboardingActivity.WELCOME, restored.step);
    assertFalse(restored.isFinishing());
    restoredHost.pause().stop().destroy();
  }

  /** 动效只改 alpha / translation：入场与切步结束后正文落在静态位置；旋转重建不重演入场。 */
  @Test
  public void stepMotionSettlesToStaticStateAndRotationSkipsIntro() {
    new ConfigStore(RuntimeEnvironment.getApplication()).setWelcomed(true);
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    page.show(OnboardingActivity.GOALS);
    org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
    ViewGroup content = page.content;
    assertTrue(content.getChildCount() > 0);
    for (int i = 0; i < content.getChildCount(); i++) {
      View child = content.getChildAt(i);
      assertEquals(1f, child.getAlpha(), 0.001f);
      assertEquals(0f, child.getTranslationX(), 0.001f);
      assertEquals(0f, child.getTranslationY(), 0.001f);
    }
    assertEquals(1f, page.footer.getAlpha(), 0.001f);
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    var restoredHost = Robolectric.buildActivity(OnboardingActivity.class).create(saved).start();
    OnboardingActivity restored = restoredHost.get();
    // 未推进主循环：如果重建时播放了入场，正文此刻会是透明的。
    assertEquals(1f, restored.content.getChildAt(0).getAlpha(), 0.001f);
    restoredHost.resume().pause().stop().destroy();
  }

  /** 完成页与运行环境维护页共用紧凑底栏：部署中显示“更新中 x/5”进度面板，不再用禁用主按钮。 */
  @Test
  @Config(qualifiers = "w393dp-h873dp-xxhdpi")
  public void finishRunningUsesCompactProgressFooter() {
    deployStartedOnFinish();
    Monitor.view =
        DeployStages.view(DeployStages.Mode.INSTALL, DeployStages.Phase.RUNNING, 1, "", "", 7);
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    assertEquals(OnboardingActivity.FINISH, page.step);
    assertEquals(View.VISIBLE, page.findViewById(R.id.extract_busy).getVisibility());
    assertEquals(View.GONE, page.findViewById(R.id.extract_retry).getVisibility());
    TextView label = page.findViewById(R.id.extract_busy_label);
    assertFalse(label.getText().toString().isEmpty());
    assertEquals(
        page.deploy.counter(),
        ((TextView) page.findViewById(R.id.extract_busy_counter)).getText().toString());
    assertEquals(
        "进入受限主界面 · 查看日志与配置", ((Button) page.findViewById(R.id.extract_enter)).getText().toString());
    View root = page.findViewById(android.R.id.content);
    assertTrue(hasText(root, "维护记录"));
    assertTrue(hasText(root, "复制给支持"));
    assertTrue(hasText(root, "应急 DSH"));
    host.pause().stop().destroy();
  }

  /** 中断 / 失败：主操作保持“恢复中断维护 / 重试部署”，应急入口强调可见。 */
  @Test
  @Config(qualifiers = "w393dp-h873dp-xxhdpi")
  public void finishPendingShowsRecoverAndEmergencyEntry() {
    deployStartedOnFinish();
    Monitor.view =
        DeployStages.view(DeployStages.Mode.INSTALL, DeployStages.Phase.PENDING, 2, "", "", 7);
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    assertEquals(View.GONE, page.findViewById(R.id.extract_busy).getVisibility());
    Button retry = page.findViewById(R.id.extract_retry);
    assertEquals(View.VISIBLE, retry.getVisibility());
    assertFalse(retry.getText().toString().isEmpty());
    assertEquals(
        "启动应急 DSH", page.findViewById(R.id.extract_emergency).getContentDescription().toString());
    host.pause().stop().destroy();
  }

  /** 就绪后主操作只剩“进入主界面”，不再显示降级的主操作按钮。 */
  @Test
  @Config(qualifiers = "w393dp-h873dp-xxhdpi")
  public void finishReadyPromotesMainEntryAndHidesRetry() {
    deployStartedOnFinish();
    Monitor.ready = true;
    Monitor.view =
        DeployStages.view(DeployStages.Mode.INSTALL, DeployStages.Phase.SUCCEEDED, 3, "", "", 7);
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    assertEquals(View.GONE, page.findViewById(R.id.extract_retry).getVisibility());
    assertEquals(View.GONE, page.findViewById(R.id.extract_busy).getVisibility());
    assertEquals("进入主界面", ((Button) page.findViewById(R.id.extract_enter)).getText().toString());
    host.pause().stop().destroy();
  }

  /** 未开始部署（兜底）：主操作回到第 1 步，受限入口仍可进入且不写就绪标记。 */
  @Test
  @Config(qualifiers = "w393dp-h873dp-xxhdpi")
  public void finishIdleOffersBackToFirstStep() {
    deployStartedOnFinish();
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    Button retry = page.findViewById(R.id.extract_retry);
    assertEquals(View.VISIBLE, retry.getVisibility());
    assertEquals("回到第 1 步", retry.getText().toString());
    host.pause().stop().destroy();
  }

  /** 宽屏：受限入口在左、进度面板在右（与维护页一致）。 */
  @Test
  @Config(qualifiers = "w840dp-h1280dp-xxhdpi")
  public void wideFinishFooterPutsEntryLeftOfProgress() {
    deployStartedOnFinish();
    Monitor.view =
        DeployStages.view(DeployStages.Mode.INSTALL, DeployStages.Phase.RUNNING, 1, "", "", 7);
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    LinearLayout actions = page.findViewById(R.id.extract_actions);
    assertEquals(LinearLayout.HORIZONTAL, actions.getOrientation());
    assertEquals(0, actions.indexOfChild(page.findViewById(R.id.extract_enter)));
    assertEquals(View.VISIBLE, page.findViewById(R.id.extract_busy).getVisibility());
    host.pause().stop().destroy();
  }

  /** 1.3 倍字体：三个描边入口的图标移到文字上方，避免三列文字被挤成多行。 */
  @Test
  @Config(qualifiers = "w393dp-h873dp-xxhdpi")
  public void largeFontPutsLinkIconsAboveLabels() {
    Resources resources = RuntimeEnvironment.getApplication().getResources();
    Configuration config = new Configuration(resources.getConfiguration());
    config.fontScale = 1.3f;
    resources.updateConfiguration(config, resources.getDisplayMetrics());
    deployStartedOnFinish();
    Monitor.view =
        DeployStages.view(DeployStages.Mode.INSTALL, DeployStages.Phase.RUNNING, 1, "", "", 7);
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    Drawable[] icons =
        ((Button) page.findViewById(R.id.extract_logs)).getCompoundDrawablesRelative();
    assertNull(icons[0]);
    assertNotNull(icons[1]);
    host.pause().stop().destroy();
  }

  /** 英文界面复用同一套入口文案，不缓存语言。 */
  @Test
  @Config(qualifiers = "w393dp-h873dp-xxhdpi")
  public void finishFooterFollowsLanguage() {
    UiText.setLanguage("en");
    deployStartedOnFinish();
    Monitor.view =
        DeployStages.view(DeployStages.Mode.INSTALL, DeployStages.Phase.RUNNING, 1, "", "", 7);
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    View root = page.findViewById(android.R.id.content);
    assertTrue(hasText(root, "Record"));
    assertTrue(hasText(root, "Copy for support"));
    assertEquals(
        UiText.text("进入受限主界面 · 查看日志与配置"),
        ((Button) page.findViewById(R.id.extract_enter)).getText().toString());
    host.pause().stop().destroy();
  }

  private void deployStartedOnFinish() {
    RuntimeEnvironment.getApplication()
        .getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(OnboardingActivity.KEY_DEPLOY_STARTED, true)
        .putInt("step", OnboardingActivity.FINISH)
        .commit();
  }

  private static boolean hasText(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasText(group.getChildAt(i), text)) return true;
    return false;
  }
}
