package com.deepseekharness.app.util;

/** 网页文件边界；文件名不能变成路径，长度声明不能越过本地容量限制。 */
public final class WebTransferPolicy {
  public static final long DOWNLOAD_LIMIT = 2L * 1024 * 1024 * 1024;

  /**
   * 一次系统文件选择的原文上限（5 GiB）。
   *
   * <p>这里是<strong>暂存副本</strong>的上限，不是网页内存的上限：{@code WebUploads} 以 64 KiB 分块把用户选中的
   * 内容流式写进应用私有缓存，全程不把整份文件读进内存，所以上限只由磁盘决定。原先的 256 MiB 是「一次挑选
   * 不许超过 256 MiB」的人为天花板，用户传单个大文件时直接撞上。
   *
   * <p>放宽以后仍然有界：{@link #checkUploadSpace} 要求卷上始终留出 {@link #UPLOAD_RESERVE}，实际能传多大由
   * 可用空间决定；单次挑选还受 {@link #UPLOAD_COUNT} 个条目约束，单个页面会话另有
   * {@link WebUploadSessionBudget#MAX_SESSION_BYTES}。
   */
  public static final long UPLOAD_LIMIT = 5L * 1024 * 1024 * 1024;

  /**
   * 上传暂存期间在卷上保留的空间（1 GiB）。低于它直接拒绝本次上传，而不是先写满再失败。
   *
   * <p>本机上传缓存、Ubuntu rootfs 与宿主附件都在同一卷上，写满会连带影响运行中的 dsh；留出固定余量比
   * 「传到一半 ENOSPC」可诊断得多。宿主随后把同一份字节再存一份附件（约再占 1×）不在本类可观测范围内，
   * 属于已知限制。
   */
  public static final long UPLOAD_RESERVE = 1024L * 1024 * 1024;

  public static final int UPLOAD_COUNT = WebUploadSessionBudget.MAX_BATCH_FILES;

  private WebTransferPolicy() {}

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

  /**
   * 上传暂存到第 {@code copied} 字节时，卷上是否仍留有 {@link #UPLOAD_RESERVE} 可用空间。
   *
   * <p>判据只依赖「开始暂存时读到的可用字节」与「已写入字节」，因此可以在没有 Android 与真实文件系统的
   * 情况下单测。{@code usableAtStart} 把 {@code File.getUsableSpace()} 的 0/负值（读取失败）当作没有空间，
   * 失败封闭而不是放行。
   *
   * @param copied 本次挑选已写入暂存区的原文字节数。
   * @param usableAtStart 开始暂存时暂存目录所在卷的可用字节数。
   * @throws IllegalArgumentException 已写入字节加上保留余量超出可用空间。
   */
  public static void checkUploadSpace(long copied, long usableAtStart) {
    if (copied < 0 || usableAtStart <= 0 || copied + UPLOAD_RESERVE > usableAtStart)
      throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("存储空间不足"));
  }
}
