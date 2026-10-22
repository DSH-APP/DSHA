package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Bitmap;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import java.util.ArrayList;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class VirtualScreenPreviewAdoptionTest {
  private VirtualScreenPreviewView view;
  private final ArrayList<Long> dispatched = new ArrayList<>();
  private boolean admitted = true;

  private JSONObject identity(long seq) throws Exception {
    return new JSONObject()
        .put("generation", "display-a")
        .put("frameSeq", seq)
        .put("width", 1008)
        .put("height", 1792);
  }

  private Bitmap pixels() {
    return Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
  }

  private void event(int action) {
    MotionEvent event = MotionEvent.obtain(10, 20, action, 100, 200, 0);
    try {
      view.onTouchEvent(event);
    } finally {
      event.recycle();
    }
  }

  @Before
  public void setup() {
    var parent = new FrameLayout(RuntimeEnvironment.getApplication());
    view = new VirtualScreenPreviewView(parent.getContext());
    parent.addView(view);
    parent.layout(0, 0, 200, 400);
    view.layout(0, 0, 200, 400);
    view.listener =
        new VirtualScreenPreviewView.Touch() {
          public boolean admit(JSONObject displayed, String stroke) {
            return admitted;
          }

          public void stream(String gen, long seq, String stroke, int action, float x, float y) {
            dispatched.add(seq);
          }

          public void input(
              String gen, long seq, float x1, float y1, float x2, float y2, int ms, boolean tap) {
            fail("The stream gesture must never fall back to a discrete replay");
          }
        };
  }

  @Test
  public void rejectedFrameDoesNotReplaceGesturePixelsOrSequence() throws Exception {
    Bitmap old = pixels();
    assertTrue(view.frame(old, identity(129)));
    event(MotionEvent.ACTION_DOWN);
    Bitmap newer = pixels();
    assertFalse(view.frame(newer, identity(130)));
    assertTrue(newer.isRecycled());
    assertFalse(old.isRecycled());
    event(MotionEvent.ACTION_UP);
    assertEquals(java.util.List.of(129L, 129L), dispatched);
  }

  @Test
  public void newFrameCanOnlyOwnTheNextGestureAfterItWasAccepted() throws Exception {
    assertTrue(view.frame(pixels(), identity(129)));
    event(MotionEvent.ACTION_DOWN);
    event(MotionEvent.ACTION_UP);
    assertTrue(view.frame(pixels(), identity(130)));
    event(MotionEvent.ACTION_DOWN);
    event(MotionEvent.ACTION_UP);
    assertEquals(java.util.List.of(129L, 129L, 130L, 130L), dispatched);
  }

  @Test
  public void previewInFlightDenialSendsNoDownOrUpAndDoesNotBlockAdoption() throws Exception {
    assertTrue(view.frame(pixels(), identity(129)));
    admitted = false;
    event(MotionEvent.ACTION_DOWN);
    assertTrue(view.frame(pixels(), identity(130)));
    event(MotionEvent.ACTION_UP);
    assertTrue(dispatched.isEmpty());
  }

  @Test
  public void programmaticCancelUnfreezesAdoptionAndOldUpCannotReplay() throws Exception {
    assertTrue(view.frame(pixels(), identity(129)));
    event(MotionEvent.ACTION_DOWN);
    view.cancelStream();
    assertTrue(view.frame(pixels(), identity(130)));
    event(MotionEvent.ACTION_UP);
    assertEquals(java.util.List.of(129L, 129L), dispatched);
    event(MotionEvent.ACTION_DOWN);
    event(MotionEvent.ACTION_UP);
    assertEquals(java.util.List.of(129L, 129L, 130L, 130L), dispatched);
  }

  @Test
  public void consumerAcknowledgementDoesNotRecycleAdoptedPixelsButReleasesGateHold()
      throws Exception {
    var gate = new VirtualScreenPreviewInputGate();
    var fetch = gate.beginPreview();
    var frame = new VirtualScreenPreviews.Frame(identity(130), pixels(), 2, fetch.retain());
    fetch.offered();
    assertNull(gate.beginGesture());
    assertTrue(view.frame(frame.bitmap, frame.value));
    frame.adopted();
    frame.finished();
    assertFalse(frame.bitmap.isRecycled());
    assertNotNull(gate.beginGesture());
  }

  private Object field(Object owner, String name) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private void offer(VirtualScreenPreviews.Lease lease, VirtualScreenPreviewInputGate.Fetch fetch)
      throws Exception {
    Class<?> type = Class.forName(VirtualScreenPreviews.class.getName() + "$Sample");
    var constructor =
        type.getDeclaredConstructor(
            JSONObject.class, long.class, VirtualScreenPreviewInputGate.Fetch.class);
    constructor.setAccessible(true);
    Object sample =
        constructor.newInstance(
            new JSONObject().put("ok", false).put("error", "FRAME_NOT_READY"), 0L, fetch);
    var offer = lease.getClass().getDeclaredMethod("offer", type);
    offer.setAccessible(true);
    offer.invoke(lease, sample);
  }

  private VirtualScreenManager manager() {
    return new VirtualScreenManager(
        null,
        () -> 100L,
        new VirtualScreenManager.Visuals() {
          public void present() {}

          public void stopped(long fence) {}
        });
  }

  @Test
  public void actualLeaseKeepsPendingHoldUntilMainConsumerAndReleasesOnConsumerFailure()
      throws Exception {
    var manager = manager();
    var previews = new VirtualScreenPreviews(manager);
    var gate = (VirtualScreenPreviewInputGate) field(previews, "inputGate");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var lease =
        previews
        .new Lease(
            frame -> {
              calls.incrementAndGet();
              throw new IllegalStateException("controlled consumer failure");
            });
    try {
      var fetch = gate.beginPreview();
      offer(lease, fetch);
      fetch.offered();
      assertNull(gate.beginGesture());
      assertEquals(0, calls.get());
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      assertEquals(1, calls.get());
      assertNotNull(gate.beginGesture());
    } finally {
      lease.close();
      ((java.util.concurrent.ScheduledExecutorService) field(manager, "WORKER")).shutdownNow();
    }
  }

  @Test
  public void actualLeaseCloseReleasesPendingAndCancelsItsQueuedConsumer() throws Exception {
    var manager = manager();
    var previews = new VirtualScreenPreviews(manager);
    var gate = (VirtualScreenPreviewInputGate) field(previews, "inputGate");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var lease = previews.new Lease(frame -> calls.incrementAndGet());
    try {
      var fetch = gate.beginPreview();
      offer(lease, fetch);
      fetch.offered();
      assertNull(gate.beginGesture());
      lease.close();
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      assertEquals(0, calls.get());
      assertNotNull(gate.beginGesture());
    } finally {
      lease.close();
      ((java.util.concurrent.ScheduledExecutorService) field(manager, "WORKER")).shutdownNow();
    }
  }
}
