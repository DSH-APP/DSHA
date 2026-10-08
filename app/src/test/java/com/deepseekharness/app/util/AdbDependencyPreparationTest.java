package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdbDependencyPreparationTest {
  private static final class Steps implements AdbDependencyPreparation.Steps {
    boolean firstReady, retryReady;
    int probes, fills, installs;
    String filled = "WHEELS_CACHE_READY: verified";
    String installed = "WHEELS_JAVA_EXTRACTED: verified";

    public boolean importsReady() {
      return probes++ == 0 ? firstReady : retryReady;
    }

    public String fillCache() {
      fills++;
      return filled;
    }

    public String installCache() {
      installs++;
      return installed;
    }
  }

  @Test
  public void installedImportsDoNotDependOnWheelCache() {
    Steps steps = new Steps();
    steps.firstReady = true;
    assertTrue(AdbDependencyPreparation.prepare(steps).continueSetup);
    assertEquals(0, steps.fills);
    assertEquals(0, steps.installs);
  }

  @Test
  public void missingImportsUseVerifiedCacheAndInstallBeforeSetup() {
    Steps steps = new Steps();
    var result = AdbDependencyPreparation.prepare(steps);
    assertTrue(result.continueSetup);
    assertEquals(1, steps.fills);
    assertEquals(1, steps.installs);
    assertTrue(
        result.output.indexOf("WHEELS_CACHE_READY")
            < result.output.indexOf("WHEELS_JAVA_EXTRACTED"));
  }

  @Test
  public void failedInjectionAndMissingImportsCannotPretendEmptyCacheIsReady() {
    Steps steps = new Steps();
    steps.filled = "WHEELS_INJECT_FAIL: PARENT_LINK; CACHE_MISSING";
    var result = AdbDependencyPreparation.prepare(steps);
    assertFalse(result.continueSetup);
    assertEquals(2, steps.probes);
    assertEquals(0, steps.installs);
    assertTrue(result.output.contains("CACHE_MISSING"));
    assertFalse(AdbResult.marker(result.output, "SETUP_DONE"));
    assertFalse(AdbResult.marker(result.output, "WHEELS_OPTIONAL_SKIP"));
  }

  @Test
  public void failedInjectionCanContinueOnlyWhenFreshImportsPass() {
    Steps steps = new Steps();
    steps.retryReady = true;
    steps.filled = "WHEELS_INJECT_FAIL: PARENT_LINK; CACHE_EMPTY";
    var result = AdbDependencyPreparation.prepare(steps);
    assertTrue(result.continueSetup);
    assertEquals(2, steps.probes);
    assertEquals(0, steps.installs);
    assertTrue(AdbResult.marker(result.output, "WHEELS_OPTIONAL_SKIP"));
    assertFalse(AdbResult.marker(result.output, "SETUP_DONE"));
  }

  @Test
  public void failedExtractionIsRecheckedWithoutRetryingInstallation() {
    Steps steps = new Steps();
    steps.installed = "WHEELS_EXTRACT_FAIL: cache unavailable";
    assertFalse(AdbDependencyPreparation.prepare(steps).continueSetup);
    assertEquals(1, steps.installs);
    Steps recovered = new Steps();
    recovered.installed = steps.installed;
    recovered.retryReady = true;
    assertTrue(AdbDependencyPreparation.prepare(recovered).continueSetup);
    assertEquals(1, recovered.installs);
  }

  @Test
  public void cancellationDoesNotLaunchInjectionOrClaimOptionalSkip() {
    Steps steps = new Steps();
    steps.firstReady = true;
    Thread.currentThread().interrupt();
    try {
      var result = AdbDependencyPreparation.prepare(steps);
      assertFalse(result.continueSetup);
      assertTrue(AdbResult.marker(result.output, "ADB_CANCELLED"));
      assertEquals(0, steps.probes);
      assertEquals(0, steps.fills);
      assertEquals(0, steps.installs);
    } finally {
      Thread.interrupted();
    }
  }
}
