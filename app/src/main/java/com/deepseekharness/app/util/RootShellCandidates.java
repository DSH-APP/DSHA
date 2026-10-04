package com.deepseekharness.app.util;

import java.util.List;
import java.util.function.Predicate;

/** 按固定优先级寻找 root 管理器；调用方仍须核对 root 所有权、权限与可执行性。 */
public final class RootShellCandidates {
  private static final List<String> PATHS =
      List.of(
          "/system/bin/su",
          "/system/xbin/su",
          "/sbin/su",
          "/debug_ramdisk/su",
          "/su/bin/su",
          "/data/adb/magisk/su",
          "/vendor/bin/su",
          "/data/adb/ksu/bin/su",
          "/data/adb/ap/bin/su");

  private RootShellCandidates() {}

  public static String firstVerified(Predicate<String> verified) {
    for (String candidate : PATHS) if (verified.test(candidate)) return candidate;
    return null;
  }
}
