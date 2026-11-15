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

  /** 09:13 真机现场的残留形态：本安装受管 proroot bridge（guest 侧）。 */
  private static final String PROROOT_BRIDGE =
      "libproroot-bridge.so /data/app/pkg/lib/arm64/libproroot-linker.so --argv0 node "
          + "--preload /data/app/pkg/lib/arm64/libproroot-runtime.so "
          + "/data/data/com.dsh/files/linux/ubuntu/usr/local/bin/node "
          + "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web";

  private static WebProcessManager.ProcessState bridge(long started) {
    return new WebProcessManager.ProcessState(
        WebProcessManager.Kind.WEB, birth(started), PROROOT_BRIDGE);
  }

  private static final class Io implements WebRecordedStop.Io {
    boolean root = true,
        trial,
        exitAfterTerm,
        interrupt,
        transientFailure,
        requireSavedBirth,
        transientDuringWait,
        mayEscalate,
        exitAfterQuit,
        exitAfterKill;
    String record = "42", saved = "42 100";
    long clock;
    int sentinel, inspections, records, waitFailures;
    final List<Integer> terms = new ArrayList<>();
    final List<String> retired = new ArrayList<>();
    final List<String> escalations = new ArrayList<>();
    final ArrayDeque<WebProcessManager.ProcessState> states = new ArrayDeque<>();
    WebProcessManager.ProcessState current = state(WebProcessManager.Kind.WEB, 100);
    WebProcessManager.ProcessState afterTerm;
    WebRecordedStop.Signal signal = WebRecordedStop.Signal.SENT;
    WebRecordedStop.Signal escalateSignal = WebRecordedStop.Signal.SENT;
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

    public boolean requireSavedBirth() {
      return requireSavedBirth;
    }

    public WebProcessManager.ProcessState inspect(int pid) throws IOException {
      assertEquals(42, pid);
      inspections++;
      if (transientFailure)
        throw new WebProcessManager.ProcessInspectionException(
            new IOException("token=synthetic-secret"));
      if (transientDuringWait && !terms.isEmpty() && waitFailures-- > 0)
        throw new WebProcessManager.ProcessInspectionException(new IOException("exit-state"));
      if (afterTerm != null && !terms.isEmpty()) return afterTerm;
      if (!escalations.isEmpty()) {
        boolean exited = escalations.contains("KILL") ? exitAfterKill : exitAfterQuit;
        if (exited) return state(WebProcessManager.Kind.GONE, -1);
      }
      if (!states.isEmpty()) return states.removeFirst();
      return exitAfterTerm && !terms.isEmpty() ? state(WebProcessManager.Kind.GONE, -1) : current;
    }

    public boolean mayEscalate() {
      return mayEscalate;
    }

    public WebRecordedStop.Signal escalate(int pid, boolean kill) {
      escalations.add(kill ? "KILL" : "QUIT");
      return escalateSignal;
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
  public void forceModeOnlySignalsWithMatchingSavedBirth() {
    Io io = new Io();
    io.requireSavedBirth = true;
    io.exitAfterTerm = true;
    assertNull(WebRecordedStop.run(io));
    assertEquals(List.of(42), io.terms);
    assertEquals(List.of("42"), io.retired);
    io = new Io();
    io.trial = true;
    io.requireSavedBirth = true;
    io.saved = null;
    assertEquals(Code.TRIAL_IDENTITY_CHANGED, WebRecordedStop.run(io).code());
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
    WebStopDiagnostic error = WebRecordedStop.run(io);
    assertNotNull(error);
    assertEquals(List.of(42), io.terms);
    assertTrue(io.retired.isEmpty());
    // 预算必须覆盖 dsh 自身 5s 的强制退出上限；3s 会把正常关闭判成未确认。
    assertEquals(com.deepseekharness.app.util.WebStopBudget.EXIT_CONFIRM_MILLIS, io.clock);
    assertTrue(io.clock >= 6_000 && io.clock <= 8_000);
  }

  @Test
  public void ignoredTermOnManagedProrootBridgeEscalatesQuitThenKill() {
    Io io = new Io();
    io.current = bridge(100);
    io.mayEscalate = true;
    io.exitAfterKill = true;
    assertNull(WebRecordedStop.run(io));
    assertEquals(List.of("QUIT", "KILL"), io.escalations);
    assertEquals(List.of("42"), io.retired);
    assertTrue(io.clock >= com.deepseekharness.app.util.WebStopBudget.EXIT_CONFIRM_MILLIS);
  }

  @Test
  public void quitThatLetsTheBridgeExitNeedsNoKill() {
    Io io = new Io();
    io.current = bridge(100);
    io.mayEscalate = true;
    io.exitAfterQuit = true;
    assertNull(WebRecordedStop.run(io));
    assertEquals(List.of("QUIT"), io.escalations);
    assertEquals(List.of("42"), io.retired);
  }

  @Test
  public void escalationIsNeverAppliedToNonProrootOrTrialStops() {
    Io normal = new Io();
    normal.mayEscalate = true; // 普通 proot node：即使允许升级也不匹配 bridge 判据
    assertEquals(Code.EXIT_UNCONFIRMED, WebRecordedStop.run(normal).code());
    assertTrue(normal.escalations.isEmpty());
    Io trial = new Io();
    trial.current = bridge(100);
    trial.mayEscalate = false; // 隔离试运行 / 强杀路径
    assertEquals(Code.EXIT_UNCONFIRMED, WebRecordedStop.run(trial).code());
    assertTrue(trial.escalations.isEmpty());
  }

  @Test
  public void nonBridgeStateAfterTermIsRefusedBeforeAnyEscalation() {
    Io io = new Io();
    io.current = bridge(100);
    io.mayEscalate = true;
    io.afterTerm = new WebProcessManager.ProcessState(WebProcessManager.Kind.OTHER, null, "");
    assertEquals(Code.IDENTITY_CHANGED, WebRecordedStop.run(io).code());
    assertTrue(io.escalations.isEmpty());
    assertTrue(io.retired.isEmpty());
  }

  @Test
  public void escalationWriteDeniedKeepsTheRecordWithoutPretendingExit() {
    Io io = new Io();
    io.current = bridge(100);
    io.mayEscalate = true;
    io.escalateSignal = WebRecordedStop.Signal.DENIED;
    WebStopDiagnostic error = WebRecordedStop.run(io);
    assertEquals(Code.SIGNAL_DENIED, error.code());
    assertEquals(List.of("QUIT"), io.escalations);
    assertTrue(io.retired.isEmpty());
  }

  @Test
  public void stillAliveAtDeadlineReportsUnconfirmedInsteadOfPendingExit() {
    Io io = new Io();
    WebStopDiagnostic error = WebRecordedStop.run(io);
    assertNotNull(error);
    assertEquals(Code.EXIT_UNCONFIRMED, error.code());
    assertNotEquals(Code.PENDING_EXIT, error.code());
    assertEquals("42", error.arguments().get(0));
    assertEquals(List.of(42), io.terms);
    assertTrue(io.retired.isEmpty());
  }

  @Test
  public void identityChangeDuringExitWaitStopsWithoutAnotherSignal() {
    Io io = new Io();
    // 两次发信号前核验都读同一出生身份；SIGTERM 之后命令行变成非 Web 命令。
    io.states.add(state(WebProcessManager.Kind.WEB, 100));
    io.states.add(state(WebProcessManager.Kind.WEB, 100));
    io.states.add(
        new WebProcessManager.ProcessState(
            WebProcessManager.Kind.WEB,
            com.deepseekharness.app.util.WebPidIdentity.parse(
                "42 (node) S 1 1 1 " + "0 ".repeat(15) + "100 0 0", 42),
            "sleep 1"));
    WebStopDiagnostic error = WebRecordedStop.run(io);
    assertNotNull(error);
    assertEquals(Code.IDENTITY_CHANGED, error.code());
    assertEquals(List.of(42), io.terms);
    assertTrue(io.retired.isEmpty());
    assertTrue(io.clock < com.deepseekharness.app.util.WebStopBudget.EXIT_CONFIRM_MILLIS);
  }

  @Test
  public void transientInspectionFailuresDuringExitWaitStillWaitOutTheBudget() {
    Io io = new Io();
    io.transientDuringWait = true;
    io.waitFailures = 2;
    WebStopDiagnostic error = WebRecordedStop.run(io);
    assertNotNull(error);
    assertEquals(Code.EXIT_UNCONFIRMED, error.code());
    assertEquals(com.deepseekharness.app.util.WebStopBudget.EXIT_CONFIRM_MILLIS, io.clock);
    assertTrue(io.retired.isEmpty());
    assertFalse(error.debug().contains("token"));
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
