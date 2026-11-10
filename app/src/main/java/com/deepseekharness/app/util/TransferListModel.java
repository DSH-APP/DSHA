package com.deepseekharness.app.util;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 下载记录页的纯逻辑：筛选、分组、步骤、主操作与失败原因分类。
 *
 * <p>状态以 {@code DownloadTask.Status} 的名称字符串传入，本类不依赖 Android 与传输引擎，便于单测锁定
 * 页面语义。原型见 docs/design/downloads-20261102/preview.html。
 */
public final class TransferListModel {
  private TransferListModel() {}

  /** 筛选分段：全部 / 进行中（含暂停与待选位置）/ 失败 / 已结束（完成与取消）。 */
  public enum Filter {
    ALL,
    ACTIVE,
    FAILED,
    DONE
  }

  /** 列表分组：需要处理 → 正在传输 → 按创建日期的已结束记录。 */
  public enum SectionKind {
    ATTENTION,
    TRANSFERRING,
    DAY
  }

  /** 详情页三步进度中单步的状态。 */
  public enum Step {
    TODO,
    CURRENT,
    PAUSED,
    DONE,
    FAILED
  }

  /** 行内与详情主操作；危险操作单独放在管理区。 */
  public enum Action {
    OPEN,
    SHARE,
    PAUSE,
    RESUME,
    RETRY,
    CHOOSE,
    CANCEL,
    REMOVE
  }

  /** 文件图标类别，只按扩展名推断，不读取文件内容。 */
  public enum Kind {
    CODE,
    IMAGE,
    DOCUMENT,
    ARCHIVE,
    TEXT
  }

  /** 与 FileTransfersActivity 原 errorText 相同的判别顺序。 */
  public enum Failure {
    CANCELLED,
    NO_SPACE,
    PERMISSION,
    INTEGRITY,
    RUNTIME,
    UNAUTHORIZED,
    SOURCE_GONE,
    INTERRUPTED,
    LENGTH,
    SIZE_LIMIT,
    TASK_LIMIT,
    NOT_IMAGE,
    BUSY,
    SAVE,
    UNAVAILABLE,
    OTHER
  }

  /** 列表所需的最小记录信息。 */
  public static final class Entry {
    public final String id, status;
    public final long createdAt;

    public Entry(String id, String status, long createdAt) {
      this.id = id;
      this.status = status;
      this.createdAt = createdAt;
    }
  }

  public static final class Section {
    public final SectionKind kind;

    /** 仅 {@link SectionKind#DAY} 有效：本地时区的 epoch day。 */
    public final long epochDay;

    public final List<Entry> entries = new ArrayList<>();

    Section(SectionKind kind, long epochDay) {
      this.kind = kind;
      this.epochDay = epochDay;
    }
  }

  public static boolean active(String status) {
    return "QUEUED".equals(status)
        || "DOWNLOADING".equals(status)
        || "VERIFYING".equals(status)
        || "PUBLISHING".equals(status);
  }

  public static boolean attention(String status) {
    return "FAILED".equals(status)
        || "PAUSED".equals(status)
        || "WAITING_DESTINATION".equals(status);
  }

  public static boolean finished(String status) {
    return "COMPLETE".equals(status) || "CANCELLED".equals(status);
  }

  public static Filter category(String status) {
    if ("FAILED".equals(status)) return Filter.FAILED;
    return finished(status) ? Filter.DONE : Filter.ACTIVE;
  }

  public static boolean matches(Filter filter, String status) {
    return filter == Filter.ALL || category(status) == filter;
  }

  /** 各分段数量，ALL 为总数。 */
  public static Map<Filter, Integer> counts(List<Entry> entries) {
    Map<Filter, Integer> result = new LinkedHashMap<>();
    for (Filter filter : Filter.values()) result.put(filter, 0);
    for (Entry entry : entries) {
      result.merge(Filter.ALL, 1, Integer::sum);
      result.merge(category(entry.status), 1, Integer::sum);
    }
    return result;
  }

  /** 只有两类以上的记录时筛选才有意义。 */
  public static boolean showFilter(Map<Filter, Integer> counts) {
    int kinds = 0;
    for (Filter filter : new Filter[] {Filter.ACTIVE, Filter.FAILED, Filter.DONE})
      if (counts.getOrDefault(filter, 0) > 0) kinds++;
    return kinds >= 2;
  }

  /** 当前筛选没有记录时回到全部，避免记录变化后停在空分段。 */
  public static Filter effective(Filter filter, Map<Filter, Integer> counts) {
    return filter == null || counts.getOrDefault(filter, 0) == 0 ? Filter.ALL : filter;
  }

  public static List<Section> sections(List<Entry> entries, Filter filter, ZoneId zone) {
    List<Entry> sorted = new ArrayList<>();
    for (Entry entry : entries) if (matches(filter, entry.status)) sorted.add(entry);
    sorted.sort(
        Comparator.comparingLong((Entry entry) -> entry.createdAt)
            .reversed()
            .thenComparing(entry -> entry.id));
    Section attention = new Section(SectionKind.ATTENTION, 0);
    Section transferring = new Section(SectionKind.TRANSFERRING, 0);
    List<Section> days = new ArrayList<>();
    for (Entry entry : sorted) {
      if (attention(entry.status)) attention.entries.add(entry);
      else if (active(entry.status)) transferring.entries.add(entry);
      else {
        long day = epochDay(entry.createdAt, zone);
        Section last = days.isEmpty() ? null : days.get(days.size() - 1);
        if (last == null || last.epochDay != day) {
          last = new Section(SectionKind.DAY, day);
          days.add(last);
        }
        last.entries.add(entry);
      }
    }
    List<Section> result = new ArrayList<>();
    if (!attention.entries.isEmpty()) result.add(attention);
    if (!transferring.entries.isEmpty()) result.add(transferring);
    result.addAll(days);
    return result;
  }

  public static long epochDay(long millis, ZoneId zone) {
    return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay();
  }

  /** 下载 / 校验 / 保存三步；取消的记录不显示步骤。 */
  public static Step[] steps(String status, Failure failure) {
    switch (status) {
      case "QUEUED":
      case "DOWNLOADING":
        return new Step[] {Step.CURRENT, Step.TODO, Step.TODO};
      case "PAUSED":
        return new Step[] {Step.PAUSED, Step.TODO, Step.TODO};
      case "VERIFYING":
        return new Step[] {Step.DONE, Step.CURRENT, Step.TODO};
      case "WAITING_DESTINATION":
      case "PUBLISHING":
        return new Step[] {Step.DONE, Step.DONE, Step.CURRENT};
      case "COMPLETE":
        return new Step[] {Step.DONE, Step.DONE, Step.DONE};
      case "FAILED":
        if (failure == Failure.INTEGRITY || failure == Failure.LENGTH)
          return new Step[] {Step.DONE, Step.FAILED, Step.TODO};
        if (failure == Failure.SAVE
            || failure == Failure.PERMISSION
            || failure == Failure.NOT_IMAGE) return new Step[] {Step.DONE, Step.DONE, Step.FAILED};
        return new Step[] {Step.FAILED, Step.TODO, Step.TODO};
      default:
        return new Step[0];
    }
  }

  /** 行内只露第一项；详情页按顺序全部显示。校验与保存阶段没有主操作。 */
  public static List<Action> primary(String status) {
    switch (status) {
      case "COMPLETE":
        return List.of(Action.OPEN, Action.SHARE);
      case "QUEUED":
      case "DOWNLOADING":
        return List.of(Action.PAUSE);
      case "PAUSED":
        return List.of(Action.RESUME);
      case "WAITING_DESTINATION":
        return List.of(Action.CHOOSE);
      case "FAILED":
        return List.of(Action.RETRY);
      default:
        return List.of();
    }
  }

  /** 管理区的危险操作：进行中只能取消，结束后只能移除记录（引擎不移除运行中的记录）。 */
  public static Action manage(String status) {
    return "FAILED".equals(status) || finished(status) ? Action.REMOVE : Action.CANCEL;
  }

  /** 取消会丢弃已下载的字节时需要二次确认。 */
  public static boolean cancelDiscards(String status, long downloaded) {
    return downloaded > 0
        && ("QUEUED".equals(status) || "DOWNLOADING".equals(status) || "PAUSED".equals(status));
  }

  public static Kind kind(String name) {
    String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
    int dot = value.lastIndexOf('.');
    String ext = dot < 0 ? "" : value.substring(dot + 1);
    switch (ext) {
      case "html":
      case "htm":
      case "js":
      case "mjs":
      case "json":
      case "css":
      case "xml":
      case "py":
      case "sh":
      case "java":
      case "ts":
        return Kind.CODE;
      case "png":
      case "jpg":
      case "jpeg":
      case "webp":
      case "gif":
      case "bmp":
      case "svg":
      case "heic":
        return Kind.IMAGE;
      case "pdf":
      case "doc":
      case "docx":
      case "ppt":
      case "pptx":
      case "xls":
      case "xlsx":
      case "odt":
        return Kind.DOCUMENT;
      case "zip":
      case "gz":
      case "tgz":
      case "tar":
      case "7z":
      case "xz":
      case "bz2":
      case "rar":
      case "apk":
        return Kind.ARCHIVE;
      default:
        return Kind.TEXT;
    }
  }

  /** 文件头副标题用的扩展名标签（大写，最多 8 个字符）；没有扩展名返回空串。 */
  public static String extension(String name) {
    if (name == null) return "";
    int dot = name.lastIndexOf('.');
    if (dot <= 0 || dot == name.length() - 1) return "";
    String ext = name.substring(dot + 1).toUpperCase(Locale.ROOT);
    return ext.length() > 8 || !ext.chars().allMatch(Character::isLetterOrDigit) ? "" : ext;
  }

  /** 同名冲突后保存为新名称时显示「已自动改名」。 */
  public static boolean renamed(String displayName, String artifactName) {
    return displayName != null
        && !displayName.isEmpty()
        && artifactName != null
        && !artifactName.isEmpty()
        && !displayName.equals(artifactName);
  }

  /** 0–100；总量未知返回 -1。 */
  public static int percent(long downloaded, long total) {
    if (total <= 0) return -1;
    return (int) Math.max(0, Math.min(100, Math.floor(downloaded * 100.0 / total)));
  }

  /** 剩余秒数；速度或总量未知返回 -1。 */
  public static long etaSeconds(long downloaded, long total, long speed) {
    if (speed <= 0 || total <= 0 || downloaded >= total) return -1;
    return Math.max(1, (long) Math.ceil((total - downloaded) / (double) speed));
  }

  public static Failure failure(String raw) {
    String lower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    if (lower.contains("task_cancelled")) return Failure.CANCELLED;
    if (has(lower, "enospc", "no space", "no_space", "insufficient", "space_low"))
      return Failure.NO_SPACE;
    if (has(lower, "permission", "eacces", "securityexception")) return Failure.PERMISSION;
    if (has(lower, "source_changed", "artifact_changed", "http_409", "integrity", "sha256"))
      return Failure.INTEGRITY;
    if (has(lower, "runtime_not_ready", "runtime_changed", "auth_required")) return Failure.RUNTIME;
    if (has(lower, "http_401", "http_403")) return Failure.UNAUTHORIZED;
    if (has(lower, "http_404", "http_410", "local_copy_missing")) return Failure.SOURCE_GONE;
    if (has(lower, "transfer_interrupted", "transfer_failed", "http_5")) return Failure.INTERRUPTED;
    if (has(lower, "length_invalid", "range_invalid", "partial_invalid")) return Failure.LENGTH;
    if (lower.contains("limit_5gb")) return Failure.SIZE_LIMIT;
    if (lower.contains("task_limit")) return Failure.TASK_LIMIT;
    if (lower.contains("not_real_image")) return Failure.NOT_IMAGE;
    if (lower.contains("task_busy")) return Failure.BUSY;
    if (has(lower, "save_target", "destination_create", "publication_failed")) return Failure.SAVE;
    if (has(lower, "task_not_found", "not_complete", "uri_unavailable")) return Failure.UNAVAILABLE;
    return Failure.OTHER;
  }

  private static boolean has(String value, String... needles) {
    for (String needle : needles) if (value.contains(needle)) return true;
    return false;
  }
}
