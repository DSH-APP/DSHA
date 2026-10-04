package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.ProcStat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TrialSupportTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static final class Launcher extends Process {
    volatile boolean exited;
    final InputStream out = new ByteArrayInputStream(new byte[] {1}),
        err = new ByteArrayInputStream(new byte[] {2});
    final OutputStream in = new ByteArrayOutputStream();
    int destroyed;

    public int exitValue() {
      if (!exited) throw new IllegalThreadStateException();
      return 7;
    }

    public int waitFor() {
      return 7;
    }

    public void destroy() {
      destroyed++;
    }

    public InputStream getInputStream() {
      return out;
    }

    public InputStream getErrorStream() {
      return err;
    }

    public OutputStream getOutputStream() {
      return in;
    }
  }

  @Test
  public void launcherExitOrDeniedGuestReadNeverReleasesOwnedLease() throws Exception {
    Launcher launcher = new Launcher();
    AtomicBoolean guest = new AtomicBoolean();
    AtomicInteger checks = new AtomicInteger(), stop = new AtomicInteger();
    Process lease =
        TrialSupport.checkedExit(
            launcher,
            p -> {
              assertSame(launcher, p);
              checks.incrementAndGet();
              return guest.get();
            },
            stop::incrementAndGet,
            "TRIAL_PROCESS_UNCONFIRMED");
    assertThrows(IllegalThreadStateException.class, lease::exitValue);
    assertEquals(0, checks.get());
    launcher.exited = true;
    assertThrows(IllegalThreadStateException.class, lease::exitValue);
    assertEquals(1, checks.get());
    Process denied =
        TrialSupport.checkedExit(
            launcher,
            p -> {
              throw new IOException("EPERM");
            },
            () -> {},
            "TRIAL_PROCESS_UNCONFIRMED");
    assertThrows(IllegalThreadStateException.class, denied::exitValue);
    guest.set(true);
    assertEquals(7, lease.exitValue());
    assertSame(launcher.out, lease.getInputStream());
    assertSame(launcher.err, lease.getErrorStream());
    assertSame(launcher.in, lease.getOutputStream());
    lease.destroy();
    assertEquals(1, stop.get());
    assertEquals(0, launcher.destroyed);
  }

  @Test
  public void interruptedWaitKeepsUnknownGuestAndLaterClosedGuestCanComplete() throws Exception {
    Launcher launcher = new Launcher();
    launcher.exited = true;
    AtomicBoolean guest = new AtomicBoolean();
    Process lease =
        TrialSupport.checkedExit(
            launcher, p -> guest.get(), () -> {}, "SETTINGS_TRIAL_PROCESS_UNCONFIRMED");
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> value = worker.submit((Callable<Integer>) lease::waitFor);
      assertThrows(TimeoutException.class, () -> value.get(150, TimeUnit.MILLISECONDS));
      guest.set(true);
      assertEquals(Integer.valueOf(7), value.get(2, TimeUnit.SECONDS));
      guest.set(false);
      Thread.currentThread().interrupt();
      try {
        assertThrows(InterruptedException.class, lease::waitFor);
      } finally {
        Thread.interrupted();
      }
    } finally {
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  public void commonWriteCreatesClosesSyncsAndNeverOverwritesExistingBytes() throws Exception {
    File root = temporary.newFolder();
    AtomicInteger syncs = new AtomicInteger();
    JvmBackupFileSystem fs =
        new JvmBackupFileSystem() {
          public void syncDirectory(File dir) throws IOException {
            syncs.incrementAndGet();
            super.syncDirectory(dir);
          }
        };
    TrialSupport.write(fs, root, "profile/file", new byte[] {1, 2, 3});
    assertArrayEquals(
        new byte[] {1, 2, 3}, Files.readAllBytes(new File(root, "profile/file").toPath()));
    assertTrue(syncs.get() > 0);
    assertThrows(
        IOException.class, () -> TrialSupport.write(fs, root, "profile/file", new byte[] {4}));
    assertArrayEquals(
        new byte[] {1, 2, 3}, Files.readAllBytes(new File(root, "profile/file").toPath()));
    JvmBackupFileSystem deny =
        new JvmBackupFileSystem() {
          public Node stat(File file) throws IOException {
            Node node = super.stat(file);
            return file.getName().equals("profile") ? new Node("LINK", "link", 0, 0, 0, 0) : node;
          }
        };
    assertThrows(
        IOException.class, () -> TrialSupport.write(deny, root, "profile/other", new byte[] {5}));
    assertFalse(new File(root, "profile/other").exists());
    JvmBackupFileSystem syncFail =
        new JvmBackupFileSystem() {
          public void syncDirectory(File dir) throws IOException {
            throw new IOException("SYNC_FAILED");
          }
        };
    assertThrows(
        IOException.class,
        () -> TrialSupport.write(syncFail, root, "sync-failure", new byte[] {6}));
  }

  @Test
  public void actualBashIdentityPrefixRecordsItsOwnPidAndBirthField() throws Exception {
    String bash = System.getProperty("dsha.test.bash", "bash");
    if (System.getProperty("os.name").startsWith("Windows")
        && System.getProperty("dsha.test.bash") == null) {
      File git = new File(System.getenv("ProgramFiles"), "Git/bin/bash.exe");
      Assume.assumeTrue("Bash host fixture is required", git.isFile());
      bash = git.getAbsolutePath();
    }
    File root = temporary.newFolder(), script = new File(root, "identity-fixture.sh");
    Files.writeString(
        script.toPath(),
        TrialSupport.identityPrefix()
            + "printf '%s\\n' \"$$\"; printf '%s\\n' \"$DSHA_STAT\"; printf '%s\\n' \"${20}\"\n",
        StandardCharsets.UTF_8);
    Process process =
        new ProcessBuilder(bash, script.getAbsolutePath().replace('\\', '/'))
            .directory(root)
            .start();
    try {
      assertTrue("bounded bash fixture", process.waitFor(5, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue());
      String[] lines =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
              .trim()
              .split("\\n", 3);
      assertEquals(3, lines.length);
      int pid = Integer.parseInt(lines[0].trim());
      long born = Long.parseLong(lines[2].trim());
      ProcStat stat = ProcStat.parse(lines[1].trim(), pid);
      // MSYS provides a synthetic stat with zero starttime. It exercises the
      // real shell mechanics but cannot grant a Linux/Android birth identity.
      if (born == 0) assertNull(stat);
      else {
        assertNotNull(stat);
        assertEquals(born, stat.started);
      }
      assertEquals(pid + "", Files.readString(new File(root, ".dsha-web.pid").toPath()).trim());
      assertEquals(
          pid + " " + born, Files.readString(new File(root, ".dsha-web.identity").toPath()).trim());
    } finally {
      if (process.isAlive()) process.destroy();
    }
  }
}
