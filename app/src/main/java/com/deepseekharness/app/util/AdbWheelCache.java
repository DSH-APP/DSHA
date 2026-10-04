package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** wheel 缓存补缺与安装。缓存原文件永不覆盖；无 Android 依赖，便于恢复回归测试。 */
public final class AdbWheelCache {
  private AdbWheelCache() {}

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
      return com.deepseekharness.app.util.UiText.format(
          "WHEELS_CACHE_READY: 补齐 %d 个，原版已在位 %d 个，保留修改版 %d 个，保留额外 wheel %d 个%s%s",
          added,
          same,
          modified,
          extra,
          names.isEmpty() ? "" : "\nWHEELS_PRESERVED: " + String.join("、", names),
          archiveDifferent
              ? com.deepseekharness.app.util.UiText.text(
                  "\nWHEELS_ARCHIVE_PRESERVED: 现有归档与 APK 不同（可能修改或损坏），原样保留；本次补缺从 APK 临时副本读取")
              : "");
    }
  }

  /** APK 已在独立目录解开；现有同名条目（含坏文件、链接、目录）不会被替换。 */
  public static Merge fillMissing(
      BackupFileSystem fs, File bundled, File cache, File apkArchive, File cachedArchive)
      throws IOException {
    List<File> originals = wheels(bundled);
    validate(fs, bundled); // 先校验 APK 副本，再发布任何缓存。
    directory(fs, cache);
    Set<String> expected = new HashSet<>();
    Merge report = new Merge();
    for (File original : originals) {
      checkCancelled();
      expected.add(original.getName());
      File target = new File(cache, original.getName());
      if (present(fs, target)) {
        if (regular(fs, target)
            && target.length() <= MAX_WHEEL
            && digest(fs, original).equals(digest(fs, target))) report.same++;
        else {
          report.modified++;
          report.preserved.add(original.getName());
        }
      } else if (copyMissing(fs, original, target)) report.added++;
      else {
        report.modified++;
        report.preserved.add(original.getName());
      }
    }
    File[] all = cache.listFiles();
    if (all == null)
      throw new IOException(com.deepseekharness.app.util.UiText.text("无法列出 wheel 缓存目录"));
    for (File f : all)
      if (f.getName().endsWith(".whl") && !expected.contains(f.getName())) report.extra++;
    if (present(fs, cachedArchive)) {
      report.archiveDifferent =
          !regular(fs, cachedArchive)
              || cachedArchive.length() > MAX_WHEEL
              || !digest(fs, apkArchive).equals(digest(fs, cachedArchive));
    } else report.archiveAdded = copyMissing(fs, apkArchive, cachedArchive);
    return report;
  }

  /** 校验所有源文件的 ZIP 路径、大小和 CRC；一项损坏都不能被算作安装成功。 */
  public static void validate(BackupFileSystem fs, File cache) throws IOException {
    long[] totals = {0, 0};
    for (File wheel : wheels(cache)) {
      try {
        readWheel(fs, wheel, null, null, totals);
      } catch (IOException e) {
        throw damaged(wheel, e);
      }
    }
  }

  /** 全部 wheel 先解入临时目录；损坏或文件冲突时不改现有 Python 安装。 */
  public static String install(BackupFileSystem fs, File cache, File site, File stagingParent)
      throws IOException {
    List<File> source = wheels(cache);
    directory(fs, stagingParent);
    File stage = new File(stagingParent, ".adb-wheel-install-" + UUID.randomUUID());
    fs.directory(stage);
    final String boundary = stage.getCanonicalPath();
    Map<String, String> owners = new HashMap<>();
    long[] totals = {0, 0};
    try {
      for (File wheel : source) {
        try {
          readWheel(fs, wheel, stage, owners, totals);
        } catch (IOException e) {
          throw damaged(wheel, e);
        }
      }
      directory(fs, site);
      // 所有源缓存仍原样在位；安装只从已验证的临时目录发布。
      for (String name : new TreeSet<>(owners.keySet())) {
        File target = child(site, name);
        directory(fs, target.getParentFile());
        File tmp = fs.child(target.getParentFile(), ".adb-wheel-" + UUID.randomUUID() + ".tmp");
        BackupFileSystem.Node[] owned = {null};
        try {
          copy(fs, new File(stage, name), tmp, owned);
          publish(fs, tmp, target, name);
        } finally {
          cleanupCopy(fs, tmp, owned[0]);
        }
      }
      return com.deepseekharness.app.util.UiText.format(
          "WHEELS_JAVA_EXTRACTED: 已校验并安装 %d 个 wheel，源缓存全部保留", source.size());
    } finally {
      removeStage(stage, boundary);
    }
  }

  private static void readWheel(
      BackupFileSystem fs, File wheel, File stage, Map<String, String> owners, long[] totals)
      throws IOException {
    if (!regular(fs, wheel))
      throw new IOException(
          com.deepseekharness.app.util.UiText.text("不是普通 wheel 文件（链接/目录/特殊条目已保留）"));
    var source = fs.stat(wheel);
    if (source.size > MAX_WHEEL)
      throw new IOException(com.deepseekharness.app.util.UiText.text("wheel 大于 128 MiB"));
    try (ZipFile zip = new ZipFile(wheel)) {
      if (zip.size() == 0)
        throw new IOException(com.deepseekharness.app.util.UiText.text("空 wheel"));
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        checkCancelled();
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (++totals[1] > MAX_ENTRIES)
          throw new IOException(com.deepseekharness.app.util.UiText.text("wheel 条目过多"));
        relativeName(name);
        File out = stage == null ? null : child(stage, name);
        if (entry.isDirectory()) continue;
        if (entry.getSize() < 0 || entry.getSize() > MAX_WHEEL)
          throw new IOException(com.deepseekharness.app.util.UiText.format("文件大小无效：%s", name));
        boolean duplicate = stage != null && owners.containsKey(name);
        if (stage != null && present(fs, out) && !duplicate)
          throw new IOException(com.deepseekharness.app.util.UiText.format("条目路径冲突：%s", name));
        if (stage != null && !duplicate) directory(fs, out.getParentFile());
        CRC32 crc = new CRC32();
        long bytes = 0;
        java.security.MessageDigest sha;
        try {
          sha = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
          throw new IOException(e);
        }
        try (InputStream in = zip.getInputStream(entry);
            OutputStream target =
                stage == null || duplicate
                    ? new ByteArrayOutputStream(0) {
                      @Override
                      public void write(byte[] b, int off, int len) {}

                      @Override
                      public void write(int b) {}
                    }
                    : fs.create(out)) {
          byte[] buffer = new byte[65536];
          int n;
          while ((n = in.read(buffer)) != -1) {
            checkCancelled();
            bytes += n;
            totals[0] += n;
            if (bytes > MAX_WHEEL || totals[0] > MAX_EXPANDED)
              throw new IOException(com.deepseekharness.app.util.UiText.text("wheel 解包大小超过上限"));
            crc.update(buffer, 0, n);
            sha.update(buffer, 0, n);
            target.write(buffer, 0, n);
          }
        }
        if (bytes != entry.getSize() || crc.getValue() != entry.getCrc())
          throw new IOException(com.deepseekharness.app.util.UiText.format("CRC/长度校验失败：%s", name));
        if (duplicate) {
          StringBuilder hash = new StringBuilder();
          for (byte b : sha.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
          if (!hash.toString().equals(digest(fs, out)))
            throw new IOException(
                com.deepseekharness.app.util.UiText.format(
                    "与 %s 的文件冲突：%s；请选择需要使用的版本", owners.get(name), name));
        } else if (stage != null) owners.put(name, wheel.getName());
      }
    }
    if (!source.same(fs.stat(wheel))) throw new IOException("WHEEL_SOURCE_CHANGED");
  }

  private static IOException damaged(File wheel, IOException cause) {
    return new IOException(
        com.deepseekharness.app.util.UiText.format(
            "WHEELS_CACHE_INVALID: %s：%s。源缓存已保留，未用 APK 覆盖。请先备份该文件并移出 wheels 目录，再重试补缺；自定义 wheel 请提供有效版本。",
            inline(wheel.getName()), inline(cause.getMessage())),
        cause);
  }

  /** Use the shared descriptor boundary and retain the previous file before publication. */
  private static void publish(BackupFileSystem fs, File tmp, File target, String name)
      throws IOException {
    var before = fs.stat(target);
    if (!before.type.equals("MISSING") && !before.type.equals("FILE"))
      throw new IOException(com.deepseekharness.app.util.UiText.format("无法发布 Python 文件：%s", name));
    if (before.type.equals("FILE")) fs.mode(tmp, before.mode);
    File previous =
        fs.child(target.getParentFile(), ".adb-wheel-previous-" + UUID.randomUUID() + ".tmp");
    boolean retained = false, published = false;
    try {
      if (before.type.equals("FILE")) {
        fs.move(target, previous);
        retained = true;
        fs.syncDirectory(target.getParentFile());
        if (!before.same(fs.stat(previous))) throw new IOException("WHEEL_TARGET_CHANGED");
      }
      fs.move(tmp, target);
      published = true;
      fs.syncDirectory(target.getParentFile());
      if (retained) {
        if (!before.same(fs.stat(previous))) throw new IOException("WHEEL_RETAINED_CHANGED");
        fs.delete(previous);
        fs.syncDirectory(target.getParentFile());
      }
    } catch (IOException failure) {
      if (retained && !published && fs.stat(target).type.equals("MISSING"))
        try {
          fs.move(previous, target);
          fs.syncDirectory(target.getParentFile());
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      throw failure;
    }
  }

  private static List<File> wheels(File dir) throws IOException {
    if (!dir.isDirectory())
      throw new IOException(com.deepseekharness.app.util.UiText.text("wheel 目录不存在或不是目录"));
    File[] files = dir.listFiles((d, n) -> n.endsWith(".whl"));
    if (files == null || files.length == 0)
      throw new IOException(com.deepseekharness.app.util.UiText.text("wheel 目录为空"));
    Arrays.sort(files, Comparator.comparing(File::getName));
    return Arrays.asList(files);
  }

  /** list() 也能看见 dangling symlink，不能仅用 exists() 判断是否可覆盖。 */
  private static boolean present(BackupFileSystem fs, File file) throws IOException {
    return !fs.stat(file).type.equals("MISSING");
  }

  private static boolean regular(BackupFileSystem fs, File file) throws IOException {
    return fs.stat(file).type.equals("FILE")
        && file.getCanonicalFile()
            .equals(new File(file.getParentFile().getCanonicalFile(), file.getName()));
  }

  private static boolean copyMissing(BackupFileSystem fs, File source, File target)
      throws IOException {
    directory(fs, target.getParentFile());
    if (present(fs, target)) return false;
    File part = fs.child(target.getParentFile(), ".adb-wheel-copy-" + UUID.randomUUID() + ".part");
    BackupFileSystem.Node[] owned = {null};
    try {
      copy(fs, source, part, owned);
      if (present(fs, target)) return false;
      fs.move(part, target);
      fs.syncDirectory(target.getParentFile());
      return true;
    } finally {
      cleanupCopy(fs, part, owned[0]);
    }
  }

  private static void copy(
      BackupFileSystem fs, File source, File target, BackupFileSystem.Node[] owned)
      throws IOException {
    var before = fs.stat(source);
    try (InputStream in = fs.read(source, before)) {
      OutputStream opened = fs.create(target);
      try (OutputStream out = opened) {
        owned[0] = fs.stat(target);
        FileIntegrity.copy(in, out, MAX_WHEEL);
      }
    }
  }

  private static void cleanupCopy(BackupFileSystem fs, File path, BackupFileSystem.Node owned)
      throws IOException {
    var current = fs.stat(path);
    if (owned != null
        && current.type.equals("FILE")
        && owned.key.equals(current.key)
        && owned.device == current.device) fs.delete(path);
  }

  private static String digest(BackupFileSystem fs, File file) throws IOException {
    try (InputStream in = fs.read(file, fs.stat(file))) {
      return FileIntegrity.copy(in, null, MAX_WHEEL).sha256;
    }
  }

  private static void directory(BackupFileSystem fs, File dir) throws IOException {
    if (dir.getParentFile() != null
        && !dir.getCanonicalFile()
            .equals(new File(dir.getParentFile().getCanonicalFile(), dir.getName())))
      throw new IOException(
          com.deepseekharness.app.util.UiText.format("目录是符号链接，已保留且未写入：%s", dir.getName()));
    if (!fs.stat(dir).type.equals("DIRECTORY") && present(fs, dir))
      throw new IOException(
          com.deepseekharness.app.util.UiText.format("目录位置已有其他条目，已保留：%s", dir.getName()));
    if (fs.stat(dir).type.equals("MISSING")) {
      directory(fs, dir.getParentFile());
      fs.directory(dir);
    }
  }

  private static String relativeName(String name) throws IOException {
    String normalized = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    for (int i = 0; i < normalized.length(); i++)
      if (normalized.charAt(i) < 32 || normalized.charAt(i) == 127)
        throw new IOException(com.deepseekharness.app.util.UiText.text("wheel 路径包含控制字符"));
    if (normalized.isEmpty()
        || normalized.startsWith("/")
        || normalized.contains("\\")
        || normalized.contains(":"))
      throw new IOException(com.deepseekharness.app.util.UiText.text("非法 wheel 路径"));
    for (String part : normalized.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw new IOException(com.deepseekharness.app.util.UiText.text("非法 wheel 路径"));
    return normalized;
  }

  private static File child(File root, String name) throws IOException {
    String normalized = relativeName(name);
    File target = new File(root, normalized);
    if (!target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
      throw new IOException(com.deepseekharness.app.util.UiText.text("wheel 路径越界"));
    return target;
  }

  public static void removeStage(File dir, String boundary) throws IOException {
    String canonical = dir.getCanonicalPath();
    if (!canonical.equals(boundary) && !canonical.startsWith(boundary + File.separator))
      throw new IOException(com.deepseekharness.app.util.UiText.text("临时目录清理路径越界"));
    File[] children = dir.listFiles();
    if (children != null) for (File child : children) removeStage(child, boundary);
    if (dir.exists() && !dir.delete())
      throw new IOException(
          com.deepseekharness.app.util.UiText.format("临时目录清理失败：%s", dir.getName()));
  }

  private static void checkCancelled() throws IOException {
    if (Thread.currentThread().isInterrupted())
      throw new java.io.InterruptedIOException(
          com.deepseekharness.app.util.UiText.text("wheel 操作已取消"));
  }

  private static String inline(String value) {
    return value == null
        ? ""
        : value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
  }
}
