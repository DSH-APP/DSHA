package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import java.util.ArrayList;
import java.util.List;
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

/** 实际Main/FragmentManager旋转恢复，后台核对被刻意延后；不运行guest或模型请求。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = MainEntryLayoutLifecycleTest.App.class,
    instrumentedPackages = {"com.deepseekharness.app.backup"},
    shadows = {
      MainEntryLayoutLifecycleTest.Controller.class,
      MainEntryLayoutLifecycleTest.Environment.class,
      com.deepseekharness.app.runtime.RuntimeToolsRegistryAndroidTest.HostFileSystem.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class MainEntryLayoutLifecycleTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Controller {
    static HarnessController value;
    static boolean unresolved;
    static int stopRequests, startRequests;
    static final List<Runnable> checks = new ArrayList<>();

    @Implementation
    protected static HarnessController get(Context context) {
      return value;
    }

    @Implementation
    protected void restoreRunningWebSession(Runnable completed) {
      if (completed != null) checks.add(completed);
    }

    @Implementation
    protected void addReadyWebPageListener(Runnable ignored) {}

    @Implementation
    protected void removeReadyWebPageListener(Runnable ignored) {}

    @Implementation
    protected boolean isEnvironmentReady() {
      return false;
    }

    @Implementation
    protected boolean hasUnresolvedWebSession() {
      return unresolved;
    }

    @Implementation
    protected void stopWeb(java.util.function.Consumer<String> status) {
      stopRequests++;
    }

    @Implementation
    protected boolean startProtectedWeb(
        com.deepseekharness.app.util.WebStartProtection.Ticket ticket,
        boolean safe,
        boolean recover,
        java.util.function.Consumer<String> status) {
      startRequests++;
      throw new AssertionError("Restoring a gated page must not start a guest");
    }
  }

  @Implements(value = EnvironmentUiStatus.class, isInAndroidSdk = false)
  public static final class Environment {
    @Implementation
    protected static EnvironmentUiStatus.Snapshot get(Context context) {
      return new EnvironmentUiStatus.Snapshot(true, true, false, false);
    }
  }

  public static final class SavedPage extends Fragment {
    static int viewsCreated;

    public SavedPage() {
      super(android.R.layout.simple_list_item_1);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
      viewsCreated++;
      return super.onCreateView(inflater, container, state);
    }
  }

  /** Restored legacy terminal page with a harmless view and no terminal session creation. */
  public static final class SavedTerminal extends TerminalFragment {
    static int viewsCreated;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
      viewsCreated++;
      return new View(inflater.getContext());
    }

    @Override
    public void onViewCreated(View view, Bundle state) {}
  }

  @Before
  public void prepare() throws Exception {
    var app = (App) RuntimeEnvironment.getApplication();
    // Robolectric has no APK native library directory. The real controller records this existing
    // directory without loading a library; host descriptor syscalls use the established NOFOLLOW
    // port.
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    String installed = com.deepseekharness.app.core.InstallationIdentity.loadOrCreate(app);
    assertEquals(installed, app.installationId());
    assertEquals(installed, com.deepseekharness.app.core.InstallationIdentity.loadOrCreate(app));
    Controller.value = org.robolectric.shadow.api.Shadow.newInstanceOf(HarnessController.class);
    Controller.unresolved = false;
    Controller.stopRequests = Controller.startRequests = 0;
    Controller.checks.clear();
    SavedPage.viewsCreated = 0;
    SavedTerminal.viewsCreated = 0;
    ConfigStore config = new ConfigStore(RuntimeEnvironment.getApplication());
    config.setWelcomed(true);
    config.setCheckUpdate(false);
  }

  @Test
  public void pendingCheckKeepsRealContainerAndRotatedFragmentAttached() throws Exception {
    var host = Robolectric.buildActivity(MainActivity.class).create().start().resume();
    var first = host.get();
    assertNotNull(first.findViewById(R.id.fragment_container));
    assertFalse(Controller.checks.isEmpty());
    first
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, new SavedPage(), "saved-page")
        .commitNow();
    assertSame(
        first.findViewById(R.id.fragment_container),
        first
            .getSupportFragmentManager()
            .findFragmentByTag("saved-page")
            .requireView()
            .getParent());
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    var restoredHost = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume();
    var restored = restoredHost.get();
    Fragment page = restored.getSupportFragmentManager().findFragmentByTag("saved-page");
    assertNotNull(page);
    assertNull("核对未完成不能重新创建旧终端等页面", page.getView());
    var release = MainActivity.class.getDeclaredMethod("releaseGatedPages", boolean.class);
    release.setAccessible(true);
    release.invoke(restored, true);
    assertNotNull(page.getView());
    assertSame(restored.findViewById(R.id.fragment_container), page.requireView().getParent());
    assertNotNull(restored.findViewById(R.id.bottom_nav));
    restoredHost.pause().stop().destroy();
  }

  @Test
  public void homeBackDuringEntryCheckGoesToDesktopWithoutFinishingOrOpeningAnotherMain() {
    var host = Robolectric.buildActivity(MainActivity.class).create().start().resume();
    var main = host.get();
    org.robolectric.shadows.ShadowActivity shadow = org.robolectric.shadow.api.Shadow.extract(main);
    while (shadow.getNextStartedActivity() != null) {}
    main.getOnBackPressedDispatcher().onBackPressed();
    assertTrue(shadow.isTaskMovedToBack());
    assertFalse(main.isFinishing());
    var home = shadow.getNextStartedActivity();
    assertNotNull(home);
    assertEquals(android.content.Intent.ACTION_MAIN, home.getAction());
    assertTrue(home.hasCategory(android.content.Intent.CATEGORY_HOME));
    assertNull(home.getComponent());
    assertNull(shadow.getNextStartedActivity());
    host.pause().stop().destroy();
  }

  @Test
  public void nestedPageBackPopsThePageBeforeLeavingMain() {
    var host = Robolectric.buildActivity(MainActivity.class).create().start().resume();
    var main = host.get();
    org.robolectric.shadows.ShadowActivity shadow = org.robolectric.shadow.api.Shadow.extract(main);
    main.getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, new SavedPage(), "nested")
        .addToBackStack("nested")
        .commit();
    main.getSupportFragmentManager().executePendingTransactions();
    assertEquals(1, main.getSupportFragmentManager().getBackStackEntryCount());
    main.getOnBackPressedDispatcher().onBackPressed();
    assertEquals(0, main.getSupportFragmentManager().getBackStackEntryCount());
    assertFalse(shadow.isTaskMovedToBack());
    assertFalse(main.isFinishing());
    host.pause().stop().destroy();
  }

  @Test
  public void readyGateIgnoresRetiredPreparationArgumentAndRestoresLocalControls()
      throws Exception {
    var restored = restoreLaunch(true);
    try {
      showMain(restored);
      Fragment current =
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertTrue(current instanceof LaunchFragment);
      assertSame(restored.page, current);
      assertEquals(
          View.VISIBLE, current.requireView().findViewById(R.id.launch_controls).getVisibility());
      assertNull(nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void unchangedGateKeepsRestoredLaunchInstanceAndItsView() throws Exception {
    var restored = restoreLaunch(false);
    try {
      showMain(restored);
      assertSame(
          restored.page,
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container));
      View view = restored.page.requireView();
      ((com.google.android.material.bottomnavigation.BottomNavigationView)
              restored.main.findViewById(R.id.bottom_nav))
          .setSelectedItemId(R.id.nav_launch);
      restored.main.getSupportFragmentManager().executePendingTransactions();
      assertSame(
          restored.page,
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container));
      assertSame(view, restored.page.requireView());
      assertNull(nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void launchUsesOneActivityTrackAndAnnouncesStatus() throws Exception {
    var restored = restoreLaunch(false);
    try {
      showMain(restored);
      View page = restored.page.requireView();
      ViewGroup actions = page.findViewById(R.id.launch_actions);
      View track = page.findViewById(R.id.launch_activity);
      assertEquals(
          actions.indexOfChild(page.findViewById(R.id.launch_subtitle)) + 1,
          actions.indexOfChild(track));
      for (int i = 0; i < actions.getChildCount(); i++) {
        assertFalse(actions.getChildAt(i) instanceof android.widget.ProgressBar);
      }
      assertEquals(
          View.ACCESSIBILITY_LIVE_REGION_POLITE,
          page.findViewById(R.id.launch_status).getAccessibilityLiveRegion());
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void quickEntryRowsMoveToMoreBubbleAndTerminalNavigationStillGated() throws Exception {
    var restored = restoreLaunch(false);
    try {
      showMain(restored);
      View page = restored.page.requireView();
      assertNotNull(page.findViewById(R.id.launch_activity));
      // T145：快捷入口卡片收进顶栏「+」气泡，启动页不再有终端 / 环境 / 地址行。
      var resources = page.getResources();
      for (String retiredId :
          new String[] {
            "launch_shortcuts", "launch_terminal_row", "launch_environment_row", "launch_address"
          }) {
        int retired = resources.getIdentifier(retiredId, "id", restored.main.getPackageName());
        if (retired != 0) assertNull(page.findViewById(retired));
      }
      View more = restored.main.findViewById(R.id.btn_more);
      assertNotNull(more);
      assertEquals(View.VISIBLE, more.getVisibility());
      var nav =
          (com.google.android.material.bottomnavigation.BottomNavigationView)
              restored.main.findViewById(R.id.bottom_nav);
      int selected = nav.getSelectedItemId();
      assertNull(nextActivity(restored.main));
      nav.setSelectedItemId(R.id.nav_terminal);
      Intent recovery = nextActivity(restored.main);
      assertNotNull(recovery);
      assertEquals(StartupRecoveryActivity.class.getName(), recovery.getComponent().getClassName());
      assertEquals(selected, nav.getSelectedItemId());
      assertSame(
          restored.page,
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container));
      assertSame(page, restored.page.requireView());
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void unknownSessionDoesNotReopenRestoredLegacyTerminal() throws Exception {
    var restored = restorePage(new SavedTerminal());
    try {
      Controller.unresolved = true;
      SavedTerminal.viewsCreated = 0;
      showMain(restored);
      assertTrue(
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container)
              instanceof LaunchFragment);
      assertEquals(0, SavedTerminal.viewsCreated);
      assertFalse(restored.main.getIntent().hasExtra("open_terminal"));
      assertNull(nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void unknownSessionReplacesMatchingRestoredLaunchAndShowsErrorWithNormalStop()
      throws Exception {
    // Construct only the real controller's display collaborators. Restore/start/stop ports remain
    // isolated above; no guest, maintenance, shell, or model operation is dispatched.
    Controller.value = new HarnessController(RuntimeEnvironment.getApplication());
    var restored = restoreLaunch(false);
    try {
      Controller.unresolved = true;
      showMain(restored);
      Fragment current =
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertTrue(current instanceof LaunchFragment);
      assertNotSame(restored.page, current);
      assertNotNull(current.getView());
      assertSame(
          restored.main.findViewById(R.id.fragment_container), current.requireView().getParent());
      assertEquals(
          View.VISIBLE, current.requireView().findViewById(R.id.launch_controls).getVisibility());
      var refresh = LaunchFragment.class.getDeclaredMethod("refreshRunState");
      refresh.setAccessible(true);
      refresh.invoke(current);
      assertEquals(
          com.deepseekharness.app.core.WebSessionRestoreText.state(""),
          ((android.widget.TextView) current.requireView().findViewById(R.id.launch_run_state))
              .getText()
              .toString());
      // 完整原因不再占大标题：副标题收成摘要，异常状态默认展开完整原因（折叠卡，不再用 ⓘ）。
      android.widget.TextView subtitle = current.requireView().findViewById(R.id.launch_subtitle);
      com.deepseekharness.app.util.SummaryText.Split split =
          com.deepseekharness.app.util.SummaryText.split(
              com.deepseekharness.app.core.WebSessionRestoreText.unresolved(""));
      assertEquals(split.summary, subtitle.getText().toString());
      assertTrue("恢复核对异常默认展开", com.deepseekharness.app.ui.DisclosureCard.expanded(subtitle));
      assertTrue(subtitle.isClickable());
      subtitle.performClick();
      assertFalse("折叠后不再显示完整原因", com.deepseekharness.app.ui.DisclosureCard.expanded(subtitle));
      assertTrue(current.requireView().findViewById(R.id.launch_stop).isEnabled());
      assertTrue(current.requireView().findViewById(R.id.launch_stop).performClick());
      assertEquals(1, Controller.stopRequests);
      assertEquals(0, Controller.startRequests);
      assertNull("Unknown session stays on its diagnostic/stop page", nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void unknownSessionDoesNotRecreateTheRestoredBusinessPageWhileShowingLaunch()
      throws Exception {
    var restored = restorePage(new SavedPage());
    try {
      Controller.unresolved = true;
      SavedPage.viewsCreated = 0;
      showMain(restored);
      Fragment current =
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertTrue(current instanceof LaunchFragment);
      assertNotNull(current.getView());
      assertFalse(restored.page.isAdded());
      assertNull(restored.page.getView());
      assertEquals(
          "Unknown environment must not recreate an old terminal/plugin view",
          0,
          SavedPage.viewsCreated);
      assertEquals(0, Controller.startRequests);
      assertNull(nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void unknownSessionRebindsStalePreparationPageWithoutExtractOrAutomaticWebEntry()
      throws Exception {
    var restored = restoreLaunch(true);
    try {
      Controller.unresolved = true;
      showMain(restored);
      Fragment current =
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertNotSame(restored.page, current);
      assertEquals(
          View.VISIBLE, current.requireView().findViewById(R.id.launch_controls).getVisibility());
      assertNull("Unknown session must remain on Main", nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void openWebIntentSurvivesRetiredPreparationArgumentOnRestoredPage() throws Exception {
    var restored = restoreLaunch(true);
    try {
      restored.main.getIntent().putExtra("open_web", true);
      showMain(restored);
      Fragment current =
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertSame(restored.page, current);
      var pending = LaunchFragment.class.getDeclaredField("pendingAutoEnter");
      pending.setAccessible(true);
      assertTrue("The restored page must keep the explicit request", pending.getBoolean(current));
      assertFalse(restored.main.getIntent().hasExtra("open_web"));
      assertNull("No runtime is ready, so no Web page may open", nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  private static RestoredLaunch restoreLaunch(boolean preparation) {
    LaunchFragment first = new LaunchFragment();
    Bundle args = new Bundle();
    // 升级前 Activity 保存的旧参数没有生产入口，但也不能隐藏本机控件。
    args.putBoolean("prepare_local", preparation);
    first.setArguments(args);
    var restored = restorePage(first);
    assertTrue(restored.page instanceof LaunchFragment);
    assertEquals(preparation, preparationPage(restored.page));
    return restored;
  }

  private static RestoredLaunch restorePage(Fragment first) {
    Intent launcher =
        new Intent(RuntimeEnvironment.getApplication(), MainActivity.class)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER);
    var firstHost =
        Robolectric.buildActivity(MainActivity.class, launcher).create().start().resume();
    firstHost
        .get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, first, "restored-launch")
        .commitNow();
    Bundle saved = new Bundle();
    firstHost.pause().saveInstanceState(saved).stop().destroy();
    var host =
        Robolectric.buildActivity(MainActivity.class, new Intent(launcher))
            .create(saved)
            .start()
            .resume();
    MainActivity main = host.get();
    Fragment page = main.getSupportFragmentManager().findFragmentByTag("restored-launch");
    assertNotNull(page);
    assertNull("The gate must hold the restored view", page.getView());
    while (nextActivity(main) != null) {}
    return new RestoredLaunch(host, main, page, saved);
  }

  private static boolean preparationPage(Fragment page) {
    return page.getArguments() != null && page.getArguments().getBoolean("prepare_local", false);
  }

  private static Intent nextActivity(MainActivity main) {
    org.robolectric.shadows.ShadowActivity shadow = org.robolectric.shadow.api.Shadow.extract(main);
    return shadow.getNextStartedActivity();
  }

  private static void showMain(RestoredLaunch restored) throws Exception {
    var probe = MainActivity.class.getDeclaredField("restoreProbeStarted");
    probe.setAccessible(true);
    probe.setBoolean(restored.main, true);
    var show =
        MainActivity.class.getDeclaredMethod(
            "showMain", Bundle.class, ConfigStore.class, HarnessController.class);
    show.setAccessible(true);
    show.invoke(restored.main, restored.saved, new ConfigStore(restored.main), Controller.value);
    restored.main.getSupportFragmentManager().executePendingTransactions();
  }

  private record RestoredLaunch(
      org.robolectric.android.controller.ActivityController<MainActivity> host,
      MainActivity main,
      Fragment page,
      Bundle saved) {}
}
