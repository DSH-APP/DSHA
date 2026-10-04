package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.ProcessIdentity;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class IsolatedInstallProcessTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static Process process(boolean ended) {
    return new Process() {
      public OutputStream getOutputStream() {
        return OutputStream.nullOutputStream();
      }

      public InputStream getInputStream() {
        return InputStream.nullInputStream();
      }

      public InputStream getErrorStream() {
        return InputStream.nullInputStream();
      }

      public int exitValue() {
        if (!ended) throw new IllegalThreadStateException();
        return 0;
      }

      public int waitFor() {
        return exitValue();
      }

      public void destroy() {}
    };
  }

  private static ProcessIdentity birth() {
    return ProcessIdentity.fromStat(
        "123 (signed leader) S 99 123 123 " + "0 ".repeat(15) + "456", 123, 99);
  }

  @Test
  public void foregroundPollingLeavesStatusRecordForExplicitClose() throws Exception {
    File status = temporary.newFile();
    java.nio.file.Files.writeString(status.toPath(), "7\n");
    var isolated =
        new IsolatedInstallProcess(
            process(false),
            birth(),
            status,
            new IsolatedInstallProcess.ExitEvidence() {
              public boolean groupGone() {
                return false;
              }

              public boolean sessionEmpty() {
                return false;
              }
            });
    assertEquals(7, isolated.exitValue());
    assertFalse(isolated.isAlive());
    assertEquals(7, isolated.exitValue());
    assertTrue(status.isFile());
    assertEquals("7\n", java.nio.file.Files.readString(status.toPath()));
  }

  @Test
  public void timeoutCannotInventExitWhenSupervisorEndedButGuestSessionRemains() throws Exception {
    File status = new File(temporary.getRoot(), "missing");
    var isolated =
        new IsolatedInstallProcess(
            process(true),
            birth(),
            status,
            new IsolatedInstallProcess.ExitEvidence() {
              public boolean groupGone() {
                return true;
              }

              public boolean sessionEmpty() {
                return false;
              }
            });
    long started = System.nanoTime();
    assertFalse(isolated.waitFor(40, TimeUnit.MILLISECONDS));
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500);
    assertThrows(IllegalThreadStateException.class, isolated::exitValue);
    assertTrue(isolated.isAlive());
  }
}
