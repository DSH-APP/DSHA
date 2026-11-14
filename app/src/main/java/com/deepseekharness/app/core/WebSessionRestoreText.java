package com.deepseekharness.app.core;

import com.deepseekharness.app.util.UiText;

/**
 * 恢复状态的展示映射。决定仍由 {@link WebSessionRecovery.Outcome} 的稳定 reason 码控制；
 * 这里只把码翻译成用户能采取动作的说明，不读取现场，也不授权启动或退休。
 */
public final class WebSessionRestoreText {
  private WebSessionRestoreText() {}

  /** 只读状态快照；reason 是稳定码，不包含凭据或异常正文。 */
  public record Status(String reason, int attempts, String notice) {}

  /** 用户在核对旧实例期间点击启动：本轮意图已记录，完成后最多继续一次。 */
  public static String deferred() {
    return UiText.choose(
        "正在核对原 Web 实例，完成后会自动继续这次启动",
        "Checking the previous Web instance; this start will continue automatically.");
  }

  /** 核对进行中的阶段说明；reason 只来自恢复器的稳定码。 */
  public static String progress(String reason, int attempt) {
    return switch (reason == null ? "" : reason) {
      case "AUTHENTICATING" ->
          UiText.choose("正在恢复原 Web 连接…", "Reconnecting to the existing Web instance…");
      case "AUTH_UNAVAILABLE" ->
          attempt > 0
              ? UiText.format(
                  UiText.choose(
                      "原 Web 仍存活，第 %s 次复核未通过，正在重试（最多 3 次）",
                      "The existing Web is still alive; check %s failed and is retrying (up to 3)."),
                  Math.min(attempt, 3))
              : UiText.choose(
                  "原 Web 仍存活，正在复核连接…", "The existing Web is alive; rechecking the link…");
      case "LAUNCHER_PENDING", "LAUNCHER_EXIT" ->
          UiText.choose(
              "原 Web 已退出，正在确认启动器退出…",
              "The previous Web exited; confirming its launcher has exited…");
      default -> UiText.choose("正在核对原 Web 实例…", "Checking the previous Web instance…");
    };
  }

  /**
   * 未确认状态的短标题（页面大标题用）：只描述当前结论，完整原因与可执行动作见
   * {@link #unresolved(String)}，由页面收进 ⓘ 弹层。
   */
  public static String state(String reason) {
    return switch (reason == null ? "" : reason) {
      case "IDENTITY_UNKNOWN" -> UiText.choose("原实例身份待确认", "Previous instance unconfirmed");
      case "AUTH_UNAVAILABLE" -> UiText.choose("原实例连接不可用", "Existing Web unreachable");
      case "LAUNCHER_PENDING" -> UiText.choose("启动器仍在退出", "Launcher still exiting");
      case "LAUNCHER_UNKNOWN" -> UiText.choose("启动器状态未知", "Launcher state unknown");
      case "RETIRE_UNAVAILABLE" -> UiText.choose("旧实例记录未清理", "Old instance record not cleared");
      case "INCOMPATIBLE" -> UiText.choose("原实例版本不兼容", "Instance version incompatible");
      case "STOP_IN_PROGRESS" -> UiText.choose("已停止，旧实例退出中", "Stopped; old instance exiting");
      case "USER_STOPPED_EXIT_UNCONFIRMED" ->
          UiText.choose("已停止，退出待确认", "Stopped; exit unconfirmed");
      case "RECORD_UNAVAILABLE" -> UiText.choose("实例记录不可读", "Instance record unreadable");
      case "INTERRUPTED" -> UiText.choose("恢复核对被中断", "Recovery check interrupted");
      default -> UiText.choose("原实例待确认", "Previous instance unconfirmed");
    };
  }

  /** 仍需用户处理的未确认状态；不同原因给出不同入口，不再共用一句“待确认”。 */
  public static String unresolved(String reason) {
    return switch (reason == null ? "" : reason) {
      case "IDENTITY_UNKNOWN" ->
          UiText.choose(
              "原 Web 实例身份无法确认；为避免重复实例，本次没有启动。可查看诊断了解核对阶段；点停止后，普通停止无法确认退出时会提示按身份核验强制结束。重启 App 会重新核对。",
              "The previous Web instance cannot be identified, so no new instance was started. Inspect diagnostics for the check stage; tap Stop — if a normal stop cannot confirm exit, a verified forced stop is offered. Restarting the app rechecks.");
      case "AUTH_UNAVAILABLE" ->
          UiText.choose(
              "原 Web 实例仍然存活，但连接暂不可用；自动复核已结束。返回本页会再核对一次，也可查看诊断。",
              "The previous Web is still alive but not reachable. Automatic checks ended; returning to this page checks once more. Diagnostics are available.");
      case "LAUNCHER_PENDING" ->
          UiText.choose(
              "原 Web 已退出，但启动器仍在退出；为避免重复实例，暂未启动。稍后重试。",
              "The previous Web exited but its launcher is still exiting. No new instance was started; retry shortly.");
      case "LAUNCHER_UNKNOWN" ->
          UiText.choose(
              "原 Web 已退出，但无法确认启动器状态；暂未启动新实例。可查看诊断，或正常停止后重试。",
              "The previous Web exited, but its launcher's state is unknown. No new instance was started. Inspect diagnostics or stop normally before retrying.");
      case "RETIRE_UNAVAILABLE" ->
          UiText.choose(
              "已确认原 Web 与启动器退出，但旧实例记录清理失败；记录已保留，稍后重试。",
              "The previous Web and launcher exited, but its record could not be retired. The record was kept; retry shortly.");
      case "INCOMPATIBLE" ->
          UiText.choose(
              "原实例来自不兼容的应用版本，未自动接回；确认旧进程退出后可启动。",
              "The existing instance belongs to an incompatible app version and was not reattached. Start after confirming its exit.");
      case "STOP_IN_PROGRESS" ->
          UiText.choose(
              "你已停止原 Web，旧实例正在退出；点「启动」会先等它退出再启动，点「停止」可重试确认。",
              "You stopped the previous Web and it is still exiting. Start waits for it to exit first; Stop rechecks the exit.");
      case "USER_STOPPED_EXIT_UNCONFIRMED" ->
          UiText.choose(
              "你已停止原 Web，旧实例退出尚未确认；点「启动」会先重新核验旧实例，确认退出后才启动，点「停止」可重试确认。",
              "You stopped the previous Web, but its exit is unconfirmed. Start re-verifies the old instance first; Stop rechecks the exit.");
      case "RECORD_UNAVAILABLE" ->
          UiText.choose(
              "原实例记录不可读，现场已保留；可查看诊断。",
              "The previous instance record is unreadable; its state was kept. Diagnostics are available.");
      case "INTERRUPTED" ->
          UiText.choose(
              "恢复核对被中断，未启动新实例；请重试。",
              "The recovery check was interrupted and no new instance was started. Retry.");
      default ->
          UiText.choose(
              "原 Web 实例状态待确认，请查看诊断或正常停止后重试",
              "The previous Web instance is unconfirmed. Inspect diagnostics or stop it normally before retrying.");
    };
  }

  public static String exited() {
    return UiText.choose(
        "已确认原 Web 实例退出，旧记录已清理；可以直接启动。",
        "The previous Web instance exited and its record was retired. You can start now.");
  }

  public static String restoredNoStart() {
    return UiText.choose(
        "已重新接入原 Web 实例，无需重新启动。", "Reconnected to the existing Web instance; no restart is needed.");
  }

  public static String continuingAfterExit() {
    return UiText.choose(
        "旧 Web 实例已确认退出，正在继续这次启动…", "The previous Web instance has exited; continuing this start…");
  }

  public static String continuingAfterNoRecord() {
    return UiText.choose(
        "没有待恢复的旧实例，正在继续这次启动…", "There is no previous instance to recover; continuing this start…");
  }

  /** 用户已停止、旧实例尚未确认退出：已请求的启动会先等旧实例退出。 */
  public static String continuingAfterStop() {
    return UiText.choose(
        "保留了你之前的停止请求；正在继续这次启动，会先等待旧实例退出…",
        "Your earlier stop is kept; continuing this start and waiting for the old instance to exit first…");
  }

  /** 停止哨兵带来的独立结论：不再显示成“版本不兼容”，只说明停止意图与退出核对状态。 */
  public static String stopped(String reason) {
    if ("STOP_IN_PROGRESS".equals(reason))
      return UiText.choose(
          "你已停止原 Web，旧实例正在退出；点「启动」会先等旧实例退出再启动，点「停止」可重试确认。",
          "You stopped the previous Web and it is still exiting. Start waits for it to exit first; Stop rechecks the exit.");
    return UiText.choose(
        "你已停止原 Web，旧实例退出尚未确认；点「启动」会先重新核验旧实例，确认退出后才启动，点「停止」可重试确认。",
        "You stopped the previous Web, but its exit is unconfirmed. Start re-verifies the old instance first; Stop rechecks the exit.");
  }

  public static String continuationBlocked() {
    return UiText.choose(
        "启动请求未能继续，请检查运行状态后重试。",
        "The start request could not continue. Check the run status and retry.");
  }
}
