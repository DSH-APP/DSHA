package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class UserDataBindingsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void unexpectedRuntimeRootCannotCreateOrBindAMigrationDomain() throws Exception {
    File files = temporary.newFolder(), other = temporary.newFolder();
    var argv = new ArrayList<String>();
    assertThrows(
        IllegalStateException.class,
        () -> UserDataBindings.append(argv, other, files, new JvmBackupFileSystem()));
    assertTrue(argv.isEmpty());
    assertFalse(new File(files, "rc1-migration-state").exists());
  }

  @Test
  public void explicitNormalDomainRetainsExistingMigrationBind() throws Exception {
    File files = temporary.newFolder(), root = new File(files, "linux/ubuntu");
    Files.createDirectories(root.toPath());
    var argv = new ArrayList<String>();
    UserDataBindings.append(argv, root, files, new JvmBackupFileSystem());
    assertEquals(
        java.util.List.of(
            "-b",
            new File(files, "rc1-migration-state").getAbsolutePath() + ":/run/dsha-rc1-state"),
        argv);
  }
}
