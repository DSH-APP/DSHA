package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class DeviceChannelSnapshotTest {
  private static final String PS =
      "  PID  PPID   UID     ELAPSED NAME\n"
          + "    1     0     0       47:01 init\n"
          + " 2434     1     0       46:58 adbd\n"
          + "11815     1     0       44:34 io.github.sunsetrne.dsha:shizuku\n"
          + "17253 10890     0       00:03 sh\n"
          + "17254 17253     0       00:03 app_process\n"
          + "17255 17253     0       05:03 app_process\n"
          + "11788     1     0       44:34 stellar_server\n"
          + " 9999     1 10123       01:00 com.example.chat\n"
          + "[EXIT=0]\n";

  @Test
  public void keepsOnlyChannelProcessesAndCountsThem() {
    String filtered = DeviceChannelSnapshot.filterProcesses(PS);
    assertTrue(filtered.startsWith("PID PPID UID ELAPSED NAME\n"));
    assertTrue(filtered.contains("2434 1 0 46:58 adbd"));
    assertTrue(filtered.contains("stellar_server"));
    assertFalse(filtered.contains("init"));
    assertFalse("与通道无关的应用不记录", filtered.contains("com.example.chat"));
    assertTrue(filtered.contains("# relevant=6 total=8"));
    Map<String, Integer> counts = DeviceChannelSnapshot.countByName(filtered);
    assertEquals(Integer.valueOf(2), counts.get("app_process"));
    assertEquals(Integer.valueOf(1), counts.get("adbd"));
  }

  @Test
  public void dropsColumnsBeyondNameSoArgumentsNeverLeak() {
    String filtered =
        DeviceChannelSnapshot.filterProcesses(
            "PID PPID UID ELAPSED NAME\n17 1 0 00:01 app_process --token=secret\n");
    assertTrue(filtered.contains("17 1 0 00:01 app_process\n"));
    assertFalse(filtered.contains("secret"));
  }

  @Test
  public void processTableIsBounded() {
    StringBuilder ps = new StringBuilder("PID PPID UID ELAPSED NAME\n");
    for (int i = 0; i < 260; i++) ps.append(1000 + i).append(" 1 0 00:01 app_process\n");
    String filtered = DeviceChannelSnapshot.filterProcesses(ps.toString());
    assertEquals(
        DeviceChannelSnapshot.MAX_PROCESS_LINES,
        DeviceChannelSnapshot.countByName(filtered).get("app_process").intValue());
    assertTrue(filtered.contains("(truncated)"));
  }

  @Test
  public void emptyOrMissingProcessTable() {
    assertEquals("", DeviceChannelSnapshot.filterProcesses(null));
    String body = DeviceChannelSnapshot.render("root-busy:exec", 0L, new LinkedHashMap<>(), "");
    assertTrue(body.contains("(unavailable)"));
  }

  @Test
  public void fileNamesAreUtcAndRotationKeepsNewest() {
    // 2026-11-03T10:28:05Z
    long at = 1793701685000L;
    assertEquals("device-channel-20261103T102805Z.txt", DeviceChannelSnapshot.fileName(at, 0));
    assertEquals("device-channel-20261103T102805Z-2.txt", DeviceChannelSnapshot.fileName(at, 2));
    List<String> names =
        Arrays.asList(
            "device-channel-20261103T102805Z.txt",
            "device-channel-20261101T000000Z.txt",
            "device-channel-20261102T000000Z.txt",
            "notes.txt",
            ".device-channel-20261103T102806Z.txt.tmp",
            "device-channel-20261103T102805Z-1.txt");
    assertEquals(
        Arrays.asList("device-channel-20261101T000000Z.txt", "device-channel-20261102T000000Z.txt"),
        DeviceChannelSnapshot.expired(names, 2));
    assertTrue(DeviceChannelSnapshot.expired(names, 10).isEmpty());
  }

  @Test
  public void triggersAreRateLimited() {
    assertTrue(DeviceChannelSnapshot.due(null, 0));
    assertFalse(
        DeviceChannelSnapshot.due(1_000L, 1_000L + DeviceChannelSnapshot.MIN_INTERVAL_MS - 1));
    assertTrue(DeviceChannelSnapshot.due(1_000L, 1_000L + DeviceChannelSnapshot.MIN_INTERVAL_MS));
    assertTrue("时钟回拨不应永久禁止取证", DeviceChannelSnapshot.due(10_000L, 5_000L));
  }

  @Test
  public void reportKeepsEachValueOnOneLine() {
    Map<String, String> state = new LinkedHashMap<>();
    state.put("root.slots", "capacity=3 active=0 lingering=3");
    state.put("adb.detail", "line1\nline2");
    String body = DeviceChannelSnapshot.render("root-lingering:exec", 1793701685000L, state, "");
    assertTrue(body.contains("trigger=root-lingering:exec\n"));
    assertTrue(body.contains("utc=20261103T102805Z\n"));
    assertTrue(body.contains("adb.detail=line1\\nline2\n"));
  }
}
