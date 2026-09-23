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

    /** 通知点击进入时带上的参数：Web 就绪后自动打开会话。 */
    public static final String ARG_OPEN_WEB = "open_web";

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
    private long logRevision = -1;
    private String logUrl = "";
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 通知点击进来、待自动进入 Web 的意图；由既有每秒刷新循环消费，就绪即进。 */
    private boolean pendingAutoEnter;
    private final Runnable refreshState = new Runnable() {
        @Override public void run() {
            refreshRunState();
            refreshLanAddr();
            // 通知点击进来的自动进入：复用这个每秒循环等鉴权链接，不另开定时器。
            // 判据只认「鉴权链接已出现」——它就是进入 Web 的前提，也是按钮变「进入」的
            // 同一条件。不能拿 getWebAuthFailure() 判成败：那是最近一次鉴权结果消息，
            // 初始值与成功值都非 null（成功时是"鉴权成功"），用它当成功判据会永不进入。
            if (pendingAutoEnter && !webEntryUrl().isEmpty()) {
                pendingAutoEnter = false;
                enterWeb();
            }
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
        logRevision = -1;
        logUrl = "";
        v.findViewById(R.id.launch_download_logs).setOnClickListener(x -> startActivity(DiagnosticActivity.downloadLogs(requireContext())));

        v.findViewById(R.id.launch_models).setOnClickListener(x->startActivity(new Intent(requireContext(),ModelSetupActivity.class)));
        v.findViewById(R.id.launch_address).setOnClickListener(x->{
            String local=controller.getWebAuthUrl();
            String addresses=local.isEmpty()?com.deepseekharness.app.util.UiText.choose("启动后可查看访问地址。","Start DSH to view access addresses."):local;
            if(lanAddrText!=null&&lanAddrText.getVisibility()==View.VISIBLE)addresses+="\n\n"+lanAddrText.getText();
            var dialog=new DshaDialogBuilder(requireContext()).setTitle(R.string.ui2_address).setMessage(addresses).setNegativeButton(android.R.string.cancel,null);
            if(!local.isEmpty())dialog.setPositiveButton(com.deepseekharness.app.util.UiText.choose("复制本机地址","Copy local URL"),(d,w)->copyAddr("DSH",local));
            dialog.show();
        });
        restart.setText(R.string.ui2_restart);
        v.findViewById(R.id.launch_recovery).setOnClickListener(x -> showRecovery());
        v.findViewById(R.id.launch_safe).setOnClickListener(x -> new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                .setTitle(com.deepseekharness.app.util.UiText.text("安全启动 Web？"))
                .setMessage(com.deepseekharness.app.util.UiText.text("停止当前启动，只加载官方基础界面。原插件开关、会话、模型和配置保留；普通重启后回到原配置。安全界面不加载移动插件等扩展。"))
                .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null).setPositiveButton(com.deepseekharness.app.util.UiText.text("安全启动"), (dialog, which) -> doStart(activity, status, start, true)).show());

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

        // 通知点击进来：置待进入标记，由每秒刷新循环在鉴权链接就绪后自动进入。
        if (getArguments() != null && getArguments().getBoolean(ARG_OPEN_WEB, false)) {
            getArguments().remove(ARG_OPEN_WEB);
            pendingAutoEnter = true;
        }

        stop.setOnClickListener(x -> {
            invalidateWebEntry();
            controller.stopWeb(msg -> {
                long generation = controller.getWebGeneration();
                activity.runOnUiThread(() -> {
                    if (getView() != v || generation != controller.getWebGeneration()) return;
                    status.setText(com.deepseekharness.app.util.UiText.text(msg));
                    refreshRunState();
                    refreshLanAddr();
                });
            });
            webReady = false;
            start.setText(com.deepseekharness.app.util.UiText.text("启动"));
            status.setText(com.deepseekharness.app.util.UiText.text("停止中…"));
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
        if (!safeMode && (controller.isStarting() || controller.isStopping())) return;
        final View root = getView();
        if (root == null || activity.isFinishing() || activity.isDestroyed()) return;
        invalidateWebEntry();
        startAtMs = System.currentTimeMillis();
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        status.setText(com.deepseekharness.app.util.UiText.text("启动中…（") + time + com.deepseekharness.app.util.UiText.text("）"));
        start.setText(com.deepseekharness.app.util.UiText.text("启动"));
        webReady = false;
        java.util.function.Consumer<String> startStatus = msg -> {
            long generation = controller.getWebGeneration();
            activity.runOnUiThread(() -> {
                if (getView() != root || generation != controller.getWebGeneration()) return;
                status.setText(com.deepseekharness.app.util.UiText.text(msg));
                refreshRunState();
                refreshLanAddr();
            });
        };
        boolean accepted;
        if (safeMode) { controller.recoverWeb(true, null, startStatus); accepted = true; }
        else accepted = controller.startWeb(startStatus);
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
            android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("拉起保活服务失败: ") + t.getMessage());
        }
    }

    /**
     * 外部（通知点击）请求：下一次刷新周期自动进入 Web。
     *
     * <p>页面不可见时由 {@code onPause} 清掉，避免用户回来时被意外带走。
     */
    void requestAutoEnterWeb() {
        pendingAutoEnter = true;
        if (getView() != null) ui.post(refreshState);
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
                        .setText(com.deepseekharness.app.util.UiText.text("先点「启动」，等鉴权链接就绪后再进入"));
            }
            return;
        }
        final long generation = webEntryGeneration();
        final long request = ++enterRequest;
        enteringWeb = true;
        ((TextView) root.findViewById(R.id.launch_status)).setText(com.deepseekharness.app.util.UiText.text("正在验证 Web 访问权限…"));
        refreshRunState();
        try {
            new Thread(() -> {
                String cookie = null;
                String failure = null;
                try {
                    cookie = exchangeWebEntryCookie();
                    if (cookie == null || cookie.isEmpty()) failure = controller.getWebAuthFailure();
                } catch (Exception error) {
                    failure = com.deepseekharness.app.util.UiText.text("Web 鉴权失败，请点「进入」重试（") + error.getClass().getSimpleName() + com.deepseekharness.app.util.UiText.text("）");
                }
                final String authCookie = cookie;
                final String authFailure = failure;
                ui.post(() -> {
                    if (request != enterRequest || getView() != root || !isResumed()
                            || activity.isFinishing() || activity.isDestroyed()) return;
                    if (generation != webEntryGeneration() || !url.equals(webEntryUrl())) {
                        finishWebEntry(root, com.deepseekharness.app.util.UiText.text("Web 状态已变化，请等待就绪后重新进入"));
                        return;
                    }
                    if (authFailure != null) { finishWebEntry(root, authFailure); return; }
                    try {
                        openWebEntry(activity, url, authCookie);
                        ((TextView) root.findViewById(R.id.launch_status)).setText(com.deepseekharness.app.util.UiText.text("鉴权成功，正在打开 Web…"));
                        refreshLanAddr();
                    } catch (RuntimeException error) {
                        finishWebEntry(root, com.deepseekharness.app.util.UiText.text("无法打开 Web 页面，请重试（") + error.getClass().getSimpleName() + com.deepseekharness.app.util.UiText.text("）"));
                    }
                });
            }, "dsh-cookie").start();
        } catch (RuntimeException error) {
            finishWebEntry(root, com.deepseekharness.app.util.UiText.text("无法开始 Web 鉴权，请重试"));
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
        ((TextView) root.findViewById(R.id.launch_status)).setText(com.deepseekharness.app.util.UiStateText.render(message));
        refreshRunState();
    }

    private void invalidateWebEntry() {
        enterRequest++;
        enteringWeb = false;
    }

    /** 仅在用户仍位于底部时跟随；滚动日志本身，不请求焦点或移动操作区。 */
    private void updateLog(String text) {
        View root = getView();
        if (root == null || launchLog == null) return;
        LogScrollView scroll=root.findViewById(R.id.launch_log_scroll);
        boolean follow=scroll.shouldFollowEnd();
        launchLog.setText(text);
        if(follow)scroll.followEndAfterLayout();
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
        // 页面不可见时撤销自动进入：用户离开了启动页，回来不该被"突袭"打开 Web。
        pendingAutoEnter = false;
        if (enteringWeb && getView() != null) {
            ((TextView) getView().findViewById(R.id.launch_status)).setText(com.deepseekharness.app.util.UiText.text("返回后可重新进入 Web"));
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
            com.deepseekharness.app.util.StartupTrace.Snapshot trace = controller.startupDiagnostics().snapshot();
            String localUrl = webEntryUrl();
            if (launchLog != null && (trace.revision != logRevision || !localUrl.equals(logUrl))) {
                // 仅原生本机视图显示当前鉴权地址；归档与导出的诊断仍统一脱敏。
                updateLog((trace.log.isEmpty() ? com.deepseekharness.app.util.UiText.text("还没有日志。") : trace.log)
                        + (localUrl.isEmpty() ? "" : com.deepseekharness.app.util.UiText.text("\n\n本机 Web 地址（可长按复制）：\n") + localUrl));
                logRevision = trace.revision; logUrl = localUrl;
            }
            runState.setText(enteringWeb ? com.deepseekharness.app.util.UiText.text("正在鉴权并打开 Web…") : controller.isRestartBlocked() ? com.deepseekharness.app.util.UiText.text("自动重启已暂停") : stopping ? com.deepseekharness.app.util.UiText.text("DSH 停止中…") : starting ? com.deepseekharness.app.util.UiText.text("DSH 启动中…")
                    : ready ? (trace.safe ? com.deepseekharness.app.util.UiText.text("基础界面已就绪 · 安全模式") : com.deepseekharness.app.util.UiText.text("DSH 已就绪，可进入"))
                    + (controller.isWebCompatibilityFallback() ? com.deepseekharness.app.util.UiText.text(" · 已兼容切换 proot") : "")
                    : controller.isUserStopped() ? getString(R.string.ui2_stopped) : getString(R.string.ui2_stopped));
            ((TextView)root.findViewById(R.id.launch_port)).setText(ready?String.valueOf(controller.getWebPort()):"—");
            ((TextView)root.findViewById(R.id.launch_environment)).setText(EnvironmentUiStatus.get(requireContext()).ready?"READY":"—");
            ((TextView)root.findViewById(R.id.launch_subtitle)).setText(ready?
                    (controller.isWebCompatibilityFallback()?"proot":controller.proot().runtime().id())+" · dsh "+com.deepseekharness.app.util.Constants.DSH_VERSION:getString(R.string.ui2_launch_hint));
            root.findViewById(R.id.launch_status).setVisibility(starting||stopping||!trace.issues.isEmpty()?View.VISIBLE:View.GONE);
            if (starting) ((TextView) root.findViewById(R.id.launch_status)).setText(trace.stage
                    + com.deepseekharness.app.util.UiText.text(" · 本阶段 ") + trace.stageElapsedMs / 1000 + com.deepseekharness.app.util.UiText.text(" 秒 · 总计 ") + trace.elapsedMs / 1000
                    + com.deepseekharness.app.util.UiText.text(" 秒\n") + (trace.issues.isEmpty() ? com.deepseekharness.app.util.UiText.text("下方实时显示启动输出；等待不会自动终止。") : com.deepseekharness.app.util.UiText.text("检测到插件或配置异常，可查看恢复选项。")));
            root.findViewById(R.id.launch_busy).setVisibility(starting || stopping ? View.VISIBLE : View.GONE);
            Button recovery = root.findViewById(R.id.launch_recovery);
            int failures = controller.config().getWebFailures();
            recovery.setVisibility(View.VISIBLE);
            recovery.setText(com.deepseekharness.app.util.UiText.text("恢复选项"));
            recovery.setContentDescription(!trace.issues.isEmpty() ? com.deepseekharness.app.util.UiText.text("检测到 ") + trace.issues.size() + com.deepseekharness.app.util.UiText.text(" 项异常，查看插件与恢复选项")
                    : com.deepseekharness.app.util.UiText.text("失败 ") + failures + "/3，" + controller.config().getWebFailureStage() + com.deepseekharness.app.util.UiText.text("，查看恢复选项"));
            if (start != null) {
                webReady = ready;
                start.setText(enteringWeb ? com.deepseekharness.app.util.UiText.text("进入中…") : ready ? com.deepseekharness.app.util.UiText.choose("▶  进入工作台","▶  Open workspace") : getString(R.string.ui2_start));
                start.setEnabled(!enteringWeb && !starting && !stopping);
            }
            Button restart = root.findViewById(R.id.launch_open);
            if (restart != null) restart.setEnabled(!starting && !stopping);
            Button stop = root.findViewById(R.id.launch_stop);
            if (stop != null) stop.setEnabled(!stopping);
            if (EnvironmentUiStatus.get(requireContext()).recovery) {
                runState.setText(com.deepseekharness.app.util.UiText.text("环境需要恢复"));
                if (start != null) start.setEnabled(false);
                if (restart != null) restart.setEnabled(false);
                root.findViewById(R.id.launch_safe).setEnabled(false);
                recovery.setVisibility(View.VISIBLE);
                recovery.setText(com.deepseekharness.app.util.UiText.text("环境维护"));
                recovery.setContentDescription(com.deepseekharness.app.util.UiText.text("查看环境维护与恢复选项"));
                recovery.setOnClickListener(v -> startActivity(new Intent(requireContext(), ExtractActivity.class).putExtra("review_only", true)));
            } else {
                root.findViewById(R.id.launch_safe).setEnabled(!starting && !stopping);
                recovery.setOnClickListener(v -> showRecovery());
            }
        } catch (Throwable ignored) {
        }
    }

    private void showRecovery() {
        startActivity(new Intent(requireContext(),StartupRecoveryActivity.class));
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
                lanAddrText.setText(com.deepseekharness.app.util.UiText.text("局域网地址（同 WiFi 的其它设备访问）：\n") + addr);
                lanAddrText.setOnClickListener(v -> copyAddr(com.deepseekharness.app.util.UiText.text("局域网地址"), addr));
            } else {
                lanAddrText.setText(com.deepseekharness.app.util.UiText.text("局域网已开启，但还没拿到 WiFi 地址（连上 WiFi 再看）"));
                lanAddrText.setOnClickListener(null);
            }
        } else {
            lanAddrText.setText(com.deepseekharness.app.util.UiText.text("局域网代理正在等待本轮认证"));
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
                Toast.makeText(requireContext(), com.deepseekharness.app.util.UiText.text("已复制：") + addr, Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(requireContext(), com.deepseekharness.app.util.UiText.text("复制失败：") + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
