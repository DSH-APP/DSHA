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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.LanProxyService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.LanAccessPresentation;
import com.deepseekharness.app.util.LanAddressPolicy;
import com.deepseekharness.app.util.SummaryText;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.button.MaterialButton;

/**
 * 启动页：启动 / 进入 / 停止 dsh Web，显示运行状态、鉴权链接与局域网访问地址。
 */
public class LaunchFragment extends Fragment {

  /** 通知点击进入时带上的参数：Web 就绪后自动打开会话。 */
  public static final String ARG_OPEN_WEB = "open_web";

  private HarnessController controller;
  private TextView lanAddrText;
  private TextView launchLog;
  private LaunchLogPanel logPanel;
  private ShellActivityTrack activityTrack;
  private androidx.appcompat.app.AlertDialog accessDialog;
  private android.animation.ObjectAnimator dotPulse;

  /** 启动核对（异常默认展开）与页脚技术信息的折叠状态。 */
  private DisclosureCard.Memory disclosure = new DisclosureCard.Memory();

  private TextView techNote;
  private TechNote.Model techModel;

  /** 访问地址弹窗的自定义正文与三个横向按钮（关闭固定在最右）。 */
  private TextView accessBody;

  private Button accessLanButton;
  private Button accessCopyButton;

  /** 启动按钮当前是否处于「进入」态（鉴权链接已就绪）。 */
  private boolean webReady;

  /** 主线程单飞；成功打开后保持占用，直到页面重新可见。 */
  private boolean enteringWeb;

  private boolean webEntryFailure;

  private boolean preparingStart;
  // 强停候选去抖：同一轮停止最多准备一次，间隔内只提示等待，不重复写候选诊断。
  private boolean forceCandidatePending;
  private long forceCandidateCheckedAtMs;
  // Preserve admission failures until the next explicit action or a ready Web instance.
  private String startStatusMessage = "";
  private final java.util.concurrent.atomic.AtomicLong startRequest =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.Executor START_IO =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  /** 本次启动开始时刻（显示耗时用）。 */
  private long startAtMs;

  private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

  /** 通知点击进来、待自动进入 Web 的意图；就绪事件立即消费，每秒刷新兜底。 */
  private boolean pendingAutoEnter;

  private final Runnable readyChanged = this::requestStateRefresh;

  private final Runnable refreshState =
      new Runnable() {
        @Override
        public void run() {
          ui.removeCallbacks(this);
          if (getView() == null || !isResumed()) return;
          refreshRunState();
          refreshLanAddr();
          // 通知点击进来的自动进入：就绪事件与周期兜底共用一条刷新链。
          // 判据只认「鉴权链接已出现」——它就是进入 Web 的前提，也是按钮变「进入」的
          // 同一条件。不能拿 getWebAuthFailure() 判成败：那是最近一次鉴权结果消息，
          // 初始值与成功值都非 null（成功时是"鉴权成功"），用它当成功判据会永不进入。
          if (pendingAutoEnter && !webEntryUrl().isEmpty()) {
            pendingAutoEnter = false;
            enterWeb();
          }
          if (getView() != null && isResumed()) ui.postDelayed(this, 1000);
        }
      };

  /** 合并同时到达的通知和就绪事件，不叠加永久的每秒刷新任务。 */
  private void requestStateRefresh() {
    if (controller == null || getView() == null || !isResumed()) return;
    ui.removeCallbacks(refreshState);
    ui.post(refreshState);
  }

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View v = inflater.inflate(R.layout.fragment_launch, container, false);
    PressFeedback.attachButtons(v);
    if (savedInstanceState != null)
      disclosure = DisclosureCard.Memory.restore(savedInstanceState, "disclosure");

    // v5 主页新增的语境、活动条与日志工具；原有 id 与业务 id 全部保留。
    activityTrack = v.findViewById(R.id.launch_activity);

    controller = HarnessController.get(requireContext());
    final Activity activity = requireActivity();
    TextView status = v.findViewById(R.id.launch_status);
    Button start = v.findViewById(R.id.launch_start);
    Button restart = v.findViewById(R.id.launch_open);
    Button stop = v.findViewById(R.id.launch_stop);
    lanAddrText = v.findViewById(R.id.lan_addr);
    launchLog = v.findViewById(R.id.launch_log);
    // 启动日志区（概览 / 原始 / 跟随浮标）由独立协作者渲染，本页只提供真实状态。
    logPanel = new LaunchLogPanel(v);
    v.findViewById(R.id.launch_download_logs)
        .setOnClickListener(x -> startActivity(DiagnosticActivity.downloadLogs(requireContext())));

    // 日志工具：概览 / 原始、复制与下载都直接作用于完整原文。
    v.findViewById(R.id.launch_copy_logs).setOnClickListener(x -> copyLogs());
    v.findViewById(R.id.launch_copy_logs)
        .setContentDescription(UiText.choose("复制完整启动日志", "Copy the full startup log"));
    v.findViewById(R.id.launch_download_logs)
        .setContentDescription(UiText.choose("下载启动日志", "Download the startup log"));
    v.findViewById(R.id.launch_copy_logs).setTooltipText(UiText.choose("复制日志", "Copy log"));
    v.findViewById(R.id.launch_download_logs)
        .setTooltipText(UiText.choose("下载日志", "Download logs"));

    restart.setText(R.string.ui2_restart);
    applySchemeALayout(v);
    v.findViewById(R.id.launch_recovery).setOnClickListener(x -> showRecovery());
    v.findViewById(R.id.launch_safe)
        .setOnClickListener(
            x ->
                new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext(), true)
                    .setTitle(com.deepseekharness.app.util.UiText.text("安全启动 Web？"))
                    .setMessage(
                        com.deepseekharness.app.util.UiText.text("安全模式：仅加载官方基础界面；插件、会话与配置保留。"))
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

    // 通知点击进来：置待进入标记，由就绪事件或周期兜底在鉴权后自动进入。
    if (getArguments() != null && getArguments().getBoolean(ARG_OPEN_WEB, false)) {
      getArguments().remove(ARG_OPEN_WEB);
      pendingAutoEnter = true;
    }

    stop.setOnClickListener(x -> requestStop(activity, v, status));

    // 页尾「技术说明」：运行时与 dsh 版本不再占副标题，折叠后才展开（B3）。
    techModel = new TechNote.Model();
    techModel.key = "launch-tech";
    techModel.style = R.style.ShellLaunchMeta;
    techNote =
        TechNote.attach(
            (LinearLayout) v.findViewById(R.id.launch_sections), techModel, disclosure, null);
    return v;
  }

  /** 启动 dsh：记录启动时刻，鉴权链接就绪后把「启动」变「进入」并输出 URL 到日志。 */
  private void doStart(Activity activity, TextView status, Button start) {
    doStart(activity, status, start, false);
  }

  private void doStart(Activity activity, TextView status, Button start, boolean safeMode) {
    if (preparingStart || !safeMode && (controller.isStarting() || controller.isStopping())) return;
    final View root = getView();
    if (root == null || !isResumed() || activity.isFinishing() || activity.isDestroyed()) return;
    FirstRunWebStart.cancel(requireContext());
    preparingStart = true;
    long request = startRequest.incrementAndGet();
    startStatusMessage = "";
    invalidateWebEntry();
    startAtMs = System.currentTimeMillis();
    String time =
        new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(new java.util.Date());
    status.setText(com.deepseekharness.app.util.UiText.format("启动中…（%s）", time));
    start.setText(com.deepseekharness.app.util.UiText.text("启动"));
    webReady = false;
    java.util.function.Consumer<String> startStatus =
        msg -> {
          long generation = controller.getWebGeneration();
          activity.runOnUiThread(
              () -> {
                if (startRequest.get() != request
                    || getView() != root
                    || generation != controller.getWebGeneration()) return;
                startStatusMessage = msg == null ? "" : msg;
                refreshRunState();
                refreshLanAddr();
              });
        };
    refreshRunState();
    boolean queued =
        enqueueStart(
            requireActivity(),
            safeMode,
            startStatus,
            accepted -> {
              if (startRequest.get() != request || getView() != root) return;
              preparingStart = false;
              if (!accepted && startStatusMessage.isEmpty()) {
                startStatusMessage =
                    UiText.choose(
                        "启动请求未执行，请检查运行状态后重试。",
                        "The start request was not submitted. Check the run status and retry.");
              }
              refreshRunState();
              refreshLanAddr();
            });
    if (!queued) {
      preparingStart = false;
      refreshRunState();
    }
  }

  // Isolate dispatch from presentation so host tests can exercise the real button and callbacks.
  boolean enqueueStart(
      androidx.fragment.app.FragmentActivity activity,
      boolean safeMode,
      java.util.function.Consumer<String> status,
      java.util.function.Consumer<Boolean> completed) {
    return FirstRunWebStart.enqueueVisible(
        activity, START_IO, safeMode, safeMode, status, completed);
  }

  /**
   * 外部（通知点击）请求：立即刷新；未就绪时继续等待事件或周期兜底。
   *
   * <p>页面不可见时由 {@code onPause} 清掉，避免用户回来时被意外带走。
   */
  void requestAutoEnterWeb() {
    // Main 的入口检查可能仍挂起恢复页视图；先保留明确请求，创建视图后再等待鉴权。
    pendingAutoEnter = true;
    requestStateRefresh();
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
            .setText(com.deepseekharness.app.util.UiText.text("请先启动"));
      }
      return;
    }
    final long generation = webEntryGeneration();
    enteringWeb = true;
    webEntryFailure = false;
    ((TextView) root.findViewById(R.id.launch_status))
        .setText(com.deepseekharness.app.util.UiText.choose("打开中…", "Opening Web…"));
    refreshRunState();
    try {
      if (getView() != root || !isResumed() || activity.isFinishing() || activity.isDestroyed()) {
        invalidateWebEntry();
        return;
      }
      if (generation != webEntryGeneration() || !url.equals(webEntryUrl())) {
        finishWebEntry(root, com.deepseekharness.app.util.UiText.text("状态已变化，请稍后重试"));
        return;
      }
      // The browser owns the token -> cookie -> HTTP 200 verification. Doing it here
      // too created two serial exchanges before the first page; no unverified cookie
      // is handed off and neither navigation nor progress reports browser readiness.
      openWebEntry(activity, url, null);
      refreshLanAddr();
    } catch (RuntimeException error) {
      finishWebEntry(
          root,
          com.deepseekharness.app.util.UiText.format(
              "无法打开 Web 页面，请重试（%s）", error.getClass().getSimpleName()));
    }
  }

  // Same-package host tests can replace only the Activity destination.
  String webEntryUrl() {
    return controller.getWebAuthUrl();
  }

  long webEntryGeneration() {
    return controller.getWebGeneration();
  }

  void openWebEntry(Activity activity, String url, String cookie) {
    startActivity(WebPreviewActivity.intent(activity, url, cookie));
  }

  private void finishWebEntry(View root, String message) {
    enteringWeb = false;
    webEntryFailure = true;
    ((TextView) root.findViewById(R.id.launch_status))
        .setText(com.deepseekharness.app.util.UiStateText.render(message));
    refreshRunState();
  }

  private void invalidateWebEntry() {
    enteringWeb = false;
  }

  /**
   * 只有纯文本时直接显示原始视图（布局验收经反射调用）；仅在用户仍位于底部且跟随开启时滚动，
   * 不请求焦点或移动操作区。
   */
  private void updateLog(String text) {
    if (getView() == null || logPanel == null) return;
    logPanel.renderPlain(text);
  }

  /** 复制完整原文（与概览 / 筛选无关）；日志可能含本机鉴权地址，按敏感剪贴板写入。 */
  private void copyLogs() {
    if (logPanel == null) return;
    CharSequence logs = logPanel.plainText();
    if (logs == null || logs.length() == 0) {
      Toast.makeText(
              requireContext(),
              UiText.choose("还没有可复制的日志", "There is no log to copy yet"),
              Toast.LENGTH_SHORT)
          .show();
      return;
    }
    try {
      android.content.ClipboardManager clipboard =
          (android.content.ClipboardManager)
              requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
      if (clipboard == null) {
        Toast.makeText(
                requireContext(),
                UiText.choose("复制失败：剪贴板不可用", "Copy failed: clipboard unavailable"),
                Toast.LENGTH_SHORT)
            .show();
        return;
      }
      clipboard.setPrimaryClip(
          com.deepseekharness.app.bridge.SensitiveClipboard.text(
              UiText.choose("DSH 启动日志", "DSH startup log"), logs));
      Toast.makeText(
              requireContext(),
              UiText.choose(
                  "已复制启动日志；其中可能包含本机访问地址，请只分享给可信对象",
                  "Startup log copied. It may contain local access addresses; share only with trusted devices"),
              Toast.LENGTH_LONG)
          .show();
    } catch (Throwable failure) {
      Toast.makeText(
              requireContext(),
              UiText.format("复制失败：%s", failure.getClass().getSimpleName()),
              Toast.LENGTH_SHORT)
          .show();
    }
  }

  /** 运行环境入口（顶栏「+」气泡调用）：需要恢复时进入环境维护，否则打开启动恢复页。 */
  void openEnvironment() {
    if (!isAdded()) return;
    if (EnvironmentUiStatus.get(requireContext()).recovery) {
      startActivity(
          new Intent(requireContext(), ExtractActivity.class).putExtra("review_only", true));
      return;
    }
    showRecovery();
  }

  @Override
  public void onResume() {
    super.onResume();
    if (controller == null) return;
    invalidateWebEntry();
    controller.addReadyWebPageListener(readyChanged);
    requestStateRefresh();
  }

  @Override
  public void onPause() {
    // 页面不可见时撤销自动进入：用户离开了启动页，回来不该被"突袭"打开 Web。
    pendingAutoEnter = false;
    startRequest.incrementAndGet();
    preparingStart = false;
    if (enteringWeb && getView() != null) {
      ((TextView) getView().findViewById(R.id.launch_status))
          .setText(com.deepseekharness.app.util.UiText.text("返回后可重新进入 Web"));
    }
    invalidateWebEntry();
    ui.removeCallbacks(refreshState);
    if (controller != null) controller.removeReadyWebPageListener(readyChanged);
    if (dotPulse != null) {
      dotPulse.cancel();
      dotPulse = null;
    }
    super.onPause();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    disclosure.save(outState, "disclosure");
  }

  @Override
  public void onDestroyView() {
    startRequest.incrementAndGet();
    preparingStart = false;
    invalidateWebEntry();
    ui.removeCallbacks(refreshState);
    if (controller != null) controller.removeReadyWebPageListener(readyChanged);
    if (accessDialog != null) accessDialog.dismiss();
    accessDialog = null;
    accessBody = null;
    accessLanButton = null;
    accessCopyButton = null;
    lanAddrText = null;
    launchLog = null;
    logPanel = null;
    activityTrack = null;
    techNote = null;
    techModel = null;
    forceCandidatePending = false;
    if (dotPulse != null) {
      dotPulse.cancel();
      dotPulse = null;
    }
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
      boolean starting = preparingStart || controller.isStarting();
      boolean stopping = controller.isStopping();
      var stopResult = controller.lastStopResult();
      boolean unresolvedStop =
          controller.isUserStopped()
              && !starting
              && (stopResult != null && !stopResult.stopped()
                  || !controller.lastStopError().isEmpty());
      boolean ready = !starting && !stopping && !webEntryUrl().isEmpty();
      if (ready) startStatusMessage = "";
      boolean restoring = controller.isRestoringWebSession();
      boolean unresolved = controller.hasUnresolvedWebSession();
      com.deepseekharness.app.core.WebSessionRestoreText.Status restore =
          controller.webSessionRestoreStatus();
      com.deepseekharness.app.util.StartupTrace.Snapshot trace =
          controller.startupDiagnostics().snapshot();
      String localUrl = webEntryUrl();
      if (logPanel != null) {
        // 仅原生本机视图显示当前鉴权地址；归档与导出的诊断仍统一脱敏。
        String urlNote =
            localUrl.isEmpty()
                ? ""
                : com.deepseekharness.app.util.UiText.format("\n\n本机 Web 地址（可长按复制）：\n%s", localUrl);
        logPanel.render(
            trace,
            logOutcome(trace, starting, stopping, ready, restoring),
            (trace.log.isEmpty() ? com.deepseekharness.app.util.UiText.text("还没有日志。") : trace.log)
                + urlNote,
            urlNote.trim());
      }
      // 大标题只放短状态；未确认时的完整原因与下一步收进副标题的 ⓘ（见下方 subtitle）。
      UiMotion.crossFadeText(
          runState,
          enteringWeb
              ? com.deepseekharness.app.util.UiText.choose("打开中…", "Opening Web…")
              : restoring
                  ? com.deepseekharness.app.core.WebSessionRestoreText.progress(
                      restore.reason(), restore.attempts())
                  : unresolved
                      ? com.deepseekharness.app.core.WebSessionRestoreText.state(restore.reason())
                      : controller.isRestartBlocked()
                          ? com.deepseekharness.app.util.UiText.text("自动重启已暂停")
                          : stopping
                              ? com.deepseekharness.app.util.UiText.text("DSH 停止中…")
                              : starting
                                  ? com.deepseekharness.app.util.UiText.text("DSH 启动中…")
                                  : ready
                                      ? (trace.safe
                                              ? com.deepseekharness.app.util.UiText.text(
                                                  "基础界面已就绪 · 安全模式")
                                              : com.deepseekharness.app.util.UiText.text(
                                                  "DSH 已就绪，可进入"))
                                          + (controller.isWebCompatibilityFallback()
                                              ? com.deepseekharness.app.util.UiText.text(
                                                  " · 已切换到兼容模式")
                                              : "")
                                      : controller.isUserStopped()
                                          ? getString(R.string.ui2_stopped)
                                          : getString(R.string.ui2_stopped));
      ((TextView) root.findViewById(R.id.launch_port))
          .setText(ready ? String.valueOf(controller.getWebPort()) : "—");
      EnvironmentUiStatus.Snapshot environment = EnvironmentUiStatus.get(requireContext());
      boolean needsRecovery = environment.recovery;
      ((TextView) root.findViewById(R.id.launch_environment))
          .setText(environment.ready ? "READY" : "—");
      // 副标题只留一句；恢复核对的完整结论与下一步在折叠卡里展开（异常默认展开，B1/B4）。
      TextView subtitle = root.findViewById(R.id.launch_subtitle);
      String hint = getString(R.string.ui2_launch_hint);
      String detail = "";
      String key = "";
      if (!ready && !restore.notice().isEmpty()) {
        detail = restore.notice();
        key = "restore";
      } else if (!ready && unresolved) {
        detail = com.deepseekharness.app.core.WebSessionRestoreText.unresolved(restore.reason());
        key = "unresolved";
      }
      SummaryText.Split split = SummaryText.split(detail);
      UiMotion.crossFadeText(subtitle, detail.isEmpty() ? hint : split.summary);
      if (detail.isEmpty()) {
        DisclosureCard.bind(subtitle, null, disclosure, null);
      } else {
        DisclosureCard.Model model = new DisclosureCard.Model();
        model.key = key;
        model.detail = detail;
        model.defaultExpanded = true;
        DisclosureCard.bind(
            subtitle, model, disclosure, null, root.findViewById(R.id.launch_activity));
      }
      if (techNote != null && techModel != null) {
        techModel.detail =
            (ready
                    ? controller.isWebCompatibilityFallback()
                        ? "proot"
                        : controller.proot().runtime().id()
                    : "—")
                + " · dsh "
                + com.deepseekharness.app.util.Constants.DSH_VERSION;
        TechNote.bind(techNote, techModel, disclosure, null);
      }
      // 语境小字 / 活动条 / 工作台状态只取真实状态；不根据等待时长推算阶段。
      renderStatusChrome(
          root,
          starting,
          stopping,
          ready,
          enteringWeb,
          restoring,
          unresolved,
          controller.isRestartBlocked(),
          needsRecovery);
      root.findViewById(R.id.launch_status)
          .setVisibility(
              enteringWeb
                      || webEntryFailure
                      || starting
                      || stopping
                      || unresolvedStop
                      || !startStatusMessage.isEmpty()
                      || !trace.issues.isEmpty()
                  ? View.VISIBLE
                  : View.GONE);
      if (starting) {
        // 状态条只放一行阶段与计时；固定说明不再堆在状态条里（启动日志就在同页实时滚动）。
        String timing =
            com.deepseekharness.app.util.UiText.format(
                "%s · 本阶段 %s 秒 · 总计 %s 秒",
                trace.stage, trace.stageElapsedMs / 1000, trace.elapsedMs / 1000);
        ((TextView) root.findViewById(R.id.launch_status))
            .setText(
                trace.issues.isEmpty()
                    ? timing
                    : com.deepseekharness.app.util.UiText.format(
                        "%s\n%s", timing, com.deepseekharness.app.util.UiText.text("配置异常")));
      } else if (stopping) {
        // 停止阶段 + 已用秒数：页面每秒刷新时解释“还在等什么”，不再只有一个“停止中…”。
        String progress = controller.stopProgressText();
        ((TextView) root.findViewById(R.id.launch_status))
            .setText(
                progress.isEmpty() ? com.deepseekharness.app.util.UiText.text("停止中…") : progress);
      } else if (!startStatusMessage.isEmpty() && !enteringWeb)
        ((TextView) root.findViewById(R.id.launch_status))
            .setText(com.deepseekharness.app.util.UiStateText.render(startStatusMessage));
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
                    ? com.deepseekharness.app.util.UiText.choose("进入工作台", "Open workspace")
                    : getString(R.string.ui2_start));
        // 已运行时主操作是导航（进入工作台），用箭头；未运行时才是启动进程的播放图标。
        if (start instanceof MaterialButton)
          ((MaterialButton) start)
              .setIconResource(
                  enteringWeb || ready ? R.drawable.ic_launch_open : R.drawable.ic_launch_start);
        start.setEnabled(!enteringWeb && !starting && !stopping && !restoring);
      }
      Button restart = root.findViewById(R.id.launch_open);
      if (restart != null) restart.setEnabled(!starting && !stopping);
      Button stop = root.findViewById(R.id.launch_stop);
      if (stop != null) stop.setEnabled(!stopping);
      if (needsRecovery) {
        runState.setText(com.deepseekharness.app.util.UiText.text("需要恢复"));
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
                  "状态异常", "Run state unavailable. See operation history."));
      }
    }
  }

  /** 启动日志概览的整体结果：只取 controller 已给出的真实状态，不从日志文本推测。 */
  private com.deepseekharness.app.util.StartupLogDigest.Outcome logOutcome(
      com.deepseekharness.app.util.StartupTrace.Snapshot trace,
      boolean starting,
      boolean stopping,
      boolean ready,
      boolean restoring) {
    if (starting || restoring) return com.deepseekharness.app.util.StartupLogDigest.Outcome.RUNNING;
    if (ready) return com.deepseekharness.app.util.StartupLogDigest.Outcome.READY;
    if (trace.entries.isEmpty()) return com.deepseekharness.app.util.StartupLogDigest.Outcome.IDLE;
    // 网页就绪过（之后退出）或用户主动停止，都不算启动失败。
    if (stopping || trace.browserReady || controller.isUserStopped())
      return com.deepseekharness.app.util.StartupLogDigest.Outcome.STOPPED;
    return com.deepseekharness.app.util.StartupLogDigest.Outcome.FAILED;
  }

  private void showRecovery() {
    startActivity(new Intent(requireContext(), StartupRecoveryActivity.class));
  }

  /**
   * UI v5 方案 A 启动主页的宽窄形态：按当前窗口可用宽度决定，不按设备类型。
   *
   * <ul>
   *   <li>≥ 600dp：标题右侧显示插图；主操作与重启/停止同一行，放不下（任一按钮文字折行）时改用窄屏排列。
   *   <li>&lt; 600dp：隐藏插图；主操作独占一行铺满，重启/停止在下方等分成两枚次要按钮（带边框、
   *       居中），不再是靠左的两段裸文字。
   *   <li>快捷入口已收进顶栏「+」气泡；启动日志独占下半区，不再按宽度分栏。
   * </ul>
   */
  private void applySchemeALayout(View root) {
    boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
    View art = root.findViewById(R.id.launch_art);
    if (art != null) art.setVisibility(wide ? View.VISIBLE : View.GONE);
    LinearLayout controls = root.findViewById(R.id.launch_controls);
    View start = root.findViewById(R.id.launch_start);
    View quiet = root.findViewById(R.id.launch_quiet_controls);
    if (controls != null && start != null && quiet != null) {
      if (!wide) stackControls(root, controls, start, quiet);
      else watchControlsFit(root, controls, start, quiet);
    }
  }

  /**
   * 宽窗口下同一行放不下时（平板竖屏插图占位、大字体、较长语言或按钮带图标），任一按钮文字折行
   * 就改用窄屏的上下排列，避免「重启」被挤成竖排。只单向切换：本视图生命周期内不再切回，
   * 避免文案变化时来回跳；旋转或换宽度时视图重建，会重新判断。
   */
  private void watchControlsFit(View root, LinearLayout controls, View start, View quiet) {
    int[] ids = {R.id.launch_start, R.id.launch_open, R.id.launch_stop};
    controls.addOnLayoutChangeListener(
        new View.OnLayoutChangeListener() {
          @Override
          public void onLayoutChange(
              View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            for (int id : ids) {
              TextView button = root.findViewById(id);
              if (button == null || button.getVisibility() != View.VISIBLE) continue;
              if (button.getLineCount() > 1) {
                controls.removeOnLayoutChangeListener(this);
                // 布局回调内不能同步改参数，下一帧再切换。
                controls.post(
                    () -> {
                      if (isAdded()) stackControls(root, controls, start, quiet);
                    });
                return;
              }
            }
          }
        });
  }

  /** 主操作独占一行铺满，重启 / 停止在下方等分成两枚带边框的次要按钮。 */
  private void stackControls(View root, LinearLayout controls, View start, View quiet) {
    controls.setOrientation(LinearLayout.VERTICAL);
    controls.setGravity(android.view.Gravity.START);
    LinearLayout.LayoutParams primary =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2);
    start.setLayoutParams(primary);
    float density = getResources().getDisplayMetrics().density;
    LinearLayout.LayoutParams secondary = new LinearLayout.LayoutParams(-1, -2);
    secondary.topMargin = Math.round(10 * density);
    quiet.setLayoutParams(secondary);
    int[] ids = {R.id.launch_open, R.id.launch_stop};
    for (int i = 0; i < ids.length; i++) {
      TextView button = root.findViewById(ids[i]);
      if (button == null) continue;
      LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
      if (i > 0) half.setMarginStart(Math.round(10 * density));
      button.setLayoutParams(half);
      button.setBackgroundResource(R.drawable.bg_btn);
      button.setMinHeight(
          getResources().getDimensionPixelSize(R.dimen.shell_button_secondary_height));
    }
  }

  /**
   * 把真实运行状态映射为语境小字、活动条与工作台状态。
   *
   * <p>只使用 controller / 环境检查已给出的结果；等待本身不产生新状态，也不显示阶段进度。
   */
  private void renderStatusChrome(
      View root,
      boolean starting,
      boolean stopping,
      boolean ready,
      boolean entering,
      boolean restoring,
      boolean unresolved,
      boolean restartBlocked,
      boolean needsRecovery) {
    int tone;
    String context;
    if (needsRecovery) {
      context = UiText.choose("运行环境需要恢复", "Runtime environment · needs recovery");
      tone = R.color.shell_bad;
    } else if (unresolved) {
      context = UiText.choose("Web 实例 · 待确认", "Web instance · unconfirmed");
      tone = R.color.shell_bad;
    } else if (restartBlocked) {
      context = UiText.choose("自动重启已暂停", "Automatic restart · paused");
      tone = R.color.shell_warn;
    } else if (entering) {
      context = UiText.choose("本机工作台 · 正在进入", "Local workspace · opening");
      tone = R.color.shell_accent;
    } else if (restoring) {
      context = UiText.choose("Web 实例 · 正在核对", "Web instance · verifying");
      tone = R.color.shell_accent;
    } else if (stopping) {
      context = UiText.choose("本机工作台 · 正在停止", "Local workspace · stopping");
      tone = R.color.shell_accent;
    } else if (starting) {
      context = UiText.choose("本机工作台 · 正在启动", "Local workspace · starting");
      tone = R.color.shell_accent;
    } else if (ready) {
      context = UiText.choose("本机工作台 · 已就绪", "Local workspace · ready");
      tone = R.color.shell_good;
    } else {
      context = UiText.choose("本机工作台 · 未运行", "Local workspace · not running");
      tone = R.color.shell_text_secondary;
    }
    TextView eyebrow = root.findViewById(R.id.launch_eyebrow);
    if (eyebrow != null) eyebrow.setText(context);
    TextView dot = root.findViewById(R.id.launch_run_dot);
    if (dot != null) dot.setTextColor(requireContext().getColor(tone));
    pulseDot(dot, starting || stopping || entering || restoring);
    if (activityTrack != null) {
      int track;
      if (needsRecovery || unresolved || restartBlocked) track = ShellActivityTrack.ERROR;
      else if (starting || stopping || entering || restoring) track = ShellActivityTrack.RUNNING;
      else if (ready) track = ShellActivityTrack.SETTLED;
      else track = ShellActivityTrack.IDLE;
      activityTrack.setState(track);
    }
    TextView workbench = root.findViewById(R.id.launch_workbench);
    if (workbench != null) {
      String value;
      int valueTone;
      if (needsRecovery) {
        value = UiText.choose("需恢复", "Repair");
        valueTone = R.color.shell_bad;
      } else if (unresolved) {
        value = UiText.choose("待确认", "Pending");
        valueTone = R.color.shell_bad;
      } else if (entering) {
        value = UiText.choose("进入中…", "Opening");
        valueTone = R.color.shell_good;
      } else if (restoring) {
        value = UiText.choose("核对中", "Check");
        valueTone = R.color.shell_accent;
      } else if (stopping) {
        value = UiText.choose("停止中…", "Stop");
        valueTone = R.color.shell_accent;
      } else if (starting) {
        value = UiText.choose("启动中", "Start");
        valueTone = R.color.shell_accent;
      } else if (ready) {
        value = UiText.choose("可进入", "Ready");
        valueTone = R.color.shell_good;
      } else {
        value = UiText.choose("未运行", "Stopped");
        valueTone = R.color.shell_text;
      }
      workbench.setText(value);
      workbench.setTextColor(requireContext().getColor(valueTone));
    }
  }

  /** 启动/停止/进入/核对中让状态点缓慢呼吸；静默与已就绪保持静止。 */
  private void pulseDot(TextView dot, boolean active) {
    if (dotPulse != null) {
      dotPulse.cancel();
      dotPulse = null;
    }
    if (dot == null) return;
    dot.setAlpha(1f);
    if (!active || !UiMotion.animationsEnabled(requireContext())) return;
    dotPulse = android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.35f, 1f);
    dotPulse.setDuration(1500L);
    dotPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
    dotPulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
    dotPulse.start();
  }

  /**
   * 用户停止入口：一次点击只入队一个停止；结论回调只发一次。
   *
   * <p>进行中的重复点击由控制器合并（只回一条“停止中…”解释）；这里额外要求停止队列已收口才允许
   * 升级强停，避免重复点击在同一个停止上反复准备候选。
   */
  private void requestStop(Activity activity, View root, TextView status) {
    startRequest.incrementAndGet();
    preparingStart = false;
    startStatusMessage = "";
    FirstRunWebStart.cancel(requireContext());
    invalidateWebEntry();
    controller.stopWeb(
        msg -> {
          long generation = controller.getWebGeneration();
          activity.runOnUiThread(
              () -> {
                if (getView() != root || generation != controller.getWebGeneration()) return;
                status.setText(com.deepseekharness.app.util.UiStateText.render(msg));
                refreshRunState();
                refreshLanAddr();
                var stopped = controller.lastStopResult();
                if (!controller.isStopping()
                    && (stopped != null && !stopped.stopped()
                        || !controller.lastStopError().isEmpty()))
                  offerForceStop(activity, root, status, generation);
              });
        });
    webReady = false;
    Button start = root.findViewById(R.id.launch_start);
    if (start != null) start.setText(com.deepseekharness.app.util.UiText.text("启动"));
    status.setText(com.deepseekharness.app.util.UiText.text("停止中…"));
    refreshLanAddr();
    refreshRunState();
  }

  private void offerForceStop(Activity activity, View root, TextView status, long generation) {
    if (forceCandidatePending) return;
    long now = android.os.SystemClock.elapsedRealtime();
    if (forceCandidateCheckedAtMs > 0
        && now - forceCandidateCheckedAtMs
            < com.deepseekharness.app.util.WebStopBudget.FORCE_RETRY_MILLIS) {
      // 一轮停止只准备一次候选：刚核验过就只提示等待，不重复起线程、不重复写诊断（日志里曾 9 连发）。
      status.setText(com.deepseekharness.app.core.WebForceStopText.waitBeforeRetry());
      return;
    }
    forceCandidatePending = true;
    forceCandidateCheckedAtMs = now;
    final long consentRevision =
        new com.deepseekharness.app.core.DeviceGrants(activity).consentPolicy().revision();
    // 进程与文件身份核验放在工作线程；本次候选只捕获一次，由对话框局部持有至确认。
    new Thread(
            () -> {
              HarnessController.ForceStopRequest request = null;
              com.deepseekharness.app.util.WebStopDiagnostic diagnostic = null;
              try {
                request = controller.prepareForceStop();
              } catch (java.io.IOException denied) {
                diagnostic =
                    denied instanceof com.deepseekharness.app.util.WebStopException
                        ? ((com.deepseekharness.app.util.WebStopException) denied).diagnostic()
                        : com.deepseekharness.app.util.WebStopDiagnostic.failure(
                            com.deepseekharness.app.util.WebStopDiagnostic.Code
                                .FORCE_EVIDENCE_UNCONFIRMED,
                            denied);
                com.deepseekharness.app.core.DiagnosticLog.record(
                    activity.getApplicationContext(),
                    "WEB_FORCE_STOP_CANDIDATE",
                    diagnostic.code().name());
              }
              final HarnessController.ForceStopRequest candidate = request;
              final com.deepseekharness.app.util.WebStopDiagnostic deniedEvidence = diagnostic;
              ui.post(
                  () -> {
                    forceCandidatePending = false;
                    if (consentRevision
                            != new com.deepseekharness.app.core.DeviceGrants(activity)
                                .consentPolicy()
                                .revision()
                        || getView() != root
                        || !isResumed()
                        || activity.isFinishing()
                        || activity.isDestroyed()
                        || generation != controller.getWebGeneration()) return;
                    if (deniedEvidence != null) {
                      showStopFailureDialog(
                          activity,
                          root,
                          status,
                          generation,
                          com.deepseekharness.app.core.WebForceStopText.unavailable(deniedEvidence),
                          true);
                      return;
                    }
                    if (candidate == null) {
                      showStopFailureDialog(
                          activity,
                          root,
                          status,
                          generation,
                          com.deepseekharness.app.core.WebForceStopText.noCandidate(),
                          false);
                      return;
                    }
                    showForceStopDialog(
                        activity, root, status, generation, candidate, consentRevision);
                  });
            },
            "web-force-stop-candidate")
        .start();
  }

  /** 证据不足时的三要素说明 + “等待后重试”入口；不发送任何信号，也不放宽核验条件。 */
  private void showStopFailureDialog(
      Activity activity,
      View root,
      TextView status,
      long generation,
      String message,
      boolean retryable) {
    if (getContext() == null || !isResumed()) return;
    status.setText(shortLine(message, com.deepseekharness.app.util.UiText.text("停止未完成")));
    DshaDialogBuilder builder = new DshaDialogBuilder(requireContext(), true);
    builder.setTitle(com.deepseekharness.app.util.UiText.text("停止未完成"));
    builder.setMessage(message);
    builder.setNegativeButton(android.R.string.cancel, null);
    if (retryable)
      builder.setPositiveButton(
          com.deepseekharness.app.core.WebForceStopText.retryButton(),
          (dialog, which) ->
              root.postDelayed(
                  () -> {
                    if (getView() != root
                        || !isResumed()
                        || activity.isFinishing()
                        || activity.isDestroyed()
                        || generation != controller.getWebGeneration()) return;
                    offerForceStop(activity, root, status, generation);
                  },
                  com.deepseekharness.app.util.WebStopBudget.FORCE_RETRY_MILLIS));
    else
      builder.setPositiveButton(
          com.deepseekharness.app.util.UiText.choose("重试", "Retry"),
          (dialog, which) -> requestStop(activity, root, status));
    builder.show();
  }

  private static String shortLine(String message, String fallback) {
    if (message == null || message.isBlank()) return fallback;
    int line = message.indexOf('\n');
    String head = line < 0 ? message : message.substring(0, line);
    return head.length() > 160 ? head.substring(0, 157) + "…" : head;
  }

  private void showForceStopDialog(
      Activity activity,
      View root,
      TextView status,
      long generation,
      HarnessController.ForceStopRequest candidate,
      long consentRevision) {
    boolean previous =
        candidate.candidate() != null && candidate.candidate().retiresPreviousLauncher();
    new DshaDialogBuilder(requireContext(), true)
        .setTitle(
            com.deepseekharness.app.util.UiText.text(previous ? "放弃上一版 Web 并强制结束？" : "强制结束本次 Web？"))
        .setMessage(
            previous
                ? com.deepseekharness.app.util.UiText.text(
                    "普通停止未确认上一版退出。强制结束可能中断写入。\n\n确认后先结束旧进程；身份变化会拒绝执行；取消会保留现场。")
                : com.deepseekharness.app.util.UiText.text(
                    "普通停止未确认退出。强制结束可能中断写入。\n\n确认后只结束已核验进程；取消会保留现场。"))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(
            com.deepseekharness.app.util.UiText.text("强制结束"),
            (dialog, which) -> {
              if (consentRevision
                      != new com.deepseekharness.app.core.DeviceGrants(activity)
                          .consentPolicy()
                          .revision()
                  || getView() != root
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
                                    || generation != controller.getWebGeneration()) return;
                                status.setText(
                                    com.deepseekharness.app.util.UiStateText.render(message));
                                refreshRunState();
                                refreshLanAddr();
                              }));
              if (!accepted)
                status.setText(
                    com.deepseekharness.app.util.UiText.text("Web 状态或进程身份已变化，本次强制结束未执行。请重新停止后确认。"));
              else status.setText(com.deepseekharness.app.util.UiText.text("正在强制结束本次已核验的 Web…"));
            })
        .show();
  }

  /** Snapshot settings, actual listener generation and current interfaces independently of the view. */
  LanAccessPresentation lanAccess() {
    boolean enabled = controller.config().isLanMode();
    boolean permission =
        com.deepseekharness.app.bridge.LocalNetworkAccess.granted(requireContext());
    long generation = controller.getWebGeneration();
    boolean authenticated = LanProxyService.hasDshAuth(generation);
    int port = LanProxyService.port(generation);
    LanAddressPolicy.Discovery discovery =
        enabled && permission && authenticated && port > 0
            ? LanAddressPolicy.currentDiscovery()
            : null;
    return LanAccessPresentation.capture(
        enabled, permission, generation, authenticated, port, discovery);
  }

  /** LAN status remains visible when disabled; opening this view never enables a listener. */
  private void refreshLanAddr() {
    if (lanAddrText == null || !isAdded() || controller == null) return;
    LanAccessPresentation access = lanAccess();
    lanAddrText.setText(lanStatusForDisplay(access));
    lanAddrText.setOnClickListener(v -> showAccessAddresses());
    lanAddrText.setVisibility(View.VISIBLE);
    renderAccessAddresses(access);
  }

  void showAccessAddresses() {
    if (!isAdded() || controller == null) return;
    if (accessDialog != null) accessDialog.dismiss();
    View content = getLayoutInflater().inflate(R.layout.dialog_access_address, null);
    accessBody = content.findViewById(R.id.access_body);
    accessLanButton = content.findViewById(R.id.access_lan_toggle);
    accessCopyButton = content.findViewById(R.id.access_copy);
    Button closeButton = content.findViewById(R.id.access_close);
    closeButton.setText(UiText.choose("关闭", "Close"));
    var dialog =
        new DshaDialogBuilder(requireContext())
            .setTitle(R.string.ui2_address)
            .setView(content)
            .create();
    accessDialog = dialog;
    closeButton.setOnClickListener(ignored -> dialog.dismiss());
    accessCopyButton.setOnClickListener(ignored -> copyAddressNow());
    accessLanButton.setOnClickListener(ignored -> onLanToggle());
    dialog.setOnDismissListener(
        ignored -> {
          if (accessDialog == dialog) {
            accessDialog = null;
            accessBody = null;
            accessLanButton = null;
            accessCopyButton = null;
          }
        });
    dialog.show();
    renderAccessAddresses(lanAccess());
  }

  private void renderAccessAddresses(LanAccessPresentation access) {
    var dialog = accessDialog;
    if (dialog == null || !dialog.isShowing() || !isAdded() || accessBody == null) return;
    String local = webEntryUrl();
    accessBody.setText(
        UiText.format(
            UiText.choose("本机访问\n%s\n\n%s", "On this phone\n%s\n\n%s"),
            local.isEmpty()
                ? UiText.choose("启动就绪后显示本机地址", "Start DSH and wait for its local URL.")
                : local,
            lanStatusForDisplay(access)));
    if (accessLanButton != null)
      accessLanButton.setText(
          controller.config().isLanMode()
              ? UiText.choose("关闭局域网", "Disable LAN")
              : UiText.choose("开启局域网", "Enable LAN"));
    if (accessCopyButton != null) {
      boolean lanReady = access.state == LanAccessPresentation.State.READY;
      accessCopyButton.setText(
          lanReady
              ? UiText.choose("复制链接", "Copy LAN link")
              : UiText.choose("复制本机地址", "Copy local URL"));
      accessCopyButton.setEnabled(lanReady || !local.isEmpty());
    }
  }

  /** 局域网开关：开启走既有确认流程；关闭时立刻停监听并撤销既有链接。 */
  private void onLanToggle() {
    if (!isAdded() || controller == null) return;
    if (!controller.config().isLanMode()) {
      onLanAction(lanAccess());
      return;
    }
    new DshaDialogBuilder(requireContext())
        .setTitle(UiText.choose("关闭局域网访问？", "Disable LAN access?"))
        .setMessage(
            UiText.choose(
                "关闭后，其它设备将无法再通过局域网访问本轮 DSH，已发出的连接链接立即失效。本机访问不受影响。",
                "Other devices lose LAN access to this DSH run and existing links stop working immediately. On-phone access is unaffected."))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(
            UiText.choose("关闭", "Disable"),
            (dialog, which) -> {
              if (!isAdded() || controller == null) return;
              controller.config().setLanMode(false);
              try {
                com.deepseekharness.app.LanProxyService.stopLanListener();
                com.deepseekharness.app.HarnessService.refreshPowerMode(requireContext());
              } catch (RuntimeException ignored) {
              }
              refreshLanAddr();
            })
        .show();
  }

  /** 复制当前可用地址：局域网就绪时复制带凭据链接，否则复制本机链接。 */
  private void copyAddressNow() {
    if (!isAdded() || controller == null) return;
    LanAccessPresentation access = lanAccess();
    long generation = controller.getWebGeneration();
    if (access.state == LanAccessPresentation.State.READY && !access.services.isEmpty()) {
      String service = access.services.get(0);
      String token = LanProxyService.boundLanToken(generation);
      if (!token.isEmpty() && access.stillOffers(generation, service)) {
        copyAddr(UiText.choose("局域网地址", "LAN address"), service + "?token=" + token);
        return;
      }
      connectionChanged();
      return;
    }
    String local = webEntryUrl();
    if (!local.isEmpty()) {
      copyAddr("DSH", local);
      return;
    }
    Toast.makeText(
            requireContext(),
            UiText.choose(
                "暂时没有可复制的地址，请等待 DSH 就绪。", "No address to copy yet; wait for DSH to be ready."),
            Toast.LENGTH_SHORT)
        .show();
  }

  private boolean needsLanPassword(LanAccessPresentation access) {
    return access.state != LanAccessPresentation.State.DISABLED
        && !controller.config().hasLanPassword();
  }

  private String lanStatusForDisplay(LanAccessPresentation access) {
    if (needsLanPassword(access)) return getString(R.string.lan_password_required_launch);
    if (access.state == LanAccessPresentation.State.READY)
      return getString(R.string.lan_address_overview, String.join("\n", access.services));
    return lanStatus(access);
  }

  static String lanStatus(LanAccessPresentation access) {
    return switch (access.state) {
      case DISABLED ->
          UiText.choose(
              "局域网访问：未开启。可在此手动开启，供同一可信网络的设备连接。",
              "LAN access: off. Enable it here to connect devices on the same trusted network.");
      case PERMISSION_REQUIRED ->
          UiText.choose(
              "局域网访问已开启，但未获得系统本地网络权限；本机访问仍可使用。",
              "LAN access is enabled, but the system local network permission is missing. Local access is still available.");
      case WAITING_AUTH ->
          UiText.choose(
              "局域网访问已开启，正在等待本轮 DSH 就绪并完成认证。",
              "LAN access is enabled. Waiting for this DSH run to be ready and authenticated.");
      case LISTENER_UNAVAILABLE ->
          UiText.choose(
              "局域网访问已开启，但本轮代理尚未成功监听。可重试局域网连接；仍失败时查看启动诊断。",
              "LAN access is enabled, but its listener is not ready. Retry LAN access or check startup diagnostics if it still fails.");
      case DISCOVERY_DENIED ->
          UiText.choose(
              "局域网代理已监听，但系统拒绝读取网络地址。请检查应用的网络权限后刷新。",
              "The LAN listener is ready, but the system denied access to network addresses. Check app network permissions and refresh.");
      case DISCOVERY_FAILED ->
          UiText.choose(
              "局域网代理已监听，但暂时无法读取网络接口。请检查 Wi-Fi 或以太网连接后刷新。",
              "The LAN listener is ready, but network interfaces could not be read. Check Wi-Fi or Ethernet and refresh.");
      case IPV6_ONLY ->
          UiText.choose(
              "局域网代理已监听，检测到 IPv6，但没有可分享的私有 IPv4 地址；此代理使用 IPv4。请连接提供私有 IPv4 地址的 Wi-Fi 或以太网。",
              "The LAN listener is ready. IPv6 was detected, but no shareable private IPv4 address is available. This proxy uses IPv4. Connect to Wi-Fi or Ethernet that provides private IPv4.");
      case NO_ADDRESS ->
          UiText.choose(
              "局域网代理已监听，但没有可分享的 Wi-Fi 或以太网私有 IPv4 地址。请连接局域网后刷新；VPN 或移动数据地址不会作为局域网链接。",
              "The LAN listener is ready, but no shareable Wi-Fi or Ethernet private IPv4 address is available. Connect to a LAN and refresh. VPN and cellular addresses are excluded.");
      case READY -> String.join("\n", access.services);
    };
  }

  private static String lanActionLabel(LanAccessPresentation.State state) {
    return switch (state) {
      case DISABLED -> UiText.choose("开启局域网访问", "Enable LAN access");
      case PERMISSION_REQUIRED -> UiText.choose("允许本地网络", "Allow local network");
      case LISTENER_UNAVAILABLE -> UiText.choose("重试局域网连接", "Retry LAN access");
      case READY -> UiText.choose("局域网连接链接", "LAN connection links");
      default -> UiText.choose("刷新地址", "Refresh addresses");
    };
  }

  private void onLanAction(LanAccessPresentation access) {
    if (!isAdded()) return;
    if (needsLanPassword(access)) {
      if (accessDialog != null) accessDialog.dismiss();
      UiMotion.page(requireContext(), getParentFragmentManager().beginTransaction())
          .addToBackStack(null)
          .replace(R.id.fragment_container, new ConfigFragment())
          .commit();
    } else if (access.state == LanAccessPresentation.State.DISABLED) {
      new DshaDialogBuilder(requireContext())
          .setTitle(UiText.choose("开启局域网访问？", "Enable LAN access?"))
          .setMessage(R.string.lan_enable_password_notice)
          .setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(
              UiText.choose("开启", "Enable"),
              (dialog, which) -> {
                if (!isAdded() || getView() == null || !isResumed()) return;
                controller.config().setLanMode(true);
                requestLanAccess();
                refreshLanAddr();
              })
          .show();
    } else if (access.state == LanAccessPresentation.State.PERMISSION_REQUIRED
        || access.state == LanAccessPresentation.State.LISTENER_UNAVAILABLE) {
      requestLanAccess();
      refreshLanAddr();
    } else if (access.state == LanAccessPresentation.State.READY) {
      if (access.services.size() == 1) showLanAddress(access.services.get(0));
      else
        new DshaDialogBuilder(requireContext())
            .setTitle(UiText.choose("选择局域网地址", "Choose a LAN address"))
            .setItems(
                access.services.toArray(new String[0]),
                (dialog, which) -> showLanAddress(access.services.get(which)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    } else refreshLanAddr();
  }

  private void requestLanAccess() {
    if (!isAdded() || !controller.config().isLanMode()) return;
    try {
      com.deepseekharness.app.HarnessService.ensureLanForeground(requireContext());
      if (getActivity() instanceof MainActivity)
        ((MainActivity) getActivity()).requestLocalNetwork();
      else
        com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(requireContext());
    } catch (RuntimeException failure) {
      Toast.makeText(
              requireContext(),
              UiText.choose(
                  "无法开启局域网连接，请检查系统权限和启动诊断后重试。",
                  "LAN access could not start. Check system permissions and startup diagnostics, then retry."),
              Toast.LENGTH_LONG)
          .show();
    }
  }

  private void showLanAddress(String service) {
    if (!isAdded()) return;
    final long generation = controller.getWebGeneration();
    if (!lanAccess().stillOffers(generation, service)) {
      connectionChanged();
      return;
    }
    new DshaDialogBuilder(requireContext())
        .setTitle(UiText.choose("局域网地址", "LAN address"))
        .setMessage(getString(R.string.lan_address_detail, service))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(
            UiText.choose("复制地址", "Copy address"),
            (dialog, which) -> {
              if (!isAdded()
                  || generation != controller.getWebGeneration()
                  || !controller.config().hasLanPassword()
                  || !lanAccess().stillOffers(generation, service)) {
                connectionChanged();
                return;
              }
              copyAddr(UiText.choose("局域网地址", "LAN address"), service);
            })
        .show();
  }

  private void connectionChanged() {
    if (isAdded())
      Toast.makeText(
              requireContext(),
              UiText.choose(
                  "连接已变化，请重新查看链接。", "The connection changed. View its current link again."),
              Toast.LENGTH_LONG)
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
