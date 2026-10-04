package com.deepseekharness.app.recovery;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class RecoveryStoragePlanTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  final JvmBackupFileSystem fs = new JvmBackupFileSystem();
  final String runtime = "1".repeat(64), id = "a".repeat(32);
  File files, capsules, staging;

  @Before
  public void fixture() throws Exception {
    files = temporary.newFolder();
    capsules = new File(files, "recovery-capsules");
    fs.directory(capsules);
    staging = new File(capsules, runtime + ".pending-" + id);
    fs.directory(staging);
    Files.createDirectories(new File(staging, "linux/ubuntu/bin").toPath());
    Files.writeString(new File(staging, "linux/ubuntu/bin/node").toPath(), "signed fixture");
    Files.createDirectories(new File(files, "recovery-sessions/" + id).toPath());
  }

  @Test
  public void verifiedCompleteCandidateCanBeReusedWithoutRecopy() throws Exception {
    RecoveryStoragePlan.prepared(fs, staging, runtime, id);
    assertEquals(staging, RecoveryStoragePlan.reusable(fs, files, capsules, runtime, Set.of()));
    assertNull(
        RecoveryStoragePlan.reusable(
            fs, files, capsules, runtime, Set.of(staging.getAbsolutePath())));
  }

  @Test
  public void extraOrModifiedOriginalAndLaunchedSessionsAreAlwaysRetained() throws Exception {
    RecoveryStoragePlan.prepared(fs, staging, runtime, id);
    File extra = new File(staging, "linux/ubuntu/personal");
    Files.writeString(extra.toPath(), "user original");
    assertNull(RecoveryStoragePlan.reusable(fs, files, capsules, runtime, Set.of()));
    assertEquals("user original", Files.readString(extra.toPath()));
    Files.delete(extra.toPath());
    Files.writeString(new File(files, "recovery-sessions/" + id + "/launched").toPath(), id);
    assertNull(RecoveryStoragePlan.reusable(fs, files, capsules, runtime, Set.of()));
    assertTrue(staging.isDirectory());
  }

  @Test
  public void incompleteUnknownCandidatesAreNotReusableOrDeleted() throws Exception {
    assertNull(RecoveryStoragePlan.reusable(fs, files, capsules, runtime, Set.of()));
    assertTrue(new File(staging, "linux/ubuntu/bin/node").isFile());
    var usage = RecoveryStoragePlan.usage(fs, files);
    assertEquals(false, usage.get("automaticDeletion"));
    assertTrue(((Number) usage.get("recovery-capsules")).longValue() > 0);
    assertTrue(staging.isDirectory());
  }
}
