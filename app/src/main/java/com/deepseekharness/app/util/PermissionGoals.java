package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 首次引导第 2 步“用途”到第 3 步授权项的推荐映射。纯逻辑：只决定哪些项推荐、按什么顺序显示，
 * 不申请、不打开任何权限。卡片 id 为界面常量，不写入任何权限状态。
 */
public final class PermissionGoals {
  private PermissionGoals() {}

  public static final String BASE = "base",
      FILES = "files",
      SCREEN = "screen",
      SHELL = "shell",
      BACKGROUND = "bg";

  /** 卡片 id。 */
  public static final String STORAGE = "storage",
      A11Y = "a11y",
      OVERLAY = "overlay",
      NOTIFY = "notify",
      CHANNEL = "channel",
      BATTERY = "battery";

  /** 全部授权项的默认显示顺序（不分组）。 */
  private static final List<String> CARDS =
      Collections.unmodifiableList(List.of(NOTIFY, BATTERY, STORAGE, A11Y, OVERLAY, CHANNEL));

  public static List<String> cards() {
    return CARDS;
  }

  /** 可选用途（BASE 始终视为已选，不带推荐授权）。 */
  public static List<String> goals() {
    return List.of(BASE, FILES, SCREEN, SHELL, BACKGROUND);
  }

  /** 用途对应的推荐卡片；未知用途忽略。 */
  public static List<String> cardsFor(String goal) {
    if (goal == null) return List.of();
    switch (goal) {
      case FILES:
        return List.of(STORAGE);
      case SCREEN:
        return List.of(A11Y, OVERLAY, NOTIFY);
      case SHELL:
        return List.of(CHANNEL);
      case BACKGROUND:
        return List.of(BATTERY, NOTIFY);
      default:
        return List.of();
    }
  }

  public static Set<String> recommended(Set<String> goals) {
    Set<String> result = new LinkedHashSet<>();
    if (goals == null) return result;
    for (String goal : goals()) if (goals.contains(goal)) result.addAll(cardsFor(goal));
    return result;
  }

  /** 推荐的授权项，按默认显示顺序。 */
  public static List<String> recommendedCards(Set<String> goals) {
    Set<String> rec = recommended(goals);
    List<String> result = new ArrayList<>();
    for (String card : CARDS) if (rec.contains(card)) result.add(card);
    return Collections.unmodifiableList(result);
  }

  /** 未推荐的授权项（折叠到“更多授权”），按默认显示顺序。 */
  public static List<String> otherCards(Set<String> goals) {
    Set<String> rec = recommended(goals);
    List<String> result = new ArrayList<>();
    for (String card : CARDS) if (!rec.contains(card)) result.add(card);
    return Collections.unmodifiableList(result);
  }

  /** 推荐授权卡片数（第 2 步按钮文案用）。 */
  public static int recommendedCount(Set<String> goals) {
    return recommended(goals).size();
  }

  /**
   * 卡片是否已满足。states 顺序固定：存储、Shizuku / Stellar、Root、无障碍、通知、悬浮窗、电池优化；
   * 设备命令通道任选其一（Shizuku / Stellar 或 Root）。
   */
  public static boolean satisfied(String card, boolean[] states) {
    if (card == null || states == null || states.length < 7) return false;
    switch (card) {
      case STORAGE:
        return states[0];
      case CHANNEL:
        return states[1] || states[2];
      case A11Y:
        return states[3];
      case NOTIFY:
        return states[4];
      case OVERLAY:
        return states[5];
      case BATTERY:
        return states[6];
      default:
        return false;
    }
  }

  /** 推荐卡片中已满足的数量。 */
  public static int recommendedDone(Set<String> goals, boolean[] states) {
    int done = 0;
    for (String card : recommended(goals)) if (satisfied(card, states)) done++;
    return done;
  }

  /** 推荐卡片中尚未满足的卡片 id（完成页提示用）。 */
  public static List<String> recommendedMissing(Set<String> goals, boolean[] states) {
    List<String> missing = new ArrayList<>();
    for (String card : recommended(goals)) if (!satisfied(card, states)) missing.add(card);
    return Collections.unmodifiableList(missing);
  }
}
