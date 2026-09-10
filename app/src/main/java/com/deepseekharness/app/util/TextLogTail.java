package com.deepseekharness.app.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** 有界读取最新 UTF-8 日志；不把大型运行输出整份载入内存。 */
public final class TextLogTail {
    private TextLogTail() { }
    public static String read(File file, int maxBytes) throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("日志大小限制必须为正数");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length(), offset = Math.max(0, length - maxBytes);
            boolean boundary = offset == 0;
            if (offset > 0) { input.seek(offset - 1); boundary = input.read() == '\n'; }
            input.seek(offset);
            byte[] bytes = new byte[(int) Math.min(length, maxBytes)];
            int count = 0, n;
            while (count < bytes.length && (n = input.read(bytes, count, bytes.length - count)) > 0) count += n;
            int start = 0;
            // 不导出被截断的首行，避免密钥字段名被截掉后只剩无法识别的值；同时保持 UTF-8 字符完整。
            if (!boundary) { while (start < count && bytes[start++] != '\n') { } }
            return (offset > 0 ? "（较早输出已省略，以下为最近日志）\n" : "")
                    + new String(bytes, start, count - start, StandardCharsets.UTF_8);
        }
    }
}
