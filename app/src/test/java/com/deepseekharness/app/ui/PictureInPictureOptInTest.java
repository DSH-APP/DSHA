package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.AppOpsManager;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.WebLifecycle;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

/** Actual Android preference, shared Activity entry and Intent paths; no system PiP surface claim. */
@RunWith(PictureInPictureOptInTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = PictureInPictureOptInTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PictureInPictureOptInTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  public static final class Browser extends PictureInPictureActivity {
    final List<Intent> starts = new ArrayList<>();
    PictureInPictureParams parameters;
    boolean inPicture, finished;
    boolean contentReady = true;
    int enters;

    @Override
    protected boolean pictureInPictureContentReady() {
      return contentReady;
    }

    @Override
    public boolean isInPictureInPictureMode() {
      return inPicture;
    }

    @Override
    public void setPictureInPictureParams(PictureInPictureParams value) {
      parameters = value;
    }

    @Override
    public boolean enterPictureInPictureMode(PictureInPictureParams value) {
      enters++;
      parameters = value;
      return true;
    }

    @Override
    public void finish() {
      finished = true;
    }

    @Override
    public boolean isFinishing() {
      return finished;
    }

    void pauseAndStop() {
      super.onPause();
      super.onStop();
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
      starts.add(intent);
    }

    @Override
    public void startActivity(Intent intent) {
      starts.add(intent);
    }

    @Override
    public <T extends android.view.View> T findViewById(int id) {
      // NoApkRunner has framework resources; keep the real Window without inflating AppCompat UI.
      return getWindow().findViewById(id);
    }
  }

  public static final class NativeMain extends MainActivity {
    final List<Intent> starts = new ArrayList<>();
    boolean rejectStarts;

    @Override
    public <T extends android.view.View> T findViewById(int id) {
      return null; // Native layout is irrelevant to the real Main lifecycle routing callbacks.
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
      starts.add(intent);
    }

    @Override
    public void startActivity(Intent intent) {
      if (rejectStarts) throw new IllegalStateException("fixture route temporarily unavailable");
      starts.add(intent);
    }
  }

  public static final class FrameworkMain extends MainActivity {
    @Override
    public <T extends android.view.View> T findViewById(int id) {
      return null;
    }
  }

  @org.robolectric.annotation.Implements(android.app.ActivityManager.AppTask.class)
  public static class RejectedAppTask extends org.robolectric.shadows.ShadowAppTask {
    static int attempts;

    @org.robolectric.annotation.Implementation
    @Override
    protected void startActivity(Context context, Intent intent, Bundle options) {
      assertTrue(context instanceof MainActivity);
      attempts++;
      throw new android.util.AndroidRuntimeException("device AppTask rejection fixture");
    }
  }

  private App app;
  private ConfigStore config;
  private SharedPreferences prefs;
  private Browser browser;
  private PictureInPictureFrame frame;

  private static Object field(Object owner, Class<?> type, String name) throws Exception {
    var field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private void field(String name, Object value) throws Exception {
    var field = PictureInPictureActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(browser, value);
  }

  private static void attachFragmentLifecycle(androidx.fragment.app.FragmentActivity activity)
      throws Exception {
    var fragments =
        (androidx.fragment.app.FragmentController)
            field(activity, androidx.fragment.app.FragmentActivity.class, "mFragments");
    fragments.attachHost(null);
  }

  @Before
  public void actualFrameworkPreferencesAndBrowser() throws Exception {
    app = (App) RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    prefs = app.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    assertTrue(prefs.edit().clear().commit());
    config = new ConfigStore(app);
    BrowserTasks.nativeNavigation();
    Shadow.<ShadowPackageManager>extract(app.getPackageManager())
        .setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true);
    browser = Robolectric.buildActivity(Browser.class).get();
    attachFragmentLifecycle(browser);
    // NoApkRunner has no AppCompat resource APK. Prepare only its unused ActionBar decor;
    // the actual Fragment/Activity pause and stop implementations still run below.
    Object delegate = browser.getDelegate();
    var installed = delegate.getClass().getDeclaredField("mSubDecorInstalled");
    installed.setAccessible(true);
    installed.setBoolean(delegate, true);
    frame = new PictureInPictureFrame(browser);
    frame.restoreViewport(1080, 1920);
    field("pictureFrame", frame);
    field("picturePreferences", prefs);
    prefs.registerOnSharedPreferenceChangeListener(
        (SharedPreferences.OnSharedPreferenceChangeListener)
            field(browser, PictureInPictureActivity.class, "picturePreferenceListener"));
    // Use the real application-owned lifecycle; no process or network start is requested.
    WebLifecycle lifecycle =
        (WebLifecycle) field(app.harnessSession(), app.harnessSession().getClass(), "lifecycle");
    long generation = lifecycle.beginStart(false, false);
    assertTrue(lifecycle.finishStart(generation));
    if (Build.VERSION.SDK_INT >= 26) assertTrue(PictureInPictureActivity.allowed(app));
  }

  @Test
  public void missingOrAmbiguousLegacyValuesNeverCountAsExplicitOn() {
    assertFalse(config.isPictureInPictureEnabled());
    assertFalse(prefs.contains(Constants.KEY_PICTURE_IN_PICTURE_USER_CHOICE));
    for (boolean legacy : new boolean[] {true, false}) {
      prefs.edit().putBoolean(Constants.KEY_PICTURE_IN_PICTURE, legacy).commit();
      assertFalse(new ConfigStore(app).isPictureInPictureEnabled());
      assertEquals(legacy, prefs.getBoolean(Constants.KEY_PICTURE_IN_PICTURE, !legacy));
    }
    prefs.edit().putString(Constants.KEY_PICTURE_IN_PICTURE_USER_CHOICE, "true").commit();
    assertFalse(new ConfigStore(app).isPictureInPictureEnabled());
  }

  @Test
  public void explicitChoicePersistsAcrossReopenAndNeverEntersFromStartingAWebpage() {
    browser.refreshPictureInPicture();
    assertFalse(config.isPictureInPictureEnabled());
    assertFalse(prefs.contains(Constants.KEY_PICTURE_IN_PICTURE_USER_CHOICE));
    config.setPictureInPictureEnabled(true);
    assertTrue(new ConfigStore(app).isPictureInPictureEnabled());
    config.setPictureInPictureEnabled(false);
    assertFalse(new ConfigStore(app).isPictureInPictureEnabled());
    assertFalse(browser.finished);
  }

  @Test
  public void explicitOnAndOffSurviveActualAndroidXmlDiskReload() throws Exception {
    for (boolean enabled : new boolean[] {true, false}) {
      config.setPictureInPictureEnabled(enabled);
      assertTrue(prefs.edit().commit());
      var file =
          new java.io.File(
              app.getApplicationInfo().dataDir, "shared_prefs/" + Constants.PREFS + ".xml");
      var constructor =
          Class.forName("android.app.SharedPreferencesImpl")
              .getDeclaredConstructor(java.io.File.class, int.class);
      constructor.setAccessible(true);
      var disk = (SharedPreferences) constructor.newInstance(file, Context.MODE_PRIVATE);
      var reopenedContext =
          new ContextWrapper(app) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
              assertEquals(Constants.PREFS, name);
              return disk;
            }
          };
      assertEquals(enabled, new ConfigStore(reopenedContext).isPictureInPictureEnabled());
    }
  }

  @Test
  public void legacyBackupTrueCannotEnableOrReenableTheLocalChoice() throws Exception {
    config.applyHostSettings(
        config.prepareHostSettings(new JSONObject().put("pictureInPicture", true), false));
    assertFalse(config.isPictureInPictureEnabled());
    config.setPictureInPictureEnabled(true);
    assertFalse(
        config.hostSettingsState().containsKey(Constants.KEY_PICTURE_IN_PICTURE_USER_CHOICE));
    config.setPictureInPictureEnabled(false);
    config.applyHostSettings(
        config.prepareHostSettings(new JSONObject().put("pictureInPicture", true), false));
    assertFalse(new ConfigStore(app).isPictureInPictureEnabled());
  }

  @Test
  public void homeAutoEnterAndSystemRequestsRemainOffUntilExplicitlyEnabled() {
    browser.refreshPictureInPicture();
    assertFalse(browser.parameters.isAutoEnterEnabled());
    browser.onUserLeaveHint();
    assertFalse(frame.isFrozen());
    assertFalse(browser.onPictureInPictureRequested());
    assertEquals(0, browser.enters);
    config.setPictureInPictureEnabled(true);
    assertTrue(browser.parameters.isAutoEnterEnabled());
    browser.onUserLeaveHint();
    assertTrue(frame.isFrozen());
    assertTrue(browser.onPictureInPictureRequested());
    assertEquals(1, browser.enters);
  }

  @Test
  public void turningOffDuringAutoEnterTransitionReleasesOnlyThePresentationTransform() {
    config.setPictureInPictureEnabled(true);
    browser.onUserLeaveHint();
    assertTrue(frame.isFrozen());
    config.setPictureInPictureEnabled(false);
    assertFalse(browser.parameters.isAutoEnterEnabled());
    assertFalse(frame.isFrozen());
    assertFalse(browser.onPictureInPictureRequested());
    assertFalse(browser.finished);
  }

  @Test
  public void systemPermissionRevocationHidesPipAndPreservesTheWebpageAndUserChoice() {
    config.setPictureInPictureEnabled(true);
    browser.inPicture = true;
    var ops = (AppOpsManager) app.getSystemService(Context.APP_OPS_SERVICE);
    Shadow.<org.robolectric.shadows.ShadowAppOpsManager>extract(ops)
        .setMode(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            android.os.Process.myUid(),
            app.getPackageName(),
            AppOpsManager.MODE_IGNORED);
    browser.refreshPictureInPicture();
    assertTrue(browser.conversationHidden());
    assertFalse(browser.finished);
    assertEquals(1, browser.starts.size());
    assertTrue(config.isPictureInPictureEnabled());
    assertFalse(browser.onPictureInPictureRequested());
  }

  @Test
  @Config(sdk = 23)
  public void lowAndroidNeverEntersEvenAfterExplicitChoice() {
    config.setPictureInPictureEnabled(true);
    browser.onUserLeaveHint();
    assertFalse(frame.isFrozen());
    assertFalse(browser.onPictureInPictureRequested());
    assertEquals(0, browser.enters);
  }

  @Test
  public void disablingAnExistingPipExpandsSameInstanceThenActuallyRoutesHome() {
    config.setPictureInPictureEnabled(true);
    browser.inPicture = true;
    config.setPictureInPictureEnabled(false);
    assertTrue(browser.conversationHidden());
    assertFalse(browser.finished);
    assertEquals(1, browser.starts.size());
    Intent expanded = browser.starts.get(0);
    assertEquals(Browser.class.getName(), expanded.getComponent().getClassName());
    assertEquals("HIDDEN", expanded.getStringExtra(BrowserConversationRoute.MODE));
    assertEquals(
        0,
        expanded.getFlags() & (Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    browser.refreshPictureInPicture();
    assertEquals(1, browser.starts.size());
    browser.inPicture = false;
    browser.setIntent(expanded);
    browser.receivePresentationIntent();
    assertEquals(MainActivity.class.getName(), browser.starts.get(1).getComponent().getClassName());
    assertFalse(browser.finished);
    assertFalse(config.isPictureInPictureEnabled());
  }

  @Test
  public void anOldPipTaskWithoutExplicitChoiceIsHiddenWithoutDestroyingThePage() {
    prefs.edit().putBoolean(Constants.KEY_PICTURE_IN_PICTURE, true).commit();
    browser.inPicture = true;
    browser.refreshPictureInPicture();
    assertTrue(browser.conversationHidden());
    assertEquals(1, browser.starts.size());
    assertFalse(browser.finished);
    assertFalse(config.isPictureInPictureEnabled());
  }

  @Test
  public void fullscreenReentryAndANewGenerationDoNotReenableThePreference() throws Exception {
    config.setPictureInPictureEnabled(false);
    browser.setIntent(new Intent().putExtra(BrowserConversationRoute.MODE, "FULL"));
    browser.receivePresentationIntent();
    assertFalse(browser.conversationHidden());
    assertFalse(browser.parameters.isAutoEnterEnabled());
    WebLifecycle lifecycle =
        (WebLifecycle) field(app.harnessSession(), app.harnessSession().getClass(), "lifecycle");
    long stopped = lifecycle.beginStop();
    lifecycle.finishStop(stopped);
    long started = lifecycle.beginStart(false, false);
    assertTrue(lifecycle.finishStart(started));
    browser.refreshPictureInPicture();
    assertFalse(browser.parameters.isAutoEnterEnabled());
    assertFalse(browser.onPictureInPictureRequested());
    assertFalse(new ConfigStore(app).isPictureInPictureEnabled());
  }

  private void publishLivePage() throws Exception {
    app.harnessController();
    var state = app.harnessSession();
    var lifecycle = (WebLifecycle) field(state, state.getClass(), "lifecycle");
    var type = Class.forName("com.deepseekharness.app.core.WebLifecycleController$WebRun");
    var constructor = type.getDeclaredConstructor(long.class);
    constructor.setAccessible(true);
    Object run = constructor.newInstance(lifecycle.generation());
    var url = type.getDeclaredField("authUrl");
    url.setAccessible(true);
    url.set(run, "http://127.0.0.1:38557/?token=fixture");
    var launcher = type.getDeclaredField("launcher");
    launcher.setAccessible(true);
    launcher.set(
        run,
        new Process() {
          public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
          }

          public java.io.InputStream getInputStream() {
            return java.io.InputStream.nullInputStream();
          }

          public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
          }

          public int waitFor() {
            return 0;
          }

          public int exitValue() {
            throw new IllegalThreadStateException("fixture alive");
          }

          public void destroy() {}

          public boolean isAlive() {
            return true;
          }
        });
    var current = state.getClass().getDeclaredField("currentRun");
    current.setAccessible(true);
    current.set(state, run);
    assertNotNull(app.harnessController().getReadyWebPageIdentity());
  }

  @Test
  public void repeatedHomeAndLauncherReturnsKeepOneBrowserAndDoNotReplaySessionRequests()
      throws Exception {
    publishLivePage();
    for (int round = 0; round < 6; round++) {
      browser.onUserLeaveHint();
      assertTrue(BrowserTasks.resumeBackground(browser));
      Intent full = browser.starts.get(browser.starts.size() - 1);
      assertEquals(Browser.class.getName(), full.getComponent().getClassName());
      assertEquals("FULL", full.getStringExtra(BrowserConversationRoute.MODE));
      assertTrue(
          BrowserConversationRoute.current(
              full, app.harnessController().getReadyWebPageIdentity()));
      assertFalse(full.hasExtra(BrowserConversationRoute.REQUEST));
      assertFalse(full.hasExtra(BrowserConversationRoute.SESSION));
      assertEquals(
          0, full.getFlags() & (Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
      browser.onNewIntent(full);
      browser.onResume();
      assertFalse(BrowserTasks.resumeBackground(browser));
      assertFalse(browser.conversationHidden());
      assertFalse(browser.finished);
    }
    assertEquals(0, browser.enters);
    assertFalse(config.isPictureInPictureEnabled());
  }

  @Test
  public void explicitBackAndNativeHomeCancelTheBackgroundReturn() throws Exception {
    publishLivePage();
    browser.onUserLeaveHint();
    browser.leavePreview();
    assertFalse(BrowserTasks.resumeBackground(browser));
    assertTrue(browser.conversationHidden());
    assertFalse(browser.finished);
    browser.setIntent(new Intent().putExtra(BrowserConversationRoute.MODE, "FULL"));
    browser.receivePresentationIntent();
    browser.onUserLeaveHint();
    BrowserTasks.openMain(browser);
    assertFalse(BrowserTasks.resumeBackground(browser));
    assertFalse(browser.finished);
  }

  @Test
  public void enabledPipHomeStillRestoresTheOriginalBrowserFullScreen() throws Exception {
    publishLivePage();
    config.setPictureInPictureEnabled(true);
    browser.onUserLeaveHint();
    browser.inPicture = true;
    assertTrue(BrowserTasks.resumeBackground(browser));
    Intent full = browser.starts.get(browser.starts.size() - 1);
    assertEquals(Browser.class.getName(), full.getComponent().getClassName());
    browser.inPicture = false;
    browser.setIntent(full);
    browser.receivePresentationIntent();
    assertFalse(browser.conversationHidden());
    assertFalse(frame.isFrozen());
    assertFalse(browser.finished);
    assertTrue(config.isPictureInPictureEnabled());
  }

  @Test
  public void stopOrReplacedPageCannotRestoreTheOldBackgroundIntent() throws Exception {
    publishLivePage();
    browser.onUserLeaveHint();
    var state = app.harnessSession();
    var lifecycle = (WebLifecycle) field(state, state.getClass(), "lifecycle");
    long stopped = lifecycle.beginStop();
    assertFalse(BrowserTasks.resumeBackground(browser));
    lifecycle.finishStop(stopped);
    long started = lifecycle.beginStart(false, false);
    assertTrue(lifecycle.finishStart(started));
    publishLivePage();
    assertFalse(BrowserTasks.resumeBackground(browser));
    assertFalse(browser.finished);
  }

  @Test
  public void mainNewIntentDoesNotMistakeTheRetainedNoActionBaseIntentForNativeNavigation()
      throws Exception {
    publishLivePage();
    browser.onUserLeaveHint();
    var main = Robolectric.buildActivity(NativeMain.class).get();
    main.onNewIntent(
        new Intent(app, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("open_launch", true));
    assertEquals(1, main.starts.size());
    assertEquals(Browser.class.getName(), main.starts.get(0).getComponent().getClassName());
    assertFalse(browser.finished);
  }

  @Test
  public void stopWithoutUserLeaveHintStillReturnsThroughTheRealMainNewIntentCallback()
      throws Exception {
    publishLivePage();
    browser.pauseAndStop();
    var main = Robolectric.buildActivity(NativeMain.class).get();
    main.onNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
    assertEquals(1, main.starts.size());
    assertEquals("FULL", main.starts.get(0).getStringExtra(BrowserConversationRoute.MODE));
    assertFalse(browser.finished);
  }

  @Test
  public void stoppedBrowserReturnsThroughMainResumeWhenLauncherDeliversNoNewIntent()
      throws Exception {
    publishLivePage();
    browser.pauseAndStop();
    var main = Robolectric.buildActivity(NativeMain.class).get();
    attachFragmentLifecycle(main);
    main.setIntent(new Intent(app, MainActivity.class).putExtra("open_launch", true));
    main.onResume();
    assertEquals(1, main.starts.size());
    assertEquals(Browser.class.getName(), main.starts.get(0).getComponent().getClassName());
    assertFalse(browser.finished);
  }

  @Test
  public void sixPauseStopAndRealMainIntentCyclesReturnToTheSameBrowser() throws Exception {
    publishLivePage();
    var main = Robolectric.buildActivity(NativeMain.class).get();
    for (int round = 0; round < 6; round++) {
      browser.pauseAndStop(); // Deliberately no onUserLeaveHint; use the actual stop callback.
      main.onNewIntent(new Intent(app, MainActivity.class).putExtra("open_launch", true));
      assertEquals(round + 1, main.starts.size());
      Intent full = main.starts.get(round);
      assertEquals(Browser.class.getName(), full.getComponent().getClassName());
      assertFalse(full.hasExtra(BrowserConversationRoute.REQUEST));
      browser.onNewIntent(full);
      browser.onResume();
      assertFalse(browser.conversationHidden());
      assertFalse(browser.finished);
    }
    assertFalse(config.isPictureInPictureEnabled());
    browser.leavePreview();
    main.onNewIntent(new Intent(app, MainActivity.class).putExtra("open_launch", true));
    assertEquals(6, main.starts.size()); // Back stays Main; no seventh Web return.
    assertFalse(browser.finished);
  }

  @Test
  public void transientContentAtMainEntryDoesNotDestroyTheHomeReturnIntent() throws Exception {
    publishLivePage();
    browser.pauseAndStop();
    var main = Robolectric.buildActivity(NativeMain.class).get();
    browser.contentReady = false;
    main.onNewIntent(new Intent(app, MainActivity.class));
    assertEquals(0, main.starts.size());
    browser.contentReady = true;
    main.onNewIntent(new Intent(app, MainActivity.class));
    assertEquals(1, main.starts.size());
    assertEquals(Browser.class.getName(), main.starts.get(0).getComponent().getClassName());
  }

  @Test
  public void failedFrameworkDispatchCanRetryTheSameHomeReturnWithoutNewWeb() throws Exception {
    publishLivePage();
    browser.pauseAndStop();
    var main = Robolectric.buildActivity(NativeMain.class).get();
    main.rejectStarts = true;
    main.onNewIntent(new Intent(app, MainActivity.class));
    assertEquals(0, main.starts.size());
    main.rejectStarts = false;
    main.onNewIntent(new Intent(app, MainActivity.class));
    assertEquals(1, main.starts.size());
    assertEquals(Browser.class.getName(), main.starts.get(0).getComponent().getClassName());
  }

  @Test
  public void mainResumeDoesNotConsumeOrDuplicateAnUnacknowledgedBrowserDispatch()
      throws Exception {
    publishLivePage();
    browser.pauseAndStop();
    var main = Robolectric.buildActivity(NativeMain.class).get();
    attachFragmentLifecycle(main);
    main.onNewIntent(new Intent(app, MainActivity.class));
    main.onResume();
    assertEquals(1, main.starts.size());
    // No Web callback has happened yet: another physical launcher entry can retry.
    main.onNewIntent(new Intent(app, MainActivity.class));
    assertEquals(2, main.starts.size());
    Intent full = main.starts.get(1);
    browser.onNewIntent(full);
    browser.onResume();
    assertFalse(BrowserTasks.resumeBackground(main));
    browser.leavePreview();
    main.onNewIntent(new Intent(app, MainActivity.class));
    assertEquals(2, main.starts.size());
    assertFalse(browser.finished);
  }

  @Test
  public void temporaryFrameworkActivityDoesNotCancelTheRememberedHomeReturn() throws Exception {
    publishLivePage();
    browser.pauseAndStop();
    browser.startActivityForResult(
        new Intent(Intent.ACTION_GET_CONTENT).setType("text/plain"), 17, null);
    var main = Robolectric.buildActivity(NativeMain.class).get();
    main.onNewIntent(new Intent(app, MainActivity.class));
    assertEquals(1, main.starts.size());
    assertEquals(Browser.class.getName(), main.starts.get(0).getComponent().getClassName());
    assertFalse(browser.finished);
  }

  @Test
  @Config(shadows = RejectedAppTask.class)
  public void appTaskRejectionFallsBackThroughTheRealActivityContextToTheSameBrowser()
      throws Exception {
    publishLivePage();
    browser.pauseAndStop();
    var info = new android.app.ActivityManager.RecentTaskInfo();
    info.id = browser.getTaskId();
    info.baseActivity = new android.content.ComponentName(app, Browser.class);
    var task = org.robolectric.shadows.ShadowAppTask.newInstance();
    Shadow.<org.robolectric.shadows.ShadowAppTask>extract(task).setTaskInfo(info);
    var manager = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
    Shadow.<org.robolectric.shadows.ShadowActivityManager>extract(manager)
        .setAppTasks(List.of(task));
    RejectedAppTask.attempts = 0;
    // This Main uses the actual framework Activity.startActivity, not a recording override.
    var main = Robolectric.buildActivity(FrameworkMain.class).get();
    main.onNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
    assertEquals(1, RejectedAppTask.attempts);
    Intent full =
        Shadow.<org.robolectric.shadows.ShadowActivity>extract(main).getNextStartedActivity();
    assertNotNull(full);
    assertEquals(Browser.class.getName(), full.getComponent().getClassName());
    assertEquals("FULL", full.getStringExtra(BrowserConversationRoute.MODE));
    assertTrue((full.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    assertEquals(
        0,
        full.getFlags()
            & (Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
    assertFalse(full.hasExtra(BrowserConversationRoute.REQUEST));
    assertTrue(
        BrowserConversationRoute.current(full, app.harnessController().getReadyWebPageIdentity()));
    browser.onNewIntent(full);
    browser.onResume();
    assertFalse(BrowserTasks.resumeBackground(main));
    assertFalse(browser.finished);
    assertFalse(Shadow.<org.robolectric.shadows.ShadowAppTask>extract(task).isFinishedAndRemoved());
  }
}
