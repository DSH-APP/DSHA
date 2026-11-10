package com.deepseekharness.app.plugins;

import com.deepseekharness.app.core.PluginRepository.Item;
import com.deepseekharness.app.core.PluginRepository.PendingReview;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 目录状态的唯一版本快照；不执行文件、guest 或 UI 操作。 */
public final class PluginRegistry {
  public static final class Snapshot {
    public final long version, invalidationSeq, verifiedAtMillis;
    public final PluginSourceIdentity source;
    public final boolean known, loading, stale, safeMode;
    public final String error;
    public final List<Item> items;
    public final List<PendingReview> pendingReviews;

    private Snapshot(
        long version,
        long invalidationSeq,
        long verifiedAtMillis,
        PluginSourceIdentity source,
        boolean known,
        boolean loading,
        boolean stale,
        String error,
        List<Item> items,
        List<PendingReview> pendingReviews,
        boolean safeMode) {
      this.version = version;
      this.invalidationSeq = invalidationSeq;
      this.verifiedAtMillis = verifiedAtMillis;
      this.source = source;
      this.known = known;
      this.loading = loading;
      this.stale = stale;
      this.error = error;
      this.items = items;
      this.pendingReviews = pendingReviews;
      this.safeMode = safeMode;
    }
  }

  /** 只允许原 Registry 消耗一次；来源 A→B→A 也不能复活旧票。 */
  public static final class Query {
    public final PluginSourceIdentity source;
    public final long sourceEpoch, invalidationSeq, queryId;

    private Query(
        PluginSourceIdentity source, long sourceEpoch, long invalidationSeq, long queryId) {
      this.source = source;
      this.sourceEpoch = sourceEpoch;
      this.invalidationSeq = invalidationSeq;
      this.queryId = queryId;
    }
  }

  private long sourceEpoch, nextQueryId;
  private Query activeQuery;
  private volatile Snapshot current =
      new Snapshot(
          0,
          0,
          0,
          null,
          false,
          false,
          true,
          "",
          Collections.emptyList(),
          Collections.emptyList(),
          false);

  public Snapshot snapshot() {
    return current;
  }

  /** 同一用户数据根保留陈旧展示；运行根或 Web 换代仍撤销旧运行证据和查询票据。 */
  public synchronized boolean updateSource(PluginSourceIdentity source) {
    Objects.requireNonNull(source);
    Snapshot old = current;
    if (source.equals(old.source)) return false;
    boolean sameData = source.sameUserDataRoot(old.source);
    List<Item> retained = Collections.emptyList();
    if (sameData) {
      List<Item> next = new ArrayList<>();
      for (Item item : old.items)
        if (!item.dynamic) next.add(withRuntime(item, "unknown", -1, true, false));
      retained = Collections.unmodifiableList(next);
    }
    sourceEpoch++;
    activeQuery = null;
    current =
        new Snapshot(
            old.version + 1,
            old.invalidationSeq + 1,
            sameData ? old.verifiedAtMillis : 0,
            source,
            sameData && old.known,
            false,
            true,
            "",
            retained,
            sameData ? old.pendingReviews : Collections.emptyList(),
            sameData && old.safeMode);
    return true;
  }

  /** 生命周期已确认换代时立即撤销旧运行证据；不把已有根身份当作重新探测成功。 */
  public synchronized boolean updateWebGeneration(long generation) {
    PluginSourceIdentity source = current.source;
    if (source == null || source.webGeneration == generation) return false;
    return updateSource(
        new PluginSourceIdentity(
            source.dataRoot,
            source.dataRootKey,
            source.dataRootDevice,
            source.rootfs,
            source.rootfsKey,
            source.rootfsDevice,
            source.runtimeMarkerSha256,
            generation));
  }

  public synchronized long invalidate() {
    Snapshot old = current;
    current =
        new Snapshot(
            old.version + 1,
            old.invalidationSeq + 1,
            old.verifiedAtMillis,
            old.source,
            old.known,
            old.loading,
            true,
            old.error,
            old.items,
            old.pendingReviews,
            old.safeMode);
    return current.invalidationSeq;
  }

  /** 同一来源的在途读取复用同一票据；调度合并仍由上层负责。 */
  public synchronized Query beginQuery(PluginSourceIdentity source) {
    updateSource(source);
    if (activeQuery != null) return activeQuery;
    Snapshot old = current;
    activeQuery = new Query(source, sourceEpoch, old.invalidationSeq, ++nextQueryId);
    current =
        new Snapshot(
            old.version + 1,
            old.invalidationSeq,
            old.verifiedAtMillis,
            source,
            old.known,
            true,
            old.stale,
            "",
            old.items,
            old.pendingReviews,
            old.safeMode);
    return activeQuery;
  }

  public synchronized boolean completeQuery(
      Query query, List<Item> items, List<PendingReview> pendingReviews, boolean safeMode) {
    return completeQuery(query, items, pendingReviews, safeMode, System.currentTimeMillis());
  }

  public synchronized boolean completeQuery(
      Query query,
      List<Item> items,
      List<PendingReview> pendingReviews,
      boolean safeMode,
      long verifiedAtMillis) {
    if (!accepts(query)) return false;
    List<Item> nextItems = immutable(items);
    List<PendingReview> nextPending = immutable(pendingReviews);
    Snapshot old = current;
    activeQuery = null;
    current =
        new Snapshot(
            old.version + 1,
            old.invalidationSeq,
            verifiedAtMillis,
            old.source,
            true,
            false,
            old.invalidationSeq != query.invalidationSeq,
            "",
            nextItems,
            nextPending,
            safeMode);
    return true;
  }

  /** 失败不把未知写成零项，也不抹掉同来源上次完整结果。 */
  public synchronized boolean failQuery(Query query, String error) {
    if (!accepts(query)) return false;
    failSource(error);
    return true;
  }

  /** 来源读取本身失败时使用；退休在途查询，保留旧结果并明确陈旧。 */
  public synchronized void failSource(String error) {
    Snapshot old = current;
    activeQuery = null;
    current =
        new Snapshot(
            old.version + 1,
            old.invalidationSeq + 1,
            old.verifiedAtMillis,
            old.source,
            old.known,
            false,
            true,
            error == null || error.isEmpty() ? "PLUGIN_SOURCE_UNAVAILABLE" : error,
            old.items,
            old.pendingReviews,
            old.safeMode);
  }

  /** delete-list 等已确认回执只更新目录项；完整扫描仍需确认其他状态。 */
  public synchronized boolean recordConfirmedItems(PluginSourceIdentity source, List<Item> items) {
    if (!Objects.equals(current.source, source) || source == null) return false;
    List<Item> next = immutable(items);
    Snapshot old = current;
    activeQuery = null;
    current =
        new Snapshot(
            old.version + 1,
            old.invalidationSeq + 1,
            old.verifiedAtMillis,
            source,
            true,
            false,
            true,
            "",
            next,
            old.pendingReviews,
            old.safeMode);
    return true;
  }

  /** 仅在执行口确认启停后更新精确目标，绝不按同名覆盖动态插件。 */
  public synchronized boolean updateConfirmedTarget(
      PluginSourceIdentity source, String stableKey, Item confirmed) {
    if (source == null
        || !source.equals(current.source)
        || !current.known
        || stableKey == null
        || stableKey.isEmpty()
        || confirmed == null
        || confirmed.dynamic
        || !stableKey.equals(confirmed.stableKey)) return false;
    List<Item> next = new ArrayList<>(current.items);
    int selected = -1;
    for (int i = 0; i < next.size(); i++) {
      Item existing = next.get(i);
      if (!existing.dynamic && stableKey.equals(existing.stableKey)) {
        if (selected >= 0) return false;
        selected = i;
      }
    }
    if (selected < 0) return false;
    next.set(selected, confirmed);
    return recordConfirmedItems(source, next);
  }

  /**
   * 心跳只更新当前来源的运行投影，不提供持久目录证明，也不取消正在进行的完整扫描。 持久条目沿当前快照保留，仅采纳其运行字段，防止较早投影覆盖后来的启停回执。 返回 true
   * 仅表示条目内容发生变化；相同投影不递增版本。
   */
  public synchronized boolean updateRuntimeItems(PluginSourceIdentity source, List<Item> items) {
    if (source == null || !source.equals(current.source)) return false;
    List<Item> projection = immutable(items);
    java.util.Map<String, Item> persistent = new java.util.HashMap<>();
    java.util.Set<String> keys = new java.util.HashSet<>();
    for (Item item : projection) {
      if (!keys.add(item.stableKey)) return false;
      if (item.dynamic) {
        if (item.runtimeGeneration != source.webGeneration) return false;
      } else persistent.put(item.stableKey, item);
    }
    Snapshot old = current;
    List<Item> next = new ArrayList<>();
    for (Item item : old.items) {
      if (item.dynamic) continue;
      Item runtime = persistent.remove(item.stableKey);
      if (runtime != null) {
        if (runtime.runtimeGeneration != -1 && runtime.runtimeGeneration != source.webGeneration)
          return false;
        if (!item.version.equals(runtime.version) || !item.origin.equals(runtime.origin)) {
          next.add(item);
          continue;
        }
        boolean restartRequired = item.restartRequired;
        if (item.enabled == runtime.enabled) {
          if (runtime.restartRequired) restartRequired = true;
          else if (!runtime.runtimeStale
              && runtime.runtimeGeneration == source.webGeneration
              && (item.enabled && "loaded".equals(runtime.runtimeState)
                  || !item.enabled
                      && ("unloaded".equals(runtime.runtimeState)
                          || "disabled".equals(runtime.runtimeState)))) restartRequired = false;
        }
        next.add(
            withRuntime(
                item,
                runtime.runtimeState,
                runtime.runtimeGeneration,
                runtime.runtimeStale,
                restartRequired));
      } else next.add(item);
    }
    // 心跳不能凭空发现新的持久安装；仍须完整目录查询确认。
    if (!persistent.isEmpty()) return false;
    for (Item item : projection) if (item.dynamic) next.add(item);
    if (sameItems(old.items, next)) return false;
    current =
        new Snapshot(
            old.version + 1,
            old.invalidationSeq,
            old.verifiedAtMillis,
            old.source,
            old.known,
            old.loading,
            old.stale,
            old.error,
            Collections.unmodifiableList(next),
            old.pendingReviews,
            old.safeMode);
    return true;
  }

  private static Item withRuntime(
      Item item, String state, long generation, boolean stale, boolean restartRequired) {
    if (state.equals(item.runtimeState)
        && generation == item.runtimeGeneration
        && stale == item.runtimeStale
        && restartRequired == item.restartRequired) return item;
    try {
      return item.withRuntime(state, generation, stale).withRestartRequired(restartRequired);
    } catch (Exception invalid) {
      throw new IllegalStateException("PLUGIN_RUNTIME_PROJECTION", invalid);
    }
  }

  private static boolean sameItems(List<Item> left, List<Item> right) {
    if (left.size() != right.size()) return false;
    for (int i = 0; i < left.size(); i++) {
      Item before = left.get(i), after = right.get(i);
      if (!before.stableKey.equals(after.stableKey) || !before.contentKey.equals(after.contentKey))
        return false;
    }
    return true;
  }

  private boolean accepts(Query query) {
    return query != null
        && query == activeQuery
        && query.sourceEpoch == sourceEpoch
        && query.source.equals(current.source);
  }

  private static <T> List<T> immutable(List<T> values) {
    Objects.requireNonNull(values);
    ArrayList<T> copy = new ArrayList<>(values.size());
    for (T value : values) copy.add(Objects.requireNonNull(value));
    return Collections.unmodifiableList(copy);
  }
}
