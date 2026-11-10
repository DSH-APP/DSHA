package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class NetworkEpochPolicyTest {
  private static NetworkSnapshot net(long handle, boolean validated, boolean blocked) {
    return new NetworkSnapshot(
        handle,
        handle == 0 ? "none" : "wifi",
        false,
        validated,
        blocked,
        List.of("192.0.2.53"),
        "off",
        "",
        true,
        false);
  }

  @Test
  public void newDefaultNetworkOrRecoveryInvalidatesConnections() {
    assertTrue(NetworkEpochPolicy.invalidates(null, net(100, true, false)));
    assertTrue(NetworkEpochPolicy.invalidates(NetworkSnapshot.NONE, net(100, true, false)));
    // Wi-Fi → 移动数据
    assertTrue(NetworkEpochPolicy.invalidates(net(100, true, false), net(200, true, false)));
    // 已连接但无法上网 → 通过验证
    assertTrue(NetworkEpochPolicy.invalidates(net(100, false, false), net(100, true, false)));
    // 后台网络限制解除
    assertTrue(NetworkEpochPolicy.invalidates(net(100, true, true), net(100, true, false)));
  }

  @Test
  public void lossAndUnrelatedChangesKeepConnections() {
    // 断网本身不推进：没有网络时新建连接也不会成功
    assertFalse(NetworkEpochPolicy.invalidates(net(100, true, false), NetworkSnapshot.NONE));
    assertFalse(NetworkEpochPolicy.invalidates(net(100, true, false), null));
    assertFalse(NetworkEpochPolicy.invalidates(net(100, true, false), net(100, true, false)));
    assertFalse(NetworkEpochPolicy.invalidates(net(100, true, false), net(100, false, false)));
    assertFalse(NetworkEpochPolicy.invalidates(net(100, true, false), net(100, true, true)));
    NetworkSnapshot dnsChanged =
        new NetworkSnapshot(
            100, "wifi", false, true, false, List.of("198.51.100.53"), "off", "", true, true);
    assertFalse(NetworkEpochPolicy.invalidates(net(100, true, false), dnsChanged));
  }

  @Test
  public void tokenIsCompactAsciiAndDistinct() {
    String a = NetworkEpochPolicy.token(123_456_789L, 1);
    assertEquals("21i3v9-1", a);
    assertTrue(a.matches("[0-9a-z.-]{1,64}"));
    assertNotEquals(a, NetworkEpochPolicy.token(123_456_789L, 2));
    assertTrue(
        NetworkEpochPolicy.token(Long.MAX_VALUE, Long.MAX_VALUE).matches("[0-9a-z.-]{1,64}"));
    assertEquals("0-0", NetworkEpochPolicy.token(-5, -1));
  }
}
