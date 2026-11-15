package com.deepseekharness.app.util;

import java.util.List;

/**
 * 何时把设备 DNS 重新投影到容器 resolv.conf。
 *
 * <p>只在拿到非空且与上次投影不同的设备 DNS 时刷新：断网瞬间（DNS 为空）保留上次有效段，
 * 信号强度、验证状态等无关变化不写盘；回到同一组 DNS 也不重复写。
 */
public final class ResolverRefreshPolicy {
  public static final long DEBOUNCE_MS = 400;
  public static final long MAINTENANCE_RETRY_MS = 5_000;

  private ResolverRefreshPolicy() {}

  public static boolean shouldRefresh(List<String> applied, List<String> current) {
    if (current == null || current.isEmpty()) return false;
    return applied == null || !applied.equals(current);
  }
}
