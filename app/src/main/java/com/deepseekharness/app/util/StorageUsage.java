package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 存储占用分布条的纯计算：按大小降序，超出色板的类别并入「其他」；不读取文件系统。 */
public final class StorageUsage {
  /** 合并后的段；key 为 null 表示「其他」。 */
  public static final class Segment {
    public final String key;
    public final long bytes;
    public final double fraction;

    Segment(String key, long bytes, double fraction) {
      this.key = key;
      this.bytes = bytes;
      this.fraction = fraction;
    }
  }

  private StorageUsage() {}

  public static long total(Map<String, Long> sizes) {
    long sum = 0;
    for (long value : sizes.values()) if (value > 0) sum += value;
    return sum;
  }

  /**
   * @param slots 色板可用颜色数（≥ 2）；超过时最后一段为「其他」
   */
  public static List<Segment> segments(Map<String, Long> sizes, int slots) {
    if (slots < 2) throw new IllegalArgumentException("SLOTS");
    List<Map.Entry<String, Long>> sorted = new ArrayList<>();
    for (var entry : sizes.entrySet()) if (entry.getValue() > 0) sorted.add(entry);
    // 同大小按键名排序，保证每次统计的颜色分配稳定。
    sorted.sort(
        (a, b) -> {
          int order = Long.compare(b.getValue(), a.getValue());
          return order != 0 ? order : a.getKey().compareTo(b.getKey());
        });
    long total = total(sizes);
    List<Segment> result = new ArrayList<>();
    if (total == 0) return result;
    boolean overflow = sorted.size() > slots;
    int named = overflow ? slots - 1 : sorted.size();
    for (int i = 0; i < named; i++) {
      long bytes = sorted.get(i).getValue();
      result.add(new Segment(sorted.get(i).getKey(), bytes, bytes / (double) total));
    }
    if (overflow) {
      long rest = 0;
      for (int i = named; i < sorted.size(); i++) rest += sorted.get(i).getValue();
      result.add(new Segment(null, rest, rest / (double) total));
    }
    return result;
  }
}
