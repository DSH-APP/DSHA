package com.deepseekharness.app.util;

import java.util.List;

/**
 * 停止等待的共享预算与“是否仍在退出”判定（纯逻辑，不访问 Android、文件系统或时钟）。
 *
 * <p>宿主过去只等 3 秒就宣布“退出未确认”，而内置 dsh 自己在收到 SIGTERM 后的优雅退出强制上限是
 * 5 秒；3 秒必然把正常关闭误判成未确认，随后启动流程又把这段“正在退出”当成失败。这里给出一个
 * 覆盖 5 秒上限并留出余量的统一预算：普通停止在 SIGTERM 后按同一出生身份核验到预算尽头，启动前
 * 置停止遇到“仍在退出”时在同一个窗口内只读复核，不重复发送信号。
 */
public final class WebStopBudget {
  /** 普通停止在 SIGTERM 后继续核验退出的最长时间（覆盖 dsh 的 5s 强制退出上限并留余量）。 */
  public static final long EXIT_CONFIRM_MILLIS = 6_500;

  /** 进程核验瞬时读取失败的重试窗口；它不是退出预算，读不了不等于已经退出。 */
  public static final long INSPECTION_RETRY_MILLIS = 3_000;

  /** 启动前置停止的整段预算：停止 + 旧启动器 + 只读复核，同一窗口内完成。 */
  public static final long STARTUP_EXIT_MILLIS = 8_000;

  /** 只读复核轮询间隔；不按 50ms 对整张 /proc 反复做严格核验。 */
  public static final long POLL_MILLIS = 250;

  /** 强停候选证据不可得时，向用户提供的“等待后重试”间隔。 */
  public static final long FORCE_RETRY_MILLIS = 15_000;

  /** 优雅窗口结束后，对同一出生身份的受管 proroot bridge 先发 SIGQUIT 的确认窗口。 */
  public static final long ESCALATION_QUIT_MILLIS = 1_500;

  /** SIGQUIT 后仍存活时再发 SIGKILL 的确认窗口。 */
  public static final long ESCALATION_KILL_MILLIS = 2_000;

  /** 升级信号前后的双次核验间隔（给内核一个稳定的观察窗口，不改变身份判据）。 */
  public static final long ESCALATION_VERIFY_PAUSE_MILLIS = 50;

  private WebStopBudget() {}

  /**
   * 这些停止事实表示进程或启动器可能仍在退出/暂时不可读，值得在同一窗口内只读复核。
   *
   * <p>明确的身份变化、拒绝发信号、记录异常等是终态，不再等待：继续等不会让证据变好，只会让
   * “停止”看起来卡住。空列表表示停止已确认，不需要等待。
   */
  public static boolean mayStillExit(List<WebStopDiagnostic> facts) {
    if (facts == null || facts.isEmpty()) return false;
    for (WebStopDiagnostic fact : facts) {
      if (fact == null) return false;
      switch (fact.code()) {
        case OWNED_WEB_REMAINS,
            OWNED_LAUNCHER_PENDING,
            PENDING_EXIT,
            EXIT_UNCONFIRMED,
            PRE_EXEC_WAIT,
            OWNED_WEB_SCAN_UNCONFIRMED,
            INSPECTION_UNCONFIRMED,
            TRIAL_UNCONFIRMED -> {}
        default -> {
          return false;
        }
      }
    }
    return true;
  }

  /** 从 {@code startedAtMs} 起算的同一窗口还剩多少毫秒；已过期为 0。 */
  public static long remaining(long startedAtMs, long nowMs, long windowMs) {
    if (windowMs <= 0) return 0;
    long elapsed = nowMs - startedAtMs;
    if (elapsed < 0) return windowMs;
    return Math.max(0, windowMs - elapsed);
  }
}
