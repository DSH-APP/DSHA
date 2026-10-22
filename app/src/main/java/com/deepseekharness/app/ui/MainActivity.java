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
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 主界面外壳：启动门禁 + 底部导航（启动 / 插件 / 设置 / 终端）+ 顶栏标题 + 关于入口。
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
  private boolean mainReady;
  private Bundle pendingMainState;
  private android.view.View entryOverlay;
  private final java.util.List<Fragment> gatedPages = new java.util.ArrayList<>();
  private final MainEntryGate entryGate = new MainEntryGate();
  private static final java.util.concurrent.Executor ENTRY_IO =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  /**
   * 通知点击带来的「进入后自动打开 Web」意图，只在一次导航创建 LaunchFragment 期间有效。
   *
   * <p>必须在 {@code setSelectedItemId} 触发监听器之前置位、在创建时立刻消费掉，
   * 否则会残留下来，让用户之后手动点启动页时被意外带进 Web。
   */
  private boolean pendingOpenWeb;

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
                      com.deepseekharness.app.util.UiText.text(
                          "未允许局域网访问；本机对话仍可使用，LAN / 无线 ADB 需在系统权限设置中开启"),
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
    super.onCreate(savedInstanceState);

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

    if (savedInstanceState != null)
      openedRecovery = savedInstanceState.getString("opened_startup_recovery", "");
    ConfigStore config = new ConfigStore(this);

    // 启动门禁：未欢迎 → Welcome；环境未解压 → Extract
    if (!config.isWelcomed()) {
      startActivity(new Intent(this, WelcomeActivity.class));
      finish();
      return;
    }
    showEntryCheck();
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

  private void beginEntryCheck() {
    HarnessController controller = HarnessController.get(this);
    controller.restoreRunningWebSession(
        () -> {
          if (!mainReady
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

  private void showEntryCheck() {
    // FragmentManager可能先于后台磁盘检查恢复页面。首次就保留真实容器，
    // 后面只移除覆盖层，避免替换整个布局后恢复视图无法重新附着。
    setContentView(R.layout.activity_main);
    var layout = new android.widget.LinearLayout(this);
    layout.setOrientation(android.widget.LinearLayout.VERTICAL);
    layout.setGravity(android.view.Gravity.CENTER);
    layout.setBackgroundColor(getColor(R.color.surface));
    layout.setClickable(true);
    layout.setFocusable(true);
    TextView status = new TextView(this);
    status.setText(
        com.deepseekharness.app.util.UiText.choose("正在检查运行环境…", "Checking the runtime…"));
    layout.addView(status);
    layout.addView(new android.widget.ProgressBar(this));
    android.widget.Button diagnostics = new android.widget.Button(this);
    diagnostics.setText(com.deepseekharness.app.util.UiText.choose("查看诊断", "View diagnostics"));
    diagnostics.setOnClickListener(v -> startActivity(DiagnosticActivity.downloadLogs(this)));
    layout.addView(diagnostics);
    entryOverlay = layout;
    addContentView(layout, new android.view.ViewGroup.LayoutParams(-1, -1));
  }

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
                  || config.allowsLimitedEntry(controller.proot().environmentIdentity());
          return !limited
              && (com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller)
                  || !skipExtract
                      && (!controller.isEnvironmentReady()
                          || com.deepseekharness.app.core.EnvironmentAccess
                              .requiresMaintenanceBeforeStart(controller)));
        },
        () ->
            !isFinishing()
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
          if (!failed
              && maintenance
              && !controller.hasUnresolvedWebSession()
              && !controller.isRestoringWebSession()) {
            startActivity(new Intent(this, ExtractActivity.class));
            finish();
          } else {
            // 原实例证据未知时留在Main提供诊断，不触发解压或静默更新原环境。
            showMain(saved, config, controller);
          }
        });
  }

  private void showMain(
      Bundle savedInstanceState, ConfigStore config, HarnessController controller) {
    if (mainReady || isFinishing() || isDestroyed()) return;
    mainReady = true;
    if (entryOverlay != null && entryOverlay.getParent() instanceof android.view.ViewGroup parent)
      parent.removeView(entryOverlay);
    entryOverlay = null;
    findViewById(R.id.btn_tasks)
        .setContentDescription(
            com.deepseekharness.app.util.UiText.choose("后台任务", "Background tasks"));
    findViewById(R.id.btn_tasks).setOnClickListener(v -> BackgroundTasksActivity.open(this));
    TextView recovery = findViewById(R.id.environment_recovery_banner);
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
    renderThemeButton();
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
    android.view.ViewGroup quickEntries = findViewById(R.id.btn_about);
    quickEntries.setVisibility(android.view.View.VISIBLE);
    quickEntries.setContentDescription(com.deepseekharness.app.util.UiText.text("关于 DSHA"));
    ((android.widget.ImageView) quickEntries.getChildAt(0)).setImageResource(R.drawable.ic_about);
    quickEntries.setOnClickListener(v -> AboutDialog.show(this));

    BottomNavigationView nav = findViewById(R.id.bottom_nav);
    nav.setOnItemSelectedListener(
        item -> {
          // 当前根页面再次点选时保留输入和滚动；从子页返回或外部打开插件仍执行导航。
          if (nav.getSelectedItemId() == item.getItemId()
              && getSupportFragmentManager().getBackStackEntryCount() == 0
              && getSupportFragmentManager().findFragmentById(R.id.fragment_container) != null
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
              pendingOpenWeb = false;
              Bundle args = new Bundle();
              args.putBoolean(LaunchFragment.ARG_OPEN_WEB, true);
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
            // 终端：默认挂真 PTY 页（vim/htop/tmux 能跑），可在 PTY 页切回简易版
            f =
                com.deepseekharness.app.core.EnvironmentAccess.needsRecovery(controller)
                    ? new EnvironmentRecoveryFragment()
                    : PtyTerminalFragment.preferred(this)
                        ? new PtyTerminalFragment()
                        : new TerminalFragment();
            title.setText(R.string.app_name);
          }
          UiMotion.page(this, getSupportFragmentManager().beginTransaction())
              .replace(R.id.fragment_container, f)
              .commit();
          return true;
        });

    // 通知点击进入：必须在 setSelectedItemId 之前登记 —— setSelectedItemId 会【同步】
    // 触发监听器创建 LaunchFragment，那时再置标记已经晚了（参数带不进去）。
    consumeOpenWeb(getIntent());
    if (savedInstanceState == null || controller.hasUnresolvedWebSession()) {
      nav.setSelectedItemId(
          !controller.hasUnresolvedWebSession()
                  && getIntent().getBooleanExtra("open_plugins", false)
              ? R.id.nav_plugins
              : R.id.nav_launch);
    }
    releaseGatedPages(!controller.hasUnresolvedWebSession());
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
            .setAnchorView(R.id.bottom_nav)
            .setAction(
                com.deepseekharness.app.util.UiText.text("查看更新"),
                view -> startActivity(new Intent(this, UpdateActivity.class)));
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
    setIntent(intent);
    BrowserTasks.entry("NEW_INTENT", intent);
    if (resumeBackground(intent)) return;
    if (!mainReady) {
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
      BrowserTasks.nativeNavigation();
      return false;
    }
    return BrowserTasks.resumeBackground(this, true);
  }

  private void renderThemeButton() {
    TextView theme = findViewById(R.id.btn_theme);
    String preference = ThemeController.preference(this);
    boolean system = com.deepseekharness.app.util.UiThemePreference.SYSTEM.equals(preference);
    boolean light = com.deepseekharness.app.util.UiThemePreference.LIGHT.equals(preference);
    theme.setText("");
    theme.setCompoundDrawablesWithIntrinsicBounds(
        system ? R.drawable.ic_ui2_auto : light ? R.drawable.ic_ui2_sun : R.drawable.ic_ui2_moon,
        0,
        0,
        0);
    theme.setContentDescription(
        com.deepseekharness.app.util.UiText.text(
            system
                ? "当前跟随系统，点击切换白天；长按跟随系统"
                : light ? "当前白天模式，点击切换黑夜；长按跟随系统" : "当前黑夜模式，点击切换跟随系统；长按跟随系统"));
    theme.setOnClickListener(
        v -> {
          ThemeController.cycle(this);
          renderThemeButton();
          showThemePreference();
        });
    theme.setOnLongClickListener(
        v -> {
          ThemeController.followSystem(this);
          renderThemeButton();
          showThemePreference();
          return true;
        });
  }

  private void showThemePreference() {
    String preference = ThemeController.preference(this);
    String message =
        com.deepseekharness.app.util.UiThemePreference.SYSTEM.equals(preference)
            ? "已跟随系统主题"
            : com.deepseekharness.app.util.UiThemePreference.LIGHT.equals(preference)
                ? "已切换白天模式"
                : "已切换黑夜模式";
    android.widget.Toast.makeText(
            this,
            com.deepseekharness.app.util.UiText.text(message),
            android.widget.Toast.LENGTH_SHORT)
        .show();
  }

  private void consumeTaskTarget(Intent intent) {
    BottomNavigationView nav = findViewById(R.id.bottom_nav);
    if (nav == null) return;
    if (intent.getBooleanExtra("open_terminal", false)) {
      intent.removeExtra("open_terminal");
      nav.setSelectedItemId(R.id.nav_terminal);
    }
    if (intent.getBooleanExtra("open_install", false)) {
      intent.removeExtra("open_install");
      nav.setSelectedItemId(R.id.nav_settings);
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
    if (shown instanceof LaunchFragment) {
      pendingOpenWeb = false;
      ((LaunchFragment) shown).requestAutoEnterWeb();
    }
  }

  private void updateToolbar() {
    TextView title = findViewById(R.id.app_title);
    if (title == null) return;
    Fragment shown = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    boolean nested = getSupportFragmentManager().getBackStackEntryCount() > 0;
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
    else if (shown instanceof WorkspaceFragment)
      title.setText(com.deepseekharness.app.util.UiText.text("数据与备份"));
    else if (shown instanceof InstallFragment) title.setText(R.string.ui2_environment_page);
    else if (shown instanceof SettingsFragment) title.setText(R.string.app_name);
    else if (shown instanceof PluginFragment) title.setText(R.string.app_name);
    else if (shown instanceof TerminalFragment || shown instanceof PtyTerminalFragment)
      title.setText(R.string.app_name);
    else title.setText(R.string.app_name);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (discardedLauncherEntry) return;
    BrowserTasks.entry("RESUME", getIntent());
    // 已核验的同代网页先恢复，避免入口检查完成后遗漏唯一一次 onResume。
    if (BrowserTasks.resumeBackground(this)) return;
    if (!mainReady && !isFinishing()) {
      beginEntryCheck();
      return;
    }
    BrowserTasks.rememberMain(this);
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
    legacyBackupPicker.launch(
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

  private final Runnable refreshRecovery =
      new Runnable() {
        @Override
        public void run() {
          TextView banner = findViewById(R.id.environment_recovery_banner);
          if (banner == null || isFinishing()) return;
          HarnessController controller = HarnessController.get(MainActivity.this);
          boolean startupRecovery =
              controller.config().isStartupRecoveryRequested()
                  || EnvironmentUiStatus.get(MainActivity.this).repairs;
          boolean limited = EnvironmentUiStatus.get(MainActivity.this).recovery;
          boolean busy =
              com.deepseekharness.app.core.BackupTask.get(MainActivity.this).maintenanceBusy();
          banner.setVisibility(
              limited || busy || startupRecovery
                  ? android.view.View.VISIBLE
                  : android.view.View.GONE);
          banner.setText(
              busy
                  ? com.deepseekharness.app.util.UiText.text("环境维护进行中 · 点击查看进度")
                  : limited
                      ? com.deepseekharness.app.util.UiText.text("受限模式：环境需要恢复 · 点击处理")
                      : com.deepseekharness.app.util.UiText.choose(
                          "启动未完成 · 点击查看恢复选项", "Startup did not complete · View recovery options"));
          com.deepseekharness.app.util.BackupTaskState.Snapshot task =
              com.deepseekharness.app.core.BackupTask.get(MainActivity.this).snapshot();
          if (busy && task.busy())
            banner.setText(
                com.deepseekharness.app.util.UiText.format(
                    "%s · 点击查看进度",
                    (task.status == com.deepseekharness.app.util.BackupTaskState.Status.PREVIEW
                        ? com.deepseekharness.app.util.UiText.choose(
                            "等待确认恢复备份", "Backup restore awaiting confirmation")
                        : com.deepseekharness.app.util.StartupText.render(task.kind))));
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
    super.onSaveInstanceState(state);
  }

  @Override
  protected void onDestroy() {
    entryGate.cancel();
    // 主题切换/旋转只重建界面，不能把正在运行的终端一起关闭。
    if (!discardedLauncherEntry && !isChangingConfigurations()) {
      try {
        PtyTerminalFragment.shutdown();
      } catch (Throwable ignored) {
      }
      try {
        TerminalFragment.shutdownShell();
      } catch (Throwable ignored) {
      }
    }
    super.onDestroy();
  }

  public static void start(Context ctx) {
    BrowserTasks.openMain(ctx);
  }
}
