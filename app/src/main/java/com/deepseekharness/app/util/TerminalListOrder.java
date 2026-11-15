package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 侧栏混合列表的显示先后。PTY 与简易会话 id 各自永久递增、互不相关，不能直接比较；这里在会话首次出现时
 * 记一个共享序号，之后保持不变，会话消失即遗忘。纯显示用途，不参与身份、选择或关闭核验。
 *
 * <p>同一次刷新里同时出现的新会话：同类按 id 先后，跨类 PTY 在前，保证结果稳定。
 */
public final class TerminalListOrder {
  /** 会话键：类型 + 该类型内的永久 id。 */
  public record Key(boolean pty, long id) {}

  private final Map<Key, Long> seen = new HashMap<>();
  private long next;

  /** 返回按显示先后排好的键；输入中未出现的旧键会被遗忘。 */
  public synchronized List<Key> arrange(List<Long> ptyIds, List<Long> simpleIds) {
    List<Key> fresh = new ArrayList<>();
    Set<Key> present = new HashSet<>();
    collect(true, ptyIds, present, fresh);
    collect(false, simpleIds, present, fresh);
    seen.keySet().retainAll(present);
    fresh.sort((a, b) -> a.pty() != b.pty() ? (a.pty() ? -1 : 1) : Long.compare(a.id(), b.id()));
    for (Key key : fresh) seen.put(key, ++next);
    List<Key> ordered = new ArrayList<>(present);
    ordered.sort((a, b) -> Long.compare(seen.get(a), seen.get(b)));
    return Collections.unmodifiableList(ordered);
  }

  private void collect(boolean pty, List<Long> ids, Set<Key> present, List<Key> fresh) {
    if (ids == null) return;
    for (Long id : ids) {
      if (id == null) continue;
      Key key = new Key(pty, id);
      if (!present.add(key)) continue;
      if (!seen.containsKey(key)) fresh.add(key);
    }
  }
}
