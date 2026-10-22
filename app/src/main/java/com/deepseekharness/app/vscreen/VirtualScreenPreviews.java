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
  private java.util.concurrent.ScheduledExecutorService worker;
  private final VirtualScreenManager manager;
  private final VirtualScreenPreviewInputGate inputGate = new VirtualScreenPreviewInputGate();

  VirtualScreenPreviews(VirtualScreenManager manager) {
    this.manager = java.util.Objects.requireNonNull(manager);
  }

  private static final class Sample {
    final JSONObject value;
    final long epoch;
    final VirtualScreenPreviewInputGate.Fetch fetch;

    Sample(JSONObject value, long epoch, VirtualScreenPreviewInputGate.Fetch fetch) {
      this.value = value;
      this.epoch = epoch;
      this.fetch = fetch;
    }
  }

  static final class Frame {
    final JSONObject value;
    final Bitmap bitmap;
    final long epoch;
    private final VirtualScreenPreviewInputGate.Hold hold;
    private boolean adopted;

    Frame(JSONObject value, Bitmap bitmap, long epoch, VirtualScreenPreviewInputGate.Hold hold) {
      this.value = value;
      this.bitmap = bitmap;
      this.epoch = epoch;
      this.hold = hold;
    }

    void adopted() {
      adopted = bitmap != null;
    }

    void finished() {
      if (hold != null) hold.close();
    }

    void release() {
      if (bitmap != null) bitmap.recycle();
      finished();
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
        if (!sample.value.optBoolean("ok")) {
          // A consumer revokes its input identity on a frame gap. Even the same successful
          // sequence must be delivered again before that identity can become usable.
          drawnEpoch = drawnSequence = -1;
          drawnGeneration = "";
        } else if (drawnEpoch == sample.epoch
            && drawnGeneration.equals(sample.value.optString("generation"))
            && drawnSequence == sample.value.optLong("frameSeq", -1)) return;
      }
      var hold = sample.fetch.retain();
      if (hold == null) return;
      Frame next;
      try {
        next =
            new Frame(
                sample.value, VirtualScreenManager.previewBitmap(sample.value), sample.epoch, hold);
      } catch (RuntimeException | Error failure) {
        hold.close();
        throw failure;
      }
      Frame replaced;
      synchronized (this) {
        if (closed) {
          replaced = next;
        } else {
          replaced = pending;
          pending = next;
          if (!queued) {
            queued = true;
            boolean posted;
            try {
              posted = MAIN.post(deliver);
            } catch (RuntimeException | Error unavailable) {
              queued = false;
              pending = null;
              next.release();
              if (replaced != null) replaced.release();
              throw unavailable;
            }
            if (!posted) {
              queued = false;
              pending = null;
              next.release();
            }
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
      try {
        listener.accept(frame);
        if (frame.adopted
            && !closed
            && frame.epoch == manager.epoch()
            && manager.frameCurrent(frame.value))
          synchronized (this) {
            drawnEpoch = frame.epoch;
            drawnGeneration = frame.value.optString("generation");
            drawnSequence = frame.value.optLong("frameSeq", -1);
          }
      } catch (RuntimeException failedConsumer) {
        frame.release();
      } finally {
        frame.finished();
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
      worker =
          Executors.newSingleThreadScheduledExecutor(
              task -> {
                Thread thread = new Thread(task, "vscreen-previews");
                thread.setDaemon(true);
                return thread;
              });
      poller =
          new CoalescingPoller<>(
              worker,
              () -> {
                var fetch = inputGate.beginPreview();
                if (fetch == null) return null;
                try {
                  long epoch = manager.epoch();
                  JSONObject value = manager.preview();
                  return epoch == manager.epoch() ? new Sample(value, epoch, fetch) : null;
                } finally {
                  // This executor task runs after CoalescingPoller finishes all current offers.
                  // Each decoded/pending frame retains its own hold until adoption or release.
                  try {
                    worker.execute(fetch::offered);
                  } catch (RuntimeException unavailable) {
                    fetch.offered();
                  }
                }
              },
              3,
              250);
    }
    Lease lease = new Lease(listener);
    lease.subscription = poller.subscribe(owner, lease::offer);
    return lease;
  }

  VirtualScreenPreviewInputGate.Gesture beginGesture(JSONObject displayed) {
    assertMain();
    var gesture = inputGate.beginGesture();
    if (gesture == null) return null;
    if (displayed != null && manager.frameCurrent(displayed)) return gesture;
    gesture.close();
    return null;
  }

  static void assertMain() {
    if (Looper.myLooper() != Looper.getMainLooper())
      throw new IllegalStateException("VSCREEN_UI_MAIN_REQUIRED");
  }
}
