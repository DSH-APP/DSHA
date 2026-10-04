package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 冷安装只投影自身运行选择；不回放凭据、用户配置或设备授权。 */
public final class ColdRuntimeSelection {
  public static final List<String> KEYS =
      List.of(
          Constants.KEY_CONTAINER_RUNTIME,
          "proot_disable_seccomp",
          "cold_runtime_root",
          "cold_runtime_packages",
          "cold_runtime_mode");

  private ColdRuntimeSelection() {}

  public static Map<String, Object> snapshot(Map<String, ?> values) throws IOException {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String key : KEYS)
      if (values.containsKey(key)) {
        Object value = values.get(key);
        if (!(value instanceof String)
            && !(value instanceof Boolean)
            && !(value instanceof Integer)
            && !(value instanceof Long)
            && !(value instanceof Float)) throw new IOException("COLD_SELECTION_TYPE");
        out.put(key, value);
      }
    return java.util.Collections.unmodifiableMap(out);
  }

  public static Map<String, Object> rollback(
      Map<String, Object> before, Map<String, ?> current, String expectedRootIdentity)
      throws IOException {
    if (before == null
        || !KEYS.containsAll(before.keySet())
        || expectedRootIdentity == null
        || expectedRootIdentity.isEmpty()
        || !expectedRootIdentity.equals(current.get("cold_runtime_root")))
      throw new IOException("COLD_SELECTION_CHANGED");
    return snapshot(before);
  }
}
