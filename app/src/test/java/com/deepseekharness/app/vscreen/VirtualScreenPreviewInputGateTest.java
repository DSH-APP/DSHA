package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VirtualScreenPreviewInputGateTest {
  @Test
  public void previewBlocksDownBeforeGetAndThroughDecodeAdoption() {
    var gate = new VirtualScreenPreviewInputGate();
    var fetch = gate.beginPreview();
    assertNotNull(fetch);
    assertNull(gate.beginGesture());
    var decoded = fetch.retain();
    fetch.offered();
    assertNull(gate.beginGesture());
    decoded.close();
    assertNotNull(gate.beginGesture());
  }

  @Test
  public void allThreeOwnersMustAdoptRejectOrReleaseBeforeDownCanStart() {
    var gate = new VirtualScreenPreviewInputGate();
    var fetch = gate.beginPreview();
    var a = fetch.retain();
    var b = fetch.retain();
    var c = fetch.retain();
    fetch.offered();
    a.close();
    b.close();
    assertNull(gate.beginGesture());
    c.close();
    assertNotNull(gate.beginGesture());
  }

  @Test
  public void duplicateFrameReleaseAndProducerFinallyAreIdempotent() {
    var gate = new VirtualScreenPreviewInputGate();
    var fetch = gate.beginPreview();
    var owner = fetch.retain();
    owner.close();
    owner.close();
    fetch.offered();
    fetch.offered();
    assertNull(fetch.retain());
    assertNotNull(gate.beginPreview());
  }

  @Test
  public void admittedGestureBlocksNewPreviewUntilActualTerminalCleanup() {
    var gate = new VirtualScreenPreviewInputGate();
    var gesture = gate.beginGesture();
    assertNotNull(gesture);
    assertNull(gate.beginPreview());
    assertNull(gate.beginGesture());
    gesture.close();
    assertNotNull(gate.beginPreview());
  }

  @Test
  public void staleGestureReleaseCannotReleaseANewGesture() {
    var gate = new VirtualScreenPreviewInputGate();
    var old = gate.beginGesture();
    old.close();
    var current = gate.beginGesture();
    old.close();
    assertNull(gate.beginPreview());
    current.close();
    assertNotNull(gate.beginPreview());
  }

  @Test
  public void concurrentPreviewAndDownCannotBothBeAdmitted() throws Exception {
    var gate = new VirtualScreenPreviewInputGate();
    var start = new java.util.concurrent.CountDownLatch(1);
    var fetch =
        new java.util.concurrent.atomic.AtomicReference<VirtualScreenPreviewInputGate.Fetch>();
    var gesture =
        new java.util.concurrent.atomic.AtomicReference<VirtualScreenPreviewInputGate.Gesture>();
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    Thread preview =
        new Thread(
            () -> {
              try {
                start.await();
                fetch.set(gate.beginPreview());
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    Thread input =
        new Thread(
            () -> {
              try {
                start.await();
                gesture.set(gate.beginGesture());
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    preview.start();
    input.start();
    start.countDown();
    preview.join(1000);
    input.join(1000);
    assertFalse(preview.isAlive());
    assertFalse(input.isAlive());
    assertNull(failure.get());
    assertNotEquals(fetch.get() == null, gesture.get() == null);
    if (fetch.get() != null) fetch.get().offered();
    if (gesture.get() != null) gesture.get().close();
    assertNotNull(gate.beginPreview());
  }
}
