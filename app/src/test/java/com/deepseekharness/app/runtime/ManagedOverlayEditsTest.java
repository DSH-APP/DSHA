package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ManagedOverlayEditsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String CLIENT = "linux/ubuntu/root/dsha-app-integration/client.js";

  @Test
  public void unchangedClientKeepsIdentityAndChangedClientIsRetainedOnce() throws Exception {
    File root = temporary.newFolder();
    var fs = new JvmBackupFileSystem();
    byte[] hotfix = "现场热修".getBytes(StandardCharsets.UTF_8);
    byte[] signed = "签名内置版本".getBytes(StandardCharsets.UTF_8);
    RuntimeAssetFiles.write(fs, root, CLIENT, hotfix, false);
    File client = new File(root, CLIENT);
    var original = fs.stat(client);
    RuntimeAssetFiles.write(fs, root, CLIENT, hotfix, false);
    assertTrue(original.same(fs.stat(client)));
    assertFalse(new File(root, ManagedOverlayEdits.DIRECTORY).exists());
    RuntimeAssetFiles.write(fs, root, CLIENT, signed, false);
    File directory = new File(root, ManagedOverlayEdits.DIRECTORY);
    assertEquals(1, fs.list(directory).size());
    File retained = new File(directory, fs.list(directory).get(0));
    assertArrayEquals(hotfix, Files.readAllBytes(retained.toPath()));
    assertArrayEquals(signed, Files.readAllBytes(client.toPath()));
    var retainedIdentity = fs.stat(retained);
    // 再次保留相同现场只复用既有完整副本，不重写原件。
    ManagedOverlayEdits.retain(fs, root, retained, retainedIdentity);
    assertEquals(1, fs.list(directory).size());
    assertTrue(retainedIdentity.same(fs.stat(retained)));
  }

  @Test
  public void unsafeRetentionDirectoryLeavesHotfixUntouched() throws Exception {
    File root = temporary.newFolder();
    var fs = new JvmBackupFileSystem();
    RuntimeAssetFiles.write(fs, root, CLIENT, new byte[] {1, 2}, false);
    Files.write(new File(root, ManagedOverlayEdits.DIRECTORY).toPath(), new byte[] {9});
    assertThrows(
        java.io.IOException.class,
        () -> RuntimeAssetFiles.write(fs, root, CLIENT, new byte[] {3}, false));
    assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(new File(root, CLIENT).toPath()));
  }

  @Test
  public void onlyManagedIntegrationClientUsesTheRetentionPolicy() {
    assertTrue(ManagedOverlayEdits.client(CLIENT));
    assertFalse(ManagedOverlayEdits.client("linux/ubuntu/root/user-plugin/client.js"));
    assertFalse(ManagedOverlayEdits.client(CLIENT + ".unrelated"));
  }
}
