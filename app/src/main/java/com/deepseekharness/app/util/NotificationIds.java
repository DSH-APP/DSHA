package com.deepseekharness.app.util;

/** Stable app notification namespace; existing Android identifiers stay unchanged. */
public final class NotificationIds {
  private NotificationIds() {}

  public static final int HARNESS = 1001,
      UPDATE = 1004,
      AGENT = 2002,
      SHELL_CONFIRM = 3003,
      DEVICE_BRIDGE = 3005,
      DATA_PROTECTION = 9031,
      RECOVERY = 9060,
      FILE_DOWNLOAD = 9062,
      REMOTE_CONNECTION = 9063,
      FLOATING_ASSISTANT = 9064,
      TERMINAL_RUNTIME = 9065,
      BRIDGE_INSTALL = 9066;

  public static java.util.Set<Integer> all() {
    return java.util.Set.of(
        HARNESS,
        UPDATE,
        AGENT,
        SHELL_CONFIRM,
        DEVICE_BRIDGE,
        DATA_PROTECTION,
        RECOVERY,
        FILE_DOWNLOAD,
        REMOTE_CONNECTION,
        FLOATING_ASSISTANT,
        TERMINAL_RUNTIME,
        BRIDGE_INSTALL);
  }
}
