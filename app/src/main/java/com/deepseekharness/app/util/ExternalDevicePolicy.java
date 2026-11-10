package com.deepseekharness.app.util;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure policy for the separately-authorized external Android-device channel. */
public final class ExternalDevicePolicy {
  public enum Risk {
    L0,
    L1,
    L2,
    L3
  }

  public enum Operation {
    STATUS,
    DEVICES,
    SHELL,
    REBOOT,
    INSTALL,
    CONNECT,
    PAIR,
    SCREENSHOT,
    GETVAR,
    FLASH,
    SIDELOAD,
    SET_ACTIVE
  }

  public static final class Plan {
    public final Operation operation;
    public final Risk risk;
    public final String partition;
    public final String value;
    public final String reason;

    private Plan(Operation operation, Risk risk, String partition, String value, String reason) {
      this.operation = operation;
      this.risk = risk;
      this.partition = partition;
      this.value = value;
      this.reason = reason;
    }

    public boolean allowed() {
      return reason.isEmpty();
    }
  }

  private static final Pattern SERIAL = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern PARTITION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
  private static final Set<String> FLASHABLE =
      Set.of(
          "boot",
          "init_boot",
          "vendor_boot",
          "dtbo",
          "recovery",
          "vbmeta",
          "vbmeta_system",
          "vbmeta_vendor",
          "system",
          "vendor",
          "product",
          "odm",
          "super");
  private static final Set<String> READ_ONLY_SHELL =
      Set.of(
          "cat",
          "getprop",
          "getevent",
          "id",
          "ls",
          "logcat",
          "pm",
          "dumpsys",
          "wm",
          "which",
          "uname",
          "toybox",
          "pwd",
          "df",
          "du",
          "printenv");

  private ExternalDevicePolicy() {}

  public static Plan plan(String operation, String partition, String value) {
    String op = operation == null ? "" : operation.trim().toLowerCase(Locale.ROOT);
    String p = partition == null ? "" : partition.trim();
    String v = value == null ? "" : value;
    switch (op) {
      case "status":
        return new Plan(Operation.STATUS, Risk.L0, "", "", "");
      case "devices":
        return new Plan(Operation.DEVICES, Risk.L0, "", "", "");
      case "shell":
        if (v.isEmpty()) return deny("外接 shell 命令为空");
        if (v.length() > 8192 || v.indexOf('\0') >= 0) return deny("外接 shell 命令过长或包含 NUL");
        String rejected =
            ExternalToolPolicy.diagnosticReadRejection(java.util.List.of(v.trim().split("\\s+")));
        if (rejected != null) return deny(rejected);
        return new Plan(Operation.SHELL, shellRisk(v), "", v, "");
      case "reboot":
        // 只为进入 bootloader 做只读预检及返回系统；edl / sideload 不开放。
        if (!Set.of("system", "bootloader", "recovery", "fastboot", "fastbootd").contains(v))
          return deny("重启目标只能是 system / bootloader / recovery / fastboot / fastbootd");
        return new Plan(Operation.REBOOT, Risk.L1, "", v, "");
      case "install":
        // 风险级由宿主读取导入文件后确定（APK/分包 L1，模块 L2）；这里只校验导入名形状。
        if (!ExternalImportQuota.isImportName(v)) return deny("安装文件必须来自外接设备导入区");
        return new Plan(Operation.INSTALL, Risk.L1, "", v, "");
      case "connect":
        if (!isWirelessTarget(v)) return deny("无线目标必须是 IPv4:端口，且不能是本机回环地址");
        return new Plan(Operation.CONNECT, Risk.L0, "", v, "");
      case "pair":
        if (pairTarget(v) == null) return deny("配对参数必须是 IPv4:端口 与 6 位配对码，以空格分隔");
        return new Plan(Operation.PAIR, Risk.L1, "", v, "");
      case "screenshot":
        return new Plan(Operation.SCREENSHOT, Risk.L0, "", "", "");
      case "getvar":
        return new Plan(Operation.GETVAR, Risk.L0, "", "", "");
      case "flash":
        if (!validPartition(p) || !FLASHABLE.contains(p)) return deny("不支持该刷写分区");
        if (v.isEmpty()) return deny("刷写镜像不能为空");
        return new Plan(Operation.FLASH, Risk.L2, p, v, "");
      case "sideload":
        if (v.isEmpty()) return deny("OTA 文件不能为空");
        return new Plan(Operation.SIDELOAD, Risk.L2, "", v, "");
      case "set_active":
        if (!v.matches("[ab]")) return deny("槽位只能是 a 或 b");
        return new Plan(Operation.SET_ACTIVE, Risk.L2, "", v, "");
      case "unlock":
      case "lock":
      case "erase":
      case "disable-verity":
      case "update":
        return deny("该高风险操作尚未开放；请使用厂商官方工具完成");
      default:
        return deny("不认识的外接设备操作");
    }
  }

  public static boolean validSerial(String serial) {
    if (serial == null || !SERIAL.matcher(serial).matches()) return false;
    String lower = serial.toLowerCase(Locale.ROOT);
    return !lower.equals("localhost")
        && !lower.equals("127.0.0.1")
        && !lower.equals("::1")
        && !lower.startsWith("127.")
        && !lower.matches("[0-9a-f:]+:[0-9]+")
        && !lower.matches("[0-9a-f.]+:[0-9]+");
  }

  /** External fastboot operations are intentionally USB-only in the first implementation. */
  public static boolean isUsbSerial(String serial) {
    return validSerial(serial) && !serial.contains(":");
  }

  /**
   * 无线调试目标：仅接受 IPv4 字面量加端口（不解析主机名），拒绝回环、0.0.0.0、组播与广播。
   * 是否指向本机由宿主再按本机网卡地址与设备指纹排除；无线目标只用于 L0–L1 调试与安装，不进入 fastboot。
   */
  public static boolean isWirelessTarget(String target) {
    if (target == null) return false;
    java.util.regex.Matcher m =
        Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3}):(\\d{1,5})")
            .matcher(target);
    if (!m.matches()) return false;
    int[] octet = new int[4];
    for (int i = 0; i < 4; i++) {
      String part = m.group(i + 1);
      if (part.length() > 1 && part.startsWith("0")) return false;
      octet[i] = Integer.parseInt(part);
      if (octet[i] > 255) return false;
    }
    int port = Integer.parseInt(m.group(5));
    if (port < 1 || port > 65535) return false;
    if (octet[0] == 127 || octet[0] == 0 || octet[0] >= 224) return false;
    return true;
  }

  /** adb 目标：USB 序列号或无线 IPv4:端口。 */
  public static boolean isAdbTarget(String target) {
    return isUsbSerial(target) || isWirelessTarget(target);
  }

  /** 解析 {@code "<ip:port> <6位配对码>"}；不合法返回 null。返回 {目标, 配对码}。 */
  public static String[] pairTarget(String value) {
    if (value == null) return null;
    String[] parts = value.trim().split("\\s+");
    if (parts.length != 2 || !isWirelessTarget(parts[0]) || !parts[1].matches("[0-9]{6}"))
      return null;
    return parts;
  }

  public static boolean validPartition(String partition) {
    return partition != null && PARTITION.matcher(partition).matches();
  }

  public static boolean supportedFlashPartition(String partition) {
    return validPartition(partition) && FLASHABLE.contains(partition);
  }

  public static String summary(
      Plan plan, String serial, String fileName, String sha256, long size) {
    if (plan == null || !plan.allowed()) return plan == null ? "外接设备操作不可用" : plan.reason;
    String target = serial == null ? "未绑定目标" : serial;
    switch (plan.operation) {
      case FLASH:
        return String.format(
            Locale.ROOT,
            "外接目标 %s：刷写 %s，文件 %s，SHA-256 %s，%d bytes",
            target,
            plan.partition,
            fileName,
            sha256,
            size);
      case SIDELOAD:
        return String.format(
            Locale.ROOT,
            "外接目标 %s：通过 ADB sideload 安装 %s，SHA-256 %s，%d bytes",
            target,
            fileName,
            sha256,
            size);
      case INSTALL:
        return String.format(
            Locale.ROOT, "外接目标 %s：安装 %s，SHA-256 %s，%d bytes", target, fileName, sha256, size);
      case PAIR:
        return String.format(Locale.ROOT, "与无线调试目标 %s 配对（配对码来自目标机屏幕）", pairTarget(plan.value)[0]);
      case REBOOT:
        return String.format(Locale.ROOT, "外接目标 %s：重启到 %s", target, plan.value);
      case SET_ACTIVE:
        return String.format(Locale.ROOT, "外接目标 %s：切换活动槽位到 %s", target, plan.value);
      case SHELL:
        return String.format(Locale.ROOT, "外接目标 %s：执行调试 shell 命令：%s", target, plan.value);
      default:
        return String.format(Locale.ROOT, "外接目标 %s：%s", target, plan.operation.name());
    }
  }

  private static Risk shellRisk(String command) {
    String flat = command.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    String[] l3 = {
      "fastboot",
      "flash",
      "flash_image",
      "of=/dev/block",
      ">/dev/block",
      "> /dev/block",
      "dd ",
      "erase",
      "wipe",
      "format",
      "recovery --wipe",
      "reboot edl",
      "ksud boot-patch",
      "magisk --install",
      "update_engine_client",
      "blockdev --setrw",
      "setprop",
      "mount ",
      "umount "
    };
    for (String marker : l3) if (flat.contains(marker)) return Risk.L3;
    String[] l2 = {
      "reboot",
      "pm install",
      "pm uninstall",
      "pm clear",
      "cmd package",
      "settings put",
      "bootctl set-active",
      "install-existing",
      "sideload"
    };
    for (String marker : l2) if (flat.contains(marker)) return Risk.L2;
    String first = flat.split(" ", 2)[0];
    return READ_ONLY_SHELL.contains(first) ? Risk.L0 : Risk.L1;
  }

  private static Plan deny(String reason) {
    return new Plan(null, Risk.L3, "", "", reason);
  }
}
