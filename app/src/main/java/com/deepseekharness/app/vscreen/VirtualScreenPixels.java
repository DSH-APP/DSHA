package com.deepseekharness.app.vscreen;

import android.graphics.Bitmap;
import android.media.Image;
import com.deepseekharness.app.util.RgbaImagePlane;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import org.json.JSONObject;

/** 像素采集/观察不承担控件树授权；status不会登记一次观察。 */
@androidx.annotation.RequiresApi(30)
final class VirtualScreenPixels {
  interface Metadata {
    JSONObject get() throws Exception;
  }

  interface Encoder {
    boolean encode(Bitmap frame, ByteArrayOutputStream output);
  }

  static void start(VirtualScreenDisplaySession session) {
    Thread worker =
        new Thread(
            () -> {
              while (session.active) {
                session.observations.prune(VirtualScreenCore.elapsed());
                Image image = null;
                try {
                  image = session.reader.acquireLatestImage();
                  if (image != null) {
                    Bitmap next = copy(image, session.width, session.height);
                    synchronized (session.lock) {
                      if (!session.active) {
                        next.recycle();
                        break;
                      }
                      if (session.frame != null && session.frame.sameAs(next)) next.recycle();
                      else {
                        if (session.frame != null) session.frame.recycle();
                        session.frame = next;
                        session.sequence++;
                      }
                      session.capturedAt = VirtualScreenCore.elapsed();
                    }
                  }
                } catch (Throwable unavailable) {
                  // 帧暂不可用不重建显示，也不伪造帧号。
                } finally {
                  if (image != null) image.close();
                }
                try {
                  Thread.sleep(33);
                } catch (InterruptedException end) {
                  return;
                }
              }
            },
            "vscreen-frames");
    worker.setDaemon(true);
    worker.start();
  }

  static JSONObject preview(
      VirtualScreenDisplaySession session, long since, String generation, Metadata metadata)
      throws Exception {
    return preview(
        session,
        since,
        generation,
        metadata,
        (frame, output) -> frame.compress(Bitmap.CompressFormat.JPEG, 65, output));
  }

  /** 测试只替换平台编码结果；帧选择、发布与观察登记仍使用生产路径。 */
  static JSONObject preview(
      VirtualScreenDisplaySession session,
      long since,
      String generation,
      Metadata metadata,
      Encoder encoder)
      throws Exception {
    Bitmap copy = null;
    long sequence, captured;
    String encoded;
    boolean unchanged;
    int width, height;
    synchronized (session.lock) {
      if (!session.active || session.frame == null)
        return VirtualScreenTransport.failure("FRAME_NOT_READY", false);
      sequence = session.sequence;
      captured = session.capturedAt;
      width = Math.min(720, session.frame.getWidth());
      height = Math.round(session.frame.getHeight() * width / (float) session.frame.getWidth());
      encoded = session.encodedSequence == sequence ? session.encoded : "";
      unchanged = !encoded.isEmpty() && since == sequence && session.generation.equals(generation);
      if (encoded.isEmpty()) copy = session.frame.copy(Bitmap.Config.ARGB_8888, false);
    }
    Bitmap scaled = null;
    try {
      if (encoded.isEmpty()) {
        scaled = Bitmap.createScaledBitmap(copy, width, height, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!encoder.encode(scaled, output) || output.size() == 0)
          return VirtualScreenTransport.failure("FRAME_ENCODE_FAILED", false);
        encoded =
            android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP);
      }
      JSONObject value =
          metadata
              .get()
              .put("frameSeq", sequence)
              .put("frameAgeMs", VirtualScreenCore.elapsed() - captured);
      if (unchanged) value.put("unchanged", true);
      else value.put("previewWidth", width).put("previewHeight", height).put("previewB64", encoded);
      synchronized (session.lock) {
        if (!session.active || session.frame == null || session.sequence != sequence)
          return VirtualScreenTransport.failure("STALE_FRAME", false);
        if (!value.optBoolean("ok")
            || !value.optBoolean("active")
            || value.optInt("displayId", -1) != session.id
            || !session.generation.equals(value.optString("generation")))
          return VirtualScreenTransport.failure("STALE_GENERATION", false);
        // 只在有效图片回包（或此前已发布的同帧缓存）完整生成后登记观察。
        session.encoded = encoded;
        session.encodedSequence = sequence;
        session.observed = sequence;
        session.observedAt = VirtualScreenCore.elapsed();
      }
      return value;
    } finally {
      if (scaled != null && scaled != copy) scaled.recycle();
      if (copy != null) copy.recycle();
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
}
