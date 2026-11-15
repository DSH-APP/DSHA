package com.deepseekharness.app.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「本轮允许此应用」的记忆（纯逻辑）。
 *
 * <p>只按 本轮任务身份 + 包名 记住用户的允许：同一轮内同一应用不再逐次确认；敏感应用每
 * {@link #SENSITIVE_ACTIONS_PER_CONFIRM} 次操作重新确认一次。确认策略修订号变化、本轮结束、暂停或停止
 * （调用方 {@link #clear()}）都会失效。它只省去重复弹窗，不跳过任何目标、遮挡、代次或权限检查。
 */
public final class TurnScreenGrants {
  public static final int SENSITIVE_ACTIONS_PER_CONFIRM = 5;
  static final int MAX_ENTRIES = 64;

  private static final class Entry {
    final long policyRevision;
    int sensitiveUses;

    Entry(long policyRevision) {
      this.policyRevision = policyRevision;
    }
  }

  private final Map<String, Entry> entries = new LinkedHashMap<>();

  private static String key(String turn, String pkg) {
    if (turn == null || turn.isEmpty() || pkg == null || pkg.isEmpty()) return null;
    return turn + "\0" + pkg;
  }

  /** 用户在确认窗选了「本轮允许此应用」，且该次确认本身已通过全部复核。 */
  public synchronized void remember(String turn, String pkg, long policyRevision) {
    String key = key(turn, pkg);
    if (key == null) return;
    entries.remove(key);
    entries.put(key, new Entry(policyRevision));
    while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
  }

  /** 本次操作能否免于弹窗；敏感应用每次调用计一次用量。 */
  public synchronized boolean allows(
      String turn, String pkg, boolean sensitive, long policyRevision) {
    String key = key(turn, pkg);
    if (key == null) return false;
    Entry entry = entries.get(key);
    if (entry == null || entry.policyRevision != policyRevision) return false;
    if (!sensitive) return true;
    if (entry.sensitiveUses >= SENSITIVE_ACTIONS_PER_CONFIRM) return false;
    entry.sensitiveUses++;
    return true;
  }

  public synchronized void clear() {
    entries.clear();
  }
}
