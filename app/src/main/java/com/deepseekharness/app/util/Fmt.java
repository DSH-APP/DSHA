package com.deepseekharness.app.util;

import java.util.Locale;

/** 进度/速率/时长的格式化 + 平滑速率估算（纯逻辑，可单测）。 */
public final class Fmt {

  private Fmt() {}

  public static String bytes(long b) {
    if (b < 1024) return b + " B";
    double kb = b / 1024.0;
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
    double mb = kb / 1024.0;
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
    return String.format(Locale.US, "%.2f GB", mb / 1024.0);
  }

  public static String rate(double bps) {
    if (bps < 1024) return String.format(Locale.US, "%.0f B/s", bps);
    if (bps < 1024 * 1024) return String.format(Locale.US, "%.0f KB/s", bps / 1024.0);
    return String.format(Locale.US, "%.1f MB/s", bps / 1024.0 / 1024.0);
  }

  public static String eta(long seconds) {
    if (seconds < 0) return "--";
    if (seconds < 60) return UiText.format("%s 秒", seconds);
    long m = seconds / 60;
    if (m < 60) return UiText.format("%s 分 %s 秒", m, seconds % 60);
    return UiText.format("%s 时 %s 分", m / 60, m % 60);
  }

  /** 平滑速率估算：每 500ms 采样一次，避免数字乱跳。 */
  public static final class RateMeter {
    private long lastDone;
    private long lastTime;
    private double rate;

    public double feed(long done) {
      long now = System.currentTimeMillis();
      if (lastTime == 0) {
        lastTime = now;
        lastDone = done;
        return 0;
      }
      long dt = now - lastTime;
      if (dt >= 500) {
        rate = dt > 0 ? (done - lastDone) * 1000.0 / dt : 0;
        lastTime = now;
        lastDone = done;
      }
      return rate;
    }

    public long eta(long done, long total) {
      return (rate > 0 && total > done) ? (long) ((total - done) / rate) : -1;
    }
  }
}
