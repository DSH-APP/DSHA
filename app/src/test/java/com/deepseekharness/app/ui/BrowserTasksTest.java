package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
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

  public static final class Host extends Activity {
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
  public void legacyBrowserRootWithNativePagesCreatesIndependentNativeShell() {
    var mixed = task(7, WebPreviewActivity.class, 4);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of(mixed));
    BrowserTasks.openMain(context);
    Intent intent = opened();
    assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0);
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
    assertTrue((browser.starts.get(0).getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0);
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
    assertTrue((opened().getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0);
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
}
