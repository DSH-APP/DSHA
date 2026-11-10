package com.deepseekharness.app.util;

/**
 * 「数据与备份」首页状态卡的判定：只根据已记录的事实分级，不推断备份内容是否完整。
 *
 * <p>优先级固定为 运行中 &gt; 维护未完成 &gt; 上次失败 &gt; 从未备份 &gt; 较久未备份 &gt; 已保护。
 * 维护未完成排在失败之前，因为此时备份 / 恢复入口本身不可用，用户需要先处理维护。
 */
public final class BackupHealth {
  /** 超过这个天数没有手动导出，就提示换机或卸载前另存一份。 */
  public static final int STALE_DAYS = 7;

  private static final long DAY = 24L * 60 * 60 * 1000;

  public enum Level {
    RUNNING,
    PENDING,
    FAILED,
    NEVER,
    STALE,
    PROTECTED
  }

  private BackupHealth() {}

  /**
   * @param lastSuccess 最近一次手动导出成功的时间戳，0 表示从未成功
   * @param lastError 最近一次手动备份留下的错误；空串表示上次没有失败
   * @param busy 当前有备份 / 数据作业在执行
   * @param pending 有未完成的环境维护
   * @param now 当前时间戳
   */
  public static Level evaluate(
      long lastSuccess, String lastError, boolean busy, boolean pending, long now) {
    if (busy) return Level.RUNNING;
    if (pending) return Level.PENDING;
    if (lastError != null && !lastError.isEmpty()) return Level.FAILED;
    if (lastSuccess <= 0) return Level.NEVER;
    // 时钟回拨时 now < lastSuccess，按刚备份处理，不误报「较久未备份」。
    if (now - lastSuccess > STALE_DAYS * DAY) return Level.STALE;
    return Level.PROTECTED;
  }

  /** 完整天数；未备份或时钟回拨时返回 0。 */
  public static long ageDays(long lastSuccess, long now) {
    if (lastSuccess <= 0 || now <= lastSuccess) return 0;
    return (now - lastSuccess) / DAY;
  }

  /** 警示等级：0 正常（含运行中），1 提醒，2 需要处理。供界面选择配色。 */
  public static int severity(Level level) {
    return switch (level) {
      case PROTECTED, RUNNING -> 0;
      case STALE, NEVER -> 1;
      case FAILED, PENDING -> 2;
    };
  }
}
