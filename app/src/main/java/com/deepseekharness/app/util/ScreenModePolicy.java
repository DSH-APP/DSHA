package com.deepseekharness.app.util;

/**
 * 识屏模式（off / physical / virtual）的纯规则：桥按模式放行、迁移默认值、切换与准入丢失。
 *
 * <p>两种模式互斥。模式不匹配时一律拒绝，语义是「容器没有拿到这块屏的授权」，<b>不回退到另一块屏</b>。
 */
public final class ScreenModePolicy {
  private ScreenModePolicy() {}

  public enum Mode {
    OFF,
    PHYSICAL,
    VIRTUAL;

    public String wire() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 未知或空值按 OFF 处理（fail-closed）。 */
    public static Mode parse(String value) {
      if ("physical".equals(value)) return PHYSICAL;
      if ("virtual".equals(value)) return VIRTUAL;
      return OFF;
    }
  }

  /** 请求属于哪块屏。 */
  public enum Domain {
    PHYSICAL,
    VIRTUAL
  }

  public static final String SCREEN_MODE_OFF = "SCREEN_MODE_OFF";
  public static final String SCREEN_MODE_PHYSICAL_OFF = "SCREEN_MODE_PHYSICAL_OFF";
  public static final String SCREEN_MODE_VIRTUAL_OFF = "SCREEN_MODE_VIRTUAL_OFF";

  /** 返回 null 表示放行；否则返回稳定拒绝码。 */
  public static String rejection(Mode mode, Domain domain) {
    if (mode == null || mode == Mode.OFF) return SCREEN_MODE_OFF;
    if (domain == Domain.PHYSICAL) return mode == Mode.PHYSICAL ? null : SCREEN_MODE_PHYSICAL_OFF;
    return mode == Mode.VIRTUAL ? null : SCREEN_MODE_VIRTUAL_OFF;
  }

  /**
   * 收尾请求不受模式门控：task-end 只关闭本轮任务围栏，不读屏、不输入；挡住它只会让旧任务卡在执行界面。
   */
  public static boolean exempt(Domain domain, String operation) {
    return domain == Domain.PHYSICAL && "task-end".equals(operation);
  }

  /**
   * 首次升级（新键缺失）时的默认模式：已有运行中或待清理的虚拟屏 → virtual（不打断现有会话）；否则无障碍已连接 →
   * physical（保持旧版主屏工具可用）；其余 off。只写一次，之后以用户选择为准。
   */
  public static Mode migrate(boolean virtualCoreLiveOrPending, boolean accessibilityConnected) {
    if (virtualCoreLiveOrPending) return Mode.VIRTUAL;
    if (accessibilityConnected) return Mode.PHYSICAL;
    return Mode.OFF;
  }

  public enum Switch {
    /** 目标与当前相同，无事可做。 */
    UNCHANGED,
    /** 可以切换。 */
    ALLOWED,
    /** 目标模式准入不满足。 */
    NOT_ADMITTED,
    /** 虚拟屏待清理（START_UNKNOWN / DISCONNECTED / CLOSING / CLOSE_UNKNOWN），禁止离开 virtual。 */
    CLEANUP_PENDING
  }

  /**
   * 切换前判定。关闭（OFF）总是允许准入；离开 virtual 时若虚拟屏待清理则拒绝 —— 待清理意味着 Core 是否退出未知，
   * 此时放手给实体识屏会让两块屏同时可能被操作。
   */
  public static Switch evaluate(
      Mode current, Mode target, boolean targetAdmitted, boolean virtualCleanupPending) {
    if (current == target) return Switch.UNCHANGED;
    if (current == Mode.VIRTUAL && virtualCleanupPending) return Switch.CLEANUP_PENDING;
    if (target != Mode.OFF && !targetAdmitted) return Switch.NOT_ADMITTED;
    return Switch.ALLOWED;
  }

  /**
   * 已开启模式的准入丢失后降为 OFF，不自动换到另一模式。保持判据比开启判据宽（见 {@link
   * ScreenCapabilityGate#channelAuthorized}），避免通道短暂重连就撤销正在用的模式。
   */
  public static Mode afterCapabilityChange(Mode current, boolean currentStillAuthorized) {
    if (current == Mode.OFF) return Mode.OFF;
    return currentStillAuthorized ? current : Mode.OFF;
  }

  /** 已开启模式是否仍保有授权（宽判据）。 */
  public static boolean retained(Mode mode, ScreenCapabilityGate.Inputs in) {
    switch (mode) {
      case PHYSICAL:
        return in.accessibility;
      case VIRTUAL:
        if (!VirtualScreenPolicy.supported(in.api)) return false;
        for (String id : ScreenCapabilityGate.order(in.api, in.adbConnected))
          if (ScreenCapabilityGate.channelAuthorized(id, in)) return true;
        return false;
      default:
        return true;
    }
  }

  /** 开启判据（严格）。 */
  public static boolean admitted(Mode mode, ScreenCapabilityGate.Inputs in) {
    switch (mode) {
      case PHYSICAL:
        return ScreenCapabilityGate.physical(in).allowed;
      case VIRTUAL:
        return ScreenCapabilityGate.virtual(in).allowed;
      default:
        return true;
    }
  }
}
