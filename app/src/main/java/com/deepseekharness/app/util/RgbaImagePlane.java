package com.deepseekharness.app.util;

import java.nio.ByteBuffer;

/** 只读取 RGBA 平面的有效像素；最后一行不要求存在行尾填充。 */
public final class RgbaImagePlane {
  private RgbaImagePlane() {}

  public static ByteBuffer packed(
      ByteBuffer source, int width, int height, int pixelStride, int rowStride) {
    if (source == null || width <= 0 || height <= 0 || pixelStride < 4 || rowStride <= 0)
      throw new IllegalArgumentException("INVALID_RGBA_PLANE");
    long rowBytes = (long) width * 4;
    long rowSpan = (long) (width - 1) * pixelStride + 4;
    long packedBytes = rowBytes * height;
    long required = (long) (height - 1) * rowStride + rowSpan;
    if (rowSpan > rowStride || packedBytes > Integer.MAX_VALUE || required > source.limit())
      throw new IllegalArgumentException("INCOMPLETE_RGBA_PLANE");
    ByteBuffer input = source.duplicate();
    input.rewind();
    if (pixelStride == 4 && rowStride == rowBytes) {
      input.limit((int) packedBytes);
      return input;
    }
    ByteBuffer output = ByteBuffer.allocate((int) packedBytes);
    for (int y = 0; y < height; y++) {
      int row = (int) ((long) y * rowStride);
      if (pixelStride == 4) {
        input.limit(row + (int) rowBytes);
        input.position(row);
        output.put(input);
        input.limit(source.limit());
      } else {
        for (int x = 0; x < width; x++) {
          int pixel = row + x * pixelStride;
          for (int channel = 0; channel < 4; channel++) output.put(input.get(pixel + channel));
        }
      }
    }
    output.flip();
    return output;
  }
}
