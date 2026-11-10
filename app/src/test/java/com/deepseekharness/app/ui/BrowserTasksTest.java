package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import com.deepseekharness.app.core.ConfigStore;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppTask;

/** Real framework Intent/AppTask adapters; system task and PiP surfaces still require device checks. */
@RunWith(BrowserTasksTest.NoApkRunner.class)
@Config(
    sdk = {23, 34},
    manifest = Config.NONE,
    application = android.app.Application.class)
public final class BrowserTasksTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  private static final class RecordingContext extends ContextWrapper {
    final List<Intent> starts = new ArrayList<>();

    RecordingContext() {
      super(RuntimeEnvironment.getApplication());
    }

    @Override
    public void startActivity(Intent intent) {
      starts.add(intent);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
      starts.add(intent);
    }
  }

  private static final class ExplodingContext extends ContextWrapper {
    ExplodingContext() {
      super(RuntimeEnvironment.getApplication());
    }

    @Override
    public Object getSystemService(String name) {
      if (Context.ACTIVITY_SERVICE.equals(name))
        throw new IllegalStateException("activity service unavailable");
      return super.getSystemService(name);
    }
  }

  public static final class Host extends Activity {
    final List<Intent> starts = new ArrayList<>();
    int id = 7;
    boolean root = true;
    boolean finished, removed;

    @Override
    public int getTaskId() {
      return id;
    }

    @Override
    public boolean isTaskRoot() {
      return root;
    }

    @Override
    public boolean isFinishing() {
      return finished;
    }

    @Override
    public void finish() {
      finished = true;
    }

    @Override
    public void finishAndRemoveTask() {
      finished = removed = true;
    }

    @Override
    public Object getSystemService(String name) {
      return RuntimeEnvironment.getApplication().getSystemService(name);
    }

    @Override
    public void startActivity(Intent intent) {
      starts.add(intent);
    }
  }

  public static final class Browser extends PictureInPictureActivity {
    final List<Intent> starts = new ArrayList<>();
    Intent entry = new Intent();
    int cancelled;
    boolean finished, inPicture;

    @Override
    protected boolean pictureInPictureContentReady() {
      return true;
    }

    @Override
    public int getTaskId() {
      return 7;
    }

    @Override
    public boolean isTaskRoot() {
      return true;
    }

    @Override
    public boolean isFinishing() {
      return finished;
    }

    @Override
    public boolean isDestroyed() {
      return false;
    }

    @Override
    public boolean isInPictureInPictureMode() {
      return inPicture;
    }

    @Override
    public void finish() {
      finished = true;
    }

    @Override
    public Intent getIntent() {
      return entry;
    }

    @Override
    public String getPackageName() {
      return RuntimeEnvironment.getApplication().getPackageName();
    }

    @Override
    public android.content.pm.PackageManager getPackageManager() {
      return RuntimeEnvironment.getApplication().getPackageManager();
    }

    @Override
    public Object getSystemService(String name) {
      return RuntimeEnvironment.getApplication().getSystemService(name);
    }

    @Override
    public void startActivity(Intent intent) {
      starts.add(intent);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
      starts.add(intent);
    }

    @Override
    protected void cancelConversationRequests() {
      cancelled++;
    }
  }

  private RecordingContext context;
  private ActivityManager manager;

  @Before
  public void isolateTaskRegistry() throws Exception {
    context = new RecordingContext();
    new ConfigStore(context).setHideFromRecents(false);
    manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of());
    var nativeMain = BrowserTasks.class.getDeclaredField("nativeMain");
    nativeMain.setAccessible(true);
    nativeMain.set(null, new WeakReference<Activity>(null));
    var browser = BrowserConversationRoute.class.getDeclaredField("browser");
    browser.setAccessible(true);
    browser.set(null, new WeakReference<PictureInPictureActivity>(null));
  }

  private ActivityManager.AppTask task(int id, Class<?> root, int count) {
    var info = new ActivityManager.RecentTaskInfo();
    info.id = id;
    info.baseActivity = new ComponentName(context, root);
    info.numActivities = count;
    var task = ShadowAppTask.newInstance();
    Shadow.<ShadowAppTask>extract(task).setTaskInfo(info);
    return task;
  }

  private Intent opened() {
    assertEquals(1, context.starts.size());
    Intent intent = context.starts.get(0);
    assertEquals(MainActivity.class.getName(), intent.getComponent().getClassName());
    assertTrue(intent.getBooleanExtra("open_launch", false));
    assertEquals(
        0, intent.getFlags() & (Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    return intent;
  }

  @Test
  public void explicitHomeReordersExistingNativeTaskWithoutClearingWeb() {
    var nativeTask = task(3, MainActivity.class, 2);
    var webTask = task(7, WebPreviewActivity.class, 3);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of(webTask, nativeTask));
    BrowserTasks.openMain(context);
    Intent intent = opened();
    assertEquals(
        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.getFlags());
    assertFalse(Shadow.<ShadowAppTask>extract(webTask).isFinishedAndRemoved());
    assertFalse(Shadow.<ShadowAppTask>extract(nativeTask).isFinishedAndRemoved());
  }

  @Test
  public void missingNativeRootFallsBackToAffinityWithoutForcingAnotherTask() {
    var mixed = task(7, WebPreviewActivity.class, 4);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of(mixed));
    BrowserTasks.openMain(context);
    Intent intent = opened();
    assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    assertEquals(0, intent.getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    assertFalse(Shadow.<ShadowAppTask>extract(mixed).isFinishedAndRemoved());
  }

  @Test
  public void backAndExplicitHomePreserveTheBrowserAndActuallyRouteToNativeMain() {
    var browser = new Browser();
    Shadow.<ShadowActivityManager>extract(manager)
        .setAppTasks(List.of(task(7, MainActivity.class, 4)));
    browser.leavePreview();
    assertTrue(browser.conversationHidden());
    assertFalse(browser.finished);
    assertEquals(1, browser.cancelled);
    assertEquals(MainActivity.class.getName(), browser.starts.get(0).getComponent().getClassName());
    assertEquals(0, browser.starts.get(0).getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    assertEquals(0, browser.starts.get(0).getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
    assertFalse(Shadow.<ShadowActivity>extract(browser).isTaskMovedToBack());
    browser.hideConversation();
    assertFalse(browser.finished);
    assertEquals(2, browser.starts.size());
  }

  @Test
  @Config(sdk = 34)
  public void homeFromPipExpandsTheSameBrowserBeforeExplicitNativeRouting() {
    var browser = new Browser();
    browser.inPicture = true;
    browser.hideConversation();
    assertEquals(1, browser.starts.size());
    Intent expanded = browser.starts.get(0);
    assertEquals(Browser.class.getName(), expanded.getComponent().getClassName());
    assertEquals("HIDDEN", expanded.getStringExtra(BrowserConversationRoute.MODE));
    assertEquals(
        0,
        expanded.getFlags() & (Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    assertFalse(browser.finished);
    browser.inPicture = false;
    browser.entry = expanded;
    browser.receivePresentationIntent();
    assertEquals(MainActivity.class.getName(), browser.starts.get(1).getComponent().getClassName());
    assertFalse(browser.finished);
    assertTrue(browser.conversationHidden());
  }

  @Test
  public void inactiveNativeTaskDoesNotMatchTheNoSelectionSentinel() {
    Shadow.<ShadowActivityManager>extract(manager)
        .setAppTasks(List.of(task(-1, MainActivity.class, 0)));
    BrowserTasks.openMain(context);
    assertEquals(0, opened().getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
  }

  @Test
  public void repeatedHomeFromALegacySharedTaskKeepsReorderingTheSameMain() {
    var mixed = task(7, MainActivity.class, 4);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of(mixed));
    var browser = new Browser();
    browser.hideConversation();
    browser.hideConversation();
    assertEquals(2, browser.starts.size());
    for (Intent intent : browser.starts) {
      assertEquals(MainActivity.class.getName(), intent.getComponent().getClassName());
      assertEquals(
          Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP,
          intent.getFlags());
    }
    assertFalse(browser.finished);
    assertFalse(Shadow.<ShadowAppTask>extract(mixed).isFinishedAndRemoved());
  }

  @Test
  public void oneMainCardHidesDuplicateNativeAndBrowserTasksWithoutFinishingTheirPages() {
    var oldMain = task(3, MainActivity.class, 4);
    var currentMain = task(8, MainActivity.class, 1);
    var browser = task(7, WebPreviewActivity.class, 1);
    var otherPage = task(9, DiagnosticActivity.class, 1);
    Shadow.<ShadowActivityManager>extract(manager)
        .setAppTasks(List.of(oldMain, currentMain, browser, otherPage));
    BrowserTasks.reconcileRecents(context, 8);
    assertTrue(Shadow.<ShadowAppTask>extract(oldMain).isExcludedFromRecents());
    assertFalse(Shadow.<ShadowAppTask>extract(currentMain).isExcludedFromRecents());
    assertTrue(Shadow.<ShadowAppTask>extract(browser).isExcludedFromRecents());
    assertFalse(Shadow.<ShadowAppTask>extract(otherPage).isExcludedFromRecents());
    for (var task : List.of(oldMain, currentMain, browser, otherPage))
      assertFalse(Shadow.<ShadowAppTask>extract(task).isFinishedAndRemoved());
  }

  @Test
  public void mainBackOpensDesktopAndBackgroundsTheTaskWithoutCreatingAnotherMain() {
    var host = new Host();
    var main = task(7, MainActivity.class, 4);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of(main));
    BrowserTasks.exitMain(host);
    assertTrue(Shadow.<ShadowActivity>extract(host).isTaskMovedToBack());
    assertFalse(host.finished);
    assertFalse(Shadow.<ShadowAppTask>extract(main).isFinishedAndRemoved());
    assertEquals(1, host.starts.size());
    assertEquals(Intent.ACTION_MAIN, host.starts.get(0).getAction());
    assertTrue(host.starts.get(0).hasCategory(Intent.CATEGORY_HOME));
    assertNull(host.starts.get(0).getComponent());
    assertTrue(context.starts.isEmpty());
  }

  @Test
  public void notificationHomeHasAnExplicitNativeDestinationWithoutClearingTheBrowser() {
    Intent intent = MainActivity.homeIntent(context);
    assertTrue(BrowserTasks.nativeDestination(intent));
    assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0);
    assertEquals(
        0,
        intent.getFlags()
            & (Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT));
    intent.removeExtra(BrowserTasks.NATIVE_HOME);
    assertFalse(
        "stale open_launch must not hijack a later browser Home return",
        BrowserTasks.nativeDestination(intent));
  }

  @Test
  public void specificNativeDestinationDoesNotAlsoRequestTheLaunchTab() {
    Intent intent = MainActivity.navigationIntent(context).putExtra("open_plugins", true);
    assertTrue(BrowserTasks.nativeDestination(intent));
    assertTrue(intent.getBooleanExtra("open_plugins", false));
    assertFalse(intent.getBooleanExtra("open_launch", false));
    assertEquals(
        0,
        intent.getFlags()
            & (Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT));
  }

  @Test
  public void launcherRedirectRequiresANewNonRootLauncherEntry() {
    var host = new Host();
    host.root = false;
    host.setIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
    assertTrue(BrowserTasks.duplicateLauncher(host, null));
    assertFalse(BrowserTasks.duplicateLauncher(host, new Bundle()));
    host.root = true;
    assertFalse(BrowserTasks.duplicateLauncher(host, null));
    host.root = false;
    host.setIntent(new Intent().putExtra("open_launch", true));
    assertFalse(BrowserTasks.duplicateLauncher(host, null));
  }

  @Test
  public void confirmedSingletonEmptyHostRemovesItsOwnTask() {
    var host = new Host();
    Shadow.<ShadowActivityManager>extract(manager)
        .setAppTasks(List.of(task(7, WebPreviewActivity.class, 1)));
    assertTrue(BrowserTasks.finishEmptyHost(host));
    assertTrue(host.removed);
  }

  @Test
  public void mixedTaskRootOnlyFinishesTheEmptyHost() {
    var host = new Host();
    Shadow.<ShadowActivityManager>extract(manager)
        .setAppTasks(List.of(task(7, WebPreviewActivity.class, 4)));
    assertTrue(BrowserTasks.finishEmptyHost(host));
    assertFalse(host.removed);
  }

  @Test
  public void nonRootAndUnknownEmptyHostsNeverRemoveNativeSiblings() {
    var host = new Host();
    host.root = false;
    Shadow.<ShadowActivityManager>extract(manager)
        .setAppTasks(List.of(task(7, MainActivity.class, 1)));
    assertTrue(BrowserTasks.finishEmptyHost(host));
    assertFalse(host.removed);
    var unknown = new Host();
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of());
    assertTrue(BrowserTasks.finishEmptyHost(unknown));
    assertFalse(unknown.removed);
  }

  private static void setField(Class<?> type, String name, Object value) throws Exception {
    var field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(null, value);
  }

  /** 以 Web 为根、但存活 Main 已加入的同一任务（通知先开 Web 的单任务布局）。 */
  private Host liveMainIn(int task) throws Exception {
    var main = new Host();
    main.id = task;
    main.root = false;
    setField(BrowserTasks.class, "nativeMain", new WeakReference<Activity>(main));
    return main;
  }

  @Test
  public void sharedWebRootedTaskWithLiveMainStaysTheOneVisibleCard() throws Exception {
    liveMainIn(7);
    var shared = task(7, WebPreviewActivity.class, 2);
    var legacyMain = task(3, MainActivity.class, 1);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of(legacyMain, shared));
    BrowserTasks.reconcileRecents(context, -1);
    assertFalse(Shadow.<ShadowAppTask>extract(shared).isExcludedFromRecents());
    assertTrue(Shadow.<ShadowAppTask>extract(legacyMain).isExcludedFromRecents());
  }

  @Test
  public void explicitHomeReordersMainInsideTheSharedTaskInsteadOfAnotherWindow() throws Exception {
    liveMainIn(7);
    var browser = new Browser();
    setField(
        BrowserConversationRoute.class,
        "browser",
        new WeakReference<PictureInPictureActivity>(browser));
    var shared = task(7, WebPreviewActivity.class, 2);
    var legacyMain = task(9, MainActivity.class, 1);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of(legacyMain, shared));
    browser.hideConversation();
    assertEquals(1, browser.starts.size());
    Intent intent = browser.starts.get(0);
    assertEquals(MainActivity.class.getName(), intent.getComponent().getClassName());
    // 经 AppTask 派发进同一任务：不带 NEW_TASK，也不清栈。
    assertEquals(
        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.getFlags());
    assertFalse(browser.finished);
  }

  @Test
  public void duplicateHostForwardsTheRequestIntoTheLiveHostTaskOnce() throws Exception {
    var live = new Browser();
    setField(
        BrowserConversationRoute.class,
        "browser",
        new WeakReference<PictureInPictureActivity>(live));
    Shadow.<ShadowActivityManager>extract(manager)
        .setAppTasks(List.of(task(7, WebPreviewActivity.class, 1)));
    var created = new Browser();
    created.entry =
        new Intent()
            .putExtra(BrowserConversationRoute.MODE, "FULL")
            .putExtra(BrowserConversationRoute.SESSION, "s1")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    assertTrue(BrowserTasks.forwardToLiveBrowser(created));
    assertEquals(1, created.starts.size());
    Intent forward = created.starts.get(0);
    assertEquals(Browser.class.getName(), forward.getComponent().getClassName());
    assertEquals("FULL", forward.getStringExtra(BrowserConversationRoute.MODE));
    assertEquals("s1", forward.getStringExtra(BrowserConversationRoute.SESSION));
    assertTrue(forward.getBooleanExtra(BrowserTasks.FORWARDED, false));
    assertEquals(
        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP,
        forward.getFlags());
    // 已转交过的请求不再转交，避免来回弹跳。
    var bounced = new Browser();
    bounced.entry = forward;
    assertFalse(BrowserTasks.forwardToLiveBrowser(bounced));
    assertTrue(bounced.starts.isEmpty());
  }

  @Test
  public void hostWithoutAnotherLiveOwnerOrReachableTaskKeepsTheRequest() throws Exception {
    var created = new Browser();
    assertFalse(BrowserTasks.forwardToLiveBrowser(created));
    setField(
        BrowserConversationRoute.class,
        "browser",
        new WeakReference<PictureInPictureActivity>(created));
    assertFalse(BrowserTasks.forwardToLiveBrowser(created));
    var live = new Browser();
    setField(
        BrowserConversationRoute.class,
        "browser",
        new WeakReference<PictureInPictureActivity>(live));
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of());
    var orphan = new Browser();
    assertFalse(BrowserTasks.forwardToLiveBrowser(orphan));
    assertTrue(orphan.starts.isEmpty());
  }

  @Test
  public void hidePreferenceHidesEveryAppTaskEvenTheSelectedMain() {
    new ConfigStore(context).setHideFromRecents(true);
    var currentMain = task(8, MainActivity.class, 1);
    var browser = task(7, WebPreviewActivity.class, 1);
    var otherPage = task(9, DiagnosticActivity.class, 1);
    Shadow.<ShadowActivityManager>extract(manager)
        .setAppTasks(List.of(currentMain, browser, otherPage));
    assertTrue(BrowserTasks.reconcileRecents(context, 8));
    for (ActivityManager.AppTask hidden : List.of(currentMain, browser, otherPage))
      assertTrue(Shadow.<ShadowAppTask>extract(hidden).isExcludedFromRecents());
  }

  @Test
  public void applyingTheHidePreferenceImmediatelyHidesAllTasks() {
    new ConfigStore(context).setHideFromRecents(true);
    var currentMain = task(8, MainActivity.class, 1);
    var browser = task(7, WebPreviewActivity.class, 1);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of(currentMain, browser));
    assertTrue(BrowserTasks.applyRecentsPreference(context));
    assertTrue(Shadow.<ShadowAppTask>extract(currentMain).isExcludedFromRecents());
    assertTrue(Shadow.<ShadowAppTask>extract(browser).isExcludedFromRecents());
  }

  @Test
  public void disablingThePreferenceRestoresTheSingleMainCard() throws Exception {
    new ConfigStore(context).setHideFromRecents(false);
    liveMainIn(8);
    var oldMain = task(3, MainActivity.class, 4);
    var currentMain = task(8, MainActivity.class, 1);
    var browser = task(7, WebPreviewActivity.class, 1);
    Shadow.<ShadowActivityManager>extract(manager)
        .setAppTasks(List.of(oldMain, currentMain, browser));
    assertTrue(BrowserTasks.applyRecentsPreference(context));
    assertTrue(Shadow.<ShadowAppTask>extract(oldMain).isExcludedFromRecents());
    assertFalse(Shadow.<ShadowAppTask>extract(currentMain).isExcludedFromRecents());
    assertTrue(Shadow.<ShadowAppTask>extract(browser).isExcludedFromRecents());
  }

  @Test
  public void failedApplyKeepsTheSavedPreferenceAndDoesNotCrash() {
    new ConfigStore(context).setHideFromRecents(true);
    assertFalse(BrowserTasks.applyRecentsPreference(new ExplodingContext()));
    assertTrue(new ConfigStore(context).isHideFromRecents());
  }

  @Test
  public void replayingAfterANewPreviewTaskAppearsHidesItWhenEnabled() {
    new ConfigStore(context).setHideFromRecents(true);
    var currentMain = task(8, MainActivity.class, 1);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of(currentMain));
    assertTrue(BrowserTasks.applyRecentsPreference(context));
    // 预览宿主后建：晚于上一次重放才进入系统任务列表（ColorOS 16 留下独立卡片的现场）。
    var preview = task(9, WebPreviewActivity.class, 1);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of(currentMain, preview));
    assertTrue(BrowserTasks.applyRecentsPreference(context));
    assertTrue(Shadow.<ShadowAppTask>extract(preview).isExcludedFromRecents());
    assertTrue(Shadow.<ShadowAppTask>extract(currentMain).isExcludedFromRecents());
  }

  @Test
  public void replayingOnResumeWithThePreferenceOffKeepsTheLiveMainCardVisible() throws Exception {
    new ConfigStore(context).setHideFromRecents(false);
    liveMainIn(8);
    var currentMain = task(8, MainActivity.class, 1);
    var preview = task(7, WebPreviewActivity.class, 1);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of(currentMain, preview));
    assertTrue(BrowserTasks.applyRecentsPreference(context));
    assertFalse(Shadow.<ShadowAppTask>extract(currentMain).isExcludedFromRecents());
    // 网页宿主按既有单主卡规则继续隐藏，关闭偏好不会把预览页放回最近任务。
    assertTrue(Shadow.<ShadowAppTask>extract(preview).isExcludedFromRecents());
  }
}
