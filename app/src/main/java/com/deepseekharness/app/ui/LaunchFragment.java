package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

/**
 * 启动页：启动 / 进入 / 停止 dsh Web，显示运行状态、鉴权链接与局域网访问地址。
 */
public class LaunchFragment extends Fragment {

  /** 通知点击进入时带上的参数：Web 就绪后自动打开会话。 */
  public static final String ARG_OPEN_WEB = "open_web";

  private HarnessController controller;
  private TextView lanAddrText;
  private TextView launchLog;
  private boolean logExpanded;
  private String fullLog = "";

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

  private final Runnable refreshState =
      new Runnable() {
        @Override
        public void run() {
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
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
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
    v.findViewById(R.id.launch_download_logs)
        .setOnClickListener(x -> startActivity(DiagnosticActivity.downloadLogs(requireContext())));

    v.findViewById(R.id.launch_models)
        .setOnClickListener(
            x -> startActivity(new Intent(requireContext(), ModelSetupActivity.class)));
    v.findViewById(R.id.launch_copy_local).setOnClickListener(x -> copyLocalAddress());
    v.findViewById(R.id.launch_copy_lan).setOnClickListener(x -> copyLanAddress());
    v.findViewById(R.id.launch_log_toggle)
        .setOnClickListener(
            x -> {
              logExpanded = !logExpanded;
              applyLogView();
            });
    restart.setText(R.string.ui2_restart);
    v.findViewById(R.id.launch_recovery).setOnClickListener(x -> showRecovery());
    v.findViewById(R.id.launch_safe)
        .setOnClickListener(
            x ->
                new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                    .setTitle(com.deepseekharness.app.util.UiText.text("安全启动 Web？"))
                    .setMessage(
                        com.deepseekharness.app.util.UiText.text(
                            "停止当前启动，只加载官方基础界面。原插件开关、会话、模型和配置保留；普通重启后回到原配置。安全界面不加载移动插件等扩展。"))
                    .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
                    .setPositiveButton(
                        com.deepseekharness.app.util.UiText.text("安全启动"),
                        (dialog, which) -> doStart(activity, status, start, true))
                    .show());

    // 启动按钮：未就绪时是「启动」；鉴权链接就绪后自动变为「进入」，点击进 WebUI。
    start.setOnClickListener(
        x -> {
          if (webReady || !webEntryUrl().isEmpty()) {
            enterWeb();
            return;
          }
          doStart(activity, status, start);
        });

    restart.setOnClickListener(
        x -> {
          // startWeb 本身串行执行「清旧进程 → 启动」，无需拆成两次请求。
          doStart(activity, status, start);
        });

    // 通知点击进来：置待进入标记，由每秒刷新循环在鉴权链接就绪后自动进入。
    if (getArguments() != null && getArguments().getBoolean(ARG_OPEN_WEB, false)) {
      getArguments().remove(ARG_OPEN_WEB);
      pendingAutoEnter = true;
    }

    stop.setOnClickListener(
        x -> {
          invalidateWebEntry();
          controller.stopWeb(
              msg -> {
                long generation = controller.getWebGeneration();
                activity.runOnUiThread(
                    () -> {
                      if (getView() != v || generation != controller.getWebGeneration()) return;
                      status.setText(com.deepseekharness.app.util.UiStateText.render(msg));
                      refreshRunState();
                      refreshLanAddr();
                      var stopped = controller.lastStopResult();
                      if (stopped != null && !stopped.stopped()
                          || !controller.lastStopError().isEmpty())
                        offerForceStop(activity, v, status, generation);
                    });
              });
          webReady = false;
          start.setText(getString(R.string.ui2_start_action));
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
    String time =
        new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(new java.util.Date());
    status.setText(com.deepseekharness.app.util.UiText.format("启动中…（%s）", time));
    start.setText(getString(R.string.ui2_start_action));
    webReady = false;
    java.util.function.Consumer<String> startStatus =
        msg -> {
          long generation = controller.getWebGeneration();
          activity.runOnUiThread(
              () -> {
                if (getView() != root || generation != controller.getWebGeneration()) return;
                status.setText(com.deepseekharness.app.util.UiStateText.render(msg));
                refreshRunState();
                refreshLanAddr();
              });
        };
    boolean accepted;
    if (safeMode) {
      controller.recoverWeb(true, null, startStatus);
      accepted = true;
    } else accepted = controller.startWeb(startStatus);
    refreshRunState();
    if (!accepted) return;
    // 前台保活服务：dsh 后台常驻 + 看门狗自动重启（退到桌面/锁屏不被杀）
    try {
      Intent svc =
          new Intent(requireContext(), com.deepseekharness.app.HarnessService.class)
              .setAction(com.deepseekharness.app.HarnessService.ACTION_START);
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        requireContext().startForegroundService(svc);
      } else {
        requireContext().startService(svc);
      }
    } catch (Throwable t) {
      android.util.Log.w(
          "DSHA", com.deepseekharness.app.util.UiText.format("拉起保活服务失败: %s", t.getMessage()));
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
    if (enteringWeb
        || root == null
        || activity == null
        || !isResumed()
        || activity.isFinishing()
        || activity.isDestroyed()) return;
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
    ((TextView) root.findViewById(R.id.launch_status))
        .setText(com.deepseekharness.app.util.UiText.text("正在验证 Web 访问权限…"));
    refreshRunState();
    try {
      new Thread(
              () -> {
                String cookie = null;
                String failure = null;
                try {
                  cookie = exchangeWebEntryCookie();
                  if (cookie == null || cookie.isEmpty()) failure = controller.getWebAuthFailure();
                } catch (Exception error) {
                  failure =
                      com.deepseekharness.app.util.UiText.format(
                          "Web 鉴权失败，请点「进入」重试（%s）", error.getClass().getSimpleName());
                }
                final String authCookie = cookie;
                final String authFailure = failure;
                ui.post(
                    () -> {
                      if (request != enterRequest
                          || getView() != root
                          || !isResumed()
                          || activity.isFinishing()
                          || activity.isDestroyed()) return;
                      if (generation != webEntryGeneration() || !url.equals(webEntryUrl())) {
                        finishWebEntry(
                            root, com.deepseekharness.app.util.UiText.text("Web 状态已变化，请等待就绪后重新进入"));
                        return;
                      }
                      if (authFailure != null) {
                        finishWebEntry(root, authFailure);
                        return;
                      }
                      try {
                        openWebEntry(activity, url, authCookie);
                        ((TextView) root.findViewById(R.id.launch_status))
                            .setText(com.deepseekharness.app.util.UiText.text("鉴权成功，正在打开 Web…"));
                        refreshLanAddr();
                      } catch (RuntimeException error) {
                        finishWebEntry(
                            root,
                            com.deepseekharness.app.util.UiText.format(
                                "无法打开 Web 页面，请重试（%s）", error.getClass().getSimpleName()));
                      }
                    });
              },
              "dsh-cookie")
          .start();
    } catch (RuntimeException error) {
      finishWebEntry(root, com.deepseekharness.app.util.UiText.text("无法开始 Web 鉴权，请重试"));
    }
  }

  // 同包调试自测可替换鉴权和 Activity 出口，验证连点/异常/销毁，不实际启动 Web。
  String webEntryUrl() {
    return controller.getWebAuthUrl();
  }

  long webEntryGeneration() {
    return controller.getWebGeneration();
  }

  String exchangeWebEntryCookie() {
    return controller.exchangeDshAuthCookie();
  }

  void openWebEntry(Activity activity, String url, String cookie) {
    startActivity(WebPreviewActivity.intent(activity, url, cookie));
  }

  private void finishWebEntry(View root, String message) {
    enteringWeb = false;
    ((TextView) root.findViewById(R.id.launch_status))
        .setText(com.deepseekharness.app.util.UiStateText.render(message));
    refreshRunState();
  }

  private void invalidateWebEntry() {
    enterRequest++;
    enteringWeb = false;
  }

  /** 仅在用户仍位于底部时跟随；滚动日志本身，不请求焦点或移动操作区。 */
  private void updateLog(String text) {
    fullLog = text == null ? "" : text;
    applyLogView();
  }

  private void applyLogView() {
    View root = getView();
    if (root == null || launchLog == null) return;
    LogScrollView scroll = root.findViewById(R.id.launch_log_scroll);
    Button toggle = root.findViewById(R.id.launch_log_toggle);
    String display = displayLog(fullLog);
    if (logExpanded) {
      launchLog.setMaxLines(Integer.MAX_VALUE);
      launchLog.setEllipsize(null);
      launchLog.setText(display);
      if (scroll != null) {
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = dp(170);
        scroll.setLayoutParams(lp);
        if (scroll.shouldFollowEnd()) scroll.followEndAfterLayout();
      }
      if (toggle != null) toggle.setText(R.string.ui2_collapse_logs);
    } else {
      launchLog.setMaxLines(1);
      launchLog.setEllipsize(android.text.TextUtils.TruncateAt.END);
      launchLog.setText(lastLine(display));
      if (scroll != null) {
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = dp(56);
        scroll.setLayoutParams(lp);
      }
      if (toggle != null) toggle.setText(R.string.ui2_view_all_logs);
    }
  }

  private String displayLog(String text) {
    if (text == null || text.isEmpty()) return "";
    return text.replace("进入对话", getString(R.string.ui2_enter_workspace));
  }

  private static String lastLine(String text) {
    if (text == null || text.isEmpty()) return "";
    int end = text.length();
    while (end > 0) {
      char c = text.charAt(end - 1);
      if (c != '\n' && c != '\r') break;
      end--;
    }
    int start = text.lastIndexOf('\n', end - 1);
    return text.substring(start + 1, end);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
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
      ((TextView) getView().findViewById(R.id.launch_status))
          .setText(com.deepseekharness.app.util.UiText.text("返回后可重新进入 Web"));
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
      var stopResult = controller.lastStopResult();
      boolean unresolvedStop =
          controller.isUserStopped()
              && !starting
              && (stopResult != null && !stopResult.stopped()
                  || !controller.lastStopError().isEmpty());
      boolean ready = !starting && !stopping && !webEntryUrl().isEmpty();
      com.deepseekharness.app.util.StartupTrace.Snapshot trace =
          controller.startupDiagnostics().snapshot();
      String localUrl = webEntryUrl();
      if (launchLog != null && (trace.revision != logRevision || !localUrl.equals(logUrl))) {
        updateLog(
            trace.log.isEmpty() ? com.deepseekharness.app.util.UiText.text("还没有日志。") : trace.log);
        logRevision = trace.revision;
        logUrl = localUrl;
      }
      runState.setText(
          enteringWeb
              ? com.deepseekharness.app.util.UiText.text("正在鉴权并打开 Web…")
              : controller.isRestartBlocked()
                  ? com.deepseekharness.app.util.UiText.text("自动重启已暂停")
                  : stopping
                      ? com.deepseekharness.app.util.UiText.text("DSH 停止中…")
                      : starting
                          ? com.deepseekharness.app.util.UiText.text("DSH 启动中…")
                          : ready
                              ? getString(R.string.ui2_ready)
                              : getString(R.string.ui2_stopped));
      TextView compat = root.findViewById(R.id.launch_compat);
      if (compat != null) {
        if (ready && controller.isWebCompatibilityFallback()) {
          compat.setText(R.string.ui2_proot_compat);
          compat.setVisibility(View.VISIBLE);
        } else if (ready && trace.safe) {
          compat.setText(R.string.ui2_safe_mode);
          compat.setVisibility(View.VISIBLE);
        } else {
          compat.setVisibility(View.GONE);
        }
      }
      TextView dot = root.findViewById(R.id.launch_run_dot);
      if (dot != null) {
        if (ready) {
          dot.setBackgroundResource(R.drawable.bg_ok_chip);
          dot.setTextColor(color(R.color.ok));
          dot.setText("✓");
        } else if (starting || stopping || enteringWeb) {
          dot.setBackgroundResource(R.drawable.bg_warn_chip);
          dot.setTextColor(color(R.color.warn));
          dot.setText(R.string.ui_status_dot);
        } else {
          dot.setBackgroundResource(R.drawable.bg_icon_tile);
          dot.setTextColor(color(R.color.text_muted));
          dot.setText(R.string.ui_status_dot);
        }
      }
      ((TextView) root.findViewById(R.id.launch_port))
          .setText(ready ? String.valueOf(controller.getWebPort()) : "—");
      ((TextView) root.findViewById(R.id.launch_environment))
          .setText(EnvironmentUiStatus.get(requireContext()).ready ? "READY" : "—");
      ((TextView) root.findViewById(R.id.launch_subtitle))
          .setText(
              ready || starting
                  ? (controller.isWebCompatibilityFallback()
                          ? "proot"
                          : controller.proot().runtime().id())
                      + " · dsh "
                      + com.deepseekharness.app.util.Constants.DSH_VERSION
                  : getString(R.string.ui2_launch_hint));
      root.findViewById(R.id.launch_status)
          .setVisibility(
              starting || stopping || unresolvedStop || !trace.issues.isEmpty()
                  ? View.VISIBLE
                  : View.GONE);
      if (starting)
        ((TextView) root.findViewById(R.id.launch_status))
            .setText(
                com.deepseekharness.app.util.UiText.format(
                    "%s · 本阶段 %s 秒 · 总计 %s 秒\n%s",
                    trace.stage,
                    trace.stageElapsedMs / 1000,
                    trace.elapsedMs / 1000,
                    trace.issues.isEmpty()
                        ? com.deepseekharness.app.util.UiText.text("下方实时显示启动输出；等待不会自动终止。")
                        : com.deepseekharness.app.util.UiText.text("检测到插件或配置异常，可查看恢复选项。")));
      root.findViewById(R.id.launch_busy)
          .setVisibility(starting || stopping ? View.VISIBLE : View.GONE);
      Button recovery = root.findViewById(R.id.launch_recovery);
      int failures = controller.config().getWebFailures();
      recovery.setVisibility(View.VISIBLE);
      recovery.setText(com.deepseekharness.app.util.UiText.text("恢复选项"));
      recovery.setContentDescription(
          !trace.issues.isEmpty()
              ? com.deepseekharness.app.util.UiText.format(
                  "检测到 %s 项异常，查看插件与恢复选项", trace.issues.size())
              : com.deepseekharness.app.util.UiText.format(
                  "失败 %s/3，%s，查看恢复选项", failures, controller.config().getWebFailureStage()));
      if (start != null) {
        webReady = ready;
        start.setText(
            enteringWeb
                ? com.deepseekharness.app.util.UiText.text("进入中…")
                : ready
                    ? getString(R.string.ui2_enter_workspace)
                    : getString(R.string.ui2_start_action));
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
        recovery.setOnClickListener(
            v ->
                startActivity(
                    new Intent(requireContext(), ExtractActivity.class)
                        .putExtra("review_only", true)));
      } else {
        root.findViewById(R.id.launch_safe).setEnabled(!starting && !stopping);
        recovery.setOnClickListener(v -> showRecovery());
      }
    } catch (RuntimeException failure) {
      if (getContext() != null)
        com.deepseekharness.app.core.DiagnosticLog.record(
            getContext(), "UI_REFRESH_FAILED", failure.getClass().getSimpleName());
      View view = getView();
      if (view != null) {
        TextView state = view.findViewById(R.id.launch_run_state);
        if (state != null)
          state.setText(
              com.deepseekharness.app.util.UiText.choose(
                  "运行状态暂时无法读取，请查看操作记录", "Run state unavailable. See operation history."));
      }
    }
  }

  private void showRecovery() {
    startActivity(new Intent(requireContext(), StartupRecoveryActivity.class));
  }

  private void offerForceStop(Activity activity, View root, TextView status, long generation) {
    // 进程与文件身份核验放在工作线程；本次候选只捕获一次，由对话框局部持有至确认。
    new Thread(
            () -> {
              HarnessController.ForceStopRequest request = null;
              try {
                request = controller.prepareForceStop();
              } catch (java.io.IOException denied) {
                com.deepseekharness.app.core.DiagnosticLog.record(
                    activity.getApplicationContext(),
                    "WEB_FORCE_STOP_CANDIDATE",
                    denied.getClass().getSimpleName());
              }
              final HarnessController.ForceStopRequest candidate = request;
              ui.post(
                  () -> {
                    if (getView() != root
                        || !isResumed()
                        || activity.isFinishing()
                        || activity.isDestroyed()
                        || generation != controller.getWebGeneration()) return;
                    if (candidate == null) {
                      status.setText(
                          com.deepseekharness.app.util.UiText.text(
                              "尚未确认 Web 退出；进程身份无法核验，保留运行屏障。请查看诊断后重试停止。"));
                      return;
                    }
                    new DshaDialogBuilder(requireContext())
                        .setTitle(com.deepseekharness.app.util.UiText.text("强制结束本次 Web？"))
                        .setMessage(
                            com.deepseekharness.app.util.UiText.text(
                                "普通停止尚未确认 Web 退出。本次已核验进程属于 DSHA；强制结束可能中断正在写入的对话或文件。\n\n确认后只结束这次已核验的 Web 进程；身份或状态变化会拒绝执行。取消会保留现场与运行屏障。"))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(
                            com.deepseekharness.app.util.UiText.text("强制结束"),
                            (dialog, which) -> {
                              if (getView() != root
                                  || !isResumed()
                                  || activity.isFinishing()
                                  || activity.isDestroyed()) return;
                              boolean accepted =
                                  controller.forceStop(
                                      candidate,
                                      message ->
                                          ui.post(
                                              () -> {
                                                if (getView() != root
                                                    || generation != controller.getWebGeneration())
                                                  return;
                                                status.setText(
                                                    com.deepseekharness.app.util.UiStateText.render(
                                                        message));
                                                refreshRunState();
                                                refreshLanAddr();
                                              }));
                              if (!accepted)
                                status.setText(
                                    com.deepseekharness.app.util.UiText.text(
                                        "Web 状态或进程身份已变化，本次强制结束未执行。请重新停止后确认。"));
                              else
                                status.setText(
                                    com.deepseekharness.app.util.UiText.text("正在强制结束本次已核验的 Web…"));
                            })
                        .show();
                  });
            },
            "web-force-stop-candidate")
        .start();
  }

  /** 本机 / 局域网地址卡：lan_addr 仅保留 id，真实状态写到可见行。 */
  private void refreshLanAddr() {
    View root = getView();
    if (root == null || !isAdded()) return;
    if (lanAddrText != null) lanAddrText.setVisibility(View.GONE);

    int port = controller.getWebPort();
    TextView local = root.findViewById(R.id.launch_local_value);
    if (local != null)
      local.setText(port > 0 ? "127.0.0.1:" + port : getString(R.string.ui_status_unavailable));
    View copyLocal = root.findViewById(R.id.launch_copy_local);
    if (copyLocal != null) copyLocal.setEnabled(port > 0);

    TextView lanLabel = root.findViewById(R.id.launch_lan_label);
    if (lanLabel != null)
      lanLabel.setText(getString(R.string.ui2_lan) + " · " + getString(R.string.ui2_lan_hint));
    TextView lanValue = root.findViewById(R.id.launch_lan_value);
    View copyLan = root.findViewById(R.id.launch_copy_lan);
    boolean lan = controller.config().isLanMode();
    if (!lan) {
      if (lanValue != null) {
        lanValue.setText(R.string.ui2_lan_off);
        lanValue.setOnClickListener(null);
      }
      if (copyLan != null) copyLan.setVisibility(View.GONE);
      return;
    }
    if (LanProxyService.isBound()) {
      String ip = HarnessController.getLanAddress();
      if (ip != null && !ip.isEmpty()) {
        final String service = "http://" + ip + ":" + LanProxyService.LAN_PORT + "/";
        if (lanValue != null) {
          lanValue.setText(ip + ":" + LanProxyService.LAN_PORT);
          lanValue.setOnClickListener(v -> showLanAddress(service));
        }
        if (copyLan != null) copyLan.setVisibility(View.VISIBLE);
      } else {
        if (lanValue != null) {
          lanValue.setText(
              com.deepseekharness.app.util.UiText.text("局域网已开启，但还没拿到 WiFi 地址（连上 WiFi 再看）"));
          lanValue.setOnClickListener(null);
        }
        if (copyLan != null) copyLan.setVisibility(View.GONE);
      }
    } else {
      if (lanValue != null) {
        lanValue.setText(com.deepseekharness.app.util.UiText.text("局域网代理正在等待本轮认证"));
        lanValue.setOnClickListener(null);
      }
      if (copyLan != null) copyLan.setVisibility(View.GONE);
    }
  }

  private void copyLocalAddress() {
    int port = controller.getWebPort();
    if (port <= 0) return;
    copyAddr(getString(R.string.ui2_copy_local), "127.0.0.1:" + port);
  }

  private void copyLanAddress() {
    if (!controller.config().isLanMode() || !LanProxyService.isBound()) return;
    String ip = HarnessController.getLanAddress();
    if (ip == null || ip.isEmpty()) return;
    copyAddr(getString(R.string.ui2_copy_lan), ip + ":" + LanProxyService.LAN_PORT);
  }

  private int color(int id) {
    return requireContext().getColor(id);
  }

  private void showLanAddress(String service) {
    if (!isAdded() || !LanProxyService.isBound()) return;
    final long generation = controller.getWebGeneration();
    final String token = LanProxyService.getLanToken(requireContext());
    final String address = service + "?token=" + token;
    new DshaDialogBuilder(requireContext())
        .setTitle(com.deepseekharness.app.util.UiText.text("局域网连接链接"))
        .setMessage(
            com.deepseekharness.app.util.UiText.format(
                "此链接包含访问凭据。局域网使用 HTTP，流量未加密，请只在可信网络使用并只分享给可信设备。\n\n%s", address))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(
            com.deepseekharness.app.util.UiText.text("复制连接链接"),
            (dialog, which) -> {
              String ip = HarnessController.getLanAddress();
              String currentService = "http://" + ip + ":" + LanProxyService.LAN_PORT + "/";
              if (!isAdded()
                  || !LanProxyService.isBound()
                  || generation != controller.getWebGeneration()
                  || !service.equals(currentService)
                  || !token.equals(LanProxyService.getLanToken(requireContext()))) {
                if (isAdded())
                  Toast.makeText(
                          requireContext(),
                          com.deepseekharness.app.util.UiText.text("连接已变化，请重新查看链接。"),
                          Toast.LENGTH_LONG)
                      .show();
                return;
              }
              copyAddr(com.deepseekharness.app.util.UiText.text("局域网地址"), address);
            })
        .show();
  }

  private void copyAddr(String label, String addr) {
    try {
      android.content.ClipboardManager cm =
          (android.content.ClipboardManager)
              requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm != null) {
        cm.setPrimaryClip(com.deepseekharness.app.bridge.SensitiveClipboard.text(label, addr));
        Toast.makeText(
                requireContext(),
                com.deepseekharness.app.util.UiText.choose(
                    "已复制连接地址，请只分享给可信设备",
                    "Connection address copied. Share only with trusted devices"),
                Toast.LENGTH_SHORT)
            .show();
      }
    } catch (Throwable t) {
      Toast.makeText(
              requireContext(),
              com.deepseekharness.app.util.UiText.format("复制失败：%s", t.getMessage()),
              Toast.LENGTH_SHORT)
          .show();
    }
  }
}
