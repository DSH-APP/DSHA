package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.util.Constants;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class Rc1MigrationCacheTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void onlyFreshUnchangedSmallInputsAndPrivateCommittedRecordsAreReused() throws Exception {
    File files = temporary.newFolder(), home = new File(files, "data");
    JvmBackupFileSystem fs =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws java.io.IOException {
            Node value = super.stat(file);
            return new Node(
                value.type,
                file.equals(home) ? "100:123" : value.key,
                value.size,
                value.modified,
                100,
                value.mode);
          }
        };
    fs.directory(home);
    fs.atomic(home, "settings.yaml", "saved setting".getBytes(StandardCharsets.UTF_8));
    String generation = UUID.randomUUID().toString();
    var identity = Map.of("path", "/root/.dsh", "device", "100", "inode", "123");
    String hash = sha("saved setting".getBytes(StandardCharsets.UTF_8));
    var inputs = Map.of("settings.yaml", hash);
    var current =
        Map.of(
            "version",
            2L,
            "generation",
            generation,
            "dshHome",
            "/root/.dsh",
            "dataRoot",
            identity,
            "inputs",
            inputs);
    var prepared =
        Map.of(
            "version",
            2L,
            "generation",
            generation,
            "dshHome",
            "/root/.dsh",
            "dataRoot",
            identity,
            "inputs",
            inputs,
            "dshVersion",
            Constants.DSH_VERSION,
            "status",
            "prepared",
            "protectionComplete",
            true);
    var receipt =
        Map.of(
            "version",
            2L,
            "generation",
            generation,
            "dshVersion",
            Constants.DSH_VERSION,
            "status",
            "committed",
            "protectionComplete",
            true,
            "sourcePreserved",
            true,
            "settingsImported",
            true);
    String folder = "rc1-migration-state/generations/" + generation;
    fs.parents(files, folder + "/reuse.json");
    File state = fs.child(files, "rc1-migration-state"), recorded = fs.child(files, folder);
    fs.atomic(state, "current.json", BackupJson.write(current, 65536));
    fs.atomic(recorded, "reuse.json", BackupJson.write(prepared, 65536));
    fs.atomic(recorded, "receipt.json", BackupJson.write(receipt, 65536));
    assertEquals(generation, Rc1MigrationCache.generation(fs, files, home));
    fs.atomic(home, "settings.yaml", "edited setting".getBytes(StandardCharsets.UTF_8));
    assertNull(Rc1MigrationCache.generation(fs, files, home));
    fs.atomic(home, "settings.yaml", "saved setting".getBytes(StandardCharsets.UTF_8));
    fs.atomic(
        home, "settings.yaml.imported", "unexpected second input".getBytes(StandardCharsets.UTF_8));
    assertNull(Rc1MigrationCache.generation(fs, files, home));
  }

  private static String sha(byte[] data) throws Exception {
    var result = new StringBuilder();
    for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(data))
      result.append(String.format("%02x", b & 255));
    return result.toString();
  }
}
