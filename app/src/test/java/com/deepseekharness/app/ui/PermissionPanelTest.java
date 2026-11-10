package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.PermissionGoals;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
import org.robolectric.shadows.ShadowSettings;

/**
 * 真实 AppCompatActivity 宿主上的授权卡片组：构造、复查与推荐标记。只读系统状态，不点击任何外跳，
 * 不初始化 HarnessController（Root 开关回调才会用到，测试不触发）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PermissionPanelTest {
  public static final class Host extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
    }
  }

  private ActivityController<Host> host;
  private String previousLanguage;
  private Context app;

  @Before
  public void prepare() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    app = RuntimeEnvironment.getApplication();
    // 首次安装：七类授权全部未开启（Robolectric 默认未授予“所有文件访问”）。
    ShadowSettings.setCanDrawOverlays(false);
    battery(false);
    org.robolectric.shadows.ShadowApplication application =
        org.robolectric.shadow.api.Shadow.extract(RuntimeEnvironment.getApplication());
    application.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    Settings.Secure.putString(
        app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
    app.getSharedPreferences(PermissionPanel.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    host = Robolectric.buildActivity(Host.class).setup();
  }

  /**
   * allowed=false：用户关闭了“允许后台使用”（后台受限，不计入已开启）；allowed=true：不受限制。
   * 系统默认的“允许后台（优化档）”也计入已开启，见 BatteryBackgroundStateTest。
   */
  private void battery(boolean allowed) {
    org.robolectric.shadows.ShadowPowerManager power =
        org.robolectric.shadow.api.Shadow.extract(app.getSystemService(PowerManager.class));
    power.setIgnoringBatteryOptimizations(app.getPackageName(), allowed);
    org.robolectric.shadows.ShadowActivityManager activities =
        org.robolectric.shadow.api.Shadow.extract(
            app.getSystemService(android.app.ActivityManager.class));
    activities.setBackgroundRestricted(!allowed);
  }

  private static Intent startedActivity(AppCompatActivity activity) {
    org.robolectric.shadows.ShadowActivity shadow =
        org.robolectric.shadow.api.Shadow.extract(activity);
    return shadow.getNextStartedActivity();
  }

  @After
  public void close() {
    host.pause().stop().destroy();
    UiText.setLanguage(previousLanguage);
  }

  private PermissionPanel panel(Set<String> goals) {
    PermissionPanel panel = new PermissionPanel(host.get(), goals, false);
    host.get().setContentView(panel.view());
    ShadowLooper.idleMainLooper();
    return panel;
  }

  private static List<String> texts(View view) {
    List<String> out = new ArrayList<>();
    collect(view, out);
    return out;
  }

  private static void collect(View view, List<String> out) {
    if (view instanceof TextView) out.add(String.valueOf(((TextView) view).getText()));
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        collect(((ViewGroup) view).getChildAt(i), out);
  }

  private static int count(List<String> texts, String value) {
    int n = 0;
    for (String text : texts) if (text.equals(value)) n++;
    return n;
  }

  @Test
  public void freshInstallBuildsAndRefreshesWithNothingGranted() {
    PermissionPanel panel = panel(new LinkedHashSet<>(Set.of(PermissionGoals.BASE)));
    assertEquals(0, panel.grantedCount());
    panel.refresh();
    panel.setEnvironmentReady(true);
    panel.setEnvironmentReady(false);
    ShadowLooper.idleMainLooper();
    assertEquals(0, panel.grantedCount());
    List<String> texts = texts(panel.view());
    assertTrue(texts.contains("已开启 0 / 6"));
    // 没有选择用途时不出现任何推荐标记。
    assertEquals(0, count(texts, "按你的用途推荐"));
    assertEquals(0, count(texts, "推荐"));
    // 构造与复查不得外跳到任何系统页面。
    assertNull(startedActivity(host.get()));
  }

  @Test
  public void summaryKeepsTheContractOrderAndFlags() {
    List<String[]> summary = panel(new LinkedHashSet<>()).summary();
    // 引导与完成页不再展示 Root：只列 6 项。
    assertEquals(6, summary.size());
    String[] names = {"存储读写", "Shizuku / Stellar", "无障碍", "通知", "悬浮窗", "电池优化"};
    for (int i = 0; i < names.length; i++) {
      assertEquals(names[i], summary.get(i)[0]);
      assertEquals("0", summary.get(i)[1]);
    }
  }

  @Test
  public void returningFromSettingsRecountsSystemState() {
    PermissionPanel panel = panel(new LinkedHashSet<>());
    ShadowSettings.setCanDrawOverlays(true);
    battery(true);
    panel.refresh();
    assertEquals(2, panel.grantedCount());
    List<String[]> summary = panel.summary();
    assertEquals("1", summary.get(4)[1]);
    assertEquals("1", summary.get(5)[1]);
    assertTrue(texts(panel.view()).stream().anyMatch(t -> t.contains("已开启 2")));
  }

  @Test
  public void selectedGoalsMarkRecommendedCardsAndGroups() {
    Set<String> goals = new LinkedHashSet<>(List.of(PermissionGoals.BASE, PermissionGoals.SCREEN));
    PermissionPanel panel = panel(goals);
    List<String> texts = texts(panel.view());
    // 屏幕操作推荐无障碍、悬浮窗、通知三项：只列授权项，不显示分类标题、分类说明或推荐标签。
    assertEquals(0, count(texts, "按你的用途推荐"));
    assertEquals(0, count(texts, "推荐"));
    assertTrue(texts.contains("推荐 0 / 3"));
    for (String category : List.of("基础", "文件", "屏幕操作（手机任务）"))
      assertFalse(category, texts.contains(category));
    assertTrue(texts.contains("通知"));
    assertTrue(texts.contains("无障碍"));
    assertTrue(texts.contains("悬浮窗"));
    // 未推荐的项收进“更多授权”，默认不显示。
    assertFalse(texts.contains("存储读写"));
    assertFalse(texts.contains("设备执行通道"));
    assertTrue(texts.contains("更多授权 · 3 项"));
    // 面板持有宿主的同一个 Set：就地增删后重绘即按新用途重排。
    goals.add(PermissionGoals.SHELL);
    panel.refresh();
    texts = texts(panel.view());
    assertTrue(texts.contains("推荐 0 / 4"));
    assertTrue(texts.contains("设备执行通道"));
    assertTrue(texts.contains("更多授权 · 2 项"));
    // 展开后未推荐的项才出现。
    View toggle = find(panel.view(), "更多授权 · 2 项");
    assertNotNull(toggle);
    toggle.performClick();
    texts = texts(panel.view());
    assertTrue(texts.contains("存储读写"));
    assertTrue(texts.contains("电池优化"));
    assertTrue(texts.contains("收起"));
  }

  @Test
  public void baseOnlyGoalShowsEverythingAsOptionalWithoutCollapse() {
    PermissionPanel panel = panel(new LinkedHashSet<>(List.of(PermissionGoals.BASE)));
    List<String> texts = texts(panel.view());
    assertTrue(texts.contains("你选的用途不需要授权，可直接继续。"));
    assertEquals(0, count(texts, "按你的用途推荐"));
    // 没有推荐项时全部直接列出，不再折叠。
    assertTrue(texts.contains("存储读写"));
    assertTrue(texts.contains("设备执行通道"));
    assertFalse(texts.stream().anyMatch(t -> t.startsWith("更多授权")));
    assertEquals(0, panel.recommendedTotal());
  }

  @Test
  public void unknownAccessibilityStateIsNotReportedAsOff() {
    Settings.Secure.putString(
        app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, null);
    PermissionPanel panel = panel(new LinkedHashSet<>());
    List<String> texts = texts(panel.view());
    // 读不到设置时提示“无法确认”，不报成未开启。
    assertTrue(texts.contains("暂时无法确认状态"));
    assertEquals(0, panel.grantedCount());
  }

  @Test
  public void accessibilityStepsUseTheSystemServiceLabel() {
    String label = app.getString(R.string.accessibility_service_label);
    String step =
        AccessibilitySetupActivity.withServiceName(
            app, UiText.choose("请打开系统设置 → 无障碍 →「%s」。", "Open Settings → Accessibility → “%s”."));
    assertTrue(step.contains(label));
    assertFalse(step.contains("配对助手"));
  }

  private static List<String> registeredResultKeys(AppCompatActivity activity) {
    Bundle saved = new Bundle();
    activity.getActivityResultRegistry().onSaveInstanceState(saved);
    List<String> keys = saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS");
    List<String> ours = new ArrayList<>();
    if (keys != null)
      for (String key : keys) if (key.startsWith("dsha-onboarding-notifications-")) ours.add(key);
    return ours;
  }

  @Test
  public void notificationRequestIsRegisteredAfterStartAndReleasedOnDestroy() {
    // 宿主已经 RESUMED 后才构造：注册不依赖 STARTED 之前，也不会因此抛异常。
    panel(new LinkedHashSet<>());
    assertEquals(1, registeredResultKeys(host.get()).size());
    Host destroyed = host.get();
    host.pause().stop().destroy();
    assertTrue(registeredResultKeys(destroyed).isEmpty());
    host = Robolectric.buildActivity(Host.class).setup();
  }

  @Test
  public void accessibilityDisconnectIsHeldBrieflySoPollingDoesNotRebuild() {
    PermissionPanel panel = panel(new LinkedHashSet<>());
    assertFalse(panel.accessibilityConnectedForDisplay());
    // 无服务实例时保持未连接；防抖只在曾经连上后才生效。
    assertEquals(0, panel.grantedCount());
  }

  @Test
  public void onlyAClickLeavesDsha() {
    PermissionPanel panel = panel(new LinkedHashSet<>(Set.of(PermissionGoals.FILES)));
    host.pause();
    host.resume();
    ShadowLooper.idleMainLooper();
    assertNull(startedActivity(host.get()));
    // 主操作在行内，短文案“去开启”，读屏描述保留完整说法。
    View button = findByDescription(panel.view(), "去开启所有文件访问");
    assertNotNull(button);
    assertEquals("去开启", String.valueOf(((TextView) button).getText()));
    button.performClick();
    Intent started = startedActivity(host.get());
    assertNotNull(started);
    assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, started.getAction());
    assertEquals("package:" + app.getPackageName(), started.getDataString());
  }

  private static View findByDescription(View view, String description) {
    if (view.getContentDescription() != null
        && description.contentEquals(view.getContentDescription())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View hit = findByDescription(((ViewGroup) view).getChildAt(i), description);
        if (hit != null) return hit;
      }
    return null;
  }

  private static final String STORAGE_WHY = "读写 /sdcard 下的项目和文件。不开也能用，仅限私有目录。";

  /** 行的读屏描述以名称开头，按名称找到可点击的那一行。 */
  private static View row(View view, String title) {
    CharSequence d = view.getContentDescription();
    if (view.isClickable() && d != null && d.toString().startsWith(title + "，")) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View hit = row(((ViewGroup) view).getChildAt(i), title);
        if (hit != null) return hit;
      }
    return null;
  }

  @Test
  public void rowsStartCollapsedAndExpandOnTapWithoutLeavingDsha() {
    PermissionPanel panel = panel(new LinkedHashSet<>(Set.of(PermissionGoals.FILES)));
    List<String> texts = texts(panel.view());
    // 折叠：只有名称与“开了能做什么”，说明和跳转提示不在视图树里。
    assertTrue(texts.contains("存储读写"));
    assertTrue(texts.contains("读写 /sdcard"));
    assertFalse(texts.contains(STORAGE_WHY));
    View storage = row(panel.view(), "存储读写");
    assertNotNull(storage);
    assertEquals("已收起", String.valueOf(storage.getStateDescription()));
    storage.performClick();
    ShadowLooper.idleMainLooper();
    texts = texts(panel.view());
    assertTrue(texts.contains(STORAGE_WHY));
    assertEquals("已展开", String.valueOf(row(panel.view(), "存储读写").getStateDescription()));
    // 展开区不重复行内主操作。
    assertEquals(1, countDescriptions(panel.view(), "去开启所有文件访问"));
    assertNull(find(panel.view(), "去开启所有文件访问"));
    // 轮询式重绘不收回用户展开的行。
    panel.refresh();
    assertTrue(texts(panel.view()).contains(STORAGE_WHY));
    // 再点一次收起；展开 / 收起都不外跳。
    row(panel.view(), "存储读写").performClick();
    assertFalse(texts(panel.view()).contains(STORAGE_WHY));
    assertNull(startedActivity(host.get()));
  }

  @Test
  public void grantedRowShowsOnlyItsStatusAndCollapsesAfterReturning() {
    Set<String> goals = new LinkedHashSet<>(List.of(PermissionGoals.SCREEN));
    PermissionPanel panel = panel(goals);
    View overlay = row(panel.view(), "悬浮窗");
    assertNotNull(overlay);
    overlay.performClick();
    assertTrue(texts(panel.view()).contains("显示悬浮条、命令批准和手机任务状态窗。"));
    // 从系统设置授权后回来：该行回到折叠，只剩名称 + “已允许”。
    ShadowSettings.setCanDrawOverlays(true);
    panel.refresh();
    List<String> texts = texts(panel.view());
    assertFalse(texts.contains("显示悬浮条、命令批准和手机任务状态窗。"));
    assertTrue(texts.contains("已允许"));
    assertFalse(texts.contains("悬浮条与状态窗"));
    assertNull(findByDescription(panel.view(), "去允许悬浮窗"));
    assertTrue(texts.contains("推荐 1 / 3"));
  }

  private static int countDescriptions(View view, String description) {
    int n =
        view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription())
            ? 1
            : 0;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        n += countDescriptions(((ViewGroup) view).getChildAt(i), description);
    return n;
  }

  private static View find(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View hit = find(((ViewGroup) view).getChildAt(i), text);
        if (hit != null) return hit;
      }
    return null;
  }
}
