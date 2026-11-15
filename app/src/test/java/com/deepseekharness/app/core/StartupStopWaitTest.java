package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.WebStopDiagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** 启动前置停止的只读等待：早退、到期限、读取失败与已过期期限各自收口。 */
public final class StartupStopWaitTest {
  private static final List<WebStopDiagnostic> PENDING =
      List.of(
          WebStopDiagnostic.of(
              WebStopDiagnostic.Code.OWNED_WEB_REMAINS, "PID=42", "命令摘要=node bin.js web"));

  private static final class Io implements StartupStopWait.Io {
    long now = 1_000;
    int confirms;
    int confirmAt = 1;
    boolean deny;
    final List<Long> pauses = new ArrayList<>();

    public boolean confirmed() throws IOException {
      confirms++;
      if (deny) throw new IOException("PROC_DENIED");
      return confirmAt > 0 && confirms >= confirmAt;
    }

    public long now() {
      return now;
    }

    public void pause(long millis) {
      pauses.add(millis);
      now += millis;
    }
  }

  @Test
  public void confirmationEndsTheWaitImmediately() throws Exception {
    Io io = new Io();
    io.confirmAt = 2;
    assertTrue(StartupStopWait.await(io, 9_000, PENDING).isEmpty());
    assertEquals(2, io.confirms);
    assertEquals(1, io.pauses.size());
    assertTrue(io.pauses.get(0) <= com.deepseekharness.app.util.WebStopBudget.POLL_MILLIS);
  }

  @Test
  public void deadlineReturnsTheLastVerifiedFacts() throws Exception {
    Io io = new Io();
    io.confirmAt = -1;
    assertEquals(PENDING, StartupStopWait.await(io, 2_000, PENDING));
    assertTrue(io.now >= 2_000);
    assertTrue(io.confirms > 1);
  }

  @Test
  public void readFailureDoesNotBecomeExitProof() throws Exception {
    Io io = new Io();
    io.deny = true;
    assertEquals(PENDING, StartupStopWait.await(io, 1_500, PENDING));
    assertTrue(io.now >= 1_500);
  }

  @Test
  public void alreadyExpiredDeadlineStillChecksOnce() throws Exception {
    Io io = new Io();
    io.confirmAt = 1;
    assertTrue(StartupStopWait.await(io, 0, PENDING).isEmpty());
    assertEquals(1, io.confirms);
    assertTrue(io.pauses.isEmpty());
  }
}
