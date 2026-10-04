package com.deepseekharness.app.core;

import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.WebStopDiagnostic;

/** Display adapter only. Runtime decisions use the immutable diagnostic code and raw facts. */
public final class WebStopText {
  private WebStopText() {}

  public static String describe(Throwable failure) {
    return render(WebStopDiagnostic.failure(WebStopDiagnostic.Code.STOP_FAILED, failure));
  }

  public static String render(WebStopDiagnostic diagnostic) {
    return switch (diagnostic.code()) {
      case OWNED_LAUNCHER_PENDING -> UiText.text("已知 Web 启动器未能确认退出");
      case FORCE_REQUIRES_UNCONFIRMED_STOP -> UiText.text("请先完成普通停止；只有退出尚未确认时才能准备强制停止候选");
      case FORCE_REQUEST_CHANGED -> UiText.text("Web 停止请求已变化，请重新检查当前状态");
      case ROOT_LINK -> UiText.text("Web 根目录是链接，未终止任何进程");
      case ROOT_FOREIGN_UID -> UiText.text("Web 根目录不属于本应用，未终止任何进程");
      case ROOT_ACCESS_UNCONFIRMED -> UiText.format("Web 根目录权限无法确认：%s", facts(diagnostic));
      case PID_RECORD_INVALID -> UiText.text("Web PID 文件异常，未终止任何进程");
      case PID_INVALID -> UiText.text("Web PID 无效，未终止任何进程");
      case RECORD_FOREIGN_UID -> UiText.text("Web 记录不属于本应用，未终止任何进程");
      case RECORD_ACCESS_UNCONFIRMED -> UiText.format("Web 记录权限无法确认：%s", facts(diagnostic));
      case SENTINEL_UNWRITABLE -> UiText.text("无法写入停止标记，尚未停止 Web");
      case PROC_INFO_LIMIT -> UiText.text("进程信息超过核验上限");
      case INSPECTION_INTERRUPTED -> UiText.text("进程核验被中断");
      case CHECK_FAILED ->
          UiText.format(
              "检查 Web 进程失败（PID %s，errno=%s）", argument(diagnostic, 0), argument(diagnostic, 1));
      case BIRTH_UNREADABLE -> UiText.text("内核进程身份无法解析");
      case COMMAND_UNREADABLE -> UiText.text("进程命令行暂不可读");
      case INSPECTION_FAILED -> UiText.format("无法核验本应用进程：%s", facts(diagnostic));
      case PID_STALE_LOCATION_INVALID -> UiText.text("Web 旧记录隔离目录异常，原环境保留");
      case RECORD_RETIRE_FAILED -> UiText.text("无法隔离旧 Web 记录，原环境保留");
      case IDENTITY_RECORD_INVALID -> UiText.text("Web 身份记录异常，原环境保留");
      case IDENTITY_RECORD_VALUE_INVALID -> UiText.text("Web 身份记录无效，原环境保留");
      case IDENTITY_RECORD_RETIRE_FAILED -> UiText.text("无法隔离旧 Web 身份记录，原环境保留");
      case IDENTITY_RECORD_WRITE_FAILED -> UiText.format("Web 身份记录暂未保存：%s", facts(diagnostic));
      case PRE_EXEC_WAIT -> UiText.text("Web 启动脚本仍在退出，已保留容器启动器");
      case IDENTITY_CHANGED -> UiText.text("Web 进程身份已变化，未终止其他进程，请重试");
      case INSPECTION_UNCONFIRMED -> UiText.format("Web 进程身份尚未确认，原环境保留：%s", facts(diagnostic));
      case TRIAL_IDENTITY_CHANGED -> UiText.text("隔离验证进程身份已变化，原现场保留");
      case TRIAL_INSPECTION_DENIED -> UiText.text("隔离验证进程不可读取，原现场保留");
      case SIGNAL_DENIED -> UiText.text("系统拒绝终止本次 Web，原环境保留");
      case EXIT_UNCONFIRMED -> UiText.format("Web 退出尚未确认，原环境保留：%s", facts(diagnostic));
      case PENDING_EXIT -> UiText.text("已请求停止，Web 尚未退出；稍后可重试，未强杀容器启动器");
      case STOP_INTERRUPTED -> UiText.text("停止等待被中断，请检查 Web 状态");
      case STOP_FAILED -> UiText.format("停止 Web 未完成：%s", facts(diagnostic));
      case TRIAL_STOP_FAILED -> UiText.format("隔离验证停止未完成：%s", facts(diagnostic));
      case TRIAL_UNCONFIRMED -> UiText.text("隔离验证进程退出尚未确认，原现场保留");
      case OWNED_WEB_REMAINS -> UiText.text("本应用仍有未退出的 Web，原环境保留");
      case OWNED_WEB_SCAN_UNCONFIRMED -> UiText.format("本应用进程清单尚未确认，原环境保留：%s", facts(diagnostic));
      case FORCE_EVIDENCE_UNCONFIRMED ->
          UiText.format("无法核验本次 Web 停止候选，未终止任何进程：%s", facts(diagnostic));
      case LEGACY_DETAIL -> facts(diagnostic);
    };
  }

  public static String renderAll(java.util.List<WebStopDiagnostic> diagnostics) {
    return diagnostics.stream()
        .map(WebStopText::render)
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  private static String argument(WebStopDiagnostic diagnostic, int index) {
    return index < diagnostic.arguments().size() ? diagnostic.arguments().get(index) : "?";
  }

  private static String facts(WebStopDiagnostic diagnostic) {
    return String.join("\n", diagnostic.arguments());
  }
}
