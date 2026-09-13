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

    public static volatile MainActivity current;
    private com.deepseekharness.app.core.UpdateEngine startupUpdates;
    private com.google.android.material.snackbar.Snackbar updateNotice;
    private boolean requestingLocalNetwork;
    /**
     * 通知点击带来的「进入后自动打开 Web」意图，只在一次导航创建 LaunchFragment 期间有效。
     *
     * <p>必须在 {@code setSelectedItemId} 触发监听器之前置位、在创建时立刻消费掉，
     * 否则会残留下来，让用户之后手动点启动页时被意外带进 Web。
     */
    private boolean pendingOpenWeb;
    private String openedRecovery="";
    private final androidx.activity.result.ActivityResultLauncher<String> localNetworkPermission =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    granted -> {
                        requestingLocalNetwork = false;
                        if (granted) com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(this);
                        else android.widget.Toast.makeText(this,
                                com.deepseekharness.app.util.UiText.text("未允许局域网访问；本机对话仍可使用，LAN / 无线 ADB 需在系统权限设置中开启"),
                                android.widget.Toast.LENGTH_LONG).show();
                    });

    public void requestLocalNetwork() {
        if (com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)) {
            com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(this);
        } else if (!requestingLocalNetwork) {
            requestingLocalNetwork = true;
            getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, MODE_PRIVATE)
                    .edit().putBoolean("local_network_permission_asked", true).apply();
            localNetworkPermission.launch(com.deepseekharness.app.bridge.LocalNetworkAccess.PERMISSION);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if(savedInstanceState!=null)openedRecovery=savedInstanceState.getString("opened_startup_recovery","");
        ConfigStore config = new ConfigStore(this);
        HarnessController controller = HarnessController.get(this);
        boolean skipExtract = com.deepseekharness.app.BuildConfig.DEBUG && getIntent().getBooleanExtra("skip_extract", false);

        // 启动门禁：未欢迎 → Welcome；环境未解压 → Extract
        if (!config.isWelcomed()) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }
        boolean limitedAllowed = getIntent().getBooleanExtra("limited_entry", false)
                || config.allowsLimitedEntry(controller.proot().environmentIdentity());
        if (!limitedAllowed && (com.deepseekharness.app.BackupManager.hasPendingMaintenance(controller)
                || !skipExtract && !controller.isEnvironmentReady())) {
            startActivity(new Intent(this, ExtractActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        TextView recovery = findViewById(R.id.environment_recovery_banner);
        recovery.setOnClickListener(v -> startActivity(new Intent(this, ExtractActivity.class).putExtra("review_only", true)));
        String pendingLink = getSharedPreferences("dsha-install-link", MODE_PRIVATE).getString("pending", "");
        if (!pendingLink.isEmpty() && !com.deepseekharness.app.core.EnvironmentAccess.needsRecovery(controller)) {
            getSharedPreferences("dsha-install-link", MODE_PRIVATE).edit().remove("pending").apply();
            try {
                com.deepseekharness.app.util.PluginInstallLink.parse(pendingLink);
                startActivity(new Intent(this, PluginInstallActivity.class).setData(android.net.Uri.parse(pendingLink)));
            } catch (IllegalArgumentException ignored) { }
        }

        TextView title = findViewById(R.id.app_title);
        TextView theme = findViewById(R.id.btn_theme);
        boolean dark = ThemeController.isDark(this);
        theme.setText(dark ? com.deepseekharness.app.util.UiText.text("黑夜") : com.deepseekharness.app.util.UiText.text("白天"));
        theme.setContentDescription(dark ? com.deepseekharness.app.util.UiText.text("当前黑夜模式，点击切换白天") : com.deepseekharness.app.util.UiText.text("当前白天模式，点击切换黑夜"));
        theme.setOnClickListener(v -> ThemeController.toggle(this));
        findViewById(R.id.sub_back).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateToolbar);
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            @Override public void onFragmentResumed(androidx.fragment.app.FragmentManager manager, Fragment fragment) { updateToolbar(); }
        }, false);
        findViewById(R.id.btn_about).setOnClickListener(v -> AboutDialog.show(this));

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            // 当前根页面再次点选时保留输入和滚动；从子页返回或外部打开插件仍执行导航。
            if (nav.getSelectedItemId()==item.getItemId()
                    && getSupportFragmentManager().getBackStackEntryCount()==0
                    && getSupportFragmentManager().findFragmentById(R.id.fragment_container)!=null
                    && !(getSupportFragmentManager().findFragmentById(R.id.fragment_container) instanceof EnvironmentRecoveryFragment)
                    && !getIntent().getBooleanExtra("open_plugins",false)) return true;
            if (!getSupportFragmentManager().isStateSaved())
                getSupportFragmentManager().popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
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
                title.setText(R.string.nav_launch);
            } else if (id == R.id.nav_plugins) {
                f = com.deepseekharness.app.core.EnvironmentAccess.needsRecovery(controller)
                        ? new EnvironmentRecoveryFragment() : new PluginFragment();
                if (getIntent().getBooleanExtra("open_plugins", false)) {
                    Bundle args = new Bundle(); args.putBoolean("show_installed", true); f.setArguments(args);
                    getIntent().removeExtra("open_plugins");
                }
                title.setText(R.string.nav_plugins);
            } else if (id == R.id.nav_settings) {
                f = new SettingsFragment();
                title.setText(R.string.nav_settings);
            } else {
                // 终端：默认挂真 PTY 页（vim/htop/tmux 能跑），可在 PTY 页切回简易版
                f = com.deepseekharness.app.core.EnvironmentAccess.needsRecovery(controller) ? new EnvironmentRecoveryFragment() : PtyTerminalFragment.preferred(this)
                        ? new PtyTerminalFragment() : new TerminalFragment();
                title.setText(R.string.nav_terminal);
            }
            UiMotion.page(this, getSupportFragmentManager().beginTransaction())
                    .replace(R.id.fragment_container, f)
                    .commit();
            return true;
        });

        // 通知点击进入：必须在 setSelectedItemId 之前登记 —— setSelectedItemId 会【同步】
        // 触发监听器创建 LaunchFragment，那时再置标记已经晚了（参数带不进去）。
        consumeOpenWeb(getIntent());
        if (savedInstanceState == null) {
            nav.setSelectedItemId(getIntent().getBooleanExtra("open_plugins", false) ? R.id.nav_plugins : R.id.nav_launch);
        }
        // 只订阅后台检查；提示不切换导航，也不自动打开更新页或 Web。
        startupUpdates = com.deepseekharness.app.core.UpdateEngine.get(this);
        startupUpdates.state().observe(this, state -> showStartupUpdate());
        startupUpdates.checkOnStartup(config.isCheckUpdate());
    }

    private void showStartupUpdate() {
        if (startupUpdates == null || updateNotice != null || isFinishing() || !hasWindowFocus()
                || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                || !new ConfigStore(this).isCheckUpdate()) return;
        com.deepseekharness.app.util.UpdatePolicy.Release release = startupUpdates.startupNotice();
        if (release == null) return;
        updateNotice = com.google.android.material.snackbar.Snackbar.make(findViewById(android.R.id.content),
                com.deepseekharness.app.util.UiText.text("发现新版本 ") + release.version + " · " + com.deepseekharness.app.core.UpdateEngine.channelName(startupUpdates.channel()),
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAnchorView(R.id.bottom_nav)
                .setAction(com.deepseekharness.app.util.UiText.text("查看更新"), view -> startActivity(new Intent(this, UpdateActivity.class)));
        updateNotice.addCallback(new com.google.android.material.snackbar.Snackbar.Callback() {
            @Override public void onShown(com.google.android.material.snackbar.Snackbar bar) {
                if (hasWindowFocus() && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                    startupUpdates.markStartupNoticeShown(release);
            }
            @Override public void onDismissed(com.google.android.material.snackbar.Snackbar bar, int event) {
                if (updateNotice == bar) updateNotice = null;
            }
        });
        updateNotice.show();
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        showStartupUpdate();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showStartupUpdate();
    }

    @Override protected void onPause() {
        recoveryHandler.removeCallbacks(refreshRecovery);
        if (updateNotice != null) { updateNotice.dismiss(); updateNotice = null; }
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav != null && intent.getBooleanExtra("open_plugins", false)) nav.setSelectedItemId(R.id.nav_plugins);
        if (nav != null && intent.getBooleanExtra("open_launch", false)) {
            intent.removeExtra("open_launch"); nav.setSelectedItemId(R.id.nav_launch);
        }
        consumeOpenWeb(intent);
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
        findViewById(R.id.sub_back).setVisibility(nested ? android.view.View.VISIBLE : android.view.View.GONE);
        findViewById(R.id.app_logo).setVisibility(nested ? android.view.View.GONE : android.view.View.VISIBLE);
        if (shown instanceof ConfigFragment) title.setText(com.deepseekharness.app.util.UiText.text("配置"));
        else if (shown instanceof DeviceGrantsFragment) title.setText(com.deepseekharness.app.util.UiText.text("设备能力授权"));
        else if (shown instanceof WorkspaceFragment) title.setText(com.deepseekharness.app.util.UiText.text("数据与备份"));
        else if (shown instanceof InstallFragment) title.setText(com.deepseekharness.app.util.UiText.text("安装与修复"));
        else if (shown instanceof SettingsFragment) title.setText(com.deepseekharness.app.util.UiText.text("设置"));
        else if (shown instanceof PluginFragment) title.setText(com.deepseekharness.app.util.UiText.text("插件"));
        else if (shown instanceof TerminalFragment || shown instanceof PtyTerminalFragment) title.setText(com.deepseekharness.app.util.UiText.text("终端"));
        else title.setText(com.deepseekharness.app.util.UiText.text("启动"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(!new ConfigStore(this).getUiLanguage().equals(com.deepseekharness.app.util.UiText.language()))LanguageController.apply(this);
        current = this;
        recoveryHandler.removeCallbacks(refreshRecovery);
        recoveryHandler.post(refreshRecovery);
        updateToolbar();
        if (!isFinishing() && findViewById(R.id.bottom_nav) != null
                && (new ConfigStore(this).isLanMode()
                || com.deepseekharness.app.DeviceBridgeService.isAdbEnabled(this))
                && !com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)
                && !getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, MODE_PRIVATE)
                .getBoolean("local_network_permission_asked", false)) requestLocalNetwork();
        // Android 12+ 可能拒绝后台唤起前台服务，回到可见界面后补一次恢复。
        if (!isFinishing() && findViewById(R.id.bottom_nav) != null
                && com.deepseekharness.app.DeviceBridgeService.isAdbEnabled(this)
                && !com.deepseekharness.app.DeviceBridgeService.isRunning()) {
            com.deepseekharness.app.DeviceBridgeService.apply(this);
        }
    }

    private final android.os.Handler recoveryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshRecovery = new Runnable() {
        @Override public void run() {
            TextView banner = findViewById(R.id.environment_recovery_banner);
            if (banner == null || isFinishing()) return;
            boolean limited = com.deepseekharness.app.core.EnvironmentAccess.needsRecovery(HarnessController.get(MainActivity.this));
            boolean busy = com.deepseekharness.app.core.BackupTask.get(MainActivity.this).maintenanceBusy();
            banner.setVisibility(limited || busy ? android.view.View.VISIBLE : android.view.View.GONE);
            banner.setText(busy ? com.deepseekharness.app.util.UiText.text("环境维护进行中 · 点击查看进度") : com.deepseekharness.app.util.UiText.text("受限模式：环境需要恢复 · 点击处理"));
            com.deepseekharness.app.util.BackupTaskState.Snapshot task=com.deepseekharness.app.core.BackupTask.get(MainActivity.this).snapshot();
            if(busy && task.busy())banner.setText((task.status==com.deepseekharness.app.util.BackupTaskState.Status.PREVIEW
                    ?com.deepseekharness.app.util.UiText.choose("等待确认恢复备份","Backup restore awaiting confirmation")
                    :com.deepseekharness.app.util.StartupText.render(task.kind))
                    +com.deepseekharness.app.util.UiText.choose(" · 点击查看进度"," · View progress"));
            HarnessController controller=HarnessController.get(MainActivity.this);
            boolean startupRecovery=controller.config().isStartupRecoveryRequested() || com.deepseekharness.app.core.StartupRepairs.pending(MainActivity.this);
            String key=controller.startupDiagnostics().recordId()+":"+controller.config().getWebFailureReason()+":"+com.deepseekharness.app.core.StartupRepairs.pending(MainActivity.this);
            if(startupRecovery && !limited && !busy && !controller.isStarting() && !controller.isStopping()
                    && !key.equals(openedRecovery) && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                openedRecovery=key;startActivity(new Intent(MainActivity.this,StartupRecoveryActivity.class));
            }
            recoveryHandler.postDelayed(this, 1000);
        }
    };

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("opened_startup_recovery",openedRecovery);super.onSaveInstanceState(state);
    }
    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        // 主题切换/旋转只重建界面，不能把正在运行的终端一起关闭。
        if (!isChangingConfigurations()) {
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
        ctx.startActivity(new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
}
