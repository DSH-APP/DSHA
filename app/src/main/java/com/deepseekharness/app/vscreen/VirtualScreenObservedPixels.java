package com.deepseekharness.app.vscreen;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.json.JSONObject;

/** Agent-see-only immutable raw snapshots. Native preview frames never enter this owner. */
@androidx.annotation.RequiresApi(30)
final class VirtualScreenObservedPixels {
  static final long BYTE_LIMIT = 24L * 1024 * 1024, TTL_MS = 30_000;
  static final int COUNT_LIMIT = 2;
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
  private long allocated;

  private static final class Entry {
    final String id = UUID.randomUUID().toString();
    final Bitmap raw;
    final long sequence, created, bytes;
    final String generation, packageName;

    Entry(Bitmap raw, VirtualScreenDisplaySession session, String packageName, long now) {
      this.raw = raw;
      sequence = session.sequence;
      generation = session.generation;
      this.packageName = packageName;
      created = now;
      bytes = raw.getAllocationByteCount();
    }
  }

  synchronized JSONObject capture(
      VirtualScreenDisplaySession session, VirtualScreenPixels.Metadata metadata) throws Exception {
    prune(VirtualScreenCore.elapsed());
    Entry entry;
    JSONObject value;
    synchronized (session.lock) {
      if (!session.active || session.frame == null) return failure("FRAME_NOT_READY");
      long required = session.frame.getAllocationByteCount();
      if (required <= 0 || required > BYTE_LIMIT) return failure("OBSERVATION_IMAGE_BUDGET");
      while (!entries.isEmpty()
          && (entries.size() >= COUNT_LIMIT || allocated + required > BYTE_LIMIT))
        remove(entries.keySet().iterator().next());
      value = metadata.get();
      if (!value.optBoolean("ok")
          || !value.optBoolean("active")
          || value.optInt("displayId", -1) != session.id
          || !session.generation.equals(value.optString("generation")))
        return failure("STALE_GENERATION");
      Bitmap copy = session.frame.copy(Bitmap.Config.ARGB_8888, false);
      if (copy == null) return failure("OBSERVATION_IMAGE_UNAVAILABLE");
      entry = new Entry(copy, session, value.optString("package"), VirtualScreenCore.elapsed());
      if (entry.bytes <= 0 || allocated + entry.bytes > BYTE_LIMIT) {
        copy.recycle();
        return failure("OBSERVATION_IMAGE_BUDGET");
      }
    }
    boolean published = false;
    try {
      // Agent coordinates address this retained raw source directly. A 720px thumbnail here
      // made the model's visible button coordinates disagree with the semantic/raw anchor.
      // Native preview keeps its separate thumbnail path in VirtualScreenPixels.
      int width = entry.raw.getWidth(), height = entry.raw.getHeight();
      ByteArrayOutputStream encoded = new ByteArrayOutputStream();
      if (!entry.raw.compress(Bitmap.CompressFormat.JPEG, 65, encoded) || encoded.size() == 0)
        return failure("FRAME_ENCODE_FAILED");
      value
          .put("frameSeq", entry.sequence)
          .put("sourceFrameSeq", entry.sequence)
          .put("observationId", entry.id)
          .put("observationBytes", entry.bytes)
          .put("observationCreatedElapsedMillis", entry.created)
          .put("previewWidth", width)
          .put("previewHeight", height)
          .put(
              "previewB64",
              android.util.Base64.encodeToString(
                  encoded.toByteArray(), android.util.Base64.NO_WRAP));
      synchronized (session.lock) {
        if (!session.active || !session.generation.equals(entry.generation))
          return failure("STALE_GENERATION");
        // Keep the legacy observed/latest gate exact. An old immutable snapshot does not renew it.
        if (session.sequence == entry.sequence) {
          session.observed = entry.sequence;
          session.observedAt = VirtualScreenCore.elapsed();
        }
        entries.put(entry.id, entry);
        allocated += entry.bytes;
        published = true;
      }
      return value;
    } finally {
      if (!published) entry.raw.recycle();
    }
  }

  synchronized JSONObject region(VirtualScreenDisplaySession session, String id, Rect bounds)
      throws Exception {
    prune(VirtualScreenCore.elapsed());
    Entry entry = entries.get(id);
    if (entry == null) return failure("OBSERVATION_EXPIRED");
    synchronized (session.lock) {
      if (!session.active
          || session.frame == null
          || !entry.generation.equals(session.generation)
          || entry.raw.getWidth() != session.frame.getWidth()
          || entry.raw.getHeight() != session.frame.getHeight()) return failure("STALE_GENERATION");
      if (session.input.active()) return failure("TOUCH_IN_PROGRESS");
      if (bounds == null
          || bounds.left < 0
          || bounds.top < 0
          || bounds.right > entry.raw.getWidth()
          || bounds.bottom > entry.raw.getHeight()
          || bounds.isEmpty()) return failure("INVALID_ANCHOR_REGION");
      boolean equal = MessageDigest.isEqual(hash(entry.raw, bounds), hash(session.frame, bounds));
      JSONObject result =
          equal ? new JSONObject().put("ok", true) : failure("ANCHOR_REGION_CHANGED");
      return result
          .put("sourceFrameSeq", entry.sequence)
          .put("validatedCurrentSeq", session.sequence)
          .put("generation", entry.generation)
          .put("displayId", session.id);
    }
  }

  private static byte[] hash(Bitmap image, Rect region) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    int[] row = new int[region.width()];
    byte[] packed = new byte[Math.multiplyExact(region.width(), 4)];
    for (int y = region.top; y < region.bottom; y++) {
      image.getPixels(row, 0, row.length, region.left, y, row.length, 1);
      for (int x = 0; x < row.length; x++) {
        int pixel = row[x], offset = 4 * x;
        packed[offset] = (byte) (pixel >>> 24);
        packed[offset + 1] = (byte) (pixel >>> 16);
        packed[offset + 2] = (byte) (pixel >>> 8);
        packed[offset + 3] = (byte) pixel;
      }
      digest.update(packed);
    }
    return digest.digest();
  }

  synchronized void prune(long now) {
    Iterator<Entry> iterator = entries.values().iterator();
    while (iterator.hasNext()) {
      Entry entry = iterator.next();
      if (now < entry.created || now - entry.created > TTL_MS) {
        iterator.remove();
        allocated -= entry.bytes;
        entry.raw.recycle();
      }
    }
  }

  synchronized void remove(String id) {
    Entry entry = entries.remove(id);
    if (entry != null) {
      allocated -= entry.bytes;
      entry.raw.recycle();
    }
  }

  synchronized void clear() {
    for (Entry entry : entries.values()) entry.raw.recycle();
    entries.clear();
    allocated = 0;
  }

  synchronized long allocatedBytes() {
    return allocated;
  }

  synchronized int retainedCount() {
    return entries.size();
  }

  private static JSONObject failure(String code) {
    return VirtualScreenTransport.failure(code, false);
  }
}
