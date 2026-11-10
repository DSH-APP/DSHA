package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外接目标的「拖进来就装」计划（移植自大侠阿木 SuperADB v4.4.2 的处理流程）。
 *
 * <p>SuperADB 的做法（从其 Windows 程序内嵌字符串还原，非源码）：
 *
 * <ul>
 *   <li>APK：有 root 时 {@code su -c 'pm install -r <tmp.apk>'} 静默安装，否则 {@code adb install}
 *   <li>APKS / XAPK：解出分包，root 下走 {@code pm install-create / install-write / install-commit}
 *       会话，失败 {@code install-abandon}；XAPK 内 OBB 推到 {@code /sdcard/Android/obb/<包名>/}
 *   <li>ZIP：含 {@code module.prop} 视为模块，按检测到的 Magisk / KernelSU / APatch 分别调用
 *       {@code magisk --install-module}、{@code ksud module install}、{@code apd module install}；
 *       都没有则改为推到 {@code /sdcard/}
 *   <li>AAB：拒绝（发布格式，需先用 bundletool 生成 APKS）
 * </ul>
 *
 * <p>本类只生成「宿主侧 adb argv」，每步带风险级；不执行、不读文件。所有命令恒带 {@code -s <serial>}，
 * 远端 shell 片段一律经 {@link ShellQuote} 转义。纯逻辑，配单测。
 */
public final class ExternalInstallPlan {

  private ExternalInstallPlan() {}

  /** 文件种类（由文件名与 zip 条目判定）。 */
  public enum Kind {
    APK,
    SPLIT_BUNDLE,
    XAPK,
    MODULE_ZIP,
    PLAIN_ZIP,
    AAB,
    UNKNOWN
  }

  /** 目标机 root 管理器。 */
  public enum RootManager {
    NONE,
    MAGISK,
    KERNELSU,
    APATCH
  }

  /** 风险级与外接设备设计第 6 节一致：L1 可恢复，L2 可能导致无法开机。 */
  public record Step(List<String> argv, String level, String note) {}

  /** 远端临时目录。 */
  public static final String REMOTE_TMP = "/data/local/tmp/dsha_install";

  /** 分包上限：执行器 argv 上限 32，install-multiple 前缀占 10 个。 */
  public static final int MAX_SPLITS = 20;

  /** XAPK 内 OBB 上限。 */
  public static final int MAX_OBBS = 8;

  private static final Pattern SERIAL = Pattern.compile("[A-Za-z0-9._:\\-]{1,64}");
  private static final Pattern PACKAGE =
      Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
  private static final Pattern OBB =
      Pattern.compile("(main|patch)\\.[0-9]+\\.[A-Za-z0-9_.]+\\.obb");
  private static final Pattern SESSION_ID = Pattern.compile("\\[(\\d{1,10})\\]");
  private static final String R = Pattern.quote(REMOTE_TMP);
  private static final Pattern SU_INNER =
      Pattern.compile(
          "id -u|magisk -V|ksud -V|apd -V"
              + "|pm install -r '"
              + R
              + "/a000\\.apk'"
              + "|pm install-create -r -S [1-9][0-9]{0,15}"
              + "|pm install-(?:commit|abandon) [0-9]{1,10}"
              + "|(?:magisk --install-module|ksud module install|apd module install) '"
              + R
              + "/module\\.zip'");
  private static final Pattern SU_WRITE =
      Pattern.compile(
          "pm install-write -S [1-9][0-9]{0,15} [0-9]{1,10} a([0-9]{3})\\.apk '"
              + R
              + "/a([0-9]{3})\\.apk'");
  private static final Pattern REMOTE_PUSH =
      Pattern.compile(
          R + "/(?:a[0-9]{3}\\.apk|module\\.zip)" + "|/sdcard/[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");

  /** 判定文件种类。{@code zipEntries} 为 null 表示不是 zip 或未读取。 */
  public static Kind kindOf(String fileName, List<String> zipEntries) {
    String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
    if (n.endsWith(".aab")) return Kind.AAB;
    if (n.endsWith(".apk")) return Kind.APK;
    if (n.endsWith(".apks")) return Kind.SPLIT_BUNDLE;
    if (n.endsWith(".xapk")) return Kind.XAPK;
    if (n.endsWith(".zip")) {
      if (zipEntries != null && zipEntries.contains("module.prop")) return Kind.MODULE_ZIP;
      return Kind.PLAIN_ZIP;
    }
    return Kind.UNKNOWN;
  }

  /** 由 {@code magisk -V} / {@code ksud -V} / {@code apd -V} 的探测结果选管理器；顺序同 SuperADB。 */
  public static RootManager detect(boolean magiskOk, boolean ksudOk, boolean apdOk) {
    if (magiskOk) return RootManager.MAGISK;
    if (ksudOk) return RootManager.KERNELSU;
    if (apdOk) return RootManager.APATCH;
    return RootManager.NONE;
  }

  /** root 与管理器探测命令（只读，L0）。退出码 0 视为存在。 */
  public static List<List<String>> detectCommands(String serial) {
    requireSerial(serial);
    List<List<String>> out = new ArrayList<>();
    out.add(adbShell(serial, su("id -u")));
    out.add(adbShell(serial, su("magisk -V")));
    out.add(adbShell(serial, su("ksud -V")));
    out.add(adbShell(serial, su("apd -V")));
    return out;
  }

  /** 单个 APK。{@code root} 时推到临时区后 pm install 静默安装，否则 adb install -r。 */
  public static List<Step> apk(String serial, String localApk, boolean root) {
    requireSerial(serial);
    requireLocal(localApk);
    List<Step> steps = new ArrayList<>();
    if (!root) {
      steps.add(new Step(adb(serial, "install", "-r", localApk), "L1", "adb install（目标机可能弹确认）"));
      return steps;
    }
    String remote = remoteSplit(0);
    steps.add(mkTmp(serial));
    steps.add(new Step(adb(serial, "push", localApk, remote), "L1", "推送 APK"));
    steps.add(
        new Step(
            adbShell(serial, su("pm install -r " + ShellQuote.arg(remote))), "L1", "root 静默安装"));
    steps.add(cleanup(serial));
    return steps;
  }

  /** 分包（APKS / XAPK 解出的 base+split），非 root：{@code adb install-multiple -r}。 */
  public static List<Step> splitsWithoutRoot(String serial, List<String> localApks) {
    requireSerial(serial);
    requireNonEmpty(localApks);
    List<String> argv = adb(serial, "install-multiple", "-r");
    if (localApks.size() > MAX_SPLITS) throw new IllegalArgumentException("TOO_MANY_SPLITS");
    for (String apk : localApks) {
      requireLocal(apk);
      argv.add(apk);
    }
    return List.of(new Step(argv, "L1", "adb install-multiple"));
  }

  /** root 分包第一段：推送全部分包并创建会话。之后用 {@link #parseSessionId} 解析输出。 */
  public static List<Step> sessionPrepare(String serial, List<String> localApks, long totalBytes) {
    requireSerial(serial);
    requireNonEmpty(localApks);
    if (totalBytes <= 0) throw new IllegalArgumentException("SIZE");
    if (localApks.size() > MAX_SPLITS) throw new IllegalArgumentException("TOO_MANY_SPLITS");
    List<Step> steps = new ArrayList<>();
    steps.add(mkTmp(serial));
    for (int i = 0; i < localApks.size(); i++) {
      requireLocal(localApks.get(i));
      steps.add(new Step(adb(serial, "push", localApks.get(i), remoteSplit(i)), "L1", "推送分包 " + i));
    }
    steps.add(
        new Step(
            adbShell(serial, su("pm install-create -r -S " + totalBytes)),
            "L1",
            "创建安装会话，输出形如 Success: created install session [1234]"));
    return steps;
  }

  /** 解析 {@code pm install-create} 输出中的会话 ID；无法识别返回 -1（调用方须停止，不猜）。 */
  public static long parseSessionId(String output) {
    if (output == null || !output.contains("Success")) return -1;
    Matcher m = SESSION_ID.matcher(output);
    return m.find() ? Long.parseLong(m.group(1)) : -1;
  }

  /**
   * root 分包第二段：逐个 install-write 后 commit。{@code sizes[i]} 为第 i 个分包字节数。 任一步失败时调用方执行
   * {@link #sessionAbandon}，不重放已提交的会话。
   */
  public static List<Step> sessionSteps(String serial, long sessionId, long[] sizes) {
    requireSerial(serial);
    if (sessionId < 0) throw new IllegalArgumentException("SESSION");
    if (sizes == null || sizes.length == 0 || sizes.length > MAX_SPLITS)
      throw new IllegalArgumentException("NO_APK");
    List<Step> steps = new ArrayList<>();
    for (int i = 0; i < sizes.length; i++) {
      if (sizes[i] <= 0) throw new IllegalArgumentException("SIZE");
      String name = String.format(Locale.ROOT, "a%03d.apk", i);
      steps.add(
          new Step(
              adbShell(
                  serial,
                  su(
                      "pm install-write -S "
                          + sizes[i]
                          + " "
                          + sessionId
                          + " "
                          + name
                          + " "
                          + ShellQuote.arg(remoteSplit(i)))),
              "L1",
              "写入分包 " + name));
    }
    steps.add(new Step(adbShell(serial, su("pm install-commit " + sessionId)), "L1", "提交安装会话"));
    steps.add(cleanup(serial));
    return steps;
  }

  /** 放弃会话（失败收尾）。 */
  public static Step sessionAbandon(String serial, long sessionId) {
    requireSerial(serial);
    if (sessionId < 0) throw new IllegalArgumentException("SESSION");
    return new Step(adbShell(serial, su("pm install-abandon " + sessionId)), "L1", "放弃安装会话");
  }

  /** XAPK 的 OBB：推到 {@code /sdcard/Android/obb/<包名>/<文件名>}。 */
  public static List<Step> obb(String serial, String packageName, String localObb, String obbName) {
    requireSerial(serial);
    requireLocal(localObb);
    if (packageName == null || !PACKAGE.matcher(packageName).matches())
      throw new IllegalArgumentException("PACKAGE");
    if (obbName == null || !OBB.matcher(obbName).matches())
      throw new IllegalArgumentException("OBB");
    String dir = "/sdcard/Android/obb/" + packageName;
    List<Step> steps = new ArrayList<>();
    steps.add(new Step(adbShell(serial, "mkdir -p " + ShellQuote.arg(dir)), "L1", "建 OBB 目录"));
    steps.add(new Step(adb(serial, "push", localObb, dir + "/" + obbName), "L1", "推送 OBB"));
    return steps;
  }

  /**
   * 模块 ZIP。模块在开机早期执行，坏模块可让目标无法开机，按 L2 确认。没有 root 管理器时退化为推到 {@code /sdcard/}（与
   * SuperADB 相同），不尝试其它安装方式。
   */
  public static List<Step> module(String serial, String localZip, RootManager manager) {
    requireSerial(serial);
    requireLocal(localZip);
    List<Step> steps = new ArrayList<>();
    String base = baseName(localZip);
    if (manager == null || manager == RootManager.NONE) {
      steps.add(
          new Step(
              adb(serial, "push", localZip, "/sdcard/" + base),
              "L1",
              "未检测到 root 管理器，改为推送到 /sdcard/"));
      return steps;
    }
    String remote = REMOTE_TMP + "/module.zip";
    String q = ShellQuote.arg(remote);
    String cmd =
        switch (manager) {
          case MAGISK -> "magisk --install-module " + q;
          case KERNELSU -> "ksud module install " + q;
          case APATCH -> "apd module install " + q;
          default -> throw new IllegalStateException();
        };
    steps.add(mkTmp(serial));
    steps.add(new Step(adb(serial, "push", localZip, remote), "L1", "推送模块"));
    steps.add(new Step(adbShell(serial, su(cmd)), "L2", "安装模块（重启后生效；坏模块可能导致无法开机）"));
    steps.add(cleanup(serial));
    return steps;
  }

  /** 普通 ZIP：推到 {@code /sdcard/}。 */
  public static List<Step> plainZip(String serial, String localZip) {
    requireSerial(serial);
    requireLocal(localZip);
    return List.of(
        new Step(
            adb(serial, "push", localZip, "/sdcard/" + baseName(localZip)), "L1", "推送到 /sdcard/"));
  }

  /**
   * 分包压缩包里要装的 APK 条目。bundletool 产物（含 {@code splits/}）只取 {@code splits/*.apk}，不取
   * {@code standalones/}；其它（SAI / XAPK）取根目录 {@code *.apk}。base 排在最前，其余按名称排序；超过上限返回空。
   */
  public static List<String> bundleApkEntries(List<String> entries) {
    List<String> out = new ArrayList<>();
    if (entries == null) return out;
    boolean bundletool =
        entries.stream().anyMatch(e -> e.startsWith("splits/") && e.endsWith(".apk"));
    for (String e : entries) {
      if (e == null || !e.toLowerCase(Locale.ROOT).endsWith(".apk") || e.contains("..")) continue;
      if (bundletool ? e.matches("splits/[^/]+\\.apk") : e.matches("[^/]+\\.(?i:apk)")) out.add(e);
    }
    out.sort(
        (a, b) -> {
          boolean ab = isBase(a), bb = isBase(b);
          return ab != bb ? (ab ? -1 : 1) : a.compareTo(b);
        });
    return out.size() > MAX_SPLITS ? new ArrayList<>() : out;
  }

  private static boolean isBase(String entry) {
    String n = entry.substring(entry.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    return n.equals("base.apk") || n.equals("base-master.apk");
  }

  /** XAPK 的 OBB 条目 {@code Android/obb/<包名>/<文件名>} → {包名, 文件名}；不符合返回 null。 */
  public static String[] xapkObb(String entry) {
    if (entry == null) return null;
    Matcher m = Pattern.compile("Android/obb/([^/]+)/([^/]+)").matcher(entry);
    if (!m.matches()
        || !PACKAGE.matcher(m.group(1)).matches()
        || !OBB.matcher(m.group(2)).matches()
        || !m.group(2).contains(m.group(1))) return null;
    return new String[] {m.group(1), m.group(2)};
  }

  /**
   * 执行器反向核验：{@code adb shell} 的远端字符串必须与本类生成的形状一致。不认识的一律拒绝；这里不是通用 shell
   * 白名单，只为本计划生成的步骤放行。
   */
  public static boolean plannedRemoteShell(String command) {
    if (command == null) return false;
    String obb = "mkdir -p '/sdcard/Android/obb/";
    if (command.equals("mkdir -p " + ShellQuote.arg(REMOTE_TMP))
        || command.equals("rm -rf " + ShellQuote.arg(REMOTE_TMP))) return true;
    if (command.startsWith(obb) && command.endsWith("'")) {
      String pkg = command.substring(obb.length(), command.length() - 1);
      return PACKAGE.matcher(pkg).matches();
    }
    String prefix = "su -c ";
    if (!command.startsWith(prefix)) return false;
    String quoted = command.substring(prefix.length());
    if (quoted.length() < 2 || !quoted.startsWith("'") || !quoted.endsWith("'")) return false;
    String inner = quoted.substring(1, quoted.length() - 1).replace("'\\''", "'");
    // 往返一致才算规范形式，杜绝拼接出的多段引号
    if (!ShellQuote.arg(inner).equals(quoted)) return false;
    if (SU_INNER.matcher(inner).matches()) return true;
    Matcher write = SU_WRITE.matcher(inner);
    return write.matches() && write.group(1).equals(write.group(2));
  }

  /** 执行器反向核验：{@code adb push} 的远端路径。 */
  public static boolean plannedRemotePush(String remote) {
    if (remote == null) return false;
    if (REMOTE_PUSH.matcher(remote).matches()) return true;
    String prefix = "/sdcard/Android/obb/";
    if (!remote.startsWith(prefix)) return false;
    String[] parts = remote.substring(prefix.length()).split("/", -1);
    return parts.length == 2
        && PACKAGE.matcher(parts[0]).matches()
        && OBB.matcher(parts[1]).matches()
        && parts[1].contains(parts[0]);
  }

  /** 远端 {@code su -c '<cmd>'}；cmd 整体作为一个参数被单引号封住。 */
  static String su(String cmd) {
    return "su -c " + ShellQuote.arg(cmd);
  }

  private static String remoteSplit(int i) {
    return REMOTE_TMP + String.format(Locale.ROOT, "/a%03d.apk", i);
  }

  private static Step mkTmp(String serial) {
    return new Step(adbShell(serial, "mkdir -p " + ShellQuote.arg(REMOTE_TMP)), "L1", "建临时目录");
  }

  private static Step cleanup(String serial) {
    return new Step(adbShell(serial, "rm -rf " + ShellQuote.arg(REMOTE_TMP)), "L1", "清理临时目录");
  }

  private static List<String> adb(String serial, String... args) {
    List<String> argv = new ArrayList<>(List.of("adb", "-s", serial));
    argv.addAll(List.of(args));
    return argv;
  }

  /** adb shell 把其后参数用空格拼接交给远端 sh，所以远端命令以单个已转义字符串传入。 */
  private static List<String> adbShell(String serial, String remoteCommand) {
    return adb(serial, "shell", remoteCommand);
  }

  /** 远端文件名：去掉导入区的 UUID 前缀，避免 /sdcard 下出现一串编号。 */
  private static String baseName(String path) {
    String b = path.substring(path.lastIndexOf('/') + 1);
    if (b.matches("[0-9a-f-]{36}-.+")) b = b.substring(37);
    if (b.isEmpty() || b.startsWith(".") || !b.matches("[A-Za-z0-9._\\-]{1,128}"))
      throw new IllegalArgumentException("FILE_NAME");
    return b;
  }

  private static void requireSerial(String serial) {
    if (serial == null || serial.startsWith("-") || !SERIAL.matcher(serial).matches())
      throw new IllegalArgumentException("SERIAL");
  }

  private static void requireLocal(String path) {
    if (path == null
        || !path.startsWith("/")
        || path.contains("\n")
        || path.contains("\0")
        || ("/" + path + "/").contains("/../")) throw new IllegalArgumentException("LOCAL_PATH");
  }

  private static void requireNonEmpty(List<String> list) {
    if (list == null || list.isEmpty()) throw new IllegalArgumentException("NO_APK");
  }
}
