package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.PowerModePolicy;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 主界面外壳：启动门禁 + 底部导航（启动 / 插件 / 设置 / 终端）+ 顶栏标题 + 「+」更多气泡入口。
 */
public class MainActivity extends AppCompatActivity {

  /** Read the lifecycle tracker; this class keeps no static reference to a page. */
  public static MainActivity active() {
    androidx.fragment.app.FragmentActivity activity =
        com.deepseekharness.app.ForegroundActivity.current();
    return activity instanceof MainActivity ? (MainActivity) activity : null;
  }

  private com.deepseekharness.app.core.UpdateEngine startupUpdates;
  private com.google.android.material.snackbar.Snackbar updateNotice;
  private boolean requestingLocalNetwork;
  private boolean restoreProbeStarted;
  private boolean discardedLauncherEntry;
  private boolean leavingUi;
  private boolean mainReady;
  private Bundle pendingMainState;
  private BootScreen bootScreen;

  /** 本进程是否已播放过开机开场动画（只在冷启动首个入口播放一次）。 */
  private static boolean bootIntroPlayed;

  /** 本次桌面图标启动是否按偏好直启 Web；入口门禁未放行前只是候选。 */
  private boolean directWebArmed;

  private DirectWebLaunch directWeb;
  private PendingMain directWebMainState;

  /**
   * 直启打开 Web 后留在 Main 上的加载页遮罩。Main 在 Web 之下完成初始化，但遮罩保持到 Main
   * 真正作为原生页面回到前台才收起：打开 Web 的任务切换期间、以及之后点桌面图标经 Main
   * 返回原网页时，系统显示的都是遮罩，而不是主界面。
   */
  private BootScreen webCover;

  /** 回到前台仍停在 Main 时，等待“返回原网页”派发的宽限；超时收起遮罩，不让 Main 卡在加载页。 */
  private static final long WEB_COVER_GRACE_MS = 1_500L;

  private final Runnable releaseWebCover =
      () -> {
        if (getLifecycle()
            .getCurrentState()
            .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
          dropWebCover();
          markNativeForeground();
        }
      };

  /** 平板左栏；手机布局没有这个视图，所有使用点都按可空处理。 */
  private ShellRail shellRail;

  private final java.util.List<Fragment> gatedPages = new java.util.ArrayList<>();
  private final MainEntryGate entryGate = new MainEntryGate();

  /** 本进程身份：进程回收后重新生成，保存态里的旧值不再匹配。 */
  private static final String PROCESS_TOKEN = java.util.UUID.randomUUID().toString();

  private long entryWindow;
  private static final java.util.concurrent.Executor ENTRY_IO =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  /**
   * 通知点击带来的「进入后自动打开 Web」意图，只在一次导航创建 LaunchFragment 期间有效。
   *
   * <p>必须在 {@code setSelectedItemId} 触发监听器之前置位、在创建时立刻消费掉，
   * 否则会残留下来，让用户之后手动点启动页时被意外带进 Web。
   */
  private boolean pendingOpenWeb;

  private boolean terminalPageInFlight;
  private final androidx.activity.result.ActivityResultLauncher<Intent> terminalPage =
      registerForActivityResult(
          new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
          result -> terminalPageInFlight = false);

  private String openedRecovery = "";
  private final androidx.activity.result.ActivityResultLauncher<String> localNetworkPermission =
      registerForActivityResult(
          new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
          granted -> {
            requestingLocalNetwork = false;
            if (granted)
              com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(this);
            else
              android.widget.Toast.makeText(
                      this,
                      com.deepseekharness.app.util.UiText.text("局域网未授权，本机对话不受影响"),
                      android.widget.Toast.LENGTH_LONG)
                  .show();
          });
  private final androidx.activity.result.ActivityResultLauncher<String[]> legacyBackupPicker =
      registerForActivityResult(
          new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri != null) openBackupForRestore(uri);
          });

  public void requestLocalNetwork() {
    if (com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)) {
      com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(this);
    } else if (!requestingLocalNetwork) {
      requestingLocalNetwork = true;
      getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, MODE_PRIVATE)
          .edit()
          .putBoolean("local_network_permission_asked", true)
          .apply();
      localNetworkPermission.launch(com.deepseekharness.app.bridge.LocalNetworkAccess.PERMISSION);
    }
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    ConfigStore config = new ConfigStore(this);
    // 首次设置完成前不恢复旧终端或插件页，也不初始化 Ubuntu。
    super.onCreate(config.isWelcomed() ? savedInstanceState : null);
    // 「隐藏后台」每次启动都重放：进程重启、覆盖更新或任务被系统重建后，新任务默认可见，
    // 不能只靠设置页开关。失败只记录不崩溃（BrowserTasks 内部降级并写诊断）。
    BrowserTasks.applyRecentsPreference(this);
    normalizeScreenPowerConflict(config);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                var fragments = getSupportFragmentManager();
                // 方案 A 双栏：设置详情窗格里的下一级页面先返回到详情首页，再走主返回栈。
                Fragment current = fragments.findFragmentById(R.id.fragment_container);
                if (current instanceof SettingsFragment && ((SettingsFragment) current).popDetail())
                  return;
                if (com.deepseekharness.app.util.TaskNavigationPolicy.back(
                        fragments.getBackStackEntryCount(), fragments.isStateSaved())
                    == com.deepseekharness.app.util.TaskNavigationPolicy.Back.POP_PAGE) {
                  fragments.popBackStackImmediate();
                } else {
                  // Back from Main leaves the UI. A retained browser and the user's
                  // running environment remain in place; neither becomes another home.
                  leavingUi = true;
                  entryGate.cancel();
                  BrowserTasks.exitMain(MainActivity.this);
                }
              }
            });

    BrowserTasks.entry("CREATE", getIntent());
    if (resumeBackground(getIntent())) {
      discardedLauncherEntry = true;
      finish(); // Only this newly created native wrapper; the existing Web task is retained.
      return;
    }

    if (BrowserTasks.duplicateLauncher(this, savedInstanceState)) {
      discardedLauncherEntry = true;
      finish();
      BrowserTasks.openMain(this);
      return;
    }
    BrowserTasks.rememberMain(this);

    if (savedInstanceState != null) {
      openedRecovery = savedInstanceState.getString("opened_startup_recovery", "");
      terminalPageInFlight = savedInstanceState.getBoolean("terminal_page_in_flight", false);
    }
    // 所有入口共用本机设置门禁，恢复旧页面前先完成入口检查。
    if (!config.isWelcomed()) {
      startActivity(new Intent(this, OnboardingActivity.class));
      finish();
      return;
    }
    // 同进程重建（旋转、深浅色、语言、字体、分屏尺寸）：重建前已通过门禁，直接还原主界面，
    // 不再整屏显示开机加载页，也不因正在写的后台事务记录被赶去维护页；后台维护由横幅表达。
    if (com.deepseekharness.app.util.MainRecreatePolicy.skipEntryGate(
        savedInstanceState == null
            ? null
            : savedInstanceState.getString(
                com.deepseekharness.app.util.MainRecreatePolicy.STATE_KEY),
        PROCESS_TOKEN,
        savedInstanceState != null)) {
      android.util.Log.i("DSHA", "MAIN_ENTRY_GATE recreate=same-process");
      setContentView(R.layout.activity_main);
      showMain(savedInstanceState, config, HarnessController.get(this));
      return;
    }
    directWebArmed = armDirectWeb(savedInstanceState, config);
    showEntryCheck(savedInstanceState != null);
    if (savedInstanceState != null) {
      var hold = getSupportFragmentManager().beginTransaction();
      for (Fragment page : getSupportFragmentManager().getFragments()) {
        if (!page.isAdded()) continue;
        gatedPages.add(page);
        hold.setMaxLifecycle(page, androidx.lifecycle.Lifecycle.State.CREATED);
      }
      if (!gatedPages.isEmpty()) hold.commitNow();
    }
    pendingMainState = savedInstanceState;
    beginEntryCheck();
  }

  /**
   * 屏幕常亮与省电模式硬互斥：历史数据或旧版本可能把两者都打开。启动归一规则固定为
   * <b>保留屏幕常亮、关闭省电模式</b>并写回持久化；这是修复旧状态，不向用户提示。
   * 放在 MainActivity（不在运行时启动器契约里），避免仅 UI 改动触发运行时描述符重算。
   */
  private void normalizeScreenPowerConflict(ConfigStore config) {
    PowerModePolicy.Decision decision =
        PowerModePolicy.normalizeOnStartup(config.isKeepScreenOn(), config.isEcoMode());
    if (!decision.changed()) return;
    if (decision.keepScreenOn != config.isKeepScreenOn())
      config.setKeepScreenOn(decision.keepScreenOn);
    if (decision.ecoMode != config.isEcoMode()) {
      config.setEcoMode(decision.ecoMode);
      com.deepseekharness.app.HarnessService.refreshPowerMode(this);
    }
  }

  private void beginEntryCheck() {
    HarnessController controller = HarnessController.get(this);
    controller.restoreRunningWebSession(
        () -> {
          if (!mainReady
              && !leavingUi
              && !isFinishing()
              && !isDestroyed()
              && getLifecycle()
                  .getCurrentState()
                  .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            checkEntry(
                pendingMainState,
                new ConfigStore(this),
                controller,
                com.deepseekharness.app.BuildConfig.DEBUG
                    && getIntent().getBooleanExtra("skip_extract", false));
        });
  }

  private void showEntryCheck(boolean restored) {
    // FragmentManager可能先于后台磁盘检查恢复页面。首次就保留真实容器，
    // 后面只移除覆盖层，避免替换整个布局后恢复视图无法重新附着。
    setContentView(R.layout.activity_main);
    // 开机加载页：主界面路径用 shell 主题色；直启 Web 时衔接最近一次网页背景色。
    com.deepseekharness.app.util.BootPalette palette = bootPalette(directWebArmed);
    // 开场只在进程冷启动的首次入口播放；换主题 / 语言重建或系统回收后恢复都直接静态显示。
    boolean intro = !bootIntroPlayed && !restored;
    bootIntroPlayed = true;
    bootScreen = new BootScreen(this, palette, intro);
    bootScreen.setStatus(
        com.deepseekharness.app.util.UiText.choose("正在检查运行环境…", "Checking the runtime…"));
    bootScreen.attach();
    // 加载页可见期间，ADB 后台探活有界避让，不与入口检查和 Web 启动争环境租约（T25，实验性）。
    entryWindow = com.deepseekharness.app.DeviceBridgeService.entryCheckBegan();
  }

  private void endEntryWindow() {
    com.deepseekharness.app.DeviceBridgeService.entryCheckEnded(entryWindow);
    entryWindow = 0L;
  }

  /**
   * Main 回到前台时决定 Web 遮罩去留：正在返回原网页时保留（宽限后仍停在 Main 再收起），
   * 否则这是用户明确回到原生页面，立即收起。
   */
  private void scheduleWebCoverRelease(boolean returningToWeb) {
    recoveryHandler.removeCallbacks(releaseWebCover);
    if (webCover == null) return;
    if (returningToWeb) recoveryHandler.postDelayed(releaseWebCover, WEB_COVER_GRACE_MS);
    else dropWebCover();
  }

  private void dropWebCover() {
    recoveryHandler.removeCallbacks(releaseWebCover);
    BootScreen screen = webCover;
    webCover = null;
    if (screen != null) screen.dismiss();
  }

  private com.deepseekharness.app.util.BootPalette bootPalette(boolean towardWeb) {
    com.deepseekharness.app.util.BootPalette palette = BootScreen.shellPalette(this);
    Integer webBackground = towardWeb ? new ConfigStore(this).getEntryWebBackground() : null;
    return webBackground == null
        ? palette
        : com.deepseekharness.app.util.BootPalette.fromWeb(webBackground, palette.background);
  }

  /** 入口检查结束后淡出加载页；系统关闭动画或视图已分离时直接移除。 */
  private void dismissEntryOverlay() {
    endEntryWindow();
    BootScreen screen = bootScreen;
    bootScreen = null;
    if (screen != null) screen.dismiss();
  }

  /**
   * 偏好为 Web 且没有待主界面处理的事项时，才在门禁后直启：桌面图标或常驻通知的新建实例，
   * 以及系统回收后、用户离开前最后停在 Web 的恢复。到这里的保存态只来自跨进程恢复——
   * 同进程重建（旋转、换主题）在 onCreate 里已直接还原主界面。
   */
  private boolean armDirectWeb(Bundle saved, ConfigStore config) {
    return com.deepseekharness.app.util.EntryTarget.directWeb(
        new com.deepseekharness.app.util.EntryTarget.Launch(
            config.getEntryTarget(),
            BrowserTasks.launcher(getIntent()),
            saved != null,
            !getSharedPreferences("dsha-install-link", MODE_PRIVATE)
                .getString("pending", "")
                .isEmpty(),
            getIntent().getBooleanExtra(FirstRunWebStart.EXTRA, false),
            config.isStartupRecoveryRequested(),
            appEntry(getIntent()),
            config.isLastForegroundWeb()));
  }

  /** 常驻通知经中转页交来的应用入口：按启动目标处理，不是“回到原生启动页”。 */
  private static boolean appEntry(Intent intent) {
    return intent != null && intent.getBooleanExtra(AppEntryRouterActivity.EXTRA_APP_ENTRY, false);
  }

  /** 原生页面真正显示在前台：记下来，系统回收后恢复时回到主界面而不是 Web。 */
  private void markNativeForeground() {
    new ConfigStore(this).setLastForegroundWeb(false);
  }

  /** 放弃直启：回到主界面并显示真实状态。 */
  private void leaveDirectWeb() {
    markNativeForeground();
    directWebArmed = false;
    if (directWeb != null) directWeb.cancel();
    directWeb = null;
    if (!mainReady && directWebMainState != null)
      showMain(directWebMainState.saved, directWebMainState.config, directWebMainState.controller);
    directWebMainState = null;
  }

  private record PendingMain(Bundle saved, ConfigStore config, HarnessController controller) {}

  /**
   * 入口门禁：覆盖更新、环境未就绪、未完成的维护事务或未关闭的试运行现场，都整屏进入运行环境维护页
   * （ExtractActivity），由维护页按原有一次性规则自动开始更新或给出恢复入口。只有用户在维护页显式选择
   * “进入受限主界面”后，同一安装身份才留在受限主界面；这里不写任何就绪标记。
   *
   * <p>未确认的原实例（unresolved）只拦自动开始（维护页的 shouldAutoStart 判定），不再阻止打开维护页：
   * 覆盖安装后旧 Web 仍存活时，用户必须能看到唯一的修复入口并自己点更新。
   */
  private void checkEntry(
      Bundle saved, ConfigStore config, HarnessController controller, boolean skipExtract) {
    long generation = controller.getWebGeneration();
    Intent admittedIntent = getIntent();
    entryGate.submit(
        ENTRY_IO,
        this::runOnUiThread,
        () -> {
          boolean limited =
              admittedIntent.getBooleanExtra("limited_entry", false)
                  || config.allowsLimitedEntry(
                      com.deepseekharness.app.core.EnvironmentAccess.limitedEntryKey(controller));
          return !limited
              // 只把崩溃 / 中断残留当成维护；本进程正在写的记录不赶用户去维护页。
              && (MainEntryMaintenance.blocksEntry(controller)
                  || RuntimeMaintenanceStarter.pendingTrial(getApplicationContext())
                  || !skipExtract
                      && (!controller.isEnvironmentReady()
                          || com.deepseekharness.app.core.EnvironmentAccess
                              .requiresMaintenanceBeforeStart(controller)));
        },
        () ->
            !isFinishing()
                && !leavingUi
                && !isDestroyed()
                && admittedIntent == getIntent()
                && getLifecycle()
                    .getCurrentState()
                    .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                && !getSupportFragmentManager().isStateSaved(),
        (maintenance, failed) -> {
          if (failed)
            com.deepseekharness.app.core.DiagnosticLog.record(
                this, "MAIN_ENVIRONMENT_CHECK", "UNAVAILABLE");
          if (generation != controller.getWebGeneration()) {
            checkEntry(saved, config, controller, skipExtract);
            return;
          }
          boolean unresolved = controller.hasUnresolvedWebSession();
          boolean restoring = controller.isRestoringWebSession();
          android.util.Log.i(
              "DSHA",
              "MAIN_ENTRY_GATE maintenance="
                  + maintenance
                  + " failed="
                  + failed
                  + " unresolved="
                  + unresolved
                  + " restoring="
                  + restoring
                  + " restored="
                  + (saved != null));
          if (com.deepseekharness.app.util.RuntimeUpgradeState.openMaintenancePage(
              failed, maintenance, restoring)) {
            // 覆盖更新与中断恢复都走整屏维护页：自动开始仍只按同一候选一次的规则。
            startActivity(new Intent(this, ExtractActivity.class));
            finish();
          } else if (com.deepseekharness.app.util.EntryTarget.admitted(
              directWebArmed, failed, maintenance, unresolved, restoring)) {
            // 门禁已放行：停在开机加载页等待鉴权，就绪后直接进入 Web。
            beginDirectWeb(saved, config, controller);
          } else {
            directWebArmed = false;
            // 原实例证据未知时留在Main提供诊断，不触发解压或静默更新原环境。
            showMain(saved, config, controller);
          }
        });
  }

  /**
   * 直启 Web：加载页保持可见，鉴权就绪后打开 Web；主界面随后在 Web 之下完成初始化，
   * 因此之后点桌面图标由既有的“返回原网页”路由接管，按 Back 回到桌面。
   */
  private void beginDirectWeb(Bundle saved, ConfigStore config, HarnessController controller) {
    if (directWeb != null && directWeb.active()) return; // onResume 重复入口检查不重启等待。
    if (bootScreen == null) {
      directWebArmed = false;
      showMain(saved, config, controller);
      return;
    }
    directWebMainState = new PendingMain(saved, config, controller);
    directWeb =
        new DirectWebLaunch(
            this,
            bootScreen,
            this::leaveDirectWeb,
            () -> {
              directWebArmed = false;
              directWeb = null;
              PendingMain pending = directWebMainState;
              directWebMainState = null;
              // Web 是独立任务，切换是异步的：此刻收起加载页会在 Web 首帧前露出主界面。
              // 加载页转为遮罩，主界面在其下初始化，Main 作为原生页面回到前台时才收起。
              BootScreen screen = bootScreen;
              bootScreen = null;
              if (screen != null) {
                screen.quiet();
                webCover = screen;
              }
              if (pending != null)
                recoveryHandler.post(
                    () -> showMain(pending.saved, pending.config, pending.controller));
            });
    directWeb.begin();
  }

  private void showMain(
      Bundle savedInstanceState, ConfigStore config, HarnessController controller) {
    if (mainReady || isFinishing() || isDestroyed()) return;
    mainReady = true;
    dismissEntryOverlay();
    if (webCover == null
        && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
      markNativeForeground();
    android.view.ViewGroup appBar = findViewById(R.id.app_bar);
    // 顶栏只保留一个「+」气泡入口：下载记录、关于与启动页的检查/地址都收进气泡。
    android.view.View quickActionsAnchor = findViewById(R.id.btn_more);
    quickActionsAnchor.setVisibility(android.view.View.VISIBLE);
    quickActionsAnchor.setOnClickListener(this::toggleQuickActions);
    installDeployCapsule(appBar, appBar.indexOfChild(quickActionsAnchor));
    android.view.View recovery = findViewById(R.id.environment_recovery_banner);
    recovery.setOnClickListener(
        v -> {
          if (com.deepseekharness.app.core.BackupTask.get(this).maintenanceBusy()
              || BackgroundTasksActivity.busy(this)) BackgroundTasksActivity.open(this);
          else if (controller.config().isStartupRecoveryRequested()
              || EnvironmentUiStatus.get(this).repairs)
            startActivity(new Intent(this, StartupRecoveryActivity.class));
          else startActivity(new Intent(this, ExtractActivity.class).putExtra("review_only", true));
        });
    String pendingLink =
        getSharedPreferences("dsha-install-link", MODE_PRIVATE).getString("pending", "");
    if (!pendingLink.isEmpty()
        && controller != null
        && !controller.hasUnresolvedWebSession()
        && !com.deepseekharness.app.core.EnvironmentAccess.needsRecovery(controller)) {
      getSharedPreferences("dsha-install-link", MODE_PRIVATE).edit().remove("pending").apply();
      try {
        com.deepseekharness.app.util.PluginInstallLink.parse(pendingLink);
        startActivity(
            new Intent(this, PluginInstallActivity.class)
                .setData(android.net.Uri.parse(pendingLink)));
      } catch (IllegalArgumentException ignored) {
      }
    }

    TextView title = findViewById(R.id.app_title);
    findViewById(R.id.sub_back)
        .setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
    getSupportFragmentManager().addOnBackStackChangedListener(this::updateToolbar);
    getSupportFragmentManager()
        .registerFragmentLifecycleCallbacks(
            new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
              @Override
              public void onFragmentResumed(
                  androidx.fragment.app.FragmentManager manager, Fragment fragment) {
                updateToolbar();
              }
            },
            false);

    BottomNavigationView nav = findViewById(R.id.bottom_nav);
    shellRail = findViewById(R.id.shell_rail);
    if (shellRail != null)
      shellRail.setOnItemSelectedListener(
          id -> {
            // 左栏只转发点击，路由/门禁/终端动作仍由 bottom_nav 的唯一监听器执行。
            nav.setSelectedItemId(id);
            syncShellRail(nav.getSelectedItemId());
          });
    nav.setOnItemSelectedListener(
        item -> {
          if (item.getItemId() == R.id.nav_terminal) {
            // 终端是导航动作：不切根页，也不把左栏/语境切到终端。
            openTerminal();
            return false; // 保留来源根页/子页及未保存草稿。
          }
          // 切换前记下来源：只有「根页 → 另一根页」才用横向 tab 动效。
          int fromTab = tabOrder(nav.getSelectedItemId());
          int toTab = tabOrder(item.getItemId());
          boolean lateral =
              fromTab >= 0
                  && toTab >= 0
                  && fromTab != toTab
                  && getSupportFragmentManager().getBackStackEntryCount() == 0
                  && getSupportFragmentManager().findFragmentById(R.id.fragment_container) != null;
          // 根页真正切换后再同步：终端动作仍停在原根页。
          syncShellRail(item.getItemId());
          // 当前根页面再次点选时保留输入和滚动；从子页返回或外部打开插件仍执行导航。
          if (nav.getSelectedItemId() == item.getItemId()
              && getSupportFragmentManager().getBackStackEntryCount() == 0
              && getSupportFragmentManager().findFragmentById(R.id.fragment_container) != null
              && !gatedPages.contains(
                  getSupportFragmentManager().findFragmentById(R.id.fragment_container))
              && !(getSupportFragmentManager().findFragmentById(R.id.fragment_container)
                  instanceof EnvironmentRecoveryFragment)
              && !getIntent().getBooleanExtra("open_plugins", false)) return true;
          if (!getSupportFragmentManager().isStateSaved())
            getSupportFragmentManager()
                .popBackStackImmediate(
                    null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
          Fragment f;
          int id = item.getItemId();
          if (id == R.id.nav_launch) {
            f = new LaunchFragment();
            if (pendingOpenWeb) {
              Bundle args = new Bundle();
              args.putBoolean(LaunchFragment.ARG_OPEN_WEB, pendingOpenWeb);
              pendingOpenWeb = false;
              f.setArguments(args);
            }
            title.setText(R.string.app_name);
          } else if (id == R.id.nav_plugins) {
            f =
                com.deepseekharness.app.core.EnvironmentAccess.needsRecovery(controller)
                    ? new EnvironmentRecoveryFragment()
                    : new PluginFragment();
            if (getIntent().getBooleanExtra("open_plugins", false)) {
              Bundle args = new Bundle();
              args.putBoolean("show_installed", true);
              f.setArguments(args);
              getIntent().removeExtra("open_plugins");
            }
            title.setText(R.string.app_name);
          } else if (id == R.id.nav_settings) {
            f = new SettingsFragment();
            title.setText(R.string.app_name);
          } else {
            return false;
          }
          var transaction = getSupportFragmentManager().beginTransaction();
          (lateral
                  ? UiMotion.tab(this, transaction, toTab > fromTab)
                  : UiMotion.page(this, transaction))
              .replace(R.id.fragment_container, f)
              .commit();
          return true;
        });

    // 通知点击进入：必须在 setSelectedItemId 之前登记 —— setSelectedItemId 会【同步】
    // 触发监听器创建 LaunchFragment，那时再置标记已经晚了（参数带不进去）。
    consumeOpenWeb(getIntent());
    boolean unresolved = controller != null && controller.hasUnresolvedWebSession();
    if (savedInstanceState == null || unresolved) {
      nav.setSelectedItemId(
          !unresolved && getIntent().getBooleanExtra("open_plugins", false)
              ? R.id.nav_plugins
              : R.id.nav_launch);
    }
    // 入口检查确认旧运行实例后，才把已保存的旧终端页迁到唯一新宿主。
    Fragment restoredTerminal =
        getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    if (!unresolved
        && (restoredTerminal instanceof PtyTerminalFragment
            || restoredTerminal instanceof TerminalFragment)) {
      gatedPages.remove(restoredTerminal);
      getSupportFragmentManager().beginTransaction().remove(restoredTerminal).commitNow();
      nav.setSelectedItemId(R.id.nav_launch);
      getIntent().putExtra("open_terminal", true);
    }
    // 保存态恢复或上方重定向都可能绕过监听器，这里再对齐一次左栏与语境。
    syncShellRail(nav.getSelectedItemId());
    releaseGatedPages(!unresolved);
    consumeTaskTarget(getIntent());
    // 只订阅后台检查；提示不切换导航，也不自动打开更新页或 Web。
    startupUpdates = com.deepseekharness.app.core.UpdateEngine.get(this);
    startupUpdates.state().observe(this, state -> showStartupUpdate());
    startupUpdates.checkOnStartup(config.isCheckUpdate());
    scheduleFirstRunWeb();
    recoveryHandler.removeCallbacks(refreshRecovery);
    recoveryHandler.post(refreshRecovery);
    maybePromptExternalRestore();
  }

  // ---------------------------------------------------------------- 顶栏部署胶囊

  /** 顶栏「+」气泡：下载记录、关于，以及启动页专属的运行环境与访问地址。 */
  private void toggleQuickActions(android.view.View anchor) {
    if (quickActions != null) {
      quickActions.dismiss();
      quickActions = null;
      return;
    }
    Fragment shown = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    final LaunchFragment launch = shown instanceof LaunchFragment ? (LaunchFragment) shown : null;
    quickActions =
        QuickActionsBubble.show(
            this,
            anchor,
            launch != null,
            new QuickActionsBubble.Listener() {
              @Override
              public void onAction(com.deepseekharness.app.util.QuickActions.Action action) {
                switch (action) {
                  case DOWNLOADS:
                    FileTransfersActivity.open(MainActivity.this);
                    break;
                  case ABOUT:
                    AboutDialog.show(MainActivity.this);
                    break;
                  case ENVIRONMENT:
                    if (launch != null) launch.openEnvironment();
                    break;
                  case ADDRESS:
                    if (launch != null) launch.showAccessAddresses();
                    break;
                }
              }

              @Override
              public void onDismissed() {
                quickActions = null;
              }
            });
  }

  /** 覆盖更新 / 首次部署的进度胶囊；只显示环境维护任务，点按打开阶段与维护记录面板。 */
  private DeployMonitor deployMonitor;

  private DeployCapsule deployCapsule;

  /** 顶栏「+」气泡弹窗；显示期间保留引用，关闭后由 onDismissed 置空。 */
  private QuickActionsBubble quickActions;

  private final Runnable deployCollapseCheck =
      () -> {
        if (deployMonitor != null) renderDeployCapsule(deployMonitor.current());
      };

  private void installDeployCapsule(android.view.ViewGroup appBar, int index) {
    deployCapsule = new DeployCapsule(this);
    boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
    deployCapsule.setMaxWidthFraction(wide ? 0.46f : 0.5f);
    deployCapsule.setOnOpen(
        () -> {
          if (deployMonitor != null && !isFinishing() && !isDestroyed())
            DeployTaskSheet.show(this, deployMonitor, false, null);
        });
    var params =
        new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    int gap = Math.round(6 * getResources().getDisplayMetrics().density);
    params.leftMargin = gap;
    params.rightMargin = gap;
    appBar.addView(deployCapsule, Math.max(0, index), params);
    deployMonitor = new DeployMonitor(this, this, this::renderDeployCapsule);
    renderDeployCapsule(deployMonitor.current());
  }

  /** 成功后显示约 5 秒再收起：只收起显示，不改任务状态；同一任务收起后本进程内不再出现。 */
  private void renderDeployCapsule(com.deepseekharness.app.util.DeployStages.View view) {
    if (deployCapsule == null || view == null) return;
    recoveryHandler.removeCallbacks(deployCollapseCheck);
    if (view.phase == com.deepseekharness.app.util.DeployStages.Phase.SUCCEEDED) {
      if (DeployMonitor.collapsed(view.taskId)) {
        view = com.deepseekharness.app.util.DeployStages.idle();
      } else {
        long seen = DeployMonitor.successSeenAt(view.taskId);
        long now = android.os.SystemClock.elapsedRealtime();
        if (com.deepseekharness.app.util.DeployDisplay.collapse(view.phase, seen, now)) {
          DeployMonitor.collapse(view.taskId);
          view = com.deepseekharness.app.util.DeployStages.idle();
        } else
          recoveryHandler.postDelayed(
              deployCollapseCheck,
              Math.max(
                      0,
                      com.deepseekharness.app.util.DeployDisplay.COLLAPSE_AFTER_MILLIS
                          - (now - seen))
                  + 50);
      }
    }
    boolean wasVisible = deployCapsule.getVisibility() == android.view.View.VISIBLE;
    deployCapsule.render(view);
    if (view.visible() && !wasVisible) deployCapsule.announceForAccessibility(view.label());
  }

  private void releaseGatedPages(boolean allowed) {
    if (allowed && !gatedPages.isEmpty()) {
      var resume = getSupportFragmentManager().beginTransaction();
      for (Fragment page : gatedPages)
        if (page.isAdded())
          resume.setMaxLifecycle(page, androidx.lifecycle.Lifecycle.State.RESUMED);
      resume.commitNow();
    }
    gatedPages.clear();
  }

  private void scheduleFirstRunWeb() {
    findViewById(android.R.id.content)
        .post(
            () -> {
              if (!isFinishing() && !isDestroyed()) FirstRunWebStart.schedule(this, getIntent());
            });
  }

  private void showStartupUpdate() {
    if (startupUpdates == null
        || updateNotice != null
        || isFinishing()
        || !hasWindowFocus()
        || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        || !new ConfigStore(this).isCheckUpdate()) return;
    com.deepseekharness.app.util.UpdatePolicy.Release release = startupUpdates.startupNotice();
    if (release == null) return;
    updateNotice =
        com.google.android.material.snackbar.Snackbar.make(
                findViewById(android.R.id.content),
                com.deepseekharness.app.util.UiText.format(
                    "发现新版本 %s · %s",
                    release.version,
                    com.deepseekharness.app.core.UpdateEngine.channelName(
                        startupUpdates.channel())),
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction(
                com.deepseekharness.app.util.UiText.text("查看更新"),
                view -> startActivity(new Intent(this, UpdateActivity.class)));
    // 平板形态的底栏是隐藏的：锚到隐藏视图会把提示顶出可视区，隐藏时交给内容区默认定位。
    android.view.View navAnchor = findViewById(R.id.bottom_nav);
    if (navAnchor != null && navAnchor.getVisibility() == android.view.View.VISIBLE)
      updateNotice.setAnchorView(R.id.bottom_nav);
    updateNotice.addCallback(
        new com.google.android.material.snackbar.Snackbar.Callback() {
          @Override
          public void onShown(com.google.android.material.snackbar.Snackbar bar) {
            if (hasWindowFocus()
                && getLifecycle()
                    .getCurrentState()
                    .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
              startupUpdates.markStartupNoticeShown(release);
          }

          @Override
          public void onDismissed(com.google.android.material.snackbar.Snackbar bar, int event) {
            if (updateNotice == bar) updateNotice = null;
          }
        });
    updateNotice.show();
  }

  @Override
  protected void onPostResume() {
    super.onPostResume();
    if (mainReady && getIntent().getBooleanExtra(FirstRunWebStart.EXTRA, false))
      scheduleFirstRunWeb();
    showStartupUpdate();
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) showStartupUpdate();
  }

  @Override
  protected void onPause() {
    if (!mainReady) entryGate.cancel();
    if (quickActions != null) {
      quickActions.dismiss();
      quickActions = null;
    }
    recoveryHandler.removeCallbacks(releaseWebCover);
    recoveryHandler.removeCallbacks(refreshRecovery);
    if (updateNotice != null) {
      updateNotice.dismiss();
      updateNotice = null;
    }
    super.onPause();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    leavingUi = false;
    setIntent(intent);
    BrowserTasks.entry("NEW_INTENT", intent);
    if (resumeBackground(intent)) return;
    if (!mainReady) {
      // 加载期间收到明确的原生目的地（插件页、任务等）：放弃直启，交给主界面处理。
      // 桌面图标与常驻通知的应用入口只是“再进一次应用”，不打断正在进行的直启。
      if (!BrowserTasks.launcher(intent) && !appEntry(intent)) {
        if (directWeb != null) directWeb.cancel();
        directWeb = null;
        directWebMainState = null;
        directWebArmed = false;
      }
      if (directWeb != null && directWeb.active()) return;
      entryGate.cancel();
      beginEntryCheck();
      return;
    }
    BottomNavigationView nav = findViewById(R.id.bottom_nav);
    if (nav != null && intent.getBooleanExtra("open_plugins", false))
      nav.setSelectedItemId(R.id.nav_plugins);
    if (nav != null && intent.getBooleanExtra("open_launch", false)) {
      intent.removeExtra("open_launch");
      nav.setSelectedItemId(R.id.nav_launch);
    }
    consumeOpenWeb(intent);
    consumeTaskTarget(intent);
    if (findViewById(android.R.id.content) != null) scheduleFirstRunWeb();
  }

  private boolean resumeBackground(Intent intent) {
    if (BrowserTasks.nativeDestination(intent)) {
      intent.removeExtra(BrowserTasks.NATIVE_HOME);
      BrowserTasks.nativeNavigation();
      return false;
    }
    return BrowserTasks.resumeBackground(this, true);
  }

  /** 所有本机终端入口共用门禁；不替换Main页面，也不把导航当新建命令。 */
  public void openTerminal() {
    launchTerminal(null);
  }

  /**
   * 打开终端并先运行一条固定 guest 命令（GitHub 登录引导）。
   *
   * <p>门禁、页面切换与普通终端完全一致；固定命令只作为新 PTY 的初始 argv，不携带 token，
   * 也不改变会话关闭、维护屏障或保护票的既有路径。
   */
  public void openTerminalWithCommand(String[] guestCommand, String title) {
    if (guestCommand == null || guestCommand.length == 0) {
      openTerminal();
      return;
    }
    Intent intent = new Intent(this, TerminalActivity.class);
    intent.putExtra(TerminalActivity.EXTRA_GUEST_COMMAND, guestCommand);
    if (title != null && !title.isEmpty())
      intent.putExtra(TerminalActivity.EXTRA_GUEST_TITLE, title);
    launchTerminal(intent);
  }

  private void launchTerminal(Intent prepared) {
    if (!mainReady
        || terminalPageInFlight
        || isFinishing()
        || isDestroyed()
        || getSupportFragmentManager().isStateSaved()) return;
    HarnessController controller = HarnessController.get(this);
    if (com.deepseekharness.app.core.EnvironmentAccess.needsRecovery(controller)) {
      startActivity(new Intent(this, StartupRecoveryActivity.class));
      return;
    }
    // 打开页面、查看和切换已有会话不依赖插件列表；新建会话在页面内单独做维护/环境准入。
    terminalPageInFlight = true;
    try {
      terminalPage.launch(prepared == null ? new Intent(this, TerminalActivity.class) : prepared);
    } catch (RuntimeException error) {
      terminalPageInFlight = false;
      android.widget.Toast.makeText(
              this,
              com.deepseekharness.app.util.UiText.choose(
                  "无法打开终端页面，会话未改变", "Could not open the terminal page; sessions were not changed"),
              android.widget.Toast.LENGTH_LONG)
          .show();
    }
  }

  /** 打开设备能力授权页（虚拟识屏页的「识屏模式」入口）。 */
  public static final String EXTRA_OPEN_DEVICE_GRANTS = "open_device_grants";

  private void consumeTaskTarget(Intent intent) {
    BottomNavigationView nav = findViewById(R.id.bottom_nav);
    if (nav == null) return;
    if (intent.getBooleanExtra("open_terminal", false)) {
      intent.removeExtra("open_terminal");
      openTerminal();
    }
    // 启动恢复页「修改 Web 端口 / 重新输入 API Key」复用启动页原有的配置页入口，不另建页面。
    if (intent.getBooleanExtra("open_config", false)) {
      intent.removeExtra("open_config");
      nav.setSelectedItemId(R.id.nav_launch);
      UiMotion.page(this, getSupportFragmentManager().beginTransaction())
          .replace(R.id.fragment_container, new ConfigFragment())
          .addToBackStack(null)
          .commit();
    }
    if (intent.getBooleanExtra(EXTRA_OPEN_DEVICE_GRANTS, false)) {
      intent.removeExtra(EXTRA_OPEN_DEVICE_GRANTS);
      nav.setSelectedItemId(R.id.nav_settings);
      UiMotion.page(this, getSupportFragmentManager().beginTransaction())
          .replace(R.id.fragment_container, new DeviceGrantsFragment())
          .addToBackStack("settings")
          .commit();
    }
    if (intent.getBooleanExtra("open_install", false)) {
      intent.removeExtra("open_install");
      nav.setSelectedItemId(R.id.nav_settings);
      // 双栏设置默认就在右侧显示安装与环境，不再整页推进一份重复的安装页。
      if (SettingsPanes.twoPane(this)) return;
      UiMotion.page(this, getSupportFragmentManager().beginTransaction())
          .replace(R.id.fragment_container, new InstallFragment())
          .addToBackStack("settings")
          .commit();
    }
  }

  /**
   * 消费通知/外部的 {@code open_web} 意图：切到启动页，并把「进来后自动进 Web」的意图
   * 交给 {@link LaunchFragment}（只有它知道鉴权是否就绪）。
   *
   * <p>时序很关键：{@code setSelectedItemId} 会<b>同步</b>触发
   * {@code OnItemSelectedListener} 去创建 fragment，所以标记必须在那之前设好；
   * 否则 fragment 已经建完，参数永远带不进去（表现为"点了通知没反应"）。
   *
   * <p>Web 未就绪时（例如服务刚被系统回收）只切页不报错：停在启动页让用户看到真实状态，
   * 比弹一个必然失败的错误更合适。
   */
  private void consumeOpenWeb(Intent intent) {
    if (intent == null || !intent.getBooleanExtra("open_web", false)) return;
    intent.removeExtra("open_web");
    BottomNavigationView nav = findViewById(R.id.bottom_nav);
    if (nav == null) return;
    pendingOpenWeb = true;
    if (nav.getSelectedItemId() != R.id.nav_launch) {
      nav.setSelectedItemId(R.id.nav_launch);
      return;
    }
    // 已经停在启动页（通知点击时 App 可能就在启动页）：不会触发监听器，
    // 直接把意图交给当前这个 LaunchFragment；没有就等下次创建。
    getSupportFragmentManager().executePendingTransactions();
    Fragment shown = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    // 已恢复的启动页也消费同一次明确请求。
    if (shown instanceof LaunchFragment) {
      pendingOpenWeb = false;
      ((LaunchFragment) shown).requestAutoEnterWeb();
    }
  }

  /** 设置双栏详情窗格的返回栈变化：只刷新顶栏返回与标题，不参与路由。 */
  void onSettingsDetailChanged() {
    if (!isFinishing() && !isDestroyed()) updateToolbar();
  }

  private void updateToolbar() {
    TextView title = findViewById(R.id.app_title);
    if (title == null) return;
    Fragment shown = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    boolean nested = getSupportFragmentManager().getBackStackEntryCount() > 0;
    // 方案 A 双栏：详情窗格内进入了下一级页面时，顶栏同样显示返回与该页标题。
    if (shown instanceof SettingsFragment && ((SettingsFragment) shown).hasNestedDetail()) {
      nested = true;
      Fragment detail = ((SettingsFragment) shown).shownDetail();
      if (detail != null) shown = detail;
    }
    findViewById(R.id.sub_back)
        .setVisibility(nested ? android.view.View.VISIBLE : android.view.View.GONE);
    findViewById(R.id.app_logo)
        .setVisibility(nested ? android.view.View.GONE : android.view.View.VISIBLE);
    if (shown instanceof AboutFragment)
      title.setText(com.deepseekharness.app.util.UiText.choose("关于 DSHA", "About DSHA"));
    else if (shown instanceof OverlayFragment)
      title.setText(com.deepseekharness.app.util.UiText.choose("悬浮条", "Floating status"));
    else if (shown instanceof ConfigFragment) title.setText(R.string.ui2_runtime_config);
    else if (shown instanceof DeviceGrantsFragment)
      title.setText(com.deepseekharness.app.util.UiText.text("设备能力授权"));
    else if (shown instanceof DeviceChannelsFragment)
      title.setText(com.deepseekharness.app.util.UiText.text("设备执行通道"));
    else if (shown instanceof ScreenOperationFragment)
      title.setText(com.deepseekharness.app.util.UiText.text("屏幕操作"));
    else if (shown instanceof SafAccessFragment)
      title.setText(com.deepseekharness.app.util.UiText.choose("跨应用文件访问", "Cross-app files"));
    else if (shown instanceof SafBrowseFragment)
      title.setText(com.deepseekharness.app.util.UiText.choose("浏览文件夹", "Browse folder"));
    else if (shown instanceof SkillsFragment)
      title.setText(com.deepseekharness.app.util.UiText.choose("技能", "Skills"));
    else if (shown instanceof WorkspaceFragment)
      title.setText(com.deepseekharness.app.util.UiText.text("数据与备份"));
    else if (shown instanceof InstallFragment) title.setText(R.string.ui2_environment_page);
    else if (shown instanceof IslandFragment)
      title.setText(com.deepseekharness.app.util.UiText.text("任务状态上岛"));
    else if (shown instanceof SettingsFragment) title.setText(R.string.app_name);
    else if (shown instanceof PluginFragment) title.setText(R.string.app_name);
    else if (shown instanceof TerminalFragment || shown instanceof PtyTerminalFragment)
      title.setText(R.string.app_name);
    else title.setText(R.string.app_name);
    renderPageContext(shown);
  }

  /**
   * 顶栏页面语境与平板左栏选中态：由 BottomNavigationView 的唯一选中事件统一驱动。
   *
   * <p>没有左栏（手机）时只更新语境；{@code app_context} 缺失时静默跳过。
   */
  private void syncShellRail(int selectedId) {
    if (shellRail != null) shellRail.setSelectedItemId(selectedId);
    TextView context = findViewById(R.id.app_context);
    if (context == null) return;
    if (selectedId == R.id.nav_plugins)
      context.setText(
          com.deepseekharness.app.util.UiText.choose("扩展你的工作方式", "Extend how you work"));
    else if (selectedId == R.id.nav_settings)
      context.setText(
          com.deepseekharness.app.util.UiText.choose("偏好与管理", "Preferences & management"));
    else if (selectedId == R.id.nav_terminal)
      context.setText(com.deepseekharness.app.util.UiText.choose("本机终端", "Local terminal"));
    else context.setText(com.deepseekharness.app.util.UiText.choose("本机环境", "Local environment"));
  }

  /** 根页在底栏中的左右顺序；终端等非根页返回 -1，不参与横向动效。 */
  private static int tabOrder(int itemId) {
    if (itemId == R.id.nav_launch) return 0;
    if (itemId == R.id.nav_plugins) return 1;
    if (itemId == R.id.nav_settings) return 2;
    return -1;
  }

  /**
   * 语境跟随实际页面：保存态恢复或外部入口可能让底栏选中项与当前根页短暂不一致，
   * 这里以当前根页为准，避免顶栏语境残留上一个页面。
   */
  private void renderPageContext(@Nullable Fragment shown) {
    TextView context = findViewById(R.id.app_context);
    if (context == null) return;
    if (shown instanceof PluginFragment || shown instanceof EnvironmentRecoveryFragment)
      context.setText(
          com.deepseekharness.app.util.UiText.choose("扩展你的工作方式", "Extend how you work"));
    else if (shown instanceof PtyTerminalFragment || shown instanceof TerminalFragment)
      context.setText(com.deepseekharness.app.util.UiText.choose("本机终端", "Local terminal"));
    else if (shown instanceof LaunchFragment || shown == null)
      context.setText(com.deepseekharness.app.util.UiText.choose("本机环境", "Local environment"));
    else
      context.setText(
          com.deepseekharness.app.util.UiText.choose("偏好与管理", "Preferences & management"));
  }

  /**
   * 偏好为 Web 时，从主界面打开整屏 Web 前先盖上与直启相同的遮罩。系统为 Main 留下的最后一帧
   * （最近任务缩略图、之后点桌面图标经 Main 返回网页时的过渡帧）因此是加载页，而不是主界面。
   * Main 作为原生页面回到前台时照常收起。
   */
  @Override
  @SuppressWarnings("deprecation") // 覆盖所有 startActivity / Fragment 启动的最终入口。
  public void startActivityForResult(Intent intent, int requestCode, @Nullable Bundle options) {
    boolean covered = coverBeforeWeb(intent);
    try {
      super.startActivityForResult(intent, requestCode, options);
    } catch (RuntimeException error) {
      if (covered) dropWebCover();
      throw error;
    }
  }

  private boolean coverBeforeWeb(Intent intent) {
    if (!mainReady
        || webCover != null
        || bootScreen != null
        || isFinishing()
        || isDestroyed()
        || intent == null
        || intent.getComponent() == null
        || !getPackageName().equals(intent.getComponent().getPackageName())
        || !"FULL".equals(intent.getStringExtra(BrowserConversationRoute.MODE))
        || !browserHost(intent.getComponent().getClassName())
        || !com.deepseekharness.app.util.EntryTarget.WEB.equals(
            new ConfigStore(this).getEntryTarget())) return false;
    BootScreen cover = new BootScreen(this, bootPalette(true), false);
    cover.attach();
    cover.quiet();
    webCover = cover;
    return true;
  }

  private static boolean browserHost(String className) {
    if (WebPreviewActivity.class.getName().equals(className)) return true;
    PictureInPictureActivity owner = BrowserConversationRoute.owner();
    return owner != null && owner.getClass().getName().equals(className);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (discardedLauncherEntry) return;
    leavingUi = false;
    BrowserTasks.entry("RESUME", getIntent());
    // 已核验的同代网页先恢复，避免入口检查完成后遗漏唯一一次 onResume。
    boolean returning = BrowserTasks.resumeBackground(this);
    scheduleWebCoverRelease(returning || BrowserTasks.returnPending());
    if (returning) return;
    if (!mainReady && !isFinishing()) {
      beginEntryCheck();
      return;
    }
    BrowserTasks.rememberMain(this);
    if (webCover == null) markNativeForeground();
    if (!new ConfigStore(this)
        .getUiLanguage()
        .equals(com.deepseekharness.app.util.UiText.language())) LanguageController.apply(this);
    recoveryHandler.removeCallbacks(refreshRecovery);
    recoveryHandler.post(refreshRecovery);
    updateToolbar();
    if (!isFinishing()
        && findViewById(R.id.bottom_nav) != null
        && (new ConfigStore(this).isLanMode()
            || com.deepseekharness.app.DeviceBridgeService.isAdbEnabled(this))
        && !com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)
        && !getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, MODE_PRIVATE)
            .getBoolean("local_network_permission_asked", false)) requestLocalNetwork();
    // Android 12+ 可能拒绝后台唤起前台服务，回到可见界面后补一次恢复。
    if (!isFinishing()
        && findViewById(R.id.bottom_nav) != null
        && com.deepseekharness.app.DeviceBridgeService.isAdbEnabled(this)
        && !com.deepseekharness.app.DeviceBridgeService.isRunning()) {
      com.deepseekharness.app.DeviceBridgeService.apply(this);
    }
    maybePromptExternalRestore();
  }

  /**
   * 卸载重装后的空环境仍给旧 Download/DSHA 备份一个明确入口。
   * 扫描本身在后台执行；无法枚举旧 MediaStore 归属时也不把「0 个」
   * 当成「没有备份」，而是提供 SAF 手动选择（issue #22）。
   */
  private void maybePromptExternalRestore() {
    if (restoreProbeStarted || isFinishing() || isDestroyed()) return;
    HarnessController controller = HarnessController.get(this);
    restoreProbeStarted = true;
    new Thread(
            () -> {
              if (!controller.isEnvironmentReady()
                  || com.deepseekharness.app.core.BackupTask.get(this).busy()
                  || com.deepseekharness.app.backup.ExternalBackupScanner.hasUserData(
                      controller.proot().getRootfsDir())) return;
              com.deepseekharness.app.backup.ExternalBackupScanner.Scan scan =
                  com.deepseekharness.app.backup.ExternalBackupScanner.scan(this);
              boolean invisible =
                  scan.total() == 0
                      && !com.deepseekharness.app.backup.ExternalBackupScanner.canSeeAllFiles();
              if (scan.total() == 0 && !invisible) return;
              String declineKey =
                  scan.best != null
                      ? scan.best.name
                      : scan.total() == 0 ? "__invisible__" : "__unreadable__" + scan.unreadable;
              android.content.SharedPreferences prefs =
                  getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, MODE_PRIVATE);
              if (declineKey.equals(prefs.getString("restore_prompt_declined", ""))) return;
              runOnUiThread(
                  () -> {
                    if (isFinishing() || isDestroyed()) return;
                    try {
                      com.deepseekharness.app.ui.DshaDialogBuilder dialog =
                          new com.deepseekharness.app.ui.DshaDialogBuilder(this);
                      if (scan.best != null) {
                        String message =
                            com.deepseekharness.app.util.UiText.format(
                                "发现已有备份：\n%s\n\n先核验内容和实际范围，确认后才会恢复。", scan.best.describe());
                        if (scan.unreadable > 0)
                          message +=
                              com.deepseekharness.app.util.UiText.format(
                                  "\n另有 %s 个备份无法自动读取，可手动选择。", scan.unreadable);
                        dialog
                            .setTitle(
                                com.deepseekharness.app.util.UiText.choose(
                                    "检测到已有备份", "Backup found"))
                            .setMessage(message)
                            .setPositiveButton(
                                com.deepseekharness.app.util.UiText.choose(
                                    "选择恢复", "Inspect and restore"),
                                (d, w) -> openBackupForRestore(scan.best))
                            .setNegativeButton(
                                com.deepseekharness.app.util.UiText.choose("忽略", "Ignore"),
                                (d, w) ->
                                    prefs
                                        .edit()
                                        .putString("restore_prompt_declined", declineKey)
                                        .apply());
                        if (scan.unreadable > 0)
                          dialog.setNeutralButton(
                              com.deepseekharness.app.util.UiText.choose("手动选择", "Choose manually"),
                              (d, w) -> pickBackupForRestore());
                      } else if (invisible) {
                        dialog
                            .setTitle(
                                com.deepseekharness.app.util.UiText.choose(
                                    "是否需要恢复以前的备份？", "Restore an older backup?"))
                            .setMessage(
                                com.deepseekharness.app.util.UiText.choose(
                                    "当前环境没有用户数据。系统暂时无法枚举卸载前的 Download/DSHA 文件；可用文件选择器直接选择备份，不需要开启所有文件访问。",
                                    "This environment has no user data. Android cannot enumerate backups from the previous installation, but the file picker can open one without all-files access."))
                            .setPositiveButton(
                                com.deepseekharness.app.util.UiText.choose("手动选择", "Choose backup"),
                                (d, w) -> pickBackupForRestore())
                            .setNegativeButton(
                                com.deepseekharness.app.util.UiText.choose("不用了", "Not now"),
                                (d, w) ->
                                    prefs
                                        .edit()
                                        .putString("restore_prompt_declined", declineKey)
                                        .apply());
                      } else {
                        dialog
                            .setTitle(
                                com.deepseekharness.app.util.UiText.choose(
                                    "备份无法自动读取", "Backup cannot be read automatically"))
                            .setMessage(
                                com.deepseekharness.app.util.UiText.choose(
                                    "请用文件选择器指定备份包。",
                                    "Choose the backup package with the file picker."))
                            .setPositiveButton(
                                com.deepseekharness.app.util.UiText.choose("手动选择", "Choose backup"),
                                (d, w) -> pickBackupForRestore())
                            .setNegativeButton(
                                com.deepseekharness.app.util.UiText.choose("忽略", "Ignore"),
                                (d, w) ->
                                    prefs
                                        .edit()
                                        .putString("restore_prompt_declined", declineKey)
                                        .apply());
                      }
                      dialog.show();
                    } catch (Throwable ignored) {
                    }
                  });
            },
            "dsha-restore-probe")
        .start();
  }

  public void pickBackupForRestore() {
    RecentsPrivacyGuard.launch(
        legacyBackupPicker,
        new String[] {
          com.deepseekharness.app.util.BackupFileNames.MIME_TYPE,
          "application/octet-stream",
          "application/gzip",
          "application/x-gzip",
          "*/*"
        });
  }

  private void openBackupForRestore(
      com.deepseekharness.app.backup.ExternalBackupScanner.Candidate candidate) {
    if (candidate == null) return;
    android.net.Uri uri = candidate.uri;
    if (uri == null && candidate.file != null) {
      try {
        uri =
            androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".updates", candidate.file);
      } catch (IllegalArgumentException ignored) {
      }
    }
    if (uri != null) openBackupForRestore(uri);
    else pickBackupForRestore();
  }

  private void openBackupForRestore(android.net.Uri uri) {
    startActivity(
        new Intent(this, NativeDataActivity.class)
            .putExtra("restore_uri", uri.toString())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
  }

  private final android.os.Handler recoveryHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());

  /** Native plugin/settings work stays in front of a persistent startup failure. */
  static boolean startupRecoveryDestination(int selectedItem, Fragment page) {
    return selectedItem == R.id.nav_launch && (page == null || page instanceof LaunchFragment);
  }

  /** 环境提示横幅文案：标题说清是什么状态，第二行说清影响与点按后去哪里。 */
  private static void renderRecoveryBanner(
      EnvironmentNoticeBanner banner,
      com.deepseekharness.app.util.RecoveryBannerState.Kind kind,
      String taskKind) {
    String title, detail;
    switch (kind) {
      case BUSY -> {
        String rendered =
            taskKind == null || taskKind.isEmpty()
                ? ""
                : com.deepseekharness.app.util.StartupText.render(taskKind);
        title =
            rendered.isEmpty()
                ? com.deepseekharness.app.util.UiText.choose(
                    "环境维护进行中", "Environment maintenance in progress")
                : rendered;
        detail =
            com.deepseekharness.app.util.UiText.choose(
                "相关操作完成前暂缓 · 点按查看进度", "Related actions wait until it finishes · Tap for progress");
      }
      case AWAITING_CONFIRMATION -> {
        title =
            com.deepseekharness.app.util.UiText.choose(
                "恢复备份等待确认", "Backup restore awaiting confirmation");
        detail =
            com.deepseekharness.app.util.UiText.choose(
                "预检已完成，确认后才会替换数据 · 点按查看",
                "Pre-check finished; data changes only after you confirm · Tap to review");
      }
      case LIMITED -> {
        title =
            com.deepseekharness.app.util.UiText.choose(
                "受限模式：环境需要恢复", "Limited mode: the environment needs recovery");
        detail =
            com.deepseekharness.app.util.UiText.choose(
                "终端、Web 与插件暂不可用 · 点按处理",
                "Terminals, Web and plugins are unavailable · Tap to resolve");
      }
      case STARTUP -> {
        title =
            com.deepseekharness.app.util.UiText.choose(
                "上次启动未完成", "The last startup did not complete");
        detail =
            com.deepseekharness.app.util.UiText.choose(
                "可查看日志与恢复选项 · 点按打开", "Logs and recovery options are available · Tap to open");
      }
      default -> {
        title = "";
        detail = "";
      }
    }
    banner.show(kind, title, detail);
  }

  private final Runnable refreshRecovery =
      new Runnable() {
        @Override
        public void run() {
          EnvironmentNoticeBanner banner = findViewById(R.id.environment_recovery_banner);
          if (banner == null || isFinishing()) return;
          HarnessController controller = HarnessController.get(MainActivity.this);
          boolean startupRecovery =
              controller.config().isStartupRecoveryRequested()
                  || EnvironmentUiStatus.get(MainActivity.this).repairs;
          boolean limited = EnvironmentUiStatus.get(MainActivity.this).recovery;
          boolean busy =
              com.deepseekharness.app.core.BackupTask.get(MainActivity.this).maintenanceBusy();
          // 顶栏胶囊正在显示环境维护（部署 / 更新 / 失败 / 未通过）时由胶囊取代横幅；
          // 备份、恢复备份、启动恢复等其它情况仍用原横幅。
          boolean capsuleShown =
              deployCapsule != null && deployCapsule.getVisibility() == android.view.View.VISIBLE;
          com.deepseekharness.app.util.BackupTaskState.Snapshot task =
              com.deepseekharness.app.core.BackupTask.get(MainActivity.this).snapshot();
          var kind =
              com.deepseekharness.app.util.RecoveryBannerState.decide(
                  capsuleShown,
                  busy,
                  busy
                      && task.busy()
                      && task.status == com.deepseekharness.app.util.BackupTaskState.Status.PREVIEW,
                  limited,
                  startupRecovery);
          renderRecoveryBanner(banner, kind, busy && task.busy() ? task.kind : "");
          BottomNavigationView nav = findViewById(R.id.bottom_nav);
          String key =
              controller.startupDiagnostics().recordId()
                  + ":"
                  + controller.config().getWebFailureReason()
                  + ":"
                  + EnvironmentUiStatus.get(MainActivity.this).repairs;
          if (startupRecovery
              && !limited
              && !busy
              && !com.deepseekharness.app.core.MaintenanceCoordinator.isEnvironmentTaskBusy()
              && !controller.isStarting()
              && !controller.isStopping()
              && startupRecoveryDestination(
                  nav == null ? R.id.nav_launch : nav.getSelectedItemId(),
                  getSupportFragmentManager().findFragmentById(R.id.fragment_container))
              && !key.equals(openedRecovery)
              && getLifecycle()
                  .getCurrentState()
                  .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            openedRecovery = key;
            startActivity(new Intent(MainActivity.this, StartupRecoveryActivity.class));
          }
          recoveryHandler.postDelayed(this, 1000);
        }
      };

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putString("opened_startup_recovery", openedRecovery);
    state.putBoolean("terminal_page_in_flight", terminalPageInFlight);
    // 只有已放行的主界面才登记本进程令牌；加载中旋转仍按原流程重新检查。
    if (mainReady)
      state.putString(com.deepseekharness.app.util.MainRecreatePolicy.STATE_KEY, PROCESS_TOKEN);
    super.onSaveInstanceState(state);
  }

  @Override
  protected void onDestroy() {
    entryGate.cancel();
    endEntryWindow();
    // 只停止加载页的等待；已提交的 Web 启动由运行时继续负责。
    if (directWeb != null) directWeb.cancel();
    // Main销毁只解绑页面。终端由进程级owner和独立FGS持有，
    // 只能通过明确会话结束/环境维护核验退出，不能把Activity销毁当停止请求。
    super.onDestroy();
  }

  public static void start(Context ctx) {
    BrowserTasks.openMain(ctx);
  }

  /** Stable notification/native-home entry; keeps the existing browser and native pages. */
  public static Intent homeIntent(Context ctx) {
    return BrowserTasks.homeIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  }

  /** A specific native destination supplies its own open_* extra after this shared route. */
  public static Intent navigationIntent(Context ctx) {
    return BrowserTasks.navigationIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  }
}
