package com.deepseekharness.app.recovery;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class RecoveryPublicationTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final String runtime = "1".repeat(64), id = "a".repeat(32);

  private static final class RecordingFs extends JvmBackupFileSystem {
    final List<String> boundaries = new ArrayList<>();
    File capsules, failSource;
    boolean interruptProofRead;

    @Override
    public void move(File source, File target) throws IOException {
      if (source.equals(failSource)) throw new IOException("SIMULATED_PUBLICATION_FAILURE");
      super.move(source, target);
      if (source.getParentFile().equals(capsules))
        boundaries.add("move:" + source.getName() + ":" + target.getName());
    }

    @Override
    public void syncDirectory(File directory) throws IOException {
      super.syncDirectory(directory);
      if (directory.equals(capsules)) boundaries.add("sync");
    }

    @Override
    public InputStream read(File file, Node node) throws IOException {
      if (interruptProofRead && file.getName().equals("prepare-proof.json"))
        throw new InterruptedIOException("CANCELLED");
      return super.read(file, node);
    }
  }

  private File staging(File capsules) throws Exception {
    File staging = new File(capsules, runtime + ".pending-" + id);
    Files.createDirectories(new File(staging, "linux/ubuntu").toPath());
    Files.writeString(new File(staging, "linux/ubuntu/signed-data").toPath(), "new signed bytes");
    return staging;
  }

  @Test
  public void publicationDurablyRetainsOriginalAndPublishesCompleteCandidate() throws Exception {
    RecordingFs fs = new RecordingFs();
    File capsules = fs.capsules = temporary.newFolder();
    File staging = staging(capsules), current = new File(capsules, runtime);
    Files.createDirectories(current.toPath());
    Files.writeString(new File(current, "original").toPath(), "old original");
    RecoveryStoragePlan.publish(fs, capsules, staging, runtime, id);
    assertEquals(
        List.of(
            "move:" + runtime + ":" + runtime + ".retained-" + id,
            "sync",
            "move:" + staging.getName() + ":" + runtime,
            "sync"),
        fs.boundaries);
    assertEquals(
        "old original",
        Files.readString(new File(capsules, runtime + ".retained-" + id + "/original").toPath()));
    assertEquals(
        "new signed bytes",
        Files.readString(new File(current, "linux/ubuntu/signed-data").toPath()));
    assertEquals(
        runtime,
        BackupJson.read(Files.readAllBytes(new File(current, "verified.json").toPath()), 8192)
            .get("runtimeId"));
    assertFalse(staging.exists());
  }

  @Test
  public void failedPublicationKeepsBothOriginalAndCompleteCandidate() throws Exception {
    RecordingFs fs = new RecordingFs();
    File capsules = fs.capsules = temporary.newFolder();
    File staging = staging(capsules), current = new File(capsules, runtime);
    Files.createDirectories(current.toPath());
    Files.writeString(new File(current, "original").toPath(), "preserve me");
    fs.failSource = staging;
    try {
      RecoveryStoragePlan.publish(fs, capsules, staging, runtime, id);
      fail("publication must fail");
    } catch (IOException expected) {
      assertEquals("SIMULATED_PUBLICATION_FAILURE", expected.getMessage());
    }
    assertEquals(
        "preserve me",
        Files.readString(new File(capsules, runtime + ".retained-" + id + "/original").toPath()));
    assertTrue(new File(staging, "verified.json").isFile());
    assertEquals(
        "new signed bytes",
        Files.readString(new File(staging, "linux/ubuntu/signed-data").toPath()));
  }

  @Test
  public void occupiedRetentionSlotCannotOverwriteOrMoveEitherOriginal() throws Exception {
    RecordingFs fs = new RecordingFs();
    File capsules = fs.capsules = temporary.newFolder();
    File staging = staging(capsules), current = new File(capsules, runtime);
    File retained = new File(capsules, runtime + ".retained-" + id);
    Files.createDirectories(current.toPath());
    Files.writeString(retained.toPath(), "unknown historical record");
    try {
      RecoveryStoragePlan.publish(fs, capsules, staging, runtime, id);
      fail("retained originals cannot be replaced");
    } catch (IOException expected) {
      assertEquals("RECOVERY_RETAIN_FAILED", expected.getMessage());
    }
    assertTrue(current.isDirectory());
    assertTrue(staging.isDirectory());
    assertEquals("unknown historical record", Files.readString(retained.toPath()));
    assertTrue(fs.boundaries.isEmpty());
  }

  @Test
  public void stagingOutsideCapsuleAuthorityIsRejectedBeforePublication() throws Exception {
    RecordingFs fs = new RecordingFs();
    File capsules = fs.capsules = temporary.newFolder();
    File external = staging(temporary.newFolder());
    try {
      RecoveryStoragePlan.publish(fs, capsules, external, runtime, id);
      fail("external staging must not publish");
    } catch (IOException expected) {
      assertEquals("RECOVERY_PUBLISH_IDENTITY", expected.getMessage());
    }
    assertTrue(external.isDirectory());
    assertTrue(fs.boundaries.isEmpty());
  }

  @Test
  public void cancellationDuringCandidateVerificationIsPropagated() throws Exception {
    RecordingFs fs = new RecordingFs();
    File files = temporary.newFolder(), capsules = new File(files, "recovery-capsules");
    Files.createDirectories(capsules.toPath());
    File staging = staging(capsules);
    RecoveryStoragePlan.prepared(fs, staging, runtime, id);
    fs.interruptProofRead = true;
    try {
      RecoveryStoragePlan.reusable(fs, files, capsules, runtime, Set.of());
      fail("cancellation must not become an unreadable candidate");
    } catch (InterruptedIOException expected) {
      assertEquals("CANCELLED", expected.getMessage());
    }
    assertTrue(staging.isDirectory());
  }
}
