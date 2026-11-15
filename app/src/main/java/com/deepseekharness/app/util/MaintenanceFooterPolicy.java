package com.deepseekharness.app.util;

/**
 * 运行环境维护页底栏的纯展示决策：主按钮 / 受限入口 / 次要入口在各维护状态下的形态。
 *
 * <p>只决定外观与强调，不决定门禁：点击后的行为仍由 ExtractActivity 原有监听与 MaintenanceCoordinator
 * 判定。就绪状态下“重试 / 重建”保留为次要按钮，维护页以 review_only 打开时用户仍可手动重建。
 */
public final class MaintenanceFooterPolicy {
  private MaintenanceFooterPolicy() {}

  /** 主操作区第一位的形态。 */
  public enum Primary {
    /** 维护进行中：不可点的进度面板（替代禁用后看不清的主按钮）。 */
    PROGRESS,
    /** 常规主色按钮。 */
    NORMAL,
    /** 失败 / 中断待恢复：告警色按钮。 */
    DANGER,
    /** 环境已就绪：重试降为描边次要按钮，“进入主界面”升为主按钮。 */
    DEMOTED
  }

  /** 受限入口 / 进入主界面按钮的形态。 */
  public enum Entry {
    OUTLINE,
    /** 就绪检查未通过时的推荐出口：主色描边。 */
    ACCENT_OUTLINE,
    /** 环境已就绪：主色实心。 */
    PRIMARY
  }

  /** 次要入口中被强调的一项。 */
  public enum Emphasis {
    NONE,
    LOGS,
    EMERGENCY
  }

  public static final class Model {
    public final Primary primary;
    public final Entry entry;
    public final Emphasis emphasis;

    /**
     * 是否在说明上方给出「导出可读副本」（只读救援导出）直达入口。维护执行中不给：树正在移动，
     * 救援结果没有参考价值；失败 / 待恢复 / 就绪检查未通过时给，说明文案也据此引导。
     */
    public final boolean rescue;

    Model(Primary primary, Entry entry, Emphasis emphasis, boolean rescue) {
      this.primary = primary;
      this.entry = entry;
      this.emphasis = emphasis;
      this.rescue = rescue;
    }
  }

  /**
   * @param busy 数据维护正在执行（不含插件查询、终端等通用锁）
   * @param pending 上次维护未完成或有未关闭的隔离试运行现场
   * @param failed 本页发起的任务以失败结束
   * @param incomplete 维护步骤结束但就绪检查未通过
   * @param ready 环境就绪且没有维护进行或待恢复
   */
  public static Model decide(
      boolean busy, boolean pending, boolean failed, boolean incomplete, boolean ready) {
    if (busy) return new Model(Primary.PROGRESS, Entry.OUTLINE, Emphasis.NONE, false);
    if (pending || failed)
      return new Model(Primary.DANGER, Entry.OUTLINE, Emphasis.EMERGENCY, true);
    if (incomplete) return new Model(Primary.NORMAL, Entry.ACCENT_OUTLINE, Emphasis.LOGS, true);
    if (ready) return new Model(Primary.DEMOTED, Entry.PRIMARY, Emphasis.NONE, false);
    return new Model(Primary.NORMAL, Entry.OUTLINE, Emphasis.NONE, false);
  }

  /**
   * 进度面板的裁切 level（0–10000，Android ClipDrawable 约定）。当前步骤记半步，
   * 让第一步运行中也有可见填充；总数无效时返回 0。
   */
  public static int progressLevel(int completed, int total, boolean running) {
    if (total <= 0) return 0;
    double done =
        Math.max(0, Math.min(completed, total)) + (running && completed < total ? 0.5 : 0);
    return (int) Math.round(Math.min(1.0, done / total) * 10_000);
  }

  /** 主操作并排：宽度达到 600dp，或高度不足 480dp（手机横屏）。 */
  public static boolean sideBySide(int screenWidthDp, int screenHeightDp) {
    return screenWidthDp >= 600 || (screenHeightDp > 0 && screenHeightDp <= 480);
  }

  /** 次要入口图标改到文字上方：大字体或可用宽度窄时，避免三列文字被挤成多行。 */
  public static boolean stackedLinks(float fontScale, int screenWidthDp) {
    return fontScale >= 1.25f || screenWidthDp < 340;
  }
}
