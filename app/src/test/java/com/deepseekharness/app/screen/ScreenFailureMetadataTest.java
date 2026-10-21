package com.deepseekharness.app.screen;

import static org.junit.Assert.*;

import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.capabilities.ScreenTargetEvidence;
import com.deepseekharness.app.core.RuntimeSnapshot;
import java.util.Set;
import org.junit.Test;

/** Tests the real metadata boundary without starting Android services or calling a log sink. */
public class ScreenFailureMetadataTest {
  @Test
  public void uppercaseBodyCannotEscapeThroughApiCodeOrEventCode() {
    String body = "ACCOUNT_BALANCE_IS_NINE_MILLION";
    // The public API keeps its existing shape rule; that rule does not establish log safety.
    assertEquals(body, ScreenScreenshotCapture.safeCode(new IllegalStateException(body)));
    var fields = MainScreenActions.diagnosticFields(body, true, 9L, "bridge-test-id");
    assertEquals("UNKNOWN_CODE", fields.get("apiCode"));
    assertEquals(true, fields.get("apiCodeOmitted"));
    assertFalse(fields.containsValue(body));
    Set<String> events =
        Set.of("SCREEN_MAIN_FAILURE", "SCREEN_CAPTURE_FAILURE", "SCREEN_CAPTURE_LATE_FAILURE");
    for (var stage : MainScreenActions.FailureStage.values()) {
      assertTrue(events.contains(stage.code));
      assertNotEquals(body, stage.code);
    }
  }

  @Test
  public void fixedLiteralMetadataAndSubmissionStateAreRetained() {
    var fields =
        MainScreenActions.diagnosticFields("SCREEN_TARGET_CHANGED", false, 17L, "bridge-test-id");
    assertEquals("SCREEN_TARGET_CHANGED", fields.get("apiCode"));
    assertEquals(false, fields.get("apiCodeOmitted"));
    assertEquals(false, fields.get("submitted"));
    assertEquals(17L, fields.get("serviceEpoch"));
    assertEquals("bridge-test-id", fields.get("bridgeRunId"));
  }

  @Test
  public void headerLikeBusinessRequestIdIsExcludedFromActualDiagnosticMetadata() {
    String externalId = "UNREGISTERED_CUSTOMER_ORDER_ABC";
    assertTrue(externalId.matches("[A-Za-z0-9_-]{1,80}"));
    var request =
        new CapabilityRequest(
            externalId,
            "screen",
            "input",
            new RuntimeSnapshot.Epoch("internal-owner", 7, "internal-runtime"),
            "internal-owner",
            "internal-bridge",
            new ScreenTargetEvidence(0, 41, "org.example.fixture"),
            3,
            5,
            1000);
    var identity = MainScreenActions.diagnosticIdentity(request);
    var fields =
        MainScreenActions.diagnosticFields("SCREEN_TARGET_CHANGED", true, 11L, request.bridgeRunId);
    assertEquals("", identity.requestId);
    assertFalse(identity.fields().containsValue(externalId));
    assertFalse(fields.containsValue(externalId));
    assertEquals("NO_TRUSTED_INTERNAL_REQUEST_ID", fields.get("requestCorrelation"));
    assertEquals("internal-runtime", identity.runId);
    assertEquals("internal-owner:7:internal-runtime", identity.epoch);
    assertEquals("internal-bridge", fields.get("bridgeRunId"));
    assertEquals(externalId, request.requestId);
  }
}
