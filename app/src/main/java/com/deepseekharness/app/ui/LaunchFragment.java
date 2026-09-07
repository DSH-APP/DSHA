package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.LanProxyService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;

/**
 * 启动页：启动 / 进入 / 停止 dsh Web，显示运行状态、鉴权链接与局域网访问地址。
 */
public class LaunchFragment extends Fragment {

    private HarnessController controller;
    private TextView lanAddrText;
    private TextView launchLog;
    /** 启动按钮当前是否处于「进入」态（鉴权链接已就绪）。 */
    private boolean webReady;
    /** 主线程单飞；成功打开后保持占用，直到页面重新可见。 */
    private boolean enteringWeb;
    private long enterRequest;
    /** 本次启动开始时刻（显示耗时用）。 */
    private long startAtMs;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshState = new Runnable() {
        @Override public void run() {
            refreshRunState();
            refreshLanAddr();
            ui.postDelayed(this, 1000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_launch, container, false);

        controller = HarnessController.get(requireContext());
        final Activity activity = requireActivity();
        TextView status = v.findViewById(R.id.launch_status);
        Button start = v.findViewById(R.id.launch_start);
        Button restart = v.findViewById(R.id.launch_open);
        Button stop = v.findViewById(R.id.launch_stop);
        lanAddrText = v.findViewById(R.id.lan_addr);
        launchLog = v.findViewById(R.id.launch_log);

        restart.setText("重启");
        v.findViewById(R.id.launch_recovery).setOnClickListener(x -> showRecovery());
        v.findViewById(R.id.launch_safe).setOnClickListener(x -> new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("安全启动 Web？")
                .setMessage("暂时禁用第三方插件后启动，保留插件文件、会话和配置。可在插件管理中逐个启用或恢复之前的状态。")
                .setNegativeButton("取消", null).setPositiveButton("安全启动", (dialog, which) -> doStart(activity, status, start, true)).show());

        // 启动按钮：未就绪时是「启动」；鉴权链接就绪后自动变为「进入」，点击进 WebUI。
        start.setOnClickListener(x -> {
            if (webReady || !webEntryUrl().isEmpty()) {
                enterWeb();
                return;
            }
            doStart(activity, status, start);
        });

        restart.setOnClickListener(x -> {
            // startWeb 本身串行执行「清旧进程 → 启动」，无需拆成两次请求。
            doStart(activity, status, start);
        });

        stop.setOnClickListener(x -> {
            invalidateWebEntry();
            controller.stopWeb(msg -> {
                long generation = controller.getWebGeneration();
                activity.runOnUiThread(() -> {
                    if (getView() != v || generation != controller.getWebGeneration()) return;
                    status.setText(msg);
                    refreshRunState();
                    refreshLanAddr();
                });
            });
            webReady = false;
            start.setText("启动");
            status.setText("停止中…");
            refreshLanAddr();
            refreshRunState();
        });

        return v;
    }

    /** 启动 dsh：记录启动时刻，鉴权链接就绪后把「启动」变「进入」并输出 URL 到日志。 */
    private void doStart(Activity activity, TextView status, Button start) {
        doStart(activity, status, start, false);
    }

    private void doStart(Activity activity, TextView status, Button start, boolean safeMode) {
        if (controller.isStarting() || controller.isStopping()) return;
        final View root = getView();
        if (root == null || activity.isFinishing() || activity.isDestroyed()) return;
        invalidateWebEntry();
        startAtMs = System.currentTimeMillis();
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        status.setText("启动中…（" + time + "）");
        start.setText("启动");
        webReady = false;
        appendLog("—— 启动 " + time + " ——");
        java.util.function.Consumer<String> startStatus = msg -> {
            long generation = controller.getWebGeneration();
            activity.runOnUiThread(() -> {
                if (getView() != root || generation != controller.getWebGeneration()) return;
                status.setText(msg);
                if (!controller.getWebAuthUrl().isEmpty() && !webReady) {
                    long sec = (System.currentTimeMillis() - startAtMs) / 1000;
                    appendLog("启动成功，耗时 " + sec + "s");
                    appendLog("本机打开：" + controller.getWebAuthUrl()
                            + "　（仅本机；其它设备请用「局域网地址」那条）");
                }
                refreshRunState();
                refreshLanAddr();
            });
        };
        boolean accepted = safeMode ? controller.startWebSafely(startStatus) : controller.startWeb(startStatus);
        if (accepted && safeMode) PluginFragment.invalidateInstalledState();
        refreshRunState();
        if (!accepted) return;
        // 前台保活服务：dsh 后台常驻 + 看门狗自动重启（退到桌面/锁屏不被杀）
        try {
            Intent svc = new Intent(requireContext(), com.deepseekharness.app.HarnessService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                requireContext().startForegroundService(svc);
            } else {
                requireContext().startService(svc);
            }
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "拉起保活服务失败: " + t.getMessage());
        }
    }

    /** 打开 WebPreviewActivity 进入 dsh WebUI。 */
    private void enterWeb() {
        final View root = getView();
        final Activity activity = getActivity();
        if (enteringWeb || root == null || activity == null || !isResumed()
                || activity.isFinishing() || activity.isDestroyed()) return;
        String url = webEntryUrl();
        if (url.isEmpty()) {
            if (getView() != null) {
                ((TextView) getView().findViewById(R.id.launch_status))
                        .setText("先点「启动」，等鉴权链接就绪后再进入");
            }
            return;
        }
        final long generation = webEntryGeneration();
        final long request = ++enterRequest;
        enteringWeb = true;
        ((TextView) root.findViewById(R.id.launch_status)).setText("正在验证 Web 访问权限…");
        refreshRunState();
        try {
            new Thread(() -> {
                String cookie = null;
                String failure = null;
                try {
                    cookie = exchangeWebEntryCookie();
                    if (cookie == null || cookie.isEmpty()) failure = "未取得 Web 鉴权，请稍后点「进入」重试";
                } catch (Exception error) {
                    failure = "Web 鉴权失败，请点「进入」重试（" + error.getClass().getSimpleName() + "）";
                }
                final String authCookie = cookie;
                final String authFailure = failure;
                ui.post(() -> {
                    if (request != enterRequest || getView() != root || !isResumed()
                            || activity.isFinishing() || activity.isDestroyed()) return;
                    if (generation != webEntryGeneration() || !url.equals(webEntryUrl())) {
                        finishWebEntry(root, "Web 状态已变化，请等待就绪后重新进入");
                        return;
                    }
                    if (authFailure != null) { finishWebEntry(root, authFailure); return; }
                    try {
                        openWebEntry(activity, url, authCookie);
                        ((TextView) root.findViewById(R.id.launch_status)).setText("鉴权成功，正在打开 Web…");
                        refreshLanAddr();
                    } catch (RuntimeException error) {
                        finishWebEntry(root, "无法打开 Web 页面，请重试（" + error.getClass().getSimpleName() + "）");
                    }
                });
            }, "dsh-cookie").start();
        } catch (RuntimeException error) {
            finishWebEntry(root, "无法开始 Web 鉴权，请重试");
        }
    }

    // 同包调试自测可替换鉴权和 Activity 出口，验证连点/异常/销毁，不实际启动 Web。
    String webEntryUrl() { return controller.getWebAuthUrl(); }
    long webEntryGeneration() { return controller.getWebGeneration(); }
    String exchangeWebEntryCookie() { return controller.exchangeDshAuthCookie(); }
    void openWebEntry(Activity activity, String url, String cookie) {
        startActivity(WebPreviewActivity.intent(activity, url, cookie));
    }

    private void finishWebEntry(View root, String message) {
        enteringWeb = false;
        ((TextView) root.findViewById(R.id.launch_status)).setText(message);
        refreshRunState();
    }

    private void invalidateWebEntry() {
        enterRequest++;
        enteringWeb = false;
    }

    /** 往日志区追加一行（首行替换占位文本）。 */
    private void appendLog(String line) {
        if (launchLog == null || !isAdded()) return;
        String cur = launchLog.getText().toString();
        launchLog.setText("还没有日志。".equals(cur) ? line : cur + "\n" + line);
        if (getView() != null) {
            try {
                android.widget.ScrollView sv = getView().findViewById(R.id.launch_log_scroll);
                if (sv != null) sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        invalidateWebEntry();
        ui.removeCallbacks(refreshState);
        ui.post(refreshState);
    }

    @Override
    public void onPause() {
        if (enteringWeb && getView() != null) {
            ((TextView) getView().findViewById(R.id.launch_status)).setText("返回后可重新进入 Web");
        }
        invalidateWebEntry();
        ui.removeCallbacks(refreshState);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        invalidateWebEntry();
        ui.removeCallbacks(refreshState);
        lanAddrText = null;
        launchLog = null;
        super.onDestroyView();
    }

    /** 读取共享状态，不在主线程执行 proot/kill -0；重建页面也能跟随后台启停。 */
    private void refreshRunState() {
        try {
            View root = getView();
            if (root == null) return;
            TextView runState = root.findViewById(R.id.launch_run_state);
            Button start = root.findViewById(R.id.launch_start);
            if (runState == null) return;
            boolean starting = controller.isStarting();
            boolean stopping = controller.isStopping();
            boolean ready = !starting && !stopping && !webEntryUrl().isEmpty();
            runState.setText(enteringWeb ? "正在鉴权并打开 Web…" : controller.isRestartBlocked() ? "自动重启已暂停" : stopping ? "DSH 停止中…" : starting ? "DSH 启动中…"
                    : ready ? "DSH 已就绪，可进入" : controller.isUserStopped() ? "DSH 已停止" : "DSH 未就绪");
            Button recovery = root.findViewById(R.id.launch_recovery);
            int failures = controller.config().getWebFailures();
            recovery.setVisibility(failures > 0 ? View.VISIBLE : View.GONE);
            recovery.setText("失败 " + failures + "/3 · " + controller.config().getWebFailureStage() + " · 查看恢复选项");
            if (start != null) {
                webReady = ready;
                start.setText(enteringWeb ? "进入中…" : ready ? "进入" : "启动");
                start.setEnabled(!enteringWeb && !starting && !stopping);
            }
            Button restart = root.findViewById(R.id.launch_open);
            if (restart != null) restart.setEnabled(!starting && !stopping);
            Button stop = root.findViewById(R.id.launch_stop);
            if (stop != null) stop.setEnabled(!stopping);
        } catch (Throwable ignored) {
        }
    }

    private void showRecovery() {
        com.deepseekharness.app.core.ConfigStore config = controller.config();
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(controller.isRestartBlocked() ? "连续失败，已暂停自动重启" : "启动恢复")
                .setMessage("失败阶段：" + config.getWebFailureStage() + "\n" + config.getWebFailureReason()
                        + "\n\n可点启动重试，或用安全启动暂时禁用第三方插件。插件管理提供上一版回退；诊断页可检查并修复基础工具。")
                .setPositiveButton("查看诊断", (d, w) -> startActivity(new Intent(requireContext(), DiagnosticActivity.class)))
                .setNeutralButton("插件回退", (d, w) -> {
                    PluginFragment fragment = new PluginFragment();
                    Bundle args = new Bundle(); args.putBoolean("show_installed", true); fragment.setArguments(args);
                    getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, fragment)
                            .addToBackStack("recovery").commit();
                }).setNegativeButton("关闭", null).show();
    }

    /** LAN 开关开 + 代理已绑定 → 直接把完整局域网地址亮出来（点一下可复制）。 */
    private void refreshLanAddr() {
        if (lanAddrText == null || !isAdded()) return;
        boolean lan = requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.KEY_LAN_MODE, false);
        if (!lan) {
            lanAddrText.setVisibility(View.GONE);
            return;
        }
        boolean bound = LanProxyService.isBound();
        if (bound) {
            // 完整地址直接亮出来：另一台设备照着输入即可，不用再点开对话框复制
            String ip = HarnessController.getLanAddress();
            if (ip != null && !ip.isEmpty()) {
                final String addr = "http://" + ip + ":" + LanProxyService.LAN_PORT + "/?token="
                        + LanProxyService.getLanToken(requireContext());
                lanAddrText.setText("局域网地址（同 WiFi 的其它设备访问）：\n" + addr);
                lanAddrText.setOnClickListener(v -> copyAddr("局域网地址", addr));
            } else {
                lanAddrText.setText("局域网已开启，但还没拿到 WiFi 地址（连上 WiFi 再看）");
                lanAddrText.setOnClickListener(null);
            }
        } else {
            lanAddrText.setText("局域网代理正在等待本轮认证");
            lanAddrText.setOnClickListener(null);
        }
        lanAddrText.setVisibility(View.VISIBLE);
    }

    private void copyAddr(String label, String addr) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, addr));
                Toast.makeText(requireContext(), "已复制：" + addr, Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "复制失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
