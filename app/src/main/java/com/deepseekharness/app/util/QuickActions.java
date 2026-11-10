package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 顶栏「+」气泡弹窗的可见项：下载记录与关于常驻，运行环境 / 访问地址只在启动页提供。
 *
 * <p>纯逻辑，不依赖 Android；顺序即弹窗自上而下的渲染顺序。
 */
public final class QuickActions {

  public enum Action {
    DOWNLOADS,
    ABOUT,
    ENVIRONMENT,
    ADDRESS
  }

  private QuickActions() {}

  /** 启动页提供四项；其它根页只保留全局的下载记录与关于。 */
  public static List<Action> items(boolean launchPage) {
    List<Action> actions = new ArrayList<>(4);
    actions.add(Action.DOWNLOADS);
    actions.add(Action.ABOUT);
    if (launchPage) {
      actions.add(Action.ENVIRONMENT);
      actions.add(Action.ADDRESS);
    }
    return Collections.unmodifiableList(actions);
  }
}
