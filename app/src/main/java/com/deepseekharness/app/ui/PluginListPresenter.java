package com.deepseekharness.app.ui;

import com.deepseekharness.app.util.PluginSort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 插件页的纯投影：身份、已核验配置与队列意图分开；不读磁盘，不执行插件操作。 */
public final class PluginListPresenter {
  private PluginListPresenter() {}

  public static final int HEADER_COUNT = 1;
  public static final String HEADER_KEY = "@plugin-page/header";
  public static final String FOOTER_KEY = "@plugin-page/footer";
  public static final int CONTENT = 1;
  public static final int OPERATION = 2;
  public static final int EXPANSION = 4;

  /** value 只供界面回传意图；稳定身份及内容摘要来自管理层。 */
  public static final class Entry<T> {
    public final T value;
    public final String stableKey, contentKey, name, description;
    public final boolean system, observedEnabled, available, dynamic, updateAvailable;

    public Entry(
        T value,
        String stableKey,
        String contentKey,
        String name,
        String description,
        boolean system,
        boolean observedEnabled,
        boolean available,
        boolean dynamic,
        boolean updateAvailable) {
      if (stableKey == null || stableKey.isEmpty()) throw new IllegalArgumentException("插件行缺少稳定身份");
      this.value = value;
      this.stableKey = stableKey;
      this.contentKey = Objects.requireNonNull(contentKey);
      this.name = name;
      this.description = description;
      this.system = system;
      this.observedEnabled = observedEnabled;
      this.available = available;
      this.dynamic = dynamic;
      this.updateAvailable = updateAvailable;
    }
  }

  public static final class Operation {
    public final String operationId, targetKey, phase, message;
    public final Boolean desired;
    public final int percent;
    public final boolean pending;

    public Operation(
        String operationId,
        String targetKey,
        Boolean desired,
        String phase,
        String message,
        int percent,
        boolean pending) {
      this.operationId = operationId;
      this.targetKey = targetKey;
      this.desired = desired;
      this.phase = phase;
      this.message = message;
      this.percent = percent;
      this.pending = pending;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Operation value)) return false;
      return Objects.equals(operationId, value.operationId)
          && Objects.equals(targetKey, value.targetKey)
          && Objects.equals(desired, value.desired)
          && Objects.equals(phase, value.phase)
          && Objects.equals(message, value.message)
          && percent == value.percent
          && pending == value.pending;
    }

    @Override
    public int hashCode() {
      return Objects.hash(operationId, targetKey, desired, phase, message, percent, pending);
    }
  }

  public static final class Row<T> {
    public final Entry<T> entry;
    public final Operation operation;
    public final boolean expanded;

    Row(Entry<T> entry, Operation operation, boolean expanded) {
      this.entry = entry;
      this.operation = operation;
      this.expanded = expanded;
    }

    public boolean pending() {
      return operation != null && operation.pending;
    }

    /** 开关表达最新待处理意图；状态文字仍使用 entry.observedEnabled。 */
    public boolean checked() {
      return pending() && operation.desired != null ? operation.desired : entry.observedEnabled;
    }

    public boolean toggleEnabled() {
      return !entry.dynamic && (entry.available || entry.observedEnabled || pending());
    }
  }

  /** 缓存不可变目录输入与视图规则；进度/展开变化不再执行映射、过滤或排序。 */
  public static final class Projection<T> {
    private final java.util.function.Function<T, String> stableKey, contentKey;
    private final java.util.function.Function<T, Entry<T>> mapper;
    private List<T> source;
    private List<Entry<T>> entries = Collections.emptyList();
    private List<Row<T>> visible = Collections.emptyList();
    private List<Operation> previousOperations = Collections.emptyList();
    private Set<String> previousExpanded = Collections.emptySet();
    private String previousQuery;
    private boolean previousHideSystem;
    private PluginSort.Mode previousSort;
    private long selectionVersion;

    public Projection(
        java.util.function.Function<T, String> stableKey,
        java.util.function.Function<T, String> contentKey,
        java.util.function.Function<T, Entry<T>> mapper) {
      this.stableKey = stableKey;
      this.contentKey = contentKey;
      this.mapper = mapper;
    }

    /** 仅过滤排序阶段前进；页面据此更新可见实体缓存，宿主测试也可验证执行次数。 */
    public long selectionVersion() {
      return selectionVersion;
    }

    public List<Row<T>> project(
        List<T> source,
        List<Operation> operations,
        String query,
        boolean hideSystem,
        PluginSort.Mode sort,
        Set<String> expanded) {
      boolean directoryChanged = this.source == null;
      if (this.source != source) {
        directoryChanged |= !sameDirectory(source);
        if (directoryChanged) {
          List<Entry<T>> next = new ArrayList<>(source.size());
          for (T value : source) next.add(mapper.apply(value));
          entries = Collections.unmodifiableList(next);
        }
        this.source = source;
      }
      String normalizedQuery = normalizeQuery(query);
      if (directoryChanged
          || !Objects.equals(previousQuery, normalizedQuery)
          || previousHideSystem != hideSystem
          || previousSort != sort) {
        List<Row<T>> selected =
            rows(entries, operations, normalizedQuery, hideSystem, sort, expanded);
        visible = reuseRows(visible, selected);
        previousQuery = normalizedQuery;
        previousHideSystem = hideSystem;
        previousSort = sort;
        selectionVersion++;
      } else {
        if (!previousOperations.equals(operations)) visible = withOperations(visible, operations);
        if (!previousExpanded.equals(expanded)) visible = withExpansion(visible, expanded);
      }
      if (!previousOperations.equals(operations))
        previousOperations = Collections.unmodifiableList(new ArrayList<>(operations));
      if (!previousExpanded.equals(expanded))
        previousExpanded = Collections.unmodifiableSet(new java.util.HashSet<>(expanded));
      return visible;
    }

    private boolean sameDirectory(List<T> source) {
      if (source.size() != entries.size()) return false;
      // 后端返回顺序变化不影响本页排序；比较规范身份和内容键，不重新建立 Entry。
      Map<String, String> known = new HashMap<>();
      for (Entry<T> entry : entries) known.put(entry.stableKey, entry.contentKey);
      if (known.size() != entries.size()) return false;
      for (T value : source)
        if (!Objects.equals(known.remove(stableKey.apply(value)), contentKey.apply(value)))
          return false;
      return known.isEmpty();
    }
  }

  /** 只更新既有顺序上的操作状态；无变化时返回原列表，每个未变化行也保留原引用。 */
  public static <T> List<Row<T>> withOperations(List<Row<T>> rows, List<Operation> operations) {
    Map<String, Operation> latest = latestOperations(operations);
    List<Row<T>> changed = null;
    for (int i = 0; i < rows.size(); i++) {
      Row<T> row = rows.get(i);
      Operation operation = latest.get(row.entry.stableKey);
      if (Objects.equals(row.operation, operation)) continue;
      if (changed == null) changed = new ArrayList<>(rows);
      changed.set(i, new Row<>(row.entry, operation, row.expanded));
    }
    return changed == null ? rows : Collections.unmodifiableList(changed);
  }

  public static <T> List<Row<T>> withExpansion(List<Row<T>> rows, Set<String> expanded) {
    List<Row<T>> changed = null;
    for (int i = 0; i < rows.size(); i++) {
      Row<T> row = rows.get(i);
      boolean nextExpanded = expanded.contains(row.entry.stableKey);
      if (row.expanded == nextExpanded) continue;
      if (changed == null) changed = new ArrayList<>(rows);
      changed.set(i, new Row<>(row.entry, row.operation, nextExpanded));
    }
    return changed == null ? rows : Collections.unmodifiableList(changed);
  }

  private static <T> List<Row<T>> reuseRows(List<Row<T>> previous, List<Row<T>> selected) {
    Map<String, Row<T>> old = new HashMap<>();
    for (Row<T> row : previous) old.put(row.entry.stableKey, row);
    List<Row<T>> retained = new ArrayList<>(selected.size());
    boolean identical = previous.size() == selected.size();
    for (int i = 0; i < selected.size(); i++) {
      Row<T> next = selected.get(i);
      Row<T> before = old.get(next.entry.stableKey);
      Row<T> row = before != null && changes(before, next) == 0 ? before : next;
      retained.add(row);
      if (identical && row != previous.get(i)) identical = false;
    }
    return identical ? previous : Collections.unmodifiableList(retained);
  }

  private static String normalizeQuery(String query) {
    return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
  }

  private static Map<String, Operation> latestOperations(List<Operation> operations) {
    Map<String, Operation> latest = new HashMap<>();
    // 队列顺序为 active、等待 FIFO、最新历史在前。最后一个待处理意图优先；
    // 终态历史不能覆盖它，没有 pending 时仅保留最新一条历史。
    for (Operation operation : operations) {
      if (operation.pending) latest.put(operation.targetKey, operation);
      else latest.putIfAbsent(operation.targetKey, operation);
    }
    return latest;
  }

  public static <T> List<Row<T>> rows(
      List<Entry<T>> entries,
      List<Operation> operations,
      String query,
      boolean hideSystem,
      PluginSort.Mode sort,
      Set<String> expanded) {
    Map<String, Operation> latest = latestOperations(operations);
    String needle = normalizeQuery(query);
    List<Row<T>> rows = new ArrayList<>();
    for (Entry<T> entry : entries) {
      if (hideSystem && entry.system) continue;
      if (!(entry.name + " " + entry.description).toLowerCase(Locale.ROOT).contains(needle))
        continue;
      rows.add(new Row<>(entry, latest.get(entry.stableKey), expanded.contains(entry.stableKey)));
    }
    // 排序只看核验配置，排队中的开关不把尚未生效的意图冒充为配置。
    rows.sort(
        PluginSort.comparator(
                sort,
                (Row<T> row) -> row.entry.name,
                row -> row.entry.observedEnabled,
                row -> row.entry.updateAvailable)
            .thenComparing(row -> row.entry.stableKey));
    return Collections.unmodifiableList(rows);
  }

  public static boolean sameItem(Row<?> before, Row<?> after) {
    return before.entry.stableKey.equals(after.entry.stableKey);
  }

  /** 进度/意图和展开变化可局部绑定，不重建描述、详情及无关插件行。 */
  public static int changes(Row<?> before, Row<?> after) {
    int changes = before.entry.contentKey.equals(after.entry.contentKey) ? 0 : CONTENT;
    if (!Objects.equals(before.operation, after.operation)) changes |= OPERATION;
    if (before.expanded != after.expanded) changes |= EXPANSION;
    return changes;
  }

  public static int adapterPosition(int rowPosition) {
    if (rowPosition < 0) throw new IllegalArgumentException("插件行位置不能为负数");
    return rowPosition + HEADER_COUNT;
  }

  public enum EmptyState {
    INITIAL,
    LOADING,
    FAILED,
    EMPTY,
    FILTERED,
    CONTENT
  }

  public static EmptyState emptyState(
      boolean known, boolean loading, String error, int total, int visible) {
    if (!known) {
      if (loading) return EmptyState.LOADING;
      return error != null && !error.isEmpty() ? EmptyState.FAILED : EmptyState.INITIAL;
    }
    if (visible > 0) return EmptyState.CONTENT;
    // 保留上次已核验空快照时，刷新失败仍明确显示失败，不当作本次确认的零条。
    if (error != null && !error.isEmpty()) return EmptyState.FAILED;
    return total == 0 ? EmptyState.EMPTY : EmptyState.FILTERED;
  }

  public static final class Anchor {
    public final String key;
    public final int position, offset;

    public Anchor(String key, int position, int offset) {
      this.key = key;
      this.position = Math.max(0, position);
      this.offset = offset;
    }
  }

  public static int anchorPosition(Anchor anchor, List<? extends Row<?>> rows) {
    if (HEADER_KEY.equals(anchor.key)) return 0;
    if (FOOTER_KEY.equals(anchor.key)) return adapterPosition(rows.size());
    for (int i = 0; i < rows.size(); i++)
      if (rows.get(i).entry.stableKey.equals(anchor.key)) return adapterPosition(i);
    return Math.min(anchor.position, adapterPosition(rows.size()));
  }
}
