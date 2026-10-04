package com.deepseekharness.app.core;

import java.util.HashMap;
import java.util.Map;

/** Persist bounded issue events per generation; the in-memory trace still retains fresh details. */
final class StartupIssueBudget {
  static final int MAX_PLUGINS = 20;
  static final int MAX_PER_PLUGIN = 3;
  private final Map<String, Integer> counts = new HashMap<>();
  private final Map<String, String> last = new HashMap<>();
  private long generation;

  synchronized void begin(long next) {
    generation = next;
    counts.clear();
    last.clear();
  }

  synchronized boolean record(long expected, String plugin, String detail) {
    if (expected <= 0 || expected != generation || detail == null || detail.isEmpty()) return false;
    String key = plugin == null ? "" : plugin;
    int count = counts.getOrDefault(key, 0);
    if (count == MAX_PER_PLUGIN || detail.equals(last.get(key))) return false;
    if (count == 0 && counts.size() == MAX_PLUGINS) return false;
    counts.put(key, count + 1);
    last.put(key, detail);
    return true;
  }
}
