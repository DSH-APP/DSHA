package com.deepseekharness.app.util;

import java.nio.charset.StandardCharsets;

/**
 * 有界字节尾部缓冲：持续写入进程输出时只保留最近 {@code maxBytes} 字节，不整份载入内存。
 *
 * <p>超限后丢弃较早输出，并按行边界裁掉可能被截断的首行，避免密钥字段名被截掉后只剩无法识别的值；
 * 与 {@link TextLogTail} 使用相同的省略标记，导出包内两类日志的口径一致。
 */
public final class BoundedByteTail {
  private final byte[] buffer;
  private int start;
  private int size;
  private boolean dropped;

  public BoundedByteTail(int maxBytes) {
    if (maxBytes <= 0) throw new IllegalArgumentException(UiText.text("日志大小限制必须为正数"));
    buffer = new byte[maxBytes];
  }

  public void write(byte[] bytes, int offset, int length) {
    if (bytes == null || length <= 0) return;
    if (offset < 0 || length > bytes.length - offset) throw new IllegalArgumentException("range");
    if (length >= buffer.length) {
      boolean discarded = dropped || size > 0 || length > buffer.length;
      System.arraycopy(bytes, offset + length - buffer.length, buffer, 0, buffer.length);
      start = 0;
      size = buffer.length;
      dropped = discarded;
      return;
    }
    int end = (start + size) % buffer.length;
    int first = Math.min(length, buffer.length - end);
    System.arraycopy(bytes, offset, buffer, end, first);
    if (length > first) System.arraycopy(bytes, offset + first, buffer, 0, length - first);
    int grown = size + length;
    if (grown > buffer.length) {
      start = (start + (grown - buffer.length)) % buffer.length;
      size = buffer.length;
      dropped = true;
    } else {
      size = grown;
    }
  }

  /** 是否已丢弃过较早输出；仅用于在 manifest 里如实记录采集边界。 */
  public boolean truncated() {
    return dropped;
  }

  public int size() {
    return size;
  }

  public String text() {
    byte[] data = new byte[size];
    if (size > 0) {
      int first = Math.min(size, buffer.length - start);
      System.arraycopy(buffer, start, data, 0, first);
      if (size > first) System.arraycopy(buffer, 0, data, first, size - first);
    }
    int begin = 0;
    if (dropped) while (begin < data.length && data[begin] != '\n') begin++;
    String body = new String(data, begin, data.length - begin, StandardCharsets.UTF_8);
    return (dropped ? UiText.text("（较早输出已省略，以下为最近日志）\n") : "") + body;
  }
}
