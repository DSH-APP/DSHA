package com.deepseekharness.app.util;

/**
 * 内置插件注册的分级预算：同一受管资产的常规启动不需要首启的宽预算。
 *
 * <p>判据只用受管资产身份（{@code managed-assets-v2} / runtimeId 的 64 位十六进制）。身份缺失、
 * 不合法或与上一次成功注册不一致，都按「覆盖升级后首启」处理，给足链接修复与覆盖层写入的时间；
 * 首启预算只在真正需要时生效，不放松常规启动的失败反馈。
 */
public final class RegisterBudget {
  /** 同一受管资产的常规启动预算。 */
  public static final long NORMAL_MS = 90_000;

  /** 覆盖升级后首启（受管资产身份变化或从未成功注册）的预算。 */
  public static final long FIRST_START_MS = 240_000;

  private RegisterBudget() {}

  /** 只有两个身份都合法且完全一致才使用常规预算；其它情况一律按首启处理。 */
  public static long timeoutMs(String lastRegistered, String current) {
    String previous = identity(lastRegistered), expected = identity(current);
    return previous != null && previous.equals(expected) ? NORMAL_MS : FIRST_START_MS;
  }

  /** 受管资产身份固定为 64 位小写十六进制；其它状态一律视为未知，不参与相等判定。 */
  public static String identity(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.matches("[a-f0-9]{64}") ? trimmed : null;
  }
}
