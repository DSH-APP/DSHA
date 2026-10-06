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
    final InputStream out, err = new ByteArrayInputStream(new byte[] {2});
    final OutputStream in = new ByteArrayOutputStream();
    int destroyed;

    Launcher() {
      this(new byte[] {1});
    }

    Launcher(byte[] output) {
      out = new ByteArrayInputStream(output);
    }

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
  public void actualTrialOutputAcceptsOnlyOwnedStagesAndReportsPreparationFailures()
      throws Exception {
    Class<?> type = Class.forName("com.deepseekharness.app.runtime.RuntimeTrial$Output");
    var constructor = type.getDeclaredConstructor(Process.class);
    constructor.setAccessible(true);
    var drain = type.getDeclaredMethod("drain");
    drain.setAccessible(true);
    var phase = type.getDeclaredField("nativeStage");
    phase.setAccessible(true);
    Object output =
        constructor.newInstance(
            new Launcher(
                ("DSHA_TRIAL_NATIVE_STAGE modules\n"
                        + "DSHA_TRIAL_NATIVE_STAGE builtin-1\n"
                        + "DSHA_TRIAL_NATIVE_STAGE builtin-999999\n"
                        + "DSHA_TRIAL_NATIVE_STAGE arbitrary user text\n"
                        + "DSHA_TRIAL_NATIVE_STAGE fresh\n"
                        + "DSHA_TRIAL_TIME fresh 1234\n"
                        + "DSHA_TRIAL_TIME arbitrary 4321\n"
                        + "DSHA_TRIAL_TIME fresh 5678\n"
                        + "DSHA_TRIAL_TIME session -1\n")
                    .getBytes(StandardCharsets.UTF_8)));
    drain.invoke(output);
    assertEquals("TRIAL_NATIVE_FRESH", phase.get(output));
    var timings = type.getDeclaredField("nativeTimings");
    timings.setAccessible(true);
    assertEquals(java.util.Map.of("fresh", 1234L), timings.get(output));
    for (String[] row :
        new String[][] {
          {"DSHA_TRIAL_NATIVE_CHECK_FAILED Error: native proof", "TRIAL_NATIVE_MODULES_FAILED"},
          {"DSHA_TRIAL_PLUGIN_CHECK_FAILED Error: fresh token mismatch", "TRIAL_PLUGIN_FAILED"}
        }) {
      Object failed =
          constructor.newInstance(new Launcher((row[0] + "\n").getBytes(StandardCharsets.UTF_8)));
      var thrown =
          assertThrows(
              java.lang.reflect.InvocationTargetException.class, () -> drain.invoke(failed));
      assertTrue(thrown.getCause() instanceof IOException);
      assertEquals(row[1], thrown.getCause().getMessage());
      var diagnostics = type.getDeclaredMethod("diagnostics");
      diagnostics.setAccessible(true);
      assertTrue(String.valueOf(diagnostics.invoke(failed)).contains(row[0]));
    }
  }

  @Test
  public void primaryFailureSurvivesAndLaterConfirmedClosePrecedesLeaseRelease() throws Exception {
    Launcher launcher = new Launcher();
    launcher.exited = true;
    AtomicBoolean confirmed = new AtomicBoolean();
    AtomicInteger records = new AtomicInteger(), releases = new AtomicInteger();
    Process[] retained = new Process[1];
    com.deepseekharness.app.util.RuntimeWorkPort.Work work =
        new com.deepseekharness.app.util.RuntimeWorkPort.Work() {
          public void retainUntilExit(Process value) {
            retained[0] = value;
          }

          public void close() {
            releases.incrementAndGet();
          }
        };
    IOException primary = new IOException("ORIGINAL_PREFLIGHT_FAILURE");
    assertFalse(
        TrialSupport.finish(
            launcher,
            work,
            new TrialSupport.Cleanup() {
              public void stop() {}

              public boolean confirmed(Process process) {
                return confirmed.get();
              }

              public void closeRecord() {
                records.incrementAndGet();
              }
            },
            primary));
    assertEquals("ORIGINAL_PREFLIGHT_FAILURE", primary.getMessage());
    assertEquals(1, primary.getSuppressed().length);
    assertEquals(0, records.get());
    assertEquals(0, releases.get());
    assertThrows(IllegalThreadStateException.class, () -> retained[0].exitValue());
    confirmed.set(true);
    assertEquals(7, retained[0].exitValue());
    assertEquals(7, retained[0].exitValue());
    assertEquals(1, records.get());
  }

  @Test
  public void closeRecordFailureDoesNotReplacePrimaryOrRetainAnAlreadyConfirmedGuest()
      throws Exception {
    Launcher launcher = new Launcher();
    launcher.exited = true;
    AtomicInteger releases = new AtomicInteger();
    com.deepseekharness.app.util.RuntimeWorkPort.Work work =
        new com.deepseekharness.app.util.RuntimeWorkPort.Work() {
          public void retainUntilExit(Process value) {
            fail("already confirmed");
          }

          public void close() {
            releases.incrementAndGet();
          }
        };
    IOException primary = new IOException("NATIVE_CHECK_FAILED");
    assertFalse(
        TrialSupport.finish(
            launcher,
            work,
            new TrialSupport.Cleanup() {
              public void stop() {}

              public boolean confirmed(Process process) {
                return true;
              }

              public void closeRecord() throws IOException {
                throw new IOException("WRITE_DENIED");
              }
            },
            primary));
    assertEquals("NATIVE_CHECK_FAILED", primary.getMessage());
    assertEquals("TRIAL_RECORD_CLOSE_FAILED", primary.getSuppressed()[0].getMessage());
    assertEquals(1, releases.get());
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
      if (process.exitValue() == 78) {
        // MSYS's zero synthetic starttime cannot establish a Linux birth.
        assertFalse(new File(root, ".dsha-web.identity").exists());
        assertTrue(new File(root, ".dsha-web.pid").isFile());
        return;
      }
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
