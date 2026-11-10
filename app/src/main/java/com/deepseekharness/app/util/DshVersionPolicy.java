package com.deepseekharness.app.util;

import java.util.List;
import java.util.Map;

/**
 * 只比较已核实的 DSH 版本和数据写入代次；未知版本不按字符串排序，也不自动修复。
 */
public final class DshVersionPolicy {
  public enum Status {
    MISSING,
    CURRENT,
    INSTALLED_NEWER,
    INSTALLED_OLDER,
    UNKNOWN
  }

  private static final Map<String, Integer> KNOWN_RANKS =
      Map.of(
          "0.2.1-rc.3", 1,
          "0.2.1-rc.5", 2,
          "0.2.2-rc.1", 3);

  private DshVersionPolicy() {}

  /**
   * 比较已安装描述与 APK 候选。只要个人数据写入版本明确高于候选，就返回
   * {@link Status#INSTALLED_NEWER}，即使候选声明了前向读取能力也不会自动覆盖；其余缺少完整证据的情况都返回
   * {@link Status#UNKNOWN}。
   */
  public static Status assess(
      String installedDsh,
      String installedDataWrite,
      String expectedDsh,
      List<String> expectedDataRead) {
    if (installedDsh == null || installedDsh.isEmpty()) return Status.MISSING;
    Integer installedRank = KNOWN_RANKS.get(installedDsh);
    Integer expectedRank = KNOWN_RANKS.get(expectedDsh);
    if (installedRank == null || expectedRank == null) return Status.UNKNOWN;
    String writtenDsh = dataWriteVersion(installedDataWrite);
    Integer writtenRank = KNOWN_RANKS.get(writtenDsh);
    if (writtenRank == null) return Status.UNKNOWN;
    boolean readable = expectedDataRead != null && expectedDataRead.contains(installedDataWrite);
    // Even a candidate that claims a forward reader must not silently replace a tree whose
    // personal data was written by a newer DSH. The user may explicitly choose that candidate.
    if (writtenRank > expectedRank) return Status.INSTALLED_NEWER;
    if (!readable) return Status.UNKNOWN;
    // dataWrite is the authoritative format evidence; dshVersion alone may be stale or
    // inconsistent after a partially completed managed-runtime transaction.
    if (writtenRank < expectedRank) return Status.INSTALLED_OLDER;
    return Status.CURRENT;
  }

  public static boolean isInstalledNewer(
      String installedDsh,
      String installedDataWrite,
      String expectedDsh,
      List<String> expectedDataRead) {
    return assess(installedDsh, installedDataWrite, expectedDsh, expectedDataRead)
        == Status.INSTALLED_NEWER;
  }

  static String dataWriteVersion(String dataWrite) {
    if (dataWrite == null || !dataWrite.startsWith("dsh-") || dataWrite.length() <= 4) return "";
    String version = dataWrite.substring(4);
    return KNOWN_RANKS.containsKey(version) ? version : "";
  }
}
