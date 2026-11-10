package com.deepseekharness.app.util;

/**
 * ADB 后台探活的避让策略（实验性，T25 尽力性修复）。
 *
 * <p>探活期间持有环境任务独占租约（最长约 60 秒），此时 Web 自动启动会被拒绝，入口门禁也只能等待。
 * 屏幕点亮 / 解锁正是用户重新打开 DSHA 的时刻，因此：
 *
 * <ul>
 *   <li>屏幕触发的探活先延后 {@link #SCREEN_DELAY_MS}，让入口检查先完成；
 *   <li>主界面入口检查进行中（开机加载页可见）时，任何非用户触发的探活都延后重试；
 *   <li>入口窗口超过 {@link #ENTRY_WINDOW_MAX_MS} 视为过期，单次探活累计避让不超过
 *       {@link #MAX_DEFER_MS}，保证保活不会被无限推迟。
 * </ul>
 *
 * <p>只调整调度时机，不改变门禁、租约或探活本身的语义。纯逻辑，不依赖 Android。
 */
public final class AdbProbeDeferral {
  /** 屏幕点亮 / 解锁后首次探活前的等待。 */
  public static final long SCREEN_DELAY_MS = 8_000L;

  /** 入口检查进行中时，每次避让后的重试间隔。 */
  public static final long RETRY_MS = 2_000L;

  /** 单个入口窗口的最长有效期；Activity 未正常收尾时按此自动过期。 */
  public static final long ENTRY_WINDOW_MAX_MS = 45_000L;

  /** 单次探活请求的累计避让上限，到达后照常探活。 */
  public static final long MAX_DEFER_MS = 30_000L;

  private AdbProbeDeferral() {}

  /**
   * 入口窗口是否仍在生效。
   *
   * @param entryStartedAt 最近一次入口检查开始的单调时钟；0 表示没有进行中的入口检查
   */
  public static boolean entryActive(long entryStartedAt, long now) {
    return entryStartedAt > 0
        && now >= entryStartedAt
        && now - entryStartedAt < ENTRY_WINDOW_MAX_MS;
  }

  /**
   * 本次探活现在应延后多久；返回 0 表示立即执行。
   *
   * @param firstRequestedAt 本次探活请求第一次到达的单调时钟
   * @param entryStartedAt 进行中入口检查的开始时钟；0 表示没有
   */
  public static long delayMs(long firstRequestedAt, long entryStartedAt, long now) {
    if (now - firstRequestedAt >= MAX_DEFER_MS) return 0L;
    if (!entryActive(entryStartedAt, now)) return 0L;
    return Math.min(RETRY_MS, MAX_DEFER_MS - (now - firstRequestedAt));
  }
}
