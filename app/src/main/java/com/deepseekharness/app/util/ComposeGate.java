package com.deepseekharness.app.util;

import java.util.function.LongSupplier;

/**
 * 用户正在给 AI 输入消息时的动作闸（纯逻辑，单调时钟）。
 *
 * <p>输入期间 AI 的手机动作（点按、滑动、输入、节点动作）停在原地等待，不取消对话、不结束本轮；
 * 读屏不受影响。等待结束有三种结果：
 *
 * <ul>
 *   <li>{@link Outcome#CLEAR}：用户没在输入，或没发送就放开了，动作照常继续（仍须先满足重新观察）。
 *   <li>{@link Outcome#MESSAGE_ARRIVED}：等待期间用户发来了新消息，本次动作不执行，AI 应先读消息、重新观察。
 *   <li>{@link Outcome#STILL_COMPOSING}：等到上限用户仍在输入，本次动作不执行，AI 应停下等用户。
 * </ul>
 *
 * 每次 begin 产生新代次，旧等待者只认它开始等待时的代次，不会被后来的输入误判。
 */
public final class ComposeGate {
  public static final long DEFAULT_WAIT_MILLIS = 60_000;

  public enum Outcome {
    CLEAR,
    MESSAGE_ARRIVED,
    STILL_COMPOSING,
    INTERRUPTED
  }

  private final LongSupplier clock;
  private boolean composing;
  private long generation;
  private long sentGeneration = -1;
  private String surface = "";

  public ComposeGate(LongSupplier clock) {
    if (clock == null) throw new IllegalArgumentException("COMPOSE_GATE_CLOCK_MISSING");
    this.clock = clock;
  }

  /** 用户开始输入（窗内输入框获得焦点、打开编辑器或语音）。重复调用只换输入来源。 */
  public synchronized void begin(String from) {
    if (!composing) generation++;
    composing = true;
    surface = from == null ? "" : from;
    notifyAll();
  }

  /** 没发送就离开：仅当仍是同一来源时放开，避免语音交还编辑器时被提前放开。 */
  public synchronized boolean release(String from) {
    if (!composing || !surface.equals(from == null ? "" : from)) return false;
    composing = false;
    surface = "";
    notifyAll();
    return true;
  }

  /** 消息已被 Host 接收：放开并让本代次的等待者得知有新消息。 */
  public synchronized void sent() {
    if (composing) sentGeneration = generation;
    composing = false;
    surface = "";
    notifyAll();
  }

  /** 任务结束、停止或连接改变：清空，不留下会阻塞下一轮的状态。 */
  public synchronized void reset() {
    composing = false;
    surface = "";
    sentGeneration = -1;
    notifyAll();
  }

  public synchronized boolean composing() {
    return composing;
  }

  public synchronized String surface() {
    return surface;
  }

  /**
   * 动作前调用。未在输入立即返回 CLEAR；否则最多等 {@code maxWaitMillis}，期间 {@code live} 变 false
   * （任务暂停/停止/换轮）立即返回 INTERRUPTED，由调用方给出原有拒绝原因。
   */
  public Outcome await(long maxWaitMillis, java.util.function.BooleanSupplier live)
      throws InterruptedException {
    long started = clock.getAsLong();
    long waitingFor;
    synchronized (this) {
      if (!composing) return Outcome.CLEAR;
      waitingFor = generation;
    }
    while (true) {
      if (live != null && !live.getAsBoolean()) return Outcome.INTERRUPTED;
      synchronized (this) {
        if (sentGeneration == waitingFor) return Outcome.MESSAGE_ARRIVED;
        if (!composing || generation != waitingFor) return Outcome.CLEAR;
        long elapsed = clock.getAsLong() - started;
        if (elapsed < 0 || elapsed >= maxWaitMillis) return Outcome.STILL_COMPOSING;
        // 分片等待，定期复核任务是否仍有效；sent/release/begin 会立即唤醒。
        wait(Math.max(1, Math.min(200, maxWaitMillis - elapsed)));
      }
    }
  }
}
