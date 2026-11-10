package com.deepseekharness.app.vscreen;

import android.content.Context;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.screen.ScreenCapabilities;
import com.deepseekharness.app.util.ScreenCapabilityGate;

import java.util.ArrayList;
import java.util.List;

/**
 * 复用 DSHA 的设备能力授权；结果未知时调用方不得换通道重放。
 *
 * <p>{@link VirtualScreenChannel#available} 是「授权仍在」（心跳与退出核验用）；新启动经 {@link #choose} 走
 * {@link ScreenCapabilityGate#chooseUsable} 的严格判据（Shizuku 需已绑定、ADB 需已验证连接）。
 */
final class VirtualScreenChannels {
  private VirtualScreenChannels() {}

  static List<VirtualScreenChannel> all() {
    return all(android.os.Build.VERSION.SDK_INT, DeviceBridgeService.adbState);
  }

  static List<VirtualScreenChannel> all(int sdk, String adbState) {
    List<VirtualScreenChannel> channels = new ArrayList<>();
    channels.add(
        new VirtualScreenChannel() {
          public String id() {
            return "root";
          }

          public boolean available(Context c) {
            return ScreenCapabilityGate.channelAuthorized(id(), ScreenCapabilities.sample(c));
          }

          public String start(Context c, String command) {
            return RootShell.execVirtualScreen(c, command);
          }

          public com.deepseekharness.app.device.DeviceTransportResult startTyped(
              Context c, String command) {
            return RootShell.execVirtualScreenTyped(c, command);
          }
        });
    VirtualScreenChannel shizuku =
        new VirtualScreenChannel() {
          public String id() {
            return "shizuku";
          }

          public boolean available(Context c) {
            return ScreenCapabilityGate.channelAuthorized(id(), ScreenCapabilities.sample(c));
          }

          public String start(Context c, String command) {
            return ShizukuShell.execVirtualScreen(command);
          }

          public com.deepseekharness.app.device.DeviceTransportResult startTyped(
              Context c, String command) {
            return ShizukuShell.execVirtualScreenTyped(command);
          }
        };
    VirtualScreenChannel adb =
        new VirtualScreenChannel() {
          public String id() {
            return "adb";
          }

          public boolean available(Context c) {
            return ScreenCapabilityGate.channelAuthorized(id(), ScreenCapabilities.sample(c));
          }

          public String start(Context c, String command) {
            String ticket = VirtualScreenManager.from(c).adbLaunchTicketFor(command);
            return ticket.isEmpty()
                ? "[POLICY_BLOCKED] Native virtual-screen launch ticket missing\n[EXIT=126]"
                : AdbBridge.executeVirtualScreen(c, command, ticket);
          }

          public com.deepseekharness.app.device.DeviceTransportResult startTyped(
              Context c, String command) {
            String ticket = VirtualScreenManager.from(c).adbLaunchTicketFor(command);
            return ticket.isEmpty()
                ? com.deepseekharness.app.device.DeviceTransportResult.unknown(
                    "adb",
                    "VSCREEN_ADB_TICKET_UNAVAILABLE",
                    "[POLICY_BLOCKED] Native virtual-screen launch ticket missing\n[EXIT=126]",
                    true,
                    com.deepseekharness.app.device.DeviceTransportIdentity.unknown())
                : AdbBridge.executeVirtualScreenTyped(c, command, ticket);
          }
        };
    // 顺序由 ScreenCapabilityGate.order 统一决定（Root 已在首位）。
    for (String id : ScreenCapabilityGate.order(sdk, "connected".equals(adbState))) {
      if (ScreenCapabilityGate.SHIZUKU.equals(id)) channels.add(shizuku);
      else if (ScreenCapabilityGate.ADB.equals(id)) channels.add(adb);
    }
    return channels;
  }

  static VirtualScreenChannel find(String id) {
    for (VirtualScreenChannel channel : all()) if (channel.id().equals(id)) return channel;
    return null;
  }

  /**
   * 新启动只选真正可用的通道。Shizuku 已授权但用户服务尚未绑定时，先在发送前等待绑定一次再重新采样
   * （此时什么都还没发出，等待不构成重放）。
   */
  static VirtualScreenChannel choose(Context context) {
    ScreenCapabilityGate.Inputs inputs = ScreenCapabilities.sample(context);
    String id = ScreenCapabilityGate.chooseUsable(inputs);
    if (id.isEmpty() && inputs.shizuku == ScreenCapabilityGate.Shizuku.PERMITTED) {
      ShizukuShell.awaitReady(context, 4000);
      id = ScreenCapabilityGate.chooseUsable(ScreenCapabilities.sample(context));
    }
    return id.isEmpty() ? null : find(id);
  }
}
