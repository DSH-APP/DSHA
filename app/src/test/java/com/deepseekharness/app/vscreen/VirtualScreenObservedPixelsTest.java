package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import com.deepseekharness.app.util.VirtualScreenInputResult;
import com.deepseekharness.app.util.VirtualScreenPolicy;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real raw observation owner and hashes; controlled bitmaps are not Android display evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenObservedPixelsTest {
  private VirtualScreenDisplaySession session;

  @Before
  public void prepare() {
    session =
        new VirtualScreenDisplaySession(
            32,
            32,
            51,
            VirtualScreenInput.commandForTest(
                51, (argv, timeout) -> VirtualScreenInputResult.REJECTED));
    session.frame = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
    session.frame.eraseColor(0xff123456);
    session.sequence = 10;
  }

  @After
  public void close() {
    session.close();
  }

  private JSONObject metadata() throws Exception {
    return new JSONObject()
        .put("ok", true)
        .put("active", true)
        .put("displayId", 51)
        .put("generation", session.generation)
        .put("package", "com.example.calculator")
        .put("width", 32)
        .put("height", 32);
  }

  private JSONObject capture() throws Exception {
    return session.observations.capture(session, this::metadata);
  }

  @Test
  public void unrelatedPixelsMayAdvanceWhileLegacyTapStillRejectsOldFrame() throws Exception {
    JSONObject source = capture();
    assertTrue(source.optBoolean("ok"));
    assertTrue(source.getString("previewB64").length() > 0);
    session.frame.setPixel(31, 31, 0xffabcdef);
    session.sequence = 11;
    JSONObject check =
        session.observations.region(
            session, source.getString("observationId"), new Rect(2, 2, 16, 16));
    assertTrue(check.optBoolean("ok"));
    assertEquals(10, check.getLong("sourceFrameSeq"));
    assertEquals(11, check.getLong("validatedCurrentSeq"));
    assertEquals(
        VirtualScreenPolicy.FRAME_EXPIRED, VirtualScreenPolicy.inputRejection(10, 11, 10, 0));
    assertEquals(10, session.observed);
  }

  @Test
  public void changedInsideOriginalRegionIsRejectedWithoutRenewingObservation() throws Exception {
    JSONObject source = capture();
    session.frame.setPixel(4, 4, 0xffabcdef);
    session.sequence = 11;
    JSONObject check =
        session.observations.region(
            session, source.getString("observationId"), new Rect(2, 2, 16, 16));
    assertEquals("ANCHOR_REGION_CHANGED", check.getString("error"));
    assertEquals(10, session.observed);
  }

  @Test
  public void thirdObservationEvictsOldestAndAllReleasesAreIdempotent() throws Exception {
    String a = capture().getString("observationId"), b = capture().getString("observationId");
    long two = session.observations.allocatedBytes();
    assertEquals(2, session.observations.retainedCount());
    String c = capture().getString("observationId");
    assertEquals(2, session.observations.retainedCount());
    assertEquals(two, session.observations.allocatedBytes());
    assertEquals(
        "OBSERVATION_EXPIRED",
        session.observations.region(session, a, new Rect(1, 1, 2, 2)).optString("error"));
    session.observations.remove(a);
    session.observations.remove(b);
    session.observations.remove(b);
    session.observations.remove(c);
    assertEquals(0, session.observations.allocatedBytes());
  }

  @Test
  public void ttlBoundaryAndSessionCloseReleaseAllOwnedRawCopies() throws Exception {
    String id = capture().getString("observationId");
    var owner = session.observations;
    owner.prune(VirtualScreenCore.elapsed() + VirtualScreenObservedPixels.TTL_MS + 1);
    assertEquals(0, owner.retainedCount());
    assertEquals(0, owner.allocatedBytes());
    assertEquals(
        "OBSERVATION_EXPIRED", owner.region(session, id, new Rect(1, 1, 2, 2)).optString("error"));
    capture();
    session.close();
    assertEquals(0, owner.allocatedBytes());
  }

  @Test
  public void oversizedSingleImageDoesNotAllocateObservationOrChangeObserved() throws Exception {
    session.frame.recycle();
    session.frame = Bitmap.createBitmap(3072, 2049, Bitmap.Config.ARGB_8888);
    session.observed = 9;
    assertTrue(session.frame.getAllocationByteCount() > VirtualScreenObservedPixels.BYTE_LIMIT);
    assertEquals("OBSERVATION_IMAGE_BUDGET", capture().optString("error"));
    assertEquals(0, session.observations.allocatedBytes());
    assertEquals(9, session.observed);
  }

  @Test
  public void invalidMetadataNeverPublishesRetainedRawCopy() throws Exception {
    JSONObject result =
        session.observations.capture(session, () -> metadata().put("generation", "wrong"));
    assertEquals("STALE_GENERATION", result.optString("error"));
    assertEquals(0, session.observations.retainedCount());
    assertEquals(-1, session.observed);
  }

  @Test
  public void agentImageUsesTheSamePixelCoordinatesAsTheRetainedAnchorSource() throws Exception {
    session.close();
    session =
        new VirtualScreenDisplaySession(
            1008,
            1792,
            51,
            VirtualScreenInput.commandForTest(
                51, (argv, timeout) -> VirtualScreenInputResult.REJECTED));
    session.frame = Bitmap.createBitmap(1008, 1792, Bitmap.Config.ARGB_8888);
    session.frame.eraseColor(0xff123456);
    session.sequence = 10;
    JSONObject source =
        session.observations.capture(
            session, () -> metadata().put("width", 1008).put("height", 1792));
    assertTrue(source.toString(), source.optBoolean("ok"));
    byte[] jpeg =
        android.util.Base64.decode(source.getString("previewB64"), android.util.Base64.DEFAULT);
    var size = new android.graphics.BitmapFactory.Options();
    size.inJustDecodeBounds = true;
    android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, size);
    assertEquals(1008, size.outWidth);
    assertEquals(1792, size.outHeight);
    assertEquals(1008, source.getInt("previewWidth"));
    assertEquals(1792, source.getInt("previewHeight"));
    assertEquals(10, source.getLong("sourceFrameSeq"));
    assertTrue(
        session
            .observations
            .region(session, source.getString("observationId"), new Rect(820, 1200, 920, 1300))
            .optBoolean("ok"));
  }
}
