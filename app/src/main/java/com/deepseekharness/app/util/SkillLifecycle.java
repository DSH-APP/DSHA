package com.deepseekharness.app.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 技能启用 / 禁用 / 最近删除的纯规则。纯逻辑，不 import Android API。
 *
 * <p>DSH 只发现技能根目录下的 {@code <名>/SKILL.md} 与 {@code <名>.md}（dsh-skill-filesystem 0.2.2-rc.1）。
 * 禁用 = 原地追加 {@link #DISABLED_SUFFIX}，目录内的脚本与资料不动；启用再改回。
 *
 * <p>最近删除放在技能根目录之外的 {@link #TRASH_DIRECTORY}，每项一个随机槽位：{@code meta} 记录原名与删除时间，
 * {@code item} 是整体改名过去的原条目。过期只按删除时间与保留天数判定，时钟回拨不提前删除。
 */
public final class SkillLifecycle {
  private SkillLifecycle() {}

  public static final String DOCUMENT = "SKILL.md";
  public static final String DISABLED_SUFFIX = ".disabled";
  public static final String DISABLED_DOCUMENT = DOCUMENT + DISABLED_SUFFIX;

  /** 与 {@code skills} 同级的私有目录；DSH 不扫描它。 */
  public static final String TRASH_DIRECTORY = ".dsha-skill-trash";

  public static final String TRASH_META = "meta";
  public static final String TRASH_ITEM = "item";
  public static final int MAX_META_BYTES = 4096;
  public static final int MAX_TRASH_ENTRIES = 512;

  public static final int DEFAULT_RETENTION_DAYS = 30;
  public static final int MIN_RETENTION_DAYS = 1;
  public static final int MAX_RETENTION_DAYS = 365;
  public static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

  private static final java.util.regex.Pattern SLOT =
      java.util.regex.Pattern.compile(
          "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  /** 条目形态：目录技能（{@code <名>/SKILL.md}）或单文件技能（{@code <名>.md}）。 */
  public enum Kind {
    DIRECTORY,
    FILE
  }

  /** 根目录下的单文件名是否表示一个已禁用的单文件技能。 */
  public static boolean disabledFile(String member) {
    return member != null
        && member.endsWith(".md" + DISABLED_SUFFIX)
        && member.length() > (".md" + DISABLED_SUFFIX).length();
  }

  /** 单文件技能启用 / 禁用后的文件名；不是 .md / .md.disabled 时拒绝。 */
  public static String toggledFile(String member) throws IOException {
    member(member);
    if (disabledFile(member))
      return member.substring(0, member.length() - DISABLED_SUFFIX.length());
    if (member.endsWith(".md") && member.length() > 3) return member + DISABLED_SUFFIX;
    throw new IOException("SKILL_STATE_NAME");
  }

  /** 根目录成员名：单段、非空、不含分隔符与 . / ..，不以 . 开头（隐藏项不是用户技能）。 */
  public static String member(String value) throws IOException {
    if (value == null
        || value.isEmpty()
        || value.length() > 255
        || value.indexOf('/') >= 0
        || value.indexOf('\\') >= 0
        || value.indexOf('\0') >= 0
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0
        || value.startsWith(".")) throw new IOException("SKILL_MEMBER");
    return value;
  }

  public static boolean slot(String value) {
    return value != null && SLOT.matcher(value).matches();
  }

  /** 保留天数：越界值收回默认值，避免一次误配置把最近删除全部清空。 */
  public static int retention(int days) {
    return days >= MIN_RETENTION_DAYS && days <= MAX_RETENTION_DAYS ? days : DEFAULT_RETENTION_DAYS;
  }

  /** 解析用户输入的天数；非数字或越界返回 -1，由界面提示，不静默改值。 */
  public static int parseRetention(String text) {
    if (text == null) return -1;
    String value = text.trim();
    if (value.isEmpty() || value.length() > 3) return -1;
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) < '0' || value.charAt(i) > '9') return -1;
    int days = Integer.parseInt(value);
    return days >= MIN_RETENTION_DAYS && days <= MAX_RETENTION_DAYS ? days : -1;
  }

  /** 已到期：删除时间晚于当前（时钟回拨）时视为未到期。 */
  public static boolean expired(long deletedAt, long now, int retentionDays) {
    if (deletedAt <= 0 || now < deletedAt) return false;
    return now - deletedAt >= retention(retentionDays) * DAY_MILLIS;
  }

  /** 剩余整天数（向上取整，最少 0）；用于「N 天后彻底删除」。 */
  public static int remainingDays(long deletedAt, long now, int retentionDays) {
    long total = retention(retentionDays) * DAY_MILLIS;
    long elapsed = now < deletedAt ? 0 : now - deletedAt;
    long left = total - elapsed;
    if (left <= 0) return 0;
    return (int) ((left + DAY_MILLIS - 1) / DAY_MILLIS);
  }

  /** 删除记录：原成员名、技能名、形态与删除时间。 */
  public static final class TrashMeta {
    public final String member, name;
    public final Kind kind;
    public final long deletedAt;

    public TrashMeta(String member, String name, Kind kind, long deletedAt) {
      this.member = member;
      this.name = name;
      this.kind = kind;
      this.deletedAt = deletedAt;
    }

    public byte[] encode() {
      return ("version=1\nmember="
              + member
              + "\nname="
              + name
              + "\nkind="
              + (kind == Kind.DIRECTORY ? "directory" : "file")
              + "\ndeleted="
              + deletedAt
              + "\n")
          .getBytes(StandardCharsets.UTF_8);
    }

    public static TrashMeta of(String member, String name, Kind kind, long deletedAt)
        throws IOException {
      try {
        member(member);
      } catch (IOException invalid) {
        throw new IOException("SKILL_TRASH_META", invalid);
      }
      if (name == null
          || name.isEmpty()
          || name.length() > SkillDocument.MAX_NAME_LENGTH
          || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new IOException("SKILL_TRASH_META");
      if (kind == Kind.FILE && !member.endsWith(".md") && !disabledFile(member))
        throw new IOException("SKILL_TRASH_META");
      if (deletedAt <= 0) throw new IOException("SKILL_TRASH_META");
      return new TrashMeta(member, name, kind, deletedAt);
    }

    /** 严格解析：未知版本、重复键、缺字段或非法值一律拒绝，不猜测原位置。 */
    public static TrashMeta decode(byte[] bytes) throws IOException {
      if (bytes == null || bytes.length == 0 || bytes.length > MAX_META_BYTES)
        throw new IOException("SKILL_TRASH_META");
      String text = new String(bytes, StandardCharsets.UTF_8);
      if (!text.endsWith("\n")) throw new IOException("SKILL_TRASH_META");
      Map<String, String> fields = new LinkedHashMap<>();
      for (String line : text.substring(0, text.length() - 1).split("\n", -1)) {
        int at = line.indexOf('=');
        if (at <= 0) throw new IOException("SKILL_TRASH_META");
        String key = line.substring(0, at);
        if (fields.put(key, line.substring(at + 1)) != null)
          throw new IOException("SKILL_TRASH_META");
      }
      if (!fields.keySet().equals(java.util.Set.of("version", "member", "name", "kind", "deleted"))
          || !"1".equals(fields.get("version"))) throw new IOException("SKILL_TRASH_META");
      String kind = fields.get("kind");
      Kind parsed =
          "directory".equals(kind) ? Kind.DIRECTORY : "file".equals(kind) ? Kind.FILE : null;
      if (parsed == null) throw new IOException("SKILL_TRASH_META");
      long deleted;
      try {
        deleted = Long.parseLong(fields.get("deleted"));
      } catch (NumberFormatException invalid) {
        throw new IOException("SKILL_TRASH_META", invalid);
      }
      return of(fields.get("member"), fields.get("name"), parsed, deleted);
    }
  }
}
