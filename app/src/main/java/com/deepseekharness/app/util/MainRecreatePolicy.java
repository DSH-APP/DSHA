package com.deepseekharness.app.util;

/**
 * 主界面重建时是否可以跳过开机加载页与入口门禁。
 *
 * <p>旋转、深浅色、语言、字体缩放、分屏尺寸变化都会让系统销毁并重建 MainActivity。重建前主界面已经
 * 通过门禁并显示；重建后它仍是同一进程里的同一个主界面，此前出现的后台维护由横幅表达，不应再整屏
 * 盖一次加载页，更不应因为正在写的事务记录被赶去维护页。
 *
 * <p>只有保存态里的进程令牌与当前进程一致才算同进程重建。进程被回收后令牌必然不同，按真正的冷启动
 * 重新走完整门禁，崩溃残留的事务记录仍会进入维护页。
 */
public final class MainRecreatePolicy {
  /** 保存态键；只在主界面已放行时写入。 */
  public static final String STATE_KEY = "main_admitted_process";

  private MainRecreatePolicy() {}

  /**
   * @param savedToken 保存态里的令牌；没有保存态或未放行时为 null
   * @param processToken 当前进程启动时生成的令牌
   * @param restoredState 是否有保存态
   */
  public static boolean skipEntryGate(
      String savedToken, String processToken, boolean restoredState) {
    return restoredState
        && savedToken != null
        && !savedToken.isEmpty()
        && processToken != null
        && savedToken.equals(processToken);
  }
}
