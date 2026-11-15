package com.deepseekharness.app.util;

/**
 * 虚拟屏助手文本写入前的密码焦点策略（T97 安全切，纯逻辑）。
 *
 * <p>危险路径：{@code /app/vscreen/type} 的纯 ASCII 快路径由宿主直接转发给 Core 的
 * {@code input -d <displayId> text}，不经过无障碍 {@code set_text}/{@code editor} 的密码检查；只要目标应用把焦点
 * 放在密码框里，文字就会被送进去。本类只做「宿主读到的焦点证据 → 允许/拒绝」的纯决策，不 import Android API。
 *
 * <p>证据与决策分开的理由：宿主只有在无障碍连接时才有语义证据。连接时若目标窗口或焦点读不出来，就无法排除
 * 密码框，此时按 fail-closed 拒绝（请求未发送，重新观察是安全的）。无障碍完全未连接时，产品文档明确支持
 * 「纯像素 + 纯 ASCII 输入」模式；这里保持该能力，但在成功回复里标注 {@code passwordFocusChecked:false}，
 * 不声称已检查（文档同时写明这是保护降级，而不是安全保证）。
 *
 * <p>限制：这是宿主侧最佳努力检查，不是内核沙箱。检查与 Core 注入之间存在时间窗；目标应用在检查之后把焦点
 * 切到密码框仍可能被写入。自定义控件不暴露 {@code isPassword()} 时只能按「不可读」处理；同 UID 的插件/终端
 * 可以绕开受管入口，安全模型本来就不宣称隔离。
 */
public final class VirtualScreenTextInputPolicy {
  private VirtualScreenTextInputPolicy() {}

  /** 只读焦点证据；全部来自当前显示的当前焦点，不跨窗口/代次复用。 */
  public enum FocusEvidence {
    /** 焦点编辑框是密码框。 */
    PASSWORD_FOCUSED,
    /** 焦点编辑框可编辑、启用且不是密码框。 */
    SAFE_FOCUS,
    /** 无障碍已连接，但目标窗口/焦点读不出来，或焦点不是可编辑控件。 */
    FOCUS_UNREADABLE,
    /** 无障碍未连接：该模式没有语义证据通道。 */
    ACCESSIBILITY_UNAVAILABLE
  }

  public enum Decision {
    /** 证据确认焦点安全，正常发送。 */
    ALLOW,
    /** 没有证据通道；保持纯 ASCII 能力，但回复必须标注未检查。 */
    ALLOW_UNVERIFIED,
    /** 已确认密码焦点：不发送。 */
    BLOCK_PASSWORD,
    /** 有证据通道但读不出焦点：无法排除密码框，不发送。 */
    BLOCK_UNVERIFIED
  }

  /** 与无障碍 {@code set_text}/{@code editor} 的密码拒绝码一致。 */
  public static final String CODE_PASSWORD = "PASSWORD_INPUT_UNAVAILABLE";

  /** 有语义证据通道但焦点不可读时的稳定码；重新观察后可重试同一动作。 */
  public static final String CODE_UNVERIFIED = "PASSWORD_FOCUS_UNVERIFIED";

  public static Decision decide(FocusEvidence evidence) {
    if (evidence == null) return Decision.BLOCK_UNVERIFIED;
    switch (evidence) {
      case PASSWORD_FOCUSED:
        return Decision.BLOCK_PASSWORD;
      case SAFE_FOCUS:
        return Decision.ALLOW;
      case ACCESSIBILITY_UNAVAILABLE:
        return Decision.ALLOW_UNVERIFIED;
      case FOCUS_UNREADABLE:
      default:
        return Decision.BLOCK_UNVERIFIED;
    }
  }

  /** 被拒绝时的稳定错误码；允许时返回空串。 */
  public static String code(Decision decision) {
    if (decision == Decision.BLOCK_PASSWORD) return CODE_PASSWORD;
    if (decision == Decision.BLOCK_UNVERIFIED) return CODE_UNVERIFIED;
    return "";
  }

  /** 被拒绝时的下一步建议；允许时返回空串。 */
  public static String nextAction(Decision decision) {
    if (decision == Decision.BLOCK_PASSWORD) return VirtualScreenObservationHint.REPORT_TO_USER;
    if (decision == Decision.BLOCK_UNVERIFIED) return VirtualScreenObservationHint.SEE_AGAIN;
    return "";
  }

  /** 仅「有通道但读不出」可安全重试；密码焦点重试也不会成功。 */
  public static boolean retryable(Decision decision) {
    return decision == Decision.BLOCK_UNVERIFIED;
  }

  /** 回复里可携带的证据名：只表达宿主读到了什么，不包含焦点文本或界面内容。 */
  public static String evidenceName(FocusEvidence evidence) {
    return evidence == null ? "FOCUS_UNREADABLE" : evidence.name();
  }
}
