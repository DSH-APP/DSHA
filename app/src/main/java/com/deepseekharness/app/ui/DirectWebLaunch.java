package com.deepseekharness.app.ui;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.UiStateText;
import com.deepseekharness.app.util.UiText;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 桌面图标直启 Web：入口门禁放行后，在开机加载页上等待鉴权链接，就绪即打开 Web。
 *
 * <p>单一职责：只编排“等待 → 打开”。启动请求走与启动页按钮相同的可见启动保护
 * （{@link FirstRunWebStart#enqueueVisible}），停止 / 维护 / 端口与鉴权仍由原有协作者负责。
 *
 * <ul>
 *   <li>等待鉴权不设强制时限，60 秒后只补一句提示；
 *   <li>启动未被接受、启动恢复被登记或启动结束仍无鉴权链接时，交还主界面显示真实状态，不自动重试；
 *   <li>只有 Activity 处于前台时才打开 Web，避免后台启动 Activity 被系统拒绝后状态不明。
 * </ul>
 */
final class DirectWebLaunch {
  private static final long POLL_MS = 400L;
  private static final long SLOW_HINT_MS = 60_000L;

  /** 启动请求被接受后，连续多少次观察到“既不在启动、也无链接”才判定启动已结束。 */
  private static final int SETTLED_CHECKS = 5;

  private static final Executor START_IO = Executors.newSingleThreadExecutor();

  private final FragmentActivity activity;
  private final BootScreen screen;
  private final Runnable fallback;
  private final Runnable opened;
  private final HarnessController controller;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final long startedAt = SystemClock.elapsedRealtime();
  private boolean active;
  private boolean requested;
  private boolean accepted;
  private int idleChecks;
  private String startStatus = "";

  // The controller publishes on the main thread after finishing the current start.
  // Only readiness accelerates navigation; other state events must not advance the
  // poll's settled checks or create another startup request.
  private final Runnable readyChanged = this::openReadyWeb;

  private final Runnable poll =
      new Runnable() {
        @Override
        public void run() {
          if (!active) return;
          step();
          if (active) ui.postDelayed(this, POLL_MS);
        }
      };

  /**
   * @param fallback 放弃直启、显示主界面
   * @param opened Web 已打开后的收尾（主界面在 Web 之下完成初始化）
   */
  DirectWebLaunch(
      FragmentActivity activity, BootScreen screen, Runnable fallback, Runnable opened) {
    this.activity = activity;
    this.screen = screen;
    this.fallback = fallback;
    this.opened = opened;
    this.controller = HarnessController.get(activity);
  }

  void begin() {
    if (active) return;
    active = true;
    controller.addReadyWebPageListener(readyChanged);
    screen.offerMain(this::abandon);
    screen.setStatus(UiText.choose("正在进入 Web…", "Opening Web…"));
    ui.post(poll);
  }

  boolean active() {
    return active;
  }

  /** 用户选择主界面、或 Main 收到其它目的地：停止等待，已提交的启动继续由运行时负责。 */
  void abandon() {
    if (!active) return;
    cancel();
    fallback.run();
  }

  /** 只停止本协作者的等待；不停止 Web，也不撤回已提交的启动请求。 */
  void cancel() {
    active = false;
    ui.removeCallbacks(poll);
    controller.removeReadyWebPageListener(readyChanged);
  }

  private void openReadyWeb() {
    if (!active || activity.isFinishing() || activity.isDestroyed()) return;
    String url = controller.getWebAuthUrl();
    if (url != null && !url.isEmpty()) open(url);
  }

  private void step() {
    if (activity.isFinishing() || activity.isDestroyed()) {
      cancel();
      return;
    }
    String url = controller.getWebAuthUrl();
    if (url != null && !url.isEmpty()) {
      open(url);
      return;
    }
    if (controller.config().isStartupRecoveryRequested()) {
      // 启动失败已登记：主界面的恢复流程负责展示原因与选项。
      abandon();
      return;
    }
    boolean busy = controller.isStarting() || controller.isStopping();
    if (!requested && !busy) {
      if (!resumed()) return; // 可见启动保护必须在前台建立。
      requestStart();
      return;
    }
    if (accepted && !busy) {
      if (++idleChecks >= SETTLED_CHECKS) {
        abandon();
        return;
      }
    } else idleChecks = 0;
    renderStatus(busy);
  }

  private void requestStart() {
    requested = true;
    boolean queued =
        FirstRunWebStart.enqueueVisible(
            activity,
            START_IO,
            false,
            false,
            message ->
                ui.post(
                    () -> {
                      startStatus = message == null ? "" : message;
                      if (active) renderStatus(true);
                    }),
            submitted -> {
              if (!active) return;
              if (!submitted) abandon();
              else accepted = true;
            });
    if (!queued) abandon();
  }

  private void renderStatus(boolean busy) {
    // 超过 60 秒只换成完整的提示句，不拼接翻译片段；仍不终止启动。
    if (SystemClock.elapsedRealtime() - startedAt >= SLOW_HINT_MS) {
      screen.setStatus(
          UiText.choose(
              "启动较慢，仍在等待鉴权；可进入主界面查看日志。",
              "Startup is slow and still waiting for authentication. Open the main screen to view logs."));
      return;
    }
    screen.setStatus(
        !startStatus.isEmpty()
            ? UiStateText.render(startStatus)
            : busy
                ? UiText.choose("正在启动 Web…", "Starting Web…")
                : UiText.choose("正在进入 Web…", "Opening Web…"));
  }

  private void open(String url) {
    if (!resumed()) return; // 回到前台后下一次轮询再打开。
    cancel();
    try {
      activity.startActivity(WebPreviewActivity.intent(activity, url, null));
    } catch (RuntimeException unavailable) {
      android.util.Log.w("DSHA", "DIRECT_WEB_OPEN: " + unavailable.getClass().getSimpleName());
      fallback.run();
      return;
    }
    opened.run();
  }

  private boolean resumed() {
    return activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
  }
}
