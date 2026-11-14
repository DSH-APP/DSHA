package com.deepseekharness.app.core;

import java.io.IOException;
import java.util.List;

/**
 * 启动前置停止的只读收尾：在同一次停止预算内轮询退出屏障，绝不重发信号。
 *
 * <p>停止事实已经说明“正在退出”时，不能用一次 10ms 的扫描就判定启动失败；也不能为了重试再跑
 * 一遍信号流程。这里只调用调用方提供的只读确认（{@code confirmStopped} 等价物），到预算尽头仍
 * 未确认才把最近一次事实交给上层展示。
 */
final class StartupStopWait {
  interface Io {
    /** 只读确认旧 guest 与已知启动器都已退出；true 表示屏障已确认（随后由调用方释放引用）。 */
    boolean confirmed() throws IOException;

    long now();

    void pause(long millis) throws InterruptedException;
  }

  private StartupStopWait() {}

  /**
   * @param pending 已取得的停止事实；确认失败时原样（或最后一次）返回给上层
   * @return 空列表表示已确认退出；非空表示到预算尽头仍未确认
   */
  static List<com.deepseekharness.app.util.WebStopDiagnostic> await(
      Io io, long deadlineMs, List<com.deepseekharness.app.util.WebStopDiagnostic> pending)
      throws InterruptedException {
    while (true) {
      boolean confirmed;
      try {
        confirmed = io.confirmed();
      } catch (IOException unavailable) {
        // 只读确认读不了不等于退出；到预算尽头按未确认收口。
        confirmed = false;
      }
      if (confirmed) return List.of();
      long now = io.now();
      if (now >= deadlineMs) return List.copyOf(pending);
      io.pause(Math.min(com.deepseekharness.app.util.WebStopBudget.POLL_MILLIS, deadlineMs - now));
    }
  }
}
