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
    private final androidx.activity.result.ActivityResultLauncher<String> localNetworkPermission =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    granted -> {
                        requestingLocalNetwork = false;
                        if (granted) com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(this);
                        else android.widget.Toast.makeText(this,
                                "未允许局域网访问；本机对话仍可使用，LAN / 无线 ADB 需在系统权限设置中开启",
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

        ConfigStore config = new ConfigStore(this);
        HarnessController controller = HarnessController.get(this);
        boolean skipExtract = com.deepseekharness.app.BuildConfig.DEBUG && getIntent().getBooleanExtra("skip_extract", false);

        // 启动门禁：未欢迎 → Welcome；环境未解压 → Extract
        if (!config.isWelcomed()) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }
        if (com.deepseekharness.app.BackupManager.hasPendingMaintenance(controller)
                || !skipExtract && !controller.isEnvironmentReady()) {
            startActivity(new Intent(this, ExtractActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        String pendingLink = getSharedPreferences("dsha-install-link", MODE_PRIVATE).getString("pending", "");
        if (!pendingLink.isEmpty()) {
            getSharedPreferences("dsha-install-link", MODE_PRIVATE).edit().remove("pending").apply();
            try {
                com.deepseekharness.app.util.PluginInstallLink.parse(pendingLink);
                startActivity(new Intent(this, PluginInstallActivity.class).setData(android.net.Uri.parse(pendingLink)));
            } catch (IllegalArgumentException ignored) { }
        }

        TextView title = findViewById(R.id.app_title);
        TextView theme = findViewById(R.id.btn_theme);
        boolean dark = ThemeController.isDark(this);
        theme.setText(dark ? "黑夜" : "白天");
        theme.setContentDescription(dark ? "当前黑夜模式，点击切换白天" : "当前白天模式，点击切换黑夜");
        theme.setOnClickListener(v -> ThemeController.toggle(this));
        findViewById(R.id.sub_back).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateToolbar);
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            @Override public void onFragmentResumed(androidx.fragment.app.FragmentManager manager, Fragment fragment) { updateToolbar(); }
        }, false);
        findViewById(R.id.btn_about).setOnClickListener(v -> AboutDialog.show(this));

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            if (!getSupportFragmentManager().isStateSaved())
                getSupportFragmentManager().popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
            Fragment f;
            int id = item.getItemId();
            if (id == R.id.nav_launch) {
                f = new LaunchFragment();
                title.setText(R.string.nav_launch);
            } else if (id == R.id.nav_plugins) {
                f = new PluginFragment();
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
                f = PtyTerminalFragment.preferred(this)
                        ? new PtyTerminalFragment() : new TerminalFragment();
                title.setText(R.string.nav_terminal);
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, f)
                    .commit();
            return true;
        });

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
                "发现新版本 " + release.version + " · " + com.deepseekharness.app.core.UpdateEngine.channelName(startupUpdates.channel()),
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAnchorView(R.id.bottom_nav)
                .setAction("查看更新", view -> startActivity(new Intent(this, UpdateActivity.class)));
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
    }

    private void updateToolbar() {
        TextView title = findViewById(R.id.app_title);
        if (title == null) return;
        Fragment shown = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        boolean nested = getSupportFragmentManager().getBackStackEntryCount() > 0;
        findViewById(R.id.sub_back).setVisibility(nested ? android.view.View.VISIBLE : android.view.View.GONE);
        findViewById(R.id.app_logo).setVisibility(nested ? android.view.View.GONE : android.view.View.VISIBLE);
        if (shown instanceof ConfigFragment) title.setText("配置");
        else if (shown instanceof WorkspaceFragment) title.setText("数据与备份");
        else if (shown instanceof InstallFragment) title.setText("安装与修复");
        else if (shown instanceof SettingsFragment) title.setText("设置");
        else if (shown instanceof PluginFragment) title.setText("插件");
        else if (shown instanceof TerminalFragment || shown instanceof PtyTerminalFragment) title.setText("终端");
        else title.setText("启动");
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
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
