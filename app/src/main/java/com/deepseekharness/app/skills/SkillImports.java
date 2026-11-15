package com.deepseekharness.app.skills;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.UserDataLayout;
import com.deepseekharness.app.util.SkillDocument;
import com.deepseekharness.app.util.SkillLifecycle;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 仅导入用户全局技能；宿主独占创建目录和文件，不启动 guest 或安装钩子。 */
public final class SkillImports {
  public static final int MAX_ENTRIES = 512;
  public static final String GUEST_ROOT = "/root/.dsh/skills";
  private final BackupFileSystem fs;
  private final File files;

  public static final class Entry {
    /** 相对技能根目录的实际文档路径：{@code 名/SKILL.md[.disabled]} 或 {@code 名.md[.disabled]}。 */
    public final String relative;

    public final SkillDocument document;

    /** false = 已禁用（文档带 .disabled 后缀，DSH 不发现）。 */
    public final boolean enabled;

    Entry(String relative, SkillDocument document) {
      this(relative, document, true);
    }

    Entry(String relative, SkillDocument document, boolean enabled) {
      this.relative = relative;
      this.document = document;
      this.enabled = enabled;
    }

    public String guestPath() {
      return GUEST_ROOT + "/" + relative;
    }

    /** 技能根目录下的成员名（目录名或单文件名）。 */
    public String member() {
      int slash = relative.indexOf('/');
      return slash < 0 ? relative : relative.substring(0, slash);
    }

    public boolean directory() {
      return relative.indexOf('/') >= 0;
    }
  }

  public static final class Catalogue {
    public final List<Entry> entries;
    public final int unreadable;

    /** 最近删除（按删除时间倒序）；只读目录时为空。 */
    public final List<SkillLibrary.Item> trash;

    Catalogue(List<Entry> entries, int unreadable) {
      this(entries, unreadable, new ArrayList<>());
    }

    Catalogue(List<Entry> entries, int unreadable, List<SkillLibrary.Item> trash) {
      this.entries = Collections.unmodifiableList(entries);
      this.unreadable = unreadable;
      this.trash = Collections.unmodifiableList(trash);
    }

    public Catalogue withTrash(List<SkillLibrary.Item> items) {
      return new Catalogue(new ArrayList<>(entries), unreadable, new ArrayList<>(items));
    }

    public int enabledCount() {
      int count = 0;
      for (Entry entry : entries) if (entry.enabled) count++;
      return count;
    }
  }

  public SkillImports(BackupFileSystem fs, File files) {
    this.fs = fs;
    this.files = files.getAbsoluteFile();
  }

  File home() throws IOException {
    File selected = new UserDataLayout(fs, files).current();
    String relative =
        selected.getPath().substring(files.getPath().length() + 1).replace(File.separatorChar, '/');
    File home = fs.child(files, relative);
    if (!fs.stat(home).type.equals("DIRECTORY")) throw new IOException("SKILL_HOME_UNAVAILABLE");
    return home;
  }

  public Catalogue list(BackupControl control) throws IOException {
    control.check();
    File root = fs.child(home(), "skills");
    var node = fs.stat(root);
    if (node.type.equals("MISSING")) return new Catalogue(new ArrayList<>(), 0);
    if (!node.type.equals("DIRECTORY")) throw new IOException("SKILL_DIRECTORY");
    List<String> names = fs.list(root);
    if (names.size() > MAX_ENTRIES) throw new IOException("SKILL_ENTRY_LIMIT");
    List<Entry> entries = new ArrayList<>();
    int unreadable = 0;
    java.util.Set<String> present = new java.util.HashSet<>(names);
    for (String name : names) {
      control.check();
      if (name.equals(".system")) continue;
      File member = fs.child(root, name);
      var kind = fs.stat(member);
      String relative;
      File document;
      boolean enabled = true;
      if (kind.type.equals("DIRECTORY")) {
        // 两份同时存在时按 DSH 实际行为算启用；切换会因目标已存在而拒绝，不覆盖任何一份。
        relative = name + "/" + SkillLifecycle.DOCUMENT;
        document = fs.child(member, SkillLifecycle.DOCUMENT);
        if (fs.stat(document).type.equals("MISSING")) {
          relative = name + "/" + SkillLifecycle.DISABLED_DOCUMENT;
          document = fs.child(member, SkillLifecycle.DISABLED_DOCUMENT);
          if (fs.stat(document).type.equals("MISSING")) continue;
          enabled = false;
        }
      } else if (SkillLifecycle.disabledFile(name)) {
        // 同名启用文件仍在时以它为准，不列出重复项。
        if (present.contains(
            name.substring(0, name.length() - SkillLifecycle.DISABLED_SUFFIX.length()))) continue;
        relative = name;
        document = member;
        enabled = false;
      } else if (name.endsWith(".md")) {
        relative = name;
        document = member;
      } else continue;
      try {
        entries.add(
            new Entry(
                relative,
                SkillDocument.parse(fs.small(document, SkillDocument.MAX_BYTES)),
                enabled));
      } catch (java.io.InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException invalid) {
        unreadable++;
      }
    }
    entries.sort(java.util.Comparator.comparing(entry -> entry.document.name));
    return new Catalogue(entries, unreadable);
  }

  /** 调用方先读完 SAF 到有界内存；取消/校验失败不会发布技能，同名原件始终保留。 */
  public Entry install(String filename, byte[] bytes, BackupControl control) throws IOException {
    SkillDocument.filename(filename);
    if (bytes != null) bytes = bytes.clone();
    SkillDocument parsed = SkillDocument.parse(bytes);
    control.check();
    File home = home(), root = fs.child(home, "skills");
    rejectName(parsed.name, control);
    File staging = fs.child(home, ".dsha-skill-import-" + UUID.randomUUID());
    BackupFileSystem.Node owned = null,
        stagedDocument = null,
        born = null,
        publishedDocument = null;
    File target = null;
    boolean complete = false, retainStaging = false, writeStarted = false, targetCreated = false;
    try {
      control.check();
      fs.directory(staging);
      owned = fs.stat(staging);
      File document = fs.child(staging, "SKILL.md");
      try (OutputStream output = fs.create(document)) {
        stagedDocument = fs.stat(document);
        output.write(bytes);
      }
      if (!Arrays.equals(bytes, fs.small(document, SkillDocument.MAX_BYTES)))
        throw new IOException("SKILL_COPY_CHANGED");
      fs.syncDirectory(staging);
      control.check();
      rejectName(parsed.name, control);
      var rootNode = fs.stat(root);
      if (rootNode.type.equals("MISSING")) fs.directory(root);
      else if (!rootNode.type.equals("DIRECTORY")) throw new IOException("SKILL_DIRECTORY");
      target = fs.child(root, parsed.name);
      if (!fs.stat(target).type.equals("MISSING")) throw new IOException("SKILL_EXISTS");
      control.check();
      // Web 仍可写用户目录；mkdir 是原子 EXCLUSIVE，不能用 lstat 后 rename 来发布。
      try {
        fs.directory(target);
        targetCreated = true;
      } catch (IOException collision) {
        if (!fs.stat(target).type.equals("MISSING"))
          throw new IOException("SKILL_EXISTS", collision);
        throw collision;
      }
      born = fs.stat(target);
      requireDirectory(target, born);
      File destination = fs.child(target, "SKILL.md");
      control.check();
      // create 在 Android 为 O_EXCL | O_NOFOLLOW；另一发布者抢先写入时保留其文件。
      try (OutputStream output = fs.create(destination)) {
        publishedDocument = fs.stat(destination);
        if (!publishedDocument.type.equals("FILE")) throw new IOException("SKILL_COPY_CHANGED");
        requireDirectory(target, born);
        control.check();
        writeStarted = true;
        output.write(bytes);
      }
      requireDirectory(target, born);
      var written = fs.stat(destination);
      if (!identity(publishedDocument, written)
          || !Arrays.equals(bytes, fs.small(destination, SkillDocument.MAX_BYTES))
          || !written.same(fs.stat(destination))) throw new IOException("SKILL_COPY_CHANGED");
      control.check();
      fs.syncDirectory(target);
      fs.syncDirectory(root);
      requireDirectory(target, born);
      if (!written.same(fs.stat(destination))) throw new IOException("SKILL_COPY_CHANGED");
      complete = true;
      return new Entry(parsed.name + "/SKILL.md", parsed);
    } finally {
      if (!complete && targetCreated) {
        if (born == null) retainStaging = true;
        else
          try {
            retainStaging = !cleanOwned(target, born, publishedDocument, bytes, !writeStarted);
          } catch (IOException unknown) {
            retainStaging = true;
          }
      }
      if (!retainStaging) cleanOwned(staging, owned, stagedDocument, bytes, false);
    }
  }

  static boolean identity(BackupFileSystem.Node expected, BackupFileSystem.Node current) {
    return expected != null
        && expected.type.equals(current.type)
        && expected.key.equals(current.key)
        && expected.device == current.device;
  }

  private void requireDirectory(File directory, BackupFileSystem.Node born) throws IOException {
    if (!born.type.equals("DIRECTORY") || !identity(born, fs.stat(directory)))
      throw new IOException("SKILL_COPY_CHANGED");
  }

  /** 仅删除本次创建且内容仍明确的对象；未知、部分或后来文件保留，完整 staging 也随之保留。 */
  private boolean cleanOwned(
      File directory,
      BackupFileSystem.Node born,
      BackupFileSystem.Node documentBorn,
      byte[] bytes,
      boolean emptyAllowed)
      throws IOException {
    if (born == null) return false;
    var node = fs.stat(directory);
    if (!born.type.equals("DIRECTORY") || !identity(born, node)) return false;
    List<String> names = fs.list(directory);
    if (!names.isEmpty()) {
      if (!names.equals(List.of("SKILL.md"))) return false;
      File document = fs.child(directory, "SKILL.md");
      var file = fs.stat(document);
      if (!file.type.equals("FILE") || !identity(documentBorn, file)) return false;
      byte[] remaining = fs.small(document, SkillDocument.MAX_BYTES);
      if (!Arrays.equals(bytes, remaining)
          && !(emptyAllowed && remaining.length == 0 && file.same(documentBorn))) return false;
      if (!file.same(fs.stat(document))) return false;
      requireDirectory(directory, born);
      fs.delete(document);
    }
    requireDirectory(directory, born);
    if (!fs.list(directory).isEmpty()) return false;
    fs.delete(directory);
    return true;
  }

  /** 已启用、已禁用与最近删除中的同名技能都占用名称；彻底删除后才释放。 */
  private void rejectName(String name, BackupControl control) throws IOException {
    Catalogue current = list(control);
    for (Entry entry : current.entries)
      if (entry.document.name.equals(name)) throw new IOException("SKILL_EXISTS");
    if (new SkillLibrary(fs, files).trashNames(control).contains(name))
      throw new IOException("SKILL_IN_TRASH");
    File root = fs.child(home(), "skills");
    if (fs.stat(root).type.equals("MISSING")) return;
    if (!fs.stat(fs.child(root, name)).type.equals("MISSING")
        || !fs.stat(fs.child(root, name + ".md")).type.equals("MISSING")
        || !fs.stat(fs.child(root, name + ".md" + SkillLifecycle.DISABLED_SUFFIX))
            .type
            .equals("MISSING")) throw new IOException("SKILL_EXISTS");
  }
}
