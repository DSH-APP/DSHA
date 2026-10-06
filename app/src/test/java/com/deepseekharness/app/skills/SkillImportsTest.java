package com.deepseekharness.app.skills;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class SkillImportsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static final byte[] SOURCE =
      ("---\nname: hello-world\ndescription: |\n  A real imported skill\n  用于验证实际发现\n---\n# Body\nDo not run: touch should-never-exist\n")
          .getBytes(StandardCharsets.UTF_8);

  private SkillImports repository(Path files) throws IOException {
    Files.createDirectories(files.resolve("user-data-v5/dsh"));
    return new SkillImports(new JvmBackupFileSystem(), files.toFile());
  }

  @Test
  public void publishesExactBytesAndRefusesASecondImport() throws Exception {
    Path files = temporary.newFolder().toPath();
    SkillImports imports = repository(files);
    var entry = imports.install("SKILL.md", SOURCE, new BackupControl(null));
    assertEquals("/root/.dsh/skills/hello-world/SKILL.md", entry.guestPath());
    Path published = files.resolve("user-data-v5/dsh/skills/hello-world/SKILL.md");
    assertArrayEquals(SOURCE, Files.readAllBytes(published));
    assertEquals(1, imports.list(new BackupControl(null)).entries.size());
    try {
      imports.install("SKILL.md", SOURCE, new BackupControl(null));
      fail();
    } catch (IOException error) {
      assertEquals("SKILL_EXISTS", error.getMessage());
    }
    assertArrayEquals(SOURCE, Files.readAllBytes(published));
    assertFalse(Files.exists(files.resolve("should-never-exist")));
    assertEquals(
        java.util.List.of("skills"),
        new JvmBackupFileSystem().list(files.resolve("user-data-v5/dsh").toFile()));
  }

  @Test
  public void cancelledImportAndBadFilenameDoNotCreateDirectories() throws Exception {
    Path files = temporary.newFolder().toPath();
    SkillImports imports = repository(files);
    BackupControl control = new BackupControl(null);
    control.cancel();
    for (String filename : new String[] {"SKILL.md", "SKILL.md.zip"}) {
      try {
        imports.install(filename, SOURCE, control);
        fail();
      } catch (IOException expected) {
        assertFalse(Files.exists(files.resolve("user-data-v5/dsh/skills")));
        assertEquals(
            0, new JvmBackupFileSystem().list(files.resolve("user-data-v5/dsh").toFile()).size());
      }
    }
  }

  @Test
  public void refusesAnExistingDifferentBundleWithTheSameMetadataName() throws Exception {
    Path files = temporary.newFolder().toPath();
    SkillImports imports = repository(files);
    Path original = files.resolve("user-data-v5/dsh/skills/another-folder/SKILL.md");
    Files.createDirectories(original.getParent());
    Files.write(original, SOURCE);
    try {
      imports.install("SKILL.md", SOURCE, new BackupControl(null));
      fail();
    } catch (IOException expected) {
      assertEquals("SKILL_EXISTS", expected.getMessage());
    }
    assertArrayEquals(SOURCE, Files.readAllBytes(original));
    assertFalse(Files.exists(files.resolve("user-data-v5/dsh/skills/hello-world")));
  }

  @Test
  public void legacyHomeAndFlatMarkdownRemainReadable() throws Exception {
    Path files = temporary.newFolder().toPath();
    Path legacy = files.resolve("linux/ubuntu/root/.dsh/skills");
    Files.createDirectories(legacy);
    Files.write(legacy.resolve("existing.md"), SOURCE);
    SkillImports imports = new SkillImports(new JvmBackupFileSystem(), files.toFile());
    var result = imports.list(new BackupControl(null));
    assertEquals(1, result.entries.size());
    assertEquals("existing.md", result.entries.get(0).relative);
    assertFalse(Files.exists(files.resolve("user-data-v5")));
  }

  @Test
  public void rejectsHomeLinksAndSkillsLinksWithoutFollowingThem() throws Exception {
    Path files = temporary.newFolder().toPath(), outside = temporary.newFolder().toPath();
    SkillImports imports = repository(files);
    try {
      Files.createSymbolicLink(files.resolve("user-data-v5/dsh/skills"), outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
      org.junit.Assume.assumeNoException(unavailable);
    }
    try {
      imports.install("SKILL.md", SOURCE, new BackupControl(null));
      fail();
    } catch (IOException expected) {
      assertEquals("SKILL_DIRECTORY", expected.getMessage());
    }
    assertEquals(0, new JvmBackupFileSystem().list(outside.toFile()).size());
  }

  @Test
  public void concurrentPublisherKeepsItsDirectoryOrFileAndUnknownStagingIsRetained()
      throws Exception {
    for (boolean raceDirectory : new boolean[] {true, false}) {
      Path files = temporary.newFolder().toPath();
      Files.createDirectories(files.resolve("user-data-v5/dsh"));
      File target = files.resolve("user-data-v5/dsh/skills/hello-world").toFile();
      byte[] theirs = "Another publisher's original".getBytes(StandardCharsets.UTF_8);
      class RacingFileSystem extends JvmBackupFileSystem {
        Node theirsBorn;

        @Override
        public void directory(File directory) throws IOException {
          if (raceDirectory && directory.equals(target)) {
            super.directory(directory);
            theirsBorn = stat(directory);
          }
          super.directory(directory);
        }

        @Override
        public java.io.OutputStream create(File file) throws IOException {
          if (!raceDirectory && file.getParentFile().equals(target)) {
            Files.write(file.toPath(), theirs, java.nio.file.StandardOpenOption.CREATE_NEW);
            theirsBorn = stat(file);
          }
          return super.create(file);
        }

        @Override
        public void move(File source, File destination) {
          fail("skill publication must never rename onto a concurrent target");
        }
      }
      RacingFileSystem fs = new RacingFileSystem();
      SkillImports imports = new SkillImports(fs, files.toFile());
      try {
        imports.install("SKILL.md", SOURCE, new BackupControl(null));
        fail("concurrent publisher must win its exclusive create");
      } catch (IOException expected) {
        assertNotNull(fs.theirsBorn);
      }
      File document = new File(target, "SKILL.md");
      assertEquals(fs.theirsBorn.key, fs.stat(raceDirectory ? target : document).key);
      if (raceDirectory) {
        assertEquals(java.util.List.of(), fs.list(target));
        assertEquals(java.util.List.of("skills"), fs.list(target.getParentFile().getParentFile()));
      } else {
        assertArrayEquals(theirs, Files.readAllBytes(document.toPath()));
        java.util.List<String> members = fs.list(target.getParentFile().getParentFile());
        assertEquals(2, members.size());
        String staging =
            members.stream()
                .filter(name -> name.startsWith(".dsha-skill-import-"))
                .findFirst()
                .orElseThrow();
        assertArrayEquals(
            SOURCE,
            Files.readAllBytes(
                target.getParentFile().getParentFile().toPath().resolve(staging + "/SKILL.md")));
      }
    }
  }

  @Test
  public void producesFixtureUsingTheActualImporterForRc2Discovery() throws Exception {
    String fixture = System.getProperty("dsha.skills.fixture");
    Path files = fixture == null ? temporary.newFolder().toPath() : Path.of(fixture);
    SkillImports imports = repository(files);
    imports.install("SKILL.md", SOURCE, new BackupControl(null));
    byte[] policy =
        ("---\nname: user-only\ndescription: Explicit invocation only\ndisable-model-invocation: true\nuser-invocable: 1\n---\nUser body\n")
            .getBytes(StandardCharsets.UTF_8);
    imports.install("SKILL.md", policy, new BackupControl(null));
    assertEquals(2, imports.list(new BackupControl(null)).entries.size());
  }
}
