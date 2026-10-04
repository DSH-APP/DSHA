package com.deepseekharness.app.util;

/** DSH 权限档位；它是运行选项，不构成 Android UID 隔离。 */
public final class PermissionMode {
  public static final String DEFAULT = "danger-full-access";

  private PermissionMode() {}

  /** 保留有效用户选择，旧 default、空值和非法档位沿用当前默认值。 */
  public static String normalize(String value) {
    if ("read-only".equals(value) || "workspace-write".equals(value) || DEFAULT.equals(value))
      return value;
    return DEFAULT;
  }
}
