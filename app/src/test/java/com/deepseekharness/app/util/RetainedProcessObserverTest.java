package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class RetainedProcessObserverTest {
  private static final class Child extends Process {
    final ArrayDeque<Object> observations = new ArrayDeque<>();
    int probes;

    Child(Object... observations) {
      this.observations.addAll(List.of(observations));
    }

    public int exitValue() {
      probes++;
      Object observed = observations.removeFirst();
      if (observed instanceof RuntimeException) throw (RuntimeException) observed;
      if (observed instanceof Error) throw (Error) observed;
      return (Integer) observed;
    }

    public int waitFor() {
      throw new AssertionError("No unbounded native wait");
    }

    public void destroy() {
      throw new AssertionError("An observer cannot signal or replay anything");
    }

    public InputStream getInputStream() {
      throw new AssertionError("No process output inspection");
    }

    public InputStream getErrorStream() {
      return getInputStream();
    }

    public OutputStream getOutputStream() {
      throw new AssertionError("No process input");
    }
  }

  @Test
  public void oneFailedProbeDoesNotStrandTheLeaseAfterActualExit() {
    RuntimeTaskRegistry tasks = new RuntimeTaskRegistry();
    var token = tasks.begin(true, "fixture");
    Child child = new Child(new SecurityException("private token must not escape"), 17);
    List<RetainedProcessObserver.State> states = new ArrayList<>();
    RetainedProcessObserver.awaitExit(
        child,
        states::add,
        millis -> {
          assertEquals(1000, millis);
          assertEquals(1, tasks.count());
          assertNull(tasks.tryEnterMaintenance());
        });
    token.close();
    assertEquals(List.of(RetainedProcessObserver.State.UNKNOWN), states);
    assertEquals(2, child.probes);
    assertEquals(0, tasks.count());
    try (var maintenance = tasks.tryEnterMaintenance()) {
      assertNotNull(maintenance);
    }
  }

  @Test
  public void unknownRunningAndConfirmedExitAreDifferentObservations() {
    Child child =
        new Child(
            new IllegalThreadStateException(),
            new SecurityException(),
            new SecurityException(),
            new IllegalThreadStateException(),
            0);
    List<RetainedProcessObserver.State> states = new ArrayList<>();
    AtomicInteger pauses = new AtomicInteger();
    RetainedProcessObserver.awaitExit(child, states::add, millis -> pauses.incrementAndGet());
    assertEquals(
        List.of(
            RetainedProcessObserver.State.RUNNING,
            RetainedProcessObserver.State.UNKNOWN,
            RetainedProcessObserver.State.RUNNING),
        states);
    assertEquals(4, pauses.get());
  }

  @Test
  public void persistentUnknownNeverReleasesTheLeaseOrFloodsReports() {
    RuntimeTaskRegistry tasks = new RuntimeTaskRegistry();
    var token = tasks.begin(true, "fixture");
    Child child =
        new Child(new SecurityException(), new SecurityException(), new SecurityException());
    List<RetainedProcessObserver.State> states = new ArrayList<>();
    AtomicInteger pauses = new AtomicInteger();
    assertThrows(
        AssertionError.class,
        () -> {
          RetainedProcessObserver.awaitExit(
              child,
              states::add,
              millis -> {
                if (pauses.incrementAndGet() == 3) throw new AssertionError("bounded fixture end");
              });
          token.close();
        });
    assertEquals(List.of(RetainedProcessObserver.State.UNKNOWN), states);
    assertEquals(1, tasks.count());
    assertNull(tasks.tryEnterMaintenance());
    token.close();
  }

  @Test
  public void failedDiagnosticCallbackDoesNotStopObservation() {
    Child child = new Child(new SecurityException(), 0);
    RetainedProcessObserver.awaitExit(
        child,
        state -> {
          throw new IllegalStateException("logger unavailable");
        },
        millis -> {});
    assertEquals(2, child.probes);
  }

  @Test
  public void interruptionIsPreservedAndDoesNotPretendTheProcessExited() {
    Child child = new Child(new IllegalThreadStateException(), 0);
    try {
      Thread.currentThread().interrupt();
      RetainedProcessObserver.awaitExit(
          child,
          state -> {},
          millis -> {
            throw new InterruptedException("interrupted cleanup");
          });
      assertEquals(2, child.probes);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void fatalVmErrorCannotBeClaimedAsSuccessfulExit() {
    Child child = new Child(new OutOfMemoryError("fixture"));
    assertThrows(
        OutOfMemoryError.class,
        () -> RetainedProcessObserver.awaitExit(child, state -> {}, millis -> {}));
    assertEquals(1, child.probes);
  }
}
