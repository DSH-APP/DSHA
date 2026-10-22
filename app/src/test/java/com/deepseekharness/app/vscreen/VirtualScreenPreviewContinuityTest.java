package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.app.Application;
import android.graphics.Bitmap;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.deepseekharness.app.util.CoalescingPoller;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Actual Activity preview consumer and View, with only the transport producer held idle. */
@RunWith(VirtualScreenPreviewContinuityTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class VirtualScreenPreviewContinuityTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  private VirtualScreenActivity activity;
  private VirtualScreenManager manager;
  private VirtualScreenPreviewView view;
  private VirtualScreenPreviews previews;
  private VirtualScreenPreviews.Lease activityLease;
  private Consumer<VirtualScreenPreviews.Frame> consumer;
  private ScheduledExecutorService idleProducer;
  private final ArrayList<Long> inputs = new ArrayList<>();

  private static Object field(Object owner, String name) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void field(Object owner, String name, Object value) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private JSONObject frame(long seq) throws Exception {
    return new JSONObject()
        .put("ok", true)
        .put("active", true)
        .put("generation", "display-a")
        .put("frameSeq", seq)
        .put("managerEpoch", 0)
        .put("managerRevision", 0)
        .put("width", 1008)
        .put("height", 1792);
  }

  private JSONObject error(String code) throws Exception {
    return new JSONObject().put("ok", false).put("error", code);
  }

  private Bitmap pixels() {
    Bitmap bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(0xff2196f3);
    return bitmap;
  }

  private void accept(Bitmap bitmap, JSONObject value) {
    consumer.accept(new VirtualScreenPreviews.Frame(value, bitmap, 0, null));
  }

  private void touch(int action) {
    MotionEvent event = MotionEvent.obtain(10, 20, action, 100, 200, 0);
    try {
      view.onTouchEvent(event);
    } finally {
      event.recycle();
    }
  }

  @Before
  @SuppressWarnings("unchecked")
  public void actualConsumer() throws Exception {
    var app = RuntimeEnvironment.getApplication();
    manager =
        new VirtualScreenManager(
            null,
            () -> 100L,
            new VirtualScreenManager.Visuals() {
              public void present() {}

              public void stopped(long fence) {}
            });
    field(manager, "generation", "display-a");
    field(manager, "token", "local-test");
    field(manager, "activeEpoch", 0L);
    previews = manager.previews();
    idleProducer = Executors.newSingleThreadScheduledExecutor();
    field(previews, "poller", new CoalescingPoller<>(idleProducer, () -> null, 3, 250));
    var parent = new FrameLayout(app);
    view = new VirtualScreenPreviewView(app);
    parent.addView(view);
    parent.layout(0, 0, 200, 400);
    view.layout(0, 0, 200, 400);
    view.listener =
        new VirtualScreenPreviewView.Touch() {
          public void stream(
              String generation, long seq, String stroke, int action, float x, float y) {
            inputs.add(seq);
          }

          public void input(
              String generation,
              long seq,
              float x,
              float y,
              float endX,
              float endY,
              int duration,
              boolean tap) {
            fail("No discrete replay is permitted");
          }
        };
    activity = new VirtualScreenActivity();
    field(activity, "manager", manager);
    field(activity, "preview", view);
    field(activity, "visible", true);
    field(activity, "state", new TextView(app));
    field(activity, "detail", new TextView(app));
    var begin = VirtualScreenActivity.class.getDeclaredMethod("beginPreview");
    begin.setAccessible(true);
    begin.invoke(activity);
    activityLease = (VirtualScreenPreviews.Lease) field(activity, "previewLease");
    consumer = (Consumer<VirtualScreenPreviews.Frame>) field(activityLease, "listener");
  }

  @After
  public void close() throws Exception {
    if (activityLease != null) activityLease.close();
    if (view != null) view.clear();
    if (activity != null)
      ((java.util.concurrent.ExecutorService) field(activity, "worker")).shutdownNow();
    if (manager != null) ((ScheduledExecutorService) field(manager, "WORKER")).shutdownNow();
    if (idleProducer != null) idleProducer.shutdownNow();
  }

  @Test
  public void staleCaptureKeepsVisiblePixelsButRevokesEveryInputIdentity() throws Exception {
    Bitmap first = pixels();
    accept(first, frame(1));
    accept(null, error("STALE_FRAME"));
    assertNotNull("A capture race must not flash the preview black", view.getDrawable());
    assertFalse(first.isRecycled());
    assertEquals(-1L, field(view, "seq"));
    assertNull(field(view, "displayed"));
    assertEquals(-1L, field(activity, "frameSeq"));
    assertEquals("", field(activity, "generation"));
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_UP);
    assertTrue(inputs.isEmpty());
    accept(pixels(), frame(2));
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_UP);
    assertEquals(java.util.List.of(2L, 2L), inputs);
  }

  @Test
  public void repeatedFrameGapsKeepTheVisualMatrixUntilANewSuccessfulAdoption() throws Exception {
    Bitmap first = pixels();
    accept(first, frame(1));
    float[] before = new float[9];
    view.getImageMatrix().getValues(before);
    for (String code :
        new String[] {"STALE_FRAME", "FRAME_NOT_READY", "FRAME_ENCODE_FAILED", "STALE_FRAME"})
      accept(null, error(code));
    assertNotNull(view.getDrawable());
    assertFalse(first.isRecycled());
    float[] after = new float[9];
    view.getImageMatrix().getValues(after);
    assertArrayEquals(before, after, 0f);
    accept(pixels(), frame(1));
    assertEquals(1L, field(view, "seq"));
    assertTrue(first.isRecycled());
  }

  @Test
  public void stoppedRevokedAndDifferentGenerationNeverKeepOldPixels() throws Exception {
    for (String code :
        new String[] {"VSCREEN_NOT_RUNNING", "VSCREEN_REVOKED", "STALE_GENERATION"}) {
      Bitmap old = pixels();
      accept(old, frame(1));
      accept(null, error(code));
      assertNull(view.getDrawable());
      assertTrue(old.isRecycled());
    }
    Bitmap old = pixels();
    accept(old, frame(1));
    field(manager, "generation", "display-b");
    accept(null, error("STALE_FRAME"));
    assertNull(view.getDrawable());
    assertTrue(old.isRecycled());
  }

  @Test
  public void frameGapCancelsTheOriginalStrokeAndOldUpCannotReplayOnANewFrame() throws Exception {
    accept(pixels(), frame(1));
    touch(MotionEvent.ACTION_DOWN);
    accept(null, error("STALE_FRAME"));
    accept(pixels(), frame(2));
    touch(MotionEvent.ACTION_UP);
    assertEquals(java.util.List.of(1L, 1L), inputs);
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_UP);
    assertEquals(java.util.List.of(1L, 1L, 2L, 2L), inputs);
  }

  private void offer(VirtualScreenPreviews.Lease lease, JSONObject value) throws Exception {
    var gate = (VirtualScreenPreviewInputGate) field(previews, "inputGate");
    var fetch = gate.beginPreview();
    assertNotNull(fetch);
    Class<?> type = Class.forName(VirtualScreenPreviews.class.getName() + "$Sample");
    var constructor =
        type.getDeclaredConstructor(
            JSONObject.class, long.class, VirtualScreenPreviewInputGate.Fetch.class);
    constructor.setAccessible(true);
    var offer = lease.getClass().getDeclaredMethod("offer", type);
    offer.setAccessible(true);
    offer.invoke(lease, constructor.newInstance(value, 0L, fetch));
    fetch.offered();
    org.robolectric.shadows.ShadowLooper.idleMainLooper();
  }

  @Test
  public void sameSequenceAfterAnErrorMustReachTheConsumerAgainToRestoreInput() throws Exception {
    Bitmap encoded = pixels();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    assertTrue(encoded.compress(Bitmap.CompressFormat.PNG, 100, bytes));
    encoded.recycle();
    JSONObject value =
        frame(1)
            .put(
                "previewB64",
                android.util.Base64.encodeToString(
                    bytes.toByteArray(), android.util.Base64.NO_WRAP));
    int[] valid = {0};
    var lease =
        previews
        .new Lease(
            next -> {
              if (next.bitmap != null) valid[0]++;
              consumer.accept(next);
            });
    try {
      offer(lease, value);
      assertEquals(1, valid[0]);
      offer(lease, error("FRAME_NOT_READY"));
      offer(lease, value);
      assertEquals(
          "A transient gap cannot permanently suppress an unchanged successful frame", 2, valid[0]);
      assertEquals(1L, field(view, "seq"));
    } finally {
      lease.close();
    }
  }
}
