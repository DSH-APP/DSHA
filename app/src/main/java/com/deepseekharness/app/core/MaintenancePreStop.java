package com.deepseekharness.app.core;

import com.deepseekharness.app.util.UiText;
import java.io.IOException;

/**
 * 用户显式确认维护后的维护前停止：先普通停止（哨兵 + PID + 出生身份核验），退出未确认时只升级一次
 * 既有身份核验强制停止。自动开始不经过这里，也不会使用强制结束。
 *
 * <p>强制候选由 {@link com.deepseekharness.app.runtime.WebProcessManager} 准备并复核（PID、出生身份、
 * 命令行、代次），这里只负责顺序、等待与失败文案，不直接读取进程、不发送信号，也不按端口或名称终止。
 * 任何一次核验失败都保留原环境，并把可操作原因交给维护页显示。
 */
final class MaintenancePreStop {
  /** 普通停止沿用既有维护屏障的 45 秒上限；强制停止只处理已核验的同一进程，给更短的确认窗口。 */
  static final long GRACE_MILLIS = 45_000;

  static final long FORCE_MILLIS = 15_000;
  static final long POLL_MILLIS = 100;

  interface Io<C> {
    /** 请求普通停止（异步入队）；实际停止与身份核验由既有停止实现完成。 */
    void requestStop();

    /** 停止队列已结束：既没有在启动，也没有在停止。 */
    boolean settled();

    /** 维护屏障的退出证明；读取失败按“尚未确认”处理。 */
    boolean confirmed() throws IOException;

    /** 最近一次停止任务的可展示原因；空串表示没有记录。 */
    String lastStopError();

    /** 准备一个经身份核验的强制结束候选；无法核验时抛出或返回 null。 */
    C prepareForce() throws IOException;

    /** 把候选交给停止队列；返回 false 表示请求未被接受。 */
    boolean requestForce(C candidate);

    long now();

    void pause(long millis) throws InterruptedException;
  }

  private MaintenancePreStop() {}

  /**
   * 停止成功才返回；普通停止未确认时只在用户已显式确认维护的调用方升级一次强制停止。
   *
   * @throws IOException 普通停止与强制停止都未能确认退出；正文含可操作的重试指引
   */
  static <C> void stop(Io<C> io) throws IOException, InterruptedException {
    io.requestStop();
    String first = await(io, GRACE_MILLIS);
    if (first == null) return;
    C candidate;
    try {
      candidate = io.prepareForce();
    } catch (IOException unavailable) {
      throw new IOException(unavailable(first, message(unavailable)));
    }
    if (candidate == null) throw new IOException(unavailable(first, ""));
    if (!io.requestForce(candidate)) throw new IOException(rejected(first));
    String second = await(io, FORCE_MILLIS);
    if (second == null) return;
    throw new IOException(failed(first, second));
  }

  /** 返回 null 表示退出已确认；否则返回最后一次可展示的停止原因。 */
  private static <C> String await(Io<C> io, long budget) throws InterruptedException {
    long deadline = io.now() + budget;
    String reason = "";
    while (true) {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedException(UiText.text("等待 Web 停止被中断"));
      if (io.settled()) {
        try {
          if (io.confirmed()) return null;
        } catch (IOException unconfirmed) {
          reason = message(unconfirmed);
        }
        String current = io.lastStopError();
        if (current != null && !current.isEmpty()) reason = current;
      }
      long now = io.now();
      if (now >= deadline) break;
      io.pause(Math.min(POLL_MILLIS, deadline - now));
    }
    return reason.isEmpty()
        ? UiText.choose("等待 Web 或它启动的后台进程退出超时", "Timed out waiting for the Web to exit")
        : reason;
  }

  static String unavailable(String first, String reason) {
    return UiText.format(
        UiText.choose(
            "旧 Web 未能在维护前确认停止，且本次无法用已核验身份强制结束。原运行环境未改动；请保持 DSHA 在前台，点下方「重试更新运行时」再试。覆盖更新留下的上一版实例只有在出生身份完整核验通过后，才会连同旧容器启动器一起结束。\n上次停止结果：%s\n核验结果：%s",
            "The old Web could not be confirmed stopped before maintenance, and no identity-verified force stop was available. The runtime was not changed; keep DSHA in the foreground and tap Retry runtime update. A previous-build instance left by an over-the-top update is ended together with its old container launcher only after its full birth identity verifies.\nLast stop result: %s\nIdentity check: %s"),
        first,
        reason == null || reason.isEmpty() ? "-" : reason);
  }

  static String rejected(String first) {
    return UiText.format(
        UiText.choose(
            "旧 Web 未能在维护前确认停止，强制停止请求也未被接受。原运行环境未改动；请保持 DSHA 在前台，点下方「重试更新运行时」再试。\n上次停止结果：%s",
            "The old Web could not be confirmed stopped and the force stop request was not accepted. The runtime was not changed; keep DSHA in the foreground and tap Retry runtime update.\nLast stop result: %s"),
        first);
  }

  static String failed(String first, String second) {
    return UiText.format(
        UiText.choose(
            "已按身份核验尝试强制结束旧 Web，但退出仍未确认。原运行环境未改动；请点下方「重试更新运行时」；仍失败请在系统设置中结束 DSHA 后重新打开。覆盖更新留下的上一版实例会在核验通过后放弃旧版：先结束 guest，再结束旧容器启动器。\n普通停止结果：%s\n强制停止结果：%s",
            "An identity-verified force stop was attempted, but the old Web still has not confirmed exit. The runtime was not changed; tap Retry runtime update, and if it still fails close DSHA from system settings and reopen it. A previous-build instance left by an over-the-top update is retired once verification passes: the guest first, then its old container launcher.\nOrdinary stop: %s\nForce stop: %s"),
        first,
        second);
  }

  private static String message(Throwable failure) {
    if (failure == null) return "";
    String value = failure.getMessage();
    return value == null ? failure.getClass().getSimpleName() : value;
  }
}
