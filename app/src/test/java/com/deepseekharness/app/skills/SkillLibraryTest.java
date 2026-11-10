package com.deepseekharness.app.skills;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.util.SkillLifecycle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class SkillLibraryTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static final long NOW = 1_800_000_000_000L;
  private static final long DAY = SkillLifecycle.DAY_MILLIS;

  private static byte[] skill(String name) {
    return ("---\nname: " + name + "\ndescription: Skill " + name + "\n---\n# Body\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private Path files, skills, trash;
  private SkillImports imports;
  private SkillLibrary library;

  private void setUp() throws IOException {
    files = temporary.newFolder().toPath();
    skills = files.resolve("user-data-v5/dsh/skills");
    trash = files.resolve("user-data-v5/dsh/" + SkillLifecycle.TRASH_DIRECTORY);
    Files.createDirectories(files.resolve("user-data-v5/dsh"));
    imports = new SkillImports(new JvmBackupFileSystem(), files.toFile());
    library = new SkillLibrary(new JvmBackupFileSystem(), files.toFile());
  }

  private static BackupControl control() {
    return new BackupControl(null);
  }

  private SkillImports.Entry only(String name) throws IOException {
    for (SkillImports.Entry entry : imports.list(control()).entries)
      if (entry.document.name.equals(name)) return entry;
    throw new AssertionError("missing " + name);
  }

  @Test
  public void disableRenamesOnlyTheDocumentAndKeepsResources() throws Exception {
    setUp();
    imports.install("SKILL.md", skill("web-scraper"), control());
    Path folder = skills.resolve("web-scraper");
    Files.createDirectories(folder.resolve("scripts"));
    Files.write(folder.resolve("scripts/fetch.py"), new byte[] {1, 2, 3});

    var disabled = library.setEnabled(only("web-scraper"), false, control());
    assertFalse(disabled.enabled);
    assertEquals("web-scraper/SKILL.md.disabled", disabled.relative);
    assertFalse(Files.exists(folder.resolve("SKILL.md")));
    assertArrayEquals(
        skill("web-scraper"), Files.readAllBytes(folder.resolve("SKILL.md.disabled")));
    assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(folder.resolve("scripts/fetch.py")));
    var listed = imports.list(control());
    assertEquals(1, listed.entries.size());
    assertEquals(0, listed.enabledCount());

    var enabled = library.setEnabled(only("web-scraper"), true, control());
    assertTrue(enabled.enabled);
    assertTrue(Files.exists(folder.resolve("SKILL.md")));
    assertFalse(Files.exists(folder.resolve("SKILL.md.disabled")));
  }

  @Test
  public void singleFileSkillsToggleWithTheirOwnSuffix() throws Exception {
    setUp();
    Files.createDirectories(skills);
    Files.write(skills.resolve("notes.md"), skill("notes"));
    var disabled = library.setEnabled(only("notes"), false, control());
    assertEquals("notes.md.disabled", disabled.relative);
    assertFalse(Files.exists(skills.resolve("notes.md")));
    assertFalse(only("notes").enabled);
    assertTrue(library.setEnabled(only("notes"), true, control()).enabled);
    assertTrue(Files.exists(skills.resolve("notes.md")));
  }

  @Test
  public void toggleRefusesChangedDocumentsAndExistingTargets() throws Exception {
    setUp();
    imports.install("SKILL.md", skill("web-scraper"), control());
    Path folder = skills.resolve("web-scraper");
    var seen = only("web-scraper");
    Files.write(folder.resolve("SKILL.md"), skill("web-scraper-v2"));
    try {
      library.setEnabled(seen, false, control());
      fail();
    } catch (IOException expected) {
      assertEquals("SKILL_CHANGED", expected.getMessage());
    }
    Files.write(folder.resolve("SKILL.md"), skill("web-scraper"));
    Files.write(folder.resolve("SKILL.md.disabled"), skill("older"));
    try {
      library.setEnabled(only("web-scraper"), false, control());
      fail();
    } catch (IOException expected) {
      assertEquals("SKILL_STATE_CONFLICT", expected.getMessage());
    }
    assertArrayEquals(skill("older"), Files.readAllBytes(folder.resolve("SKILL.md.disabled")));
    assertArrayEquals(skill("web-scraper"), Files.readAllBytes(folder.resolve("SKILL.md")));
  }

  @Test
  public void trashMovesTheWholeBundleAndRestoreReturnsItInTheSameState() throws Exception {
    setUp();
    imports.install("SKILL.md", skill("web-scraper"), control());
    Files.write(skills.resolve("web-scraper/extra.txt"), new byte[] {9});
    library.setEnabled(only("web-scraper"), false, control());

    var preview = library.preview(only("web-scraper"), control());
    assertEquals(List.of("SKILL.md.disabled", "extra.txt"), preview.paths);
    assertFalse(preview.truncated);

    String slot = library.trash(only("web-scraper"), NOW, control());
    assertFalse(Files.exists(skills.resolve("web-scraper")));
    assertEquals(0, imports.list(control()).entries.size());
    List<SkillLibrary.Item> items = library.list(control());
    assertEquals(1, items.size());
    assertEquals(slot, items.get(0).slot);
    assertEquals("web-scraper", items.get(0).meta.name);
    assertEquals("Skill web-scraper", items.get(0).description);
    assertFalse(items.get(0).wasEnabled);

    // 名称仍被最近删除占用。
    try {
      imports.install("SKILL.md", skill("web-scraper"), control());
      fail();
    } catch (IOException expected) {
      assertEquals("SKILL_IN_TRASH", expected.getMessage());
    }

    var restored = library.restore(slot, control());
    assertFalse(restored.enabled);
    assertArrayEquals(new byte[] {9}, Files.readAllBytes(skills.resolve("web-scraper/extra.txt")));
    assertEquals(0, library.list(control()).size());
    assertEquals(List.of(), new JvmBackupFileSystem().list(trash.toFile()));
  }

  @Test
  public void restoreNeverOverwritesAReplacementWithTheSameName() throws Exception {
    setUp();
    imports.install("SKILL.md", skill("web-scraper"), control());
    String slot = library.trash(only("web-scraper"), NOW, control());
    Files.createDirectories(skills.resolve("web-scraper"));
    Files.write(skills.resolve("web-scraper/SKILL.md"), skill("replacement"));
    try {
      library.restore(slot, control());
      fail();
    } catch (IOException expected) {
      assertEquals("SKILL_RESTORE_EXISTS", expected.getMessage());
    }
    assertArrayEquals(
        skill("replacement"), Files.readAllBytes(skills.resolve("web-scraper/SKILL.md")));
    assertEquals(1, library.list(control()).size());
  }

  @Test
  public void permanentDeleteRemovesFilesAndReleasesTheName() throws Exception {
    setUp();
    imports.install("SKILL.md", skill("web-scraper"), control());
    Path outside = temporary.newFolder().toPath();
    Files.write(outside.resolve("keep.txt"), new byte[] {7});
    try {
      Files.createSymbolicLink(skills.resolve("web-scraper/linked"), outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
      // 不支持链接的文件系统只验证普通删除。
    }
    library.deletePermanently(only("web-scraper"), NOW, control());
    assertFalse(Files.exists(skills.resolve("web-scraper")));
    assertEquals(0, library.list(control()).size());
    // 链接按链接删除，不跟随到外部目录。
    assertArrayEquals(new byte[] {7}, Files.readAllBytes(outside.resolve("keep.txt")));
    imports.install("SKILL.md", skill("web-scraper"), control());
    assertTrue(only("web-scraper").enabled);
  }

  @Test
  public void purgeExpiredHonoursRetentionAndKeepsUnknownSlots() throws Exception {
    setUp();
    imports.install("SKILL.md", skill("old-notes"), control());
    imports.install("SKILL.md", skill("fresh-notes"), control());
    library.trash(only("old-notes"), NOW - 31 * DAY, control());
    String fresh = library.trash(only("fresh-notes"), NOW - 2 * DAY, control());
    Path unknown = trash.resolve("00000000-0000-0000-0000-000000000000");
    Files.createDirectories(unknown);
    Files.write(unknown.resolve("meta"), "garbage".getBytes(StandardCharsets.UTF_8));

    assertEquals(1, library.purgeExpired(NOW, 30, control()));
    List<SkillLibrary.Item> left = library.list(control());
    assertEquals(1, left.size());
    assertEquals(fresh, left.get(0).slot);
    assertTrue(Files.exists(unknown.resolve("meta")));

    // 缩短保留期后，下一次清理按新天数生效。
    assertEquals(1, library.purgeExpired(NOW, 1, control()));
    assertEquals(0, library.list(control()).size());
    assertTrue(Files.exists(unknown.resolve("meta")));
  }

  @Test
  public void trashRefusesAPlainFileInPlaceOfTheTrashDirectory() throws Exception {
    setUp();
    imports.install("SKILL.md", skill("web-scraper"), control());
    Files.write(trash, new byte[] {1});
    try {
      library.trash(only("web-scraper"), NOW, control());
      fail();
    } catch (IOException expected) {
      assertEquals("SKILL_TRASH_DIRECTORY", expected.getMessage());
    }
    assertTrue(only("web-scraper").enabled);
  }
}
