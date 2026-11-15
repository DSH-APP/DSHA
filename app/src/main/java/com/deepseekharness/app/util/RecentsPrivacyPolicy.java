package com.deepseekharness.app.util;

/**
 * 「隐藏后台」在 Android 13+ 的任务可见性策略。
 *
 * <p>平台会把「最近可见任务」保留在最近任务列表里（AOSP
 * {@code RecentTasks#isInVisibleRange}：Keep the most recent task of home display even if it is
 * excluded from recents），因此从本应用直接滑到最近任务时，当前卡片由系统保留，应用没有公开
 * API 可以去掉。本策略只决定应用自己能做的两件事：
 *
 * <ol>
 *   <li>开启时隐藏最近任务截图内容，当前卡片不泄露页面内容；
 *   <li>实验性「严格模式」在应用整体退到后台后结束 UI 任务，让卡片立即消失。
 * </ol>
 */
public final class RecentsPrivacyPolicy {
  public enum BackgroundAction {
    KEEP,
    END_TASK
  }

  private RecentsPrivacyPolicy() {}

  /** 隐藏后台开启时，最近任务卡片不显示页面内容（系统 API 33+ 才支持关闭截图）。 */
  public static boolean hideRecentsScreenshot(boolean hideFromRecents) {
    return hideFromRecents;
  }

  /**
   * 严格模式：应用整体退到后台后结束 UI 任务，让最近任务卡片立即消失。
   *
   * <p>外部选择器（SAF 等）在途时不能结束，否则结果回调随界面一起丢失；画中画里应用仍可见，
   * 结束任务会关掉用户的画中画窗口；多窗口下用户还在屏幕上使用，不替用户结束布局。
   */
  public static BackgroundAction onAppBackground(
      boolean hideFromRecents,
      boolean strict,
      boolean externalFlow,
      boolean pictureInPicture,
      boolean multiWindow) {
    if (!hideFromRecents || !strict) return BackgroundAction.KEEP;
    if (externalFlow || pictureInPicture || multiWindow) return BackgroundAction.KEEP;
    return BackgroundAction.END_TASK;
  }

  /** 严格模式是「隐藏后台」的附加项，父开关关闭时不生效。 */
  public static boolean strictAvailable(boolean hideFromRecents) {
    return hideFromRecents;
  }
}
