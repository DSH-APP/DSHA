package com.deepseekharness.app.util;

/**
 * 何时让容器里的 Node 回收外网连接（T37）。
 *
 * <p>已建立的 TCP 连接绑定在旧网络与旧源地址上；默认网络换了、从断网恢复、刚通过验证或解除后台
 * 限制之后，旧连接可能已失效却不报错，模型请求会一直等到流空闲看门狗（默认 300 秒）。只有这些
 * 「之后应当新建连接」的时刻才推进代次；断网本身、信号与 DNS 变化不推进，避免没有网络时把仍可能
 * 恢复的连接提前关掉。
 */
public final class NetworkEpochPolicy {
  /** 容器内代次文件（rootfs 相对路径）：不进备份，也不属于受管资产。 */
  public static final String RELATIVE_PATH = "run/dsha-network/epoch";

  private NetworkEpochPolicy() {}

  public static boolean invalidates(NetworkSnapshot before, NetworkSnapshot after) {
    if (after == null || after.handle == 0) return false;
    if (before == null || before.handle != after.handle) return true;
    if (!before.validated && after.validated) return true;
    return before.blocked && !after.blocked;
  }

  /** 代次正文：开机后单调时间加进程内序号，App 重启后也不会与旧值相同（ASCII 小写）。 */
  public static String token(long elapsedRealtimeMs, long sequence) {
    return Long.toString(Math.max(0, elapsedRealtimeMs), 36)
        + "-"
        + Long.toString(Math.max(0, sequence), 36);
  }
}
