package com.deepseekharness.app.util;

/** 平台读数与退出决策分开；不可读不是退出，端口状态不参与决策。 */
public final class VirtualScreenExitProofPolicy {
  private VirtualScreenExitProofPolicy() {}

  public static VirtualScreenCoreIdentity.Exit classify(
      VirtualScreenCoreIdentity expected,
      String boot,
      ProcStat process,
      Integer ownerUid,
      boolean kernelEsrch) {
    if (expected == null || boot == null || !Ids.uuid(boot))
      return VirtualScreenCoreIdentity.Exit.UNKNOWN;
    if (!expected.boot.equals(boot) || kernelEsrch) return VirtualScreenCoreIdentity.Exit.EXITED;
    if (process == null || ownerUid == null || process.pid != expected.pid)
      return VirtualScreenCoreIdentity.Exit.UNKNOWN;
    return process.started != expected.started || ownerUid != expected.uid || process.exited()
        ? VirtualScreenCoreIdentity.Exit.EXITED
        : VirtualScreenCoreIdentity.Exit.ALIVE;
  }
}
