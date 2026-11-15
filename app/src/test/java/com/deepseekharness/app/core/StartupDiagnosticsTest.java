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
    final java.util.concurrent.atomic.AtomicLong clock =
        new java.util.concurrent.atomic.AtomicLong(10_000L);
    final StartupDiagnostics diagnostics =
        new StartupDiagnostics(
            files, fs, clock::get, (stage, detail) -> events.add(stage + ":" + detail), io);

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
  public void optionalDependencyWarningNearKnownPluginIsPreservedOnceWithoutGuessedOwnership()
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.diagnostics.output(
          1,
          "[DSHA_STARTUP] {\"type\":\"entry\",\"plugin\":\"optional-plugin\",\"path\":\"/root/plugins/optional-plugin\",\"ids\":[\"optional-module\"]}\n"
              + "warning: optional dependency missing /root/plugins/optional-plugin/file.js\n");
      assertTrue(fixture.diagnostics.snapshot().issues.isEmpty());
      assertFalse(fixture.diagnostics.hasExplicitStartupFailure(1));
      String log = fixture.diagnostics.snapshot().log;
      assertTrue(
          log.contains(
              "warning: optional dependency missing /root/plugins/optional-plugin/file.js"));
      assertFalse(log.contains("RELATED_PLUGIN_LOG"));
      assertTrue(fixture.events.isEmpty());
    }
  }

  @Test
  public void dependencyStackDoesNotDuplicateOrAttributeAnAccountHttpRejection() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.diagnostics.output(
          1,
          "[DSHA_STARTUP] {\"type\":\"entry\",\"plugin\":\"@deepseek-ai/dsh-base\",\"path\":\"/root/plugins/base\",\"ids\":[\"base\"]}\n"
              + "[deepseek-account] request failed HTTP 403 token=private-secret\n"
              + "  at /root/plugins/base/node_modules/account/client.js:1\n");
      String log = fixture.diagnostics.snapshot().log;
      assertEquals(
          log.indexOf("request failed HTTP 403"), log.lastIndexOf("request failed HTTP 403"));
      assertTrue(log.contains("/root/plugins/base/node_modules/account/client.js:1"));
      assertFalse(log.contains("private-secret"));
      assertFalse(log.contains("RELATED_PLUGIN_LOG"));
      assertTrue(fixture.diagnostics.snapshot().issues.isEmpty());
      assertFalse(fixture.diagnostics.hasExplicitStartupFailure(1));
    }
  }

  @Test
  public void readyThenStructuredRuntimeFailureDoesNotChangeStartupResultOrDuration()
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.clock.set(12_000);
      fixture.diagnostics.backendReady(1);
      fixture.clock.set(15_000);
      fixture.diagnostics.browserReady(1);
      fixture.clock.set(1_000_000);
      fixture.diagnostics.output(
          1,
          "[DSHA_STARTUP] {\"type\":\"issue\",\"plugin\":\"account\",\"message\":\"HTTP 403 forbidden\",\"fatal\":true}\n");
      assertTrue(fixture.diagnostics.snapshot().issues.isEmpty());
      assertFalse(fixture.diagnostics.hasExplicitStartupFailure(1));
      assertEquals(5000, fixture.diagnostics.snapshot().elapsedMs);
      assertEquals(2000, fixture.diagnostics.snapshot().backendReadyMs);
      assertEquals(5000, fixture.diagnostics.snapshot().browserReadyMs);
      assertTrue(fixture.diagnostics.snapshot().log.contains("HTTP 403 forbidden"));
      assertTrue(fixture.events.stream().anyMatch(s -> s.startsWith("RUNTIME_ERROR:")));
      assertFalse(fixture.events.stream().anyMatch(s -> s.startsWith("STARTUP_ERROR:")));
      fixture.diagnostics.drainForFactoryReset(2000);
      assertEquals("ready", fixture.diagnostics.history().get(0).status);
      assertEquals(5000, fixture.diagnostics.history().get(0).elapsed);
    }
  }

  @Test
  public void observerStageBeforeAuthDoesNotClaimAuthenticationCompleted() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.diagnostics.stage(1, "等待鉴权链接");
      fixture.clock.set(20_000);
      fixture.diagnostics.output(1, "[DSHA_STARTUP] {\"type\":\"stage\",\"message\":\"检查启动配置\"}\n");
      assertFalse(fixture.diagnostics.snapshot().backendReady);
      assertEquals(-1, fixture.diagnostics.snapshot().backendReadyMs);
      assertFalse(fixture.diagnostics.snapshot().log.contains("完成「等待鉴权链接」"));
      fixture.diagnostics.backendReady(2);
      assertFalse(fixture.diagnostics.snapshot().backendReady);
      fixture.diagnostics.backendReady(1);
      assertTrue(fixture.diagnostics.snapshot().backendReady);
      assertEquals(10_000, fixture.diagnostics.snapshot().backendReadyMs);
    }
  }

  @Test
  public void unidentifiedPageFailureDoesNotBorrowDependencyStackOwner() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.diagnostics.output(
          1,
          "[DSHA_STARTUP] {\"type\":\"entry\",\"plugin\":\"base\",\"path\":\"/root/plugins/base\",\"ids\":[\"base\"]}\n");
      assertTrue(
          fixture.diagnostics.pageIssue(
              1,
              "unregistered-account",
              "failed at /root/plugins/base/node_modules/account/client.js",
              true));
      assertTrue(fixture.diagnostics.snapshot().issues.containsKey(""));
      assertFalse(fixture.diagnostics.snapshot().issues.containsKey("base"));
    }
  }

  @Test
  public void declaredPageEntryKeepsItsOwnerAndReadyPageErrorsRemainRuntimeOnly() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.diagnostics.output(
          1,
          "[DSHA_STARTUP] {\"type\":\"entry\",\"plugin\":\"mobile\",\"ids\":[\"mobile-client\"]}\n");
      assertTrue(
          fixture.diagnostics.pageIssue(1, "mobile-client", "client activation failed", true));
      assertEquals(
          "mobile-client：client activation failed",
          fixture.diagnostics.snapshot().issues.get("mobile"));
      fixture.diagnostics.begin(2, false);
      fixture.diagnostics.browserReady(2);
      assertFalse(fixture.diagnostics.pageIssue(2, "account", "HTTP 403", true));
      assertTrue(fixture.diagnostics.snapshot().issues.isEmpty());
      assertTrue(fixture.diagnostics.snapshot().log.contains("RUNTIME_ERROR: account：HTTP 403"));
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

  @Test
  public void invalidOptionalBundleWarningAllowsReadinessWhileActualFatalStillBlocks()
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      String warning =
          "[DSHA_STARTUP] {\"type\":\"issue\",\"plugin\":\"\",\"message\":\"插件清单含无效名称\",\"fatal\":false}\n";
      fixture.diagnostics.output(1, warning);
      assertFalse(fixture.diagnostics.hasExplicitStartupFailure(1));
      assertTrue(fixture.diagnostics.snapshot().log.contains("插件清单含无效名称"));
      fixture.diagnostics.backendReady(1);
      fixture.diagnostics.browserReady(1);
      assertTrue(fixture.diagnostics.snapshot().backendReady);
      assertTrue(fixture.diagnostics.snapshot().browserReady);
      fixture.diagnostics.begin(2, false);
      fixture.diagnostics.output(2, warning);
      fixture.diagnostics.output(
          2,
          "[DSHA_STARTUP] {\"type\":\"issue\",\"plugin\":\"\",\"message\":\"webserver actual failure\",\"fatal\":true}\n");
      assertTrue(fixture.diagnostics.hasExplicitStartupFailure(2));
      assertFalse(fixture.diagnostics.snapshot().browserReady);
      fixture.diagnostics.begin(3, false);
      fixture.diagnostics.output(
          3,
          "[DSHA_STARTUP] {\"type\":\"issue\",\"plugin\":\"\",\"message\":\"actual uncaught failure\"}\n");
      assertTrue(fixture.diagnostics.hasExplicitStartupFailure(3));
    }
  }
}
