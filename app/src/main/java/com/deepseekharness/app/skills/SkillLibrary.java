package com.deepseekharness.app.skills;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.util.SkillDocument;
import com.deepseekharness.app.util.SkillLifecycle;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 已安装技能的启用 / 禁用、最近删除、恢复与彻底删除。
 *
 * <p>只用同一文件系统内的整体改名（不复制、不覆盖），改名前重读文档确认仍是界面看到的那个技能。 彻底删除只移除最近删除槽位里本 UID
 * 的条目，链接按链接删除不跟随。调用方持有与导入相同的环境门禁、维护屏障与 RuntimeTasks。
 */
public final class SkillLibrary {
  private final BackupFileSystem fs;
  private final File files;

  /** 最近删除中的一项。 */
  public static final class Item {
    public final String slot;
    public final SkillLifecycle.TrashMeta meta;

    /** 能读出的描述；读不出时为空，不影响恢复与删除。 */
    public final String description;

    /** 删除前是否已禁用（恢复后保持原状态）。 */
    public final boolean wasEnabled;

    Item(String slot, SkillLifecycle.TrashMeta meta, String description, boolean wasEnabled) {
      this.slot = slot;
      this.meta = meta;
      this.description = description;
      this.wasEnabled = wasEnabled;
    }
  }

  /** 删除确认里列出的文件（相对条目根，最多 {@link #PREVIEW_LIMIT} 项）。 */
  public static final class Preview {
    public final List<String> paths;
    public final long bytes;
    public final boolean truncated;

    Preview(List<String> paths, long bytes, boolean truncated) {
      this.paths = java.util.Collections.unmodifiableList(paths);
      this.bytes = bytes;
      this.truncated = truncated;
    }
  }

  public static final int PREVIEW_LIMIT = 200;

  /** 记录有效、条目缺失的槽位超过此时长才清理，避免与进行中的删除抢同一槽位。 */
  static final long ORPHAN_GRACE_MILLIS = 60L * 60 * 1000;

  public SkillLibrary(BackupFileSystem fs, File files) {
    this.fs = fs;
    this.files = files.getAbsoluteFile();
  }

  private File home() throws IOException {
    return new SkillImports(fs, files).home();
  }

  private File skillsRoot() throws IOException {
    File root = fs.child(home(), "skills");
    if (!fs.stat(root).type.equals("DIRECTORY")) throw new IOException("SKILL_DIRECTORY");
    return root;
  }

  // ---- 启用 / 禁用 ----

  /** 原地改名 SKILL.md ⇄ SKILL.md.disabled（单文件技能为 名.md ⇄ 名.md.disabled）；返回新状态的条目。 */
  public SkillImports.Entry setEnabled(
      SkillImports.Entry entry, boolean enabled, BackupControl control) throws IOException {
    control.check();
    if (entry.enabled == enabled) return entry;
    File root = skillsRoot();
    String member = SkillLifecycle.member(entry.member());
    File source, target;
    String relative;
    if (entry.directory()) {
      File folder = fs.child(root, member);
      if (!fs.stat(folder).type.equals("DIRECTORY")) throw new IOException("SKILL_CHANGED");
      String from = enabled ? SkillLifecycle.DISABLED_DOCUMENT : SkillLifecycle.DOCUMENT;
      String to = enabled ? SkillLifecycle.DOCUMENT : SkillLifecycle.DISABLED_DOCUMENT;
      if (!entry.relative.equals(member + "/" + from)) throw new IOException("SKILL_CHANGED");
      source = fs.child(folder, from);
      target = fs.child(folder, to);
      relative = member + "/" + to;
    } else {
      String to = SkillLifecycle.toggledFile(member);
      if (SkillLifecycle.disabledFile(member) != enabled) throw new IOException("SKILL_CHANGED");
      source = fs.child(root, member);
      target = fs.child(root, to);
      relative = to;
    }
    requireSameDocument(source, entry);
    if (!fs.stat(target).type.equals("MISSING")) throw new IOException("SKILL_STATE_CONFLICT");
    if (enabled) rejectEnabledDuplicate(entry, control);
    control.check();
    fs.move(source, target);
    fs.syncDirectory(target.getParentFile());
    return new SkillImports.Entry(relative, entry.document, enabled);
  }

  /** 启用后若与另一已启用技能同名，DSH 只会取其一；拒绝而不是制造不确定的覆盖。 */
  private void rejectEnabledDuplicate(SkillImports.Entry entry, BackupControl control)
      throws IOException {
    for (SkillImports.Entry other : new SkillImports(fs, files).list(control).entries)
      if (other.enabled
          && other.document.name.equals(entry.document.name)
          && !other.member().equals(entry.member())) throw new IOException("SKILL_EXISTS");
  }

  private void requireSameDocument(File document, SkillImports.Entry entry) throws IOException {
    var node = fs.stat(document);
    if (!node.type.equals("FILE")) throw new IOException("SKILL_CHANGED");
    SkillDocument current;
    try {
      current = SkillDocument.parse(fs.small(document, SkillDocument.MAX_BYTES));
    } catch (IOException changed) {
      throw new IOException("SKILL_CHANGED", changed);
    }
    if (!current.text.equals(entry.document.text) || !node.same(fs.stat(document)))
      throw new IOException("SKILL_CHANGED");
  }

  // ---- 删除预览 ----

  /** 列出删除会带走的文件；不跟随链接，链接本身作为一项列出。 */
  public Preview preview(SkillImports.Entry entry, BackupControl control) throws IOException {
    control.check();
    File root = skillsRoot();
    File member = fs.child(root, SkillLifecycle.member(entry.member()));
    var node = fs.stat(member);
    List<String> paths = new ArrayList<>();
    long[] bytes = {0};
    boolean[] truncated = {false};
    if (node.type.equals("DIRECTORY")) walk(member, "", paths, bytes, truncated, control);
    else if (node.type.equals("FILE")) {
      paths.add(entry.member());
      bytes[0] = node.size;
    } else throw new IOException("SKILL_CHANGED");
    return new Preview(paths, bytes[0], truncated[0]);
  }

  private void walk(
      File directory,
      String prefix,
      List<String> paths,
      long[] bytes,
      boolean[] truncated,
      BackupControl control)
      throws IOException {
    List<String> names = new ArrayList<>(fs.list(directory));
    java.util.Collections.sort(names);
    for (String name : names) {
      control.check();
      if (paths.size() >= PREVIEW_LIMIT) {
        truncated[0] = true;
        return;
      }
      File child = fs.child(directory, name);
      var node = fs.stat(child);
      String relative = prefix + name;
      if (node.type.equals("DIRECTORY"))
        walk(child, relative + "/", paths, bytes, truncated, control);
      else {
        paths.add(relative);
        if (node.type.equals("FILE")) bytes[0] += node.size;
      }
    }
  }

  // ---- 最近删除 ----

  /** 整体移入最近删除；返回槽位号。原条目已不在技能目录后才算成功。 */
  public String trash(SkillImports.Entry entry, long now, BackupControl control)
      throws IOException {
    control.check();
    File home = home(), root = skillsRoot();
    String memberName = SkillLifecycle.member(entry.member());
    File member = fs.child(root, memberName);
    var memberNode = fs.stat(member);
    SkillLifecycle.Kind kind;
    if (entry.directory()) {
      if (!memberNode.type.equals("DIRECTORY")) throw new IOException("SKILL_CHANGED");
      requireSameDocument(fs.child(root, entry.relative), entry);
      kind = SkillLifecycle.Kind.DIRECTORY;
    } else {
      requireSameDocument(member, entry);
      kind = SkillLifecycle.Kind.FILE;
    }
    SkillLifecycle.TrashMeta meta =
        SkillLifecycle.TrashMeta.of(memberName, entry.document.name, kind, now);
    File trash = trashRoot(home, true);
    String slotName = UUID.randomUUID().toString();
    File slot = fs.child(trash, slotName);
    fs.directory(slot);
    var slotBorn = fs.stat(slot);
    File metaFile = fs.child(slot, SkillLifecycle.TRASH_META);
    byte[] bytes = meta.encode();
    boolean moved = false;
    try {
      try (OutputStream output = fs.create(metaFile)) {
        output.write(bytes);
      }
      if (!Arrays.equals(bytes, fs.small(metaFile, SkillLifecycle.MAX_META_BYTES)))
        throw new IOException("SKILL_TRASH_META");
      fs.syncDirectory(slot);
      control.check();
      // 改名前最后一次确认：条目仍是同一个对象，之后不再响应取消。
      if (!SkillImports.identity(memberNode, fs.stat(member)))
        throw new IOException("SKILL_CHANGED");
      fs.move(member, fs.child(slot, SkillLifecycle.TRASH_ITEM));
      moved = true;
      fs.syncDirectory(root);
      fs.syncDirectory(slot);
      return slotName;
    } finally {
      // 只有条目确实没移过去时才回收空槽；已移入的结果保留，由列表显示。
      if (!moved
          && fs.stat(fs.child(slot, SkillLifecycle.TRASH_ITEM)).type.equals("MISSING")
          && SkillImports.identity(slotBorn, fs.stat(slot)))
        try {
          fs.removeOwned(trash, slotName);
        } catch (IOException retained) {
          // 残留空槽的记录有效但条目缺失，宽限期后由 purgeExpired 清理。
        }
    }
  }

  /** 恢复到原位置；原名称已被占用时拒绝，不覆盖。 */
  public SkillImports.Entry restore(String slotName, BackupControl control) throws IOException {
    control.check();
    File home = home();
    File trash = trashRoot(home, false);
    File slot = slot(trash, slotName);
    SkillLifecycle.TrashMeta meta = readMeta(slot);
    File item = fs.child(slot, SkillLifecycle.TRASH_ITEM);
    var itemNode = fs.stat(item);
    boolean directory = meta.kind == SkillLifecycle.Kind.DIRECTORY;
    if (!itemNode.type.equals(directory ? "DIRECTORY" : "FILE"))
      throw new IOException("SKILL_TRASH_ITEM");
    File root = fs.child(home, "skills");
    var rootNode = fs.stat(root);
    if (rootNode.type.equals("MISSING")) fs.directory(root);
    else if (!rootNode.type.equals("DIRECTORY")) throw new IOException("SKILL_DIRECTORY");
    File target = fs.child(root, meta.member);
    if (!fs.stat(target).type.equals("MISSING")) throw new IOException("SKILL_RESTORE_EXISTS");
    for (SkillImports.Entry other : new SkillImports(fs, files).list(control).entries)
      if (other.document.name.equals(meta.name)) throw new IOException("SKILL_EXISTS");
    control.check();
    if (!SkillImports.identity(itemNode, fs.stat(item))) throw new IOException("SKILL_TRASH_ITEM");
    fs.move(item, target);
    fs.syncDirectory(root);
    try {
      fs.removeOwned(trash, slotName);
      fs.syncDirectory(trash);
    } catch (IOException leftover) {
      // 条目已回到原位；残留记录宽限期后清理，不把成功恢复报告成失败。
    }
    for (SkillImports.Entry restored : new SkillImports(fs, files).list(control).entries)
      if (restored.member().equals(meta.member)) return restored;
    throw new IOException("SKILL_RESTORED_UNREADABLE");
  }

  /** 彻底删除最近删除中的一项。 */
  public void purge(String slotName, BackupControl control) throws IOException {
    control.check();
    File trash = trashRoot(home(), false);
    slot(trash, slotName);
    fs.removeOwned(trash, slotName);
    fs.syncDirectory(trash);
  }

  /** 先移入最近删除再立即清除；清除中断时条目留在最近删除，可再次彻底删除或等过期。 */
  public void deletePermanently(SkillImports.Entry entry, long now, BackupControl control)
      throws IOException {
    String slot = trash(entry, now, control);
    try {
      purge(slot, new BackupControl(null));
    } catch (IOException partial) {
      throw new IOException("SKILL_PURGE_PARTIAL", partial);
    }
  }

  /** 清除已过期项；返回清除数。记录无法解析的槽位原样保留。 */
  public int purgeExpired(long now, int retentionDays, BackupControl control) throws IOException {
    control.check();
    File trash = trashRoot(home(), false);
    if (trash == null) return 0;
    int purged = 0;
    for (String name : fs.list(trash)) {
      control.check();
      if (!SkillLifecycle.slot(name)) continue;
      File slot = fs.child(trash, name);
      if (!fs.stat(slot).type.equals("DIRECTORY")) continue;
      SkillLifecycle.TrashMeta meta;
      try {
        meta = readMeta(slot);
      } catch (IOException unknown) {
        continue;
      }
      boolean orphan = fs.stat(fs.child(slot, SkillLifecycle.TRASH_ITEM)).type.equals("MISSING");
      boolean due =
          orphan
              ? now >= meta.deletedAt && now - meta.deletedAt >= ORPHAN_GRACE_MILLIS
              : SkillLifecycle.expired(meta.deletedAt, now, retentionDays);
      if (!due) continue;
      fs.removeOwned(trash, name);
      if (!orphan) purged++;
    }
    if (purged > 0) fs.syncDirectory(trash);
    return purged;
  }

  /** 最近删除列表，按删除时间倒序；记录无效或条目缺失的槽位不显示。 */
  public List<Item> list(BackupControl control) throws IOException {
    control.check();
    File trash = trashRoot(home(), false);
    List<Item> items = new ArrayList<>();
    if (trash == null) return items;
    for (String name : fs.list(trash)) {
      control.check();
      if (!SkillLifecycle.slot(name)) continue;
      File slot = fs.child(trash, name);
      if (!fs.stat(slot).type.equals("DIRECTORY")) continue;
      SkillLifecycle.TrashMeta meta;
      try {
        meta = readMeta(slot);
      } catch (IOException unknown) {
        continue;
      }
      File item = fs.child(slot, SkillLifecycle.TRASH_ITEM);
      var node = fs.stat(item);
      boolean directory = meta.kind == SkillLifecycle.Kind.DIRECTORY;
      if (!node.type.equals(directory ? "DIRECTORY" : "FILE")) continue;
      String description = "";
      boolean wasEnabled = directory ? true : !SkillLifecycle.disabledFile(meta.member);
      try {
        File document = item;
        if (directory) {
          document = fs.child(item, SkillLifecycle.DOCUMENT);
          if (fs.stat(document).type.equals("MISSING")) {
            document = fs.child(item, SkillLifecycle.DISABLED_DOCUMENT);
            wasEnabled = false;
          }
        }
        description = SkillDocument.parse(fs.small(document, SkillDocument.MAX_BYTES)).description;
      } catch (java.io.InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException unreadable) {
        // 描述只用于显示。
      }
      items.add(new Item(name, meta, description, wasEnabled));
    }
    items.sort((a, b) -> Long.compare(b.meta.deletedAt, a.meta.deletedAt));
    if (items.size() > SkillLifecycle.MAX_TRASH_ENTRIES)
      return new ArrayList<>(items.subList(0, SkillLifecycle.MAX_TRASH_ENTRIES));
    return items;
  }

  /** 最近删除中仍占用的技能名。 */
  public Set<String> trashNames(BackupControl control) throws IOException {
    Set<String> names = new HashSet<>();
    for (Item item : list(control)) names.add(item.meta.name);
    return names;
  }

  private File trashRoot(File home, boolean create) throws IOException {
    File trash = fs.child(home, SkillLifecycle.TRASH_DIRECTORY);
    var node = fs.stat(trash);
    if (node.type.equals("MISSING")) {
      if (!create) return null;
      fs.directory(trash);
      fs.syncDirectory(home);
      return trash;
    }
    if (!node.type.equals("DIRECTORY")) throw new IOException("SKILL_TRASH_DIRECTORY");
    return trash;
  }

  private File slot(File trash, String slotName) throws IOException {
    if (trash == null || !SkillLifecycle.slot(slotName)) throw new IOException("SKILL_TRASH_ITEM");
    File slot = fs.child(trash, slotName);
    if (!fs.stat(slot).type.equals("DIRECTORY")) throw new IOException("SKILL_TRASH_ITEM");
    return slot;
  }

  private SkillLifecycle.TrashMeta readMeta(File slot) throws IOException {
    File meta = fs.child(slot, SkillLifecycle.TRASH_META);
    if (!fs.stat(meta).type.equals("FILE")) throw new IOException("SKILL_TRASH_META");
    return SkillLifecycle.TrashMeta.decode(fs.small(meta, SkillLifecycle.MAX_META_BYTES));
  }
}
