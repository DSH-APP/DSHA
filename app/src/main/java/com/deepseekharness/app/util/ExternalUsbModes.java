package com.deepseekharness.app.util;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 外接 USB 设备的模式识别（“驱动”层的 Android 端移植）。
 *
 * <p>参考：大侠阿木一加全能工具箱的「安装驱动」步骤（Windows 上分装 OPPO/一加 ADB 驱动、高通 / MTK
 * Fastboot 驱动，按 VID/PID 绑定 WinUSB）。Android 宿主没有驱动安装这一步：内核按 USB 接口暴露
 * {@code /dev/bus/usb}，App 经 {@code UsbManager} 授权后即可通信。真正需要移植的是驱动 INF
 * 里隐含的那张「哪个 VID/PID/接口 = 哪种模式」的识别表，本类就是这张表。
 *
 * <p>识别优先看接口三元组（class/subclass/protocol），它与厂商无关：
 *
 * <ul>
 *   <li>ADB：{@code 0xFF/0x42/0x01}（AOSP {@code ADB_CLASS/ADB_SUBCLASS/ADB_PROTOCOL}）
 *   <li>Fastboot / fastbootd：{@code 0xFF/0x42/0x03}（AOSP fastboot usb 定义）
 * </ul>
 *
 * <p>厂商私有下载模式没有标准接口，只能按 VID/PID 识别；它们只做「认出来并提示」，不进入
 * adb/fastboot 执行链（9008、BROM 等协议见设计文档非目标）。
 *
 * <p>纯逻辑，不 import Android API。
 */
public final class ExternalUsbModes {

  private ExternalUsbModes() {}

  public static final int CLASS_VENDOR_SPECIFIC = 0xFF;
  public static final int ANDROID_SUBCLASS = 0x42;
  public static final int ADB_PROTOCOL = 0x01;
  public static final int FASTBOOT_PROTOCOL = 0x03;

  /** 识别出的连接模式。{@code toolchain} 为 true 才允许交给 adb / fastboot。 */
  public enum Mode {
    ADB("adb", true),
    FASTBOOT("fastboot", true),
    QUALCOMM_EDL("qualcomm-edl", false),
    MTK_BROM("mtk-brom", false),
    MTK_PRELOADER("mtk-preloader", false),
    UNISOC_DOWNLOAD("unisoc-download", false),
    SAMSUNG_ODIN("samsung-odin", false),
    MTP_OR_OTHER("other", false);

    public final String id;
    public final boolean toolchain;

    Mode(String id, boolean toolchain) {
      this.id = id;
      this.toolchain = toolchain;
    }
  }

  /** 单个 USB 接口描述（来自 UsbInterface 或 sysfs bInterfaceClass 等）。 */
  public record Iface(int cls, int subclass, int protocol) {}

  /** 识别结果。 */
  public record Result(Mode mode, String vendor, String hint) {}

  /**
   * 常见手机厂商 VID → 名称。数据来源：Google Android 官方 OEM USB 驱动表与各厂商驱动 INF；
   * 只用于显示，不参与安全判定（VID 可被任何设备声称）。
   */
  private static final Map<Integer, String> VENDORS =
      Map.ofEntries(
          Map.entry(0x18D1, "Google"),
          Map.entry(0x22D9, "OPPO/OnePlus/realme"),
          Map.entry(0x2A70, "OnePlus"),
          Map.entry(0x2717, "Xiaomi"),
          Map.entry(0x04E8, "Samsung"),
          Map.entry(0x12D1, "Huawei"),
          Map.entry(0x2D95, "vivo"),
          Map.entry(0x17EF, "Lenovo"),
          Map.entry(0x22B8, "Motorola"),
          Map.entry(0x0FCE, "Sony"),
          Map.entry(0x1004, "LG"),
          Map.entry(0x19D2, "ZTE"),
          Map.entry(0x2B4C, "ZTE/nubia"),
          Map.entry(0x05C6, "Qualcomm"),
          Map.entry(0x0E8D, "MediaTek"),
          Map.entry(0x1782, "Unisoc"));

  /** 厂商显示名；未知返回 VID 十六进制。 */
  public static String vendorName(int vid) {
    String name = VENDORS.get(vid & 0xFFFF);
    return name != null ? name : String.format(Locale.ROOT, "VID %04X", vid & 0xFFFF);
  }

  /**
   * 识别模式。接口三元组优先：同一设备在复合模式（MTP+ADB）下也能认出 ADB；没有标准接口时 才按私有下载模式的
   * VID/PID 表判断。
   */
  public static Result classify(int vid, int pid, List<Iface> interfaces) {
    vid &= 0xFFFF;
    pid &= 0xFFFF;
    String vendor = vendorName(vid);
    List<Iface> list = interfaces == null ? List.of() : interfaces;
    for (Iface i : list) {
      if (isAndroid(i, FASTBOOT_PROTOCOL))
        return new Result(
            Mode.FASTBOOT, vendor, "fastboot / fastbootd，用 fastboot getvar is-userspace 区分");
    }
    for (Iface i : list) {
      if (isAndroid(i, ADB_PROTOCOL))
        return new Result(Mode.ADB, vendor, "adb（系统、recovery 或 sideload，用 adb get-state 区分）");
    }
    if (vid == 0x05C6 && pid == 0x9008)
      return new Result(Mode.QUALCOMM_EDL, vendor, "高通 9008（EDL / Sahara），不经 adb/fastboot，不在支持范围");
    if (vid == 0x0E8D && pid == 0x0003)
      return new Result(Mode.MTK_BROM, vendor, "联发科 BROM，不经 adb/fastboot，不在支持范围");
    if (vid == 0x0E8D && (pid == 0x2000 || pid == 0x2001))
      return new Result(Mode.MTK_PRELOADER, vendor, "联发科 Preloader，不经 adb/fastboot，不在支持范围");
    if (vid == 0x1782 && pid == 0x4D00)
      return new Result(Mode.UNISOC_DOWNLOAD, vendor, "展锐下载模式，不在支持范围");
    if (vid == 0x04E8 && pid == 0x685D)
      return new Result(Mode.SAMSUNG_ODIN, vendor, "三星 Download（Odin），不经 fastboot，不在支持范围");
    return new Result(Mode.MTP_OR_OTHER, vendor, "未发现 adb / fastboot 接口：在目标机开启 USB 调试并选择「传输文件」");
  }

  private static boolean isAndroid(Iface i, int protocol) {
    return i != null
        && i.cls() == CLASS_VENDOR_SPECIFIC
        && i.subclass() == ANDROID_SUBCLASS
        && i.protocol() == protocol;
  }
}
