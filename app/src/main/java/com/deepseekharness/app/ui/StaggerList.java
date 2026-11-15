package com.deepseekharness.app.ui;

import android.view.View;
import android.view.ViewGroup;
import com.deepseekharness.app.util.StaggerPolicy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 列表首次错峰（F1）：只给前 8 个条目一次淡入上移，滚动回来、重绘、翻页都不重放。
 *
 * <p>容器版用于一次性建好的列表（备份记录、卡片列表），逐行版用于 RecyclerView 这类会复用
 * 行视图的列表：按条目稳定键判断“第一次见到”，所以滚动复用的同一行不会再次动画。
 * 系统关闭动画时直接显示，不做半透明残留。
 */
final class StaggerList {
  static final int MAX_ITEMS = 8;
  private static final Map<String, StaggerPolicy> POLICIES = new LinkedHashMap<>();

  private StaggerList() {}

  /** 容器首次出现：给前 {@code maxItems} 个可见子项错峰淡入；同一列表再次调用不再播放。 */
  static void reveal(ViewGroup parent, String listKey) {
    reveal(parent, listKey, MAX_ITEMS);
  }

  static void reveal(ViewGroup parent, String listKey, int maxItems) {
    if (parent == null || listKey == null || maxItems <= 0) return;
    if (!UiMotion.animationsEnabled(parent.getContext())) return;
    StaggerPolicy policy = policy(listKey, maxItems);
    int delayIndex = 0;
    for (int index = 0; index < parent.getChildCount() && delayIndex < maxItems; index++) {
      View child = parent.getChildAt(index);
      if (child == null || child.getVisibility() != View.VISIBLE) continue;
      String key = childKey(child, index);
      if (!policy.claim(key)) continue;
      animate(child, delayIndex * 30L, 200L);
      delayIndex++;
    }
  }

  /** RecyclerView 行绑定：第一次见到该条目才淡入，复用行不重播。 */
  static void revealRow(View row, String listKey, String itemKey) {
    if (row == null || listKey == null || itemKey == null) return;
    if (!UiMotion.animationsEnabled(row.getContext())) return;
    if (!policy(listKey, MAX_ITEMS).claim(itemKey)) return;
    animate(row, 0L, 160L);
  }

  static boolean revealed(String listKey, String itemKey) {
    return listKey != null && itemKey != null && policy(listKey, MAX_ITEMS).revealed(itemKey);
  }

  static void reset(String listKey) {
    if (listKey == null) return;
    synchronized (POLICIES) {
      POLICIES.remove(listKey);
    }
  }

  private static StaggerPolicy policy(String listKey, int maxItems) {
    synchronized (POLICIES) {
      return POLICIES.computeIfAbsent(listKey, ignored -> new StaggerPolicy(maxItems));
    }
  }

  private static String childKey(View child, int index) {
    Object tag = child.getTag();
    return tag == null ? "position:" + index : tag.toString();
  }

  private static void animate(View child, long delayMs, long durationMs) {
    float offset = 6f * child.getResources().getDisplayMetrics().density;
    child.animate().cancel();
    child.setAlpha(0f);
    child.setTranslationY(offset);
    child
        .animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(delayMs)
        .setDuration(durationMs)
        .setInterpolator(UiMotion.standard())
        .start();
  }
}
