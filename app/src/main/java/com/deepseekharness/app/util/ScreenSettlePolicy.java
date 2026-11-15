package com.deepseekharness.app.util;

/**
 * 动作后事件驱动稳定的纯判定；无 Android 依赖。
 *
 * <p>判定只用同一单调时钟的毫秒值：动作派发后最多等 {@code firstEventMillis} 等第一个无障碍事件（期间没有事件就认为界面
 * 没有变化，直接结束）；出现事件后等 {@code quietMillis} 的静默窗口；整段等待不超过 {@code totalMillis}。上限是硬
 * 约束：界面持续动画时如实返回超时，不把“没等到静默”说成“没有变化”。
 */
public final class ScreenSettlePolicy {
  /** 默认预算取自“首个事件 ≤800ms、静默 250ms、总上限 2.5s”的产品约束。 */
  public static final Config DEFAULT = new Config(800L, 250L, 2_500L);

  private ScreenSettlePolicy() {}

  public record Config(long firstEventMillis, long quietMillis, long totalMillis) {
    public Config {
      if (firstEventMillis <= 0
          || quietMillis <= 0
          || totalMillis <= 0
          || firstEventMillis > totalMillis
          || quietMillis > totalMillis)
        throw new IllegalArgumentException("SCREEN_SETTLE_CONFIG_INVALID");
    }
  }

  public enum State {
    WAITING_FIRST_EVENT,
    WAITING_QUIET,
    SETTLED,
    TIMED_OUT
  }

  /**
   * 一次判定结果。{@code nextCheckInMillis} 只在等待态有意义，是到下一个判定边界还要等多久；调用方可以用它做有界
   * 等待，而不是忙轮询。
   */
  public record Decision(State state, long nextCheckInMillis) {
    public boolean terminal() {
      return state == State.SETTLED || state == State.TIMED_OUT;
    }
  }

  /**
   * 等待结束后的回执。{@code firstEventMs}/{@code quietMs} 为 -1 表示没有观察到对应事件；{@code timedOut}
   * 表示总上限到了界面仍未静默（结果仍必须交给调用方，不能静默丢掉动作回执）。
   */
  public record Outcome(
      boolean settled,
      boolean timedOut,
      boolean sawEvent,
      long firstEventMs,
      long quietMs,
      long totalMs,
      int events) {}

  /** 把早于动作开始的旧事件归零，避免调用方误把上一次动作的事件算进本次。 */
  private static long since(long startedAt, long candidate) {
    return candidate >= startedAt ? candidate : -1L;
  }

  public static Decision evaluate(
      long now, long startedAt, long firstEventAt, long lastEventAt, Config config) {
    if (config == null) throw new IllegalArgumentException("SCREEN_SETTLE_CONFIG_MISSING");
    if (startedAt < 0 || now < startedAt) return new Decision(State.TIMED_OUT, 0L);
    long first = since(startedAt, firstEventAt);
    long last = since(startedAt, lastEventAt);
    if (last >= 0) {
      long quiet = now - last;
      if (quiet >= config.quietMillis()) return new Decision(State.SETTLED, 0L);
    }
    long elapsed = now - startedAt;
    if (elapsed >= config.totalMillis()) return new Decision(State.TIMED_OUT, 0L);
    if (first < 0) {
      long remaining = config.firstEventMillis() - elapsed;
      if (remaining <= 0) return new Decision(State.SETTLED, 0L);
      return new Decision(State.WAITING_FIRST_EVENT, remaining);
    }
    long quietRemaining = config.quietMillis() - (now - last);
    long totalRemaining = config.totalMillis() - elapsed;
    return new Decision(State.WAITING_QUIET, Math.min(quietRemaining, totalRemaining));
  }

  /** 到达终态后组装回执；{@code completion} 是实际结束等待的时刻。 */
  public static Outcome outcome(
      State state,
      long startedAt,
      long completion,
      long firstEventAt,
      long lastEventAt,
      int events) {
    long first = since(startedAt, firstEventAt);
    long last = since(startedAt, lastEventAt);
    long total = Math.max(0L, completion - startedAt);
    return new Outcome(
        state == State.SETTLED,
        state == State.TIMED_OUT,
        first >= 0,
        first >= 0 ? first - startedAt : -1L,
        last >= 0 ? Math.max(0L, completion - last) : -1L,
        total,
        Math.max(0, events));
  }
}
