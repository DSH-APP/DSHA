package com.deepseekharness.app.core;

import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.WebStopBudget;
import com.deepseekharness.app.util.WebStopDiagnostic;
import java.util.List;

/**
 * 启动前置停止失败时的展示适配：三要素（发生了什么 / 影响 / 下一步）+ 已核验残留。
 *
 * <p>只翻译稳定代码与已脱敏参数；不读取现场，也不改变停止结论。可执行入口是启动页上本来就有的
 * 「启动」/「停止」按钮，文案点名即可，不新增绕过身份核验的信号路径。
 */
public final class StartupStopText {
  private StartupStopText() {}

  public static String unconfirmed(List<WebStopDiagnostic> facts) {
    String detail = WebStopText.renderAll(facts == null ? List.of() : facts);
    if (detail.isBlank())
      detail =
          UiText.choose(
              "未取得可核验的残留进程信息（PID 记录缺失或身份不符）",
              "No verifiable residual process detail (PID record missing or identity mismatch)");
    long seconds = WebStopBudget.STARTUP_EXIT_MILLIS / 1000;
    return UiText.format(
        UiText.choose(
            "启动前的停止未完成，本次启动已暂停。\n发生了什么：旧 Web 或它的容器启动器在等待窗口内没有确认退出。\n影响：为避免两个 Web 实例并存，没有创建新实例；旧进程与原环境保留。\n下一步：稍等约 %s 秒后点「启动」重试；仍失败可先点「停止」单独处理，或在「恢复选项」查看诊断。\n已核验的残留与原因：\n%s",
            "The pre-start stop did not finish, so this start is paused.\nWhat happened: the old Web or its container launcher did not confirm exit within the wait window.\nImpact: no new instance was created, to avoid two Web instances; the old process and runtime were kept.\nNext: wait about %s seconds and tap Start to retry; if it still fails, stop first or open Recovery options for diagnostics.\nVerified residual and reason:\n%s"),
        seconds,
        detail);
  }
}
