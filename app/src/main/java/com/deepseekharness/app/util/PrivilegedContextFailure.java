package com.deepseekharness.app.util;

/** 隐藏 API 失败的有界平台诊断，不自动转移授权通道。 */
public final class PrivilegedContextFailure {
  public static String describe(int sdk, Throwable failure) {
    Throwable first = failure;
    java.util.Set<Throwable> seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (int i = 0; first != null && first.getCause() != null && i < 16 && seen.add(first); i++)
      first = first.getCause();
    String reason =
        first == null
            ? "unknown"
            : first.getClass().getSimpleName() + ": " + String.valueOf(first.getMessage());
    reason = SensitiveData.redact(reason).replace('\n', ' ').replace('\r', ' ');
    if (reason.length() > 512) reason = reason.substring(0, 512);
    return "PRIVILEGED_PACKAGE_CONTEXT_UNAVAILABLE: sdk=" + sdk + " cause=" + reason;
  }

  private PrivilegedContextFailure() {}
}
