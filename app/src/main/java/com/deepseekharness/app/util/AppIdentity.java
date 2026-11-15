package com.deepseekharness.app.util;

/** Product identity for the production application. */
public final class AppIdentity {
  private AppIdentity() {}

  /**
   * 运行时包名，必须跟随实际安装身份（含 {@code dsha.sunsetTest} 的 .test 变体）。
   * 不能写死字符串：桥描述符、安装身份与虚拟屏会话都以它核对，写死会让变体包互相不认。
   */
  public static String applicationId() {
    return com.deepseekharness.app.BuildConfig.APPLICATION_ID;
  }

  public static String displayName() {
    return "DSH-A-Su";
  }

  public static String publicDirectoryName() {
    return "DSHA";
  }

  public static String defaultUpdateFeed() {
    return "";
  }

  public static boolean isExperimental() {
    return false;
  }
}
