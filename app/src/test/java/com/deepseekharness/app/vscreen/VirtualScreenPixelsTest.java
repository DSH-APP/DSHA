package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import com.deepseekharness.app.util.VirtualScreenInputResult;
import com.deepseekharness.app.util.VirtualScreenPolicy;
import java.io.IOException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 真实预览路径；仅编码失败和采集/关闭竞态由受控平台事实触发。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenPixelsTest {
  private VirtualScreenDisplaySession session;

  @Before
  public void frame() {
    session =
        new VirtualScreenDisplaySession(
            2,
            2,
            51,
            VirtualScreenInput.commandForTest(
                51, (argv, timeout) -> VirtualScreenInputResult.REJECTED));
    session.frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
    session.frame.eraseColor(0xff2196f3);
    session.sequence = 3;
    session.observed = 2;
    session.observedAt = 11;
  }

  @After
  public void close() {
    session.close();
  }

  private JSONObject metadata() throws Exception {
    return new JSONObject()
        .put("ok", true)
        .put("active", true)
        .put("displayId", session.id)
        .put("generation", session.generation);
  }

  private void previousObservationRemains() {
    assertEquals(2, session.observed);
    assertEquals(11, session.observedAt);
    assertEquals(-1, session.encodedSequence);
    assertEquals("", session.encoded);
    assertEquals(
        VirtualScreenPolicy.FRAME_NOT_OBSERVED,
        VirtualScreenPolicy.inputRejection(3, 3, session.observed, 0));
  }

  @Test
  public void failedEncoderCannotObserveEvenWhenCallerGuessesCurrentSequence() throws Exception {
    var result =
        VirtualScreenPixels.preview(
            session, 3, session.generation, this::metadata, (bitmap, output) -> false);
    assertEquals("FRAME_ENCODE_FAILED", result.getString("error"));
    previousObservationRemains();
  }

  @Test
  public void emptyEncoderSuccessIsNotAnImageObservation() throws Exception {
    var result =
        VirtualScreenPixels.preview(session, -1, null, this::metadata, (bitmap, output) -> true);
    assertEquals("FRAME_ENCODE_FAILED", result.getString("error"));
    previousObservationRemains();
  }

  @Test
  public void firstGuessedSinceStillReturnsPixelsBeforeObserving() throws Exception {
    var result = VirtualScreenPixels.preview(session, 3, session.generation, this::metadata);
    assertTrue(result.toString(), result.getBoolean("ok"));
    assertFalse(result.optBoolean("unchanged"));
    assertFalse(result.getString("previewB64").isEmpty());
    assertEquals(3, session.observed);
    assertEquals(3, session.encodedSequence);
    assertNull(VirtualScreenPolicy.inputRejection(3, 3, session.observed, 0));
  }

  @Test
  public void onlyPreviouslyPublishedSameFrameCanRenewWithoutPixels() throws Exception {
    VirtualScreenPixels.preview(session, -1, null, this::metadata);
    session.observed = -1; // 输入消费后，必须再次成功观察。
    var result =
        VirtualScreenPixels.preview(
            session,
            3,
            session.generation,
            this::metadata,
            (bitmap, output) -> {
              throw new AssertionError("同帧应复用已发布图片");
            });
    assertTrue(result.getBoolean("unchanged"));
    assertFalse(result.has("previewB64"));
    assertEquals(3, session.observed);
  }

  @Test
  public void previousFrameCacheCannotRenewAnUndeliveredNewFrame() throws Exception {
    VirtualScreenPixels.preview(session, -1, null, this::metadata);
    session.observed = -1;
    session.observedAt = 12;
    session.sequence = 4;
    var result =
        VirtualScreenPixels.preview(
            session, 4, session.generation, this::metadata, (bitmap, output) -> false);
    assertEquals("FRAME_ENCODE_FAILED", result.getString("error"));
    assertEquals(-1, session.observed);
    assertEquals(12, session.observedAt);
    assertEquals(3, session.encodedSequence);
    assertEquals(
        VirtualScreenPolicy.FRAME_NOT_OBSERVED,
        VirtualScreenPolicy.inputRejection(4, 4, session.observed, 0));
  }

  @Test
  public void missingFrameCannotRenewObservation() throws Exception {
    session.frame.recycle();
    session.frame = null;
    var result = VirtualScreenPixels.preview(session, 3, session.generation, this::metadata);
    assertEquals("FRAME_NOT_READY", result.getString("error"));
    previousObservationRemains();
  }

  @Test
  public void metadataFailureDoesNotPublishEncodedOrObservedState() {
    assertThrows(
        IOException.class,
        () ->
            VirtualScreenPixels.preview(
                session,
                -1,
                null,
                () -> {
                  throw new IOException("controlled metadata failure");
                }));
    previousObservationRemains();
  }

  @Test
  public void replacedFrameDuringEncodingCannotBePublishedOrObserved() throws Exception {
    var result =
        VirtualScreenPixels.preview(
            session,
            -1,
            null,
            this::metadata,
            (bitmap, output) -> {
              boolean encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 65, output);
              synchronized (session.lock) {
                session.sequence++;
              }
              return encoded;
            });
    assertEquals("STALE_FRAME", result.getString("error"));
    previousObservationRemains();
  }

  @Test
  public void closedSessionDuringEncodingCannotBePublishedOrObserved() throws Exception {
    var result =
        VirtualScreenPixels.preview(
            session,
            -1,
            null,
            this::metadata,
            (bitmap, output) -> {
              boolean encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 65, output);
              session.close();
              return encoded;
            });
    assertEquals("STALE_FRAME", result.getString("error"));
    previousObservationRemains();
  }

  @Test
  public void differentGenerationMetadataCannotPublishImageOrObservation() throws Exception {
    var result =
        VirtualScreenPixels.preview(
            session, -1, null, () -> metadata().put("generation", "another-display-generation"));
    assertEquals("STALE_GENERATION", result.getString("error"));
    previousObservationRemains();
  }
}
