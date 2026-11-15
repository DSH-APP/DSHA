package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设备通道的唯一原生选择顺序（纯函数，不依赖 Android）。
 *
 * <p>设备命令（{@code /device/execute}）与虚拟屏 Core 启动都从这里取顺序，避免同一台设备上「走哪条」答案不同。
 * 顺序焊死在壳上：用户只能通过原生「设备能力授权」开关决定哪些通道参与，不能调整先后。
 *
 * <p>只选一次：执行失败返回该通道自己的失败码，调用方不得换下一条通道重放。
 */
public final class DeviceChannelOrder {
  private DeviceChannelOrder() {}

  public static final String ROOT = "root";
  public static final String SHIZUKU = "shizuku";
  public static final String ADB = "adb";

  /**
   * 通道尝试顺序：Root 优先。Android 17（API 37）上 Shizuku 用户服务可能在进入 DSHA 前退出，ADB 连接已验证时排在
   * Shizuku 之前；其余情况 Shizuku 在 ADB 之前。
   */
  public static List<String> order(int api, boolean adbConnected) {
    List<String> order = new ArrayList<>();
    order.add(ROOT);
    if (api >= 37 && adbConnected) {
      order.add(ADB);
      order.add(SHIZUKU);
    } else {
      order.add(SHIZUKU);
      order.add(ADB);
    }
    return Collections.unmodifiableList(order);
  }

  /** 设备命令类别：决定哪些通道有资格执行。 */
  public enum Kind {
    /** 白名单内的普通命令：三条通道都可。 */
    NORMAL,
    /** 短信读取：需要指定 Android 用户，Shizuku 不支持，只走 Root / ADB。 */
    SMS,
    /** 放宽档原始 shell：只允许 root 身份，即本机 su 或 ADB + su；不能落到 Shizuku，也不降级。 */
    SHELL
  }

  /** 选通道结论；{@link #channel} 为空串表示没有可用通道。 */
  public static final class Choice {
    public final String channel;

    /** 走 ADB 时是否由 guest 以 {@code su -c} 执行。 */
    public final boolean su;

    Choice(String channel, boolean su) {
      this.channel = channel == null ? "" : channel;
      this.su = su;
    }

    public boolean available() {
      return !channel.isEmpty();
    }
  }

  /** 该类别与强制参数下允许参与选择的通道（未排序）。 */
  static boolean eligible(String id, Kind kind, boolean forceRoot, boolean forceAdb) {
    if (forceAdb) return ADB.equals(id);
    if (SHIZUKU.equals(id)) return kind == Kind.NORMAL && !forceRoot;
    return ROOT.equals(id) || ADB.equals(id);
  }

  /**
   * 设备命令选通道，分两档：先按严格判据（{@link ScreenCapabilityGate#channelUsable}）沿统一顺序找；都不可用时，再按宽判据
   * （{@link ScreenCapabilityGate#channelAuthorized}：Shizuku 已授权待绑定、ADB 已启用且有局域网授权待连接）沿同一顺序找。
   * 宽档通道由执行器在发送前自行等待就绪，未就绪即返回未发送失败，不构成重放。
   */
  public static Choice forDeviceCommand(
      ScreenCapabilityGate.Inputs in, Kind kind, boolean forceRoot, boolean forceAdb) {
    Kind k = kind == null ? Kind.NORMAL : kind;
    List<String> order = order(in.api, in.adbConnected);
    String chosen = "";
    for (String id : order)
      if (eligible(id, k, forceRoot, forceAdb) && ScreenCapabilityGate.channelUsable(id, in)) {
        chosen = id;
        break;
      }
    if (chosen.isEmpty())
      for (String id : order)
        if (eligible(id, k, forceRoot, forceAdb)
            && ScreenCapabilityGate.channelAuthorized(id, in)) {
          chosen = id;
          break;
        }
    boolean su = ADB.equals(chosen) && (k == Kind.SHELL || forceRoot);
    return new Choice(chosen, su);
  }
}
