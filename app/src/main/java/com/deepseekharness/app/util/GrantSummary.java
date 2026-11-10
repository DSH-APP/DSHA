package com.deepseekharness.app.util;

/**
 * 设备能力授权页顶部概览胶囊的判据：只把已有状态映射成「标签 + 风险色」，不读取或修改任何授权。
 *
 * <p>文字由界面层按 {@link Label} 渲染，这里不持有语言；风险色与页面内对应控件保持一致：
 * 全局权限开启与 root 完全放宽为红色，root 放宽 / 屏幕全部允许 / 待处理状态为橙色。
 */
public final class GrantSummary {
  private GrantSummary() {}

  public enum Tone {
    GOOD,
    WARN,
    BAD,
    NEUTRAL
  }

  public enum Label {
    GLOBAL_ON,
    GLOBAL_OFF,
    GLOBAL_ATTENTION,
    GLOBAL_UPDATING,
    ROOT_OFF,
    ROOT_NO_SU,
    ROOT_STRICT,
    ROOT_RELAXED,
    ROOT_FULL,
    SHIZUKU_READY,
    SHIZUKU_NOT_AUTHORIZED,
    SHIZUKU_BINDING,
    SHIZUKU_FAILED,
    SHIZUKU_NOT_RUNNING,
    SCREEN_NOT_SET,
    SCREEN_CONNECTING,
    SCREEN_PAUSED,
    SCREEN_MANUAL,
    SCREEN_CUSTOM,
    SCREEN_ALLOW_ALL
  }

  public static final class Item {
    public final Label label;
    public final Tone tone;

    Item(Label label, Tone tone) {
      this.label = label;
      this.tone = tone;
    }
  }

  /** 全局确认豁免：开启即为最高风险；读不到 / 需重新确认时不报告成「关闭」。 */
  public static Item global(NativeConsentPolicy.ReadState state) {
    if (state == null) return new Item(Label.GLOBAL_ATTENTION, Tone.WARN);
    return switch (state) {
      case ON -> new Item(Label.GLOBAL_ON, Tone.BAD);
      case OFF, UNCONFIGURED -> new Item(Label.GLOBAL_OFF, Tone.GOOD);
      case CHANGING -> new Item(Label.GLOBAL_UPDATING, Tone.NEUTRAL);
      case MALFORMED, READ_UNAVAILABLE, SAVE_FAILED, RECONFIRM_REQUIRED ->
          new Item(Label.GLOBAL_ATTENTION, Tone.WARN);
    };
  }

  /** Root：未启用为中性；放宽档位优先于「是否找到 su」显示，避免高风险档位被掩盖。 */
  public static Item root(boolean enabled, boolean suPresent, DeviceShellPolicy.Mode mode) {
    if (!enabled) return new Item(Label.ROOT_OFF, Tone.NEUTRAL);
    if (mode == DeviceShellPolicy.Mode.FULL) return new Item(Label.ROOT_FULL, Tone.BAD);
    if (mode == DeviceShellPolicy.Mode.RELAXED) return new Item(Label.ROOT_RELAXED, Tone.WARN);
    if (!suPresent) return new Item(Label.ROOT_NO_SU, Tone.WARN);
    return new Item(Label.ROOT_STRICT, Tone.GOOD);
  }

  public static Item shizuku(ShizukuChannelStatus.Step step) {
    if (step == null) return new Item(Label.SHIZUKU_NOT_RUNNING, Tone.NEUTRAL);
    return switch (step) {
      case READY -> new Item(Label.SHIZUKU_READY, Tone.GOOD);
      case REQUEST, AUTHORIZE_IN_OWNER -> new Item(Label.SHIZUKU_NOT_AUTHORIZED, Tone.WARN);
      case BINDING -> new Item(Label.SHIZUKU_BINDING, Tone.NEUTRAL);
      case BIND_FAILED -> new Item(Label.SHIZUKU_FAILED, Tone.WARN);
      case INSTALL, CONNECT, PROFILE_NO_SERVICE ->
          new Item(Label.SHIZUKU_NOT_RUNNING, Tone.NEUTRAL);
    };
  }

  /**
   * 屏幕操作：未开启无障碍为待设置；已连接时暂停优先，其次按确认方式，全部允许标为高风险。
   *
   * @param enabled 系统无障碍开关已开启
   * @param connected 无障碍服务已实际连接
   */
  public static Item screen(
      boolean enabled, boolean connected, boolean paused, ScreenConfirmationPolicy.Mode mode) {
    if (!enabled) return new Item(Label.SCREEN_NOT_SET, Tone.WARN);
    if (!connected) return new Item(Label.SCREEN_CONNECTING, Tone.NEUTRAL);
    if (paused) return new Item(Label.SCREEN_PAUSED, Tone.WARN);
    if (mode == ScreenConfirmationPolicy.Mode.ALLOW_ALL)
      return new Item(Label.SCREEN_ALLOW_ALL, Tone.WARN);
    if (mode == ScreenConfirmationPolicy.Mode.CUSTOM)
      return new Item(Label.SCREEN_CUSTOM, Tone.GOOD);
    return new Item(Label.SCREEN_MANUAL, Tone.GOOD);
  }

  /** 内容区宽度（dp）达到该值才分两栏，保证每栏不少于约 300dp。 */
  public static final int TWO_COLUMN_DP = 640;

  /** 单栏时内容最多这么宽，宽屏上居中，不随屏宽拉满。 */
  public static final int SINGLE_COLUMN_MAX_DP = 640;

  /** 双栏时内容最多这么宽，横屏大屏上不让两栏被拉得过散。 */
  public static final int TWO_COLUMN_MAX_DP = 1040;

  public static int columns(int contentWidthDp) {
    return contentWidthDp >= TWO_COLUMN_DP ? 2 : 1;
  }

  /** 左右留白（dp）：内容收进上限宽度后居中，但不小于页面原有边距。 */
  public static int sidePadding(int widthDp, int basePaddingDp) {
    int max = columns(widthDp - 2 * basePaddingDp) == 2 ? TWO_COLUMN_MAX_DP : SINGLE_COLUMN_MAX_DP;
    return Math.max(basePaddingDp, (widthDp - max) / 2);
  }
}
