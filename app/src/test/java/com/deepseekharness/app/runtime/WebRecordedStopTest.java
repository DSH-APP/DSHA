package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.WebPidIdentity;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import com.deepseekharness.app.util.WebStopDiagnostic;
import com.deepseekharness.app.util.WebStopDiagnostic.Code;

/** Actual stop algorithm with explicit kernel/record/clock outcomes; it never signals a host PID. */
public final class WebRecordedStopTest {
  private static final String WEB =
      "node /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web";

  private static WebPidIdentity birth(long value) {
    return WebPidIdentity.parse("42 (node) S 1 1 1 " + "0 ".repeat(15) + value + " 0 0", 42);
  }

  private static WebProcessManager.ProcessState state(WebProcessManager.Kind kind, long started) {
    return new WebProcessManager.ProcessState(kind, started < 0 ? null : birth(started), WEB);
  }

  private static final class Io implements WebRecordedStop.Io {
    boolean root = true, trial, exitAfterTerm, interrupt, transientFailure;
    String record = "42", saved = "42 100";
    long clock;
    int sentinel, inspections, records;
    final List<Integer> terms = new ArrayList<>();
    final List<String> retired = new ArrayList<>();
    final ArrayDeque<WebProcessManager.ProcessState> states = new ArrayDeque<>();
    WebProcessManager.ProcessState current = state(WebProcessManager.Kind.WEB, 100);
    WebRecordedStop.Signal signal = WebRecordedStop.Signal.SENT;
    int recordChangeAt = -1;

    public boolean rootExists() {
      return root;
    }

    public boolean trial() {
      return trial;
    }

    public String trialProfile() {
      return "dsha-recovery-0123456789abcdef";
    }

    public long now() {
      return clock;
    }

    public void pause(long millis) throws InterruptedException {
      if (interrupt) throw new InterruptedException("explicit clock fixture cancellation");
      clock += millis;
    }

    public void sentinel() {
      sentinel++;
    }

    public String pidRecord() {
      return ++records == recordChangeAt ? "43" : record;
    }

    public String savedIdentity(int pid) {
      return saved;
    }

    public WebProcessManager.ProcessState inspect(int pid) throws IOException {
      assertEquals(42, pid);
      inspections++;
      if (transientFailure)
        throw new WebProcessManager.ProcessInspectionException(
            new IOException("token=synthetic-secret"));
      if (!states.isEmpty()) return states.removeFirst();
      return exitAfterTerm && !terms.isEmpty() ? state(WebProcessManager.Kind.GONE, -1) : current;
    }

    public void retire(String record) {
      retired.add(record);
    }

    public WebRecordedStop.Signal term(int pid) {
      terms.add(pid);
      return signal;
    }
  }

  @Test
  public void missingRootOrRecordNeverSignals() {
    Io io = new Io();
    io.root = false;
    assertNull(WebRecordedStop.run(io));
    assertEquals(0, io.sentinel);
    io.root = true;
    io.record = null;
    assertNull(WebRecordedStop.run(io));
    assertTrue(io.terms.isEmpty());
  }

  @Test
  public void goneAndReusedBirthRetireOnlyTheOldRecord() {
    for (WebProcessManager.ProcessState candidate :
        List.of(state(WebProcessManager.Kind.GONE, -1), state(WebProcessManager.Kind.OTHER, 101))) {
      Io io = new Io();
      io.current = candidate;
      assertNull(WebRecordedStop.run(io));
      assertEquals(List.of("42"), io.retired);
      assertTrue(io.terms.isEmpty());
    }
  }

  @Test
  public void unreadableSameUidAndUnrelatedSameBirthKeepBarrierWithoutAnySignal() {
    for (WebProcessManager.ProcessState candidate :
        List.of(
            state(WebProcessManager.Kind.DENIED, -1), state(WebProcessManager.Kind.OTHER, 100))) {
      Io io = new Io();
      io.current = candidate;
      assertNotNull(WebRecordedStop.run(io));
      assertTrue(io.retired.isEmpty());
      assertTrue(io.terms.isEmpty());
    }
  }

  @Test
  public void hiddenUnsignalablePidRequiresTwoMatchingRecordsAndNeverReceivesTerm() {
    Io io = new Io();
    io.current =
        new WebProcessManager.ProcessState(WebProcessManager.Kind.DENIED, null, "", false, true);
    assertNull(WebRecordedStop.run(io));
    assertEquals(2, io.inspections);
    assertEquals(List.of("42"), io.retired);
    assertTrue(io.terms.isEmpty());
    io = new Io();
    io.current =
        new WebProcessManager.ProcessState(WebProcessManager.Kind.DENIED, null, "", false, true);
    io.recordChangeAt = 2;
    assertEquals(Code.IDENTITY_CHANGED, WebRecordedStop.run(io).code());
    assertTrue(io.retired.isEmpty());
    assertTrue(io.terms.isEmpty());
  }

  @Test
  public void trialCannotUseHiddenRecordRetirementOrWrongProfile() {
    Io io = new Io();
    io.trial = true;
    io.current =
        new WebProcessManager.ProcessState(WebProcessManager.Kind.DENIED, null, "", false, true);
    assertNotNull(WebRecordedStop.run(io));
    assertTrue(io.retired.isEmpty());
    io.current = state(WebProcessManager.Kind.WEB, 100);
    assertEquals(Code.TRIAL_IDENTITY_CHANGED, WebRecordedStop.run(io).code());
    assertTrue(io.terms.isEmpty());
  }

  @Test
  public void changedSecondBirthOrPidRecordPreventsSignal() {
    Io io = new Io();
    io.states.add(state(WebProcessManager.Kind.WEB, 100));
    io.states.add(state(WebProcessManager.Kind.WEB, 101));
    assertNotNull(WebRecordedStop.run(io));
    assertTrue(io.terms.isEmpty());
    io = new Io();
    io.recordChangeAt = 2;
    assertNotNull(WebRecordedStop.run(io));
    assertTrue(io.terms.isEmpty());
    assertTrue(io.retired.isEmpty());
  }

  @Test
  public void confirmedExitAfterTermRetiresOnlyAfterObservedKernelOutcome() {
    Io io = new Io();
    io.exitAfterTerm = true;
    assertNull(WebRecordedStop.run(io));
    assertEquals(List.of(42), io.terms);
    assertEquals(List.of("42"), io.retired);
    assertEquals(3, io.inspections);
  }

  @Test
  public void termTimeoutHasNoForcePathAndKeepsTheRecord() {
    Io io = new Io();
    assertNotNull(WebRecordedStop.run(io));
    assertEquals(List.of(42), io.terms);
    assertTrue(io.retired.isEmpty());
    assertEquals(3000, io.clock);
  }

  @Test
  public void signalGoneOrForeignUidRetiresAndDeniedKeepsBarrier() {
    for (WebRecordedStop.Signal outcome :
        List.of(WebRecordedStop.Signal.GONE, WebRecordedStop.Signal.FOREIGN_UID)) {
      Io io = new Io();
      io.signal = outcome;
      assertNull(WebRecordedStop.run(io));
      assertEquals(List.of("42"), io.retired);
    }
    Io denied = new Io();
    denied.signal = WebRecordedStop.Signal.DENIED;
    assertEquals(Code.SIGNAL_DENIED, WebRecordedStop.run(denied).code());
    assertTrue(denied.retired.isEmpty());
  }

  @Test
  public void transientInspectionRetriesOnlyWithinDeadlineAndRedactsFailure() {
    Io io = new Io();
    io.transientFailure = true;
    WebStopDiagnostic error = WebRecordedStop.run(io);
    assertNotNull(error);
    assertEquals(Code.STOP_FAILED, error.code());
    assertFalse(error.debug().contains("synthetic-secret"));
    assertEquals(3000, io.clock);
    assertTrue(io.terms.isEmpty());
    assertTrue(io.retired.isEmpty());
  }

  @Test
  public void interruptedWaitKeepsRecordAndRestoresInterruptFlag() {
    try {
      Io io = new Io();
      io.interrupt = true;
      assertNotNull(WebRecordedStop.run(io));
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(io.retired.isEmpty());
      assertEquals(List.of(42), io.terms);
    } finally {
      Thread.interrupted();
    }
  }
}
