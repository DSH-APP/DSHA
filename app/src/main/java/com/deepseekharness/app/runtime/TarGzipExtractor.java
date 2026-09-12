package com.deepseekharness.app.runtime;
import com.deepseekharness.app.util.Compat;

import android.system.Os;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.zip.GZIPInputStream;

/**
 * 纯 Java 流式 tar / tar.gz 解压器。
 * aapt 会把 assets 里的 .tar.gz 自动解成 .tar，所以必须同时支持两种。
 * 离线 rootfs 的绝对符号链接（/root、/usr…）会转成 dest 内相对链接，见 {@link #safeSymlinkTarget}。
 */
public final class TarGzipExtractor {

    private static final int BLOCK = 512;
    /** 元数据记录（GNU/PAX）最大长度：超过视为损坏。 */
    private static final long MAX_META_RECORD = 256 * 1024;
    /** 单个常规文件解压上限：防 tar bomb。 */
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024;
    /** 单次解压总输出上限。 */
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024 * 1024;

    /** 最近一次宽松解压跳过的条目数。 */
    public static volatile int lastSkipped = 0;
    /** 最近一次宽松解压跳过的条目名摘要。 */
    public static volatile String lastSkipNote = "";

    private TarGzipExtractor() {
    }

    public static void extract(File tarball, File dest) throws IOException {
        try (InputStream raw = new FileInputStream(tarball)) {
            extractAuto(raw, dest, 0);
        }
    }

    /** gzip 或裸 tar 自动识别（1f 8b gzip magic）。 */
    public static void extractAuto(InputStream raw, File dest, int strip) throws IOException {
        extractSelected(raw, dest, strip, name -> true);
    }

    /** 在同一份离线包上流式筛选受管运行时，避免重新落盘 Ubuntu 和用户数据。 */
    public static void extractSelected(InputStream raw, File dest, int strip, java.util.function.Predicate<String> selected) throws IOException {
        PushbackInputStream pin = new PushbackInputStream(new BufferedInputStream(raw, 1 << 16), 2);
        int b0 = pin.read();
        int b1 = pin.read();
        if (b0 >= 0) {
            if (b1 >= 0) pin.unread(new byte[]{(byte) b0, (byte) b1});
            else pin.unread(b0);
        }
        if (b0 == 0x1f && b1 == 0x8b) {
            try (GZIPInputStream gz = new GZIPInputStream(pin, 1 << 16)) {
                extractTar(gz, dest, strip, selected);
            }
        } else {
            extractTar(pin, dest, strip, selected);
        }
    }

    public static void extractTar(InputStream tar, File dest, int strip) throws IOException {
        extractTar(tar, dest, strip, name -> true);
    }

    private static void extractTar(InputStream tar, File dest, int strip, java.util.function.Predicate<String> selected) throws IOException {
        InputStream in = (tar instanceof BufferedInputStream) ? tar : new BufferedInputStream(tar, 1 << 16);
        byte[] header = new byte[BLOCK];
        // 256KB：既当 inflate 读块、又当写文件缓冲（rootfs 解出约 1GB，8KB 会放大 syscall 次数）
        byte[] buf = new byte[1 << 18];
        String pendingName = null;
        String pendingLinkname = null;
        long totalBytes = 0;

        while (true) {
            if (!readFull(in, header, BLOCK)) break;
            if (isZeroBlock(header)) {
                if (!readFull(in, header, BLOCK)) break;
                if (isZeroBlock(header)) break;
                continue;
            }

            String name = parseString(header, 0, 100);
            long size = parseOctal(header, 124, 12);
            int mode = (int) parseOctal(header, 100, 8);
            int type = header[156] & 0xFF;
            String linkname = parseString(header, 157, 100);
            if (pendingLinkname != null) {
                linkname = pendingLinkname;
                pendingLinkname = null;
            }

            if (type == 'L' || type == 'x' || type == 'K') {
                if (size <= 0 || size > MAX_META_RECORD) {
                    throw new IOException(com.deepseekharness.app.util.UiText.text("预构建包损坏（超长元数据记录 size=") + size + com.deepseekharness.app.util.UiText.text("）"));
                }
                byte[] longData = new byte[(int) size];
                readFull(in, longData, longData.length);
                skipPadding(in, size);
                if (type == 'L') {
                    pendingName = parseString(longData, 0, longData.length);
                } else if (type == 'K') {
                    pendingLinkname = parseString(longData, 0, longData.length);
                } else {
                    pendingName = parsePaxPath(longData);
                }
                continue;
            }

            if (pendingName != null) {
                name = pendingName;
                pendingName = null;
            }

            String prefix = parseString(header, 345, 155);
            if (prefix != null && !prefix.isEmpty()) {
                name = prefix + "/" + name;
            }

            if (strip > 0) {
                for (int i = 0; i < strip; i++) {
                    int idx = name.indexOf('/');
                    if (idx < 0) { name = null; break; }
                    name = name.substring(idx + 1);
                }
                if (name == null || name.isEmpty()) {
                    skipPadding(in, size);
                    continue;
                }
            }

            File out = new File(dest, name);

            boolean traversal = name.equals("..")
                    || name.startsWith("/")
                    || name.startsWith("../") || name.contains("/../") || name.endsWith("/..")
                    || name.contains("\u0000");
            if (name == null || name.isEmpty() || traversal
                    || name.contains("..") || name.contains("\\\"") || name.contains(",")) {
                throw new IOException(com.deepseekharness.app.util.UiText.text("预构建包损坏（非法文件条目: ") + safeName(name) + com.deepseekharness.app.util.UiText.text("）"));
            }

            if (!selected.test(name)) {
                long remaining = size;
                while (remaining > 0) {
                    int count = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (count < 0) throw new IOException(com.deepseekharness.app.util.UiText.text("预构建包被截断"));
                    remaining -= count;
                }
                skipPadding(in, size);
                continue;
            }

            switch (type) {
                case '0':
                case 0:
                case '7':
                    if (size > MAX_FILE_BYTES || totalBytes + size > MAX_TOTAL_BYTES) {
                        throw new IOException(com.deepseekharness.app.util.UiText.text("预构建包损坏（文件过大 size=") + size + com.deepseekharness.app.util.UiText.text("）"));
                    }
                    writeFile(in, out, size, mode, buf);
                    totalBytes += size;
                    break;
                case '5':
                    out.mkdirs();
                    skipPadding(in, size);
                    break;
                case '2':
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    String symlinkTarget = safeSymlinkTarget(dest, out, linkname);
                    if (symlinkTarget != null) {
                        try {
                            Os.symlink(symlinkTarget, out.getAbsolutePath());
                        } catch (Throwable ignored) {
                        }
                    }
                    skipPadding(in, size);
                    break;
                case '1':
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    if (linkSafeWithin(dest, out, linkname, true)) {
                        try {
                            Os.link(new File(dest, linkname).getAbsolutePath(), out.getAbsolutePath());
                        } catch (Throwable ignored) {
                        }
                    }
                    skipPadding(in, size);
                    break;
                default:
                    skipPadding(in, size);
                    break;
            }
        }
    }

    private static boolean linkSafeWithin(File dest, File out, String linkname, boolean relativeToDest) {
        if (linkname == null || linkname.isEmpty()) return false;
        String trimmed = linkname.replace('\\', '/');
        if (trimmed.startsWith("/")) return false;
        try {
            String root = Compat.normalizePosix(dest.getAbsolutePath());
            String base = relativeToDest || out.getParentFile() == null
                    ? root
                    : Compat.normalizePosix(out.getParentFile().getAbsolutePath());
            String target = Compat.normalizePosix(base + "/" + trimmed);
            return target.equals(root) || (target.startsWith(root)
                    && target.length() > root.length() && target.charAt(root.length()) == '/');
        } catch (Throwable e) {
            return false;
        }
    }

    /** 把 rootfs 内的绝对链接（/root/…）映射进 dest 并转成相对链接。 */
    private static String safeSymlinkTarget(File dest, File out, String linkname) {
        if (linkname == null || linkname.isEmpty()) return null;
        File raw = new File(linkname);
        if (!raw.isAbsolute()) {
            return linkSafeWithin(dest, out, linkname, false) ? linkname : null;
        }
        try {
            String root = Compat.normalizePosix(dest.getAbsolutePath());
            String virtual = linkname.replace('\\', '/');
            String target = Compat.normalizePosix(root + "/" + virtual.substring(1));
            if (!(target.equals(root) || (target.startsWith(root)
                    && target.length() > root.length() && target.charAt(root.length()) == '/'))) {
                return null;
            }
            String parent = Compat.normalizePosix(out.getParentFile().getAbsolutePath());
            return Compat.relativizePosix(parent, target);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void writeFile(InputStream in, File out, long size, int mode, byte[] buf)
            throws IOException {
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            long remaining = size;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new IOException(com.deepseekharness.app.util.UiText.text("tar 数据意外结束"));
                fos.write(buf, 0, n);
                remaining -= n;
            }
        }
        try {
            Os.chmod(out.getAbsolutePath(), mode & 0777);
        } catch (Throwable ignored) {
        }
        skipPadding(in, size);
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (BLOCK - (size % BLOCK)) % BLOCK;
        long remaining = pad;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static String safeName(String name) {
        if (name == null) return "(null)";
        String s = name.replace("\n", "\\n").replace("\r", "\\r");
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static boolean readFull(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) {
                if (off == 0) return false; // 干净 EOF
                throw new IOException(com.deepseekharness.app.util.UiText.text("tar 数据意外结束（需要 ") + len + com.deepseekharness.app.util.UiText.text(" 字节，只读到 ") + off + com.deepseekharness.app.util.UiText.text("）"));
            }
            off += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String parseString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private static String parsePaxPath(byte[] data) {
        String s = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        for (String line : s.split("\\n")) {
            String t = line.trim();
            int sp = t.indexOf(' ');
            if (sp <= 0) continue;
            String kv = t.substring(sp + 1).trim();
            if (kv.startsWith("path=")) return kv.substring("path=".length());
        }
        return null;
    }
}
