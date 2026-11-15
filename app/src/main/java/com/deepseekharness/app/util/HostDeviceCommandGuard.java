package com.deepseekharness.app.util;

import java.util.Locale;

/** Host-device invariant: the local Root/Shizuku/ADB command surface never flashes or reboots. */
public final class HostDeviceCommandGuard {
  private HostDeviceCommandGuard() {}

  public static String rejection(String command) {
    if (command == null) return null;
    String flat = command.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    if (flat.isEmpty()) return null;
    String[] rules = {
      "fastboot",
      "flash",
      "flash_image",
      "of=/dev/block",
      ">/dev/block",
      "> /dev/block",
      " reboot",
      "reboot ",
      "shutdown",
      "poweroff",
      "halt",
      "adb server",
      "adb nodaemon",
      "mkfs",
      "wipefs",
      "format",
      "erase",
      "mount ",
      "umount ",
      "update_engine_client",
      "bootctl",
      "recovery --wipe",
      "erase userdata",
      "wipe userdata"
    };
    for (String rule : rules) {
      if (flat.equals(rule.trim()) || flat.contains(rule))
        return "[POLICY_BLOCKED] 本机设备命令禁止刷机、重启或写入块设备；外接目标只能经 /app/extdev/* 结构化接口操作";
    }
    return null;
  }

  public static boolean allowed(String command) {
    return rejection(command) == null;
  }
}
