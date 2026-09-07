package com.deepseekharness.app.util;

import java.io.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** wheel 缓存补缺与安装。缓存原文件永不覆盖；无 Android 依赖，便于恢复回归测试。 */
public final class AdbWheelCache {
    private AdbWheelCache() { }
    private static final long MAX_WHEEL = 128L * 1024 * 1024;
    private static final long MAX_EXPANDED = 512L * 1024 * 1024;
    private static final int MAX_ENTRIES = 50_000;

    public static final class Merge {
        public int added, same, modified, extra;
        public boolean archiveAdded, archiveDifferent;
        public final List<String> preserved = new ArrayList<>();
        public String message() {
            List<String> names = new ArrayList<>();
            for (String name : preserved) names.add(inline(name));
            return "WHEELS_CACHE_READY: 补齐 " + added + " 个，原版已在位 " + same
                    + " 个，保留修改版 " + modified + " 个，保留额外 wheel " + extra + " 个"
                    + (names.isEmpty() ? "" : "\nWHEELS_PRESERVED: " + String.join("、", names))
                    + (archiveDifferent ? "\nWHEELS_ARCHIVE_PRESERVED: 现有归档与 APK 不同（可能修改或损坏），原样保留；本次补缺从 APK 临时副本读取" : "");
        }
    }

    /** APK 已在独立目录解开；现有同名条目（含坏文件、链接、目录）不会被替换。 */
    public static Merge fillMissing(File bundled, File cache, File apkArchive, File cachedArchive) throws IOException {
        List<File> originals = wheels(bundled);
        validate(bundled); // 先校验 APK 副本，再发布任何缓存。
        directory(cache);
        Set<String> expected = new HashSet<>();
        Merge report = new Merge();
        for (File original : originals) {
            checkCancelled();
            expected.add(original.getName());
            File target = new File(cache, original.getName());
            if (present(target)) {
                if (regular(target) && target.length() <= MAX_WHEEL && digest(original).equals(digest(target))) report.same++;
                else { report.modified++; report.preserved.add(original.getName()); }
            } else if (copyMissing(original, target)) report.added++;
            else { report.modified++; report.preserved.add(original.getName()); }
        }
        File[] all = cache.listFiles();
        if (all == null) throw new IOException("无法列出 wheel 缓存目录");
        for (File f : all) if (f.getName().endsWith(".whl") && !expected.contains(f.getName())) report.extra++;
        if (present(cachedArchive)) {
            report.archiveDifferent = !regular(cachedArchive) || cachedArchive.length() > MAX_WHEEL
                    || !digest(apkArchive).equals(digest(cachedArchive));
        } else report.archiveAdded = copyMissing(apkArchive, cachedArchive);
        return report;
    }

    /** 校验所有源文件的 ZIP 路径、大小和 CRC；一项损坏都不能被算作安装成功。 */
    public static void validate(File cache) throws IOException {
        long[] totals = {0, 0};
        for (File wheel : wheels(cache)) {
            try { readWheel(wheel, null, null, totals); }
            catch (IOException e) { throw damaged(wheel, e); }
        }
    }

    /** 全部 wheel 先解入临时目录；损坏或文件冲突时不改现有 Python 安装。 */
    public static String install(File cache, File site, File stagingParent) throws IOException {
        List<File> source = wheels(cache);
        directory(stagingParent);
        File stage = new File(stagingParent, ".adb-wheel-install-" + UUID.randomUUID());
        if (!stage.mkdir()) throw new IOException("无法创建 wheel 解包临时目录");
        final String boundary = stage.getCanonicalPath();
        Map<String, String> owners = new HashMap<>();
        long[] totals = {0, 0};
        try {
            for (File wheel : source) {
                try { readWheel(wheel, stage, owners, totals); }
                catch (IOException e) { throw damaged(wheel, e); }
            }
            directory(site);
            // 所有源缓存仍原样在位；安装只从已验证的临时目录发布。
            for (String name : new TreeSet<>(owners.keySet())) {
                File target = child(site, name);
                directory(target.getParentFile());
                File tmp = File.createTempFile(".adb-wheel-", ".tmp", target.getParentFile());
                try {
                    copy(new File(stage, name), tmp);
                    publish(tmp, target, name);
                } finally { if (tmp.exists() && !tmp.delete()) throw new IOException("无法清理安装临时文件：" + tmp.getName()); }
            }
            return "WHEELS_JAVA_EXTRACTED: 已校验并安装 " + source.size() + " 个 wheel，源缓存全部保留";
        } finally { removeStage(stage, boundary); }
    }

    private static void readWheel(File wheel, File stage, Map<String, String> owners, long[] totals) throws IOException {
        if (!regular(wheel)) throw new IOException("不是普通 wheel 文件（链接/目录/特殊条目已保留）");
        if (wheel.length() > MAX_WHEEL) throw new IOException("wheel 大于 128 MiB");
        try (ZipFile zip = new ZipFile(wheel)) {
            if (zip.size() == 0) throw new IOException("空 wheel");
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkCancelled();
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (++totals[1] > MAX_ENTRIES) throw new IOException("wheel 条目过多");
                relativeName(name);
                File out = stage == null ? null : child(stage, name);
                if (entry.isDirectory()) continue;
                if (entry.getSize() < 0 || entry.getSize() > MAX_WHEEL) throw new IOException("文件大小无效：" + name);
                boolean duplicate = stage != null && owners.containsKey(name);
                if (stage != null && present(out) && !duplicate) throw new IOException("条目路径冲突：" + name);
                if (stage != null && !duplicate) directory(out.getParentFile());
                CRC32 crc = new CRC32(); long bytes = 0;
                java.security.MessageDigest sha;
                try { sha = java.security.MessageDigest.getInstance("SHA-256"); }
                catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream target = stage == null || duplicate ? new ByteArrayOutputStream(0) {
                         @Override public void write(byte[] b, int off, int len) { }
                         @Override public void write(int b) { }
                     } : new FileOutputStream(out)) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        checkCancelled(); bytes += n; totals[0] += n;
                        if (bytes > MAX_WHEEL || totals[0] > MAX_EXPANDED) throw new IOException("wheel 解包大小超过上限");
                        crc.update(buffer, 0, n); sha.update(buffer, 0, n); target.write(buffer, 0, n);
                    }
                }
                if (bytes != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("CRC/长度校验失败：" + name);
                if (duplicate) {
                    StringBuilder hash = new StringBuilder();
                    for (byte b : sha.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
                    if (!hash.toString().equals(digest(out))) throw new IOException("与 " + owners.get(name) + " 的文件冲突：" + name + "；请选择需要使用的版本");
                } else if (stage != null) owners.put(name, wheel.getName());
            }
        }
    }

    private static IOException damaged(File wheel, IOException cause) {
        return new IOException("WHEELS_CACHE_INVALID: " + inline(wheel.getName()) + "：" + inline(cause.getMessage())
                + "。源缓存已保留，未用 APK 覆盖。请先备份该文件并移出 wheels 目录，再重试补缺；自定义 wheel 请提供有效版本。", cause);
    }

    /** Android/Linux 可原子覆盖安装文件；Windows 不支持时先保留旧文件，失败再恢复。 */
    private static void publish(File tmp, File target, String name) throws IOException {
        if (tmp.renameTo(target)) return;
        if (!regular(target)) throw new IOException("无法发布 Python 文件：" + name);
        File previous = File.createTempFile(".adb-wheel-previous-", ".tmp", target.getParentFile());
        if (!previous.delete() || !target.renameTo(previous)) throw new IOException("无法保留旧 Python 文件：" + name);
        if (!tmp.renameTo(target)) {
            if (!previous.renameTo(target)) throw new IOException("安装发布及回滚失败；旧文件仍保留在 " + previous.getAbsolutePath());
            throw new IOException("无法发布 Python 文件，旧文件已恢复：" + name);
        }
        if (!previous.delete()) throw new IOException("新文件已发布，但旧副本清理失败：" + previous.getAbsolutePath());
    }

    private static List<File> wheels(File dir) throws IOException {
        if (!dir.isDirectory()) throw new IOException("wheel 目录不存在或不是目录");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".whl"));
        if (files == null || files.length == 0) throw new IOException("wheel 目录为空");
        Arrays.sort(files, Comparator.comparing(File::getName));
        return Arrays.asList(files);
    }

    /** list() 也能看见 dangling symlink，不能仅用 exists() 判断是否可覆盖。 */
    private static boolean present(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory()) return file.exists();
        String[] names = parent.list();
        if (names == null) throw new IOException("无法列出缓存父目录");
        return Arrays.asList(names).contains(file.getName());
    }

    private static boolean regular(File file) throws IOException {
        return file.isFile() && file.getCanonicalFile().equals(new File(file.getParentFile().getCanonicalFile(), file.getName()));
    }

    private static boolean copyMissing(File source, File target) throws IOException {
        directory(target.getParentFile());
        if (present(target) || !target.createNewFile()) return false;
        try { copy(source, target); return true; }
        catch (IOException e) {
            if (!target.delete()) e.addSuppressed(new IOException("补缺产生的未完整文件保留在 " + target.getName()));
            throw e;
        }
    }

    private static void copy(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            FileIntegrity.copy(in, out, MAX_WHEEL);
        }
    }

    private static String digest(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return FileIntegrity.copy(in, null, MAX_WHEEL).sha256; }
    }

    private static void directory(File dir) throws IOException {
        if (dir.getParentFile() != null && !dir.getCanonicalFile().equals(new File(dir.getParentFile().getCanonicalFile(), dir.getName())))
            throw new IOException("目录是符号链接，已保留且未写入：" + dir.getName());
        if (!dir.isDirectory() && present(dir)) throw new IOException("目录位置已有其他条目，已保留：" + dir.getName());
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建目录：" + dir.getName());
    }

    private static String relativeName(String name) throws IOException {
        String normalized = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (int i = 0; i < normalized.length(); i++) if (normalized.charAt(i) < 32 || normalized.charAt(i) == 127)
            throw new IOException("wheel 路径包含控制字符");
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains("\\") || normalized.contains(":"))
            throw new IOException("非法 wheel 路径");
        for (String part : normalized.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals(".."))
            throw new IOException("非法 wheel 路径");
        return normalized;
    }

    private static File child(File root, String name) throws IOException {
        String normalized = relativeName(name);
        File target = new File(root, normalized);
        if (!target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("wheel 路径越界");
        return target;
    }

    public static void removeStage(File dir, String boundary) throws IOException {
        String canonical = dir.getCanonicalPath();
        if (!canonical.equals(boundary) && !canonical.startsWith(boundary + File.separator)) throw new IOException("临时目录清理路径越界");
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) removeStage(child, boundary);
        if (dir.exists() && !dir.delete()) throw new IOException("临时目录清理失败：" + dir.getName());
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("wheel 操作已取消");
    }

    private static String inline(String value) {
        return value == null ? "" : value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
