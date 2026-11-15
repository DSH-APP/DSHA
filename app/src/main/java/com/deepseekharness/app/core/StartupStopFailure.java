package com.deepseekharness.app.core;

import com.deepseekharness.app.util.WebStopDiagnostic;
import java.io.IOException;
import java.util.List;

/**
 * 启动前置停止在约定窗口内仍未确认退出时抛出的可展示结论。
 *
 * <p>消息正文已经是由 {@link StartupStopText} 组合好的“发生了什么 / 影响 / 下一步 + 已核验残留”，
 * 启动流程不能再套一层内部串前缀；facts 同时留给启动页，用于把失败变成“可执行的强制结束入口”
 * （用户不必重启 App）。用独立类型区分，避免把其它启动失败也当成停止结论。
 */
final class StartupStopFailure extends IOException {
  private final List<WebStopDiagnostic> facts;

  StartupStopFailure(String message, List<WebStopDiagnostic> facts) {
    super(message);
    this.facts = List.copyOf(facts);
  }

  List<WebStopDiagnostic> facts() {
    return facts;
  }
}
