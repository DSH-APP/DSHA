package com.deepseekharness.app.backup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class RestoreStagingTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  private File fixture() throws Exception {
    File root = temp.newFolder(), operation = new File(root, UUID.randomUUID().toString());
    Files.createDirectory(operation.toPath());
    Files.createDirectories(new File(operation, "payload").toPath());
    Files.writeString(new File(operation, "payload/0").toPath(), "decrypted record");
    Files.writeString(new File(operation, "restore.dshdata").toPath(), "decrypted archive");
    Files.writeString(new File(operation, "snapshot.dshdata").toPath(), "private snapshot");
    Files.writeString(new File(operation, "input.archive").toPath(), "keep selected input");
    return operation;
  }

  private void marker(File operation, String name) throws Exception {
    Files.writeString(new File(operation, name).toPath(), operation.getName() + "\n" + name + "\n");
  }

  @Test
  public void successAndCancellationClearDerivedPlaintextButRetainInputs() throws Exception {
    for (boolean cancelled : new boolean[] {false, true}) {
      File operation = fixture();
      if (!cancelled) {
        marker(operation, "switching");
        marker(operation, "finalized");
      }
      RestoreStaging.clearSensitivePlaintext(fs, operation);
      for (String name : List.of("payload", "restore.dshdata", "snapshot.dshdata"))
        assertEquals(name, "MISSING", fs.stat(new File(operation, name)).type);
      assertEquals(
          "keep selected input", Files.readString(new File(operation, "input.archive").toPath()));
    }
  }

  @Test
  public void unresolvedInterruptedCommitKeepsPlaintextUntilRollbackIsProven() throws Exception {
    File operation = fixture();
    marker(operation, "switching");
    try {
      RestoreStaging.clearSensitivePlaintext(fs, operation);
      fail();
    } catch (IOException expected) {
      assertEquals("RESTORE_TRANSACTION_UNRESOLVED", expected.getMessage());
    }
    assertTrue(new File(operation, "restore.dshdata").isFile());
    assertTrue(new File(operation, "payload/0").isFile());
    marker(operation, "rolled-back");
    RestoreStaging.clearSensitivePlaintext(fs, operation);
    assertFalse(new File(operation, "restore.dshdata").exists());
    assertFalse(new File(operation, "payload").exists());
    assertTrue(new File(operation, "input.archive").isFile());
  }

  @Test
  public void malformedMarkerOrUnexpectedPayloadTypeFailsClosed() throws Exception {
    File operation = fixture();
    Files.writeString(new File(operation, "switching").toPath(), "different id\n");
    try {
      RestoreStaging.clearSensitivePlaintext(fs, operation);
      fail();
    } catch (IOException expected) {
      assertEquals("RESTORE_TRANSACTION_MARKER", expected.getMessage());
    }
    assertTrue(new File(operation, "restore.dshdata").isFile());
  }
}
