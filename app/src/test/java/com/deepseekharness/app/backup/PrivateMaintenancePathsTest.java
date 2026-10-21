package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PrivateMaintenancePathsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void externalRootsAndPrivateRootAreRejectedWithoutChangingPersonalBytes()
      throws Exception {
    File files = temporary.newFolder("files"), external = temporary.newFolder("external");
    File backup = new File(external, "manual-backup.tar.gz");
    Files.writeString(backup.toPath(), "personal bytes");
    var fs = new JvmBackupFileSystem();
    for (File target : new File[] {files, external, backup})
      assertThrows(IOException.class, () -> PrivateMaintenancePaths.target(fs, files, target));
    assertEquals("personal bytes", Files.readString(backup.toPath()));
    assertEquals(1, external.list().length);
  }

  @Test
  public void missingPrivateSuffixAndLeafReferenceRemainValid() throws Exception {
    File files = temporary.newFolder("private");
    var fs = new JvmBackupFileSystem();
    File target = new File(files, "user-data-v5/restored-projects/new/file");
    assertEquals(target, PrivateMaintenancePaths.target(fs, files, target));
    assertFalse(new File(files, "user-data-v5").exists());
  }

  @Test
  public void aParentReclassifiedAsLinkRejectsTheWholeHistoricalTarget() throws Exception {
    File files = temporary.newFolder("files-parent"), parent = new File(files, "home");
    assertTrue(parent.mkdir());
    File personal = new File(parent, "personal.txt");
    Files.writeString(personal.toPath(), "original");
    var fs =
        new JvmBackupFileSystem() {
          public Node stat(File file) throws IOException {
            Node node = super.stat(file);
            return file.equals(parent)
                ? new Node("LINK", node.key, node.size, node.modified, node.device, node.mode)
                : node;
          }
        };
    assertEquals(
        "MAINTENANCE_PARENT_LINK_OR_SPECIAL",
        assertThrows(IOException.class, () -> PrivateMaintenancePaths.target(fs, files, personal))
            .getMessage());
    assertEquals("original", Files.readString(personal.toPath()));
    // Detaching the private reference itself remains possible without walking its target.
    assertEquals(parent, PrivateMaintenancePaths.target(fs, files, parent));
  }

  @Test
  public void aDifferentFilesystemCannotBecomeAnOldJournalWriteTarget() throws Exception {
    File files = temporary.newFolder("files-device"), mount = new File(files, "mount");
    assertTrue(mount.mkdir());
    var fs =
        new JvmBackupFileSystem() {
          public Node stat(File file) throws IOException {
            Node node = super.stat(file);
            return file.equals(mount)
                ? new Node(
                    node.type, node.key, node.size, node.modified, node.device + 1, node.mode)
                : node;
          }
        };
    assertEquals(
        "MAINTENANCE_FILESYSTEM_BOUNDARY",
        assertThrows(IOException.class, () -> PrivateMaintenancePaths.target(fs, files, mount))
            .getMessage());
    assertTrue(mount.isDirectory());
  }
}
