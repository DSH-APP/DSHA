package com.deepseekharness.app.util;

/**
 * 虚拟屏的稳定错误码。
 *
 * <p>对外错误码只表达"发生了什么、下一步做什么"，绝不把异常类名当作语义：{@code VSCREEN_RemoteException}
 * 这类外泄（issue #123 / #113）对用户和调用方都没有可操作信息。映射是纯逻辑，按异常链的类别判定。
 */
public final class VirtualScreenErrors {
  private VirtualScreenErrors() {}

  public static final String PERMISSION_DENIED = "VSCREEN_PERMISSION_DENIED";
  public static final String DISPLAY_SERVICE_UNAVAILABLE = "VSCREEN_DISPLAY_SERVICE_UNAVAILABLE";
  public static final String INVALID_REQUEST = "VSCREEN_INVALID_DISPLAY_REQUEST";
  public static final String DISPLAY_STATE = "VSCREEN_DISPLAY_STATE";
  public static final String DISPLAY_IO = "VSCREEN_DISPLAY_IO";
  public static final String OPERATION_FAILED = "VSCREEN_OPERATION_FAILED";
  public static final String DISPLAY_CREATE_FAILED = "VSCREEN_DISPLAY_CREATE_FAILED";
  public static final String TRUSTED_DISPLAY_DENIED = "VSCREEN_TRUSTED_DISPLAY_DENIED";

  /**
   * 创建虚拟显示失败的码。创建点自己知道有没有请求受信任显示，因此能给出可操作的结论：
   * 请求过却被拒绝 → 该通道不能创建受信任显示；没请求仍失败 → 与权限无关的创建失败。
   */
  public static String createFailure(boolean requestedTrusted) {
    return requestedTrusted ? TRUSTED_DISPLAY_DENIED : DISPLAY_CREATE_FAILED;
  }

  /**
   * 启动阶段的失败码：异常自带的 {@code VSCREEN_*} 码原样保留（例如 ADB 启动许可冲突是设备通道
   * 问题，不该贴成显示域的标签），其余按类别映射；同样不外泄异常类名。
   */
  public static String startFailure(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      String message = current.getMessage();
      if (message != null && message.matches("VSCREEN_[A-Z0-9_]{1,64}")) return message;
    }
    return stable(error);
  }

  /** 其它路由的兜底映射：沿异常链取第一个可识别的类别，取不到就是通用的操作失败。 */
  public static String stable(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof SecurityException) return PERMISSION_DENIED;
      String name = current.getClass().getSimpleName();
      if (name.equals("RemoteException") || name.equals("DeadObjectException"))
        return DISPLAY_SERVICE_UNAVAILABLE;
      if (current instanceof IllegalArgumentException) return INVALID_REQUEST;
      if (current instanceof IllegalStateException) return DISPLAY_STATE;
      if (current instanceof java.io.IOException) return DISPLAY_IO;
    }
    return OPERATION_FAILED;
  }
}
