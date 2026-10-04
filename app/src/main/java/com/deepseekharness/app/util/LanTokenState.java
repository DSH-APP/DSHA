package com.deepseekharness.app.util;

import java.util.function.Supplier;

/** 本进程的 LAN 凭据；关闭后旧偏好值不能重新成为有效授权。 */
public final class LanTokenState {
  private String value = "";

  public synchronized String current() {
    return value;
  }

  public synchronized String ensure(Supplier<String> source) {
    if (!value.isEmpty()) return value;
    String candidate = source.get();
    if (!valid(candidate)) throw new IllegalArgumentException("LAN_TOKEN_INVALID");
    value = candidate;
    return value;
  }

  public synchronized void revoke() {
    value = "";
  }

  public static boolean valid(String value) {
    return value != null && value.matches("[A-Za-z0-9_-]{43}");
  }
}
