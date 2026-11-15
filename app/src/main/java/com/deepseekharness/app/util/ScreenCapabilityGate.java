package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 识屏两种模式的准入判定（纯函数，不依赖 Android）。
 *
 * <p>实体识屏本期只认无障碍；虚拟识屏要求 Root / Shizuku / ADB 任一<b>真正可用</b>，无障碍只是增强
 * （节点与中文输入），缺失时置灰而不阻止开启。
 *
 * <p>通道判据分两档：
 *
 * <ul>
 *   <li>{@link #channelUsable} —— 发起新启动与模式准入用：Root 已启用且 su 存在；Shizuku 已授权且用户服务已绑定
 *       （仅 hasPermission 不够）；ADB 已启用、已获局域网授权且连接已验证（仅 adb_enabled 不够）。
 *   <li>{@link #channelAuthorized} —— 已运行会话的心跳与退出核验用：只要求授权仍在。真实存活由随后的 Core ping
 *       判定；若在这里也用严格判据，Shizuku 短暂重绑或 ADB 保活重连会把可用会话误判为断连。
 * </ul>
 */
public final class ScreenCapabilityGate {
  private ScreenCapabilityGate() {}

  public static final String ROOT = DeviceChannelOrder.ROOT;
  public static final String SHIZUKU = DeviceChannelOrder.SHIZUKU;
  public static final String ADB = DeviceChannelOrder.ADB;

  /** 缺项代码；界面按代码渲染文案，不在这里拼语言。 */
  public static final String MISSING_ACCESSIBILITY = "ACCESSIBILITY";

  public static final String MISSING_API = "API_30";
  public static final String MISSING_DEVICE_CHANNEL = "DEVICE_CHANNEL";
  public static final String MISSING_OVERLAY = "OVERLAY";

  public enum Shizuku {
    /** Binder 不在或未授权。 */
    NONE,
    /** 已授权，用户服务未绑定（可能正在绑定）。 */
    PERMITTED,
    /** 已授权且用户服务 Binder 存活。 */
    READY
  }

  /** 一次采样的设备状态；由 Android 侧组装，判定本身可单测。 */
  public static final class Inputs {
    public final int api;
    public final boolean accessibility;
    public final boolean rootEnabled;
    public final boolean rootPresent;
    public final Shizuku shizuku;
    public final boolean adbEnabled;
    public final boolean localNetwork;
    public final boolean adbConnected;
    public final boolean overlay;

    public Inputs(
        int api,
        boolean accessibility,
        boolean rootEnabled,
        boolean rootPresent,
        Shizuku shizuku,
        boolean adbEnabled,
        boolean localNetwork,
        boolean adbConnected,
        boolean overlay) {
      this.api = api;
      this.accessibility = accessibility;
      this.rootEnabled = rootEnabled;
      this.rootPresent = rootPresent;
      this.shizuku = shizuku == null ? Shizuku.NONE : shizuku;
      this.adbEnabled = adbEnabled;
      this.localNetwork = localNetwork;
      this.adbConnected = adbConnected;
      this.overlay = overlay;
    }
  }

  /** 某一模式的准入结论。 */
  public static final class Verdict {
    public final boolean allowed;

    /** 阻止开启的缺项（按代码）。 */
    public final List<String> missing;

    /** 不阻止开启、但功能会降级的缺项。 */
    public final List<String> degraded;

    /** 虚拟识屏将使用的通道；实体识屏或不可开启时为空串。 */
    public final String channel;

    Verdict(boolean allowed, List<String> missing, List<String> degraded, String channel) {
      this.allowed = allowed;
      this.missing = Collections.unmodifiableList(missing);
      this.degraded = Collections.unmodifiableList(degraded);
      this.channel = channel == null ? "" : channel;
    }
  }

  /** 通道尝试顺序；与设备命令共用 {@link DeviceChannelOrder#order}，这里不再单独决定。 */
  public static List<String> order(int api, boolean adbConnected) {
    return DeviceChannelOrder.order(api, adbConnected);
  }

  public static boolean channelUsable(String id, Inputs in) {
    switch (id == null ? "" : id) {
      case ROOT:
        return in.rootEnabled && in.rootPresent;
      case SHIZUKU:
        return in.shizuku == Shizuku.READY;
      case ADB:
        return in.adbEnabled && in.localNetwork && in.adbConnected;
      default:
        return false;
    }
  }

  public static boolean channelAuthorized(String id, Inputs in) {
    switch (id == null ? "" : id) {
      case ROOT:
        return in.rootEnabled && in.rootPresent;
      case SHIZUKU:
        return in.shizuku != Shizuku.NONE;
      case ADB:
        return in.adbEnabled && in.localNetwork;
      default:
        return false;
    }
  }

  /** 新启动选用的通道；没有真正可用的通道时返回空串。 */
  public static String chooseUsable(Inputs in) {
    for (String id : order(in.api, in.adbConnected)) if (channelUsable(id, in)) return id;
    return "";
  }

  public static Verdict physical(Inputs in) {
    List<String> missing = new ArrayList<>();
    List<String> degraded = new ArrayList<>();
    if (!in.accessibility) missing.add(MISSING_ACCESSIBILITY);
    if (!in.overlay) degraded.add(MISSING_OVERLAY);
    return new Verdict(missing.isEmpty(), missing, degraded, "");
  }

  public static Verdict virtual(Inputs in) {
    List<String> missing = new ArrayList<>();
    List<String> degraded = new ArrayList<>();
    if (!VirtualScreenPolicy.supported(in.api)) missing.add(MISSING_API);
    String channel = chooseUsable(in);
    if (channel.isEmpty()) missing.add(MISSING_DEVICE_CHANNEL);
    if (!in.accessibility) degraded.add(MISSING_ACCESSIBILITY);
    if (!in.overlay) degraded.add(MISSING_OVERLAY);
    boolean allowed = missing.isEmpty();
    return new Verdict(allowed, missing, degraded, allowed ? channel : "");
  }
}
