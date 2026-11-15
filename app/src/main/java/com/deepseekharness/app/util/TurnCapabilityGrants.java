package com.deepseekharness.app.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** 当前对话轮次的能力记忆；只用于显式的「本轮允许」，不替代能力策略或运行时闸门。 */
public final class TurnCapabilityGrants {
  private static final int MAX_ENTRIES = 32;
  private final Map<String, Long> entries = new LinkedHashMap<>();

  private static String key(String turn, String capability) {
    if (turn == null || turn.isEmpty() || capability == null || capability.isEmpty()) return null;
    return turn + "\0" + capability;
  }

  public synchronized void remember(String turn, String capability, long policyRevision) {
    String key = key(turn, capability);
    if (key == null || policyRevision < 0) return;
    entries.remove(key);
    entries.put(key, policyRevision);
    while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
  }

  public synchronized boolean allows(String turn, String capability, long policyRevision) {
    String key = key(turn, capability);
    return key != null
        && policyRevision >= 0
        && Long.valueOf(policyRevision).equals(entries.get(key));
  }

  public synchronized void clear() {
    entries.clear();
  }
}
