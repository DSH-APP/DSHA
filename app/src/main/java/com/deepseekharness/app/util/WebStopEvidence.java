package com.deepseekharness.app.util;

/** 停止屏障只接受明确退出或编号已复用的证据；不可读不等于不存在。 */
public final class WebStopEvidence {
  private WebStopEvidence() {}

  public enum Kind {
    GONE,
    WEB,
    OTHER,
    DENIED
  }

  /** 无记录的全局候选只有 uid 已证实属于本应用时才进入严格进程核验。 */
  public static boolean scanCandidate(Integer owner, int appUid) {
    return owner != null && owner == appUid;
  }

  public static boolean mayRetire(
      Kind kind, boolean differentUid, String saved, WebPidIdentity current) {
    if (kind == Kind.GONE || differentUid) return true;
    return current != null
        && saved != null
        && saved.matches(current.pid + " [1-9][0-9]*")
        && !current.matches(saved);
  }

  /**
   * 仅主 Web 的可信旧记录可根据内核 signal-0 EPERM 退休：同 UID、同应用域启动的
   * Web 可接受来自宿主的 signal-0；/proc 因 hidepid 不可读时，这代表旧 PID 已
   * 指向不可由本应用发信号的进程。普通 /proc EACCES 及同 UID 不可读仍不放行。
   */
  public static boolean mayRetireUnsignalableRecord(
      Kind kind, boolean signalProbeForbidden, int pid, String saved) {
    return kind == Kind.DENIED
        && signalProbeForbidden
        && pid > 1
        && saved != null
        && saved.matches(pid + " [1-9][0-9]*");
  }

  /** 已能核对目标 UID 时按 UID 处理；这个例外只属于被 hidepid 隐藏的编号。 */
  public static boolean hiddenUnsignalableCandidate(boolean signalProbeEperm, Integer owner) {
    return signalProbeEperm && owner == null;
  }

  /** 全局扫描允许跳过已读明的无关命令；本 UID 或归属未知的 DENIED 保持屏障。 */
  public static boolean scanUnconfirmed(Kind kind, boolean differentUid) {
    return !differentUid && (kind == Kind.WEB || kind == Kind.DENIED);
  }
}
