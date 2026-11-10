package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import com.deepseekharness.app.util.TerminalSessionDot.Kind;
import org.junit.Test;

/** 侧栏状态点映射：结束中 / 待核验永远优先显示“需要留意”，不能被进程态掩盖。 */
public class TerminalSessionDotTest {
  @Test
  public void ptyStatesMapToRunningPendingOrExited() {
    assertEquals(Kind.RUNNING, TerminalSessionDot.forPty(false, false, true, false));
    assertEquals(Kind.PENDING, TerminalSessionDot.forPty(false, false, false, true));
    assertEquals(Kind.EXITED, TerminalSessionDot.forPty(false, false, false, false));
    // 结束中 / 待核验即使进程仍在运行也必须显示黄点。
    assertEquals(Kind.PENDING, TerminalSessionDot.forPty(true, false, true, false));
    assertEquals(Kind.PENDING, TerminalSessionDot.forPty(false, true, true, false));
  }

  @Test
  public void everySimpleStateHasOneDot() {
    assertEquals(
        Kind.RUNNING, TerminalSessionDot.forSimple(false, false, TerminalSession.State.BUSY));
    assertEquals(
        Kind.IDLE, TerminalSessionDot.forSimple(false, false, TerminalSession.State.READY));
    assertEquals(
        Kind.PENDING, TerminalSessionDot.forSimple(false, false, TerminalSession.State.STARTING));
    assertEquals(
        Kind.PENDING, TerminalSessionDot.forSimple(false, false, TerminalSession.State.STOPPING));
    assertEquals(
        Kind.PENDING, TerminalSessionDot.forSimple(false, false, TerminalSession.State.FAILED));
    assertEquals(
        Kind.EXITED, TerminalSessionDot.forSimple(false, false, TerminalSession.State.STOPPED));
    assertEquals(Kind.EXITED, TerminalSessionDot.forSimple(false, false, null));
    for (TerminalSession.State state : TerminalSession.State.values()) {
      assertEquals(state.name(), Kind.PENDING, TerminalSessionDot.forSimple(true, false, state));
      assertEquals(state.name(), Kind.PENDING, TerminalSessionDot.forSimple(false, true, state));
    }
  }
}
