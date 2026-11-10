package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 外接设备镜像导入槽位的配额与过期规则（纯逻辑）。
 *
 * <p>导入目录是 App 私有的一次性中转区，不是用户数据：过期副本直接回收；未过期的副本占满配额时拒绝新导入，
 * 不为腾位置删除用户刚导入、仍可能要用的文件。只认 {@code <UUID>-<安全名>} 形状的条目，其余（如 adb-home）不计入、不删除。
 */
public final class ExternalImportQuota {
  public static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;
  public static final int MAX_FILES = 4;
  public static final long TTL_MILLIS = 24L * 60 * 60 * 1000;
  private static final String NAME = "[0-9a-f-]{36}-[A-Za-z0-9._-]{1,128}";

  private ExternalImportQuota() {}

  /** 目录中的一个条目；regularFile 须由调用方用 NOFOLLOW 判定。 */
  public static final class Entry {
    public final String name;
    public final long size, modifiedMillis;
    public final boolean regularFile;

    public Entry(String name, long size, long modifiedMillis, boolean regularFile) {
      this.name = name;
      this.size = size;
      this.modifiedMillis = modifiedMillis;
      this.regularFile = regularFile;
    }
  }

  public static final class Plan {
    /** 可回收的过期导入文件名。 */
    public final List<String> expired;

    /** null 表示允许导入；否则为拒绝原因。 */
    public final String rejection;

    Plan(List<String> expired, String rejection) {
      this.expired = Collections.unmodifiableList(expired);
      this.rejection = rejection;
    }

    public boolean allowed() {
      return rejection == null;
    }
  }

  public static boolean isImportName(String name) {
    return name != null && name.matches(NAME);
  }

  /** 已导入文件是否已过期；修改时间在未来（时钟回拨）按未过期处理，等下次正常时间再回收。 */
  public static boolean expired(long modifiedMillis, long nowMillis) {
    return modifiedMillis <= nowMillis && nowMillis - modifiedMillis >= TTL_MILLIS;
  }

  /** 先回收过期条目，再按剩余条目判断本次 incomingBytes 能否放下。 */
  public static Plan plan(List<Entry> entries, long incomingBytes, long nowMillis) {
    List<String> expired = new ArrayList<>();
    if (incomingBytes <= 0 || incomingBytes > MAX_TOTAL_BYTES)
      return new Plan(expired, "IMPORT_SIZE");
    long kept = 0;
    int keptFiles = 0;
    for (Entry entry : entries == null ? List.<Entry>of() : entries) {
      if (entry == null || !isImportName(entry.name)) continue;
      // 同名但不是普通文件（链接 / 目录）属于异常现场：不删除、不跟随，按满额计，宁可拒绝导入。
      if (!entry.regularFile) return new Plan(new ArrayList<>(), "IMPORT_SLOT_UNEXPECTED");
      if (expired(entry.modifiedMillis, nowMillis)) {
        expired.add(entry.name);
        continue;
      }
      keptFiles++;
      kept += Math.max(0, entry.size);
    }
    if (keptFiles >= MAX_FILES) return new Plan(expired, "IMPORT_QUOTA_FILES");
    if (kept > MAX_TOTAL_BYTES - incomingBytes) return new Plan(expired, "IMPORT_QUOTA_BYTES");
    return new Plan(expired, null);
  }
}
