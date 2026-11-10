package com.deepseekharness.app.vscreen;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.MotionEvent;
import com.deepseekharness.app.util.PrivilegedScreenPolicy;
import com.deepseekharness.app.util.RgbaImagePlane;
import com.deepseekharness.app.util.VirtualScreenInputResult;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.json.JSONObject;

/**
 * 实体屏（display 0）特权抓帧会话，T130 Phase A。
 *
 * <p>用 {@code VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR} 的 VirtualDisplay + ImageReader 镜像默认显示，
 * 图像只留在特权 Core 进程内存里：不落盘、不进日志、不上传。输入走 {@link VirtualScreenInput}
 * 的反射注入（display 0），不依赖无障碍服务。
 *
 * <p>安全界面在未受信任的镜像里是黑帧；本类只按采样结果标 {@code SECURE_OR_BLACK}，这是保守启发式，
 * 不是「已确认没有敏感内容」的证明。旋转或尺寸变化会重建显示代次，旧观察与旧凭据随之失效。
 */
@androidx.annotation.RequiresApi(30)
final class PhysicalScreenDisplaySession {
  static final int DISPLAY_ID = 0;
  private static final int NEAR_BLACK_MAX = 8;
  private static final int BLACK_SAMPLE_WIDTH = 48;
  private static final int MAX_ENCODED_BYTES = 3 * 1024 * 1024;

  final VirtualDisplay display;
  final ImageReader reader;
  final int captureWidth, captureHeight;
  final int screenWidth, screenHeight, densityDpi;
  final PrivilegedScreenPolicy.FrameConfig config;
  final String generation = UUID.randomUUID().toString();
  final Object lock = new Object();
  final VirtualScreenInput input;
  volatile boolean active = true;
  volatile long sequence;
  volatile long capturedAt;
  volatile boolean secureOrBlack;
  Bitmap frame;
  long encodedSequence = -1;
  String encoded = "";
  long observedSequence = -1, observedAt;
  String observationId = "";
  long claimedSequence = -1;

  private long lastAttemptAt;

  private PhysicalScreenDisplaySession(
      VirtualDisplay display,
      ImageReader reader,
      int captureWidth,
      int captureHeight,
      int screenWidth,
      int screenHeight,
      int densityDpi,
      PrivilegedScreenPolicy.FrameConfig config,
      VirtualScreenInput input) {
    this.display = display;
    this.reader = reader;
    this.captureWidth = captureWidth;
    this.captureHeight = captureHeight;
    this.screenWidth = screenWidth;
    this.screenHeight = screenHeight;
    this.densityDpi = densityDpi;
    this.config = config;
    this.input = java.util.Objects.requireNonNull(input);
  }

  /**
   * 宿主测试缝：只替换平台显示与输入事实，凭据、帧选择与 JSON 结构仍走生产路径。
   * 会话没有显示/图像流，因此不会启动抓帧线程。
   */
  static PhysicalScreenDisplaySession forHost(
      int captureWidth,
      int captureHeight,
      int screenWidth,
      int screenHeight,
      VirtualScreenInput input) {
    return new PhysicalScreenDisplaySession(
        null,
        null,
        captureWidth,
        captureHeight,
        screenWidth,
        screenHeight,
        320,
        PrivilegedScreenPolicy.FrameConfig.defaults(),
        input);
  }

  /**
   * 建立一条镜像显示并开始受限抓帧。默认显示取不到、尺寸非法或平台拒绝创建时抛出异常，
   * 由 Core 返回稳定失败码，不伪造空帧。
   */
  static PhysicalScreenDisplaySession create(PrivilegedScreenPolicy.FrameConfig config)
      throws Exception {
    android.content.Context context = VirtualScreenCore.privilegedContext();
    if (context == null) throw new IllegalStateException("PRIV_CONTEXT_UNAVAILABLE");
    DisplayManager manager =
        (DisplayManager) context.getSystemService(android.content.Context.DISPLAY_SERVICE);
    Display display = manager == null ? null : manager.getDisplay(DISPLAY_ID);
    if (display == null) throw new IllegalStateException("PRIV_DISPLAY_UNAVAILABLE");
    android.graphics.Point size = new android.graphics.Point();
    display.getRealSize(size);
    if (size.x <= 0 || size.y <= 0) throw new IllegalStateException("PRIV_DISPLAY_SIZE_INVALID");
    int[] capture = PrivilegedScreenPolicy.captureSize(size.x, size.y, config.maxWidth());
    DisplayMetrics metrics = new DisplayMetrics();
    display.getRealMetrics(metrics);
    int density = metrics.densityDpi > 0 ? metrics.densityDpi : DisplayMetrics.DENSITY_DEFAULT;
    ImageReader reader = ImageReader.newInstance(capture[0], capture[1], PixelFormat.RGBA_8888, 3);
    VirtualDisplay mirror = null;
    try {
      mirror =
          manager.createVirtualDisplay(
              "DSHA-PhysicalMirror",
              capture[0],
              capture[1],
              density,
              reader.getSurface(),
              DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                  | DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
      if (mirror == null) throw new IllegalStateException("PRIV_DISPLAY_CREATE_FAILED");
      PhysicalScreenDisplaySession session =
          new PhysicalScreenDisplaySession(
              mirror,
              reader,
              capture[0],
              capture[1],
              size.x,
              size.y,
              density,
              config,
              VirtualScreenInput.create(DISPLAY_ID));
      session.startCapture();
      return session;
    } catch (Throwable error) {
      try {
        if (mirror != null) mirror.release();
      } catch (Throwable ignored) {
      }
      try {
        reader.close();
      } catch (Throwable ignored) {
      }
      throw error;
    }
  }

  boolean matches(android.graphics.Point size, PrivilegedScreenPolicy.FrameConfig candidate) {
    if (size == null || candidate == null) return false;
    int[] capture = PrivilegedScreenPolicy.captureSize(size.x, size.y, candidate.maxWidth());
    return screenWidth == size.x
        && screenHeight == size.y
        && captureWidth == capture[0]
        && captureHeight == capture[1];
  }

  private void startCapture() {
    Thread worker =
        new Thread(
            () -> {
              while (active) {
                long budget = Math.max(16, 1000 / Math.max(1, config.maxFps()));
                long wait = lastAttemptAt + budget - SystemClock.elapsedRealtime();
                if (wait > 0) {
                  try {
                    Thread.sleep(Math.min(wait, 50));
                  } catch (InterruptedException end) {
                    return;
                  }
                  continue;
                }
                lastAttemptAt = SystemClock.elapsedRealtime();
                Image image = null;
                try {
                  image = reader.acquireLatestImage();
                  if (image == null) continue;
                  Bitmap next = copy(image, captureWidth, captureHeight);
                  boolean black = nearBlack(next);
                  synchronized (lock) {
                    if (!active) {
                      next.recycle();
                      return;
                    }
                    if (frame != null && frame.sameAs(next)) {
                      next.recycle();
                    } else {
                      if (frame != null) frame.recycle();
                      frame = next;
                      sequence++;
                      capturedAt = SystemClock.elapsedRealtime();
                      secureOrBlack = black;
                    }
                  }
                } catch (Throwable unavailable) {
                  // 单帧取不到不重建显示、不伪造帧号；下一次再试。
                } finally {
                  if (image != null) image.close();
                }
              }
            },
            "priv-frames");
    worker.setDaemon(true);
    worker.start();
  }

  /** 基础元数据；帧号、帧龄与观察 id 只在 {@link #frame} / {@link #afterAction} 里签发。 */
  JSONObject status(long now) throws Exception {
    long seq, captured;
    boolean black;
    synchronized (lock) {
      seq = sequence;
      captured = capturedAt;
      black = secureOrBlack;
    }
    String target = VirtualScreenCore.currentPackage(DISPLAY_ID);
    return new JSONObject()
        .put("ok", true)
        .put("state", "COMPLETED")
        .put("active", true)
        .put("physical", true)
        .put("displayId", DISPLAY_ID)
        .put("coreInstance", VirtualScreenCore.coreInstanceId())
        .put("generation", generation)
        .put("frameSeq", seq)
        .put("frameAgeMs", captured <= 0 ? -1 : Math.max(0, now - captured))
        .put("secureOrBlack", black)
        .put("width", captureWidth)
        .put("height", captureHeight)
        .put("screenWidth", screenWidth)
        .put("screenHeight", screenHeight)
        .put("densityDpi", densityDpi)
        .put("inputBackend", input.backend())
        .put("inputUnavailable", input.unavailableReason())
        .put("package", target)
        .put("packageVerified", !target.isEmpty())
        .put("frameConfig", frameConfig())
        .put("accessibilityRequired", false);
  }

  /**
   * 取帧并签发一次性观察凭据。
   *
   * @param since 调用方持有的最近帧号；相等且已编码时只回 {@code unchanged}，不重复传图
   */
  JSONObject frame(long since, long now) throws Exception {
    Bitmap copy = null;
    long seq, captured;
    boolean black, unchanged;
    String encodedImage;
    int width, height;
    synchronized (lock) {
      if (!active || frame == null)
        return VirtualScreenTransport.failure("PRIV_FRAME_NOT_READY", false);
      seq = sequence;
      captured = capturedAt;
      black = secureOrBlack;
      width = frame.getWidth();
      height = frame.getHeight();
      unchanged = encodedSequence == seq && since == seq;
      encodedImage = unchanged ? "" : encoded;
      if (!unchanged && encodedImage.isEmpty()) copy = frame.copy(Bitmap.Config.ARGB_8888, false);
    }
    String target = VirtualScreenCore.currentPackage(DISPLAY_ID);
    try {
      if (copy != null) {
        encodedImage = encode(copy);
        if (encodedImage.isEmpty())
          return VirtualScreenTransport.failure("PRIV_FRAME_ENCODE_FAILED", false);
      }
      JSONObject observation;
      long age;
      synchronized (lock) {
        if (!active || frame == null || sequence != seq)
          return VirtualScreenTransport.failure("PRIV_FRAME_CHANGED_DURING_READ", false);
        if (copy != null && encodedSequence != seq) {
          encoded = encodedImage;
          encodedSequence = seq;
        }
        observation = issueObservationLocked();
        age = captured <= 0 ? -1 : Math.max(0, now - captured);
      }
      JSONObject value =
          new JSONObject()
              .put("ok", true)
              .put("state", "COMPLETED")
              .put("active", true)
              .put("physical", true)
              .put("displayId", DISPLAY_ID)
              .put("coreInstance", VirtualScreenCore.coreInstanceId())
              .put("generation", generation)
              .put("frameSeq", seq)
              .put("frameAgeMs", age)
              .put("secureOrBlack", black)
              .put("width", width)
              .put("height", height)
              .put("screenWidth", screenWidth)
              .put("screenHeight", screenHeight)
              .put("package", target)
              .put("packageVerified", !target.isEmpty())
              .put("observationId", observation.optString("observationId"))
              .put("observation", observation)
              .put("retryable", false)
              .put("accessibilityRequired", false)
              .put("frameConfig", frameConfig());
      if (unchanged) value.put("unchanged", true);
      else
        value
            .put("previewWidth", width)
            .put("previewHeight", height)
            .put("previewB64", encodedImage);
      return value;
    } finally {
      if (copy != null) copy.recycle();
    }
  }

  /**
   * 消费一次性观察凭据。返回 null 表示已放行且凭据已作废；否则返回稳定拒绝码。
   * 请求被拒时不消费凭据，但调用方不得据此重放已经发出的动作。
   */
  String claim(long frameSeq, String requestedObservation, String requestedGeneration, long now) {
    synchronized (lock) {
      String rejection =
          PrivilegedScreenPolicy.credentialRejection(
              frameSeq,
              requestedObservation,
              observedSequence,
              observationId,
              observedAt <= 0 ? Long.MAX_VALUE : now - observedAt,
              requestedGeneration,
              generation);
      if (rejection != null) return rejection;
      claimedSequence = observedSequence;
      observationId = "";
      observedSequence = -1;
      observedAt = 0;
      return null;
    }
  }

  /** 已消费凭据对应的帧号；没有消费记录时为 -1。 */
  long claimedSequence() {
    synchronized (lock) {
      return claimedSequence;
    }
  }

  /**
   * 动作执行后的回执：在预算内等待新帧；无论画面是否变化都签发新的观察凭据，
   * 让调用方可以连续动作（这是低延迟路径，benchmark 直接测量它）。
   */
  JSONObject afterAction(int waitMs, long inputMs, long now) throws Exception {
    long started = now;
    long before = claimedSequence();
    long waitDeadline = started + Math.max(0, waitMs);
    long seq;
    while (true) {
      synchronized (lock) {
        seq = sequence;
        if (seq > before || waitMs <= 0 || SystemClock.elapsedRealtime() >= waitDeadline) break;
      }
      try {
        Thread.sleep(5);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    long waited = Math.max(0, SystemClock.elapsedRealtime() - started);
    return frameAfterAction(seq, before, waited, inputMs);
  }

  private JSONObject frameAfterAction(long seq, long before, long waitMs, long inputMs)
      throws Exception {
    Bitmap copy = null;
    String encodedImage;
    boolean unchanged;
    long captured;
    boolean black;
    synchronized (lock) {
      if (!active || frame == null)
        return VirtualScreenTransport.failure("PRIV_FRAME_NOT_READY", false);
      seq = Math.max(seq, sequence);
      unchanged = seq <= before;
      captured = capturedAt;
      black = secureOrBlack;
      encodedImage = unchanged ? "" : (encodedSequence == seq ? encoded : "");
      if (!unchanged && encodedImage.isEmpty()) copy = frame.copy(Bitmap.Config.ARGB_8888, false);
    }
    String target = VirtualScreenCore.currentPackage(DISPLAY_ID);
    try {
      if (copy != null) {
        encodedImage = encode(copy);
        if (encodedImage.isEmpty())
          return VirtualScreenTransport.failure("PRIV_FRAME_ENCODE_FAILED", false);
      }
      JSONObject observation;
      long age;
      boolean imageStale = false;
      synchronized (lock) {
        if (!active || frame == null || sequence < seq)
          return VirtualScreenTransport.failure("PRIV_STALE_FRAME", false);
        if (copy != null && sequence != seq) {
          // 编码期间画面又变了：不能把旧图挂到新帧号上；仍签发当前帧的观察，让调用方决定是否再取图。
          imageStale = true;
          encodedImage = "";
        } else if (copy != null) {
          encoded = encodedImage;
          encodedSequence = seq;
        }
        seq = sequence;
        observation = issueObservationLocked();
        age = captured <= 0 ? -1 : Math.max(0, SystemClock.elapsedRealtime() - captured);
      }
      JSONObject value =
          new JSONObject()
              .put("ok", true)
              .put("state", "COMPLETED")
              .put("active", true)
              .put("physical", true)
              .put("displayId", DISPLAY_ID)
              .put("coreInstance", VirtualScreenCore.coreInstanceId())
              .put("generation", generation)
              .put("frameSeq", seq)
              .put("frameAgeMs", age)
              .put("frameWaitMs", waitMs)
              .put("inputMs", inputMs)
              .put("secureOrBlack", black)
              .put("width", captureWidth)
              .put("height", captureHeight)
              .put("screenWidth", screenWidth)
              .put("screenHeight", screenHeight)
              .put("package", target)
              .put("packageVerified", !target.isEmpty())
              .put("observationId", observation.optString("observationId"))
              .put("observation", observation)
              .put("retryable", false)
              .put("accessibilityRequired", false)
              .put("frameConfig", frameConfig());
      if (unchanged || imageStale) {
        value.put("unchanged", unchanged);
        if (imageStale)
          value
              .put("frameImageUnavailable", "PRIV_FRAME_CHANGED_DURING_READ")
              .put("nextAction", "CALL_/app/priv/frame_FOR_THE_NEWEST_IMAGE");
      } else {
        value
            .put("previewWidth", captureWidth)
            .put("previewHeight", captureHeight)
            .put("previewB64", encodedImage);
      }
      return value;
    } finally {
      if (copy != null) copy.recycle();
    }
  }

  private JSONObject issueObservationLocked() throws Exception {
    observationId = UUID.randomUUID().toString();
    observedSequence = sequence;
    observedAt = SystemClock.elapsedRealtime();
    return new JSONObject()
        .put("kind", "dsha-priv-frame-v1")
        .put("observationId", observationId)
        .put("frameSeq", observedSequence)
        .put("generation", generation)
        .put("coreInstance", VirtualScreenCore.coreInstanceId())
        .put("capturedAtMs", capturedAt)
        .put("width", captureWidth)
        .put("height", captureHeight)
        .put("screenWidth", screenWidth)
        .put("screenHeight", screenHeight)
        .put("ageMs", Math.max(0, observedAt - capturedAt))
        .put("accessibilityRequired", false);
  }

  private JSONObject frameConfig() throws Exception {
    return new JSONObject()
        .put("maxWidth", config.maxWidth())
        .put("jpegQuality", config.jpegQuality())
        .put("maxFps", config.maxFps())
        .put("captureWidth", captureWidth)
        .put("captureHeight", captureHeight);
  }

  private String encode(Bitmap bitmap) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality(), output)
          || output.size() == 0
          || output.size() > MAX_ENCODED_BYTES) return "";
      return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    } catch (Throwable unavailable) {
      return "";
    }
  }

  VirtualScreenInputResult tap(float x, float y) {
    float[] mapped =
        PrivilegedScreenPolicy.map(x, y, captureWidth, captureHeight, screenWidth, screenHeight);
    String stroke = UUID.randomUUID().toString();
    VirtualScreenInputResult down =
        input.touch(stroke, MotionEvent.ACTION_DOWN, mapped[0], mapped[1]);
    if (!down.accepted()) return down;
    return input.touch(stroke, MotionEvent.ACTION_UP, mapped[0], mapped[1]);
  }

  VirtualScreenInputResult swipe(float x1, float y1, float x2, float y2, int ms) {
    float[] start =
        PrivilegedScreenPolicy.map(x1, y1, captureWidth, captureHeight, screenWidth, screenHeight);
    float[] end =
        PrivilegedScreenPolicy.map(x2, y2, captureWidth, captureHeight, screenWidth, screenHeight);
    String stroke = UUID.randomUUID().toString();
    VirtualScreenInputResult result =
        input.touch(stroke, MotionEvent.ACTION_DOWN, start[0], start[1]);
    if (!result.accepted()) return result;
    int steps = Math.max(1, ms / 16);
    try {
      for (int n = 1; n < steps; n++) {
        Thread.sleep(16);
        result =
            input.touch(
                stroke,
                MotionEvent.ACTION_MOVE,
                start[0] + (end[0] - start[0]) * n / steps,
                start[1] + (end[1] - start[1]) * n / steps);
        if (!result.accepted()) return result;
      }
      return input.touch(stroke, MotionEvent.ACTION_UP, end[0], end[1]);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return VirtualScreenInputResult.UNKNOWN;
    } finally {
      try {
        input.cancel();
      } catch (Throwable ignored) {
      }
    }
  }

  VirtualScreenInputResult key(int keycode) {
    return input.key(keycode);
  }

  /** 心跳：卡住未抬起的手势要被取消，避免目标应用停在按下态。 */
  void expire() {
    try {
      input.expire();
    } catch (Throwable ignored) {
    }
  }

  void close() {
    active = false;
    try {
      input.cancel();
    } catch (Throwable ignored) {
    }
    synchronized (lock) {
      if (frame != null) frame.recycle();
      frame = null;
      observationId = "";
      observedSequence = -1;
      observedAt = 0;
    }
    try {
      if (display != null) display.release();
    } catch (Throwable ignored) {
    } finally {
      try {
        if (reader != null) reader.close();
      } catch (Throwable ignored) {
      }
    }
  }

  private static Bitmap copy(Image image, int width, int height) {
    Image.Plane plane = image.getPlanes()[0];
    ByteBuffer buffer =
        RgbaImagePlane.packed(
            plane.getBuffer(), width, height, plane.getPixelStride(), plane.getRowStride());
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    try {
      bitmap.copyPixelsFromBuffer(buffer);
      return bitmap;
    } catch (Throwable error) {
      bitmap.recycle();
      throw error;
    }
  }

  private static boolean nearBlack(Bitmap frame) {
    int width = Math.min(BLACK_SAMPLE_WIDTH, frame.getWidth());
    int height = Math.max(1, Math.round(width * frame.getHeight() / (float) frame.getWidth()));
    Bitmap sample = Bitmap.createScaledBitmap(frame, width, height, false);
    try {
      int[] pixels = new int[width * height];
      sample.getPixels(pixels, 0, width, 0, 0, width, height);
      int near = 0;
      for (int color : pixels) {
        int red = (color >> 16) & 255, green = (color >> 8) & 255, blue = color & 255;
        if (red <= NEAR_BLACK_MAX && green <= NEAR_BLACK_MAX && blue <= NEAR_BLACK_MAX) near++;
      }
      return PrivilegedScreenPolicy.secureOrBlack(near, pixels.length);
    } finally {
      sample.recycle();
    }
  }
}
