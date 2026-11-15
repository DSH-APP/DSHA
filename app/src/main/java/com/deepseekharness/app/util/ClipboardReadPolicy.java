package com.deepseekharness.app.util;

/** 原生剪贴板读取三态；默认关闭，授权只保存在宿主偏好中。 */
public final class ClipboardReadPolicy {
  public enum Mode {
    OFF("off"),
    ASK("ask"),
    ALLOW("allow");

    public final String id;

    Mode(String id) {
      this.id = id;
    }

    public static Mode decode(String value) {
      if (value == null) return OFF;
      for (Mode mode : values()) if (mode.id.equals(value)) return mode;
      return OFF;
    }
  }

  /** 拒绝词表：待 T40 的 DeviceRefusal/AgentNextAction 实现后迁移到统一接口。 */
  public static final String ERROR_OFF = "DEVICE_CLIPBOARD_OFF";

  public static final String NEXT_ACTION = "REPORT_TO_USER";
  public static final String RETRY = "after_user";
  public static final String USER_ACTION = "设置 → 设备能力授权 → 读剪贴板";

  private ClipboardReadPolicy() {}

  public static boolean readsAllowed(Mode mode) {
    return mode == Mode.ASK || mode == Mode.ALLOW;
  }

  public static boolean requiresConfirmation(Mode mode) {
    return mode == Mode.ASK;
  }
}
