package com.deepseekharness.app.bridge;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.deepseekharness.app.util.BridgeImage;

/** Validate the actual Android decoder result before sending image bytes to a client. */
public final class BridgeImageDecoder {
  private BridgeImageDecoder() {}

  public static boolean valid(byte[] image, String media) {
    if (image == null || media == null || !media.equals(BridgeImage.mediaType(image))) return false;
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
    if (!media.equals(bounds.outMimeType)
        || !BridgeImage.safeDimensions(bounds.outWidth, bounds.outHeight)) return false;
    Bitmap bitmap = BitmapFactory.decodeByteArray(image, 0, image.length);
    if (bitmap == null) return false;
    try {
      return bitmap.getWidth() == bounds.outWidth && bitmap.getHeight() == bounds.outHeight;
    } finally {
      bitmap.recycle();
    }
  }
}
