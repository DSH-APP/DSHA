package com.deepseekharness.app.util;

/**
 * 版本更新页“进度按钮”的纯推导：同一个按钮在原位切换模式，不再叠放检查 / 下载 / 安装 / 取消。
 *
 * <p>只看任务阶段、候选 / 安装包是否存在、字节数与安装交接状态，不从展示文案推断；文案由界面层按 {@link Label}
 * 取当前语言。取消入口只保留在下载通知栏，页面不再提供。
 */
public final class UpdateActionPolicy {
  private UpdateActionPolicy() {}

  /** 与 {@code UpdateRepository.Stage} 一一对应；util 不依赖 Android 层，按名称映射。 */
  public enum Stage {
    IDLE,
    CHECKING,
    DOWNLOADING,
    VERIFYING
  }

  /** 按钮外观：IDLE 实心可点；SWEEP 浅底流光；FILL 从左填充；FULL 填满微光；WAIT 填满静止。 */
  public enum Mode {
    IDLE,
    SWEEP,
    FILL,
    FULL,
    WAIT
  }

  /** 点击后的动作；进行中为 NONE（按钮不可点）。 */
  public enum Command {
    NONE,
    CHECK,
    DOWNLOAD,
    INSTALL
  }

  /** 按钮文字种类；具体文案与数字由界面层组装。 */
  public enum Label {
    CHECK,
    DOWNLOAD,
    RESUME,
    INSTALL,
    CHECKING,
    DOWNLOADING,
    VERIFYING,
    REVERIFYING,
    OPENING_INSTALLER
  }

  /** 状态点语气，只映射到 shell_* 语义色。 */
  public enum Tone {
    MUTED,
    ACCENT,
    GOOD,
    BAD
  }

  public static final class Action {
    public final Mode mode;
    public final Command command;
    public final Label label;

    /** 0–100；未知或不适用时为 -1。FILL 时是下载进度，RESUME 时是已下载比例。 */
    public final int percent;

    /** 状态行末尾的“重新检查”链接是否显示。 */
    public final boolean recheck;

    /** 通道切换是否可用；空闲与检查中可用，下载 / 校验或安装交接未完成时锁定。 */
    public final boolean channelEnabled;

    public final Tone tone;

    Action(
        Mode mode,
        Command command,
        Label label,
        int percent,
        boolean recheck,
        boolean channelEnabled,
        Tone tone) {
      this.mode = mode;
      this.command = command;
      this.label = label;
      this.percent = percent;
      this.recheck = recheck;
      this.channelEnabled = channelEnabled;
      this.tone = tone;
    }

    public boolean clickable() {
      return command != Command.NONE;
    }

    /** 下载中按钮文字带字节数；其它阶段不冒充传输进度。 */
    public boolean showsBytes() {
      return mode == Mode.FILL;
    }
  }

  /**
   * @param installVerifying 安装前重新校验安装包中
   * @param installPending 校验完成、等待页面领取并打开系统安装器
   * @param installError 上一次安装交接失败（仅影响语气）
   */
  public static Action resolve(
      Stage stage,
      boolean hasRelease,
      boolean hasApk,
      long downloaded,
      long total,
      boolean installVerifying,
      boolean installPending,
      boolean installError) {
    Stage current = stage == null ? Stage.IDLE : stage;
    boolean handoff = installVerifying || installPending;
    boolean idle = current == Stage.IDLE && !handoff;
    int ratio = ratio(downloaded, total);
    Mode mode;
    Command command = Command.NONE;
    Label label;
    int percent = -1;
    if (installVerifying) {
      mode = Mode.FULL;
      label = Label.REVERIFYING;
    } else if (installPending) {
      mode = Mode.WAIT;
      label = Label.OPENING_INSTALLER;
    } else if (current == Stage.CHECKING) {
      mode = Mode.SWEEP;
      label = Label.CHECKING;
    } else if (current == Stage.DOWNLOADING) {
      // 总大小未知时不编造百分比，退回不定进度。
      mode = ratio >= 0 ? Mode.FILL : Mode.SWEEP;
      label = Label.DOWNLOADING;
      percent = ratio;
    } else if (current == Stage.VERIFYING) {
      mode = Mode.FULL;
      label = Label.VERIFYING;
    } else if (hasApk) {
      mode = Mode.IDLE;
      command = Command.INSTALL;
      label = Label.INSTALL;
    } else if (hasRelease) {
      mode = Mode.IDLE;
      command = Command.DOWNLOAD;
      boolean partial = downloaded > 0;
      label = partial ? Label.RESUME : Label.DOWNLOAD;
      percent = partial ? ratio : -1;
    } else {
      mode = Mode.IDLE;
      command = Command.CHECK;
      label = Label.CHECK;
    }
    Tone tone;
    if (installError && !handoff) tone = Tone.BAD;
    else if (!idle) tone = Tone.ACCENT;
    else if (hasApk) tone = Tone.GOOD;
    else if (hasRelease) tone = Tone.ACCENT;
    else tone = Tone.MUTED;
    // 检查中可切换通道（引擎作废当前检查并按新通道重查）；下载、校验与安装交接仍锁定。
    boolean channelEnabled = idle || (current == Stage.CHECKING && !handoff);
    return new Action(mode, command, label, percent, idle && hasRelease, channelEnabled, tone);
  }

  /** 已下载比例，向下取整并夹在 0–100；总大小未知返回 -1。 */
  static int ratio(long downloaded, long total) {
    if (total <= 0) return -1;
    long clamped = Math.max(0, Math.min(downloaded, total));
    return (int) (clamped * 100 / total);
  }
}
