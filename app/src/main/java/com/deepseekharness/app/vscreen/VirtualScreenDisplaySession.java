package com.deepseekharness.app.vscreen;

import android.graphics.Bitmap;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import java.util.UUID;

/** 一个显示代次独占显示、图像流和输入；宿主旋转只更换预览订阅。 */
@androidx.annotation.RequiresApi(30)
final class VirtualScreenDisplaySession {
  final VirtualDisplay display;
  final ImageReader reader;
  final int width, height, id;
  final Object lock = new Object();
  final String generation = UUID.randomUUID().toString();
  volatile boolean active = true;
  volatile long sequence;
  long observed = -1, observedAt, capturedAt, encodedSequence = -1;
  String packageName = "", encoded = "";
  Bitmap frame;
  final VirtualScreenObservedPixels observations = new VirtualScreenObservedPixels();
  final VirtualScreenInput input;

  VirtualScreenDisplaySession(VirtualDisplay display, ImageReader reader, int width, int height)
      throws Exception {
    this.display = display;
    this.reader = reader;
    this.width = width;
    this.height = height;
    id = display.getDisplay().getDisplayId();
    input = VirtualScreenInput.create(id);
  }

  /** Host platform adapter supplies primitive display facts; protocol/auth/route remain real. */
  VirtualScreenDisplaySession(int width, int height, int id, VirtualScreenInput input) {
    this.display = null;
    this.reader = null;
    this.width = width;
    this.height = height;
    this.id = id;
    this.input = java.util.Objects.requireNonNull(input);
  }

  void start() {
    if (reader != null) VirtualScreenPixels.start(this);
  }

  void close() {
    active = false;
    try {
      input.cancel();
    } catch (Exception ignored) {
    }
    synchronized (lock) {
      if (frame != null) frame.recycle();
      frame = null;
    }
    observations.clear();
    try {
      if (display != null) display.release();
    } finally {
      if (reader != null) reader.close();
    }
  }
}
