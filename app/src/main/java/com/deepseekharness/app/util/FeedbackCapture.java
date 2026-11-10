package com.deepseekharness.app.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试包「反馈与诊断」导出包的有界收集器（纯逻辑）。
 *
 * <p>只接受显式登记的安全归档名；单条 ≤ {@value #MAX_ENTRY_BYTES} 字节、总量 ≤
 * {@value #MAX_TOTAL_BYTES} 字节、最多 {@value #MAX_ENTRIES} 条。不存在、越界、软链、重名与读取
 * 失败只记录来源与稳定原因，不阻断整包导出。所有文本进入收集器时统一按
 * {@link SensitiveData#redact(String)} 脱敏；不采集账号、设备标识或全量 logcat。
 *
 * <p>manifest 由 {@code backup/DiagnosticExportArchive} 生成（逐条 sha256 与 byte 数）；这里只
 * 负责把采集来源、边界和失败原因整理成同一份状态，供 manifest 的 {@code capture} 段引用。
 */
public final class FeedbackCapture {
  public static final int MAX_ENTRIES = 48;
  public static final int MAX_ENTRY_BYTES = 256 * 1024;
  public static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;

  private static final java.util.regex.Pattern NAME =
      java.util.regex.Pattern.compile("[A-Za-z0-9_./-]+");

  public enum Outcome {
    INCLUDED,
    INCLUDED_TRUNCATED,
    SKIPPED_NAME,
    SKIPPED_DUPLICATE,
    SKIPPED_MISSING,
    SKIPPED_NOT_FILE,
    SKIPPED_SYMLINK,
    SKIPPED_OUTSIDE_BASE,
    SKIPPED_ENTRY_LIMIT,
    SKIPPED_TOTAL_LIMIT,
    FAILED_READ
  }

  /** 一条采集来源的结果；{@link #detail} 只允许稳定原因或脱敏后的错误摘要。 */
  public static final class Attempt {
    public final String source, archiveName, detail;
    public final Outcome outcome;
    public final long bytes;

    Attempt(String source, String archiveName, Outcome outcome, long bytes, String detail) {
      this.source = source == null ? "" : source;
      this.archiveName = archiveName == null ? "" : archiveName;
      this.outcome = outcome;
      this.bytes = bytes;
      this.detail = detail == null ? "" : detail;
    }

    Map<String, Object> map() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("source", SensitiveData.redact(source));
      value.put("archiveName", SensitiveData.redact(archiveName));
      value.put("outcome", outcome.name());
      value.put("bytes", bytes);
      if (!detail.isEmpty()) value.put("detail", SensitiveData.redact(detail));
      return value;
    }
  }

  private final Map<String, byte[]> entries = new LinkedHashMap<>();
  private final List<Attempt> attempts = new ArrayList<>();
  private long totalBytes;

  public Outcome addText(String source, String archiveName, String text) {
    String safe = SensitiveData.redact(text == null ? "" : text);
    return add(source, archiveName, safe.getBytes(StandardCharsets.UTF_8), false);
  }

  /** 已是结构化字节的内容（如 device.json）；仍按同一套名称/条数/总量边界接收。 */
  public Outcome addBytes(String source, String archiveName, byte[] bytes) {
    if (bytes == null) return record(source, archiveName, Outcome.SKIPPED_MISSING, 0, "");
    return add(source, archiveName, bytes, false);
  }

  /**
   * 读取文件尾部（有界按行）并脱敏后加入导出包。{@code base} 非空时拒绝解析到该目录之外的路径；
   * 软链一律跳过，避免导出可重定向的宿主文件。超过 {@code maxBytes} 的部分按
   * {@link TextLogTail} 规则裁掉并在状态里标记 truncated。
   */
  public Outcome addFile(String source, String archiveName, File file, File base, int maxBytes) {
    // 省略标记与脱敏替换可能让正文略长于读入上限；预留 1 KB，保证加入的条目仍 ≤ MAX_ENTRY_BYTES。
    int limit =
        Math.min(
            maxBytes <= 0 || maxBytes > MAX_ENTRY_BYTES ? MAX_ENTRY_BYTES : maxBytes,
            MAX_ENTRY_BYTES - 1024);
    if (!validName(archiveName)) return record(source, archiveName, Outcome.SKIPPED_NAME, 0, "");
    if (entries.containsKey(archiveName))
      return record(source, archiveName, Outcome.SKIPPED_DUPLICATE, 0, "");
    if (file == null) return record(source, archiveName, Outcome.SKIPPED_MISSING, 0, "");
    try {
      if (base != null) {
        File canonicalBase = base.getCanonicalFile();
        File canonical = file.getCanonicalFile();
        if (!canonical.equals(canonicalBase)
            && !canonical.toPath().startsWith(canonicalBase.toPath()))
          return record(source, archiveName, Outcome.SKIPPED_OUTSIDE_BASE, 0, "");
      }
      // minSdk 30 已覆盖 API 26 的 java.nio.file；这里只判断文件本身是否为链接，不解析父目录，
      // 避免 /data/data → /data/user/0 这类宿主别名把正常文件误判成链接。
      if (java.nio.file.Files.isSymbolicLink(file.toPath()))
        return record(source, archiveName, Outcome.SKIPPED_SYMLINK, 0, "");
      if (!file.exists()) return record(source, archiveName, Outcome.SKIPPED_MISSING, 0, "");
      if (!file.isFile()) return record(source, archiveName, Outcome.SKIPPED_NOT_FILE, 0, "");
      boolean truncated = file.length() > limit;
      String text = TextLogTail.read(file, limit);
      byte[] bytes = SensitiveData.redact(text).getBytes(StandardCharsets.UTF_8);
      return add(source, archiveName, bytes, truncated);
    } catch (IOException | RuntimeException error) {
      return record(
          source,
          archiveName,
          Outcome.FAILED_READ,
          0,
          SensitiveData.redact(String.valueOf(error.getMessage())));
    }
  }

  /** 记录一条采集失败（例如 logcat 无权限）；导出继续，原因进入 manifest。 */
  public void fail(String source, String detail) {
    record(source, "", Outcome.FAILED_READ, 0, detail);
  }

  public Map<String, byte[]> entries() {
    return java.util.Collections.unmodifiableMap(entries);
  }

  public List<Attempt> attempts() {
    return java.util.Collections.unmodifiableList(attempts);
  }

  public long totalBytes() {
    return totalBytes;
  }

  /** manifest 的 capture 段：边界、来源结果与脱敏口径；不含业务正文。 */
  public Map<String, Object> status(long capturedAtMillis) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", 1L);
    value.put("kind", "DSHA_TEST_PACKAGE_FEEDBACK");
    value.put("automaticUpload", false);
    value.put("testPackageOnly", true);
    value.put("capturedAtMillis", capturedAtMillis);
    Map<String, Object> limits = new LinkedHashMap<>();
    limits.put("maxEntries", (long) MAX_ENTRIES);
    limits.put("maxEntryBytes", (long) MAX_ENTRY_BYTES);
    limits.put("maxTotalBytes", (long) MAX_TOTAL_BYTES);
    value.put("limits", limits);
    value.put("includedEntryCount", (long) entries.size());
    value.put("includedBytes", totalBytes);
    value.put("redaction", "SensitiveData.RULES");
    List<Map<String, Object>> rows = new ArrayList<>(attempts.size());
    for (Attempt attempt : attempts) rows.add(attempt.map());
    value.put("sources", rows);
    return value;
  }

  private Outcome add(String source, String archiveName, byte[] bytes, boolean truncated) {
    if (!validName(archiveName)) return record(source, archiveName, Outcome.SKIPPED_NAME, 0, "");
    if (entries.containsKey(archiveName))
      return record(source, archiveName, Outcome.SKIPPED_DUPLICATE, 0, "");
    if (entries.size() >= MAX_ENTRIES || bytes.length > MAX_ENTRY_BYTES)
      return record(source, archiveName, Outcome.SKIPPED_ENTRY_LIMIT, 0, "");
    if (totalBytes + bytes.length > MAX_TOTAL_BYTES)
      return record(source, archiveName, Outcome.SKIPPED_TOTAL_LIMIT, 0, "");
    byte[] snapshot = bytes.clone();
    entries.put(archiveName, snapshot);
    totalBytes += snapshot.length;
    return record(
        source,
        archiveName,
        truncated ? Outcome.INCLUDED_TRUNCATED : Outcome.INCLUDED,
        snapshot.length,
        "");
  }

  private Outcome record(
      String source, String archiveName, Outcome outcome, long bytes, String detail) {
    attempts.add(new Attempt(source, archiveName, outcome, bytes, detail));
    return outcome;
  }

  private static boolean validName(String name) {
    return name != null
        && !name.isEmpty()
        && name.length() <= 100
        && !name.equals("manifest.json")
        && !name.startsWith("/")
        && !name.endsWith("/")
        && name.indexOf("..") < 0
        && NAME.matcher(name).matches();
  }
}
