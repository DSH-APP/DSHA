package com.deepseekharness.app.core;

import java.io.IOException;
import java.util.*;
import com.deepseekharness.app.util.BoundedProcessRunner;
import org.junit.Test;
import static org.junit.Assert.*;

public class StartupPipelineTest {
  static final class Fixture
      implements StartupPipeline.Runtime, StartupPipeline.Diagnostics, StartupPipeline.Actions {
    final List<String> calls = new ArrayList<>();
    boolean current = true;
    String failure = "";
    String staleAt = "", stopError = "";
    String casReceipt = receipt(false, 0, "");
    BoundedProcessRunner.Result casOutcome;

    void step(String name) throws IOException {
      calls.add(name);
      if (name.equals(staleAt)) current = false;
      if (name.equals(failure)) throw new IOException(name);
    }

    public void credentials() throws Exception {
      step("credentials");
    }

    public void stopLan() {
      calls.add("lan-stop");
    }

    public String stopWeb() throws IOException {
      step("stop");
      return stopError;
    }

    public void tools() throws Exception {
      step("tools");
    }

    public boolean ready() {
      return true;
    }

    public boolean offlineBundle() {
      return true;
    }

    public void extract() throws Exception {
      step("extract");
    }

    public String migration(String id) throws Exception {
      step("migration");
      return "DSHA_RC1_MIGRATION={\"status\":\"already\",\"protectionComplete\":true}";
    }

    public String register() throws Exception {
      step("register");
      return "BUILTIN_REGISTER_OK";
    }

    public String legacyPlugins() throws Exception {
      step("legacy");
      return "ok";
    }

    public BoundedProcessRunner.Result materializeCasLinks() throws Exception {
      step("cas-copy");
      return casOutcome == null ? BoundedProcessRunner.localCompletion(casReceipt) : casOutcome;
    }

    public String recordId() {
      return "fixture";
    }

    public void message(long g, String s) {}

    public void issue(long g, String n, String s) {
      calls.add("issue");
    }

    public boolean current() {
      return current;
    }

    public void stage(String value) {}

    public void status(String value) {}

    public void releasePrior() {
      calls.add("release-prior");
    }

    public void recoverData() throws Exception {
      step("recover");
    }

    public void cleanup() throws IOException {
      step("cleanup");
    }

    public void beforeLaunch(String id) throws IOException {
      step("activate");
    }

    public String safeProfile() throws IOException {
      step("safe-profile");
      return "dsha-recovery-0123456789abcdef";
    }

    public boolean unlockStartup() throws IOException {
      step("unlock");
      return current;
    }

    public void checkpoint(String id) throws Exception {
      step("checkpoint");
    }
  }

  @Test
  public void ordinaryPreparationIsOrderedAndDoesNotOwnProcessLaunch() throws Exception {
    Fixture f = new Fixture();
    assertEquals("web", new StartupPipeline(f, f).prepare(1, false, f));
    assertEquals(
        List.of(
            "credentials",
            "lan-stop",
            "stop",
            "release-prior",
            "tools",
            "recover",
            "cleanup",
            "migration",
            "cas-copy",
            "register",
            "legacy",
            "activate",
            "unlock",
            "checkpoint"),
        f.calls);
  }

  @Test
  public void credentialsOrMigrationFailureCannotPublishOrLaunch() throws Exception {
    for (String stage : List.of("credentials", "migration")) {
      Fixture f = new Fixture();
      f.failure = stage;
      assertThrows(IOException.class, () -> new StartupPipeline(f, f).prepare(1, false, f));
      assertFalse(f.calls.contains("unlock"));
      assertFalse(f.calls.contains("activate"));
      if (stage.equals("credentials")) assertEquals(List.of("credentials"), f.calls);
    }
  }

  @Test
  public void safeProfileSkipsUserPluginActivationAndSnapshots() throws Exception {
    Fixture f = new Fixture();
    assertEquals("dsha-recovery-0123456789abcdef", new StartupPipeline(f, f).prepare(2, true, f));
    assertFalse(f.calls.contains("register"));
    assertFalse(f.calls.contains("legacy"));
    assertFalse(f.calls.contains("activate"));
    assertFalse(f.calls.contains("checkpoint"));
    assertFalse(f.calls.contains("cas-copy"));
    assertTrue(f.calls.contains("migration"));
    assertTrue(f.calls.contains("unlock"));
  }

  @Test
  public void staleGenerationPerformsNoSteps() throws Exception {
    Fixture f = new Fixture();
    f.current = false;
    assertNull(new StartupPipeline(f, f).prepare(1, false, f));
    assertTrue(f.calls.isEmpty());
  }

  private static String receipt(boolean changed, int count, String operation) {
    return "PLUGIN_RESULT: {\"status\":\"ok\",\"algorithm\":\"DSHA_PNPM_CAS_COPY_V1\","
        + "\"profile\":\"web\",\"storeReadOnly\":true,\"changed\":"
        + changed
        + ",\"migratedFiles\":"
        + count
        + ",\"operationId\":\""
        + operation
        + "\"}\n";
  }

  @Test
  public void casCopyRunsOnceOnlyAfterStopAndBeforeUserPluginPreparation() throws Exception {
    Fixture f = new Fixture();
    f.casReceipt =
        "native diagnostic before result\r\n"
            + receipt(true, 439, "1c1a39fc-4a63-4bec-af7c-79805f53b097").replace("\n", "\r\n")
            + "native diagnostic after result\r\n";
    assertEquals("web", new StartupPipeline(f, f).prepare(1, false, f));
    assertEquals(1, Collections.frequency(f.calls, "cas-copy"));
    assertTrue(f.calls.indexOf("stop") < f.calls.indexOf("cas-copy"));
    assertTrue(f.calls.indexOf("release-prior") < f.calls.indexOf("cas-copy"));
    assertTrue(f.calls.indexOf("migration") < f.calls.indexOf("cas-copy"));
    assertTrue(f.calls.indexOf("cas-copy") < f.calls.indexOf("register"));
    assertTrue(f.calls.indexOf("cas-copy") < f.calls.indexOf("activate"));
    assertTrue(f.calls.indexOf("cas-copy") < f.calls.indexOf("unlock"));
  }

  @Test
  public void stopFailureOrObsoleteGenerationCannotMaterializeOrUnlock() throws Exception {
    Fixture stopped = new Fixture();
    stopped.stopError = "old guest exit unconfirmed";
    assertThrows(
        IOException.class, () -> new StartupPipeline(stopped, stopped).prepare(1, false, stopped));
    assertFalse(stopped.calls.contains("cas-copy"));
    assertFalse(stopped.calls.contains("unlock"));
    Fixture stale = new Fixture();
    stale.staleAt = "migration";
    assertNull(new StartupPipeline(stale, stale).prepare(1, false, stale));
    assertFalse(stale.calls.contains("cas-copy"));
    assertFalse(stale.calls.contains("unlock"));
  }

  @Test
  public void interruptionOrGenerationChangeAfterRepairNeverStartsUserPlugins() throws Exception {
    Fixture stale = new Fixture();
    stale.staleAt = "cas-copy";
    assertNull(new StartupPipeline(stale, stale).prepare(1, false, stale));
    assertEquals(1, Collections.frequency(stale.calls, "cas-copy"));
    assertFalse(stale.calls.contains("register"));
    assertFalse(stale.calls.contains("unlock"));
    Fixture interrupted = new Fixture();
    try {
      Thread.currentThread().interrupt();
      assertThrows(
          java.io.InterruptedIOException.class,
          () -> new StartupPipeline(interrupted, interrupted).prepare(1, false, interrupted));
      assertFalse(interrupted.calls.contains("cas-copy"));
      assertFalse(interrupted.calls.contains("unlock"));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void pendingUnknownMalformedOrContradictoryRepairReceiptsBlockWithoutReplay()
      throws Exception {
    String ok = receipt(false, 0, "");
    for (String invalid :
        List.of(
            "",
            "diagnostic only",
            ok + ok,
            "PLUGIN_RESULT: {\"status\":\"pending\",\"automaticRetry\":false}",
            "PLUGIN_RESULT: {\"status\":\"unknown\"}",
            ok.replace("\"web\"", "\"other-profile\""),
            ok.replace("DSHA_PNPM_CAS_COPY_V1", "future-algorithm"),
            ok.replace("\"storeReadOnly\":true", "\"storeReadOnly\":false"),
            ok.replace("\"changed\":false", "\"changed\":\"false\""),
            ok.replace("\"migratedFiles\":0", "\"migratedFiles\":-1"),
            ok.replace("\"migratedFiles\":0", "\"migratedFiles\":0.5"),
            ok.replace("\"migratedFiles\":0", "\"migratedFiles\":true"),
            ok.replace("\"status\":\"ok\"", "\"status\":\"error\",\"status\":\"ok\""),
            ok.replace("}\n", "} trailing\n"),
            receipt(true, 0, ""),
            receipt(false, 1, ""),
            receipt(true, 1, "not-a-uuid"))) {
      Fixture f = new Fixture();
      f.casReceipt = invalid;
      assertThrows(
          "Receipt must not authorize startup: " + invalid,
          IOException.class,
          () -> new StartupPipeline(f, f).prepare(1, false, f));
      assertEquals(1, Collections.frequency(f.calls, "cas-copy"));
      assertFalse(f.calls.contains("register"));
      assertFalse(f.calls.contains("activate"));
      assertFalse(f.calls.contains("unlock"));
    }
  }

  @Test
  public void realNonzeroTruncatedAndTimedOutCommandsCannotUseAnOkReceipt() throws Exception {
    for (BoundedProcessRunner.Result outcome :
        List.of(result(7, false, 65536), result(0, false, 8), result(0, true, 65536))) {
      Fixture f = new Fixture();
      f.casOutcome = outcome;
      assertThrows(IOException.class, () -> new StartupPipeline(f, f).prepare(1, false, f));
      assertEquals(1, Collections.frequency(f.calls, "cas-copy"));
      assertFalse(f.calls.contains("register"));
      assertFalse(f.calls.contains("unlock"));
    }
  }

  private static BoundedProcessRunner.Result result(int code, boolean live, int limit)
      throws Exception {
    Process child =
        new Process() {
          boolean running = live;
          final java.io.InputStream output =
              new java.io.ByteArrayInputStream(
                  receipt(false, 0, "").getBytes(java.nio.charset.StandardCharsets.UTF_8));

          public java.io.OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
          }

          public java.io.InputStream getInputStream() {
            return output;
          }

          public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
          }

          public int waitFor() {
            throw new AssertionError("No unbounded wait");
          }

          public int exitValue() {
            if (running) throw new IllegalThreadStateException();
            return code;
          }

          public void destroy() {
            running = false;
          }
        };
    return BoundedProcessRunner.collect(child, live ? 10 : 1000, limit, Process::destroy);
  }

  private static final class PriorSession implements AutoCloseable {
    final HarnessSessionState state = new HarnessSessionState();
    final WebLifecycleController.WebRun old, current;
    final int[] released = new int[2], confirmations = new int[1];
    boolean confirmed = true;
    final StartupEnvironmentActions actions;

    PriorSession() {
      long first = state.lifecycle.beginStart(false, false);
      old = new WebLifecycleController.WebRun(first);
      state.runs.put(first, old);
      state.lifecycle.finishStart(first);
      state.lifecycle.beginStop();
      state.lifecycle.finishStop(state.lifecycle.generation());
      long generation = state.lifecycle.beginStart(false, false);
      current = new WebLifecycleController.WebRun(generation);
      state.runs.put(generation, current);
      state.currentRun = current;
      old.bridge = lease(0);
      current.bridge = lease(1);
      var manager =
          new com.deepseekharness.app.runtime.WebProcessManager(null) {
            @Override
            public boolean confirmStopped(boolean launcherAlive) {
              confirmations[0]++;
              return !launcherAlive && confirmed;
            }
          };
      var processes =
          new WebProcessSession(
              state,
              manager,
              () -> {
                throw new AssertionError("No bridge acquisition during prior exit confirmation");
              });
      actions =
          new StartupEnvironmentActions(
              null, state, processes, generation, value -> {}, value -> {});
    }

    private WebProcessSession.BridgeLease lease(int index) {
      return new WebProcessSession.BridgeLease() {
        public void ensureStarted() {
          throw new AssertionError("No bridge restart during stop proof");
        }

        public void close() {
          released[index]++;
        }
      };
    }

    public void close() {
      state.io.shutdownNow();
    }
  }

  @Test
  public void actualPriorAdapterCannotReleaseProtectionWithoutGuestAndLauncherProof()
      throws Exception {
    try (PriorSession session = new PriorSession()) {
      session.confirmed = false;
      assertThrows(IOException.class, session.actions::releasePrior);
      assertEquals(1, session.confirmations[0]);
      assertArrayEquals(new int[] {0, 0}, session.released);
      session.confirmed = true;
      session.actions.releasePrior();
      assertEquals(2, session.confirmations[0]);
      assertArrayEquals(new int[] {1, 0}, session.released);
    }
  }

  @Test
  public void actualPriorAdapterDoesNotSignalAnUnknownLiveLauncherOrReleaseItsBridge()
      throws Exception {
    try (PriorSession session = new PriorSession()) {
      int[] destroyed = {0};
      session.old.launcher =
          new Process() {
            public java.io.OutputStream getOutputStream() {
              return java.io.OutputStream.nullOutputStream();
            }

            public java.io.InputStream getInputStream() {
              return java.io.InputStream.nullInputStream();
            }

            public java.io.InputStream getErrorStream() {
              return java.io.InputStream.nullInputStream();
            }

            public int waitFor() {
              throw new AssertionError("No unbounded wait");
            }

            public int exitValue() {
              throw new IllegalThreadStateException();
            }

            public void destroy() {
              destroyed[0]++;
            }
          };
      assertThrows(IOException.class, session.actions::releasePrior);
      assertEquals(0, destroyed[0]);
      assertEquals(0, session.confirmations[0]);
      assertArrayEquals(new int[] {0, 0}, session.released);
    }
  }

  @Test
  public void obsoleteOrInterruptedPriorAdapterCannotAdvancePreparation() throws Exception {
    try (PriorSession session = new PriorSession()) {
      session.state.lifecycle.beginStop();
      session.actions.releasePrior();
      assertEquals(0, session.confirmations[0]);
      assertArrayEquals(new int[] {0, 0}, session.released);
    }
    try (PriorSession session = new PriorSession()) {
      try {
        Thread.currentThread().interrupt();
        assertThrows(java.io.InterruptedIOException.class, session.actions::releasePrior);
        assertEquals(0, session.confirmations[0]);
        assertArrayEquals(new int[] {0, 0}, session.released);
      } finally {
        Thread.interrupted();
      }
    }
  }
}
