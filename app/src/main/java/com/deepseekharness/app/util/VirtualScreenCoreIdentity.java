package com.deepseekharness.app.util;

/** 只从可信启动结果取得的进程出生身份；端口和 PID 都不能单独充当退出证明。 */
public final class VirtualScreenCoreIdentity {
  private static final String UUID = Ids.UUID_PATTERN;
  public final int pid, uid;
  public final long started;
  public final String boot, instance;

  public VirtualScreenCoreIdentity(int pid, long started, int uid, String boot, String instance) {
    if (pid <= 1
        || started <= 0
        || uid < 0
        || boot == null
        || instance == null
        || !boot.matches(UUID)
        || !instance.matches(UUID))
      throw new IllegalArgumentException("VSCREEN_CORE_IDENTITY_INVALID");
    this.pid = pid;
    this.started = started;
    this.uid = uid;
    this.boot = boot;
    this.instance = instance;
  }

  public String encode() {
    return pid + ":" + started + ":" + uid + ":" + boot + ":" + instance;
  }

  public static VirtualScreenCoreIdentity parse(String text) {
    if (text == null || text.length() > 160)
      throw new IllegalArgumentException("VSCREEN_CORE_IDENTITY_INVALID");
    String[] fields = text.split(":", -1);
    if (fields.length != 5
        || !fields[0].matches("[1-9][0-9]{0,9}")
        || !fields[1].matches("[1-9][0-9]{0,18}")
        || !fields[2].matches("0|[1-9][0-9]{0,9}"))
      throw new IllegalArgumentException("VSCREEN_CORE_IDENTITY_INVALID");
    return new VirtualScreenCoreIdentity(
        Integer.parseInt(fields[0]),
        Long.parseLong(fields[1]),
        Integer.parseInt(fields[2]),
        fields[3],
        fields[4]);
  }

  public enum Exit {
    EXITED,
    ALIVE,
    UNKNOWN
  }

  public Exit proof(String output) {
    if (output == null || !output.contains("[EXIT=0]")) return Exit.UNKNOWN;
    String prefix = "DSHA_VSCREEN_EXIT_PROOF 1 " + encode() + " ";
    Exit result = null;
    for (String line : output.split("\\r?\\n")) {
      if (!line.startsWith("DSHA_VSCREEN_EXIT_PROOF ")) continue;
      if (result != null || !line.startsWith(prefix)) return Exit.UNKNOWN;
      try {
        result = Exit.valueOf(line.substring(prefix.length()));
      } catch (IllegalArgumentException invalid) {
        return Exit.UNKNOWN;
      }
    }
    return result == null ? Exit.UNKNOWN : result;
  }
}
