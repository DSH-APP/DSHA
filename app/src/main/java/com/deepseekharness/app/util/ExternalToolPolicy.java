package com.deepseekharness.app.util;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Native boundary guard for the host-side adb/fastboot argv path. */
public final class ExternalToolPolicy {
  private static final Set<String> READ_SHELL =
      Set.of(
          "id", "getprop", "wm", "dumpsys", "pm", "ls", "cat", "df", "uname", "logcat", "settings");

  private ExternalToolPolicy() {}

  public static String rejection(List<String> argv) {
    return rejection(argv, "/");
  }

  /** Same check plus canonical rootfs confinement when the native caller can provide it. */
  public static String rejection(List<String> argv, String expectedRoot) {
    return rejection(argv, expectedRoot, null);
  }

  /**
   * 安装类命令（install / install-multiple / push）的本地文件必须位于 {@code importRoot}（App 私有导入区，
   * 不在 rootfs 内）；未提供 importRoot 时这些命令一律拒绝。
   */
  public static String rejection(List<String> argv, String expectedRoot, String importRoot) {
    String prefixFailure = prefixRejection(argv, expectedRoot);
    if (prefixFailure != null) return prefixFailure;
    String binary = argv.get(3);
    if (binary.endsWith("/usr/bin/adb"))
      return adbRejection(argv.subList(4, argv.size()), importRoot);
    if (binary.endsWith("/usr/bin/fastboot"))
      return fastbootRejection(argv.subList(4, argv.size()));
    return "EXTERNAL_BINARY";
  }

  /** Only the owned nodaemon server process may create an external ADB server. */
  public static String rejectionAdbServer(List<String> argv, String expectedRoot) {
    String prefixFailure = prefixRejection(argv, expectedRoot);
    if (prefixFailure != null) return prefixFailure;
    if (!argv.get(3).endsWith("/usr/bin/adb")) return "EXTERNAL_ADB_SERVER_BINARY";
    List<String> args = argv.subList(4, argv.size());
    if (args.size() != 4
        || !args.get(0).equals("-P")
        || !validPort(args.get(1))
        || !args.get(2).equals("nodaemon")
        || !args.get(3).equals("server")) return "EXTERNAL_ADB_SERVER_ARGS";
    return null;
  }

  /**
   * 外接 ADB 会话的端口：server 的 `-P` 必须与客户端紧跟二进制后的 `-P` 相同，否则客户端可能连到别的 server。
   * 返回端口；形状不符返回 -1。只做形状比对，策略检查仍由 rejection / rejectionAdbServer 负责。
   */
  public static int adbSessionPort(List<String> serverArgv, List<String> clientArgv) {
    if (serverArgv == null || clientArgv == null || serverArgv.size() != 8 || clientArgv.size() < 7)
      return -1;
    for (int i = 0; i < 4; i++)
      if (!java.util.Objects.equals(serverArgv.get(i), clientArgv.get(i))) return -1;
    if (!"-P".equals(serverArgv.get(4)) || !validPort(serverArgv.get(5))) return -1;
    if (!"-P".equals(clientArgv.get(4)) || !serverArgv.get(5).equals(clientArgv.get(5))) return -1;
    return Integer.parseInt(serverArgv.get(5));
  }

  private static String prefixRejection(List<String> argv, String expectedRoot) {
    if (argv == null || argv.size() < 4 || argv.size() > 32) return "EXTERNAL_ARGV_SHAPE";
    for (String value : argv) {
      if (value == null || value.isEmpty() || value.length() > 4096 || value.indexOf('\0') >= 0)
        return "EXTERNAL_ARGV_VALUE";
      if (value.indexOf(';') >= 0
          || value.indexOf('|') >= 0
          || value.indexOf('&') >= 0
          || value.indexOf('>') >= 0
          || value.indexOf('<') >= 0
          || value.indexOf('$') >= 0
          || value.indexOf('`') >= 0) return "EXTERNAL_ARGV_METACHARACTER";
    }
    String loader = argv.get(0);
    String library = argv.get(2);
    String binary = argv.get(3);
    if (!absoluteSafe(loader) || !absoluteSafe(binary) || !"--library-path".equals(argv.get(1)))
      return "EXTERNAL_TOOL_PATH";
    if (expectedRoot == null || expectedRoot.isEmpty()) return "EXTERNAL_ROOT_REQUIRED";
    final String root;
    try {
      root = new java.io.File(expectedRoot).getCanonicalPath();
      if (!new java.io.File(root).isDirectory()) return "EXTERNAL_ROOT_INVALID";
    } catch (java.io.IOException error) {
      return "EXTERNAL_ROOT_INVALID";
    }
    if (!underRoot(loader, root) || !underRoot(binary, root)) return "EXTERNAL_TOOL_OUTSIDE_ROOT";
    // -1 保留空段：`a::b`、首尾冒号都会产生空段，空段在 ld.so 里等于当前目录，必须拒绝。
    for (String item : library.split(":", -1))
      if (item.isEmpty() || !absoluteSafe(item) || !underRoot(item, root))
        return "EXTERNAL_LIBRARY_OUTSIDE_ROOT";
    if (!loader.endsWith("/ld-linux-aarch64.so.1")) return "EXTERNAL_LOADER";
    return null;
  }

  private static String adbRejection(List<String> args, String importRoot) {
    int i = 0;
    if (i < args.size() && args.get(i).equals("-H")) {
      if (++i >= args.size() || !args.get(i++).equals("127.0.0.1")) return "EXTERNAL_ADB_HOST";
    }
    if (i < args.size() && args.get(i).equals("-P")) {
      if (++i >= args.size() || !validPort(args.get(i++))) return "EXTERNAL_ADB_PORT";
    }
    String target = null;
    if (i < args.size() && args.get(i).equals("-s")) {
      // USB 序列号或无线 IPv4:端口；fastboot 仍只认 USB（见 fastbootRejection）。
      if (++i >= args.size() || !ExternalDevicePolicy.isAdbTarget(args.get(i)))
        return "EXTERNAL_ADB_SERIAL";
      target = args.get(i++);
    }
    if (i >= args.size()) return "EXTERNAL_ADB_COMMAND";
    String command = args.get(i++).toLowerCase(Locale.ROOT);
    List<String> rest = args.subList(i, args.size());
    if (command.equals("connect"))
      return target == null
              && rest.size() == 1
              && ExternalDevicePolicy.isWirelessTarget(rest.get(0))
          ? null
          : "EXTERNAL_ADB_CONNECT_ARGS";
    if (command.equals("pair"))
      return target == null
              && rest.size() == 2
              && ExternalDevicePolicy.pairTarget(rest.get(0) + " " + rest.get(1)) != null
          ? null
          : "EXTERNAL_ADB_PAIR_ARGS";
    if (command.equals("reboot"))
      return target != null
              && (rest.isEmpty()
                  || rest.size() == 1
                      && Set.of("bootloader", "recovery", "fastboot").contains(rest.get(0)))
          ? null
          : "EXTERNAL_ADB_REBOOT_ARGS";
    if (command.equals("wait-for-device"))
      return target != null && rest.isEmpty() ? null : "EXTERNAL_ADB_TARGET_REQUIRED";
    if (command.equals("install") || command.equals("install-multiple") || command.equals("push"))
      return target == null
          ? "EXTERNAL_ADB_TARGET_REQUIRED"
          : installRejection(command, rest, importRoot);
    if (command.equals("devices"))
      return target == null
              && (i == args.size() || i + 1 == args.size() && args.get(i).equals("-l"))
          ? null
          : "EXTERNAL_ADB_DEVICES_ARGS";
    if (command.equals("get-state") || command.equals("get-serialno"))
      return target != null && i == args.size() ? null : "EXTERNAL_ADB_TARGET_REQUIRED";
    if (command.equals("shell")) {
      if (target == null || i >= args.size()) return "EXTERNAL_ADB_TARGET_REQUIRED";
      // 安装计划生成的单串远端命令（su -c '...' 等）按生成形状反向核验，其余走只读白名单。
      if (rest.size() == 1 && ExternalInstallPlan.plannedRemoteShell(rest.get(0))) return null;
      return shellRejection(rest);
    }
    return "EXTERNAL_ADB_OPERATION_BLOCKED";
  }

  /** install -r FILE / install-multiple -r FILE... / push FILE REMOTE；FILE 必须在导入区。 */
  private static String installRejection(String command, List<String> rest, String importRoot) {
    if (importRoot == null || importRoot.isEmpty()) return "EXTERNAL_IMPORT_ROOT_REQUIRED";
    final String root;
    try {
      root = new java.io.File(importRoot).getCanonicalPath();
    } catch (java.io.IOException error) {
      return "EXTERNAL_IMPORT_ROOT_INVALID";
    }
    if (command.equals("push")) {
      if (rest.size() != 2) return "EXTERNAL_ADB_PUSH_ARGS";
      if (!importedFile(rest.get(0), root)) return "EXTERNAL_ADB_PUSH_SOURCE";
      return ExternalInstallPlan.plannedRemotePush(rest.get(1)) ? null : "EXTERNAL_ADB_PUSH_TARGET";
    }
    if (rest.isEmpty() || !rest.get(0).equals("-r")) return "EXTERNAL_ADB_INSTALL_ARGS";
    List<String> files = rest.subList(1, rest.size());
    if (command.equals("install")
        ? files.size() != 1
        : files.isEmpty() || files.size() > ExternalInstallPlan.MAX_SPLITS)
      return "EXTERNAL_ADB_INSTALL_ARGS";
    for (String file : files)
      if (!importedFile(file, root) || !file.toLowerCase(Locale.ROOT).endsWith(".apk"))
        return "EXTERNAL_ADB_INSTALL_SOURCE";
    return null;
  }

  /** 本地文件：绝对路径、无 .. 段、规范化后在导入区内、文件名只含安全字符。 */
  private static boolean importedFile(String raw, String root) {
    if (raw == null || !absoluteSafe(raw) || !underRoot(raw, root) || raw.equals(root))
      return false;
    String name = raw.substring(raw.lastIndexOf('/') + 1);
    return name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,200}");
  }

  private static String shellRejection(List<String> args) {
    String command = args.get(0).toLowerCase(Locale.ROOT);
    if (!READ_SHELL.contains(command)) return "EXTERNAL_ADB_SHELL_BLOCKED";
    if (command.equals("id")) return args.size() == 1 ? null : "EXTERNAL_ADB_SHELL_ARGS";
    if (command.equals("getprop"))
      return args.size() <= 2 && (args.size() == 1 || args.get(1).matches("[A-Za-z0-9_.-]{1,128}"))
          ? null
          : "EXTERNAL_ADB_SHELL_ARGS";
    if (command.equals("pm"))
      return args.size() == 3
              && Set.of("list", "path", "dump", "resolve-activity").contains(args.get(1))
              && args.get(2).matches("[A-Za-z0-9_.-]{1,128}")
          ? null
          : args.size() == 3
                  && args.get(1).equals("list")
                  && args.get(2).matches("packages|users|features")
              ? null
              : "EXTERNAL_ADB_SHELL_BLOCKED";
    if (command.equals("settings"))
      return args.size() == 3
              && Set.of("get", "list").contains(args.get(1))
              && Set.of("system", "secure", "global").contains(args.get(2))
          ? null
          : "EXTERNAL_ADB_SHELL_BLOCKED";
    if (command.equals("wm") || command.equals("logcat")) return diagnosticReadRejection(args);
    if (command.equals("cat")) {
      if (args.size() != 2) return "EXTERNAL_ADB_SHELL_ARGS";
      String path = args.get(1).toLowerCase(Locale.ROOT);
      return path.startsWith("/proc/bootconfig") || path.equals("/proc/cmdline")
          ? null
          : "EXTERNAL_ADB_SHELL_PATH_BLOCKED";
    }
    if (command.equals("ls"))
      return args.size() <= 2
              && (args.size() == 1 || args.get(1).matches("/[A-Za-z0-9_./-]{1,128}"))
          ? null
          : "EXTERNAL_ADB_SHELL_ARGS";
    if (command.equals("df")) return args.size() <= 2 ? null : "EXTERNAL_ADB_SHELL_ARGS";
    if (command.equals("uname")) return args.size() <= 2 ? null : "EXTERNAL_ADB_SHELL_ARGS";
    if (command.equals("dumpsys"))
      return args.size() == 2
              && Set.of("battery", "display", "meminfo", "cpuinfo", "window").contains(args.get(1))
          ? null
          : "EXTERNAL_ADB_SHELL_ARGS";
    return "EXTERNAL_ADB_SHELL_BLOCKED";
  }

  /** These two executable names have both read and write forms. Use the same grammar at planning and dispatch. */
  static String diagnosticReadRejection(List<String> argv) {
    if (argv.get(0).equalsIgnoreCase("wm"))
      return argv.size() == 2 && Set.of("size", "density").contains(argv.get(1))
          ? null
          : "EXTERNAL_ADB_SHELL_BLOCKED";
    if (!argv.get(0).equalsIgnoreCase("logcat")) return null;
    if (argv.size() > 12) return "EXTERNAL_ADB_SHELL_BLOCKED";
    for (int i = 1; i < argv.size(); i++) {
      String token = argv.get(i);
      switch (token) {
        case "-d", "-g", "-s" -> {}
        case "-t" -> {
          if (++i == argv.size() || !argv.get(i).matches("[1-9][0-9]{0,5}"))
            return "EXTERNAL_ADB_SHELL_BLOCKED";
        }
        case "-b" -> {
          if (++i == argv.size()
              || !argv.get(i)
                  .matches(
                      "(?:main|system|radio|events|crash|default|all)(?:,(?:main|system|radio|events|crash|default|all))*"))
            return "EXTERNAL_ADB_SHELL_BLOCKED";
        }
        case "-v" -> {
          if (++i == argv.size()
              || !Set.of(
                      "brief",
                      "process",
                      "tag",
                      "thread",
                      "raw",
                      "time",
                      "threadtime",
                      "long",
                      "uid",
                      "year",
                      "zone",
                      "usec",
                      "epoch",
                      "monotonic",
                      "printable")
                  .contains(argv.get(i))) return "EXTERNAL_ADB_SHELL_BLOCKED";
        }
        default -> {
          if (!token.matches("[A-Za-z0-9_.][A-Za-z0-9_.-]{0,63}(?::[VDIWEFS])?"))
            return "EXTERNAL_ADB_SHELL_BLOCKED";
        }
      }
    }
    return null;
  }

  private static String fastbootRejection(List<String> args) {
    int i = 0;
    if (i + 1 >= args.size()
        || !args.get(i).equals("-s")
        || !ExternalDevicePolicy.isUsbSerial(args.get(i + 1)))
      return "EXTERNAL_FASTBOOT_SERIAL_REQUIRED";
    i += 2;
    if (i >= args.size()) return "EXTERNAL_FASTBOOT_COMMAND";
    String command = args.get(i++).toLowerCase(Locale.ROOT);
    if (command.equals("devices")) return i == args.size() ? null : "EXTERNAL_FASTBOOT_ARGS";
    if (command.equals("getvar"))
      return i + 1 == args.size() && args.get(i).matches("[A-Za-z0-9_.:-]{1,128}")
          ? null
          : "EXTERNAL_FASTBOOT_READ_ONLY_ONLY";
    // 只允许离开 bootloader；不允许 flash / erase / set_active / oem / flashing / boot 等任何写入。
    if (command.equals("reboot"))
      return i == args.size()
              || i + 1 == args.size()
                  && Set.of("bootloader", "fastboot", "recovery").contains(args.get(i))
          ? null
          : "EXTERNAL_FASTBOOT_ARGS";
    return "EXTERNAL_FASTBOOT_WRITE_BLOCKED";
  }

  private static boolean validPort(String value) {
    try {
      int port = Integer.parseInt(value);
      return port >= 1024 && port <= 65535;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private static boolean absoluteSafe(String value) {
    return value.startsWith("/")
        && !value.contains("/../")
        && !value.endsWith("/..")
        && !value.contains("//");
  }

  private static boolean underRoot(String raw, String root) {
    try {
      String canonical = new java.io.File(raw).getCanonicalPath();
      return root.equals(java.io.File.separator)
          ? canonical.startsWith(java.io.File.separator)
          : canonical.equals(root) || canonical.startsWith(root + java.io.File.separator);
    } catch (java.io.IOException error) {
      return false;
    }
  }
}
