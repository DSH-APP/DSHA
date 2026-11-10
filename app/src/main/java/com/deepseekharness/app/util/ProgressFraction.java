package com.deepseekharness.app.util;

/**
 * 真实进度分数：只有在总量已知时才给出百分比，未知一律返回 -1（调用方使用不确定态，
 * 不拿动画或时间猜进度）。
 */
public final class ProgressFraction {
  private ProgressFraction() {}

  /**
   * 已完成 / 总量 → 0..99；运行中封顶 99，100 只由完成事件决定。
   *
   * @return 总量未知返回 -1
   */
  public static int percent(long done, long total) {
    if (total <= 0 || done < 0) return -1;
    if (done >= total) return 99;
    long clamped = Math.min(done, total);
    return (int) Math.max(0, Math.min(99, Math.round(clamped * 100.0 / total)));
  }

  /**
   * 恢复作业优先用字节比例（后端逐块累计已复制的字节），字节总量未知时退回条目数；
   * 两者都未知返回 -1。
   */
  public static int restorePercent(
      long doneEntries, long doneBytes, long totalEntries, long totalBytes) {
    int bytes = percent(doneBytes, totalBytes);
    if (bytes >= 0) return bytes;
    return percent(doneEntries, totalEntries);
  }
}
