package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BoundedGuestSessionsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void inventoryReportsEveryRecordAndALaterInvalidRecordStillBlocks() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    var a = BoundedGuestSessions.begin(fs, files);
    var b = BoundedGuestSessions.begin(fs, files);
    a.uncertain();
    b.uncertain();
    fs.create(new File(files, "bounded-guest-active/" + b.id() + "/unexpected")).close();
    org.junit.Assert.assertEquals(2, BoundedGuestSessions.inspect(fs, files).size());
    org.junit.Assert.assertTrue(
        BoundedGuestSessions.inspect(fs, files).stream()
            .anyMatch(i -> i.state().equals("INVALID")));
    assertNotNull(BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void inProcessCommandIsIgnoredButUncertainRecordBlocksStartup() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    BoundedGuestSessions.Operation operation = BoundedGuestSessions.begin(fs, files);
    assertNull(BoundedGuestSessions.firstPending(fs, files));
    operation.uncertain();
    assertNotNull(BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void unknownRecordMemberCannotBeSilentlyRetired() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    BoundedGuestSessions.Operation operation = BoundedGuestSessions.begin(fs, files);
    operation.uncertain();
    String id = BoundedGuestSessions.firstPending(fs, files);
    File entry = new File(new File(files, "bounded-guest-active"), id);
    fs.create(new File(entry, "unexpected")).close();
    assertThrows(IOException.class, () -> BoundedGuestSessions.reapExited(fs, files));
  }

  @Test
  public void interruptedIntentPublicationBeforeHandshakeCanBeReaped() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    File home = new File(files, "bounded-guest-active");
    fs.directory(home);
    String id = UUID.randomUUID().toString();
    File entry = new File(home, id);
    fs.directory(entry);
    write(fs, new File(entry, "intent.tmp-" + UUID.randomUUID()), id.substring(0, 12));
    assertNotNull(BoundedGuestSessions.firstPending(fs, files));
    BoundedGuestSessions.reapExited(fs, files);
    assertNull(BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void interruptedIdentityPublicationBeforeHandshakeCanBeReaped() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    BoundedGuestSessions.Operation operation = BoundedGuestSessions.begin(fs, files);
    operation.uncertain();
    String id = BoundedGuestSessions.firstPending(fs, files);
    File entry = new File(new File(files, "bounded-guest-active"), id);
    write(fs, new File(entry, "identity.tmp-" + UUID.randomUUID()), id + "\n123 456 ");
    BoundedGuestSessions.reapExited(fs, files);
    assertNull(BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void cleanupInterruptedAfterIdentityUnlinkRetiresOnlyKnownMembers() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    BoundedGuestSessions.Operation operation = BoundedGuestSessions.begin(fs, files);
    operation.uncertain();
    String id = BoundedGuestSessions.firstPending(fs, files);
    File entry = new File(new File(files, "bounded-guest-active"), id);
    File identity = new File(entry, "identity");
    write(fs, identity, id + "\n123 456 123\n");
    fs.delete(identity); // The group was already confirmed gone before cleanup began.
    write(fs, new File(entry, "identity.tmp-" + UUID.randomUUID()), id + "\n123 ");
    BoundedGuestSessions.reapExited(fs, files);
    assertNull(BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void nonFileInKnownTemporarySlotStillBlocksRecovery() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    BoundedGuestSessions.Operation operation = BoundedGuestSessions.begin(fs, files);
    operation.uncertain();
    String id = BoundedGuestSessions.firstPending(fs, files);
    File entry = new File(new File(files, "bounded-guest-active"), id);
    fs.directory(new File(entry, "identity.tmp-" + UUID.randomUUID()));
    assertThrows(IOException.class, () -> BoundedGuestSessions.reapExited(fs, files));
  }

  @Test
  public void corruptRecordIsRetainedWhileLaterProvenEmptyRecordIsPruned() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    var bad = BoundedGuestSessions.begin(fs, files);
    var good = BoundedGuestSessions.begin(fs, files);
    bad.uncertain();
    good.uncertain();
    File home = new File(files, "bounded-guest-active");
    write(fs, new File(home, bad.id() + "/identity"), "bad identity\n");
    write(fs, new File(home, good.id() + "/identity"), good.id() + "\n123 456 123\n");
    IOException blocked =
        assertThrows(
            IOException.class,
            () -> BoundedGuestSessions.reapExited(fs, files, (pid, born) -> true));
    org.junit.Assert.assertTrue(blocked.getMessage().contains(bad.id()));
    org.junit.Assert.assertTrue(new File(home, bad.id()).isDirectory());
    org.junit.Assert.assertFalse(new File(home, good.id()).exists());
    org.junit.Assert.assertEquals(bad.id(), BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void cancelledReapingDoesNotContinueIntoLaterRecords() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    var first = BoundedGuestSessions.begin(fs, files);
    var later = BoundedGuestSessions.begin(fs, files);
    first.uncertain();
    later.uncertain();
    File home = new File(files, "bounded-guest-active");
    write(fs, new File(home, first.id() + "/identity"), first.id() + "\n123 456 123\n");
    write(fs, new File(home, later.id() + "/identity"), later.id() + "\n124 457 124\n");
    assertThrows(
        java.io.InterruptedIOException.class,
        () ->
            BoundedGuestSessions.reapExited(
                fs,
                files,
                (pid, born) -> {
                  throw new java.io.InterruptedIOException("CANCELLED");
                }));
    org.junit.Assert.assertTrue(new File(home, first.id()).isDirectory());
    org.junit.Assert.assertTrue(new File(home, later.id()).isDirectory());
  }

  private static void write(JvmBackupFileSystem fs, File file, String value) throws IOException {
    try (var out = fs.create(file)) {
      out.write(value.getBytes(StandardCharsets.US_ASCII));
    }
  }
}
