package com.deepseekharness.app.util;

/**
 * root helper 的自退判定（T38）。
 *
 * <p>App 的 uid 无法向 su 提权后的 helper 发信号（EPERM），所以由 helper 自己在 root 身份下检查：
 * 超过请求给出的截止时间，或发起请求的 App 进程已退出 / PID 被复用，就结束子进程并退出。
 * 这样覆盖安装或 App 被系统结束后，残留 helper 不会一直占着 root 进程与设备资源。
 *
 * <p>纯逻辑：/proc 读取由调用方完成。
 */
public final class RootHelperWatchdog {
  private RootHelperWatchdog() {}

  /** helper 可额外存活的余量：App 等待结束后，还要留给 helper 自己回收子进程。 */
  public static final long EXIT_MARGIN_MS = 5_000L;

  /** 截止时间允许的上限：最长外接任务 900 秒，加 App 侧 25 秒等待与余量。 */
  public static final long MAX_DEADLINE_MS = 900_000L + 25_000L + EXIT_MARGIN_MS;

  public enum Verdict {
    CONTINUE,
    DEADLINE,
    OWNER_GONE
  }

  /** 来自请求的 owner 身份；pid 或启动时间无效时只按截止时间判定。 */
  public static final class Owner {
    public final int pid;
    public final long started;

    public Owner(int pid, long started) {
      this.pid = pid;
      this.started = started;
    }

    public boolean known() {
      return pid > 1 && started > 0;
    }
  }

  /** 请求里的截止时长；缺省或越界时用调用方给的默认值，不能无限运行。 */
  public static long deadlineMs(long requested, long fallback) {
    if (requested > 0 && requested <= MAX_DEADLINE_MS) return requested;
    return Math.max(1_000L, Math.min(fallback, MAX_DEADLINE_MS));
  }

  /**
   * @param ownerStat 读取到的 /proc/&lt;owner&gt;/stat 内容；null 表示读取失败（进程不存在）
   */
  public static Verdict check(long elapsedMs, long deadlineMs, Owner owner, String ownerStat) {
    if (elapsedMs >= deadlineMs) return Verdict.DEADLINE;
    if (owner == null || !owner.known()) return Verdict.CONTINUE;
    if (ownerStat == null) return Verdict.OWNER_GONE;
    ProcStat stat = ProcStat.parse(ownerStat, owner.pid);
    // 解析失败按「无法确认」处理，不误杀仍在等待结果的请求；截止时间兜底。
    if (stat == null) return Verdict.CONTINUE;
    if (stat.started != owner.started || stat.exited()) return Verdict.OWNER_GONE;
    return Verdict.CONTINUE;
  }
}
