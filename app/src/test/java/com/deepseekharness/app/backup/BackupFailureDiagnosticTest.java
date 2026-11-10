package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

public final class BackupFailureDiagnosticTest {
  @Test
  public void localizedFailureShowsProjectBoundaryWithoutMessageOrPath() {
    IOException error = new IOException("等待 /private/user/conversation?token=secret 的进程停止");
    error.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("java.io.File", "open", "/private/data.txt", 12),
          new StackTraceElement(
              "com.deepseekharness.app.core.MaintenanceCoordinator",
              "stopWeb",
              "/private/compiler/source.java",
              89)
        });
    String actual = BackupFailureDiagnostic.describe("PREPARING", error);
    assertTrue(actual.contains("stage=PREPARING cause=java.io.IOException"));
    assertTrue(actual.contains("MaintenanceCoordinator.stopWeb:89"));
    assertFalse(actual.contains("secret"));
    assertFalse(actual.contains("private"));
    assertFalse(actual.contains("等待"));
    assertFalse(actual.contains("java.io.File"));
  }

  @Test
  public void nestedExceptionTypesAndProjectLinesRemainAvailable() {
    IOException cause = new IOException("SOURCE_CHANGED");
    cause.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement(
              "com.deepseekharness.app.backup.AndroidBackupFileSystem", "read", "file.java", 177)
        });
    IOException outer = new IOException("/storage/private", cause);
    outer.setStackTrace(new StackTraceElement[0]);
    String actual = BackupFailureDiagnostic.describe("CAPTURING", outer);
    assertEquals(2, actual.split(" cause=java.io.IOException", -1).length - 1);
    assertFalse(actual.contains("SOURCE_CHANGED"));
    assertTrue(actual.contains("AndroidBackupFileSystem.read:177"));
    assertFalse(actual.contains("/storage"));
  }

  @Test
  public void invalidStageAndSpoofedStackNamesCannotInjectPrivateTextOrNewlines() {
    IOException error = new IOException("secret");
    error.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement(
              "com.deepseekharness.app.invalid\nsecret", "read/path", "private", 2)
        });
    String actual = BackupFailureDiagnostic.describe("/private\nsecret", error);
    assertTrue(actual.startsWith("stage=UNKNOWN"));
    assertFalse(actual.contains("secret"));
    assertFalse(actual.contains("\n"));
    assertFalse(actual.contains("/"));
  }

  @Test
  public void cyclicCausesAndLargeStacksRemainBounded() {
    IOException first = new IOException("first"), second = new IOException("second");
    first.initCause(second);
    second.initCause(first);
    StackTraceElement[] stack = new StackTraceElement[1000];
    java.util.Arrays.fill(
        stack,
        new StackTraceElement("com.deepseekharness.app.backup.HostSnapshot", "create", "x", 1));
    first.setStackTrace(stack);
    second.setStackTrace(stack);
    String actual = BackupFailureDiagnostic.describe("CAPTURING", first);
    assertEquals(2, actual.split(" cause=", -1).length - 1);
    assertEquals(8, actual.split(" at=", -1).length - 1);
    assertTrue(actual.length() < 1000);
  }

  @Test
  public void nullFailureStillReturnsOnlyStableStageAndCode() {
    assertEquals("stage=UNKNOWN", BackupFailureDiagnostic.describe(null, null));
  }

  @Test
  public void exceptionMessagesAreNeverReadEvenWhenTheyLookLikeCodes() {
    IOException error =
        new IOException("PRIVATE_UPPERCASE_SECRET") {
          @Override
          public String getMessage() {
            throw new AssertionError("Diagnostic must not read exception message");
          }
        };
    assertFalse(BackupFailureDiagnostic.describe("PREPARING", error).contains("SECRET"));
  }

  @Test
  public void suppressedCleanupKeepsSafeLocationsWithoutMessagesOrPaths() {
    IOException original = new IOException("synthetic private primary content");
    original.setStackTrace(new StackTraceElement[0]);
    IOException cleanup =
        new IOException("synthetic private cleanup content") {
          @Override
          public String getMessage() {
            throw new AssertionError("Diagnostic must not read suppressed messages");
          }
        };
    cleanup.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement(
              "com.deepseekharness.app.backup.BackupTemporaryFiles", "close", "/private/file", 31)
        });
    original.addSuppressed(cleanup);
    String actual = BackupFailureDiagnostic.describe("EXPORTING_MEDIASTORE", original);
    assertTrue(actual.contains("suppressed="));
    assertTrue(actual.contains("BackupTemporaryFiles.close:31"));
    assertFalse(actual.contains("private"));
    assertFalse(actual.contains("content"));
  }

  @Test
  public void suppressedCyclesAndBranchesShareTheEightExceptionBudget() {
    IOException original = new IOException("original");
    original.setStackTrace(new StackTraceElement[0]);
    for (int i = 0; i < 100; i++) {
      IOException cleanup = new IOException("private cleanup " + i);
      cleanup.setStackTrace(new StackTraceElement[0]);
      cleanup.addSuppressed(original);
      original.addSuppressed(cleanup);
    }
    String actual = BackupFailureDiagnostic.describe("CAPTURING_SNAPSHOT", original);
    assertEquals(7, actual.split(" suppressed=", -1).length - 1);
    assertTrue(actual.length() < 500);
  }
}
