package com.deepseekharness.app.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外接设备页面事件与前台窗口的纯解析/摘要逻辑。
 *
 * <p>输入只来自目标的只读命令（{@code logcat -d -b events}、{@code dumpsys window}）；输出只保留包名与 Activity
 * 名，不保留屏幕文本。{@code digest} 是带目标代次与观察模式前缀的 SHA-256 前缀：不可逆、不含用户内容、不能跨目标
 * 代次或跨模式复用。Android 16 的页面事件是 {@code wm_resume_activity} / {@code wm_set_resumed_activity}，
 * 旧版本可能是 {@code am_focused_activity} 等；真实设备测量见
 * docs/design/extdev-observation-20261110.md。
 */
public final class ExternalFocusEvents {
  private ExternalFocusEvents() {}

  /** 一条页面事件；{@code deviceTimeMs} 为 -1 表示日志格式没有绝对时间（threadtime/brief）。 */
  public record Event(long deviceTimeMs, String tag, String packageName, String activity) {
    public String component() {
      return packageName + "/" + activity;
    }
  }

  /** 前台页面快照；{@code known()} 为 false 表示没有可用的包/Activity。 */
  public record Snapshot(long deviceTimeMs, String packageName, String activity) {
    public boolean known() {
      return packageName != null
          && !packageName.isEmpty()
          && activity != null
          && !activity.isEmpty();
    }

    public String component() {
      return known() ? packageName + "/" + activity : "";
    }
  }

  private static final Set<String> FOCUS_TAGS =
      Set.of(
          "am_focused_activity",
          "am_resume_activity",
          "am_restart_activity",
          "am_relaunch_resume_activity",
          "am_on_resume_called",
          "am_on_top_resumed_gained_called",
          "am_on_top_resumed_lost_called",
          "wm_resume_activity",
          "wm_restart_activity",
          "wm_relaunch_resume_activity",
          "wm_set_resumed_activity",
          "wm_on_resume_called",
          "wm_on_top_resumed_gained_called",
          "wm_on_top_resumed_lost_called");

  private static final Pattern COMPONENT =
      Pattern.compile("(?<![A-Za-z0-9_.])([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_.$]+)");
  private static final Pattern EPOCH =
      Pattern.compile(
          "^\\s*(\\d{10,13})\\.(\\d{3})\\s+\\d+\\s+\\d+\\s+[VDIWEFS]\\s+(\\S+?)\\s*:\\s?(.*)$");
  private static final Pattern THREADTIME =
      Pattern.compile(
          "^\\s*\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+\\s+[VDIWEFS]\\s+(\\S+?)\\s*:\\s?(.*)$");
  private static final Pattern TIME =
      Pattern.compile(
          "^\\s*\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+[VDIWEFS]/(\\S+?)\\(\\s*\\d+\\):\\s?(.*)$");
  private static final Pattern CURRENT_FOCUS =
      Pattern.compile(
          "mCurrentFocus=Window\\{[^}\\n]*?\\s([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_.$]+)");
  private static final Pattern FOCUSED_APP =
      Pattern.compile(
          "mFocusedApp=ActivityRecord\\{[^}\\n]*?\\s([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_.$]+)");

  /** 解析一份 {@code logcat -b events -v epoch|threadtime} 输出；不认识的标签与行直接忽略。 */
  public static List<Event> parse(String output) {
    List<Event> events = new ArrayList<>();
    if (output == null || output.isEmpty()) return events;
    for (String line : output.split("\\r?\\n", -1)) {
      Event event = parseLine(line);
      if (event != null) events.add(event);
    }
    return events;
  }

  private static Event parseLine(String line) {
    if (line == null || line.isEmpty()) return null;
    Matcher matcher = EPOCH.matcher(line);
    if (matcher.matches()) {
      long seconds;
      long millis;
      try {
        seconds = Long.parseLong(matcher.group(1));
        millis = Long.parseLong(matcher.group(2));
      } catch (NumberFormatException invalid) {
        return null;
      }
      return event(seconds * 1000L + millis, matcher.group(3), matcher.group(4));
    }
    matcher = THREADTIME.matcher(line);
    if (matcher.matches()) return event(-1L, matcher.group(1), matcher.group(2));
    matcher = TIME.matcher(line);
    if (matcher.matches()) return event(-1L, matcher.group(1), matcher.group(2));
    return null;
  }

  private static Event event(long deviceTimeMs, String tag, String payload) {
    if (tag == null || payload == null || !FOCUS_TAGS.contains(tag)) return null;
    Matcher component = COMPONENT.matcher(payload);
    if (!component.find()) return null;
    String packageName = component.group(1);
    return new Event(
        deviceTimeMs, tag, packageName, canonicalActivity(packageName, component.group(2)));
  }

  /** 取事件列表里最新的前台页面（按设备时间；没有绝对时间时取最后一条）。 */
  public static Snapshot latest(List<Event> events) {
    if (events == null) return null;
    Event latest = null;
    for (Event event : events) {
      if (event == null) continue;
      if (latest == null || event.deviceTimeMs() >= latest.deviceTimeMs()) latest = event;
    }
    return latest == null
        ? null
        : new Snapshot(latest.deviceTimeMs(), latest.packageName(), latest.activity());
  }

  /** 从 {@code dumpsys window} 输出取权威前台页面；优先 mCurrentFocus，其次 mFocusedApp。 */
  public static Snapshot parseWindowDump(String dump) {
    if (dump == null || dump.isEmpty()) return null;
    String packageName = null;
    String activity = null;
    Matcher matcher = CURRENT_FOCUS.matcher(dump);
    while (matcher.find()) {
      packageName = matcher.group(1);
      activity = canonicalActivity(packageName, matcher.group(2));
    }
    if (packageName != null) return new Snapshot(-1L, packageName, activity);
    matcher = FOCUSED_APP.matcher(dump);
    while (matcher.find()) {
      packageName = matcher.group(1);
      activity = canonicalActivity(packageName, matcher.group(2));
    }
    return packageName == null ? null : new Snapshot(-1L, packageName, activity);
  }

  /**
   * 页面摘要：目标代次 + 模式 + 前台包/Activity 的 SHA-256 前 128 位。代次进入摘要，重新绑定目标后旧 {@code
   * since} 必然失效；摘要不含屏幕文本，可以安全返回给调用方。
   */
  public static String digest(long targetEpoch, String mode, String packageName, String activity) {
    String canonical =
        "dsha-extdev-observe-v1\n"
            + targetEpoch
            + "\n"
            + (mode == null ? "" : mode)
            + "\n"
            + (packageName == null ? "" : packageName)
            + "\n"
            + (activity == null ? "" : activity);
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      byte[] hash = sha.digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(32);
      for (int i = 0; i < 16; i++)
        hex.append(String.format(java.util.Locale.ROOT, "%02x", hash[i]));
      return hex.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static String canonicalActivity(String packageName, String activity) {
    if (activity.startsWith(".")) return packageName + activity;
    if (activity.indexOf('.') < 0) return packageName + "." + activity;
    return activity;
  }
}
