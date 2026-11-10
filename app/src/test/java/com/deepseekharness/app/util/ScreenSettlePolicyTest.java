package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

/** 纯判定：首个事件预算、静默窗口、总上限与时钟异常，不依赖 Android 或真实等待。 */
public final class ScreenSettlePolicyTest {
  private static final ScreenSettlePolicy.Config DEFAULT = ScreenSettlePolicy.DEFAULT;

  @Test
  public void productBudgetsAreFirstEventQuietAndTotal() {
    assertEquals(800L, DEFAULT.firstEventMillis());
    assertEquals(250L, DEFAULT.quietMillis());
    assertEquals(2_500L, DEFAULT.totalMillis());
    try {
      new ScreenSettlePolicy.Config(0, 250, 2_500);
      fail("zero first event budget must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("SCREEN_SETTLE_CONFIG_INVALID", expected.getMessage());
    }
    try {
      new ScreenSettlePolicy.Config(900, 250, 800);
      fail("first event budget beyond total must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("SCREEN_SETTLE_CONFIG_INVALID", expected.getMessage());
    }
  }

  @Test
  public void noEventAtAllSettlesAtFirstEventBudget() {
    assertEquals(
        ScreenSettlePolicy.State.WAITING_FIRST_EVENT,
        ScreenSettlePolicy.evaluate(1_000, 1_000, -1, -1, DEFAULT).state());
    assertEquals(
        800L, ScreenSettlePolicy.evaluate(1_000, 1_000, -1, -1, DEFAULT).nextCheckInMillis());
    assertEquals(
        1L, ScreenSettlePolicy.evaluate(1_799, 1_000, -1, -1, DEFAULT).nextCheckInMillis());
    assertEquals(
        ScreenSettlePolicy.State.SETTLED,
        ScreenSettlePolicy.evaluate(1_800, 1_000, -1, -1, DEFAULT).state());
  }

  @Test
  public void firstEventStartsTheQuietWindow() {
    var waiting = ScreenSettlePolicy.evaluate(1_349, 1_000, 1_100, 1_100, DEFAULT);
    assertEquals(ScreenSettlePolicy.State.WAITING_QUIET, waiting.state());
    assertEquals(1L, waiting.nextCheckInMillis());
    var settled = ScreenSettlePolicy.evaluate(1_350, 1_000, 1_100, 1_100, DEFAULT);
    assertEquals(ScreenSettlePolicy.State.SETTLED, settled.state());
    assertEquals(0L, settled.nextCheckInMillis());
  }

  @Test
  public void continuousEventsHitTheHardTotalCap() {
    var decision = ScreenSettlePolicy.evaluate(3_500, 1_000, 1_010, 3_450, DEFAULT);
    assertEquals(ScreenSettlePolicy.State.TIMED_OUT, decision.state());
    assertTrue(decision.terminal());
    // 已经静默够久时先算稳定，不把正常结果说成超时。
    assertEquals(
        ScreenSettlePolicy.State.SETTLED,
        ScreenSettlePolicy.evaluate(3_300, 1_000, 1_010, 3_000, DEFAULT).state());
  }

  @Test
  public void eventsBeforeTheActionStartDoNotCount() {
    var decision = ScreenSettlePolicy.evaluate(1_500, 1_200, 1_000, 1_000, DEFAULT);
    assertEquals(ScreenSettlePolicy.State.WAITING_FIRST_EVENT, decision.state());
    assertEquals(500L, decision.nextCheckInMillis());
  }

  @Test
  public void clockRegressionCannotReportAStableScreen() {
    assertEquals(
        ScreenSettlePolicy.State.TIMED_OUT,
        ScreenSettlePolicy.evaluate(900, 1_000, 1_100, 1_100, DEFAULT).state());
    assertEquals(
        ScreenSettlePolicy.State.TIMED_OUT,
        ScreenSettlePolicy.evaluate(1_000, -1, -1, -1, DEFAULT).state());
  }

  @Test
  public void outcomeReportsOnlyObservedFacts() {
    var settled =
        ScreenSettlePolicy.outcome(ScreenSettlePolicy.State.SETTLED, 1_000, 1_350, 1_100, 1_100, 2);
    assertTrue(settled.settled());
    assertFalse(settled.timedOut());
    assertTrue(settled.sawEvent());
    assertEquals(100L, settled.firstEventMs());
    assertEquals(250L, settled.quietMs());
    assertEquals(350L, settled.totalMs());
    assertEquals(2, settled.events());

    var none =
        ScreenSettlePolicy.outcome(ScreenSettlePolicy.State.SETTLED, 1_000, 1_800, -1, -1, 0);
    assertTrue(none.settled());
    assertFalse(none.sawEvent());
    assertEquals(-1L, none.firstEventMs());
    assertEquals(-1L, none.quietMs());
    assertEquals(800L, none.totalMs());

    var timedOut =
        ScreenSettlePolicy.outcome(
            ScreenSettlePolicy.State.TIMED_OUT, 1_000, 3_600, 1_010, 3_500, 4);
    assertFalse(timedOut.settled());
    assertTrue(timedOut.timedOut());
    assertEquals(100L, timedOut.quietMs());
  }
}
