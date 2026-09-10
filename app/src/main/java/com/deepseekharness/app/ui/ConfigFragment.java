package com.deepseekharness.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.OverlayController;
import com.deepseekharness.app.R;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;

/**
 * 配置子页：接入（API Key / 端口）+ 行为开关 + ADB 设备通道。
 * 所有开关都落到 ConfigStore / SharedPreferences，并真正影响启动与预览。
 */
public class ConfigFragment extends Fragment {
    private int adbStatusRequest;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_config, container, false);
        ConfigStore c = new ConfigStore(requireContext());
        Context ctx = requireContext();


        v.findViewById(R.id.config_workspace_entry).setOnClickListener(x -> open(new WorkspaceFragment()));

        EditText apiKey = v.findViewById(R.id.config_api_key);
        EditText port = v.findViewById(R.id.config_port);
        CheckBox confirm = v.findViewById(R.id.config_confirm_shell);
        CheckBox rootShell = v.findViewById(R.id.config_root_shell);
        CheckBox checkUpdate = v.findViewById(R.id.config_check_update);
        CheckBox desktop = v.findViewById(R.id.config_desktop_mode);
        CheckBox backupKey = v.findViewById(R.id.config_backup_key);
        CheckBox proroot = v.findViewById(R.id.config_proroot);
        CheckBox lan = v.findViewById(R.id.config_lan_mode);
        CheckBox overlay = v.findViewById(R.id.config_overlay_stream);
        CheckBox sensors = v.findViewById(R.id.config_cap_sensors);
        CheckBox location = v.findViewById(R.id.config_cap_location);
        CheckBox adb = v.findViewById(R.id.config_adb_enable);
        Button save = v.findViewById(R.id.config_save);

        // 高级项折叠
        View advBody = v.findViewById(R.id.config_adv_body);
        v.findViewById(R.id.config_adv_header).setOnClickListener(x ->
                advBody.setVisibility(advBody.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        // 回填当前值
        apiKey.setText(c.getApiKey());
        port.setText(c.getPort());
        confirm.setChecked(c.isConfirmShell());
        rootShell.setChecked(c.isRootShellAllowed());
        checkUpdate.setChecked(c.isCheckUpdate());
        desktop.setChecked(c.isDesktopMode());
        CheckBox gecko = v.findViewById(R.id.config_gecko_core);
        gecko.setVisibility(com.deepseekharness.app.BuildConfig.LOW_ANDROID ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.config_gecko_hint).setVisibility(com.deepseekharness.app.BuildConfig.LOW_ANDROID ? View.VISIBLE : View.GONE);
        gecko.setChecked(c.isGeckoCore());
        backupKey.setChecked(c.isBackupKey());
        proroot.setChecked(c.isProroot());
        lan.setChecked(c.isLanMode());
        overlay.setChecked(pref(ctx, "overlay_stream", false));
        sensors.setChecked(pref(ctx, "cap_sensors", false));
        location.setChecked(pref(ctx, "cap_location", false));
        location.setOnCheckedChangeListener((button, checked) -> {
            if (checked && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION}, 104);
        });
        adb.setChecked(pref(ctx, "adb_enabled", false));
        TextView adbStatus = v.findViewById(R.id.config_adb_status);
        adb.setOnCheckedChangeListener((b, checked) ->
                adbStatus.setText(checked
                        ? "ADB 已开启。无线配对：开发者选项 → 无线调试"
                        : "ADB 已关闭。不用无线调试就保持关闭。"));
        refreshAdbStatus(adbStatus);

        // 所有文件访问权限（Android 11+ MANAGE_EXTERNAL_STORAGE）：跳系统设置开启，
        // 让容器/proot 能读写手机存储任意文件（含 DSHA 目录外），WebUI 工作区可建到 /sdcard
        View allFiles = v.findViewById(R.id.config_all_files);
        if (allFiles != null) {
            allFiles.setOnClickListener(x -> openAllFilesAccess(ctx));
            refreshAllFilesStatus(v.findViewById(R.id.config_all_files_status));
        }

        // 悬浮条外观与行为（照 1.1.9.1：底色预设 + 不透明度/行数/字号/停留 + 行为开关）
        v.findViewById(R.id.config_overlay_style).setOnClickListener(x -> showOverlayStyleDialog());

        // ADB 通道（可直接用）
        v.findViewById(R.id.config_adb_pair).setOnClickListener(x -> {
            if (android.os.Build.VERSION.SDK_INT < 30) {
                new androidx.appcompat.app.AlertDialog.Builder(ctx).setTitle("当前系统没有配对码接口")
                        .setMessage("无线调试配对码需要 Android 11+。Android 6—10 可使用 Shizuku 或由电脑开启 ADB TCP 通道；对话、插件和终端不受影响。")
                        .setPositiveButton("知道了", null).show();
            } else startActivity(new Intent(ctx, AdbPairActivity.class));
        });
        v.findViewById(R.id.config_battery_opt).setOnClickListener(x -> openBatteryOpt(ctx));
        v.findViewById(R.id.config_a11y).setOnClickListener(x -> openA11ySettings(ctx));
        refreshA11yStatus(v.findViewById(R.id.config_a11y_status));
        v.findViewById(R.id.config_runtime_update).setOnClickListener(x -> checkScriptUpdate(ctx));
        v.findViewById(R.id.config_repo_link).setOnClickListener(x -> openRepo(ctx));

        save.setOnClickListener(x -> {
            final int chosenPort;
            try { chosenPort = com.deepseekharness.app.util.ConfigInput.port(port.getText().toString()); }
            catch (IllegalArgumentException e) { advBody.setVisibility(View.VISIBLE); port.setError(e.getMessage()); port.requestFocus(); return; }
            port.setError(null); apiKey.setError(null);
            com.deepseekharness.app.util.EnvironmentTaskGate.Lease saving =
                    com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire("保存配置");
            if (saving == null) { toast("正在" + com.deepseekharness.app.util.EnvironmentTaskGate.activeKind() + "，完成后再保存配置"); return; }
            try {
            saving.run(() -> {
            String key = apiKey.getText().toString().trim();
            if (!c.saveApiKey(key)) { apiKey.setError("密钥加密保存失败，原配置已保留，请重试"); return null; }
            c.setPort(String.valueOf(chosenPort));
            c.setConfirmShell(confirm.isChecked());
            c.setRootShellAllowed(rootShell.isChecked());
            c.setCheckUpdate(checkUpdate.isChecked());
            c.setDesktopMode(desktop.isChecked());
            if (com.deepseekharness.app.BuildConfig.LOW_ANDROID) c.setGeckoCore(gecko.isChecked());
            c.setBackupKey(backupKey.isChecked());
            c.setProroot(proroot.isChecked());
            c.setLanMode(lan.isChecked());
            setPref(ctx, "overlay_stream", overlay.isChecked());
            setPref(ctx, "cap_sensors", sensors.isChecked());
            setPref(ctx, "cap_location", location.isChecked());
            setPref(ctx, "adb_enabled", adb.isChecked());
            String synced = AdbBridge.applySettings(ctx, HarnessController.get(ctx).proot());
            TextView guardStatus = v.findViewById(R.id.config_guard_status);
            guardStatus.setText("设备命令保护始终生效；" + synced.replaceFirst("^SETTINGS_[A-Z]+: ", ""));
            guardStatus.setVisibility(View.VISIBLE);
            if (adb.isChecked()) {
                DeviceBridgeService.apply(ctx);
            } else {
                ctx.stopService(new Intent(ctx, DeviceBridgeService.class));
            }
            applyLanMode(c, lan.isChecked());
            if ((adb.isChecked() || lan.isChecked()) && getActivity() instanceof MainActivity)
                ((MainActivity) getActivity()).requestLocalNetwork();
            Toast.makeText(ctx, synced.startsWith("SETTINGS_FAILED") || synced.startsWith("ENVIRONMENT_BUSY")
                    ? "配置已保存，但设备授权同步失败，请查看安全与备份下的说明并重试保存"
                    : "已保存；网页显示选项重新进入对话生效，端口与运行时需重启 Web", Toast.LENGTH_LONG).show();
            if (overlay.isChecked() && !OverlayController.permitted(ctx)) openOverlayPermission();
            refreshAdbStatus(adbStatus);
            return null;
            });
            } catch (Exception e) {
                toast("配置保存未完成：" + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e)));
            } finally { saving.close(); }
        });

        return v;
    }

    private void openOverlayPermission() {
        try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + requireContext().getPackageName()))); }
        catch (Exception e) { toast("未取得悬浮窗权限，请到系统设置 → 应用 → DSHA → 悬浮窗中允许"); }
    }

    /** LAN 开关真正生效：开启时若 dsh 已鉴权则启动 3081 代理，关闭时停掉监听。 */
    private void applyLanMode(ConfigStore c, boolean on) {
        try {
            if (!on) {
                com.deepseekharness.app.LanProxyService.stopLanListener();
                return;
            }
            HarnessController hc = new HarnessController(requireContext());
            long gen = hc.getWebGeneration();
            if (gen <= 0 || !com.deepseekharness.app.LanProxyService.hasDshAuth(gen)) {
                // dsh 还没起来/还没交换 cookie：等下次进入对话时 HarnessController 自动启动
                return;
            }
            com.deepseekharness.app.LanProxyService.start(
                    hc.proot().getRootfsDir().getAbsolutePath(),
                    requireContext(), c.getPortInt(), gen);
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "LAN 开关生效失败: " + t.getMessage());
        }
    }

    private void open(Fragment f) {
        getParentFragmentManager().beginTransaction()
                .addToBackStack(null)
                .replace(R.id.fragment_container, f)
                .commit();
    }

    /** 后台读 ADB 通道真实状态（key/deps/端口 + 保活服务的连接状态），刷到状态栏。 */
    private void refreshAdbStatus(final TextView status) {
        final Context app = requireContext().getApplicationContext();
        final View page = getView();
        final android.app.Activity activity = getActivity();
        if (activity == null) return;
        final int request = ++adbStatusRequest;
        new Thread(() -> {
            final String text = computeAdbStatus(app);
            activity.runOnUiThread(() -> {
                if (request != adbStatusRequest || status == null || getView() != page || !isAdded()) return;
                status.setText(text);
                if (text.startsWith("环境任务进行中") && isResumed()) status.postDelayed(() -> {
                    if (request == adbStatusRequest && getView() == page && isResumed()) refreshAdbStatus(status);
                }, 1500);
            });
        }, "adb-status").start();
    }

    private String computeAdbStatus(Context app) {
        try {
            if (!pref(app, "adb_enabled", false)) return "ADB 已关闭。开启并保存后才会保持连接。";
            String bridge = DeviceBridgeService.adbState;
            String detail = DeviceBridgeService.adbDetail == null ? "" : DeviceBridgeService.adbDetail;
            HarnessController hc = HarnessController.get(app);
            String st = hc.proot().isEnvironmentReady()
                    ? AdbBridge.status(hc.proot()) : "env:not_ready";
            if (st.startsWith("ENVIRONMENT_BUSY") || "environment_busy".equals(bridge))
                return "环境任务进行中，ADB 暂停检查，完成后自动重试。"
                        + (detail.isEmpty() ? "" : "\n" + detail);
            boolean key = st.contains("key=YES");
            String port = "?";
            int p = st.indexOf("port=");
            if (p >= 0) port = st.substring(p + 5).trim();
            if ("need_pair".equals(bridge)) {
                return "配对已失效，请重新配对。";
            } else if ("reconnecting".equals(bridge)) {
                return "正在重连无线调试…" + (detail.isEmpty() ? "" : "\n" + detail);
            } else if ("connected".equals(bridge)) {
                return "连接已验证 · 端口 " + port + (detail.isEmpty() ? "" : "\n" + detail);
            } else if (key) {
                return "配对密钥已保存，连接尚未验证。" + (detail.isEmpty() ? "" : "\n" + detail);
            } else {
                return "尚未配对：点下方「ADB 无线配对」，配对后还会验证连接。";
            }
        } catch (Throwable e) {
            return "ADB 状态读取失败：" + e.getMessage();
        }
    }

    private void openBatteryOpt(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            toast("无法打开电池优化设置");
        }
    }

    /** 「所有文件访问」入口：Android 11+ 跳系统 MANAGE 设置；Android 6-10 请求 WRITE_EXTERNAL_STORAGE。 */
    private void openAllFilesAccess(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + ctx.getPackageName()));
                startActivity(i);
            } catch (Throwable e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Throwable e2) {
                    toast("打开设置失败：" + e2.getMessage());
                }
            }
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            // Android 6-10：运行时请求 WRITE_EXTERNAL_STORAGE（Android 10 作用域存储下尽力而为）
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 501);
            } else {
                toast("存储权限已授予，容器可访问手机存储");
            }
            return;
        }
        toast("当前系统无需存储权限");
    }

    /** 刷新「所有文件访问权限」状态行（含从系统设置返回后的更新）。 */
    private void refreshAllFilesStatus(TextView status) {
        if (status == null) return;
        boolean granted;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            granted = Environment.isExternalStorageManager();
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            granted = requireContext().checkSelfPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } else {
            granted = true;
        }
        status.setText(granted
                ? "已开启：容器可读写手机存储任意文件（含 DSHA 目录外）"
                : "未开启：仅能访问 App 私有目录；去系统设置开启后可访问全部文件");
        try {
            status.setTextColor(granted
                    ? getResources().getColor(R.color.primary, null)
                    : getResources().getColor(R.color.err, null));
        } catch (Throwable ignored) {
        }
    }

    /** 跳到本应用无障碍服务的开关页；部分 ROM 不支持直达就退回系统无障碍列表。 */
    private void openA11ySettings(Context ctx) {
        try {
            // Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS 常量在 compileSdk 里缺失
            // （各版本 SDK 不一致），直接用 action 字符串，运行时兼容
            Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(Intent.EXTRA_COMPONENT_NAME,
                    new ComponentName(ctx, DshaAccessibilityService.class));
            startActivity(i);
        } catch (Throwable e) {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable e2) {
                toast("打不开无障碍设置：" + e2.getMessage());
            }
        }
    }

    /** 刷新「屏幕操作权限」状态行：是否已开启无障碍服务（从系统设置返回后也会更新）。 */
    private void refreshA11yStatus(TextView status) {
        if (status == null) return;
        String st = DshaAccessibilityService.enabledState(requireContext());
        boolean ok = "YES".equals(st);
        if (ok) {
            status.setText("✅ 已开启：AI 可读屏 / 点按 / 输入 / 截屏（截屏需 Android 11+）");
        } else if ("NO".equals(st)) {
            status.setText("❌ 未开启：点上方去系统设置开启「DSHA 配对助手」");
        } else {
            status.setText("⚠️ 状态未知：点上方到系统设置确认「DSHA 配对助手」已开启");
        }
        try {
            status.setTextColor(getResources().getColor(ok ? R.color.primary : R.color.err, null));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            View v = getView();
            if (v != null) refreshAllFilesStatus(v.findViewById(R.id.config_all_files_status));
            if (v != null) refreshA11yStatus(v.findViewById(R.id.config_a11y_status));
            if (v != null) refreshAdbStatus(v.findViewById(R.id.config_adb_status));
        } catch (Throwable ignored) {
        }
    }

    // ================= 悬浮条外观与行为（照 1.1.9.1 移植） =================

    private void showOverlayStyleDialog() {
        final android.content.Context app = requireContext().getApplicationContext();
        final android.content.SharedPreferences sp = requireContext()
                .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);

        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dpx(16);
        box.setPadding(pad, pad, pad, 0);

        // 底色不做取色器：悬浮条只需要「在任何壁纸上都读得清」，几个深色预设够用
        box.addView(sectionLabel("底色"));
        final int[] pickedBg = {sp.getInt(OverlayController.K_BG, 0)};
        LinearLayout swatches = new LinearLayout(requireContext());
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] cells = new TextView[OverlayController.BG_PRESETS.length];
        for (int i = 0; i < OverlayController.BG_PRESETS.length; i++) {
            final int idx = i;
            TextView cell = new TextView(requireContext());
            cell.setText(OverlayController.BG_NAMES[i]);
            cell.setTextColor(0xFFFFFFFF);
            cell.setTextSize(11f);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dpx(6), dpx(10), dpx(6), dpx(10));
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dpx(4);
            cell.setLayoutParams(lp);
            cells[i] = cell;
            cell.setOnClickListener(v -> {
                pickedBg[0] = idx;
                paintSwatches(cells, pickedBg[0]);
            });
            swatches.addView(cell);
        }
        paintSwatches(cells, pickedBg[0]);
        box.addView(swatches);

        final android.widget.SeekBar alpha = slider(box, "底色不透明度", 20, 100,
                sp.getInt(OverlayController.K_ALPHA, OverlayController.DEF_ALPHA), "%");
        final android.widget.SeekBar lines = slider(box, "最多显示几行（写满后丢最旧一行）", 1, 8,
                sp.getInt(OverlayController.K_LINES, OverlayController.DEF_LINES), " 行");
        final android.widget.SeekBar wide = slider(box, "字号（越小一行放得越多）", 6, 20,
                sp.getInt(OverlayController.K_TEXT_SP, OverlayController.DEF_TEXT_SP), " sp");
        final android.widget.SeekBar hold = slider(box, "无新内容后停留", 2, 60,
                sp.getInt(OverlayController.K_HOLD, OverlayController.DEF_HOLD), " 秒");

        final CheckBox think = new CheckBox(requireContext());
        think.setText("显示思考过程（reasoning，会明显更吵）");
        think.setChecked(sp.getBoolean(OverlayController.K_REASONING, false));
        box.addView(think);

        final CheckBox cmd = new CheckBox(requireContext());
        cmd.setText("工具调用带上命令原文（否则只看到「正在执行命令」）");
        cmd.setChecked(sp.getBoolean(OverlayController.K_COMMAND, true));
        box.addView(cmd);

        final CheckBox confirmHere = new CheckBox(requireContext());
        confirmHere.setText("危险命令在悬浮条上直接批准（不必切回 App 或拉通知栏）");
        confirmHere.setChecked(sp.getBoolean(OverlayController.K_CONFIRM, true));
        box.addView(confirmHere);

        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(box);

        // 保存抽成 Runnable：「预览」要能不关对话框就先落盘，否则看到的还是旧样式
        final Runnable save = () -> sp.edit()
                .putInt(OverlayController.K_BG, pickedBg[0])
                .putInt(OverlayController.K_ALPHA, Math.max(20, alpha.getProgress()))
                .putInt(OverlayController.K_LINES, Math.max(1, lines.getProgress()))
                .putInt(OverlayController.K_TEXT_SP, Math.max(6, wide.getProgress()))
                .putInt(OverlayController.K_HOLD, Math.max(2, hold.getProgress()))
                .putBoolean(OverlayController.K_REASONING, think.isChecked())
                .putBoolean(OverlayController.K_COMMAND, cmd.isChecked())
                .putBoolean(OverlayController.K_CONFIRM, confirmHere.isChecked())
                .apply();

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("悬浮条外观与行为")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    save.run();
                    OverlayController.applyStyleNow(app);
                    Toast.makeText(requireContext(), "已保存（下一条输出即生效）",
                            Toast.LENGTH_SHORT).show();
                })
                // 中间按钮当预览：调样式最烦的就是「保存 → 等 agent 说话 → 不合适 → 再调」
                .setNeutralButton("预览", (d, w) -> {
                    save.run();
                    if (!OverlayController.permitted(requireContext())) {
                        Toast.makeText(requireContext(), "还没给悬浮窗权限，先勾上面那个开关授权",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    OverlayController.applyStyleNow(app);
                    OverlayController.push(app, "preview", "text",
                            "这是预览：AI 的回复会像这样流出来，调工具时会变成"
                                    + "「⚙ 正在执行命令: ls -la」这种。");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void paintSwatches(TextView[] cells, int picked) {
        for (int i = 0; i < cells.length; i++) {
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dpx(10));
            bg.setColor(0xFF000000 | OverlayController.BG_PRESETS[i]);
            // 选中描边：几个深色块之间光靠颜色分不清哪个选上了
            if (i == picked) bg.setStroke(dpx(2), 0xFF7DA7F4);
            cells[i].setBackground(bg);
        }
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextSize(12f);
        t.setPadding(0, dpx(8), 0, dpx(4));
        return t;
    }

    /** 一条「标题 + 当前值」的滑杆。SeekBar 只有 0..max，下限靠回弹保证。 */
    private android.widget.SeekBar slider(LinearLayout parent, String title,
                                          int min, int max, int value, String unit) {
        final TextView label = sectionLabel(title + "：" + value + unit);
        parent.addView(label);
        final android.widget.SeekBar bar = new android.widget.SeekBar(requireContext());
        bar.setMax(max);
        bar.setProgress(Math.max(min, Math.min(max, value)));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                if (progress < min) {
                    sb.setProgress(min);
                    return;
                }
                label.setText(title + "：" + progress + unit);
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
            }
        });
        parent.addView(bar);
        return bar;
    }

    private int dpx(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void openDeveloperOptions(Context ctx) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            toast("无法打开开发者选项");
        }
    }

    private void checkScriptUpdate(Context ctx) {
        try { startActivity(new Intent(ctx, UpdateActivity.class)); }
        catch (Exception e) { toast("无法打开更新页面：" + e.getClass().getSimpleName()); }
    }

    private void openRepo(Context ctx) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/qiannianhuanxiang/DSHA")));
        } catch (Exception e) {
            toast("无法打开浏览器");
        }
    }

    private boolean pref(Context ctx, String k, boolean def) {
        return ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).getBoolean(k, def);
    }

    private void setPref(Context ctx, String k, boolean v) {
        ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit().putBoolean(k, v).apply();
    }

    private void toast(String s) {
        Context context = getContext();
        if (context != null) Toast.makeText(context, s, Toast.LENGTH_SHORT).show();
    }
}
