package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.DeviceChannelOrder.Choice;
import com.deepseekharness.app.util.DeviceChannelOrder.Kind;
import com.deepseekharness.app.util.ScreenCapabilityGate.Inputs;
import com.deepseekharness.app.util.ScreenCapabilityGate.Shizuku;
import java.util.List;
import org.junit.Test;

public final class DeviceChannelOrderTest {
  private static Inputs in(
      int api,
      boolean root,
      Shizuku shizuku,
      boolean adbEnabled,
      boolean lan,
      boolean adbConnected) {
    return new Inputs(api, true, root, root, shizuku, adbEnabled, lan, adbConnected, true);
  }

  private static Choice normal(Inputs in) {
    return DeviceChannelOrder.forDeviceCommand(in, Kind.NORMAL, false, false);
  }

  @Test
  public void screenGateAndDeviceCommandsShareOneOrder() {
    for (int api : new int[] {30, 34, 36, 37, 38})
      for (boolean connected : new boolean[] {false, true})
        assertEquals(
            DeviceChannelOrder.order(api, connected), ScreenCapabilityGate.order(api, connected));
    assertEquals(List.of("root", "shizuku", "adb"), DeviceChannelOrder.order(36, true));
    assertEquals(List.of("root", "adb", "shizuku"), DeviceChannelOrder.order(37, true));
    assertEquals(List.of("root", "shizuku", "adb"), DeviceChannelOrder.order(37, false));
  }

  @Test
  public void deviceCommandPicksSameChannelAsVirtualScreenWhenUsable() {
    Inputs[] cases = {
      in(34, true, Shizuku.READY, true, true, true),
      in(34, false, Shizuku.READY, true, true, true),
      in(37, false, Shizuku.READY, true, true, true),
      in(37, false, Shizuku.READY, true, true, false),
      in(34, false, Shizuku.NONE, true, true, true),
    };
    for (Inputs c : cases) assertEquals(ScreenCapabilityGate.chooseUsable(c), normal(c).channel);
    // Android 17 且 ADB 已验证：设备命令同样 ADB 先于 Shizuku（旧逻辑会选 Shizuku）。
    assertEquals("adb", normal(in(37, false, Shizuku.READY, true, true, true)).channel);
  }

  @Test
  public void strictTierWinsBeforeAuthorizedTier() {
    // Shizuku 只是已授权、ADB 已验证：严格档先选 ADB，不等待 Shizuku 绑定。
    assertEquals("adb", normal(in(34, false, Shizuku.PERMITTED, true, true, true)).channel);
    // 都不严格可用：宽档沿统一顺序，Shizuku（已授权待绑定）先于 ADB（待连接）。
    assertEquals("shizuku", normal(in(34, false, Shizuku.PERMITTED, true, true, false)).channel);
    assertEquals("adb", normal(in(34, false, Shizuku.NONE, true, true, false)).channel);
  }

  @Test
  public void adbNeedsSwitchAndLocalNetworkGrant() {
    assertFalse(normal(in(34, false, Shizuku.NONE, true, false, false)).available());
    assertFalse(normal(in(34, false, Shizuku.NONE, true, false, true)).available());
    assertFalse(normal(in(34, false, Shizuku.NONE, false, true, true)).available());
    assertEquals("", normal(in(34, false, Shizuku.NONE, false, false, false)).channel);
  }

  @Test
  public void smsNeverUsesShizuku() {
    Choice c =
        DeviceChannelOrder.forDeviceCommand(
            in(34, false, Shizuku.READY, true, true, false), Kind.SMS, false, false);
    assertEquals("adb", c.channel);
    assertFalse(c.su);
    assertFalse(
        DeviceChannelOrder.forDeviceCommand(
                in(34, false, Shizuku.READY, false, false, false), Kind.SMS, false, false)
            .available());
    assertEquals(
        "root",
        DeviceChannelOrder.forDeviceCommand(
                in(37, true, Shizuku.READY, true, true, true), Kind.SMS, false, false)
            .channel);
  }

  @Test
  public void rawShellOnlyRootOrAdbWithSu() {
    assertEquals(
        "root",
        DeviceChannelOrder.forDeviceCommand(
                in(34, true, Shizuku.READY, true, true, true), Kind.SHELL, false, false)
            .channel);
    Choice adb =
        DeviceChannelOrder.forDeviceCommand(
            in(34, false, Shizuku.READY, true, true, true), Kind.SHELL, false, false);
    assertEquals("adb", adb.channel);
    assertTrue(adb.su);
    assertFalse(
        DeviceChannelOrder.forDeviceCommand(
                in(34, false, Shizuku.READY, false, false, false), Kind.SHELL, false, false)
            .available());
  }

  @Test
  public void forceFlagsRestrictTheEligibleSet() {
    Inputs all = in(34, true, Shizuku.READY, true, true, true);
    Choice forcedAdb = DeviceChannelOrder.forDeviceCommand(all, Kind.NORMAL, false, true);
    assertEquals("adb", forcedAdb.channel);
    assertFalse(forcedAdb.su);
    Choice adbSu = DeviceChannelOrder.forDeviceCommand(all, Kind.NORMAL, true, true);
    assertEquals("adb", adbSu.channel);
    assertTrue(adbSu.su);
    assertEquals(
        "root", DeviceChannelOrder.forDeviceCommand(all, Kind.NORMAL, true, false).channel);
    // forceRoot 不落到 Shizuku：root 不可用时走 ADB + su。
    Choice noRoot =
        DeviceChannelOrder.forDeviceCommand(
            in(34, false, Shizuku.READY, true, true, true), Kind.NORMAL, true, false);
    assertEquals("adb", noRoot.channel);
    assertTrue(noRoot.su);
    // 强制 ADB 但 ADB 未授权：不回落到其它通道。
    assertFalse(
        DeviceChannelOrder.forDeviceCommand(
                in(34, true, Shizuku.READY, false, false, false), Kind.NORMAL, false, true)
            .available());
  }

  @Test
  public void shizukuAndRootNeverCarrySu() {
    assertFalse(normal(in(34, true, Shizuku.READY, true, true, true)).su);
    assertFalse(normal(in(34, false, Shizuku.READY, true, true, true)).su);
  }
}
