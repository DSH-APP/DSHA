package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.ExternalBackupScanner;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

public class ExternalBackupDataPresenceTest {
  @Test
  public void rootfsMarkersAreNotUserDataButLegacyAndStableBodiesAre() throws Exception {
    File files = Files.createTempDirectory("user-data-presence").toFile();
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    File root = new File(files, "linux/ubuntu");
    try {
      Files.createDirectories(new File(root, "root/.dsh").toPath());
      Files.writeString(new File(root, "root/.dsha-runtime-version").toPath(), "managed-version");
      assertFalse(ExternalBackupScanner.hasUserData(root));
      File stable = new File(files, "user-data-v5/dsh/settings.yaml");
      Files.createDirectories(stable.toPath().getParent());
      Files.writeString(stable.toPath(), "personal-settings");
      assertTrue(ExternalBackupScanner.hasUserData(root));
      Files.delete(stable.toPath());
      File sessions = new File(root, "root/.dsh/sessions");
      Files.createDirectories(sessions.toPath());
      assertFalse(ExternalBackupScanner.hasUserData(root));
      Files.writeString(new File(sessions, "user-session").toPath(), "personal-session");
      assertTrue(ExternalBackupScanner.hasUserData(root));
    } finally {
      fs.removeOwned(files.getParentFile(), files.getName());
    }
  }
}
