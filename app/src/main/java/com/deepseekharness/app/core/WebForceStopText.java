package com.deepseekharness.app.core;

import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.WebStopBudget;
import com.deepseekharness.app.util.WebStopDiagnostic;
import java.util.ArrayList;
import java.util.List;

/**
 * 强停候选准备失败时的展示适配：把不可强停变成可读的三要素 + 已核验部分 + 重试入口。
 *
 * <p>这里只把已有、已脱敏的事实组织成文案；不改变任何信号条件——仍只对通过内核出生身份核验的
 * PID 发信号，证据不足时只展示、不降级为按名称 / 端口 / 裸 PID 结束进程。
 */
public final class WebForceStopText {
  private WebForceStopText() {}

  /** 强停候选无法准备时的完整说明（对话框正文）。 */
  public static String unavailable(WebStopDiagnostic diagnostic) {
    WebStopDiagnostic safe =
        diagnostic == null
            ? WebStopDiagnostic.of(WebStopDiagnostic.Code.FORCE_EVIDENCE_UNCONFIRMED)
            : diagnostic;
    return UiText.format(
        UiText.choose(
            "停止未完成：本次无法准备可核验的强制结束候选。\n发生了什么：普通停止已请求，但强制结束所需的 PID / 出生身份 / 命令行证据无法完整核对。\n已核验的部分：%s\n无法准备候选的原因：%s\n影响：没有发送任何强制结束信号，旧 Web 可能仍在退出或身份无法核验；原环境保留。\n下一步：约 %s 秒后点「停止」重试；仍失败请在「恢复选项」查看诊断。",
            "Stop did not complete: no identity-verified force-stop candidate was available.\nWhat happened: a normal stop was requested, but the PID / birth identity / command evidence needed for a verified force stop could not be fully confirmed.\nVerified so far: %s\nWhy no candidate: %s\nImpact: no force-stop signal was sent; the old Web may still be exiting or unverifiable, and the runtime was kept.\nNext: wait about %s seconds and tap Stop to retry; if it still fails, open Recovery options for diagnostics."),
        evidence(safe),
        reason(safe),
        retrySeconds());
  }

  /** 没有取得候选（例如覆盖更新弃旧版时旧实例可能已经退出）。 */
  public static String noCandidate() {
    return UiText.choose(
        "停止未完成：本次没有可强制结束的旧实例。\n发生了什么：强制结束需要一个由 PID、出生身份和命令行完整核验的旧实例，本次没有取得候选。\n影响：没有发送任何信号；旧实例可能已经退出，也可能因证据不足无法识别，原环境保留。\n下一步：点「重新核验」或「停止」重试；仍失败请在「恢复选项」查看诊断。",
        "Stop did not complete: no old instance could be force-stopped.\nWhat happened: a force stop needs an old instance fully verified by PID, birth identity, and command line; no candidate was obtained.\nImpact: no signal was sent. The old instance may have exited, or may not be identifiable from the available evidence; the runtime was kept.\nNext: tap Re-verify or Stop to retry; if it still fails, open Recovery options for diagnostics.");
  }

  /** 刚核验过候选证据时，短状态提示（不重复准备候选、不重复记日志）。 */
  public static String waitBeforeRetry() {
    return UiText.format(
        UiText.choose(
            "刚核验过强停证据，请稍候约 %s 秒再试。",
            "Force-stop evidence was just checked; retry in about %s seconds."),
        retrySeconds());
  }

  public static String retryButton() {
    return UiText.format(UiText.choose("等待 %s 秒后重试", "Wait %s s and retry"), retrySeconds());
  }

  public static int retrySeconds() {
    return (int) (WebStopBudget.FORCE_RETRY_MILLIS / 1000);
  }

  /** 只挑“已核验”的 PID / 命令摘要；没有可用事实时明确说“未取得”，不能假装有 PID。 */
  static String evidence(WebStopDiagnostic diagnostic) {
    if (diagnostic == null) return missingEvidence();
    List<String> values = new ArrayList<>();
    for (String argument : diagnostic.arguments())
      if (argument != null && (argument.startsWith("PID=") || argument.startsWith("命令摘要=")))
        values.add(argument);
    return values.isEmpty() ? missingEvidence() : String.join("\n", values);
  }

  /** 原因行只放“未通过=…”或非证据参数；内部异常串也照实保留（已脱敏），不伪装成已核验事实。 */
  static String reason(WebStopDiagnostic diagnostic) {
    List<String> rejected = new ArrayList<>();
    List<String> others = new ArrayList<>();
    if (diagnostic != null)
      for (String argument : diagnostic.arguments()) {
        if (argument == null || argument.isBlank()) continue;
        if (argument.startsWith("未通过=")) rejected.add(argument.substring("未通过=".length()));
        else if (!argument.startsWith("PID=") && !argument.startsWith("命令摘要="))
          others.add(argument);
      }
    if (!rejected.isEmpty()) return String.join("；", rejected);
    if (!others.isEmpty()) return String.join("；", others);
    return UiText.choose("证据不足或两次核验结果不一致", "Evidence was insufficient or changed between checks");
  }

  private static String missingEvidence() {
    return UiText.choose(
        "未取得可核验的残留 PID（PID 记录缺失或出生身份不符）",
        "No verifiable residual PID (PID record missing or birth identity mismatch)");
  }
}
