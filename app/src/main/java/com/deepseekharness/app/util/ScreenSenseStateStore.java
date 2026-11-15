package com.deepseekharness.app.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 上一次快照只按完整任务 owner、无障碍代次和窗口身份保存，供增量编码使用；不作为缓存授权。
 */
public final class ScreenSenseStateStore {
  private static final int MAX_ENTRIES = 8;
  private static final long MAX_AGE_MILLIS = 30_000L;

  private final LongSupplier clock;
  private final Map<Key, Entry> entries = new LinkedHashMap<>();
  private final Map<Key, String> scopes = new LinkedHashMap<>();
  private long nextSid;

  public ScreenSenseStateStore(LongSupplier clock) {
    if (clock == null) throw new IllegalArgumentException("SCREEN_SENSE_CLOCK_MISSING");
    this.clock = clock;
  }

  public synchronized long nextSid() {
    if (nextSid == Long.MAX_VALUE) throw new IllegalStateException("SCREEN_SENSE_SID_EXHAUSTED");
    return ++nextSid;
  }

  /** Stable only for this owner/epoch/window scope; it is not an action credential. */
  public synchronized String scopeId(String owner, long epoch, ScreenTarget target) {
    Key key = key(owner, epoch, target);
    String value = scopes.get(key);
    if (value == null) {
      value = UUID.randomUUID().toString();
      scopes.put(key, value);
      while (scopes.size() > MAX_ENTRIES) {
        Key expired = scopes.keySet().iterator().next();
        scopes.remove(expired);
        entries.remove(expired);
      }
    }
    return value;
  }

  public synchronized ScreenSenseState previous(String owner, long epoch, ScreenTarget target) {
    Entry entry = currentEntry(owner, epoch, target);
    return entry == null ? null : entry.state;
  }

  public synchronized boolean isCurrent(
      String owner, long epoch, ScreenTarget target, long sid, String scopeId) {
    Entry entry = currentEntry(owner, epoch, target);
    return entry != null && entry.state.sid == sid && entry.state.treeId.equals(scopeId);
  }

  public synchronized void record(
      String owner, long epoch, ScreenTarget target, ScreenSenseState state) {
    if (owner == null || target == null || state == null) return;
    if (!state.target.matches(target) || !state.treeId.equals(scopeId(owner, epoch, target)))
      throw new IllegalArgumentException("SCREEN_SENSE_SCOPE_CHANGED");
    entries.put(new Key(owner, epoch, target), new Entry(state, clock.getAsLong()));
    trimEntries();
  }

  /** A submitted action or any target-changing operation invalidates the old preview. */
  public synchronized void invalidate(String owner, long epoch, ScreenTarget target) {
    if (owner == null || target == null) return;
    entries.remove(new Key(owner, epoch, target));
  }

  public synchronized void clearOwner(String owner) {
    if (owner == null) return;
    entries.keySet().removeIf(key -> key.owner.equals(owner));
    scopes.keySet().removeIf(key -> key.owner.equals(owner));
  }

  public synchronized void clear() {
    entries.clear();
    scopes.clear();
  }

  private Entry currentEntry(String owner, long epoch, ScreenTarget target) {
    if (owner == null || target == null) return null;
    Key key = new Key(owner, epoch, target);
    Entry entry = entries.get(key);
    if (entry == null) return null;
    long age = clock.getAsLong() - entry.createdAt;
    if (age < 0 || age > MAX_AGE_MILLIS) {
      entries.remove(key);
      return null;
    }
    return entry;
  }

  private Key key(String owner, long epoch, ScreenTarget target) {
    if (owner == null || owner.isEmpty() || epoch < 0 || target == null || target.windowId < 0)
      throw new IllegalArgumentException("SCREEN_SENSE_SCOPE_INVALID");
    return new Key(owner, epoch, target);
  }

  private void trimEntries() {
    while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
  }

  private static final class Entry {
    final ScreenSenseState state;
    final long createdAt;

    Entry(ScreenSenseState state, long createdAt) {
      this.state = state;
      this.createdAt = createdAt;
    }
  }

  private static final class Key {
    final String owner;
    final long epoch;
    final ScreenTarget target;

    Key(String owner, long epoch, ScreenTarget target) {
      this.owner = owner;
      this.epoch = epoch;
      this.target = target;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Key value)) return false;
      return epoch == value.epoch
          && owner.equals(value.owner)
          && target.displayId == value.target.displayId
          && target.windowId == value.target.windowId
          && target.packageName.equals(value.target.packageName);
    }

    @Override
    public int hashCode() {
      int result = owner.hashCode();
      result = 31 * result + Long.hashCode(epoch);
      result = 31 * result + target.displayId;
      result = 31 * result + target.windowId;
      return 31 * result + target.packageName.hashCode();
    }
  }
}
