package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.ui.LaunchFragment;
import com.deepseekharness.app.ui.MainActivity;
import com.deepseekharness.app.ui.StartupRecoveryActivity;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.json.JSONObject;
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

/** Real model, activity callbacks, task state and gate; only disk/guest/composition ports are isolated. */
@RunWith(StartupRecoveryLifecycleTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    instrumentedPackages = "com.deepseekharness.app.core",
    shadows = {
      StartupRecoveryLifecycleTest.Controller.class,
      StartupRecoveryLifecycleTest.Repairs.class,
      StartupRecoveryLifecycleTest.Tasks.class,
      StartupRecoveryLifecycleTest.Maintenance.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class StartupRecoveryLifecycleTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Controller {
    static HarnessController value;
    static StartupDiagnostics diagnostics;
    static ConfigStore config;

    @Implementation
    protected static HarnessController get(Context context) {
      return value;
    }

    @Implementation
    protected StartupDiagnostics startupDiagnostics() {
      return diagnostics;
    }

    @Implementation
    protected boolean isEnvironmentReady() {
      return true;
    }

    @Implementation
    protected ConfigStore config() {
      return config;
    }

    @Implementation
    protected boolean isStarting() {
      return false;
    }

    @Implementation
    protected boolean isStopping() {
      return false;
    }
  }

  @Implements(value = StartupRepairs.class, isInAndroidSdk = false)
  public static final class Repairs {
    static int lists, plugins;
    static boolean unreadable;

    @Implementation
    protected static JSONObject list(HarnessController controller) throws Exception {
      lists++;
      if (unreadable) throw new java.io.IOException("RECOVERY_FORMAT");
      return new JSONObject("{\"snapshots\":[{\"slot\":\"before-1\"}],\"pending\":false}");
    }

    @Implementation
    protected static JSONObject plugins(HarnessController controller) throws Exception {
      plugins++;
      return new JSONObject(
          "{\"items\":[{\"name\":\"private-fixture-plugin\",\"deletable\":true}]}");
    }
  }

  @Implements(value = BackupTask.class, isInAndroidSdk = false)
  public static final class Tasks {
    static BackupTask value;

    @Implementation
    protected static BackupTask get(Context context) {
      return value;
    }
  }

  @Implements(value = MaintenanceCoordinator.class, isInAndroidSdk = false)
  public static final class Maintenance {
    @Implementation
    protected static boolean pending(HarnessController controller) {
      return false; // This fixture has no interrupted disk transaction.
    }
  }

  private ScheduledExecutorService history;
  private StartupRecoveryModel model;
  private StartupRecoveryActivity activity;
  private TextView progress;
  private Runnable refresh;

  public static final class SelectedNavigation
      extends com.google.android.material.bottomnavigation.BottomNavigationView {
    int selected;

    public SelectedNavigation(Context context) {
      super(context);
    }

    @Override
    public int getSelectedItemId() {
      return selected;
    }
  }

  public static final class NativeMain extends MainActivity {
    TextView banner;
    SelectedNavigation navigation;
    androidx.lifecycle.LifecycleRegistry visibleLifecycle;
    final java.util.List<android.content.Intent> starts = new java.util.ArrayList<>();

    @Override
    public androidx.lifecycle.Lifecycle getLifecycle() {
      return visibleLifecycle == null ? super.getLifecycle() : visibleLifecycle;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends android.view.View> T findViewById(int id) {
      return (T)
          (id == R.id.environment_recovery_banner
              ? banner
              : id == R.id.bottom_nav ? navigation : null);
    }

    @Override
    public void startActivity(android.content.Intent intent) {
      starts.add(intent);
    }
  }

  private static void field(Object object, String name, Object value) throws Exception {
    var field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(object, value);
  }

  private static Object field(Object object, String name) throws Exception {
    Class<?> owner = object.getClass();
    while (owner != null) {
      try {
        owner.getDeclaredField(name);
        break;
      } catch (NoSuchFieldException missing) {
        owner = owner.getSuperclass();
      }
    }
    var field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  @Before
  public void createIsolatedPortsAndRealActivityCallbacks() throws Exception {
    Application app = RuntimeEnvironment.getApplication();
    history = Executors.newSingleThreadScheduledExecutor();
    Controller.diagnostics =
        new StartupDiagnostics(
            app.getFilesDir(), new JvmBackupFileSystem(), () -> 0L, (stage, detail) -> {}, history);
    Controller.value = org.robolectric.shadow.api.Shadow.newInstanceOf(HarnessController.class);
    Controller.config = new ConfigStore(app);
    Tasks.value = new BackupTask(app, Controller.value);
    Repairs.lists = Repairs.plugins = 0;
    Repairs.unreadable = false;
    model = new StartupRecoveryModel(app);
    activity = Robolectric.buildActivity(StartupRecoveryActivity.class).get();
    progress = new TextView(app);
    field(activity, "progress", progress);
    field(activity, "model", model);
    refresh = (Runnable) field(activity, "refresh");
    // Bind the exact production error observer without inflating unrelated native UI/resources.
    Method observer = null;
    for (Method method : StartupRecoveryActivity.class.getDeclaredMethods())
      if (method.isSynthetic()
          && method.getName().contains("lambda$onCreate$")
          && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[] {String.class})) {
        assertNull(observer);
        observer = method;
      }
    assertNotNull(observer);
    observer.setAccessible(true);
    Method errorObserver = observer;
    model.error.observeForever(
        value -> {
          try {
            errorObserver.invoke(activity, value);
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        });
  }

  private void awaitRead() throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while ((boolean) field(model, "working") && System.nanoTime() < deadline) Thread.sleep(10);
    assertFalse("Recovery read did not finish", (boolean) field(model, "working"));
    org.robolectric.shadows.ShadowLooper main =
        org.robolectric.shadow.api.Shadow.extract(Looper.getMainLooper());
    main.idle();
  }

  @After
  public void releaseFixtureOnly() throws Exception {
    if (activity != null) ((android.os.Handler) field(activity, "ui")).removeCallbacks(refresh);
    history.shutdownNow();
    assertFalse(
        "The UI must neither leak nor release another task's lease", EnvironmentTaskGate.isBusy());
  }

  @Test
  public void ordinaryQueryCompletionRetriesReadAfterReturnAndClearsOnlyItsError()
      throws Exception {
    try (var query = EnvironmentTaskGate.tryAcquire("插件操作")) {
      model.load();
      awaitRead();
      assertEquals("RECOVERY_BUSY", model.error.getValue());
      assertNull(model.contents.getValue());
      assertTrue(Tasks.value.busy());
      assertFalse(Tasks.value.maintenanceBusy());
      for (int i = 0; i < 3; i++) refresh.run();
      assertEquals(0, Repairs.lists);
      assertEquals("插件操作", EnvironmentTaskGate.activeKind());
    }
    // The owner can finish while this page is paused; no observed busy edge is required on return.
    refresh.run();
    awaitRead();
    assertNotNull("The idle page must retry its contended read", model.contents.getValue());
    assertEquals(1, model.contents.getValue().getJSONArray("snapshots").length());
    assertEquals(1, model.contents.getValue().getJSONArray("items").length());
    assertEquals("", model.error.getValue());
    assertEquals("", progress.getText().toString());
    for (int i = 0; i < 3; i++) refresh.run();
    assertEquals("A successful read must not be replayed on every poll", 1, Repairs.lists);
  }

  @Test
  public void successfulManualReadClearsBusyTextButPreservesANewerTaskResult() throws Exception {
    try (var query = EnvironmentTaskGate.tryAcquire("插件操作")) {
      model.load();
      awaitRead();
      assertFalse(progress.getText().toString().isEmpty());
    }
    model.load();
    awaitRead();
    assertEquals("", progress.getText().toString());
    try (var query = EnvironmentTaskGate.tryAcquire("插件操作")) {
      model.load();
      awaitRead();
      progress.setText("已完成所选插件修复");
    }
    model.load();
    awaitRead();
    assertEquals("已完成所选插件修复", progress.getText().toString());
  }

  @Test
  public void unreadableSnapshotIsNotAutomaticallyRetriedOrMisreportedAsBusy() throws Exception {
    Repairs.unreadable = true;
    model.load();
    awaitRead();
    assertEquals("RECOVERY_FORMAT", model.error.getValue());
    for (int i = 0; i < 3; i++) refresh.run();
    assertEquals(1, Repairs.lists);
    assertEquals(0, Repairs.plugins);
    assertFalse(EnvironmentTaskGate.isBusy());
  }

  @Test
  public void explicitNativeDestinationCannotBeOvertakenByPersistentStartupFailure()
      throws Exception {
    Method policy =
        MainActivity.class.getDeclaredMethod(
            "startupRecoveryDestination", int.class, Fragment.class);
    policy.setAccessible(true);
    assertTrue((boolean) policy.invoke(null, R.id.nav_launch, null));
    assertTrue((boolean) policy.invoke(null, R.id.nav_launch, new LaunchFragment()));
    for (int id : new int[] {R.id.nav_plugins, R.id.nav_settings, R.id.nav_terminal}) {
      assertFalse((boolean) policy.invoke(null, id, null));
      assertFalse((boolean) policy.invoke(null, id, new LaunchFragment()));
    }
    assertFalse((boolean) policy.invoke(null, R.id.nav_launch, new Fragment()));
  }

  @Test
  public void actualMainPollKeepsNativePageAndBannerThenRoutesLaunchOnlyAfterGateCloses()
      throws Exception {
    // Freeze only the asynchronous display cache; execution/pending gates remain production code.
    Class<?> display = Class.forName("com.deepseekharness.app.ui.EnvironmentUiStatus");
    var value = display.getDeclaredField("value");
    var running = display.getDeclaredField("running");
    value.setAccessible(true);
    running.setAccessible(true);
    Object oldValue = value.get(null);
    boolean oldRunning = running.getBoolean(null);
    var snapshot = oldValue.getClass().getDeclaredConstructors()[0];
    snapshot.setAccessible(true);
    value.set(null, snapshot.newInstance(true, true, false, false));
    running.setBoolean(null, true);
    var main = Robolectric.buildActivity(NativeMain.class).get();
    main.banner = new TextView(RuntimeEnvironment.getApplication());
    // Only getSelectedItemId is used; avoid inflating unrelated Material resources in NoApkRunner.
    Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
    var unsafeField = unsafeType.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    main.navigation =
        (SelectedNavigation)
            unsafeType
                .getMethod("allocateInstance", Class.class)
                .invoke(unsafeField.get(null), SelectedNavigation.class);
    main.visibleLifecycle = new androidx.lifecycle.LifecycleRegistry(main);
    main.visibleLifecycle.setCurrentState(androidx.lifecycle.Lifecycle.State.RESUMED);
    Runnable poll = (Runnable) field(main, "refreshRecovery");
    Controller.config.requestStartupRecovery(true);
    try {
      for (int destination : new int[] {R.id.nav_plugins, R.id.nav_settings, R.id.nav_terminal}) {
        main.navigation.selected = destination;
        poll.run();
        assertEquals("An explicit native destination must stay in front", 0, main.starts.size());
        assertEquals(android.view.View.VISIBLE, main.banner.getVisibility());
      }
      main.navigation.selected = R.id.nav_launch;
      try (var query = EnvironmentTaskGate.tryAcquire("插件操作")) {
        poll.run();
        assertEquals(0, main.starts.size());
        assertFalse(main.banner.getText().toString().contains("维护进行中"));
      }
      poll.run();
      assertEquals(1, main.starts.size());
      assertEquals(
          StartupRecoveryActivity.class.getName(),
          main.starts.get(0).getComponent().getClassName());
      poll.run();
      assertEquals("The same failure must not launch twice", 1, main.starts.size());
    } finally {
      ((android.os.Handler) field(main, "recoveryHandler")).removeCallbacks(poll);
      value.set(null, oldValue);
      running.setBoolean(null, oldRunning);
    }
  }
}
