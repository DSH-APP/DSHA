package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 按受支持的 grep 参数合同收集真正读取的文件，不把模式和过滤条件当作文件。 */
public final class GrepReadPaths {
  private static final String FLAGS = "ZzEFHIabhinorRsvwcLlqx";
  private static final Set<String> LONG_FLAGS =
      Set.of(
          "line-buffered",
          "byte-offset",
          "no-filename",
          "only-matching",
          "count",
          "files-without-match",
          "files-with-matches",
          "quiet",
          "silent",
          "help",
          "version");
  private static final Set<String> LONG_FILTERS = Set.of("include", "exclude", "exclude-dir");

  private GrepReadPaths() {}

  public static List<String> collect(List<String> argv) {
    if (argv == null || argv.isEmpty() || !"grep".equals(argv.get(0))) throw invalid();
    for (String arg : argv) if (arg == null || arg.indexOf('\0') >= 0) throw invalid();
    List<String> paths = new ArrayList<>(), positional = new ArrayList<>();
    boolean options = true, explicitPattern = false, recursive = false;
    for (int i = 1; i < argv.size(); i++) {
      String arg = argv.get(i);
      if (arg == null || arg.indexOf('\0') >= 0) throw invalid();
      if (options && arg.equals("--")) {
        options = false;
      } else if (options && arg.startsWith("--")) {
        int equals = arg.indexOf('=');
        String name = arg.substring(2, equals < 0 ? arg.length() : equals);
        if (name.equals("color")) continue;
        if (LONG_FLAGS.contains(name) && equals < 0) continue;
        if (!LONG_FILTERS.contains(name)) throw invalid();
        if (equals < 0 && ++i >= argv.size()) throw invalid();
      } else if (options && arg.startsWith("-") && !arg.equals("-")) {
        for (int j = 1; j < arg.length(); j++) {
          char flag = arg.charAt(j);
          if (flag == 'r' || flag == 'R') recursive = true;
          if (FLAGS.indexOf(flag) >= 0) continue;
          if ("efMSABCm".indexOf(flag) < 0) throw invalid();
          String value;
          if (j + 1 < arg.length()) value = arg.substring(j + 1);
          else {
            if (++i >= argv.size()) throw invalid();
            value = argv.get(i);
          }
          if (value == null || value.indexOf('\0') >= 0) throw invalid();
          if ("ABCm".indexOf(flag) >= 0 && !value.matches("[0-9]+")) throw invalid();
          if (flag == 'e' || flag == 'f') explicitPattern = true;
          // Toybox 的模式文件 "-" 是字面文件名；只有位置参数 "-" 表示标准输入。
          if (flag == 'f') paths.add(value);
          break; // 带值选项消费余下字节，模式中的 f 不能再解释成选项。
        }
      } else positional.add(arg);
    }
    int firstFile = explicitPattern ? 0 : 1;
    for (int i = firstFile; i < positional.size(); i++)
      if (!positional.get(i).equals("-")) paths.add(positional.get(i));
    if (recursive && positional.size() <= firstFile) paths.add(".");
    return List.copyOf(paths);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("GREP_READ_ARGUMENTS_UNVERIFIED");
  }
}
