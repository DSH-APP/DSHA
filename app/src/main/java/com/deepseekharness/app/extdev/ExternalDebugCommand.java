package com.deepseekharness.app.extdev;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Strict, read-only adb shell argv parser for the first external-device release. */
final class ExternalDebugCommand {
  private static final Pattern ARG = Pattern.compile("[A-Za-z0-9_./:=+@%,-]{1,256}");

  private ExternalDebugCommand() {}

  static List<String> argv(String command) {
    if (command == null || command.length() > 512 || command.indexOf('\0') >= 0)
      throw new IllegalArgumentException("外接调试命令无效");
    List<String> result = new ArrayList<>();
    for (String value : command.trim().split("\\s+")) {
      if (value.isEmpty() || !ARG.matcher(value).matches())
        throw new IllegalArgumentException("外接调试命令只允许简单参数，不支持脚本、引号、管道或重定向");
      result.add(value);
    }
    if (result.isEmpty() || result.size() > 12) throw new IllegalArgumentException("外接调试参数过多");
    String executable = result.get(0);
    if (!java.util.Set.of(
            "id",
            "getprop",
            "wm",
            "dumpsys",
            "pm",
            "ls",
            "cat",
            "df",
            "uname",
            "logcat",
            "settings")
        .contains(executable)) throw new IllegalArgumentException("外接调试命令不在只读白名单");
    if (result.stream()
        .anyMatch(
            item ->
                item.contains("/dev/block")
                    || item.equals("put")
                    || item.equals("delete")
                    || item.equals("clear"))) throw new IllegalArgumentException("外接调试命令可能修改目标状态");
    return List.copyOf(result);
  }
}
