package com.deepseekharness.app.vscreen;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import com.deepseekharness.app.util.CoalescingPoller;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Three UI owners share one preview request; each owns its decoded bitmap and one main callback. */
final class VirtualScreenPreviews {
  private final Handler MAIN = new Handler(Looper.getMainLooper());
  private CoalescingPoller<Sample> poller;
  private final VirtualScreenManager manager;

  VirtualScreenPreviews(VirtualScreenManager manager) {
    this.manager = java.util.Objects.requireNonNull(manager);
  }

  private static final class Sample {
    final JSONObject value;
    final long epoch;

    Sample(JSONObject value, long epoch) {
      this.value = value;
      this.epoch = epoch;
    }
  }

  static final class Frame {
    final JSONObject value;
    final Bitmap bitmap;
    final long epoch;

    Frame(JSONObject value, Bitmap bitmap, long epoch) {
      this.value = value;
      this.bitmap = bitmap;
      this.epoch = epoch;
    }

    void release() {
      if (bitmap != null) bitmap.recycle();
    }
  }

  final class Lease implements AutoCloseable {
    private final Consumer<Frame> listener;
    private final Runnable deliver = this::deliver;
    private CoalescingPoller<Sample>.Subscription subscription;
    private Frame pending;
    private boolean queued, closed;
    private long drawnEpoch = -1, drawnSequence = -1;
    private String drawnGeneration = "";

    Lease(Consumer<Frame> listener) {
      this.listener = listener;
    }

    private void offer(Sample sample) {
      if (sample == null || sample.epoch != manager.epoch()) return;
      synchronized (this) {
        if (closed) return;
        if (sample.value.optBoolean("ok")
            && drawnEpoch == sample.epoch
            && drawnGeneration.equals(sample.value.optString("generation"))
            && drawnSequence == sample.value.optLong("frameSeq", -1)) return;
      }
      Frame next =
          new Frame(sample.value, VirtualScreenManager.previewBitmap(sample.value), sample.epoch);
      Frame replaced;
      synchronized (this) {
        if (closed) {
          replaced = next;
        } else {
          replaced = pending;
          pending = next;
          if (!queued) {
            queued = true;
            MAIN.post(deliver);
          }
        }
      }
      if (replaced != null) replaced.release();
    }

    private void deliver() {
      assertMain();
      Frame frame;
      synchronized (this) {
        queued = false;
        frame = pending;
        pending = null;
      }
      if (frame == null) return;
      if (closed
          || frame.epoch != manager.epoch()
          || (frame.value.optBoolean("ok") && !manager.frameCurrent(frame.value))) {
        frame.release();
        return;
      }
      if (frame.bitmap != null)
        synchronized (this) {
          drawnEpoch = frame.epoch;
          drawnGeneration = frame.value.optString("generation");
          drawnSequence = frame.value.optLong("frameSeq", -1);
        }
      try {
        listener.accept(frame);
      } catch (RuntimeException failedConsumer) {
        frame.release();
      }
    }

    @Override
    public void close() {
      assertMain();
      Frame old;
      synchronized (this) {
        if (closed) return;
        closed = true;
        old = pending;
        pending = null;
      }
      MAIN.removeCallbacks(deliver);
      if (subscription != null) subscription.close();
      if (old != null) old.release();
    }
  }

  Lease subscribe(Object owner, Consumer<Frame> listener) {
    assertMain();
    if (poller == null) {
      poller =
          new CoalescingPoller<>(
              Executors.newSingleThreadScheduledExecutor(
                  task -> {
                    Thread thread = new Thread(task, "vscreen-previews");
                    thread.setDaemon(true);
                    return thread;
                  }),
              () -> {
                long epoch = manager.epoch();
                JSONObject value = manager.preview();
                return epoch == manager.epoch() ? new Sample(value, epoch) : null;
              },
              3,
              250);
    }
    Lease lease = new Lease(listener);
    lease.subscription = poller.subscribe(owner, lease::offer);
    return lease;
  }

  static void assertMain() {
    if (Looper.myLooper() != Looper.getMainLooper())
      throw new IllegalStateException("VSCREEN_UI_MAIN_REQUIRED");
  }
}
