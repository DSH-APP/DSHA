package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /app/install 的纯判定：文件名、大小、自身包拦截与私有快照目录轮换。
 *
 * <p>桥只把容器里的 APK 交给宿主系统安装器，实际安装仍由用户在系统界面确认；这里不做任何签名或来源信任判断。
 */
public final class BridgeApkInstall {
  /** 单个安装包上限；超过的包多半不是普通 APK，也会挤占 App 私有缓存。 */
  public static final long SIZE_LIMIT = 512L * 1024 * 1024;

  /** 系统安装器异步读取 content URI，快照至少保留这么久才允许清理。 */
  public static final long RETAIN_MS = 6L * 60 * 60 * 1000;

  /** 快照目录名：毫秒时间戳 + "-" + UUID，只有这种格式才会被自动清理。 */
  private static final Pattern SLOT =
      Pattern.compile(
          "^([0-9]{1,19})-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private BridgeApkInstall() {}

  /** 只接受 .apk 后缀（大小写不敏感）；分包 .apks/.xapk 需要会话安装，不在本端点范围。 */
  public static boolean apkName(String name) {
    if (name == null) return false;
    String low = name.toLowerCase(Locale.ROOT);
    return low.length() > 4 && low.endsWith(".apk") && !low.contains("/");
  }

  public static boolean sizeAllowed(long size) {
    return size > 0 && size <= SIZE_LIMIT;
  }

  /** 安装源沿用读取入口的域边界；共享存储只认当前用户根，guest 链接不能跨到共享/宿主域。 */
  public static boolean sourceAllowed(
      String requested,
      String canonical,
      String rootfsCanonical,
      String sharedCanonical,
      String mediaCanonical) {
    return !BridgePathPolicy.deniedReadRaw(requested)
        && !BridgePathPolicy.deniedReadCanonical(
            canonical,
            rootfsCanonical,
            sharedCanonical,
            mediaCanonical,
            BridgePathPolicy.readSharedDomain(requested));
  }

  /** 覆盖安装自身会被系统结束整个 App（DSH 与全部会话随之中断），应走应用内更新页。 */
  public static boolean selfPackage(String archivePackage, String ownPackage) {
    return archivePackage != null && archivePackage.equals(ownPackage);
  }

  /** 拉起安装器的方式：直接打开系统安装器，或发通知由用户点按后打开。 */
  public enum Launch {
    INSTALLER,
    NOTIFICATION
  }

  /** Android 15（API 35）起悬浮窗权限还需屏幕上有可见悬浮窗才豁免后台启动。 */
  public static final int OVERLAY_NEEDS_VISIBLE_WINDOW_SDK = 35;

  /**
   * 按 Android 后台启动 Activity 的豁免条件选择方式；任一条件不确定就走通知。
   *
   * <p>厂商另设的「后台弹出界面」开关无法检测，满足 AOSP 豁免时仍可能被静默拦截。
   */
  public static Launch launch(
      boolean appForeground, boolean accessibilityConnected, boolean overlayPermitted, int sdk) {
    if (appForeground || accessibilityConnected) return Launch.INSTALLER;
    if (overlayPermitted && sdk < OVERLAY_NEEDS_VISIBLE_WINDOW_SDK) return Launch.INSTALLER;
    return Launch.NOTIFICATION;
  }

  public static String slotName(long nowMs, String uuid) {
    return Long.toString(nowMs) + "-" + uuid;
  }

  /** 返回已过保留期、可删除的快照目录名；格式不符或时间在未来的目录一律保留。 */
  public static List<String> expiredSlots(List<String> names, long nowMs) {
    List<String> out = new ArrayList<>();
    if (names == null) return out;
    for (String name : names) {
      if (name == null) continue;
      Matcher m = SLOT.matcher(name);
      if (!m.matches()) continue;
      long created;
      try {
        created = Long.parseLong(m.group(1));
      } catch (NumberFormatException e) {
        continue;
      }
      if (created <= nowMs && nowMs - created >= RETAIN_MS) out.add(name);
    }
    return out;
  }
}
