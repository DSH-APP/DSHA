package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenTouchOutcomeTest {
  @Test
  public void frameFailureUsesOnlyFixedReasonAndNumericMetadata() throws Exception {
    String text =
        VirtualScreenActivity.touchOutcomeText(
            new JSONObject()
                .put("ok", false)
                .put("error", "STALE_FRAME")
                .put("reason", "FRAME_NOT_OBSERVED")
                .put("requestedFrameSeq", 129)
                .put("currentFrameSeq", 130)
                .put("observedFrameSeq", 130));
    assertTrue(text.contains("requested=129 current=130 observed=130"));
  }

  @Test
  public void arbitraryCauseErrorAndReasonCannotReachTouchFeedback() throws Exception {
    String text =
        VirtualScreenActivity.touchOutcomeText(
            new JSONObject()
                .put("ok", false)
                .put("error", "SYNTHETIC_PRIVATE_ERROR")
                .put("reason", "SYNTHETIC_PRIVATE_REASON")
                .put("cause", "SYNTHETIC_PRIVATE_CAUSE")
                .put("previewB64", "SYNTHETIC_PRIVATE_PIXELS"));
    assertFalse(text.contains("SYNTHETIC_PRIVATE"));
    assertTrue(text.contains("UNCLASSIFIED_FAILURE"));
  }

  @Test
  public void executionUnknownCannotBeRenderedAsSuccessOrReplayPermission() throws Exception {
    String expected =
        VirtualScreenActivity.touchOutcomeText(
            new JSONObject().put("error", "INPUT_RESULT_UNKNOWN"));
    assertEquals(
        expected,
        VirtualScreenActivity.touchOutcomeText(
            new JSONObject()
                .put("ok", true)
                .put("executionUnknown", true)
                .put("cause", "SYNTHETIC_PRIVATE_CAUSE")));
  }

  @Test
  public void missingReceiptIsInputUnknownAndMalformedCountersCannotEchoText() throws Exception {
    String expected =
        VirtualScreenActivity.touchOutcomeText(
            new JSONObject().put("error", "INPUT_RESULT_UNKNOWN"));
    assertEquals(expected, VirtualScreenActivity.touchOutcomeText(null));
    String text =
        VirtualScreenActivity.touchOutcomeText(
            new JSONObject()
                .put("error", "STALE_FRAME")
                .put("requestedFrameSeq", "SYNTHETIC_PRIVATE_COUNTER"));
    assertFalse(text.contains("SYNTHETIC_PRIVATE"));
    assertTrue(text.contains("requested=-1 current=-1 observed=-1"));
  }
}
