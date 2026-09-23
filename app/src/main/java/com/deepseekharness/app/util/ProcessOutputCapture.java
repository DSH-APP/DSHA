package com.deepseekharness.app.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** 输出继续排空，同时独立保留开头和真正的末尾；不能把截断头部当作错误尾部。 */
public final class ProcessOutputCapture {
    private final int headLimit;
    private final ByteArrayOutputStream head;
    private final byte[] tail;
    private int tailPosition, tailSize;
    private long total;

    public ProcessOutputCapture(int headLimit, int tailLimit) {
        if (headLimit < 0 || tailLimit < 0) throw new IllegalArgumentException("OUTPUT_LIMIT");
        this.headLimit = headLimit;
        head = new ByteArrayOutputStream(Math.min(headLimit, 8192));
        tail = new byte[tailLimit];
    }

    public void write(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        int keep = Math.min(length, headLimit - head.size());
        head.write(bytes, offset, keep);
        total = total > Long.MAX_VALUE - length ? Long.MAX_VALUE : total + length;
        if (tail.length == 0) return;
        // 大块只复制最终能进入环形缓冲的部分，避免无效逐字节工作。
        int start = Math.max(offset, offset + length - tail.length);
        for (int at = start; at < offset + length; at++) {
            tail[tailPosition] = bytes[at];
            tailPosition = (tailPosition + 1) % tail.length;
            tailSize = Math.min(tail.length, tailSize + 1);
        }
    }

    public String head() { return new String(head.toByteArray(), StandardCharsets.UTF_8); }
    public String tail() {
        byte[] bytes = new byte[tailSize];
        int start = (tailPosition - tailSize + tail.length) % Math.max(1, tail.length);
        for (int at = 0; at < tailSize; at++) bytes[at] = tail[(start + at) % tail.length];
        int offset = 0;
        // 环形起点可能落在 UTF-8 字符中间；只跳过最前面的续字节。
        while (offset < bytes.length && (bytes[offset] & 0xc0) == 0x80) offset++;
        return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
    }
    public long totalBytes() { return total; }
    public int headLimit() { return headLimit; }
    public boolean truncated() { return total > headLimit; }
}
