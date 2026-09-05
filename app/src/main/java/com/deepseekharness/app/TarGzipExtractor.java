package com.deepseekharness.app;

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
 */
public final class TarGzipExtractor {

    private static final int BLOCK = 512;
    /** 元数据记录（GNU/PAX）最大长度：超过视为损坏，防巨量 skip 后读错流。 */
    private static final long MAX_META_RECORD = 256 * 1024;
    /** 单个常规文件解压上限：防 tar bomb（rootfs 内最大单文件一般远小于此）。 */
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024;
    /** 单次解压总输出上限：给足 rootfs/备份空间，同时拦截无限增长。 */
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024 * 1024;

    /** 最近一次宽松解压跳过的条目数（0=全部恢复） */
    public static volatile int lastSkipped = 0;
    /** 最近一次宽松解压跳过的条目名摘要（截断，用于给用户看） */
    public static volatile String lastSkipNote = "";

    private TarGzipExtractor() {}

    public static void extract(File tarball, File dest) throws IOException {
        extract(tarball, dest, 0);
    }

    /** 恢复备份用：宽松模式（只拦路径穿越，不拦逗号/引号等正常文件名）。
     *  宽容策略：可疑/超大条目只跳过并计入 {@link #lastSkipped}，绝不让一条坏记录
     *  废掉整份备份 —— 用户宁可少恢复一个文件，也不要「恢复失败」四个字。 */
    public static void extractLenient(File tarball, File dest) throws IOException {
        lastSkipped = 0;
        lastSkipNote = "";
        try (InputStream raw = new FileInputStream(tarball)) {
            extractAuto(raw, dest, 0, true);
        }
    }

    public static void extract(File tarball, File dest, int strip) throws IOException {
        try (InputStream raw = new FileInputStream(tarball)) {
            extractAuto(raw, dest, strip);
        }
    }

    /** 严格模式解压（插件导入用）：拒绝符号链接/硬链接条目，防链接逃逸。 */
    public static void extractSafe(File tarball, File dest) throws IOException {
        try (InputStream raw = new FileInputStream(tarball)) {
            extractAuto(raw, dest, 0, false, true);
        }
    }

    /** gzip 或裸 tar 自动识别。 */
    public static void extractAuto(InputStream raw, File dest, int strip) throws IOException {
        extractAuto(raw, dest, strip, false);
    }

    /** gzip 或裸 tar 自动识别。lenient=true 用于恢复用户备份：文件名含逗号/引号不判损坏。 */
    public static void extractAuto(InputStream raw, File dest, int strip, boolean lenient) throws IOException {
        extractAuto(raw, dest, strip, lenient, false);
    }

    /** 完整参数：rejectLinks=true 时遇到 symlink/hardlink 直接判非法（严格来源用）。 */
    public static void extractAuto(InputStream raw, File dest, int strip, boolean lenient,
                                   boolean rejectLinks) throws IOException {
        PushbackInputStream pin = new PushbackInputStream(new BufferedInputStream(raw, 1 << 16), 2);
        int b0 = pin.read();
        int b1 = pin.read();
        if (b0 >= 0) {
            if (b1 >= 0) pin.unread(new byte[]{(byte) b0, (byte) b1});
            else pin.unread(b0);
        }
        if (b0 == 0x1f && b1 == 0x8b) {
            try (GZIPInputStream gz = new GZIPInputStream(pin, 1 << 16)) {
                extractTar(gz, dest, strip, lenient, rejectLinks);
            }
        } else {
            extractTar(pin, dest, strip, lenient, rejectLinks);
        }
    }

    public static void extract(InputStream rawGzip, File dest, int strip) throws IOException {
        extractAuto(rawGzip, dest, strip);
    }

    public static void extractTar(InputStream tar, File dest, int strip) throws IOException {
        extractTar(tar, dest, strip, false, false);
    }

    public static void extractTar(InputStream tar, File dest, int strip, boolean lenient) throws IOException {
        extractTar(tar, dest, strip, lenient, false);
    }

    public static void extractTar(InputStream tar, File dest, int strip, boolean lenient,
                                  boolean rejectLinks) throws IOException {
        InputStream in = (tar instanceof BufferedInputStream) ? tar : new BufferedInputStream(tar, 1 << 16);
        byte[] header = new byte[BLOCK];
        // 256KB 而不是 8KB：这个数组既当 inflate 的读块、又当写文件的缓冲。
        // 首次解压要过掉约 1GB 数据（rootfs 解出来的量），8KB 一次就是十几万次 write
        // 系统调用；对 libxul 那种上百 MB 的大文件差别最明显。
        // 实测（本机 381MB gzip 流，只算 inflate 那段）：GZIP 内部缓冲 512B + 读块 8KB
        // 用 2223ms，换成 64KB + 256KB 是 1898ms，快 14.6%；写文件那部分的 syscall
        // 收益不在这个数里。代价是 256KB 常驻内存，解压期间而已。
        byte[] buf = new byte[1 << 18];
        String pendingName = null;
        /** GNU 长链接名（type 'K'）：下一个符号链接/硬链接条目的 linkname 用这个 */
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
            // GNU 长链接名：优先用 K 扩展提供的（header 里的 100 字节是截断的）
            if (pendingLinkname != null) {
                linkname = pendingLinkname;
                pendingLinkname = null;
            }

            if (type == 'L' || type == 'x' || type == 'K') {
                // 元数据记录必须整体读入（旧实现 clampSize 只读 64KB，超过会漏读
                // 导致流错位）；超过上限直接判损坏。
                if (size <= 0 || size > MAX_META_RECORD) {
                    throw new IOException("预构建包损坏（超长元数据记录 size=" + size + "）");
                }
                byte[] longData = new byte[(int) size];
                readFull(in, longData, longData.length);
                skipPadding(in, size);
                if (type == 'L') {
                    pendingName = parseString(longData, 0, longData.length);
                } else if (type == 'K') {
                    // GNU 长链接名：下一个链接条目用它（不直接拼 name）
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

            // 路径安全校验：
            // 普通模式（下载包）拦一切可疑（含 .. 子串、引号、逗号）；
            // 宽松模式（恢复用户备份）只拦【真正的路径穿越】：
            //   绝对路径 / 开头 ../ 或包含 /../ 的段 / NUL
            //   文件名里的 ".."（如 v1.2..3.md）不是穿越，必须放行——
            //   否则用户备份里含双点的正常文件名恢复失败（"非法文件条目"）
            boolean traversal = name.equals("..")
                    || name.startsWith("/")
                    || name.startsWith("../") || name.contains("/../") || name.endsWith("/..")
                    || name.contains("\u0000");
            if (name == null || name.isEmpty()
                    || traversal
                    || (!lenient && (name.contains("..") || name.contains("\\\"") || name.contains(",")))) {
                if (lenient) {
                    // 宽容恢复：跳过这一条，继续恢复其余文件（备份里出现异常条目不致命）
                    noteSkip(name);
                    skipEntry(in, size);
                    continue;
                }
                throw new IOException("预构建包损坏（非法文件条目: " + safeName(name)
                        + "），请重新下载或改用「直连源码构建」");
            }

            switch (type) {
                case '0':
                case 0:
                case '7':
                    if (size > MAX_FILE_BYTES || totalBytes + size > MAX_TOTAL_BYTES) {
                        if (lenient) {
                            // 单个超大文件/总量超限：跳过它，剩下的照常恢复
                            noteSkip(name + "(过大)");
                            skipEntry(in, size);
                            continue;
                        }
                        throw new IOException("预构建包损坏（文件过大 size=" + size + "）");
                    }
                    writeFile(in, out, size, mode, buf);
                    totalBytes += size;
                    break;
                case '5':
                    out.mkdirs();
                    skipPadding(in, size);
                    break;
                case '2':
                    if (rejectLinks) {
                        throw new IOException("预构建包损坏（禁止符号链接条目: " + safeName(name) + "）");
                    }
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    // rootfs tar 使用 /root/...、/usr/... 这类「容器内绝对路径」。
                    // Android 宿主不能直接创建指向自身 /root 的链接，因此仅在离线
                    // rootfs 解压（rejectLinks=false）时把它转换成 dest 内相对链接。
                    // 外部/备份归档仍拒绝绝对链接，避免任何路径逃逸。
                    String symlinkTarget = safeSymlinkTarget(dest, out, linkname, rejectLinks);
                    if (symlinkTarget == null) {
                        // 目标越界或不可用：记下来。静默跳过会解出一个「命令文件不见了」
                        // 的 rootfs，而现场只有一条没人看的日志。
                        noteSkip(name + "(软链目标不安全)");
                    } else {
                        try {
                            // 同名残留会让 symlink 直接 EEXIST，那正是「解压看着成功、
                            // 实际少了一条链」的来源。
                            java.nio.file.Files.deleteIfExists(out.toPath());
                        } catch (Throwable ignored) {
                        }
                        try {
                            Os.symlink(symlinkTarget, out.getAbsolutePath());
                        } catch (Throwable e) {
                            noteSkip(name + "(软链创建失败)");
                        }
                    }
                    skipPadding(in, size);
                    break;
                case '1':
                    if (rejectLinks) {
                        throw new IOException("预构建包损坏（禁止硬链接条目: " + safeName(name) + "）");
                    }
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    // 硬链接目标同样必须留在 dest 内（不允许链到已有敏感文件）
                    // 硬链接按 dest 基准校验，与下面 new File(dest, linkname) 保持一致
                    if (linkSafeWithin(dest, out, linkname, true)) {
                        try {
                            Os.link(new File(dest, linkname).getAbsolutePath(), out.getAbsolutePath());
                        } catch (Throwable e) {
                            // App 私有目录不支持硬链接（SELinux）—— 这是常态，
                            // 但结果是那个文件不存在，必须可见而不是静默。
                            noteSkip(name + "(硬链接创建失败)");
                        }
                    } else {
                        noteSkip(name + "(硬链接目标越界)");
                    }
                    skipPadding(in, size);
                    break;
                default:
                    skipPadding(in, size);
                    break;
            }
        }
    }

    /** 链接目标安全校验：非绝对路径且 normalize 后仍落在 dest 内。
     *
     *  @param relativeToDest true 时按「linkname 相对 dest」解析（tar 的硬链接语义），
     *                        false 时按「相对 out 所在目录」解析（符号链接语义）。
     *  这个参数是必须的：两种链接的基准本来就不同，而此前统一按 out 的父目录校验，
     *  硬链接实际却用 new File(dest, linkname) —— 校验和使用对不上，
     *  形如 ../../etc/x 的 linkname 能通过校验却链到 dest 之外。 */
    private static boolean linkSafeWithin(File dest, File out, String linkname,
                                          boolean relativeToDest) {
        if (linkname == null || linkname.isEmpty()) return false;
        String trimmed = new File(linkname).getPath();
        if (new File(trimmed).isAbsolute()) return false;
        try {
            java.nio.file.Path root = dest.toPath().toAbsolutePath().normalize();
            java.nio.file.Path base = relativeToDest || out.getParentFile() == null
                    ? root
                    : out.getParentFile().toPath().toAbsolutePath().normalize();
            java.nio.file.Path target = base.resolve(trimmed).normalize();
            String rs = root.toString();
            String ts = target.toString();
            return ts.equals(rs) || (ts.startsWith(rs) && ts.length() > rs.length()
                    && ts.charAt(rs.length()) == java.io.File.separatorChar);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Returns a host-valid symlink target for an internal rootfs link, or null when unsafe.
     * Absolute targets are mapped from the virtual root (/) into {@code dest} and made
     * relative to the link's parent, so the resulting tree behaves identically in chroot.
     */
    private static String safeSymlinkTarget(File dest, File out, String linkname,
                                            boolean rejectLinks) {
        if (linkname == null || linkname.isEmpty()) return null;
        File raw = new File(linkname);
        if (!raw.isAbsolute()) {
            return linkSafeWithin(dest, out, linkname, false) ? linkname : null;
        }
        if (rejectLinks) return null;
        try {
            java.nio.file.Path root = dest.toPath().toAbsolutePath().normalize();
            String virtual = linkname.replace('\\', '/');
            java.nio.file.Path target = root.resolve(virtual.substring(1)).normalize();
            String rs = root.toString();
            String ts = target.toString();
            if (!(ts.equals(rs) || (ts.startsWith(rs) && ts.length() > rs.length()
                    && ts.charAt(rs.length()) == java.io.File.separatorChar))) return null;
            java.nio.file.Path parent = out.getParentFile().toPath().toAbsolutePath().normalize();
            return parent.relativize(target).toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private static void writeFile(InputStream in, File out, long size, int mode, byte[] buf)
            throws IOException {
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        // 目标已是符号链接时先摘掉链接本身再写。跟随链接写等于让归档里
        // 「先放一条软链、再放一个同名文件」的组合把内容写到链接指向的地方去
        // （公开数据目录下的 sessions/storages 就是这种链接）。tar 里那条文件条目
        // 表达的是「这里应该是一个普通文件」，覆盖链接才是忠实还原。
        try {
            if (java.nio.file.Files.isSymbolicLink(out.toPath())) {
                java.nio.file.Files.delete(out.toPath());
            }
        } catch (Throwable ignored) {
        }
        try (FileOutputStream fos = new FileOutputStream(out)) {
            long remaining = size;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new IOException("tar 数据意外结束");
                fos.write(buf, 0, n);
                remaining -= n;
            }
        }
        chmodBestEffort(out, mode);
        skipPadding(in, size);
    }

    private static void chmodBestEffort(File f, int mode) {
        try {
            Os.chmod(f.getAbsolutePath(), mode & 0777);
        } catch (Throwable ignored) {
        }
    }

    /** 记录一个被跳过的条目（宽容恢复用） */
    private static void noteSkip(String name) {
        lastSkipped++;
        String note = lastSkipNote;
        if (note.length() < 400) {
            lastSkipNote = note.isEmpty() ? safeName(name) : note + ", " + safeName(name);
        }
    }

    /** 跳过整个条目（数据体 + 块对齐填充） */
    private static void skipEntry(InputStream in, long size) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                remaining--;
            } else {
                remaining -= skipped;
            }
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

    /**
     * 读满 len 字节。返回 false = 干净 EOF（一字节都没读到，用于判断 tar 正常结束）；
     * 读到一半 EOF = 数据损坏，抛 IOException（不能静默当成功，否则解出半截 rootfs）。
     */
    private static boolean readFull(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) {
                if (off == 0) return false; // 干净 EOF
                throw new IOException("tar 数据意外结束（需要 " + len + " 字节，只读到 " + off + "）");
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
        // PAX 记录格式： "<len> key=value\n"（len 含 key=value+\n 本身长度）
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
