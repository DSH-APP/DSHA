package com.deepseekharness.app.backup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class AutomaticBackupPrunerTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  private File copy(File parent, boolean automatic, String quality, long created, boolean extra)
      throws Exception {
    String id = UUID.randomUUID().toString();
    File directory = new File(parent, id);
    fs.directory(directory);
    File artifact = new File(directory, "portable.dshbak");
    try (OutputStream out = fs.create(artifact)) {
      PortableBackupCrypto.encrypt(
          new ByteArrayInputStream((id + " contents").getBytes()),
          out,
          "synthetic-password".toCharArray(),
          new BackupControl(null));
    }
    String sha;
    try (InputStream in = fs.read(artifact, fs.stat(artifact))) {
      sha = BackupArchive.digest(in, new BackupControl(null));
    }
    fs.atomic(
        directory,
        "verified.json",
        BackupJson.write(
            Map.of(
                "encryptedSha256",
                sha,
                "encryptedBytes",
                artifact.length(),
                "entries",
                1L,
                "createdAt",
                created,
                "integrity",
                quality,
                "requestedScope",
                "application",
                "automatic",
                automatic),
            BackupLimits.MANIFEST));
    fs.atomic(
        directory,
        "operation.json",
        BackupJson.write(
            Map.of(
                "version",
                1L,
                "id",
                id,
                "stage",
                "FINISHED",
                "busy",
                false,
                "updatedAt",
                created,
                "result",
                "COMPLETE",
                "error",
                "",
                "artifact",
                "portable.dshbak"),
            16384));
    if (extra) fs.atomic(directory, "manual-note", "retain me".getBytes());
    return directory;
  }

  @Test
  public void onlyOlderCompleteAutomaticCopiesAreRotated() throws Exception {
    File files = temp.newFolder(), operations = HostOperationArchive.root(files);
    fs.directory(operations);
    File history = new File(operations, HostOperationArchive.COMPLETED);
    fs.directory(history);
    List<VerifiedBackupCopy> copies = new ArrayList<>();
    File[] automatic = new File[5];
    for (int i = 0; i < 5; i++) {
      automatic[i] = copy(history, true, "QUIESCENT", i + 1, i == 0);
      copies.add(VerifiedBackupCopy.inspect(fs, operations, automatic[i].getName()));
    }
    File manual = copy(history, false, "QUIESCENT", 0, false),
        partial = copy(history, true, "PARTIAL", -1, false);
    copies.add(VerifiedBackupCopy.inspect(fs, operations, manual.getName()));
    copies.add(VerifiedBackupCopy.inspect(fs, operations, partial.getName()));
    AutomaticBackupPruner.prune(fs, copies, new BackupControl(null));
    assertTrue(
        "unrecognized extra payload remains retained",
        fs.stat(automatic[0]).type.equals("DIRECTORY"));
    assertEquals("older ordinary automatic copy rotates", "MISSING", fs.stat(automatic[1]).type);
    for (int i = 2; i < 5; i++)
      assertEquals("keep newest three", "DIRECTORY", fs.stat(automatic[i]).type);
    assertEquals("manual copy remains", "DIRECTORY", fs.stat(manual).type);
    assertEquals("partial automatic copy remains", "DIRECTORY", fs.stat(partial).type);
  }
}
