package com.deepseekharness.app.util;

/** 只有本轮真实运行时、工具与鉴权均通过，进程信息暂不可读才允许应急只读入口。 */
public final class RuntimeInstanceReadiness {
  private RuntimeInstanceReadiness() {}

  public enum Identity {
    VERIFIED,
    UNAVAILABLE,
    INVALID
  }

  public enum Result {
    BLOCKED,
    READY,
    READY_READ_ONLY
  }

  public static Result decide(
      boolean assetsVerified,
      boolean officialAuthReady,
      boolean controlledToolsReady,
      boolean ownedProcessLive,
      Identity identity) {
    if (!assetsVerified
        || !officialAuthReady
        || !controlledToolsReady
        || !ownedProcessLive
        || identity == null) return Result.BLOCKED;
    if (identity == Identity.VERIFIED) return Result.READY;
    if (identity == Identity.UNAVAILABLE) return Result.READY_READ_ONLY;
    return Result.BLOCKED;
  }
}
