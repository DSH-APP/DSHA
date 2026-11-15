package com.deepseekharness.app.util;

/** 网页文件边界；文件名不能变成路径，长度声明不能越过本地容量限制。 */
public final class WebTransferPolicy {
  public static final long DOWNLOAD_LIMIT = 5_000_000_000L;

  /** 一次系统选择的累计上限；独立于单文件的十进制 5GB 边界。 */
  public static final long UPLOAD_LIMIT = 5L * 1024 * 1024 * 1024;

  public static final long UPLOAD_FILE_LIMIT = 5_000_000_000L;

  /** 暂存缓存所在卷须留下的余量；不代表后端附件发布的全部空间需求。 */
  public static final long UPLOAD_RESERVE = 1024L * 1024 * 1024;

  public static final int UPLOAD_COUNT = WebUploadSessionBudget.MAX_BATCH_FILES;

  private WebTransferPolicy() {}

  public static String mimeType(String value) {
    return value != null
            && value.length() <= 127
            && value.matches("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+")
        ? value
        : "application/octet-stream";
  }

  public static String fileName(String name) {
    String safe = name == null ? "" : name.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
    if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) return "download.bin";
    if (safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 240) return safe;
    int dot = safe.lastIndexOf('.');
    String suffix = dot > 0 ? safe.substring(dot) : "";
    if (suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64) suffix = "";
    String stem = safe.substring(0, safe.length() - suffix.length());
    while ((stem + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240)
      stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
    return stem + suffix;
  }

  public static void checkSize(long actual, long expected, long limit, boolean complete) {
    if (actual < 0 || actual > limit || expected > limit)
      throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("文件超过允许大小"));
    if (complete && expected >= 0 && actual != expected)
      throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("文件未下载完整，请重试"));
  }

  /** 写下一块之前检查当前可用空间；不重复扣除已经反映在可用量中的缓存。 */
  public static void checkUploadSpace(long nextWriteBytes, long currentUsableBytes) {
    if (nextWriteBytes < 0
        || currentUsableBytes < UPLOAD_RESERVE
        || nextWriteBytes > currentUsableBytes - UPLOAD_RESERVE)
      throw new IllegalArgumentException("UPLOAD_SPACE_INSUFFICIENT");
  }
}
