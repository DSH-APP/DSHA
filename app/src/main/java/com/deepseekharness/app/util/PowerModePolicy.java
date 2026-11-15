package com.deepseekharness.app.util;

/**
 * 屏幕常亮与省电模式的互斥规则（纯逻辑：不访问 Android、存储或用户界面）。
 *
 * <p>两者语义冲突：屏幕常亮要求窗口保持点亮，省电模式允许系统在空闲时休眠。应用采用
 * <b>硬互斥</b>，固定规则如下：
 *
 * <ul>
 *   <li>用户开启任一边时，另一边必须关闭；
 *   <li>关闭一边只改自己，绝不动另一边；
 *   <li>重复开启已开启的一边不产生变更（调用方据此跳过持久化、服务刷新与提示）；
 *   <li>启动时遇到历史数据或旧版本留下的「双开」，保留屏幕常亮、关闭省电模式，且不提示。
 * </ul>
 */
public final class PowerModePolicy {
  private PowerModePolicy() {}

  /**
   * 一次决策：{@link #previousKeepScreenOn}/{@link #previousEcoMode} 是调用方读到的现状，
   * {@link #keepScreenOn}/{@link #ecoMode} 是应写入的目标；{@link #changed()} 判断是否需要同步。
   */
  public static final class Decision {
    public final boolean previousKeepScreenOn;
    public final boolean previousEcoMode;
    public final boolean keepScreenOn;
    public final boolean ecoMode;

    /** 本次决策强制关闭了屏幕常亮。 */
    public final boolean disabledKeepScreenOn;

    /** 本次决策强制关闭了省电模式。 */
    public final boolean disabledEcoMode;

    private Decision(
        boolean previousKeepScreenOn,
        boolean previousEcoMode,
        boolean keepScreenOn,
        boolean ecoMode,
        boolean disabledKeepScreenOn,
        boolean disabledEcoMode) {
      this.previousKeepScreenOn = previousKeepScreenOn;
      this.previousEcoMode = previousEcoMode;
      this.keepScreenOn = keepScreenOn;
      this.ecoMode = ecoMode;
      this.disabledKeepScreenOn = disabledKeepScreenOn;
      this.disabledEcoMode = disabledEcoMode;
    }

    /** 目标状态与现状不同：需要写持久化并同步 UI / 服务。 */
    public boolean changed() {
      return keepScreenOn != previousKeepScreenOn || ecoMode != previousEcoMode;
    }
  }

  /** 启动归一：历史数据或旧版本可能双开；固定保留屏幕常亮、关闭省电模式，且不提示。 */
  public static Decision normalizeOnStartup(boolean keepScreenOn, boolean ecoMode) {
    boolean conflict = keepScreenOn && ecoMode;
    return new Decision(
        keepScreenOn, ecoMode, keepScreenOn, conflict ? false : ecoMode, false, conflict);
  }

  /** 用户切换「屏幕常亮」：开启时强制关闭省电模式；关闭时不动省电模式。 */
  public static Decision afterKeepScreenOnToggle(
      boolean keepScreenOn, boolean ecoMode, boolean checked) {
    boolean disableEco = checked && ecoMode;
    return new Decision(
        keepScreenOn, ecoMode, checked, disableEco ? false : ecoMode, false, disableEco);
  }

  /** 用户切换「省电模式」：开启时强制关闭屏幕常亮；关闭时不动屏幕常亮。 */
  public static Decision afterEcoModeToggle(
      boolean keepScreenOn, boolean ecoMode, boolean checked) {
    boolean disableKeep = checked && keepScreenOn;
    return new Decision(
        keepScreenOn, ecoMode, disableKeep ? false : keepScreenOn, checked, disableKeep, false);
  }
}
