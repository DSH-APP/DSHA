package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.UpdateEngine;
import com.deepseekharness.app.core.UpdateRepository;
import com.deepseekharness.app.util.CurrentReleaseNotes;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.UpdateActionPolicy;
import com.deepseekharness.app.util.UpdatePolicy;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDialog;

/** Actual version page, bundled resource and opened notes; transport never runs in this fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    qualifiers = "zh",
    application = UpdateVersionPageTest.App.class,
    shadows = UpdateVersionPageTest.Engine.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class UpdateVersionPageTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = UpdateEngine.class, isInAndroidSdk = false)
  public static final class Engine {
    static MutableLiveData<UpdateRepository.State> states;
    static int checks;

    @Implementation
    protected static UpdateEngine get(Context context) {
      return Shadow.newInstanceOf(UpdateEngine.class);
    }

    @Implementation
    protected androidx.lifecycle.LiveData<UpdateRepository.State> state() {
      return states;
    }

    @Implementation
    protected String channel() {
      return "preview";
    }

    @Implementation
    protected boolean hasTask() {
      return true;
    }

    @Implementation
    protected void setChannel(String channel) {}

    @Implementation
    protected void check() {
      checks++;
    }
  }

  private String previousLanguage;

  @Before
  public void prepare() throws Exception {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    Engine.checks = 0;
    Engine.states = new MutableLiveData<>(state("暂无可用更新"));
  }

  @After
  public void restore() {
    UiText.setLanguage(previousLanguage);
  }

  private static UpdateRepository.State state(String message) throws Exception {
    var constructor =
        UpdateRepository.State.class.getDeclaredConstructor(
            String.class,
            UpdateRepository.Stage.class,
            long.class,
            long.class,
            com.deepseekharness.app.util.UpdatePolicy.Release.class,
            File.class,
            String.class,
            String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        message, UpdateRepository.Stage.IDLE, 0L, 0L, null, null, "preview", null);
  }

  @Test
  public void currentRowAndActuallyOpenedNotesUseTheInstalledIdentity() throws IOException {
    try (var controller = Robolectric.buildActivity(UpdateActivity.class).setup()) {
      UpdateActivity activity = controller.get();
      assertEquals(
          CurrentReleaseNotes.versionLabel(BuildConfig.VERSION_NAME)
              + " · build"
              + BuildConfig.VERSION_CODE,
          ((TextView) activity.findViewById(R.id.update_changelog_summary)).getText().toString());
      activity.findViewById(R.id.update_changelog).performClick();
      Dialog dialog = ShadowDialog.getLatestDialog();
      assertNotNull(dialog);
      String shown = text(dialog.getWindow().getDecorView());
      assertCurrentNotesShown(activity, shown);
      for (String language : UiLanguagePreference.LANGUAGES)
        bundledNotes(inLanguage(activity, language));
      assertTrue(shown.contains("dsh-web-mobile"));
      assertTrue(shown.contains("3.3.5"));
      assertTrue(shown.contains("版本码提升为 356"));
      assertFalse(shown.contains("版本码提升为 355"));
      assertFalse(shown.contains("版本码提升为 354"));
      assertFalse(shown.contains("版本码提升为 353"));
      assertFalse(shown.contains("版本码提升为 352"));
      assertFalse(shown.contains("版本码提升为 351"));
      assertFalse(shown.contains("版本码提升为 350"));
      assertFalse(shown.contains("版本码提升为 349"));
      assertFalse(shown.contains("版本码提升为 348"));
      assertFalse(shown.contains("版本码提升为 347"));
      assertFalse(shown.contains("版本码提升为 346"));
      assertFalse(shown.contains("版本码提升为 345"));
      assertFalse(shown.contains("版本码提升为 344"));
      assertFalse(shown.contains("版本码提升为 342"));
      assertFalse(shown.contains("版本码提升为 341"));
      assertFalse(shown.contains("版本码提升为 340"));
      assertFalse(shown.contains("版本码提升为 339"));
      assertFalse(shown.contains("版本码提升为 338"));
      assertFalse(shown.contains("版本码提升为 337"));
      assertFalse(shown.contains("版本码提升为 336"));
      assertFalse(shown.contains("版本码提升为 334"));
      assertFalse(shown.contains("MAINTENANCE_TASK_START"));
      assertFalse(shown.contains("跨应用文件访问"));
      assertFalse(shown.contains("管理文件访问权限"));
      assertFalse(shown.contains("DisclosureCard"));
      assertFalse(shown.contains("ShellStatusPill"));
      assertFalse(shown.contains("trackTint"));
      assertFalse(shown.contains("f65796a2"));
      assertFalse(shown.contains("PowerModePolicy"));
      assertFalse(shown.contains("ScreenActivityFeed"));
      assertFalse(shown.contains("recentActivity"));
      assertFalse(shown.contains("android_history"));
      assertFalse(shown.contains("7b1ca97d"));
      assertFalse(shown.contains("屏幕常亮"));
      assertFalse(shown.contains("省电模式"));
      assertFalse(shown.contains("SystemDocumentPicker"));
      assertFalse(shown.contains("ShellChannelStatusPill"));
      assertFalse(shown.contains("RecentsPrivacyGuard"));
      assertFalse(shown.contains("28dc573a"));
      assertFalse(shown.contains("Android 13"));
      assertFalse(shown.contains("系统文件选择器"));
      assertFalse(shown.contains("严格模式"));
      assertFalse(shown.contains("SheetGeometry"));
      assertFalse(shown.contains("bottomDrawerMaxHeight"));
      assertFalse(shown.contains("PluginIdentity"));
      assertFalse(shown.contains("legacyShim"));
      assertFalse(shown.contains("PluginCardState"));
      assertFalse(shown.contains("5fec6de5"));
      assertFalse(shown.contains("5ff08d58"));
      assertFalse(shown.contains("6f31f111"));
      assertFalse(shown.contains("b8c59df9"));
      assertTrue(shown.contains("8e54085c"));
      assertFalse(shown.contains("Auto review"));
      assertFalse(shown.contains("0.72"));
      assertFalse(shown.contains("语义色"));
      assertFalse(shown.contains("18%"));
      assertFalse(shown.contains("contentDescription"));
      assertFalse(shown.contains("identityHashCode"));
      assertFalse(shown.contains("shell_raised"));
      assertFalse(shown.contains("ASK_USER_TO_CLOSE_SPLIT_SCREEN"));
      assertFalse(shown.contains("VSCREEN_WINDOW_AMBIGUOUS"));
      assertFalse(shown.contains("SENSITIVE_SCREEN_HANDOFF"));
      assertFalse(shown.contains("sensitive_screen_v1"));
      assertFalse(shown.contains("OriginOS 7"));
      assertFalse(shown.contains("/app/priv"));
      assertFalse(shown.contains("PhysicalScreenManager"));
      assertFalse(shown.contains("privileged_execution_v1"));
      assertFalse(shown.contains("SECURE_OR_BLACK"));
      assertFalse(shown.contains("PRIV_SENSITIVE_TARGET_UNSUPPORTED"));
      assertFalse(shown.contains("646fe837"));
      assertFalse(shown.contains("/app/extdev/watch"));
      assertFalse(shown.contains("ExternalWatchPolicy"));
      assertFalse(shown.contains("PLUGIN_PROFILE_LOCK_STALE"));
      assertFalse(shown.contains("GUEST_TIMING_MISMATCH"));
      assertFalse(shown.contains("register-managed-asset-id"));
      assertFalse(shown.contains("479409"));
      assertFalse(shown.contains("MaintenanceRegistration"));
      assertFalse(shown.contains("MAINTENANCE_REGISTER_DEFERRED"));
      assertFalse(shown.contains("TestPackageFeedbackExporter"));
      assertFalse(shown.contains("PARENT_LINK"));
      assertFalse(shown.contains("getCanonicalFile"));
      assertFalse(shown.contains("QuickActionsBubble"));
      assertFalse(shown.contains("DeviceChannelsFragment"));
      assertFalse(shown.contains("ScreenOperationFragment"));
      assertFalse(shown.contains("GrantSummary"));
      assertFalse(shown.contains("SettingsPanes"));
      assertTrue(shown.contains("T143"));
      assertTrue(shown.contains("d3c07b4f8"));
      assertTrue(shown.contains("ShellCard"));
      assertTrue(shown.contains("selectableItemBackground"));
      assertTrue(shown.contains("shell_section_margin"));
      assertTrue(shown.contains("fragment_settings"));
      assertTrue(shown.contains("plugin_list_footer"));
      assertFalse(shown.contains("dry_run"));
      assertFalse(shown.contains("test_packages"));
      assertFalse(shown.contains("DeterminateProgress"));
      assertFalse(shown.contains("FiniteSkeleton"));
      assertFalse(shown.contains("StaggerList"));
      assertFalse(shown.contains("CardTabs"));
      assertFalse(shown.contains("T88"));
      assertFalse(shown.contains("T115"));
      assertFalse(shown.contains("T116"));
      assertFalse(shown.contains("5918af63"));
      assertFalse(shown.contains("a54f9854"));
      assertFalse(shown.contains("版本码提升为 332"));
      assertFalse(shown.contains("GIT_PROROOT_SIGSEGV"));
      assertFalse(shown.contains("git_guard.py"));
      assertFalse(shown.contains("libmonochrome"));
      assertFalse(shown.contains("VSCREEN_SELF_LAUNCH_REFUSED"));
      assertFalse(shown.contains("版本码提升为 331"));
      assertFalse(shown.contains("GitHub 登录"));
      assertFalse(shown.contains("受管 CA"));
      assertFalse(shown.contains("版本码提升为 330"));
      assertFalse(shown.contains("版本码提升为 329"));
      assertFalse(shown.contains("gh 纳入受管资产"));
      assertFalse(shown.contains("默认禁用"));
      assertFalse(shown.contains("同一 DSH 版本"));
      assertFalse(shown.contains("版本码提升为 327"));
      assertFalse(shown.contains("技能调用确认"));
      assertFalse(shown.contains("版本码提升为 324"));
      assertFalse(shown.contains("DSH 0.2.2 升级第一步"));
      assertFalse(shown.contains("版本码提升为 323"));
      assertFalse(shown.contains("插件卸载：修复"));
      assertFalse(shown.contains("版本码提升为 322"));
      assertFalse(shown.contains("DSH 0.2.2 升级预备"));
      assertFalse(shown.contains("版本码提升为 321"));
      assertFalse(shown.contains("屏幕常亮：设置页新增"));
      assertFalse(shown.contains("版本码提升为 320"));
      assertFalse(shown.contains("剪贴板读取：新增"));
      assertFalse(shown.contains("版本码提升为 319"));
      assertFalse(shown.contains("技能页：可单独禁用"));
      assertFalse(shown.contains("版本码提升为 318"));
      assertFalse(shown.contains("gh 适配：包装脚本"));
      assertFalse(shown.contains("版本码提升为 317"));
      assertFalse(shown.contains("CAS 扫描快路径复核"));
      assertFalse(shown.contains("版本码提升为 316"));
      assertFalse(shown.contains("运行时准备缓存"));
      assertFalse(shown.contains("版本码提升为 315"));
      assertFalse(shown.contains("不对临时插件显示删除"));
      assertFalse(shown.contains("版本码提升为 314"));
      assertFalse(shown.contains("渲染进程退出后回收"));
      assertFalse(shown.contains("版本码提升为 313"));
      assertFalse(shown.contains("大目录流式列出"));
      assertFalse(shown.contains("版本码提升为 312"));
      assertFalse(shown.contains("不再被旧 PID 翻回未知"));
      assertFalse(shown.contains("版本码提升为 311"));
      assertFalse(shown.contains("保留环境保护"));
      assertFalse(shown.contains("版本码提升为 310"));
      assertFalse(shown.contains("固定目标设备"));
      assertFalse(shown.contains("版本码提升为 309"));
      assertFalse(shown.contains("动作按钮超过系统上限"));
      assertFalse(shown.contains("版本码提升为 308"));
      assertFalse(shown.contains("紧凑两行条目"));
      assertFalse(shown.contains("版本码提升为 307"));
      assertFalse(shown.contains("竖屏底部导航"));
      assertFalse(shown.contains("版本码提升为 306"));
      assertFalse(shown.contains("主 tab"));
      assertFalse(shown.contains("版本码提升为 305"));
      assertFalse(shown.contains("ADB/root 通道"));
      assertFalse(shown.contains("版本码提升为 304"));
      assertFalse(shown.contains("弹层按窗口宽、高"));
      assertFalse(shown.contains("版本码提升为 303"));
      assertFalse(shown.contains("300 秒"));
      assertFalse(shown.contains("版本码提升为 302"));
      assertFalse(shown.contains("首帧等待"));
      assertFalse(shown.contains("版本码提升为 301"));
      assertFalse(shown.contains("dsh-fs-read-hint"));
      assertFalse(shown.contains("版本码提升为 300"));
      assertFalse(shown.contains("版本协商"));
      assertFalse(shown.contains("版本码提升为 299"));
      assertFalse(shown.contains("仅更新文档"));
      assertFalse(shown.contains("版本码提升为 298"));
      assertFalse(shown.contains("seccomp 过滤"));
      assertFalse(shown.contains("版本码提升为 297"));
      assertFalse(shown.contains("识屏模式"));
      assertFalse(shown.contains("版本码提升为 296"));
      assertFalse(shown.contains("版本码提升为 295"));
      assertFalse(shown.contains("版本码提升为 294"));
      assertFalse(shown.contains("版本码提升为 293"));
      assertFalse(shown.contains("支持的设备"));
      assertFalse(shown.contains("版本码提升为 292"));
      assertFalse(shown.contains("一键备份"));
      assertFalse(shown.contains("版本码提升为 291"));
      assertFalse(shown.contains("网络健康"));
      assertFalse(shown.contains("版本码提升为 290"));
      assertFalse(shown.contains("阶段分段条"));
      assertFalse(shown.contains("版本码提升为 289"));
      assertFalse(shown.contains("版本码提升为 288"));
      assertFalse(shown.contains("直进 Web"));
      assertFalse(shown.contains("版本码提升为 287"));
      assertFalse(shown.contains("/app/install"));
      assertFalse(shown.contains("版本码提升为 286"));
      assertFalse(shown.contains("说明弹窗"));
      assertFalse(shown.contains("版本码提升为 285"));
      assertFalse(shown.contains("错误面板"));
      assertFalse(shown.contains("版本码提升为 284"));
      assertFalse(shown.contains("原生加载页"));
      assertFalse(shown.contains("版本码提升为 283"));
      assertFalse(shown.contains("插件页可见"));
      assertFalse(shown.contains("版本码提升为 282"));
      assertFalse(shown.contains("正在检查运行环境"));
      assertFalse(shown.contains("版本码提升为 281"));
      assertFalse(shown.contains("不再回退 3090"));
      assertFalse(shown.contains("版本码提升为 280"));
      assertFalse(shown.contains("启动恢复页"));
      assertFalse(shown.contains("版本码提升为 279"));
      assertFalse(shown.contains("后台任务页"));
      assertFalse(shown.contains("版本码提升为 278"));
      assertFalse(shown.contains("数据救援入口"));
      assertFalse(shown.contains("版本码提升为 277"));
      assertFalse(shown.contains("首次引导"));
      assertFalse(shown.contains("版本码提升为 276"));
      assertFalse(shown.contains("已就绪 · 用时"));
      assertFalse(shown.contains("版本码提升为 275"));
      assertFalse(shown.contains("主题与外观"));
      assertFalse(shown.contains("版本码提升为 274"));
      assertFalse(shown.contains("保存栏不再被底部导航胶囊盖住"));
      assertFalse(shown.contains("版本码提升为 273"));
      assertFalse(shown.contains("启动日志区原生重做"));
      assertFalse(shown.contains("版本码提升为 272"));
      assertFalse(shown.contains("版本码提升为 271"));
      assertFalse(shown.contains("任务状态上岛页重绘"));
      assertFalse(shown.contains("版本码提升为 270"));
      assertFalse(shown.contains("下载记录页重写"));
      assertFalse(shown.contains("版本码提升为 269"));
      assertFalse(shown.contains("设备能力授权页压缩"));
      assertFalse(shown.contains("版本码提升为 268"));
      assertFalse(shown.contains("切到移动数据"));
      assertFalse(shown.contains("版本码提升为 267"));
      assertFalse(shown.contains("悬浮助手重构"));
      assertFalse(shown.contains("版本码提升为 266"));
      assertFalse(shown.contains("维护页底栏"));
      assertFalse(shown.contains("版本码提升为 265"));
      assertFalse(shown.contains("版本更新页压缩"));
      assertFalse(shown.contains("版本码提升为 264"));
      assertFalse(shown.contains("关闭终端标签"));
      assertFalse(shown.contains("版本码提升为 263"));
      assertFalse(shown.contains("识屏预览"));
      assertFalse(shown.contains("版本码提升为 262"));
      assertFalse(shown.contains("终端粘贴长文本"));
      assertFalse(shown.contains("版本码提升为 261"));
      assertFalse(shown.contains("运行配置页压缩"));
      assertFalse(shown.contains("版本码提升为 260"));
      assertFalse(shown.contains("矢量图标"));
      assertFalse(shown.contains("版本码提升为 259"));
      assertFalse(shown.contains("开机加载页"));
      assertFalse(shown.contains("版本码提升为 258"));
      assertFalse(shown.contains("proroot 文件锁修复"));
      assertFalse(shown.contains("版本码提升为 257"));
      assertFalse(shown.contains("数据与备份二级页重构"));
      assertFalse(shown.contains("版本码提升为 256"));
      assertFalse(shown.contains("维护页底部操作区"));
      assertFalse(shown.contains("版本码提升为 255"));
      assertFalse(shown.contains("安装与环境页压缩"));
      assertFalse(shown.contains("版本码提升为 254"));
      assertFalse(shown.contains("终端界面精修"));
      assertFalse(shown.contains("版本码提升为 253"));
      assertFalse(shown.contains("自检与诊断页简化"));
      assertFalse(shown.contains("版本码提升为 252"));
      assertFalse(shown.contains("插件页头部重构"));
      assertFalse(shown.contains("版本码提升为 251"));
      assertFalse(shown.contains("修复配置快照删不掉"));
      assertFalse(shown.contains("版本码提升为 250"));
      assertFalse(shown.contains("版本码提升为 248"));
      assertFalse(shown.contains("插件页卡片重构"));
      assertFalse(shown.contains("版本码提升为 247"));
      assertFalse(shown.contains("压缩为一张单列表"));
      assertFalse(shown.contains("版本身份更新为 versionCode 249"));
      assertFalse(shown.contains("plugin-install-quarantine"));
      assertFalse(shown.contains("版本码提升为 242"));
      assertFalse(shown.contains("版本码提升为 220"));
      assertFalse(shown.contains("build157"));
      assertFalse(shown.contains("0.2.0-rc2"));
      dialog.dismiss();
    }
  }

  @Test
  @Config(qualifiers = "en")
  public void englishNotesOpenTheEnglishCurrentResource() throws IOException {
    UiText.setLanguage("en");
    try (var controller = Robolectric.buildActivity(UpdateActivity.class).setup()) {
      controller.get().findViewById(R.id.update_changelog).performClick();
      Dialog dialog = ShadowDialog.getLatestDialog();
      assertNotNull(dialog);
      String shown = text(dialog.getWindow().getDecorView());
      String englishBody = assertCurrentNotesShown(controller.get(), shown);
      assertNotEquals(
          "English notes must resolve a localized body rather than the default Chinese resource",
          body(bundledNotes(inLanguage(controller.get(), "zh"))),
          englishBody);
      assertTrue(shown.contains("dsh-web-mobile"));
      assertTrue(shown.contains("3.3.5"));
      assertTrue(shown.contains("versionCode raised to 356"));
      assertFalse(shown.contains("versionCode raised to 355"));
      assertFalse(shown.contains("versionCode raised to 354"));
      assertFalse(shown.contains("versionCode raised to 353"));
      assertFalse(shown.contains("versionCode raised to 352"));
      assertFalse(shown.contains("versionCode raised to 351"));
      assertFalse(shown.contains("versionCode raised to 350"));
      assertFalse(shown.contains("versionCode raised to 349"));
      assertFalse(shown.contains("versionCode raised to 348"));
      assertFalse(shown.contains("versionCode raised to 347"));
      assertFalse(shown.contains("versionCode raised to 346"));
      assertFalse(shown.contains("versionCode raised to 345"));
      assertFalse(shown.contains("versionCode raised to 344"));
      assertFalse(shown.contains("versionCode raised to 342"));
      assertFalse(shown.contains("versionCode raised to 341"));
      assertFalse(shown.contains("versionCode raised to 340"));
      assertFalse(shown.contains("versionCode raised to 339"));
      assertFalse(shown.contains("versionCode raised to 338"));
      assertFalse(shown.contains("versionCode raised to 337"));
      assertFalse(shown.contains("versionCode raised to 336"));
      assertFalse(shown.contains("Raises versionCode to 334"));
      assertFalse(shown.contains("MAINTENANCE_TASK_START"));
      assertFalse(shown.contains("Cross-app file access"));
      assertFalse(shown.contains("Manage file access"));
      assertFalse(shown.contains("DisclosureCard"));
      assertFalse(shown.contains("ShellStatusPill"));
      assertFalse(shown.contains("trackTint"));
      assertFalse(shown.contains("f65796a2"));
      assertFalse(shown.contains("PowerModePolicy"));
      assertFalse(shown.contains("ScreenActivityFeed"));
      assertFalse(shown.contains("recentActivity"));
      assertFalse(shown.contains("android_history"));
      assertFalse(shown.contains("7b1ca97d"));
      assertFalse(shown.contains("Keep screen on"));
      assertFalse(shown.contains("power saving"));
      assertFalse(shown.contains("SystemDocumentPicker"));
      assertFalse(shown.contains("ShellChannelStatusPill"));
      assertFalse(shown.contains("RecentsPrivacyGuard"));
      assertFalse(shown.contains("28dc573a"));
      assertFalse(shown.contains("Android 13"));
      assertFalse(shown.contains("system file picker"));
      assertFalse(shown.contains("strict mode"));
      assertFalse(shown.contains("SheetGeometry"));
      assertFalse(shown.contains("bottomDrawerMaxHeight"));
      assertFalse(shown.contains("PluginIdentity"));
      assertFalse(shown.contains("legacyShim"));
      assertFalse(shown.contains("PluginCardState"));
      assertFalse(shown.contains("5fec6de5"));
      assertFalse(shown.contains("5ff08d58"));
      assertFalse(shown.contains("6f31f111"));
      assertFalse(shown.contains("b8c59df9"));
      assertTrue(shown.contains("8e54085c"));
      assertFalse(shown.contains("Auto review"));
      assertFalse(shown.contains("0.72"));
      assertFalse(shown.contains("semantic-color badges"));
      assertFalse(shown.contains("18%"));
      assertFalse(shown.contains("contentDescription"));
      assertFalse(shown.contains("identityHashCode"));
      assertFalse(shown.contains("shell_raised"));
      assertFalse(shown.contains("ASK_USER_TO_CLOSE_SPLIT_SCREEN"));
      assertFalse(shown.contains("VSCREEN_WINDOW_AMBIGUOUS"));
      assertFalse(shown.contains("SENSITIVE_SCREEN_HANDOFF"));
      assertFalse(shown.contains("sensitive_screen_v1"));
      assertFalse(shown.contains("OriginOS 7"));
      assertFalse(shown.contains("/app/priv"));
      assertFalse(shown.contains("PhysicalScreenManager"));
      assertFalse(shown.contains("privileged_execution_v1"));
      assertFalse(shown.contains("SECURE_OR_BLACK"));
      assertFalse(shown.contains("PRIV_SENSITIVE_TARGET_UNSUPPORTED"));
      assertFalse(shown.contains("646fe837"));
      assertFalse(shown.contains("/app/extdev/watch"));
      assertFalse(shown.contains("ExternalWatchPolicy"));
      assertFalse(shown.contains("PLUGIN_PROFILE_LOCK_STALE"));
      assertFalse(shown.contains("GUEST_TIMING_MISMATCH"));
      assertFalse(shown.contains("register-managed-asset-id"));
      assertFalse(shown.contains("479409"));
      assertFalse(shown.contains("MaintenanceRegistration"));
      assertFalse(shown.contains("MAINTENANCE_REGISTER_DEFERRED"));
      assertFalse(shown.contains("TestPackageFeedbackExporter"));
      assertFalse(shown.contains("PARENT_LINK"));
      assertFalse(shown.contains("getCanonicalFile"));
      assertFalse(shown.contains("QuickActionsBubble"));
      assertFalse(shown.contains("DeviceChannelsFragment"));
      assertFalse(shown.contains("ScreenOperationFragment"));
      assertFalse(shown.contains("GrantSummary"));
      assertFalse(shown.contains("SettingsPanes"));
      assertTrue(shown.contains("T143"));
      assertTrue(shown.contains("d3c07b4f8"));
      assertTrue(shown.contains("ShellCard"));
      assertTrue(shown.contains("selectableItemBackground"));
      assertTrue(shown.contains("shell_section_margin"));
      assertTrue(shown.contains("fragment_settings"));
      assertTrue(shown.contains("plugin_list_footer"));
      assertFalse(shown.contains("dry_run"));
      assertFalse(shown.contains("test_packages"));
      assertFalse(shown.contains("DeterminateProgress"));
      assertFalse(shown.contains("FiniteSkeleton"));
      assertFalse(shown.contains("StaggerList"));
      assertFalse(shown.contains("CardTabs"));
      assertFalse(shown.contains("T88"));
      assertFalse(shown.contains("T115"));
      assertFalse(shown.contains("T116"));
      assertFalse(shown.contains("5918af63"));
      assertFalse(shown.contains("a54f9854"));
      assertFalse(shown.contains("Raises versionCode to 332"));
      assertFalse(shown.contains("GIT_PROROOT_SIGSEGV"));
      assertFalse(shown.contains("git_guard.py"));
      assertFalse(shown.contains("libmonochrome"));
      assertFalse(shown.contains("VSCREEN_SELF_LAUNCH_REFUSED"));
      assertFalse(shown.contains("Raises versionCode to 331"));
      assertFalse(shown.contains("GitHub login"));
      assertFalse(shown.contains("managed CA"));
      assertFalse(shown.contains("Raises versionCode to 330"));
      assertFalse(shown.contains("Raises versionCode to 329"));
      assertFalse(shown.contains("managed asset"));
      assertFalse(shown.contains("disabled by default"));
      assertFalse(shown.contains("the same DSH version"));
      assertFalse(shown.contains("Raises versionCode to 327"));
      assertFalse(shown.contains("Skill-call approval"));
      assertFalse(shown.contains("Raises versionCode to 324"));
      assertFalse(shown.contains("DSH 0.2.2 upgrade step one"));
      assertFalse(shown.contains("Raises versionCode to 323"));
      assertFalse(shown.contains("Plugin uninstall: fixes"));
      assertFalse(shown.contains("Raises versionCode to 322"));
      assertFalse(shown.contains("DSH 0.2.2 upgrade preparation"));
      assertFalse(shown.contains("Raises versionCode to 321"));
      assertFalse(shown.contains("Keep screen on: Settings adds"));
      assertFalse(shown.contains("Raises versionCode to 320"));
      assertFalse(shown.contains("Clipboard reading: adds"));
      assertFalse(shown.contains("Raises versionCode to 319"));
      assertFalse(shown.contains("Skills page: each skill"));
      assertFalse(shown.contains("Raises versionCode to 318"));
      assertFalse(shown.contains("gh adapter: the wrapper"));
      assertFalse(shown.contains("Raises versionCode to 317"));
      assertFalse(shown.contains("CAS scan fast path rechecks"));
      assertFalse(shown.contains("Raises versionCode to 316"));
      assertFalse(shown.contains("runtime preparation caching"));
      assertFalse(shown.contains("Raises versionCode to 315"));
      assertFalse(shown.contains("hide delete for temporary plugins"));
      assertFalse(shown.contains("Raises versionCode to 314"));
      assertFalse(shown.contains("reclaimed after the WebView renderer exits"));
      assertFalse(shown.contains("Raises versionCode to 313"));
      assertFalse(shown.contains("streams large folder listings"));
      assertFalse(shown.contains("Raises versionCode to 312"));
      assertFalse(shown.contains("no longer turned back to unknown"));
      assertFalse(shown.contains("Raises versionCode to 311"));
      assertFalse(shown.contains("keeps the environment guard"));
      assertFalse(shown.contains("Raises versionCode to 310"));
      assertFalse(shown.contains("pins the target device"));
      assertFalse(shown.contains("Raises versionCode to 309"));
      assertFalse(shown.contains("grep pattern-file"));
      assertFalse(shown.contains("Raises versionCode to 308"));
      assertFalse(shown.contains("compact two-line rows"));
      assertFalse(shown.contains("Raises versionCode to 307"));
      assertFalse(shown.contains("phone-portrait bottom navigation"));
      assertFalse(shown.contains("Raises versionCode to 306"));
      assertFalse(shown.contains("main tabs"));
      assertFalse(shown.contains("Raises versionCode to 305"));
      assertFalse(shown.contains("ADB/root helper"));
      assertFalse(shown.contains("Raises versionCode to 304"));
      assertFalse(shown.contains("sheets and dialogs adapt"));
      assertFalse(shown.contains("Raises versionCode to 303"));
      assertFalse(shown.contains("300 seconds"));
      assertFalse(shown.contains("Raises versionCode to 302"));
      assertFalse(shown.contains("first-frame wait"));
      assertFalse(shown.contains("Raises versionCode to 301"));
      assertFalse(shown.contains("dsh-fs-read-hint"));
      assertFalse(shown.contains("Raises versionCode to 300"));
      assertFalse(shown.contains("version negotiation"));
      assertFalse(shown.contains("Raises versionCode to 299"));
      assertFalse(shown.contains("Documentation only"));
      assertFalse(shown.contains("Raises versionCode to 298"));
      assertFalse(shown.contains("seccomp filter"));
      assertFalse(shown.contains("Raises versionCode to 297"));
      assertFalse(shown.contains("screen mode"));
      assertFalse(shown.contains("Raises versionCode to 296"));
      assertFalse(shown.contains("in-place update"));
      assertFalse(shown.contains("Raises versionCode to 295"));
      assertFalse(shown.contains("Raises versionCode to 294"));
      assertFalse(shown.contains("Raises versionCode to 293"));
      assertFalse(shown.contains("supported devices"));
      assertFalse(shown.contains("Raises versionCode to 292"));
      assertFalse(shown.contains("one-tap backup"));
      assertFalse(shown.contains("Raises versionCode to 291"));
      assertFalse(shown.contains("network health"));
      assertFalse(shown.contains("Raises versionCode to 290"));
      assertFalse(shown.contains("stage segment bar"));
      assertFalse(shown.contains("Raises versionCode to 289"));
      assertFalse(shown.contains("Launch target"));
      assertFalse(shown.contains("Raises versionCode to 288"));
      assertFalse(shown.contains("direct Web launch"));
      assertFalse(shown.contains("Raises versionCode to 287"));
      assertFalse(shown.contains("/app/install"));
      assertFalse(shown.contains("Raises versionCode to 286"));
      assertFalse(shown.contains("info dialog"));
      assertFalse(shown.contains("Raises versionCode to 285"));
      assertFalse(shown.contains("error panel"));
      assertFalse(shown.contains("Raises versionCode to 284"));
      assertFalse(shown.contains("native loading screen"));
      assertFalse(shown.contains("Raises versionCode to 283"));
      assertFalse(shown.contains("plugin page is visible"));
      assertFalse(shown.contains("Raises versionCode to 282"));
      assertFalse(shown.contains("Checking environment"));
      assertFalse(shown.contains("Raises versionCode to 281"));
      assertFalse(shown.contains("falling back to port 3090"));
      assertFalse(shown.contains("Raises versionCode to 280"));
      assertFalse(shown.contains("startup recovery page"));
      assertFalse(shown.contains("Raises versionCode to 279"));
      assertFalse(shown.contains("background tasks page"));
      assertFalse(shown.contains("Raises versionCode to 278"));
      assertFalse(shown.contains("data rescue entries"));
      assertFalse(shown.contains("Raises versionCode to 277"));
      assertFalse(shown.contains("Onboarding now animates"));
      assertFalse(shown.contains("Raises versionCode to 276"));
      assertFalse(shown.contains("ready duration"));
      assertFalse(shown.contains("Raises versionCode to 275"));
      assertFalse(shown.contains("Theme and appearance"));
      assertFalse(shown.contains("Raises versionCode to 274"));
      assertFalse(shown.contains("save bar is no longer covered"));
      assertFalse(shown.contains("Raises versionCode to 273"));
      assertFalse(shown.contains("Launch log area rebuilt"));
      assertFalse(shown.contains("Raises versionCode to 272"));
      assertFalse(shown.contains("External device"));
      assertFalse(shown.contains("Raises versionCode to 271"));
      assertFalse(shown.contains("Task status island page redrawn"));
      assertFalse(shown.contains("Raises versionCode to 270"));
      assertFalse(shown.contains("Downloads page rewritten"));
      assertFalse(shown.contains("Raises versionCode to 269"));
      assertFalse(shown.contains("Device permissions page compacted"));
      assertFalse(shown.contains("Raises versionCode to 268"));
      assertFalse(shown.contains("switching to mobile data"));
      assertFalse(shown.contains("Raises versionCode to 267"));
      assertFalse(shown.contains("Floating assistant redesigned"));
      assertFalse(shown.contains("Raises versionCode to 266"));
      assertFalse(shown.contains("Maintenance buttons now center their icons"));
      assertFalse(shown.contains("Raises versionCode to 265"));
      assertFalse(shown.contains("Update page compacted"));
      assertFalse(shown.contains("Raises versionCode to 264"));
      assertFalse(shown.contains("Closing a terminal tab"));
      assertFalse(shown.contains("Raises versionCode to 263"));
      assertFalse(shown.contains("compact screen preview"));
      assertFalse(shown.contains("Raises versionCode to 262"));
      assertFalse(shown.contains("Pasting long text into the terminal"));
      assertFalse(shown.contains("Raises versionCode to 261"));
      assertFalse(shown.contains("Run configuration compacted"));
      assertFalse(shown.contains("Raises versionCode to 260"));
      assertFalse(shown.contains("vector icons"));
      assertFalse(shown.contains("Raises versionCode to 259"));
      assertFalse(shown.contains("Boot loading screen"));
      assertFalse(shown.contains("Raises versionCode to 258"));
      assertFalse(shown.contains("proroot file lock fix"));
      assertFalse(shown.contains("Raises versionCode to 257"));
      assertFalse(shown.contains("Data and backup sub-pages reworked"));
      assertFalse(shown.contains("Raises versionCode to 256"));
      assertFalse(shown.contains("Maintenance page footer"));
      assertFalse(shown.contains("Raises versionCode to 255"));
      assertFalse(shown.contains("Install and environment page compacted"));
      assertFalse(shown.contains("Raises versionCode to 254"));
      assertFalse(shown.contains("Terminal UI polish"));
      assertFalse(shown.contains("Raises versionCode to 253"));
      assertFalse(shown.contains("Self-check and diagnostics page simplified"));
      assertFalse(shown.contains("Raises versionCode to 252"));
      assertFalse(shown.contains("Plugin page header redesign"));
      assertFalse(shown.contains("Raises versionCode to 251"));
      assertFalse(shown.contains("Configuration snapshots can be deleted again"));
      assertFalse(shown.contains("Raises versionCode to 250"));
      assertFalse(shown.contains("Raises versionCode to 248"));
      assertFalse(shown.contains("share one card"));
      assertFalse(shown.contains("Raises versionCode to 247"));
      assertFalse(shown.contains("is now a single list"));
      assertFalse(shown.contains("Updates the publication identity to versionCode 249"));
      assertFalse(shown.contains("plugin-install-quarantine"));
      assertFalse(shown.contains("Raises versionCode to 242"));
      assertFalse(shown.contains("Raises versionCode to 220"));
      assertFalse(shown.contains("build157"));
      dialog.dismiss();
    }
  }

  @Test
  public void emptyResultIsNormalAndStillAllowsRecheckAndBrowser() {
    try (var controller = Robolectric.buildActivity(UpdateActivity.class).setup()) {
      UpdateActivity activity = controller.get();
      assertEquals(
          "暂无可用更新", ((TextView) activity.findViewById(R.id.update_status)).getText().toString());
      assertEquals(View.GONE, activity.findViewById(R.id.update_release_panel).getVisibility());
      // 没有候选：唯一主按钮就是“检查更新”，状态行不再重复显示“重新检查”链接。
      UpdateProgressButton action = activity.findViewById(R.id.update_action);
      assertEquals(UpdateActionPolicy.Command.CHECK, action.action().command);
      assertFalse(action.animating());
      assertEquals("检查更新", action.text().toString());
      assertEquals(View.GONE, activity.findViewById(R.id.update_check).getVisibility());
      action.performClick();
      assertEquals(1, Engine.checks);
      assertTrue(activity.findViewById(R.id.update_browser).performClick());
      Intent browser =
          Shadow.<org.robolectric.shadows.ShadowActivity>extract(activity).getNextStartedActivity();
      assertEquals(Intent.ACTION_VIEW, browser.getAction());
      assertTrue(browser.getDataString().endsWith("/releases"));
    }
  }

  @Test
  public void certificateFailureIsNotDisplayedAsNoUpdateAndRawLegacyExceptionsAreHidden()
      throws Exception {
    String certificate = "无法验证更新服务的安全证书，请检查设备日期时间或切换网络后重试";
    Engine.states.setValue(state(certificate));
    try (var controller = Robolectric.buildActivity(UpdateActivity.class).setup()) {
      UpdateActivity activity = controller.get();
      TextView status = activity.findViewById(R.id.update_status);
      assertEquals(certificate, status.getText().toString());
      Engine.states.setValue(state("javax.net.ssl.SSLHandshakeException: Trust anchor missing"));
      assertFalse(status.getText().toString().contains("Exception"));
      assertFalse(status.getText().toString().contains("暂无可用更新"));
      assertTrue(activity.findViewById(R.id.update_action).isEnabled());
      assertTrue(activity.findViewById(R.id.update_browser).isEnabled());
    }
  }

  @Test
  public void progressButtonStaysInPlaceFromCandidateThroughDownloadWithoutCancel()
      throws Exception {
    UpdatePolicy.Release release = release();
    Engine.states.setValue(state("发现候选", UpdateRepository.Stage.IDLE, 0, 0, release));
    try (var controller = Robolectric.buildActivity(UpdateActivity.class).setup()) {
      UpdateActivity activity = controller.get();
      UpdateProgressButton action = activity.findViewById(R.id.update_action);
      assertEquals(View.VISIBLE, activity.findViewById(R.id.update_release_panel).getVisibility());
      assertEquals(
          "预览版 0.2.1-rc.9",
          ((TextView) activity.findViewById(R.id.update_release_title)).getText().toString());
      assertEquals(UpdateActionPolicy.Command.DOWNLOAD, action.action().command);
      assertEquals("下载更新 · 4.0 MiB", action.text().toString());
      assertEquals(View.VISIBLE, activity.findViewById(R.id.update_check).getVisibility());
      assertTrue(activity.findViewById(R.id.update_stable).isEnabled());

      long total = 4L * 1048576L;
      Engine.states.setValue(
          state("正在下载", UpdateRepository.Stage.DOWNLOADING, total / 4, total, release));
      assertSame("下载中仍是同一个按钮", action, activity.findViewById(R.id.update_action));
      assertEquals(UpdateActionPolicy.Mode.FILL, action.action().mode);
      assertEquals(25, action.action().percent);
      assertEquals("25% · 1.0 / 4.0 MiB", action.text().toString());
      assertFalse("下载中按钮不可点", action.isEnabled());
      assertEquals(View.GONE, activity.findViewById(R.id.update_check).getVisibility());
      assertFalse("任务进行中锁定通道", activity.findViewById(R.id.update_preview).isEnabled());
      assertNoCancel(activity.findViewById(android.R.id.content));

      Engine.states.setValue(
          state("已取消下载，进度已保留", UpdateRepository.Stage.IDLE, total / 4, total, release));
      assertEquals(UpdateActionPolicy.Command.DOWNLOAD, action.action().command);
      assertEquals("继续下载 · 已下载 25%", action.text().toString());
      assertTrue(action.isEnabled());
    }
  }

  @Test
  public void phoneCandidateDetailOpensSheetWithMetaAndNotes() throws Exception {
    Engine.states.setValue(state("发现候选", UpdateRepository.Stage.IDLE, 0, 0, release()));
    try (var controller = Robolectric.buildActivity(UpdateActivity.class).setup()) {
      UpdateActivity activity = controller.get();
      assertNull("手机布局没有右栏", activity.findViewById(R.id.update_pane_tabs));
      activity.findViewById(R.id.update_release_detail).performClick();
      Dialog dialog = ShadowDialog.getLatestDialog();
      assertNotNull(dialog);
      String shown = text(dialog.getWindow().getDecorView());
      assertTrue(shown.contains("预览版 0.2.1-rc.9"));
      assertTrue(shown.contains("build999 · 4.0 MiB"));
      assertTrue(shown.contains("候选版本的完整更新记录"));
      dialog.dismiss();
    }
  }

  @Test
  @Config(qualifiers = "zh-w1280dp-h800dp-land")
  public void tabletLandscapeShowsTwoPanesAndRowsSwitchThePaneWithoutDialogs() throws Exception {
    Engine.states.setValue(state("发现候选", UpdateRepository.Stage.IDLE, 0, 0, release()));
    try (var controller = Robolectric.buildActivity(UpdateActivity.class).setup()) {
      UpdateActivity activity = controller.get();
      RadioGroup tabs = activity.findViewById(R.id.update_pane_tabs);
      assertNotNull("≥ 840dp 使用两栏布局", tabs);
      assertEquals("有候选时默认看候选", R.id.update_pane_candidate, tabs.getCheckedRadioButtonId());
      assertEquals(
          "候选版本的完整更新记录",
          ((TextView) activity.findViewById(R.id.update_notes)).getText().toString());
      assertEquals(View.GONE, activity.findViewById(R.id.update_pane_empty).getVisibility());

      activity.findViewById(R.id.update_changelog).performClick();
      assertNull("两栏下点“本版本更新记录”不弹层", ShadowDialog.getLatestDialog());
      assertEquals(R.id.update_pane_current, tabs.getCheckedRadioButtonId());
      assertEquals(
          View.VISIBLE, activity.findViewById(R.id.update_pane_current_body).getVisibility());
      String current =
          ((TextView) activity.findViewById(R.id.update_pane_current_title)).getText().toString()
              + "\n"
              + ((TextView) activity.findViewById(R.id.update_pane_current_notes))
                  .getText()
                  .toString();
      assertTrue(
          current.contains(
              "DSHA " + BuildConfig.VERSION_NAME + " / build" + BuildConfig.VERSION_CODE));
      assertTrue(current.contains(body(bundledNotes(activity))));

      // 用户切过标签后，状态刷新不再自动切回候选。
      Engine.states.setValue(state("发现候选", UpdateRepository.Stage.IDLE, 0, 0, release()));
      assertEquals(R.id.update_pane_current, tabs.getCheckedRadioButtonId());
      activity.findViewById(R.id.update_release_detail).performClick();
      assertNull(ShadowDialog.getLatestDialog());
      assertEquals(R.id.update_pane_candidate, tabs.getCheckedRadioButtonId());
    }
  }

  @Test
  @Config(qualifiers = "zh-w1280dp-h800dp-land")
  public void tabletLandscapeWithoutCandidateShowsCurrentBuildAndEmptyCandidatePane() {
    try (var controller = Robolectric.buildActivity(UpdateActivity.class).setup()) {
      UpdateActivity activity = controller.get();
      RadioGroup tabs = activity.findViewById(R.id.update_pane_tabs);
      assertEquals(R.id.update_pane_current, tabs.getCheckedRadioButtonId());
      assertEquals(View.GONE, activity.findViewById(R.id.update_release_panel).getVisibility());
      tabs.check(R.id.update_pane_candidate);
      assertEquals(View.VISIBLE, activity.findViewById(R.id.update_pane_empty).getVisibility());
    }
  }

  @Test
  public void versionLineExpandsFullIdentity() {
    try (var controller = Robolectric.buildActivity(UpdateActivity.class).setup()) {
      UpdateActivity activity = controller.get();
      assertEquals(
          CurrentReleaseNotes.versionLabel(BuildConfig.VERSION_NAME)
              + " · build"
              + BuildConfig.VERSION_CODE,
          ((TextView) activity.findViewById(R.id.update_version_line)).getText().toString());
      View details = activity.findViewById(R.id.update_version_details);
      assertEquals(View.GONE, details.getVisibility());
      activity.findViewById(R.id.update_version_toggle).performClick();
      assertEquals(View.VISIBLE, details.getVisibility());
      assertEquals(
          BuildConfig.VERSION_NAME,
          ((TextView) activity.findViewById(R.id.update_current)).getText().toString());
    }
  }

  @Test
  public void policyStagesMirrorRepositoryStages() {
    UpdateRepository.Stage[] stages = UpdateRepository.Stage.values();
    assertEquals(stages.length, UpdateActionPolicy.Stage.values().length);
    for (UpdateRepository.Stage stage : stages)
      assertEquals(stage.name(), UpdateUi.stage(stage).name());
  }

  private static void assertNoCancel(View view) {
    if (view instanceof TextView)
      assertFalse("页面不提供取消（通知栏保留）", ((TextView) view).getText().toString().contains("取消"));
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        if (group.getChildAt(i).getVisibility() == View.VISIBLE)
          assertNoCancel(group.getChildAt(i));
    }
  }

  private static UpdatePolicy.Release release() {
    return new UpdatePolicy.Release(
        999,
        "0.2.1-rc.9",
        UpdatePolicy.PREVIEW,
        "standard",
        30,
        "arm64-v8a",
        "https://example.invalid/app.apk",
        "0".repeat(64),
        4L * 1048576L,
        "候选版本的完整更新记录",
        "https://example.invalid/releases");
  }

  private static UpdateRepository.State state(
      String message,
      UpdateRepository.Stage stage,
      long downloaded,
      long total,
      UpdatePolicy.Release release)
      throws Exception {
    var constructor =
        UpdateRepository.State.class.getDeclaredConstructor(
            String.class,
            UpdateRepository.Stage.class,
            long.class,
            long.class,
            UpdatePolicy.Release.class,
            File.class,
            String.class,
            String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        message, stage, downloaded, total, release, null, "preview", null);
  }

  private static String assertCurrentNotesShown(Context context, String shown) throws IOException {
    String currentBody = body(bundledNotes(context));
    assertFalse(
        "The current bundled notes must contain an actual release body", currentBody.isEmpty());
    assertTrue(
        shown.contains("DSHA " + BuildConfig.VERSION_NAME + " / build" + BuildConfig.VERSION_CODE));
    assertTrue(
        "The opened sheet must show the complete current bundled body",
        shown.contains(currentBody));
    return currentBody;
  }

  private static String bundledNotes(Context context) throws IOException {
    try (var input = context.getResources().openRawResource(R.raw.changelog)) {
      String notes = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(
          "The localized bundled header must identify the installed build",
          CurrentReleaseNotes.matches(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, notes));
      return notes;
    }
  }

  private static Context inLanguage(Context context, String language) {
    Configuration configuration = new Configuration(context.getResources().getConfiguration());
    configuration.setLocale(Locale.forLanguageTag(language));
    return context.createConfigurationContext(configuration);
  }

  private static String body(String notes) {
    int end = notes.indexOf('\n');
    assertTrue("The current notes must contain a header followed by a release body", end >= 0);
    return notes.substring(end + 1).trim();
  }

  private static String text(View view) {
    StringBuilder result = new StringBuilder();
    if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) result.append(text(group.getChildAt(i)));
    }
    return result.toString();
  }
}
