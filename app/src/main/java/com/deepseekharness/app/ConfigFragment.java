package com.deepseekharness.app;

import android.os.Bundle;
import android.os.Build;
import android.content.Context;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.app.PendingIntent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.fragment.app.Fragment;

/** 配置模块：API key / 端口 / 模型 / 沙箱模式 */
public class ConfigFragment extends Fragment {

    private HarnessController c;
    private EditText apiKeyEdit, portEdit;
    private CheckBox confirmShellCb, checkUpdateCb, desktopModeCb, lanModeCb, rc6Cb, geckoCb, adbCb, rootShellCb, prorootCb;
    private CheckBox backupKeyCb;
    private CheckBox overlayStreamCb;
    private CheckBox capSensorsCb, capLocationCb;
    private EditText autoBackupEdit;
    private Button saveBtn;
    private TextView repoLink;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        apiKeyEdit = view.findViewById(R.id.config_api_key);
        portEdit = view.findViewById(R.id.config_port);
        confirmShellCb = view.findViewById(R.id.config_confirm_shell);
        checkUpdateCb = view.findViewById(R.id.config_check_update);
        desktopModeCb = view.findViewById(R.id.config_desktop_mode);
        backupKeyCb = view.findViewById(R.id.config_backup_key);
        lanModeCb = view.findViewById(R.id.config_lan_mode);
        View overlayStyle = view.findViewById(R.id.config_overlay_style);
        if (overlayStyle != null) overlayStyle.setOnClickListener(v -> showOverlayStyleDialog());
        View uiTheme = view.findViewById(R.id.config_ui_theme);
        if (uiTheme != null) uiTheme.setOnClickListener(v -> showAppearanceDialog());
        overlayStreamCb = view.findViewById(R.id.config_overlay_stream);
        if (overlayStreamCb != null) {
            // 勾上时才要权限：没授权就直接引导过去，别让用户勾了个不生效的开关
            overlayStreamCb.setOnCheckedChangeListener((btn, checked) -> {
                if (!checked) {
                    OverlayController.teardown(requireContext().getApplicationContext());
                    return;
                }
                if (!OverlayController.permitted(requireContext())) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("需要悬浮窗权限")
                            .setMessage("在屏幕顶部实时显示 AI 输出需要「显示在其他应用上层」权限。\n\n"
                                    + "顺便提醒：开启后内容会直接显示在屏幕上，旁边的人也看得见。")
                            .setPositiveButton("去开启", (d, w) -> {
                                try {
                                    startActivity(new android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:" + requireContext().getPackageName())));
                                } catch (Throwable e) {
                                    Toast.makeText(requireContext(),
                                            "打不开系统设置，请手动到「应用权限 → 显示在其他应用上层」开启",
                                            Toast.LENGTH_LONG).show();
                                }
                            })
                            .setNegativeButton("算了", (d, w) -> btn.setChecked(false))
                            .setOnCancelListener(d -> btn.setChecked(false))
                            .show();
                }
            });
        }
        capSensorsCb = view.findViewById(R.id.config_cap_sensors);
        capLocationCb = view.findViewById(R.id.config_cap_location);
        if (capLocationCb != null) {
            // 与悬浮窗那个开关同一个道理：勾上就当场把系统权限要到，
            // 要不到就把开关退回去 —— 界面显示「开着」而实际读不到，是最难排查的一类问题。
            // 位置比悬浮窗更敏感，所以文案要把「agent 随时能读」讲明白再让用户决定。
            capLocationCb.setOnCheckedChangeListener((btn, checked) -> {
                if (!checked || !isAdded()) return;
                if (locationGranted()) return;
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("需要定位权限")
                        .setMessage("开启后，运行中的 agent 随时可以读取这台手机的位置"
                                + "（经纬度、精度、海拔）。\n\n"
                                + "只在你确实要它回答「我在哪」「附近有什么」这类问题时开启，"
                                + "平时建议保持关闭。")
                        .setPositiveButton("去授权", (d, w) -> {
                            try {
                                requestPermissions(new String[]{
                                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                                        android.Manifest.permission.ACCESS_COARSE_LOCATION}, 101);
                            } catch (Throwable e) {
                                btn.setChecked(false);
                                Toast.makeText(requireContext(),
                                        "无法申请定位权限，请手动到「应用权限 → 位置」开启",
                                        Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("算了", (d, w) -> btn.setChecked(false))
                        .setOnCancelListener(d -> btn.setChecked(false))
                        .show();
            });
        }
        rc6Cb = view.findViewById(R.id.config_rc6);
        autoBackupEdit = view.findViewById(R.id.config_auto_backup);
        geckoCb = view.findViewById(R.id.config_gecko_core);
        prorootCb = view.findViewById(R.id.config_proroot);
        // 高级项折叠：默认收起，点标题展开。已经改过端口/模型的用户
        // （非默认值）自动展开，否则他们会以为设置丢了。
        final View advBody = view.findViewById(R.id.config_adv_body);
        final TextView advHeader = view.findViewById(R.id.config_adv_header);
        if (advBody != null && advHeader != null) {
            boolean customized = !"3080".equals(c.getPort().trim());
            advBody.setVisibility(customized ? View.VISIBLE : View.GONE);
            advHeader.setText((customized ? "▾" : "▸") + " 高级：端口");
            advHeader.setOnClickListener(v -> {
                boolean show = advBody.getVisibility() != View.VISIBLE;
                advBody.setVisibility(show ? View.VISIBLE : View.GONE);
                advHeader.setText((show ? "▾" : "▸") + " 高级：端口");
            });
        }
        adbCb = view.findViewById(R.id.config_adb_enable);
        rootShellCb = view.findViewById(R.id.config_root_shell);
        saveBtn = view.findViewById(R.id.config_save);
        repoLink = view.findViewById(R.id.config_repo_link);
        bindDevBridgeSwitch(view);
        SubPageBack.bind(this, view);
        setupCommonControls(); // 模式 spinner / 保存 / 关于
        // 工作区（文件/备份恢复/环境管理）→ 二级页面
        TextView workspaceEntry = view.findViewById(R.id.config_workspace_entry);
        if (workspaceEntry != null) {
            workspaceEntry.setOnClickListener(v ->
                    requireActivity().getSupportFragmentManager().beginTransaction()
                            .replace(R.id.fragment_container, new WorkspaceFragment())
                            .addToBackStack("workspace")
                            .commit());
        }

        Button batteryBtn = view.findViewById(R.id.config_battery_opt);
        if (batteryBtn != null) {
            refreshBatteryOptState(view);
        bindA11yEntry(view);
        bindRuntimeUpdate(view);
            batteryBtn.setOnClickListener(v -> requestIgnoreBatteryOpt());
        }
        Button adbPairBtn = view.findViewById(R.id.config_adb_pair);
        if (adbPairBtn != null) {
            adbPairBtn.setOnClickListener(v -> {
                if (!DeviceBridgeService.isAdbEnabled(requireContext())) {
                    Toast.makeText(requireContext(), "先勾选「启用 ADB」并保存", Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    DeviceBridgeService.apply(requireContext());
                    showAdbPairNotification();
                } catch (Throwable t) {
                    Toast.makeText(requireContext(), "无法打开 ADB 配对：" + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
        startAdbStatusPolling(); // 设置页在前台时每秒检测 ADB 运行状态
    }

    // ================= ADB 运行状态实时检测（每秒；仅本页前台时） =================
    private final android.os.Handler adbStatusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable adbStatusTick = this::pollAdbStatus;
    private boolean adbPolling = false;

    /** 启动每秒轮询（Fragment 可见时）。实际探测一次要起 proot 子进程（1~3s），
     *  1s 太频繁 → 5s 一次（线程异步不卡 UI，但降低 CPU/电量消耗） */
    private static final long ADB_POLL_MS = 5000;

    /** 启动 ADB 状态轮询（Fragment 可见时） */
    private void startAdbStatusPolling() {
        if (adbPolling) return;
        adbPolling = true;
        adbStatusHandler.postDelayed(adbStatusTick, 1000);
    }

    /** 停止轮询（Fragment 不可见时，省电） */
    private void stopAdbStatusPolling() {
        adbPolling = false;
        adbStatusHandler.removeCallbacks(adbStatusTick);
    }

    /** 每 5 秒执行：查询 ADB 实际运行状态并刷新 UI */
    private void pollAdbStatus() {
        if (!adbPolling) return;
        if (!isAdded() || getView() == null) {
            stopAdbStatusPolling();
            return;
        }
        // getView() 在 Fragment 视图销毁后为 null（同一文件下面第二处就判了空，
        // 这里漏了）。视图没了直接返回，后面的绑定也没有意义。
        android.view.View rootV = getView();
        if (rootV == null) return;
        final TextView adbStatus = rootV.findViewById(R.id.config_adb_status);
        if (adbStatus == null) { stopAdbStatusPolling(); return; }
        if (!DeviceBridgeService.isAdbEnabled(requireContext())) {
            adbStatus.setText("ADB 已关闭。不用无线调试就保持关闭。");
            adbStatusHandler.postDelayed(adbStatusTick, ADB_POLL_MS);
            return;
        }
        new Thread(() -> {
            try {
                // 探测 adb 是否真实可用（用 rootfs 里的 adb-shell 实际跑一下，最准）
                String r = c.getProot().execAndRead("DSH_INTERNAL=1 python3 /root/.dsh/adb-shell.py id 2>&1 | head -2");
                final boolean connected = r != null && r.contains("uid=");
                final String detail = r == null ? "" : r.replace("\n", " ").trim();
                // 轮询线程每隔几秒回来一次，而 requireActivity() 在 Fragment detach 后
                // 抛 IllegalStateException —— 抛在这个后台线程上就是整个进程崩。
                // isAdded() 与 requireActivity() 之间还有窗口，所以直接取 getActivity()。
                android.app.Activity act = getActivity();
                if (act != null && isAdded()) act.runOnUiThread(() -> {
                    TextView tv = getView() != null ? getView().findViewById(R.id.config_adb_status) : null;
                    if (tv == null) return;     // detach 后 getView() 就是 null，这里天然收口
                    if (connected) {
                        tv.setTextColor(tv.getContext().getColor(R.color.ok));
                        tv.setText("● ADB 运行中（已连接，uid=2000 shell）\n" + (detail.length() > 80 ? detail.substring(0, 80) : detail));
                    } else {
                        tv.setTextColor(tv.getContext().getColor(R.color.warn));
                        tv.setText("○ ADB 未连接（无线调试可能未开启）\n点下方「无线配对」或查看手机「开发者选项→无线调试」");
                    }
                    // 3090 桥绑定失败（端口被别的应用占了）时一并摊开说——否则表现出来
                    // 只是「确认弹窗不出现 / agent 调什么都超时」，很难定位
                    String bridgeErr = HttpShellService.bindError();
                    if (bridgeErr != null && !bridgeErr.isEmpty()) {
                        tv.setTextColor(tv.getContext().getColor(R.color.err));
                        tv.setText(tv.getText() + "\n⚠ 命令桥未启动：" + bridgeErr);
                    }
                });
            } catch (Throwable ignored) {
            }
            if (adbPolling) adbStatusHandler.postDelayed(adbStatusTick, ADB_POLL_MS);
        }, "adb-status-poll").start();
    }

    /** 电池优化白名单：系统休眠会冻结后台网络，不放行的话 ADB 保活等于白做 */
    private void refreshBatteryOptState(View root) {
        if (root == null) return;
        Button btn = root.findViewById(R.id.config_battery_opt);
        TextView hint = root.findViewById(R.id.config_battery_opt_hint);
        if (btn == null) return;
        boolean ignoring = false;
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    requireContext().getSystemService(android.content.Context.POWER_SERVICE);
            ignoring = pm != null
                    && pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
        } catch (Throwable ignored) {
        }
        if (ignoring) {
            btn.setText("🔋 已关闭电池优化 ✓");
            if (hint != null) {
                hint.setText("已在白名单内：系统休眠不会再冻结 ADB 连接与后台自愈。");
                hint.setTextColor(requireContext().getColor(R.color.ok));
            }
        } else {
            btn.setText("🔋 关闭电池优化（保活必做）");
            if (hint != null) {
                hint.setText("系统休眠会冻结后台网络，ADB 连接因此断掉且无法自动恢复。加入白名单后保活才真正生效。");
                hint.setTextColor(requireContext().getColor(R.color.warn));
            }
        }
    }

    private void requestIgnoreBatteryOpt() {
        String pkg = requireContext().getPackageName();
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    requireContext().getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(pkg)) {
                // 已放行：跳系统列表页，用户可以自己核对或撤销
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                return;
            }
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:" + pkg)));
        } catch (Throwable e) {
            // 部分 ROM 屏蔽了直接申请：退回系统电池设置页
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
                Toast.makeText(requireContext(),
                        "请手动到系统设置 → 电池 → 应用耗电管理里放行 DSHA", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 脚本层增量更新：先 dry-run 把「会改哪些文件」摊给用户看，确认后才下载。
     *
     *  为什么不做自动静默更新：这是一条远程代码通道，而清单目前还没有签名 ——
     *  仓库万一被攻破，带正确 sha256 的恶意脚本一样能通过校验。手动触发 +
     *  展示变更清单 + 只动脚本，这三条约束是签名做好之前的替代品。 */
    private void bindRuntimeUpdate(View view) {
        Button btn = view == null ? null : view.findViewById(R.id.config_runtime_update);
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "正在检查脚本更新…", Toast.LENGTH_SHORT).show();
            final android.content.Context appCtx = requireContext().getApplicationContext();
            new Thread(() -> {
                RuntimeUpdater.Result probe =
                        RuntimeUpdater.checkAndApply(appCtx, c, true);   // dryRun
                // 探测要联网，回来时用户可能已经离开配置页。requireActivity() /
                // requireContext() 这时都会抛 IllegalStateException。
                android.app.Activity act = getActivity();
                if (act == null || !isAdded()) return;
                act.runOnUiThread(() -> {
                    if (probe.updated == 0) {
                        Toast.makeText(appCtx, probe.message, Toast.LENGTH_LONG).show();
                        refreshRuntimeStatus();
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(probe.message).append("\n\n将更新：\n");
                    int n = 0;
                    for (String f : probe.changed) {
                        if (n++ >= 12) {
                            sb.append("  … 还有 ").append(probe.changed.size() - 12).append(" 个\n");
                            break;
                        }
                        sb.append("  ").append(f).append('\n');
                    }
                    sb.append("\n只更新脚本，不动 rootfs 与应用本体；");
                    sb.append("每个文件都会校验 sha256，不符就保留原版本。");
                    // 弹窗必须用 Activity context；这一刻拿不到就退回 Toast，
                    // 不能让「有更新」这件事被静默吞掉。
                    android.app.Activity a2 = getActivity();
                    if (a2 == null || a2.isFinishing() || !isAdded()) {
                        Toast.makeText(appCtx, probe.message, Toast.LENGTH_LONG).show();
                        return;
                    }
                    new androidx.appcompat.app.AlertDialog.Builder(a2)
                            .setTitle("脚本更新")
                            .setMessage(sb.toString())
                            .setPositiveButton("下载并应用", (d, w) -> doRuntimeUpdate(appCtx))
                            .setNegativeButton("取消", null)
                            .show();
                });
            }, "dsha-runtime-probe").start();
        });
    }



    /** 外观选择。选完立刻 recreate —— 已经创建的窗口不会自己换主题，
     *  而颜色槽是主题属性，只有重建才会重新解析。
     *
     *  <p>已被 {@link #showAppearanceDialog()} 取代（那个还带背景图与三个滑块），
     *  保留是因为它是纯主题切换的最小实现，将来若要做「快速切换」入口可以直接用。 */
    @SuppressWarnings("unused")
    private void showThemeDialogSimple() {
        int cur = DshaTheme.currentIndex(requireContext());
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("外观")
                .setSingleChoiceItems(DshaTheme.LABELS, cur, (d, which) -> {
                    if (which == DshaTheme.currentIndex(requireContext())) {
                        d.dismiss();
                        return;                     // 选的就是当前那套，别白重建一次
                    }
                    DshaTheme.set(requireContext(), DshaTheme.VALUES[which]);
                    d.dismiss();
                    requireActivity().recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 选背景图的请求码。放在这里而不是常量类：只有这一处用。 */
    private static final int REQ_PICK_BG = 4301;

    /** 外观面板：主题 + 背景图 + 三个滑块（卡片不透明度 / 背景压暗 / 背景模糊）。
     *
     *  <p>透明度和压暗<b>拖动时就地预览</b> —— 这几个值说不清好坏，只能看着调，
     *  让用户"应用→看→再打开→再调"是最糟的交互。预览作用在 Activity 的 decorView 上，
     *  所以对话框背后的整个界面会跟着变；点取消会还原成落盘的值。
     *
     *  <p>模糊例外：它做在 Bitmap 上（见 {@link DshaBackground}），必须重建界面才生效，
     *  UI 上已经写明这一点。 */
    private void showAppearanceDialog() {
        final android.content.Context ctx = requireContext();
        View v = android.view.LayoutInflater.from(ctx).inflate(R.layout.dialog_appearance, null);

        final int[] ids = {R.id.ap_theme_default, R.id.ap_theme_sakura};
        final android.widget.RadioGroup group = v.findViewById(R.id.ap_theme_group);
        group.check(ids[DshaTheme.currentIndex(ctx)]);

        // 玻璃是叠加开关，不是第三套配色 —— 这样它能配任一配色，
        // 而配色各自跟随系统深浅。
        final android.widget.CheckBox glass = v.findViewById(R.id.ap_glass);
        glass.setChecked(DshaGlass.enabled(ctx));

        final TextView bgState = v.findViewById(R.id.ap_bg_state);
        refreshBgState(bgState);
        v.findViewById(R.id.ap_bg_pick).setOnClickListener(x -> pickBackground());
        v.findViewById(R.id.ap_bg_clear).setOnClickListener(x -> {
            DshaBackground.clear(ctx);
            refreshBgState(bgState);
            Toast.makeText(ctx, "已清除背景图，界面重建后生效", Toast.LENGTH_SHORT).show();
        });

        final android.widget.SeekBar alpha = v.findViewById(R.id.ap_alpha);
        final android.widget.SeekBar dim = v.findViewById(R.id.ap_dim);
        final android.widget.SeekBar blur = v.findViewById(R.id.ap_blur);
        final TextView alphaLabel = v.findViewById(R.id.ap_alpha_label);
        final TextView dimLabel = v.findViewById(R.id.ap_dim_label);
        final TextView blurLabel = v.findViewById(R.id.ap_blur_label);
        alpha.setProgress(DshaGlass.alpha(ctx));
        dim.setProgress(DshaBackground.dim(ctx));
        blur.setProgress(DshaBackground.blurPct(ctx));
        alphaLabel.setText("卡片不透明度 " + alpha.getProgress() + "%");
        dimLabel.setText("背景压暗 " + dim.getProgress() + "%");
        blurLabel.setText("背景模糊 " + blur.getProgress() + "%");

        final android.widget.SeekBar ovl = v.findViewById(R.id.ap_overlay);
        final android.widget.SeekBar rad = v.findViewById(R.id.ap_radius);
        final android.widget.SeekBar corner = v.findViewById(R.id.ap_corner);
        final android.widget.CheckBox noise = v.findViewById(R.id.ap_noise);
        final TextView ovlLabel = v.findViewById(R.id.ap_overlay_label);
        final TextView radLabel = v.findViewById(R.id.ap_radius_label);
        final TextView cornerLabel = v.findViewById(R.id.ap_corner_label);
        ovl.setProgress(DshaGlass.overlayPct(ctx));
        rad.setProgress(DshaGlass.radius(ctx));
        noise.setChecked(DshaGlass.noise(ctx));
        int cdp = DshaGlass.cornerDp(ctx);
        if (cdp < 0) {
            // 没设过 → 滑块停在布局里的原值上，这样用户一拖就是相对现状微调，
            // 而不是从 0 开始重新找。
            cdp = (int) (ctx.getResources().getDimension(R.dimen.radius_card)
                    / ctx.getResources().getDisplayMetrics().density);
        }
        corner.setProgress(cdp);
        ovlLabel.setText("玻璃浓度 " + ovl.getProgress() + "%");
        radLabel.setText("模糊强度 " + rad.getProgress());
        cornerLabel.setText("圆角 " + corner.getProgress() + "dp");

        final View decor = requireActivity().getWindow().getDecorView();
        alpha.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            alphaLabel.setText("卡片不透明度 " + Math.max(20, p) + "%");
            DshaGlass.preview(decor, p);
        }));
        dim.setOnSeekBarChangeListener(new SimpleSeek(p -> dimLabel.setText("背景压暗 " + p + "%")));
        blur.setOnSeekBarChangeListener(new SimpleSeek(p -> blurLabel.setText("背景模糊 " + p + "%")));
        // 玻璃浓度与模糊强度都能运行时改（BlurView 的 setter 支持），所以直接预览；
        // 噪点只在 setupWith 时能定，改了得重建界面。
        ovl.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            ovlLabel.setText("玻璃浓度 " + Math.max(20, p) + "%");
            DshaGlass.previewBlur(decor, rad.getProgress(), p);
        }));
        rad.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            radLabel.setText("模糊强度 " + Math.max(4, p));
            DshaGlass.previewBlur(decor, p, ovl.getProgress());
        }));
        corner.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            cornerLabel.setText("圆角 " + p + "dp");
            DshaGlass.previewCorner(decor, p);
        }));

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("外观")
                .setView(v)
                .setPositiveButton("应用", (d, w) -> {
                    DshaGlass.setAlpha(ctx, alpha.getProgress());
                    DshaGlass.setEnabled(ctx, glass.isChecked());
                    DshaGlass.setOverlayPct(ctx, ovl.getProgress());
                    DshaGlass.setRadius(ctx, rad.getProgress());
                    DshaGlass.setNoise(ctx, noise.isChecked());
                    DshaGlass.setCornerDp(ctx, corner.getProgress());
                    DshaBackground.setDim(ctx, dim.getProgress());
                    DshaBackground.setBlur(ctx, blur.getProgress());
                    int idx = 0;
                    for (int i = 0; i < ids.length; i++) {
                        if (group.getCheckedRadioButtonId() == ids[i]) idx = i;
                    }
                    DshaTheme.set(ctx, DshaTheme.VALUES[idx]);
                    requireActivity().recreate();
                })
                .setNegativeButton("取消", (d, w) -> DshaGlass.apply(decor))
                .setOnCancelListener(d -> DshaGlass.apply(decor))
                .show();
    }

    private void refreshBgState(TextView tv) {
        if (tv == null) return;
        android.content.Context ctx = requireContext();
        tv.setText(DshaBackground.exists(ctx)
                ? "已设置 · " + (DshaBackground.file(ctx).length() / 1024) + " KB"
                : "未设置（没有背景图时，玻璃外观只能透出主题自带的渐变）");
    }

    private void pickBackground() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_PICK_BG);
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "打不开图片选择器：" + t, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_BG) return;
        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        // 复制进私有目录（不留 URI）：见 DshaBackground 的说明。
        String err = DshaBackground.saveFrom(requireContext(), data.getData());
        if (err != null) {
            Toast.makeText(requireContext(), "背景设置失败：" + err, Toast.LENGTH_LONG).show();
            return;
        }
        // 有了背景图，卡片还是全不透明的话什么都看不出来 —— 首次设图时给一个能看出效果的值。
        if (DshaGlass.alpha(requireContext()) >= 100) {
            DshaGlass.setAlpha(requireContext(), 72);
        }
        Toast.makeText(requireContext(), "背景已设置", Toast.LENGTH_SHORT).show();
        requireActivity().recreate();
    }

    /** SeekBar 三个回调里只关心一个，剩下两个每次都要写空实现 —— 收一下。 */
    private static final class SimpleSeek
            implements android.widget.SeekBar.OnSeekBarChangeListener {
        interface OnChange {
            void accept(int progress);
        }

        private final OnChange fn;

        SimpleSeek(OnChange fn) {
            this.fn = fn;
        }

        @Override
        public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
            fn.accept(p);
        }

        @Override
        public void onStartTrackingTouch(android.widget.SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(android.widget.SeekBar s) {
        }
    }

    /** 开发者直连开关。即时生效、不等「保存配置」—— 它排在保存按钮下面那一组，
     *  那一组的约定就是点一下就生效。
     *
     *  <p>开着的时候，已授权的 ADB 可以通过 {@link DevBridgeProvider} 直接跑容器命令。
     *  这是提权（adb 本来读不了私有目录，也没有容器内的 root），所以默认关、提示写明白、
     *  每次调用记活动日志。 */
    private void bindDevBridgeSwitch(View view) {
        android.widget.CheckBox cb = view.findViewById(R.id.config_dev_bridge);
        if (cb == null) return;
        final android.content.SharedPreferences sp = requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
        cb.setChecked(sp.getBoolean(DevBridgeProvider.KEY_ENABLED, false));
        cb.setOnCheckedChangeListener((v, on) -> {
            sp.edit().putBoolean(DevBridgeProvider.KEY_ENABLED, on).apply();
            Toast.makeText(requireContext(), on
                    ? "已开启 —— 已授权的 ADB 现在能跑容器命令，排查完记得关掉"
                    : "已关闭开发者直连", Toast.LENGTH_SHORT).show();
        });
    }

    /** 悬浮条的外观与行为。做成独立对话框而不是往配置页里塞七个控件：
     *  这些项只有开了悬浮条的人才关心，摊在主页面上是给所有人添噪声。 */
    private void showOverlayStyleDialog() {
        final android.content.Context app = requireContext().getApplicationContext();
        final android.content.SharedPreferences sp = requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);

        android.widget.LinearLayout box = new android.widget.LinearLayout(requireContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dpx(16);
        box.setPadding(pad, pad, pad, 0);

        // 底色不做取色器：悬浮条只需要「在任何壁纸上都读得清」，几个深色预设够用
        box.addView(sectionLabel("底色"));
        final int[] pickedBg = {sp.getInt(OverlayController.K_BG, 0)};
        android.widget.LinearLayout swatches = new android.widget.LinearLayout(requireContext());
        swatches.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        final TextView[] cells = new TextView[OverlayController.BG_PRESETS.length];
        for (int i = 0; i < OverlayController.BG_PRESETS.length; i++) {
            final int idx = i;
            TextView cell = new TextView(requireContext());
            cell.setText(OverlayController.BG_NAMES[i]);
            cell.setTextColor(0xFFFFFFFF);
            cell.setTextSize(11f);
            cell.setGravity(android.view.Gravity.CENTER);
            cell.setPadding(dpx(6), dpx(10), dpx(6), dpx(10));
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(0,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
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

        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
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
    private android.widget.SeekBar slider(android.widget.LinearLayout parent, String title,
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

    private void doRuntimeUpdate(final android.content.Context appCtx) {
        Toast.makeText(requireContext(), "下载中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            RuntimeUpdater.Result r = RuntimeUpdater.checkAndApply(appCtx, c, false);
            // 这里是真正的下载，耗时更长，用户离开页面的概率更高。
            android.app.Activity act = getActivity();
            if (act == null || !isAdded()) {
                // 结果不能悄悄丢掉：更新已经落盘了，至少让用户看到一次
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        Toast.makeText(appCtx, r.message, Toast.LENGTH_LONG).show());
                return;
            }
            act.runOnUiThread(() -> {
                android.app.Activity a2 = getActivity();
                if (a2 == null || a2.isFinishing() || !isAdded()) {
                    Toast.makeText(appCtx, r.message, Toast.LENGTH_LONG).show();
                    return;
                }
                new androidx.appcompat.app.AlertDialog.Builder(a2)
                        .setTitle(r.ok ? "更新完成" : "部分失败")
                        .setMessage(r.message)
                        .setPositiveButton("知道了", null)
                        .show();
                refreshRuntimeStatus();
            });
        }, "dsha-runtime-apply").start();
    }

    private void refreshRuntimeStatus() {
        View v = getView();
        TextView tv = v == null ? null : v.findViewById(R.id.config_runtime_status);
        if (tv == null) return;
        int n = RuntimeUpdater.overlayCount(requireContext());
        if (n <= 0) {
            tv.setTextColor(requireContext().getColor(R.color.text_secondary));
            tv.setText("○ 正在用应用内置的脚本版本");
            tv.setOnClickListener(null);
            return;
        }
        tv.setTextColor(requireContext().getColor(R.color.ok));
        tv.setText("● 已用更新覆盖 " + n + " 个脚本（点这行可恢复内置版本）");
        tv.setOnClickListener(x -> new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("恢复内置脚本")
                .setMessage("删掉下载的覆盖文件，回到应用自带的版本。"
                        + "更新后出现异常时用这个退回。")
                .setPositiveButton("恢复", (d, w) -> {
                    boolean ok = RuntimeUpdater.resetOverlay(requireContext());
                    Toast.makeText(requireContext(),
                            ok ? "已恢复内置版本，重启 Web 生效" : "恢复失败，请重启 App 再试",
                            Toast.LENGTH_LONG).show();
                    refreshRuntimeStatus();
                })
                .setNegativeButton("取消", null)
                .show());
    }

    /** 「屏幕操作权限」= 无障碍服务。它是免 ADB / 免 Shizuku 操作手机的唯一现实路径：
     *  绝大多数用户既没开无线调试也没装 Shizuku，而无障碍一次授权就长期可用。 */
    private void bindA11yEntry(View view) {
        Button btn = view == null ? null : view.findViewById(R.id.config_a11y);
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            boolean on = DshaAccessibilityService.enabled(requireContext());
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(on ? "屏幕操作权限（已开启）" : "开启屏幕操作权限")
                    .setMessage(on
                            ? "AI 现在可以读屏、点按、输入、按键、滑动、截屏，也能自动读取"
                              + "无线调试的配对码。\n\n要关闭的话，在系统设置 → 无障碍 → "
                              + "「DSHA 配对助手」里关掉即可。"
                            : "开启后 AI 不需要 ADB 或 Shizuku 就能操作手机：读屏、点按、输入、"
                              + "按键、滑动、截屏；配对无线调试时也能自动读出配对码，省掉手抄。\n\n"
                              + "隐私：DSHA 不做任何后台屏幕记录 —— 读屏只发生在 AI 明确请求的"
                              + "那一刻，结果直接回到对话里，不落盘也不上传；配对码只在你点过"
                              + "「自动读配对码」之后的两分钟内读取。\n\n"
                              + "在接下来的列表里找到「DSHA 配对助手」并打开。")
                    .setPositiveButton(on ? "去系统设置" : "去开启", (d, w) -> {
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Throwable t) {
                            Toast.makeText(requireContext(), "打不开无障碍设置：" + t.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    private void refreshA11yStatus() {
        View v = getView();
        TextView tv = v == null ? null : v.findViewById(R.id.config_a11y_status);
        if (tv == null) return;
        // 三态：读不到设置时说「读不到」，别把不确定说成未开启 ——
        // 各家 ROM 的 ENABLED_ACCESSIBILITY_SERVICES 格式不一，解析失败很常见，
        // 报「未开启」会让已经开好的用户反复去开
        String st = DshaAccessibilityService.enabledState(requireContext());
        if ("YES".equals(st)) {
            tv.setTextColor(requireContext().getColor(R.color.ok));
            tv.setText("● 已开启：AI 可读屏/点按/输入/截屏，配对码也能自动读");
        } else if ("NO".equals(st)) {
            tv.setTextColor(requireContext().getColor(R.color.text_secondary));
            tv.setText("○ 未开启：AI 只能靠 ADB 或 Shizuku 操作手机（多数人没配）");
        } else {
            tv.setTextColor(requireContext().getColor(R.color.warn));
            tv.setText("？读不到无障碍设置（系统没返回）——如果你已经开过，忽略这行即可；"
                    + "让 AI 调一次屏幕操作就能确认");
        }
    }

    /** 危险命令守卫的完整性提示（吸收上游 PR#24）。
     *  bash 工具补丁靠 sed 匹配 dsh 已构建的代码，而 dsh 走「始终最新 RC」自动升级，
     *  上游一改代码这层保险就静默降级 —— 必须让用户看得见，
     *  否则会以为确认仍是「PATH 包装器 + 函数级守卫」双保险。 */
    private void showGuardStatus() {
        View v = getView();
        TextView tv = v == null ? null : v.findViewById(R.id.config_guard_status);
        if (tv == null || c == null) return;
        String st = c.guardPatchState();
        if ("unknown".equals(st)) {
            tv.setVisibility(View.GONE); // 还没启动过 Web，无从判断，不必吓人
            return;
        }
        tv.setVisibility(View.VISIBLE);
        if ("ok".equals(st)) {
            tv.setTextColor(requireContext().getColor(R.color.ok));
            tv.setText("● 守卫完整：PATH 包装器 + bash 工具补丁");
        } else {
            tv.setTextColor(requireContext().getColor(R.color.warn));
            tv.setText("○ bash 工具补丁未生效（dsh 可能已升级改动代码）\n"
                    + "危险命令仍会被 PATH 包装器拦截并弹确认，只是少一层兜底。\n"
                    + "详情见容器内 /root/dsh-guard-patch.log");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        startAdbStatusPolling();
        refreshBatteryOptState(getView());
        showGuardStatus(); // 上次启动 Web 时补丁可能已失配，切回本页就刷新
        refreshA11yStatus(); // 用户可能刚从系统设置里开/关了无障碍
        refreshRuntimeStatus();
        // 回到配置页顺手催一次 ADB 探测：用户往往就是来看连上没有的
        DeviceBridgeService.kickNow(requireContext(), "打开配置页");
    }

    /** 位置权限当前是否已授予（粗略或精确任一即可 —— DeviceSense 两者都能用）。 */
    private boolean locationGranted() {
        try {
            android.content.Context ctx = requireContext();
            return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    || ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 101 || capLocationCb == null || !isAdded()) return;
        // 用户在系统弹窗里选了「不允许」：开关必须跟着退回去。
        // 只看 grantResults 不够 —— 用户可能只给了「大致位置」，那也算能用。
        if (locationGranted()) {
            Toast.makeText(requireContext(), "定位权限已授予，记得点「保存配置」", Toast.LENGTH_SHORT).show();
        } else {
            capLocationCb.setChecked(false);
            Toast.makeText(requireContext(), "未授予定位权限，该能力保持关闭", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopAdbStatusPolling();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopAdbStatusPolling();
    }

    /** root shell 授权标记：授权 → 写 /root/.dsh/allow-root-shell（adb-shell.py 检查）；
     *  取消 → 删标记。rootfs 未就绪时静默。 */
    private void applyRootShellMark() {
        try {
            final boolean allow = c.isRootShellAllowed();
            new Thread(() -> {
                try {
                    if (c.getProot().isInstalled()) {
                        if (allow) {
                            c.getProot().execAndRead(
                                    "mkdir -p /root/.dsh && touch /root/.dsh/allow-root-shell && echo ok");
                        } else {
                            c.getProot().execAndRead("rm -f /root/.dsh/allow-root-shell && echo ok");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "root-shell-mark").start();
        } catch (Throwable ignored) {
        }
    }

    /** 守卫开关标记：confirm_shell=true → 写 /root/.dsh/confirm-shell-enabled
     *  （adb-shell 包装据此对设备命令弹确认）；false → 删标记（只口头报备）。 */
    private void applyConfirmShellMark() {
        try {
            final boolean enabled = confirmShellCb != null && confirmShellCb.isChecked();
            new Thread(() -> {
                try {
                    if (c.getProot().isInstalled()) {
                        if (enabled) {
                            c.getProot().execAndRead(
                                    "mkdir -p /root/.dsh && touch /root/.dsh/confirm-shell-enabled && echo ok");
                        } else {
                            c.getProot().execAndRead("rm -f /root/.dsh/confirm-shell-enabled && echo ok");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "confirm-shell-mark").start();
        } catch (Throwable ignored) {
        }
    }

    /** 构建「输入配对码」通知卡（RemoteInput，参考 Shizuku 无线配对交互）：
     *  通知栏直接输入 6 位码 → 点「输码配对」→ AdbPairReceiver 后台完成配对 → 结果推回。 */
    private void showAdbPairNotification() {
        try {
            Context ctx = requireContext();
            // Android 13+：无通知权限直接 notify 会抛 SecurityException → 先引导授权
            if (Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(ctx, "需要通知权限才能显示配对卡片，请在系统弹窗中允许", Toast.LENGTH_LONG).show();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
                return;
            }
            String CH = "dsh_adbpair_channel";
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "ADB 无线配对",
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            Intent intent = new Intent(ctx, AdbPairReceiver.class).setAction(AdbPairReceiver.ACTION_PAIR);
            RemoteInput ri = new RemoteInput.Builder(AdbPairReceiver.EXTRA_CODE)
                    .setLabel("6 位配对码")
                    .build();
            // RemoteInput 必须用 FLAG_MUTABLE：IMMUTABLE 的 PendingIntent 收不到输入内容
            PendingIntent pi = PendingIntent.getBroadcast(
                    ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            NotificationCompat.Action action = new NotificationCompat.Action.Builder(
                    R.drawable.ic_launch, "输码配对", pi)
                    .addRemoteInput(ri)
                    .build();
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH)
                    .setSmallIcon(R.drawable.ic_launch)
                    .setContentTitle("🔐 ADB 无线配对")
                    .setContentText("请在手机「开发者选项→无线调试」点「使用配对码配对设备」，把 6 位码填到下面")
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText("1. 手机「开发者选项 → 无线调试」→「使用配对码配对设备」\n"
                                    + "2. 记下 6 位配对码\n"
                                    + "3. 点下方「输码配对」，在通知栏直接输入配对码"))
                    .addAction(action)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);
            nm.notify(Constants.NOTIF_ADB_PAIR_CARD, b.build());
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "通知创建失败（可先到系统设置允许通知权限）：" + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void setupCommonControls() {
        loadConfig();
        if (rootShellCb != null) rootShellCb.setChecked(c.isRootShellAllowed());
        if (backupKeyCb != null) {
            // 默认开：这是别人为「离线包用户恢复后 key 为空」加的功能，不能默默关掉
            backupKeyCb.setChecked(requireContext()
                    .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getBoolean("backup_include_key", true));
        }

        saveBtn.setOnClickListener(v -> {
            c.setApiKey(apiKeyEdit.getText().toString().trim());
            c.setPort(portEdit.getText().toString().trim());
            requireContext().getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("confirm_shell", confirmShellCb.isChecked())
                    .putBoolean("check_update", checkUpdateCb.isChecked())
                    .putBoolean("desktop_mode", desktopModeCb.isChecked())
                    .putBoolean("backup_include_key",
                            backupKeyCb == null || backupKeyCb.isChecked())
                    .putBoolean("lan_mode", lanModeCb.isChecked())
                    .putBoolean("overlay_stream",
                            overlayStreamCb != null && overlayStreamCb.isChecked())
                    // 传感器/手电没有隐私风险，缺控件时按开启算（与 DeviceSense 的默认一致）
                    .putBoolean(DeviceSense.K_SENSORS, capSensorsCb == null || capSensorsCb.isChecked())
                    // 位置反过来：勾了但系统权限没到手就不算开，宁可让用户再点一次，
                    // 也别在设置里留一条「已开启」的假记录
                    .putBoolean(DeviceSense.K_LOCATION,
                            capLocationCb != null && capLocationCb.isChecked() && locationGranted())
                    .putBoolean("use_rc6", rc6Cb.isChecked())
                    .putBoolean("gecko_core", geckoCb != null && geckoCb.isChecked())
                    // 运行时以字符串存，将来加第三种后端不必改存储格式
                    .putString("container_runtime",
                            prorootCb != null && prorootCb.isChecked() ? "proroot" : "proot")
                    // 换运行时后连续失败计数要归零，否则会带着旧账立刻触发强制回退
                    .putInt("proroot_fail_streak", 0)
                    .putBoolean(DeviceBridgeService.PREF_ADB, adbCb != null && adbCb.isChecked())
                    .putInt("auto_backup_launches", parseAutoBackup())
                    .apply();
            c.setRootShellAllowed(rootShellCb != null && rootShellCb.isChecked());
            applyRootShellMark();
            applyConfirmShellMark();
            DeviceBridgeService.apply(requireContext());
            // 能力开关变了就重写清单 —— agent 的能力说明按它拼。
            boolean capsChanged = c.writeCapsFile();
            Toast.makeText(requireContext(),
                    (adbCb != null && adbCb.isChecked()) ? "配置已保存（ADB 已开）" : "配置已保存（ADB 已关）",
                    Toast.LENGTH_SHORT).show();
            if (capsChanged) {
                // 提示词是插件在 dsh 启动时读的，光保存不重启，当前会话里 agent 手上还是旧的那份。
                // 这一句不说，用户会以为关掉位置权限之后 agent 立刻就不知道位置接口了。
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("权限变动已记下")
                        .setMessage("agent 的能力说明会跟着这次改动更新，但它是在 dsh 启动时读的 —— "
                                + "要到下次「重启 Web」之后，agent 才知道现在什么能用、什么不能用。\n\n"
                                + "当前会话里它手上还是旧的那份。")
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });

        // 关于入口：点版本号弹「关于」对话框（GitHub / QQ 群）
        // 版本号动态显示（与应用信息一致）
        if (repoLink != null) {
            try {
                String v = requireContext().getPackageManager()
                        .getPackageInfo(requireContext().getPackageName(), 0).versionName;
                repoLink.setText("DSHA v" + v);
            } catch (Exception ignored) {
            }
            repoLink.setOnClickListener(v -> AboutDialog.show(requireContext()));
        }
    }

    /** 解析"每启动 N 次自动备份"输入（0=关闭，非法回退 5） */
    private int parseAutoBackup() {
        try {
            int n = Integer.parseInt(autoBackupEdit.getText().toString().trim());
            return Math.max(0, Math.min(n, 999));
        } catch (Exception e) {
            return 5;
        }
    }

    private void loadConfig() {
        apiKeyEdit.setText(c.getApiKey());
        portEdit.setText(c.getPort());
        confirmShellCb.setChecked(requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("confirm_shell", true));
        checkUpdateCb.setChecked(requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("check_update", true));
        desktopModeCb.setChecked(requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("desktop_mode", false));
        rc6Cb.setChecked(requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("use_rc6", true));
        lanModeCb.setChecked(requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("lan_mode", false));
        if (overlayStreamCb != null) {
            // 权限可能被用户在系统设置里撤掉 —— 那时开关也该跟着回到关闭，
            // 否则界面显示「开着」而实际什么都不显示，又是一次静默失效
            boolean want = requireContext()
                    .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getBoolean("overlay_stream", false);
            overlayStreamCb.setChecked(want && OverlayController.permitted(requireContext()));
        }
        if (capSensorsCb != null) {
            capSensorsCb.setChecked(requireContext()
                    .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getBoolean(DeviceSense.K_SENSORS, true));
        }
        if (capLocationCb != null) {
            // 同悬浮窗：系统权限可能在设置里被撤掉，撤了就跟着回到关闭
            boolean wantLoc = requireContext()
                    .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getBoolean(DeviceSense.K_LOCATION, false);
            capLocationCb.setChecked(wantLoc && locationGranted());
        }
        if (prorootCb != null) {
            prorootCb.setChecked("proroot".equals(requireContext()
                    .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getString("container_runtime", "proroot")));
        }
        if (geckoCb != null) {
            geckoCb.setChecked(requireContext()
                    .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getBoolean("gecko_core", false));
        }
        if (adbCb != null) {
            adbCb.setChecked(DeviceBridgeService.isAdbEnabled(requireContext()));
        }
        if (autoBackupEdit != null) {
            int n = requireContext()
                    .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getInt("auto_backup_launches", 5);
            autoBackupEdit.setText(String.valueOf(n));
        }
        if (repoLink != null) {
            try {
                String v = requireContext().getPackageManager()
                        .getPackageInfo(requireContext().getPackageName(), 0).versionName;
                repoLink.setText("DSHA v" + v);
            } catch (Exception ignored) {
            }
            repoLink.setOnClickListener(v -> AboutDialog.show(requireContext()));
        }
    }
}
