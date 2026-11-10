package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 设备通道卡住时的自动取证快照（T38）：格式化、进程表过滤与轮换选择。
 *
 * <p>只记录通道状态与相关进程的 PID / PPID / UID / 存活时长 / 名称，不记录命令参数、输出或密钥；
 * 写入应用私有目录，保留最近 {@link #KEEP} 份。纯逻辑，不依赖 Android。
 */
public final class DeviceChannelSnapshot {
  private DeviceChannelSnapshot() {}

  public static final int KEEP = 5;
  public static final int MAX_PROCESS_LINES = 200;
  public static final long MIN_INTERVAL_MS = 5 * 60_000L;
  public static final String PREFIX = "device-channel-";
  public static final String SUFFIX = ".txt";

  private static final Pattern FILE =
      Pattern.compile("device-channel-\\d{8}T\\d{6}Z(-\\d+)?\\.txt");
  private static final Pattern RELEVANT =
      Pattern.compile(
          "(?i)(adbd|\\badb\\b|app_process|shizuku|stellar|sui|\\bsu\\b|\\bsh\\b|sunsetrne|RootShellMain|VirtualScreenCore)");

  /** 文件名使用 UTC 时间；同秒重复时加序号。 */
  public static String fileName(long epochMillis, int sequence) {
    java.text.SimpleDateFormat format =
        new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT);
    format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    String stamp = format.format(new java.util.Date(epochMillis));
    return PREFIX + stamp + (sequence > 0 ? "-" + sequence : "") + SUFFIX;
  }

  public static boolean ownedName(String name) {
    return name != null && FILE.matcher(name).matches();
  }

  /** 轮换：只考虑本模块命名的文件，按名称（即时间）保留最新 keep 份，返回应删除的名称。 */
  public static List<String> expired(List<String> names, int keep) {
    List<String> owned = new ArrayList<>();
    for (String name : names) if (ownedName(name)) owned.add(name);
    Collections.sort(owned);
    int remove = Math.max(0, owned.size() - Math.max(1, keep));
    return new ArrayList<>(owned.subList(0, remove));
  }

  /** 是否到了下一次可取证的时间（按触发原因分别限频）。 */
  public static boolean due(Long lastAt, long now) {
    return lastAt == null || now - lastAt >= MIN_INTERVAL_MS || now < lastAt;
  }

  /**
   * 只保留与设备通道相关的进程行；表头保留。期望输入为 {@code ps -A -o PID,PPID,UID,ETIME,NAME}，
   * 每行最多保留前 5 列，丢弃其余字段，避免意外带出参数。
   */
  public static String filterProcesses(String ps) {
    if (ps == null || ps.isEmpty()) return "";
    StringBuilder out = new StringBuilder();
    int kept = 0, total = 0;
    boolean header = true;
    for (String raw : ps.split("\r?\n")) {
      String line = raw.trim();
      if (line.isEmpty() || line.startsWith("[EXIT=")) continue;
      String[] fields = line.split("\\s+");
      if (header && fields.length > 0 && fields[0].equals("PID")) {
        out.append(columns(fields)).append('\n');
        header = false;
        continue;
      }
      header = false;
      if (fields.length < 5 || !fields[0].matches("\\d+")) continue;
      total++;
      if (!RELEVANT.matcher(fields[4]).find()) continue;
      if (kept >= MAX_PROCESS_LINES) continue;
      out.append(columns(fields)).append('\n');
      kept++;
    }
    out.append("# relevant=").append(kept).append(" total=").append(total);
    if (kept >= MAX_PROCESS_LINES) out.append(" (truncated)");
    return out.append('\n').toString();
  }

  /** 按名称统计相关进程数量，堆积时一眼可见。 */
  public static Map<String, Integer> countByName(String filtered) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    if (filtered == null) return counts;
    for (String line : filtered.split("\n")) {
      String[] fields = line.trim().split("\\s+");
      if (fields.length < 5 || !fields[0].matches("\\d+")) continue;
      counts.merge(fields[4], 1, Integer::sum);
    }
    return counts;
  }

  private static String columns(String[] fields) {
    return String.join(" ", Arrays.copyOfRange(fields, 0, Math.min(5, fields.length)));
  }

  /** 报告正文；各段值由调用方脱敏后传入。 */
  public static String render(
      String trigger, long epochMillis, Map<String, String> state, String processes) {
    StringBuilder out = new StringBuilder();
    out.append("DSHA device channel snapshot\n");
    out.append("trigger=").append(oneLine(trigger)).append('\n');
    out.append("utc=").append(fileName(epochMillis, 0), PREFIX.length(), PREFIX.length() + 16);
    out.append('\n');
    for (Map.Entry<String, String> entry : state.entrySet())
      out.append(entry.getKey()).append('=').append(oneLine(entry.getValue())).append('\n');
    out.append("\n[processes]\n");
    if (processes == null || processes.isEmpty()) out.append("(unavailable)\n");
    else {
      out.append(processes);
      Map<String, Integer> counts = countByName(processes);
      if (!counts.isEmpty()) {
        out.append("\n[counts]\n");
        for (Map.Entry<String, Integer> entry : counts.entrySet())
          out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
      }
    }
    return out.toString();
  }

  private static String oneLine(String value) {
    if (value == null) return "";
    String text = value.replace("\r", "\\r").replace("\n", "\\n");
    return text.length() > 2000 ? text.substring(0, 2000) + "…" : text;
  }
}
