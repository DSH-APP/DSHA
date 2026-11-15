package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.junit.Test;

/**
 * 事件脉冲的真实等待：用单调时钟与短预算验证“有事件→静默后返回、无事件→首个预算后返回、事件不断→总上限超时、
 * 目标不匹配不计入、可被撤销打断”。断言只覆盖有上下界的性质，不把测试机负载当成产品时延结论。
 */
public final class ScreenEventPulseTest {
  private static final LongSupplier CLOCK = () -> System.nanoTime() / 1_000_000L;

  private static ScreenSettlePolicy.Config config(long first, long quiet, long total) {
    return new ScreenSettlePolicy.Config(first, quiet, total);
  }

  @Test
  public void watchesGateRecordingAndClearOnEnd() {
    ScreenEventPulse pulse = new ScreenEventPulse(CLOCK);
    long started = CLOCK.getAsLong();
    pulse.record(7, "example.app");
    assertFalse(pulse.watching());
    pulse.beginWatch();
    assertTrue(pulse.watching());
    pulse.record(7, "example.app");
    pulse.endWatch();
    assertFalse(pulse.watching());
    pulse.beginWatch();
    var outcome =
        pulse.await(started, "example.app", 7, started + 2_000, config(60, 30, 200), () -> true);
    assertTrue(outcome.settled());
    assertFalse("endWatch 必须清掉旧事件", outcome.sawEvent());
    pulse.endWatch();
  }

  @Test
  public void eventThenQuietSettles() {
    ScreenEventPulse pulse = new ScreenEventPulse(CLOCK);
    pulse.beginWatch();
    long started = CLOCK.getAsLong();
    pulse.record(7, "example.app");
    var outcome =
        pulse.await(started, "example.app", 7, started + 2_000, config(300, 60, 800), () -> true);
    pulse.endWatch();
    assertTrue(outcome.settled());
    assertTrue(outcome.sawEvent());
    assertEquals(1, outcome.events());
    assertTrue("firstEventMs=" + outcome.firstEventMs(), outcome.firstEventMs() >= 0);
    assertTrue("firstEventMs=" + outcome.firstEventMs(), outcome.firstEventMs() < 200);
    assertTrue("quietMs=" + outcome.quietMs(), outcome.quietMs() >= 55);
    assertTrue("totalMs=" + outcome.totalMs(), outcome.totalMs() < 1_000);
  }

  @Test
  public void noEventSettlesAtFirstEventBudget() {
    ScreenEventPulse pulse = new ScreenEventPulse(CLOCK);
    pulse.beginWatch();
    long started = CLOCK.getAsLong();
    var outcome =
        pulse.await(started, "example.app", 7, started + 2_000, config(80, 40, 300), () -> true);
    pulse.endWatch();
    assertTrue(outcome.settled());
    assertFalse(outcome.sawEvent());
    assertEquals(-1L, outcome.firstEventMs());
    assertEquals(-1L, outcome.quietMs());
    assertTrue("totalMs=" + outcome.totalMs(), outcome.totalMs() >= 50);
    assertTrue("totalMs=" + outcome.totalMs(), outcome.totalMs() < 700);
  }

  @Test
  public void continuousEventsTimeOutAtTheTotalCap() throws Exception {
    ScreenEventPulse pulse = new ScreenEventPulse(CLOCK);
    pulse.beginWatch();
    long started = CLOCK.getAsLong();
    AtomicBoolean running = new AtomicBoolean(true);
    Thread producer =
        new Thread(
            () -> {
              while (running.get()) {
                pulse.record(7, "example.app");
                try {
                  Thread.sleep(5);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
            },
            "screen-pulse-producer");
    producer.start();
    var outcome =
        pulse.await(started, "example.app", 7, started + 2_000, config(100, 80, 250), () -> true);
    running.set(false);
    producer.join(1_000);
    pulse.endWatch();
    assertFalse(outcome.settled());
    assertTrue(outcome.timedOut());
    assertTrue(outcome.sawEvent());
    assertTrue("totalMs=" + outcome.totalMs(), outcome.totalMs() >= 200);
    assertTrue("totalMs=" + outcome.totalMs(), outcome.totalMs() < 1_200);
  }

  @Test
  public void otherTargetsDoNotCountAsEvents() {
    ScreenEventPulse pulse = new ScreenEventPulse(CLOCK);
    pulse.beginWatch();
    long started = CLOCK.getAsLong();
    pulse.record(8, "com.other.app");
    var outcome =
        pulse.await(started, "example.app", 7, started + 2_000, config(60, 30, 200), () -> true);
    pulse.endWatch();
    assertTrue(outcome.settled());
    assertFalse(outcome.sawEvent());
  }

  @Test
  public void sameWindowOtherPackageStillCounts() {
    ScreenEventPulse pulse = new ScreenEventPulse(CLOCK);
    pulse.beginWatch();
    long started = CLOCK.getAsLong();
    // 输入法或系统弹窗常以别的包名出现在同一窗口号上。
    pulse.record(7, "com.android.inputmethod");
    var outcome =
        pulse.await(started, "example.app", 7, started + 2_000, config(60, 30, 200), () -> true);
    pulse.endWatch();
    assertTrue(outcome.sawEvent());
  }

  @Test
  public void cancellationAndDeadlineBoundedTheWait() {
    ScreenEventPulse pulse = new ScreenEventPulse(CLOCK);
    pulse.beginWatch();
    long started = CLOCK.getAsLong();
    var cancelled =
        pulse.await(
            started, "example.app", 7, started + 2_000, config(800, 250, 2_500), () -> false);
    assertFalse(cancelled.settled());
    assertTrue("totalMs=" + cancelled.totalMs(), cancelled.totalMs() < 300);
    var deadline =
        pulse.await(started, "example.app", 7, started + 60, config(800, 250, 2_500), () -> true);
    assertTrue(deadline.timedOut());
    assertTrue("totalMs=" + deadline.totalMs(), deadline.totalMs() < 500);
    pulse.endWatch();
  }
}
