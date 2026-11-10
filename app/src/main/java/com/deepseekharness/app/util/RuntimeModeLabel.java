package com.deepseekharness.app.util;

/**
 * 本次实际运行方式的展示与诊断标签：proot 区分是否带 seccomp 过滤。
 *
 * <p>无 seccomp 过滤（{@code PROOT_NO_SECCOMP=1}，首次部署探测失败时的兼容档）每个系统调用都经 ptrace
 * 拦截，启动明显更慢；启动日志与诊断导出必须能看出用户落在哪一档。只描述实际启动参数，不参与运行方式选择。
 */
public final class RuntimeModeLabel {
  private RuntimeModeLabel() {}

  /** 稳定的诊断键：proroot / proot-seccomp / proot-no-seccomp；其它运行时原样返回。 */
  public static String key(String runtimeId, boolean seccompDisabled) {
    if (!"proot".equals(runtimeId)) return String.valueOf(runtimeId);
    return seccompDisabled ? "proot-no-seccomp" : "proot-seccomp";
  }

  /** 启动日志用的可读标签；proroot 不受 seccomp 设置影响。 */
  public static String display(String runtimeId, boolean seccompDisabled) {
    if (!"proot".equals(runtimeId)) return String.valueOf(runtimeId);
    return seccompDisabled
        ? UiText.choose("proot（兼容模式：无 seccomp）", "proot (compatibility mode: no seccomp)")
        : UiText.choose("proot（seccomp 过滤）", "proot (seccomp filter)");
  }
}
