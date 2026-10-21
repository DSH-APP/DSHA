package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.core.WebLifecycleController.WebRun;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.Test;

public class WebProcessSessionTest {
  static final class Lease implements WebProcessSession.BridgeLease {
    int started, closed;

    public void ensureStarted() {
      started++;
    }

    public void close() {
      closed++;
    }
  }

  static final class Child extends Process {
    boolean alive = true;

    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    public int waitFor() {
      alive = false;
      return 0;
    }

    public int exitValue() {
      if (alive) throw new IllegalThreadStateException();
      return 0;
    }

    public void destroy() {
      alive = false;
    }
  }

  @Test
  public void stopDuringBridgeAcquisitionClosesTheUnclaimedLease() {
    HarnessSessionState state = new HarnessSessionState();
    long generation = state.lifecycle.beginStart(false, false);
    WebRun run = new WebRun(generation);
    state.currentRun = run;
    state.runs.put(generation, run);
    Lease lease = new Lease();
    WebProcessSession processes =
        new WebProcessSession(
            state,
            null,
            () -> {
              state.lifecycle.beginStop();
              return lease;
            });
    processes.acquireBridge(run);
    assertNull(run.bridge);
    assertEquals(1, lease.closed);
    assertEquals(0, lease.started);
    state.io.shutdownNow();
  }

  @Test
  public void releasingOldRunKeepsTheNewRunAndAnyOldLiveLauncher() {
    HarnessSessionState state = new HarnessSessionState();
    long generation = state.lifecycle.beginStart(false, false);
    WebRun old = new WebRun(generation);
    Child process = new Child();
    Lease oldLease = new Lease();
    old.launcher = process;
    old.bridge = oldLease;
    state.runs.put(generation, old);
    state.lifecycle.finishStart(generation);
    state.lifecycle.beginStop();
    state.lifecycle.finishStop(state.lifecycle.generation());
    WebRun current = new WebRun(state.lifecycle.beginStart(false, false));
    Lease currentLease = new Lease();
    current.bridge = currentLease;
    state.currentRun = current;
    state.runs.put(current.generation, current);
    WebProcessSession processes =
        new WebProcessSession(
            state,
            null,
            () -> {
              throw new AssertionError("no acquisition expected");
            });
    processes.releaseBridge(generation);
    assertEquals(1, oldLease.closed);
    assertEquals(0, currentLease.closed);
    assertTrue(processes.hasLiveLaunchers());
    assertTrue(state.runs.containsKey(generation));
    process.alive = false;
    assertFalse(processes.hasLiveLaunchers());
    assertFalse(state.runs.containsKey(generation));
    assertSame(current, state.currentRun);
    assertTrue(state.runs.containsKey(current.generation));
    state.io.shutdownNow();
  }
}
