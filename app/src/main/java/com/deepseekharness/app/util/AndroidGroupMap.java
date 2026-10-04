package com.deepseekharness.app.util;

import java.util.*;

/** Android groups derive app/user identities from the current UID, never a development handset. */
public final class AndroidGroupMap {
  private static final Map<Integer, String> COMMON =
      Map.ofEntries(
          Map.entry(1004, "input"),
          Map.entry(1007, "log"),
          Map.entry(1011, "adb"),
          Map.entry(1015, "sdcard_rw"),
          Map.entry(1028, "sdcard_r"),
          Map.entry(1078, "ext_data_rw"),
          Map.entry(1079, "ext_obb_rw"),
          Map.entry(3001, "net_bt_admin"),
          Map.entry(3002, "net_bt"),
          Map.entry(3003, "inet"),
          Map.entry(3006, "net_bw_stats"),
          Map.entry(3009, "readproc"),
          Map.entry(3011, "uhid"),
          Map.entry(3012, "readtracefs"),
          Map.entry(9997, "everybody"));

  public static List<Integer> groups(String procStatus) {
    List<Integer> result = new ArrayList<>();
    for (String line : procStatus.split("\n"))
      if (line.startsWith("Groups:")) {
        for (String value : line.substring(7).trim().split("\\s+"))
          try {
            int gid = Integer.parseInt(value);
            if (gid > 0 && gid < Integer.MAX_VALUE && !result.contains(gid)) result.add(gid);
          } catch (NumberFormatException ignored) {
          }
        break;
      }
    return List.copyOf(result);
  }

  public static String appendMissing(String content, int uid, List<Integer> actual) {
    Set<Integer> present = new HashSet<>();
    Set<String> names = new HashSet<>();
    for (String line : content.split("\n")) {
      String[] fields = line.split(":", -1);
      if (fields.length != 4) continue;
      names.add(fields[0]);
      try {
        present.add(Integer.parseInt(fields[2]));
      } catch (NumberFormatException ignored) {
      }
    }
    TreeMap<Integer, String> requested = new TreeMap<>(COMMON);
    int app = uid % 100000, user = uid / 100000;
    if (uid > 0)
      requested.putIfAbsent(
          uid, app >= 10000 && app <= 19999 ? "u" + user + "_a" + (app - 10000) : "aid_" + uid);
    for (int gid : actual)
      if (gid > 0 && gid < Integer.MAX_VALUE) requested.putIfAbsent(gid, "aid_" + gid);
    StringBuilder append = new StringBuilder();
    for (var entry : requested.entrySet())
      if (!present.contains(entry.getKey())) {
        String name = entry.getValue();
        if (names.contains(name)) name = "aid_" + entry.getKey();
        if (names.contains(name)) continue;
        names.add(name);
        append.append(name).append(":x:").append(entry.getKey()).append(":\n");
      }
    if (append.length() == 0) return content;
    return content + (content.isEmpty() || content.endsWith("\n") ? "" : "\n") + append;
  }

  private AndroidGroupMap() {}
}
