package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** 应用选择器的纯逻辑：按名称或包名过滤，已配置规则的应用排在前面，其余按名称排序。 */
public final class AppSearch {
  private AppSearch() {}

  /** 空查询匹配全部；否则名称或包名包含查询（忽略大小写与首尾空白）。 */
  public static boolean matches(String query, String label, String packageName) {
    String q = normalize(query);
    if (q.isEmpty()) return true;
    return normalize(label).contains(q) || normalize(packageName).contains(q);
  }

  public static <T> List<T> filterAndSort(
      List<T> items,
      String query,
      Function<T, String> label,
      Function<T, String> packageName,
      java.util.function.Predicate<T> configured) {
    List<T> out = new ArrayList<>();
    for (T item : items)
      if (matches(query, label.apply(item), packageName.apply(item))) out.add(item);
    out.sort(
        Comparator.<T, Boolean>comparing(item -> !configured.test(item))
            .thenComparing(item -> normalize(label.apply(item)))
            .thenComparing(item -> normalize(packageName.apply(item))));
    return out;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
