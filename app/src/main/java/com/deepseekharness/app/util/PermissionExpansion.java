package com.deepseekharness.app.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 引导授权行的展开状态（纯逻辑，无 Android 依赖）。
 *
 * <p>每张授权卡默认折叠成一行：已开启只显示状态，未开启在行内给出主操作；用途说明、跳转提示与其余按钮在展开后才出现。
 * 用户的展开选择按“卡片 id + 当前是否已满足”记忆：授权状态一变（例如从系统设置授权回来），该卡回到默认折叠，
 * 而面板每秒轮询重绘时不会把用户刚点开的卡收回去。只影响本次显示，不持久化、不申请任何权限。
 */
public final class PermissionExpansion {
  private final Map<String, Boolean> chosen = new HashMap<>();

  /** 当前是否展开。未作选择时一律折叠。 */
  public boolean expanded(String card, boolean satisfied) {
    Boolean value = chosen.get(key(card, satisfied));
    return value != null && value;
  }

  /** 切换展开；返回切换后的状态。同一张卡在另一授权状态下的选择会被清掉。 */
  public boolean toggle(String card, boolean satisfied) {
    boolean next = !expanded(card, satisfied);
    chosen.remove(key(card, !satisfied));
    chosen.put(key(card, satisfied), next);
    return next;
  }

  private static String key(String card, boolean satisfied) {
    return String.valueOf(card) + (satisfied ? "#on" : "#off");
  }
}
