package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡内标签页的选择状态（F3）：按稳定键记住选中项，旋转 / 语言切换后按同一键恢复。
 *
 * <p>保存的键已不存在（例如某页签在当前条件下隐藏）时退回第一项，不让卡片出现空白内容。
 */
public final class CardTabState {
  private final List<String> keys;
  private String selected;

  public CardTabState(List<String> keys, String savedKey) {
    this.keys = keys == null ? new ArrayList<>() : new ArrayList<>(keys);
    if (savedKey != null && this.keys.contains(savedKey)) selected = savedKey;
    else if (!this.keys.isEmpty()) selected = this.keys.get(0);
  }

  public List<String> keys() {
    return List.copyOf(keys);
  }

  public String selectedKey() {
    return selected;
  }

  public int selectedIndex() {
    return selected == null ? -1 : keys.indexOf(selected);
  }

  /** 切换页签；未知键或已选中返回 false。 */
  public boolean select(String key) {
    if (key == null || !keys.contains(key) || key.equals(selected)) return false;
    selected = key;
    return true;
  }

  public boolean select(int index) {
    if (index < 0 || index >= keys.size()) return false;
    return select(keys.get(index));
  }
}
