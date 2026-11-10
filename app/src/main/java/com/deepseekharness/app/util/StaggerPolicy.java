package com.deepseekharness.app.util;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 列表首次错峰的闸门（F1）：只给前 N 个不同条目播放一次，滚动回来、重绘、翻页都不重放。
 *
 * <p>按稳定键判断而不是按位置：RecyclerView 会复用行视图，按位置会让同一行在滚动时反复
 * 淡入；按条目键就能做到“第一次见到才动画”。
 */
public final class StaggerPolicy {
  private final int max;
  private final Set<String> revealed = new LinkedHashSet<>();

  public StaggerPolicy(int max) {
    this.max = Math.max(0, max);
  }

  /**
   * 尝试占用一次错峰名额。
   *
   * @return true 表示这是该条目第一次出现且仍在名额内，可以播放动画
   */
  public boolean claim(String key) {
    if (key == null || max == 0 || revealed.size() >= max) return false;
    return revealed.add(key);
  }

  public boolean revealed(String key) {
    return key != null && revealed.contains(key);
  }

  public int count() {
    return revealed.size();
  }

  public int max() {
    return max;
  }

  public void reset() {
    revealed.clear();
  }
}
