package com.deepseekharness.app.vscreen;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public final class VirtualScreenChannelsTest {
  private static List<String> order(int sdk, String adbState) {
    return VirtualScreenChannels.all(sdk, adbState).stream()
        .map(VirtualScreenChannel::id)
        .collect(Collectors.toList());
  }

  @Test
  public void prefersVerifiedAdbOnAndroid17AndKeepsRootFirst() {
    assertEquals(List.of("root", "adb", "shizuku"), order(37, "connected"));
  }

  @Test
  public void preservesOlderAndroidOrderEvenWithConnectedAdb() {
    assertEquals(List.of("root", "shizuku", "adb"), order(36, "connected"));
  }

  @Test
  public void doesNotPreferUnverifiedOrDisconnectedAdbOnAndroid17() {
    assertEquals(List.of("root", "shizuku", "adb"), order(37, "unknown"));
    assertEquals(List.of("root", "shizuku", "adb"), order(37, "reconnecting"));
    assertEquals(List.of("root", "shizuku", "adb"), order(37, "disabled"));
  }
}
