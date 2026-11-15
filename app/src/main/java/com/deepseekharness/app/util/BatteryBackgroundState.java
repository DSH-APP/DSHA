package com.deepseekharness.app.util;

/**
 * 电池 / 后台运行状态的显示判定（纯逻辑）。Android 开放两项可读事实：
 *
 * <ul>
 *   <li>{@code ActivityManager.isBackgroundRestricted}：用户是否关闭了“允许后台使用”（RUN_ANY_IN_BACKGROUND）；
 *   <li>{@code PowerManager.isIgnoringBatteryOptimizations}：是否在 Doze 豁免名单（“不受限制”档）。
 * </ul>
 *
 * Android 14 起应用电池页的“允许后台使用”开关打开后默认是“优化”档：后台允许运行，但仍受 Doze
 * 批处理；点开开关文字选择“不受限制”才进入豁免名单。因此“未受限但未豁免”就是用户已允许后台，
 * 应计为已开启，只额外提示可选的“不受限制”。
 */
public final class BatteryBackgroundState {
  private BatteryBackgroundState() {}

  public enum State {
    /** 已在电池优化豁免名单（不受限制）。 */
    UNRESTRICTED,
    /** 允许后台使用，处于系统“优化”档（Doze 仍会批处理）。 */
    ALLOWED,
    /** 用户关闭了后台使用：Web、终端与保活在后台很可能被结束。 */
    RESTRICTED
  }

  /** 显式限制优先于豁免：两者同时出现时按受限提示（取保守结论）。 */
  public static State of(boolean ignoringOptimizations, boolean backgroundRestricted) {
    if (backgroundRestricted) return State.RESTRICTED;
    if (ignoringOptimizations) return State.UNRESTRICTED;
    return State.ALLOWED;
  }

  /** 允许后台（优化或不受限制）即计入已开启；只有被限制才不计。 */
  public static boolean granted(State state) {
    return state == State.UNRESTRICTED || state == State.ALLOWED;
  }
}
