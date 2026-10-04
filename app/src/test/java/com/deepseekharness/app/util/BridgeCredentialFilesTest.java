package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class BridgeCredentialFilesTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();

  @Test
  public void actualAtomicRotationRetainsOldSourceAndCreatesMatchingHeader() throws Exception {
    File root = temporary.newFolder(), token = new File(root, CredentialPaths.BRIDGE_TOKEN);
    String before = "A".repeat(32), after = "B".repeat(32);
    BridgeCredentialFiles.publish(fs, token, before, false);
    assertEquals(before, BridgeCredentialFiles.read(fs, token));
    BridgeCredentialFiles.publish(fs, token, after, true);
    assertEquals(after, BridgeCredentialFiles.read(fs, token));
    assertEquals(
        "X-Token: " + after + "\n",
        new String(
            fs.small(new File(root, CredentialPaths.BRIDGE_HEADERS), 256), StandardCharsets.UTF_8));
    int retained = 0;
    for (String name : fs.list(root))
      if (name.startsWith(CredentialPaths.BRIDGE_TOKEN + ".retained-")) {
        retained++;
        assertEquals(before, Files.readString(new File(root, name).toPath()));
        assertTrue(CredentialPaths.machine(name));
      }
    assertEquals(1, retained);
  }

  @Test
  public void nonFileTargetsAndInjectedLineBreaksNeverBecomeCredentials() throws Exception {
    File root = temporary.newFolder(), token = new File(root, CredentialPaths.BRIDGE_TOKEN);
    fs.directory(token);
    assertThrows(
        IOException.class, () -> BridgeCredentialFiles.publish(fs, token, "A".repeat(32), false));
    File clean = new File(root, "clean-token");
    assertThrows(
        IOException.class,
        () -> BridgeCredentialFiles.publish(fs, clean, "A".repeat(32) + "\nInjected: x", false));
    assertFalse(clean.exists());
  }

  @Test
  public void selectedStableDirectoryIsUsedWithoutChangingLegacyBytes() throws Exception {
    File files = temporary.newFolder(),
        legacy = new File(files, UserDataLayout.LEGACY),
        stable = new File(files, UserDataLayout.STABLE);
    Files.createDirectories(legacy.toPath());
    Files.createDirectories(stable.toPath());
    Files.write(
        new File(files, UserDataLayout.RECORD).toPath(),
        UserDataLayout.record(UserDataLayout.Home.STABLE));
    File old = new File(legacy, CredentialPaths.BRIDGE_TOKEN);
    Files.writeString(old.toPath(), "old retained data");
    File current = fs.child(new UserDataLayout(fs, files).current(), CredentialPaths.BRIDGE_TOKEN);
    BridgeCredentialFiles.publish(fs, current, "C".repeat(32), false);
    assertEquals("old retained data", Files.readString(old.toPath()));
    assertEquals(
        "C".repeat(32), Files.readString(new File(stable, CredentialPaths.BRIDGE_TOKEN).toPath()));
  }
}
