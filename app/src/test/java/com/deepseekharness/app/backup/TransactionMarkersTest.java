package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class TransactionMarkersTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void readsHistoricBytesAndRejectsDirectoryOrForeignOwner() throws Exception {
    BackupFileSystem fs = new JvmBackupFileSystem();
    File folder = temp.newFolder("11111111-1111-4111-8111-111111111111");
    assertFalse(TransactionMarkers.read(fs, folder, "committed", "DOMAIN_MARKER"));
    TransactionMarkers.write(fs, folder, "committed", "DOMAIN_MARKER");
    assertTrue(TransactionMarkers.read(fs, folder, "committed", "DOMAIN_MARKER"));
    assertEquals(
        folder.getName() + "\ncommitted\n",
        Files.readString(new File(folder, "committed").toPath()));
    Files.writeString(new File(folder, "committed").toPath(), "other\ncommitted\n");
    assertEquals(
        "DOMAIN_MARKER",
        assertThrows(
                IOException.class,
                () -> TransactionMarkers.read(fs, folder, "committed", "DOMAIN_MARKER"))
            .getMessage());
    Files.createDirectory(new File(folder, "switching").toPath());
    assertThrows(
        IOException.class, () -> TransactionMarkers.read(fs, folder, "switching", "DOMAIN_MARKER"));
  }

  @Test
  public void historyProofPreservesHistoricalOwnerFieldsAndRejectsWrongType() throws Exception {
    BackupFileSystem fs = new JvmBackupFileSystem();
    File owner = temp.newFolder(), history = new File(owner, "completed");
    Files.createDirectory(history.toPath());
    File proof = new File(owner, "proof.json");
    String ownerKey = fs.stat(owner).key, historyKey = fs.stat(history).key;
    assertFalse(
        CompletedHistory.matches(fs, proof, "homeKey", ownerKey, historyKey, "DOMAIN_HISTORY_"));
    CompletedHistory.write(fs, proof, "homeKey", ownerKey, historyKey);
    assertTrue(
        CompletedHistory.matches(fs, proof, "homeKey", ownerKey, historyKey, "DOMAIN_HISTORY_"));
    assertFalse(
        CompletedHistory.matches(fs, proof, "homeKey", "changed", historyKey, "DOMAIN_HISTORY_"));
    Files.delete(proof.toPath());
    Files.createDirectory(proof.toPath());
    assertEquals(
        "DOMAIN_HISTORY_PROOF_TYPE",
        assertThrows(
                IOException.class,
                () ->
                    CompletedHistory.matches(
                        fs, proof, "homeKey", ownerKey, historyKey, "DOMAIN_HISTORY_"))
            .getMessage());
  }
}
