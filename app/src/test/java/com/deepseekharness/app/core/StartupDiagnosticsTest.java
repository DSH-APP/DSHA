package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Test;

public class StartupDiagnosticsTest {
  static final class Fixture implements AutoCloseable {
    final File files = Files.createTempDirectory("startup-evidence").toFile();
    final JvmBackupFileSystem fs = new JvmBackupFileSystem();
    final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
    final StartupDiagnostics diagnostics =
        new StartupDiagnostics(
            files, fs, () -> 10_000L, (stage, detail) -> events.add(stage + ":" + detail), io);

    Fixture() throws Exception {
      diagnostics.begin(1, false);
    }

    public void close() throws Exception {
      try {
        diagnostics.drainForFactoryReset(2000);
      } finally {
        io.shutdownNow();
        fs.removeOwned(files.getParentFile(), files.getName());
      }
    }
  }

  @Test
  public void optionalDependencyWarningNearKnownPluginIsRelatedLogAndNeverAFailure()
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.diagnostics.output(
          1,
          "[DSHA_STARTUP] {\"type\":\"entry\",\"plugin\":\"optional-plugin\",\"path\":\"/root/plugins/optional-plugin\",\"ids\":[\"optional-module\"]}\n"
              + "warning: optional dependency missing /root/plugins/optional-plugin/file.js\n");
      assertTrue(fixture.diagnostics.snapshot().issues.isEmpty());
      assertFalse(fixture.diagnostics.hasExplicitStartupFailure(1));
      assertTrue(
          fixture.diagnostics.snapshot().log.contains("RELATED_PLUGIN_LOG[optional-plugin]"));
      assertTrue(fixture.events.isEmpty());
    }
  }

  @Test
  public void structuredFailuresRetainFreshEvidenceButOneThousandEventsHaveBoundedDiskWrites()
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      for (int i = 0; i < 1000; i++)
        fixture.diagnostics.output(
            1,
            "[DSHA_STARTUP] {\"type\":\"issue\",\"plugin\":\"failed-plugin\",\"message\":\"actual-error-"
                + i
                + "\",\"fatal\":true}\n");
      assertEquals("actual-error-999", fixture.diagnostics.snapshot().issues.get("failed-plugin"));
      assertTrue(fixture.diagnostics.hasExplicitStartupFailure(1));
      assertEquals(
          StartupIssueBudget.MAX_PER_PLUGIN,
          fixture.events.stream().filter(s -> s.startsWith("STARTUP_ERROR:")).count());
      fixture.diagnostics.begin(2, false);
      int before = fixture.events.size();
      fixture.diagnostics.issue(1, "old-plugin", "stale-error");
      assertEquals(before, fixture.events.size());
      assertTrue(fixture.diagnostics.snapshot().issues.isEmpty());
    }
  }

  @Test
  public void duplicateStructuredKeysCannotTurnRawProcessOutputIntoAnAuthoritativeIssue()
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.diagnostics.output(
          1,
          "[DSHA_STARTUP] {\"type\":\"issue\",\"type\":\"stage\",\"plugin\":\"fake\",\"message\":\"ambiguous\"}\n");
      assertTrue(fixture.diagnostics.snapshot().issues.isEmpty());
      assertFalse(fixture.diagnostics.hasExplicitStartupFailure(1));
      assertTrue(fixture.diagnostics.snapshot().log.contains("ambiguous"));
      assertTrue(fixture.events.isEmpty());
    }
  }

  @Test
  public void incorrectlyTypedFatalFlagIsRawEvidenceWithoutAnAutomaticFailure() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.diagnostics.output(
          1,
          "[DSHA_STARTUP] {\"type\":\"issue\",\"plugin\":\"fake\",\"message\":\"ambiguous\",\"fatal\":\"false\"}\n");
      assertTrue(fixture.diagnostics.snapshot().issues.isEmpty());
      assertFalse(fixture.diagnostics.hasExplicitStartupFailure(1));
      assertTrue(fixture.events.isEmpty());
    }
  }
}
