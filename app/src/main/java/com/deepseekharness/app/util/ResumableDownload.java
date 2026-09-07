package com.deepseekharness.app.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Range 续传与完整文件校验；身份由调用者固定的大小和 SHA-256 决定。 */
public final class ResumableDownload {
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private ResumableDownload() { }
    public interface Connection { HttpURLConnection open(long offset) throws Exception; }
    public interface Progress {
        void check() throws IOException;
        void changed(long current, long total);
    }
    public static void transfer(File partial, long size, String sha256, Connection source, Progress progress) throws Exception {
        if (size <= 0 || sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")) throw new IOException("下载清单无效");
        long offset = partial.isFile() ? partial.length() : 0;
        if (offset > size) {
            if (!partial.delete()) throw new IOException("无法清理无效下载");
            offset = 0;
        }
        progress.check();
        if (offset < size) {
            HttpURLConnection conn = source.open(offset);
            try {
                int code = conn.getResponseCode();
                boolean append = code == 206;
                if (append) {
                    Matcher range = RANGE.matcher(String.valueOf(conn.getHeaderField("Content-Range")));
                    if (!range.matches() || Long.parseLong(range.group(1)) != offset
                            || Long.parseLong(range.group(2)) != size - 1 || Long.parseLong(range.group(3)) != size)
                        throw new IOException("服务器返回的续传范围与安装包不符");
                } else if (code == 200) offset = 0;
                else throw new IOException("HTTP " + code + "，进度已保留，可稍后继续下载");
                String length = conn.getHeaderField("Content-Length");
                if (length != null && Long.parseLong(length) != size - offset)
                    throw new IOException("服务器返回的文件大小与清单不符");
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(partial, append)) {
                    byte[] buffer = new byte[65536]; int count;
                    try {
                        while ((count = in.read(buffer)) != -1) {
                            progress.check();
                            if (count > size - offset) throw new IOException("下载超过清单大小");
                            out.write(buffer, 0, count); offset += count;
                            progress.changed(offset, size);
                        }
                    } finally { out.getFD().sync(); }
                }
                if (offset != size) throw new IOException("网络传输中断，进度已保留，可继续下载");
            } finally { conn.disconnect(); }
        }
        progress.check();
        verify(partial, size, sha256);
        progress.check();
    }
    public static void verify(File file, long size, String sha256) throws IOException {
        if (!file.isFile() || file.length() != size) throw new IOException("安装包大小发生变化，请重新下载");
        String actual;
        try (FileInputStream in = new FileInputStream(file)) { actual = FileIntegrity.copy(in, null, size).sha256; }
        if (!actual.equalsIgnoreCase(sha256)) {
            file.delete();
            throw new IOException("SHA-256 校验失败，已清理损坏文件，请重新下载");
        }
    }
}
