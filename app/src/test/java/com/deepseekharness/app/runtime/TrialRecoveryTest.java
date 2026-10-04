package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Actual recovery delegate with real private files; Stopper is explicitly a platform-result fixture. */
public final class TrialRecoveryTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();

  private File home() throws IOException {
    return temporary.newFolder("runtime-trials");
  }

  private File entry(File home) throws IOException {
    File entry = new File(home, UUID.randomUUID().toString());
    Files.createDirectories(new File(entry, "payload").toPath());
    Files.writeString(new File(entry, "payload/owned-data").toPath(), "owned private bytes");
    return entry;
  }

  private void write(File entry, String name, String body) throws IOException {
    Files.writeString(new File(entry, name).toPath(), body);
  }

  private void retained(File entry) throws IOException {
    assertFalse(new File(entry, "closed").exists());
    assertEquals(
        "owned private bytes", Files.readString(new File(entry, "payload/owned-data").toPath()));
  }

  @Test
  public void missingHomeDoesNotCreateOrInvokeAnything() throws Exception {
    File missing = new File(temporary.getRoot(), "missing");
    TrialRecovery.recover(
        fs,
        missing,
        payload -> {
          throw new AssertionError("unexpected stop");
        });
    assertFalse(missing.exists());
  }

  @Test
  public void neverLaunchedTrialClosesAndRemovesOnlyItsOwnedPayload() throws Exception {
    File home = home(), entry = entry(home);
    write(entry, "retained-diagnostic", "diagnostic");
    TrialRecovery.recover(
        fs,
        home,
        payload -> {
          throw new AssertionError("not launched");
        });
    assertEquals(entry.getName(), Files.readString(new File(entry, "closed").toPath()));
    assertFalse(new File(entry, "payload").exists());
    assertEquals("diagnostic", Files.readString(new File(entry, "retained-diagnostic").toPath()));
  }

  @Test
  public void launchedTrialWithoutPidKeepsPayloadAndNeverCallsStopper() throws Exception {
    File home = home(), entry = entry(home);
    write(entry, "launched", entry.getName());
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                TrialRecovery.recover(
                    fs,
                    home,
                    payload -> {
                      throw new AssertionError("unknown PID");
                    }));
    assertEquals("TRIAL_PROCESS_UNCONFIRMED", error.getMessage());
    retained(entry);
  }

  @Test
  public void unconfirmedPlatformStopCannotPublishClosedOrDeletePayload() throws Exception {
    File home = home(), entry = entry(home);
    write(entry, "launched", entry.getName());
    write(entry, "payload/.dsha-web.pid", "42");
    AtomicInteger called = new AtomicInteger();
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                TrialRecovery.recover(
                    fs,
                    home,
                    payload -> {
                      assertEquals(new File(entry, "payload"), payload);
                      called.incrementAndGet();
                      throw new IOException("TRIAL_PROCESS_UNCONFIRMED");
                    }));
    assertEquals("TRIAL_PROCESS_UNCONFIRMED", error.getMessage());
    assertEquals(1, called.get());
    retained(entry);
  }

  @Test
  public void stalePidStillRequiresPlatformConfirmationBeforeCleanup() throws Exception {
    File home = home(), entry = entry(home);
    write(entry, "launched", entry.getName());
    write(entry, "payload/.dsha-web.pid.stale", "42");
    AtomicInteger called = new AtomicInteger();
    TrialRecovery.recover(
        fs,
        home,
        payload -> {
          called.incrementAndGet();
          assertFalse(new File(entry, "closed").exists());
        });
    assertEquals(1, called.get());
    assertTrue(new File(entry, "closed").isFile());
    assertFalse(new File(entry, "payload").exists());
  }

  @Test
  public void confirmedClosedTrialDoesNotSignalAgain() throws Exception {
    File home = home(), entry = entry(home);
    write(entry, "closed", entry.getName());
    TrialRecovery.recover(
        fs,
        home,
        payload -> {
          throw new AssertionError("already closed");
        });
    assertFalse(new File(entry, "payload").exists());
    assertEquals(entry.getName(), Files.readString(new File(entry, "closed").toPath()));
  }

  @Test
  public void forgedClosedMarkerKeepsAllPayloadBytes() throws Exception {
    File home = home(), entry = entry(home);
    write(entry, "closed", UUID.randomUUID().toString());
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                TrialRecovery.recover(
                    fs,
                    home,
                    payload -> {
                      throw new AssertionError("bad marker");
                    }));
    assertEquals("TRIAL_MARKER", error.getMessage());
    assertEquals(
        "owned private bytes", Files.readString(new File(entry, "payload/owned-data").toPath()));
  }

  @Test
  public void unexpectedDirectoryCannotBecomeARecoveryAuthority() throws Exception {
    File home = home(), bad = new File(home, "not-a-trial");
    Files.createDirectory(bad.toPath());
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                TrialRecovery.recover(
                    fs,
                    home,
                    payload -> {
                      throw new AssertionError("bad directory");
                    }));
    assertEquals("TRIAL_DIRECTORY", error.getMessage());
    assertTrue(bad.isDirectory());
  }

  @Test
  public void injectedParentLinkCannotReachStopOrWriteBoundary() throws Exception {
    File home = home(), entry = entry(home);
    write(entry, "launched", entry.getName());
    write(entry, "payload/.dsha-web.pid", "42");
    var linked =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            Node real = super.stat(file);
            return file.equals(entry)
                ? new Node("LINK", real.key, real.size, real.modified, real.device, real.mode)
                : real;
          }
        };
    assertThrows(
        IOException.class,
        () ->
            TrialRecovery.recover(
                linked,
                home,
                payload -> {
                  throw new AssertionError("linked parent");
                }));
    retained(entry);
  }
}
