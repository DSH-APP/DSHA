package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 设备命令默认拒绝：严格档只接受可完整识别的单条 argv；放宽档由用户显式选择并仅对 root 生效。 */
public final class DeviceShellPolicy {
  private DeviceShellPolicy() {}

  /**
   * root 命令的三态拦截档位。
   *
   * <p>严格档（默认）只放行可完整识别的单条 argv；放宽档允许任意 shell 命令，但保留不可逆操作的
   * 黑名单；完全放宽档不再按命令内容判断，只保留短信读取这类原生授权闸门。档位只影响 root
   * 通道，Shizuku、ADB 与普通设备命令继续使用严格档。
   */
  public enum Mode {
    STRICT("strict"),
    RELAXED("relaxed"),
    FULL("full");

    public final String id;

    Mode(String id) {
      this.id = id;
    }

    /** 非法或缺失一律回落严格档：配置被写坏时不能顺手把防护降到最低。 */
    public static Mode parse(String value) {
      if (value != null) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (Mode mode : values()) if (mode.id.equals(normalized)) return mode;
      }
      return STRICT;
    }

    /** 只允许脚本使用的稳定标识；null 回落 strict，避免把内部枚举名写进桥协议。 */
    public static String idOf(Mode mode) {
      return mode == null ? STRICT.id : mode.id;
    }
  }

  public enum Kind {
    READ,
    FILE,
    STOP,
    SENSITIVE_READ,
    VIRTUAL_SCREEN,
    /** 用户显式放宽的 root 原始 shell；只能经 RootShell/RootShellMain 的特权路径执行。 */
    SHELL,
    DENY
  }

  /** root 原始命令的 UTF-8 字节上限；RootShellMain 与 guest 使用同一预算，避免半途截断。 */
  public static final int MAX_SHELL_COMMAND = 16384;

  public static final class Plan {
    public final Kind kind;
    public final List<String> argv, operands;
    public final String reason;

    /** 仅 {@link Kind#SHELL} 使用：用户选择的原始命令，不做 argv 解析。 */
    public final String shell;

    /** 仅 {@link Kind#SHELL} 使用：生成该计划的档位，供桥与 guest 交叉核验。 */
    public final Mode mode;

    private Plan(
        Kind kind,
        List<String> argv,
        List<String> operands,
        String reason,
        String shell,
        Mode mode) {
      this.kind = kind;
      this.argv = Collections.unmodifiableList(new ArrayList<>(argv));
      this.operands = Collections.unmodifiableList(new ArrayList<>(operands));
      this.reason = reason;
      this.shell = shell;
      this.mode = mode;
    }

    public boolean allowed() {
      return kind != Kind.DENY;
    }

    public String command() {
      return argv.isEmpty() ? "" : argv.get(0);
    }
  }

  private static Set<String> set(String words) {
    return new HashSet<>(Arrays.asList(words.split(" ")));
  }

  private static final Set<String> READ =
      set(
          "id whoami pwd uname getprop getenforce ps pidof ls cat head tail wc grep stat readlink realpath df du free uptime printenv echo printf md5sum sha1sum sha256sum sha512sum true false");
  private static final Set<String> BLOCK =
      set(
          "dd mkfs mke2fs mkfs.ext4 mkfs.f2fs fdisk sfdisk cfdisk parted sgdisk blockdev losetup wipefs setenforce chcon restorecon setprop mount umount unmount fastboot flash flash_image format erase wipe recovery reboot shutdown poweroff halt bootctl boot_control lpmake lpflash update_engine_client avbctl dmctl fsck tune2fs");
  private static final Set<String> FILE = set("mkdir touch cp mv rm rmdir rename");
  private static final Set<String> CONTENT_READ =
      set(
          "cat head tail wc grep stat readlink realpath du ls find md5sum sha1sum sha256sum sha512sum");
  private static final String[] PROTECTED = {"/dcim", "/pictures", "/android/data", "/android/obb"};
  private static final String SMS_PROVIDER = "com.android.providers.telephony";
  private static final String[] SMS_READ_ROOTS = {
    "/data/data/" + SMS_PROVIDER,
    "/data/user/*/" + SMS_PROVIDER,
    "/data/user_de/*/" + SMS_PROVIDER,
    "/data_mirror/data_ce/*/*/" + SMS_PROVIDER,
    "/data_mirror/data_de/*/*/" + SMS_PROVIDER,
    "/mnt/expand/*/user/*/" + SMS_PROVIDER,
    "/mnt/expand/*/user_de/*/" + SMS_PROVIDER
  };
  private static final String[] ALIASES = {
    "/sdcard", "/mnt/sdcard", "/storage/self/primary", "/mnt/user/0/primary"
  };
  private static final String STORAGE =
      "^/storage/(?:emulated/[0-9]+|[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4})(/.*)?$";

  /** ADB 客户端使用由原生侧提供的同一份目录规则，不再维护另一份路径黑名单。 */
  public static java.util.Map<String, Object> pathRules() {
    java.util.Map<String, Object> rules = new java.util.LinkedHashMap<>();
    rules.put("protected", Collections.unmodifiableList(Arrays.asList(PROTECTED.clone())));
    rules.put("aliases", Collections.unmodifiableList(Arrays.asList(ALIASES.clone())));
    rules.put("smsProvider", SMS_PROVIDER);
    rules.put("smsReadRoots", Collections.unmodifiableList(Arrays.asList(SMS_READ_ROOTS.clone())));
    rules.put("storage", STORAGE);
    rules.put("temporary", "/data/local/tmp");
    return rules;
  }

  private static Plan plan(Kind kind, List<String> args, List<String> paths) {
    return new Plan(kind, args, paths, "", null, null);
  }

  private static Plan shellPlan(String command, Mode mode) {
    return new Plan(
        Kind.SHELL, Collections.emptyList(), Collections.emptyList(), "", command, mode);
  }

  private static Plan deny(String reason) {
    return new Plan(
        Kind.DENY,
        Collections.emptyList(),
        Collections.emptyList(),
        "[POLICY_BLOCKED] " + reason,
        null,
        null);
  }

  /**
   * 按用户选择的档位判定。严格档完全沿用历史白名单；放宽/完全放宽档只对 root 通道开放，且短信读取
   * 的原生授权闸门在三个档位都保留。调用方必须先核对 root shell 开关与真实 root 通道。
   */
  public static Plan inspect(String command, Mode mode) {
    Mode effective = mode == null ? Mode.STRICT : mode;
    Plan strict = inspect(command);
    // 短信读取是独立敏感能力：严格档识别出的单条 content query 始终走原生授权，
    // 不能被“放宽”成任意 shell，否则复合脚本可绕过短信授权。
    if (strict.kind == Kind.SENSITIVE_READ) return strict;
    if (effective == Mode.STRICT) return strict;
    if (command == null || command.trim().isEmpty()) return deny(UiText.text("命令为空"));
    if (command.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SHELL_COMMAND)
      return deny(UiText.format("root 命令超过 %d 字节上限", MAX_SHELL_COMMAND));
    if (command.indexOf('\0') >= 0) return deny(UiText.text("命令包含 NUL 字符"));
    if (smsSurface(command)) return deny(UiText.text("短信读取只允许单条 content query，不能混在脚本中"));
    if (effective == Mode.RELAXED) {
      String blocked = irreversible(command);
      if (blocked != null) return deny(blocked);
    }
    return shellPlan(command, effective);
  }

  /** 原始命令里出现短信 provider 痕迹时，不再按普通 shell 放行，堵住复合脚本绕过授权。 */
  private static boolean smsSurface(String command) {
    String flat = command.toLowerCase(Locale.ROOT);
    return flat.contains("content://sms")
        || flat.contains("content://mms")
        || flat.contains("com.android.providers.telephony")
        || flat.contains("content query") && flat.contains("sms");
  }

  /**
   * 放宽档仍然拒绝的不可逆操作。
   *
   * <p>参考 Operit 的命令内容拦截思路，但不照抄“整条命令出现 rm -rf 或 format 就拒”：DSHA 在严格档
   * 的 rm 是受管 argv，删普通文件属于正常用法。这里按“命令词 + 目标可恢复性”分级，只拦住真正
   * 不可逆的系统级动作；需要完全放开时用户可显式选择完全放宽档。
   */
  private static String irreversible(String command) {
    String flat = command.toLowerCase(Locale.ROOT);
    String compact = flat.replaceAll("\\s+", " ");
    String[][] words = {
      {"mkfs", "禁止格式化文件系统"},
      {"mke2fs", "禁止格式化文件系统"},
      {"wipefs", "禁止抹除文件系统签名"},
      {"fastboot", "禁止刷机"},
      {"flash_image", "禁止刷机"},
      {"update_engine", "禁止系统更新写入"},
      {"mount", "禁止挂载"},
      {"umount", "禁止卸载系统分区"},
      {"reboot", "禁止重启设备"},
      {"shutdown", "禁止关机"},
      {"poweroff", "禁止关机"},
      {"setenforce", "禁止修改 SELinux 状态"},
      {"of=/dev/block", "禁止直接写块设备"},
      {">/dev/block", "禁止直接写块设备"},
      {"> /dev/block", "禁止直接写块设备"},
      {"pm clear", "禁止清除应用数据"},
      {"cmd package clear", "禁止清除应用数据"}
    };
    for (String[] rule : words) if (wordHit(compact, rule[0])) return UiText.text(rule[1]);
    for (String target : deleteTargets(compact))
      if (catastrophicTarget(target)) return UiText.format("禁止删除不可恢复的目标：%s", target);
    return null;
  }

  /** 按词边界命中，避免把 "echo reboot" 这类输出文本当成实际命令。 */
  private static boolean wordHit(String flat, String word) {
    if (word.startsWith(">") || word.contains("=") || word.contains("/"))
      return flat.contains(word);
    return java.util.regex.Pattern.compile(
            "(?:^|[\\s;&|(`])" + java.util.regex.Pattern.quote(word) + "(?![A-Za-z0-9_])")
        .matcher(flat)
        .find();
  }

  /** 从 `rm` 片段里取出目标词；只认 rm，别的命令不猜，引号只当数据。 */
  private static List<String> deleteTargets(String flat) {
    List<String> targets = new ArrayList<>();
    java.util.regex.Matcher match =
        java.util.regex.Pattern.compile(
                "(?:^|[;&|\\n]|\\bthen\\b|\\bdo\\b)\\s*(?:sudo\\s+)?rm\\b([^;&|\\n]*)")
            .matcher(flat);
    while (match.find()) {
      String[] words = match.group(1).replace("\"", "").replace("'", "").trim().split("\\s+");
      boolean recursive = false;
      List<String> current = new ArrayList<>();
      for (String word : words) {
        if (word.isEmpty()) continue;
        if (word.startsWith("-")) {
          if (word.equals("--recursive") || word.matches(".*[rR].*")) recursive = true;
        } else current.add(word);
      }
      if (recursive) targets.addAll(current);
    }
    return targets;
  }

  /** 目标是根、系统分区，或“一整块用户数据”时不可恢复。 */
  private static boolean catastrophicTarget(String raw) {
    String stripped = raw.replace("*", "");
    String path = normalize(stripped);
    if (path.isEmpty()) {
      // 相对路径无法从命令文本确定 cwd：放行普通构建目录，但拦截根/父目录和顶层系统目录。
      String word = stripped.trim().toLowerCase(Locale.ROOT);
      if (word.isEmpty() || word.equals(".") || word.startsWith("..") || word.contains("/../"))
        return true;
      return word.matches(
          "system|data|vendor|product|odm|apex|boot|sdcard|storage|root|etc|dev|proc|sys|mnt|usr|bin|lib|lib64|opt|var|run|tmp");
    }
    if (path.equals("/")) return true;
    if (within(path, "/data/local/tmp")) return path.equals("/data/local/tmp");
    for (String prefix :
        new String[] {
          "/system", "/data", "/vendor", "/product", "/odm", "/apex", "/boot", "/etc", "/root"
        }) if (within(path, prefix)) return true;
    return broadDeleteTarget(path);
  }

  /** rm 是否带递归语义：`-r`、`-R`、`-rf`、`-fr`、`--recursive` 都算。 */
  private static boolean recursiveDelete(List<String> args) {
    for (String arg : args.subList(1, args.size())) {
      if (arg.equals("--")) break;
      if (arg.equals("--recursive")) return true;
      if (arg.startsWith("-") && !arg.startsWith("--")) {
        for (int i = 1; i < arg.length(); i++) {
          char c = arg.charAt(i);
          if (c == 'r' || c == 'R') return true;
        }
      }
    }
    return false;
  }

  /**
   * 存储根本身、存储根的一级目录，或者 /data/local/tmp 根：递归删除等于清空一整块用户数据。
   * 更深的子目录仍按普通文件操作处理。
   */
  private static boolean broadDeleteTarget(String raw) {
    String path = normalize(raw);
    if (path.isEmpty()) return true;
    if (path.startsWith("/data/local/tmp")) return path.equals("/data/local/tmp");
    java.util.regex.Matcher root = java.util.regex.Pattern.compile(STORAGE).matcher(path);
    if (!root.matches()) return false;
    String rest = root.group(1) == null ? "" : root.group(1);
    if (rest.isEmpty() || rest.equals("/")) return true;
    return rest.indexOf('/', 1) < 0;
  }

  public static Plan inspect(String command) {
    final List<String> args;
    try {
      args = split(command);
    } catch (IllegalArgumentException error) {
      return deny(error.getMessage());
    }
    if (args.isEmpty()) return deny(com.deepseekharness.app.util.UiText.text("命令为空"));
    String name = executable(args.get(0));
    if (name == null) return deny(com.deepseekharness.app.util.UiText.text("只允许系统目录中的已识别命令"));
    if (name.equals("toybox")) {
      if (args.size() < 2) return deny(com.deepseekharness.app.util.UiText.text("缺少 toybox 子命令"));
      args.remove(0);
      name = args.get(0);
      if (name.contains("/")) return deny(com.deepseekharness.app.util.UiText.text("不支持嵌套执行器"));
    }
    args.set(0, name);
    if (BLOCK.contains(name) || name.startsWith("mkfs.") || name.startsWith("fsck."))
      return deny(
          com.deepseekharness.app.util.UiText.format("禁止设备分区、SELinux、系统属性、挂载或刷机操作：%s", name));
    if (READ.contains(name)) {
      if (name.equals("grep")) {
        try {
          GrepReadPaths.collect(args);
        } catch (IllegalArgumentException invalid) {
          return deny(invalid.getMessage());
        }
      }
      return plan(Kind.READ, args, Collections.emptyList());
    }
    if (name.equals("content")) {
      try {
        SmsQuery.validate(args);
        return plan(Kind.SENSITIVE_READ, args, Collections.emptyList());
      } catch (IllegalArgumentException rejected) {
        return deny(rejected.getMessage());
      }
    }
    if (name.equals("date")) {
      for (String arg : args.subList(1, args.size()))
        if (!arg.equals("-u") && !arg.equals("-R") && !arg.equals("-I") && !arg.startsWith("+"))
          return deny(com.deepseekharness.app.util.UiText.text("date 只允许读取时间"));
      return plan(Kind.READ, args, Collections.emptyList());
    }
    if (name.equals("settings")) {
      int i = 1;
      if (args.size() > 3 && args.get(i).equals("--user")) {
        if (!args.get(i + 1).matches("[0-9]+|current"))
          return deny(com.deepseekharness.app.util.UiText.text("无法识别用户编号"));
        i += 2;
      }
      if (args.size() <= i + 1 || !set("system secure global").contains(args.get(i + 1)))
        return deny(com.deepseekharness.app.util.UiText.text("无法识别 settings 参数"));
      if (args.get(i).equals("get") && args.size() == i + 3
          || args.get(i).equals("list") && args.size() == i + 2)
        return plan(Kind.READ, args, Collections.emptyList());
      return deny(com.deepseekharness.app.util.UiText.text("系统设置只允许 get/list，禁止写入"));
    }
    if (name.equals("pm")) {
      if (args.size() == 3 && set("path dump").contains(args.get(1)) && packageName(args.get(2)))
        return plan(Kind.READ, args, Collections.emptyList());
      if (args.size() >= 3
          && args.get(1).equals("list")
          && set("packages permissions features libraries users instrumentation permission-groups")
              .contains(args.get(2))) {
        for (int i = 3; i < args.size(); i++) {
          String arg = args.get(i);
          if (arg.equals("--user")) {
            if (++i >= args.size() || !args.get(i).matches("[0-9]+|current"))
              return deny(com.deepseekharness.app.util.UiText.text("无法识别用户编号"));
          } else if (arg.startsWith("-")
              && !set("-f -d -e -s -3 -i -U -u -g -a --show-versioncode").contains(arg))
            return deny(com.deepseekharness.app.util.UiText.text("未识别的 pm 选项"));
          else if (!arg.startsWith("-") && !arg.matches("[A-Za-z0-9_.]+"))
            return deny(com.deepseekharness.app.util.UiText.text("无法识别应用筛选条件"));
        }
        return plan(Kind.READ, args, Collections.emptyList());
      }
      return deny(com.deepseekharness.app.util.UiText.text("pm 只允许查询；安装、卸载、清数据和授权不开放给设备命令"));
    }
    if (name.equals("dumpsys")) {
      if (args.size() == 2
          && set("-l battery power display connectivity wifi cpuinfo meminfo package window activity input sensorservice diskstats thermalservice")
              .contains(args.get(1))) return plan(Kind.READ, args, Collections.emptyList());
      if (args.size() == 3
          && (args.get(1).equals("activity")
                  && set("activities processes top recents").contains(args.get(2))
              || args.get(1).equals("window") && set("windows displays").contains(args.get(2))
              || set("package meminfo").contains(args.get(1)) && packageName(args.get(2))))
        return plan(Kind.READ, args, Collections.emptyList());
      return deny(com.deepseekharness.app.util.UiText.text("未识别的 dumpsys 参数；禁止用诊断命令改设备状态"));
    }
    if (name.equals("find")) return inspectFind(args);
    if (name.equals("logcat")) return inspectLogcat(args);
    if (name.equals("am") && args.size() == 2 && args.get(1).equals("get-current-user"))
      return plan(Kind.READ, args, Collections.emptyList());
    // app_process can load arbitrary DEX code. The narrowly managed virtual/physical-screen
    // launchers have separate typed entry points and are never accepted as a shell command.
    if (name.equals("app_process"))
      return deny(com.deepseekharness.app.util.UiText.text("app_process 仅允许原生受管虚拟屏/实体屏入口"));
    if (name.equals("am")
        && args.size() == 3
        && set("force-stop kill").contains(args.get(1))
        && packageName(args.get(2)))
      return plan(Kind.STOP, args, Collections.singletonList(args.get(2)));
    if (name.equals("kill") || name.equals("killall") || name.equals("pkill")) {
      int i = 1;
      if (args.size() > i && set("-9 -15 -KILL -TERM -SIGKILL -SIGTERM").contains(args.get(i))) i++;
      if (name.equals("pkill") && args.size() > i && args.get(i).equals("-x")) i++;
      if (i >= args.size()) return deny(com.deepseekharness.app.util.UiText.text("缺少进程目标"));
      List<String> targets = args.subList(i, args.size());
      for (String target : targets) {
        if (name.equals("kill")) {
          if (WebProcSel.parsePid(target) < 0)
            return deny(com.deepseekharness.app.util.UiText.text("只接受正数 PID，禁止整组或全部进程信号"));
        } else if (!packageName(target))
          return deny(com.deepseekharness.app.util.UiText.text("按名称结束应用必须使用完整包名，禁止模糊匹配"));
      }
      return plan(Kind.STOP, args, targets);
    }
    if (FILE.contains(name)) return inspectFile(args);
    return deny(com.deepseekharness.app.util.UiText.format("不认识的命令不执行：%s", name));
  }

  /** 受管 FILE helper 已持有 argv；文件名中的引号只是数据，不再往返 shell 字符串。 */
  public static Plan inspectFileArguments(List<String> input) {
    if (input == null || input.isEmpty() || input.size() > 128)
      return deny("FILE_ARGUMENTS_INVALID");
    List<String> args = new ArrayList<>(input);
    int length = 0;
    for (String value : args) {
      if (value == null) return deny("FILE_ARGUMENTS_INVALID");
      length += value.length() + 1;
      if (length > 8192) return deny("FILE_ARGUMENTS_LIMIT");
      for (int i = 0; i < value.length(); i++)
        if (value.charAt(i) < 32 || value.charAt(i) == 127)
          return deny("FILE_ARGUMENTS_CONTROL_CHARACTER");
    }
    String name = executable(args.get(0));
    if (name == null || !FILE.contains(name)) return deny("FILE_OPERATION_REQUIRED");
    args.set(0, name);
    return inspectFile(args);
  }

  /** Native-only operation: validate a launcher against PackageManager's current DSHA APK. */
  public static Plan inspectVirtualScreenLaunch(String command, String installedApkPath) {
    return inspectVirtualScreenLaunch(command, installedApkPath, null);
  }

  public static Plan inspectVirtualScreenLaunch(
      String command, String installedApkPath, String expectedPackage) {
    final List<String> args;
    try {
      args = split(command);
    } catch (IllegalArgumentException error) {
      return deny(error.getMessage());
    }
    if (args.isEmpty()) return deny(com.deepseekharness.app.util.UiText.text("虚拟屏启动命令为空"));
    String name = executable(args.get(0));
    if (!"app_process".equals(name))
      return deny(com.deepseekharness.app.util.UiText.text("虚拟屏启动器无法核验"));
    args.set(0, name);
    // The child creates its own bearer secret after exec. Keeping the launcher argv
    // free of secrets prevents disclosure through process listings and crash tools.
    if (args.size() != 9
        || !canonicalApkPath(installedApkPath)
        || !SelfPackageIdentity.validName(expectedPackage)
        || !args.get(1).equals("-Djava.class.path=" + installedApkPath)
        || !args.get(2).equals("/system/bin")
        || !args.get(3).equals("com.deepseekharness.app.vscreen.VirtualScreenCore")
        || !args.get(4).equals("--launch")
        || !args.get(5).equals("--port")
        || !args.get(6).equals("0")
        || !args.get(7).equals("--package")
        || !args.get(8).equals(expectedPackage))
      return deny(com.deepseekharness.app.util.UiText.text("虚拟屏启动参数无法核验"));
    return plan(Kind.VIRTUAL_SCREEN, args, Collections.emptyList());
  }

  /** 只读退出核验只能绑定调用方持有的出生身份，不能通过此入口发信号。 */
  public static Plan inspectVirtualScreenProbe(
      String command, String installedApkPath, String expectedPackage) {
    final List<String> args;
    try {
      args = split(command);
    } catch (IllegalArgumentException error) {
      return deny(error.getMessage());
    }
    if (args.size() != 9
        || !"app_process".equals(executable(args.get(0)))
        || !canonicalApkPath(installedApkPath)
        || !SelfPackageIdentity.validName(expectedPackage)
        || !args.get(1).equals("-Djava.class.path=" + installedApkPath)
        || !args.get(2).equals("/system/bin")
        || !args.get(3).equals("com.deepseekharness.app.vscreen.VirtualScreenCore")
        || !args.get(4).equals("--probe-exit")
        || !args.get(5).equals("--identity")
        || !validVirtualScreenIdentity(args.get(6))
        || !args.get(7).equals("--package")
        || !args.get(8).equals(expectedPackage)) return deny("VSCREEN_PROBE_IDENTITY_UNVERIFIED");
    args.set(0, "app_process");
    return plan(Kind.VIRTUAL_SCREEN, args, Collections.emptyList());
  }

  /**
   * T130 Phase A：实体屏特权 Core 的启动器。与虚拟屏启动器共用同一 {@code app_process} 与 Core 类，
   * 只多一个固定的 {@code --physical}；参数逐项精确核验，仍然不放开 app_process 通用执行。
   */
  public static Plan inspectPhysicalScreenLaunch(
      String command, String installedApkPath, String expectedPackage) {
    final List<String> args;
    try {
      args = split(command);
    } catch (IllegalArgumentException error) {
      return deny(error.getMessage());
    }
    if (args.isEmpty()) return deny(com.deepseekharness.app.util.UiText.text("实体屏特权启动命令为空"));
    String name = executable(args.get(0));
    if (!"app_process".equals(name))
      return deny(com.deepseekharness.app.util.UiText.text("实体屏特权启动器无法核验"));
    args.set(0, name);
    if (args.size() != 10
        || !canonicalApkPath(installedApkPath)
        || !SelfPackageIdentity.validName(expectedPackage)
        || !args.get(1).equals("-Djava.class.path=" + installedApkPath)
        || !args.get(2).equals("/system/bin")
        || !args.get(3).equals("com.deepseekharness.app.vscreen.VirtualScreenCore")
        || !args.get(4).equals("--launch")
        || !args.get(5).equals("--physical")
        || !args.get(6).equals("--port")
        || !args.get(7).equals("0")
        || !args.get(8).equals("--package")
        || !args.get(9).equals(expectedPackage))
      return deny(com.deepseekharness.app.util.UiText.text("实体屏特权启动参数无法核验"));
    return plan(Kind.VIRTUAL_SCREEN, args, Collections.emptyList());
  }

  public static Plan inspectManagedVirtualScreen(
      String command, String installedApkPath, String expectedPackage) {
    Plan start = inspectVirtualScreenLaunch(command, installedApkPath, expectedPackage);
    if (start.allowed()) return start;
    Plan physical = inspectPhysicalScreenLaunch(command, installedApkPath, expectedPackage);
    return physical.allowed()
        ? physical
        : inspectVirtualScreenProbe(command, installedApkPath, expectedPackage);
  }

  private static boolean validVirtualScreenIdentity(String value) {
    if (value == null || value.length() > 160) return false;
    String[] fields = value.split(":", -1);
    if (fields.length != 5) return false;
    try {
      if (!fields[0].matches("[1-9][0-9]{0,9}")
          || Long.parseLong(fields[0]) <= 1
          || Long.parseLong(fields[0]) > Integer.MAX_VALUE
          || !fields[1].matches("[1-9][0-9]{0,18}")
          || Long.parseLong(fields[1]) <= 0
          || !fields[2].matches("0|[1-9][0-9]{0,9}")
          || Long.parseLong(fields[2]) > Integer.MAX_VALUE) return false;
      String uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
      return fields[3].matches(uuid) && fields[4].matches(uuid);
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  /** Android package-manager path syntax, kept pure so all transports share the same rule. */
  public static boolean canonicalApkPath(String path) {
    if (path == null
        || !path.startsWith("/data/app/")
        || !path.endsWith(".apk")
        || path.indexOf('\\') >= 0
        || path.indexOf('\0') >= 0
        || path.matches(".*[\\s].*")) return false;
    for (String part : path.split("/")) if (part.equals(".") || part.equals("..")) return false;
    return !path.contains("//");
  }

  /** Paths read by a Root command or copied by Root must not expose SMS storage. */
  public static List<String> rootReadPaths(Plan plan) {
    if (plan == null) return Collections.emptyList();
    if (plan.kind == Kind.READ && plan.command().equals("grep"))
      return GrepReadPaths.collect(plan.argv);
    List<String> paths = new ArrayList<>();
    if (plan.kind == Kind.FILE && plan.command().equals("cp")) {
      for (int i = 0; i + 1 < plan.operands.size(); i++) paths.add(plan.operands.get(i));
      return Collections.unmodifiableList(paths);
    }
    if (plan.kind != Kind.READ || !CONTENT_READ.contains(plan.command()))
      return Collections.emptyList();
    boolean afterSeparator = false;
    for (int i = 1; i < plan.argv.size(); i++) {
      String arg = plan.argv.get(i);
      if (!afterSeparator && arg.equals("--")) {
        afterSeparator = true;
        continue;
      }
      if (!afterSeparator && arg.startsWith("-")) {
        if (arg.startsWith("--") && arg.contains("=")) arg = arg.substring(arg.indexOf('=') + 1);
        else if (arg.length() > 2 && arg.charAt(0) == '-' && arg.charAt(2) == '/')
          arg = arg.substring(2);
        else continue;
      }
      if (arg.startsWith("/") || arg.contains("/") || !arg.startsWith("-")) paths.add(arg);
    }
    if (paths.isEmpty() && plan.command().equals("du")) paths.add(".");
    if (paths.isEmpty() && plan.command().equals("ls") && rootReadMayDescend(plan)) paths.add(".");
    return Collections.unmodifiableList(paths);
  }

  /** Recursive reads/copies of an ancestor could enumerate or expose the SMS provider tree. */
  public static boolean rootReadMayDescend(Plan plan) {
    if (plan == null) return false;
    String command = plan.command();
    if (plan.kind == Kind.FILE && command.equals("cp")) {
      for (String arg : plan.argv) {
        if (arg.equals("--")) break;
        if (arg.startsWith("-") && (arg.indexOf('R') >= 0 || arg.indexOf('r') >= 0)) return true;
      }
      return false;
    }
    if (plan.kind != Kind.READ) return false;
    if (command.equals("find") || command.equals("du")) return true;
    if (command.equals("ls") || command.equals("grep")) {
      for (String arg : plan.argv) {
        if (arg.equals("-R") || arg.equals("-r") || arg.equals("--recursive")) return true;
        if (arg.startsWith("-")
            && !arg.startsWith("--")
            && (arg.indexOf('R') >= 0 || arg.indexOf('r') >= 0)) return true;
      }
    }
    return false;
  }

  /** 受保护路径与祖先共用发送给 ADB 的唯一根表，避免跨语言追加路径时漂移。 */
  public static boolean smsProviderPath(String raw) {
    String path = normalize(raw).toLowerCase(Locale.ROOT);
    if (path.isEmpty()) return false;
    String[] parts = path.equals("/") ? new String[0] : path.substring(1).split("/");
    for (String root : SMS_READ_ROOTS) {
      String[] pattern = root.substring(1).split("/");
      if (parts.length >= pattern.length && smsPrefix(parts, pattern, pattern.length)) return true;
    }
    return false;
  }

  public static boolean smsProviderDescendant(String raw) {
    String path = normalize(raw).toLowerCase(Locale.ROOT);
    if (path.isEmpty()) return false;
    String[] parts = path.equals("/") ? new String[0] : path.substring(1).split("/");
    for (String root : SMS_READ_ROOTS) {
      String[] pattern = root.substring(1).split("/");
      if (smsPrefix(parts, pattern, Math.min(parts.length, pattern.length))) return true;
    }
    return false;
  }

  private static boolean smsPrefix(String[] parts, String[] pattern, int count) {
    for (int i = 0; i < count; i++)
      if (!pattern[i].equals("*") && !pattern[i].equals(parts[i])) return false;
    return true;
  }

  private static Plan inspectFile(List<String> args) {
    String name = args.get(0);
    String flags =
        name.equals("cp")
            ? "Rrfnpv"
            : name.equals("mv")
                ? "fnv"
                : name.equals("rm")
                    ? "rfRdv"
                    : name.equals("mkdir")
                        ? "pv"
                        : name.equals("rmdir") ? "v" : name.equals("touch") ? "c" : "";
    List<String> paths = new ArrayList<>();
    boolean options = true;
    for (int i = 1; i < args.size(); i++) {
      String arg = args.get(i);
      if (options && arg.equals("--")) {
        options = false;
        continue;
      }
      if (options && arg.startsWith("-")) {
        if (arg.length() < 2) return deny(com.deepseekharness.app.util.UiText.text("未识别的文件操作参数"));
        for (int j = 1; j < arg.length(); j++)
          if (flags.indexOf(arg.charAt(j)) < 0)
            return deny(com.deepseekharness.app.util.UiText.text("未识别的文件操作选项"));
      } else {
        if (!absolutePath(arg) || hasGlob(arg) || Arrays.asList(arg.split("/")).contains(".."))
          return deny(com.deepseekharness.app.util.UiText.text("写操作必须使用明确的绝对路径，禁止通配符或上级跳转"));
        paths.add(arg);
      }
    }
    int minimum = set("cp mv rename").contains(name) ? 2 : 1;
    if (paths.size() < minimum || name.equals("rename") && paths.size() != 2)
      return deny(com.deepseekharness.app.util.UiText.text("文件操作参数不完整"));
    // 复制只读取源文件；目标、移动源、删除等均必须在普通可写目录。
    List<String> writes =
        name.equals("cp") ? Collections.singletonList(paths.get(paths.size() - 1)) : paths;
    for (String path : writes)
      if (!writeAllowed(path))
        return deny(com.deepseekharness.app.util.UiText.format("受保护目录只读：%s", path));
    // 递归删除存储根或它的一级目录 = 清空整块用户数据；更深的子目录仍按普通文件操作处理。
    if (name.equals("rm") && recursiveDelete(args) && !paths.isEmpty()) {
      for (String path : paths)
        if (broadDeleteTarget(path))
          return deny(com.deepseekharness.app.util.UiText.format("拒绝递归删除存储根或一级目录：%s", path));
    }
    if (name.equals("rename")) args.set(0, "mv");
    return plan(Kind.FILE, args, paths);
  }

  private static Plan inspectFind(List<String> args) {
    if (args.size() < 2 || !absolutePath(args.get(1)))
      return deny(com.deepseekharness.app.util.UiText.text("find 需要明确的读取目录"));
    for (int i = 2; i < args.size(); i++) {
      String arg = args.get(i);
      if (set("-print -print0 -ls -empty").contains(arg)) continue;
      if (set("-name -iname -path -type -maxdepth -mindepth -size -mtime -mmin").contains(arg)
          && ++i < args.size()) continue;
      return deny(com.deepseekharness.app.util.UiText.text("find 只允许读取条件，禁止 exec/delete 等动作"));
    }
    return plan(Kind.READ, args, Collections.emptyList());
  }

  private static Plan inspectLogcat(List<String> args) {
    boolean finite = false;
    for (int i = 1; i < args.size(); i++) {
      String arg = args.get(i);
      if (arg.equals("-d")) {
        finite = true;
        continue;
      }
      if (arg.equals("-t")) {
        if (++i >= args.size() || !args.get(i).matches("[0-9]{1,5}"))
          return deny(com.deepseekharness.app.util.UiText.text("无效日志行数"));
        finite = true;
        continue;
      }
      if (set("-v -b").contains(arg)) {
        if (++i >= args.size() || !args.get(i).matches("[A-Za-z0-9_,]+"))
          return deny(com.deepseekharness.app.util.UiText.text("无效日志参数"));
        continue;
      }
      if (arg.matches("[A-Za-z0-9_.*-]+:[VDIWEFS]")) continue;
      return deny(com.deepseekharness.app.util.UiText.text("logcat 只允许读取，禁止清日志、写文件和修改缓冲区"));
    }
    return finite
        ? plan(Kind.READ, args, Collections.emptyList())
        : deny(com.deepseekharness.app.util.UiText.text("读取日志请指定 -d 或 -t 行数"));
  }

  public static boolean packageName(String name) {
    return name != null && name.matches("android|[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
  }

  public static boolean absolutePath(String path) {
    return path != null && path.startsWith("/") && path.indexOf('\0') < 0;
  }

  public static boolean hasGlob(String path) {
    return path.indexOf('*') >= 0
        || path.indexOf('?') >= 0
        || path.indexOf('[') >= 0
        || path.indexOf(']') >= 0;
  }

  public static String normalize(String path) {
    if (!absolutePath(path)) return "";
    List<String> parts = new ArrayList<>();
    for (String part : path.split("/")) {
      if (part.isEmpty() || part.equals(".")) continue;
      if (part.equals("..")) {
        if (!parts.isEmpty()) parts.remove(parts.size() - 1);
      } else parts.add(part);
    }
    String normal = "/" + String.join("/", parts);
    for (String alias : ALIASES)
      if (within(normal, alias)) return "/storage/emulated/0" + normal.substring(alias.length());
    return normal.replaceFirst(
        "^/mnt/runtime/(default|read|write|full)/emulated/", "/storage/emulated/");
  }

  private static boolean within(String path, String root) {
    return path.equals(root) || path.startsWith(root + "/");
  }

  public static boolean writeAllowed(String raw) {
    if (!absolutePath(raw) || hasGlob(raw) || Arrays.asList(raw.split("/")).contains(".."))
      return false;
    String path = normalize(raw), lower = path.toLowerCase(Locale.ROOT);
    if (path.startsWith("/data/local/tmp/")) return true;
    java.util.regex.Matcher root = java.util.regex.Pattern.compile(STORAGE).matcher(path);
    if (!root.matches() || root.group(1) == null || root.group(1).equals("/")) return false;
    String suffix = root.group(1).toLowerCase(Locale.ROOT);
    for (String protectedPath : PROTECTED)
      if (within(suffix, protectedPath) || within(protectedPath, suffix)) return false;
    return !lower.endsWith("/.");
  }

  private static String executable(String name) {
    if (!name.contains("/")) return name;
    return name.startsWith("/system/bin/") && name.lastIndexOf('/') == 11
        ? name.substring(12)
        : null;
  }

  public static List<String> split(String command) {
    if (command == null || command.length() > 8192)
      throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("命令为空或过长"));
    List<String> result = new ArrayList<>();
    StringBuilder word = new StringBuilder();
    char quote = 0;
    boolean present = false;
    for (int i = 0; i < command.length(); i++) {
      char c = command.charAt(i);
      if (c < 32 && c != '\t' || c == 127)
        throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("禁止多行脚本或控制字符"));
      if (quote != 0) {
        if (c == quote) quote = 0;
        else {
          if (quote == '"' && (c == '$' || c == '`' || c == '\\'))
            throw new IllegalArgumentException(
                com.deepseekharness.app.util.UiText.text("禁止变量、命令展开和转义脚本"));
          word.append(c);
        }
      } else if (c == '\'' || c == '"') {
        quote = c;
        present = true;
      } else if (Character.isWhitespace(c)) {
        if (present) {
          result.add(word.toString());
          word.setLength(0);
          present = false;
        }
      } else {
        if (";&|><$`(){}\\#".indexOf(c) >= 0)
          throw new IllegalArgumentException(
              com.deepseekharness.app.util.UiText.text("禁止脚本、管道、重定向、变量或嵌套命令；请分条执行"));
        word.append(c);
        present = true;
      }
    }
    if (quote != 0)
      throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("引号不完整"));
    if (present) result.add(word.toString());
    if (result.size() > 128)
      throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("参数过多"));
    return result;
  }
}
