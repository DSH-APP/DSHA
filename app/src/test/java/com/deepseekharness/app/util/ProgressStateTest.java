package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** E1 状态机：百分比只增、100 只由完成事件给出、终态锁存、取消只发一次。 */
public class ProgressStateTest {

  private static ProgressState running() {
    ProgressState state = new ProgressState();
    assertTrue(state.start());
    return state;
  }

  @Test
  public void percentNeverGoesBackwardsAndStopsAtNinetyNine() {
    ProgressState state = running();
    assertTrue(state.report(30));
    assertFalse(state.report(12));
    assertEquals(30, state.percent());
    assertTrue(state.report(150));
    assertEquals(99, state.percent());
    assertFalse(state.report(99));
  }

  @Test
  public void onlyCompleteReachesOneHundred() {
    ProgressState state = running();
    state.report(99);
    assertTrue(state.complete());
    assertEquals(ProgressState.Phase.COMPLETED, state.phase());
    assertEquals(100, state.percent());
    assertTrue(state.terminal());
    // 终态锁存：随后到达的失败 / 百分比不能把它改回运行中。
    assertFalse(state.fail());
    assertFalse(state.report(50));
    assertEquals(ProgressState.Phase.COMPLETED, state.phase());
  }

  @Test
  public void cancellationOnlyFromRunningAndOnlyOnce() {
    ProgressState idle = new ProgressState();
    assertFalse(idle.requestCancel());

    ProgressState state = running();
    assertTrue(state.requestCancel());
    assertTrue(state.cancelRequested());
    assertFalse(state.requestCancel());
    // 等待后端收尾期间不再接受进度；收尾完成才进入取消终态。
    assertFalse(state.report(50));
    assertEquals(0, state.percent());
    assertTrue(state.cancelled());
    assertEquals(ProgressState.Phase.CANCELLED, state.phase());
    assertFalse(state.complete());
  }

  @Test
  public void failureKeepsTheRealPercentForTheResultLine() {
    ProgressState state = running();
    state.report(42);
    assertTrue(state.fail());
    assertEquals(42, state.percent());
    assertTrue(state.terminal());
    assertFalse(state.start());
  }

  @Test
  public void resetAllowsANewTask() {
    ProgressState state = running();
    state.report(10);
    state.fail();
    state.reset();
    assertEquals(ProgressState.Phase.IDLE, state.phase());
    assertEquals(-1, state.percent());
    assertTrue(state.start());
  }
}
