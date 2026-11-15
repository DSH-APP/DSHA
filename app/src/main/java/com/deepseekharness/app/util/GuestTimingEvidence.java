package com.deepseekharness.app.util;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * guest 注册进度（{@code REGISTER_TIMING}）的宿主侧证据：只取最后一个阶段行与耗时用于诊断，
 * 不把 guest 自报时间当作完成、失败或重试的依据。
 */
public final class GuestTimingEvidence {
  public static final String PREFIX = "REGISTER_TIMING: ";

  private static final Pattern PHASE = Pattern.compile("\"phase\":\"([A-Za-z0-9_.-]{1,48})\"");
  private static final Pattern ELAPSED =
      Pattern.compile("\"elapsedMs\":([0-9]{1,9})(?:\\.([0-9]{1,6}))?");
  private static final int MAX_LINE = 512;

  /** guest 不可能比宿主墙钟多出这么多毫秒；超出即视为两边时间源不自洽。 */
  static final long MISMATCH_TOLERANCE_MS = 5_000;

  private GuestTimingEvidence() {}

  public static final class Phase {
    public final String name;
    public final long elapsedMs;

    private Phase(String name, long elapsedMs) {
      this.name = name;
      this.elapsedMs = elapsedMs;
    }
  }

  /** 只接受应用自己的进度行；其余输出（插件正文、用户内容）一律忽略。 */
  public static Phase parse(String line) {
    if (line == null || line.length() > MAX_LINE || !line.startsWith(PREFIX)) return null;
    Matcher phase = PHASE.matcher(line);
    Matcher elapsed = ELAPSED.matcher(line);
    if (!phase.find() || !elapsed.find()) return null;
    return new Phase(phase.group(1), Long.parseLong(elapsed.group(1)));
  }

  public static Phase last(List<String> lines) {
    if (lines == null) return null;
    for (int at = lines.size() - 1; at >= 0; at--) {
      Phase phase = parse(lines.get(at));
      if (phase != null) return phase;
    }
    return null;
  }

  /** 诊断串只含阶段名与毫秒数；guest 时间超过宿主墙钟时显式标 GUEST_TIMING_MISMATCH。 */
  public static String evidence(long hostElapsedMs, Phase guest) {
    long host = Math.max(0, hostElapsedMs);
    if (guest == null) return "hostElapsedMs=" + host + " lastGuestPhase=none";
    String value =
        "hostElapsedMs="
            + host
            + " lastGuestPhase="
            + guest.name
            + " guestElapsedMs="
            + Math.max(0, guest.elapsedMs);
    if (guest.elapsedMs > host + MISMATCH_TOLERANCE_MS) return value + " GUEST_TIMING_MISMATCH";
    return value;
  }
}
