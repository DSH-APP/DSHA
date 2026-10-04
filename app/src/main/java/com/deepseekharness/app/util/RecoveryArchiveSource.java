package com.deepseekharness.app.util;

/** 应急归档的包内存储位置；只允许复用相同摘要的签名资产，不读取正式运行目录。 */
public final class RecoveryArchiveSource {
  private RecoveryArchiveSource() {}

  public static String validate(String logical, String source, String lockedSha, String mappedSha) {
    String shared;
    if ("recovery-rootfs.bin".equals(logical)) shared = "offline-rootfs.bin";
    else if ("recovery-dsh-runtime.bin".equals(logical)) shared = "dsh-runtime.bin";
    else throw new IllegalArgumentException("RECOVERY_ARCHIVE_NAME");
    if (!logical.equals(source) && !shared.equals(source))
      throw new IllegalArgumentException("RECOVERY_ARCHIVE_SOURCE");
    if (lockedSha == null || !lockedSha.matches("[a-f0-9]{64}") || !lockedSha.equals(mappedSha))
      throw new IllegalArgumentException("RECOVERY_ARCHIVE_SOURCE_HASH");
    return source;
  }
}
