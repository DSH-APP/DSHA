package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;
import com.deepseekharness.app.util.WebPidIdentity;
import java.io.IOException;
import org.junit.Test;

/** 真实只读取证算法；仅替换内核读口，不访问或发信号给宿主进程。 */
public final class WebSessionKernelEvidenceTest {
  private static final String INSTANCE = "12345678-1234-1234-1234-123456789abc";

  private static class Kernel extends WebProcessManager {
    int reads, uid = 10001;
    boolean denied, changed, launcher;
    String env =
        "DSHA_WEB_GENERATION=7\0DSH_TASK_INSTANCE="
            + INSTANCE
            + "\0DSH_TASK_TOKEN="
            + "ab".repeat(32)
            + "\0";

    Kernel() {
      super(null);
    }

    @Override
    String[] recoveryProcessEntries() {
      return new String[] {"42"};
    }

    @Override
    int recoveryHostPid() {
      return 100;
    }

    @Override
    int recoveryHostUid() {
      return 10001;
    }

    @Override
    Integer processOwner(int pid) {
      return uid;
    }

    @Override
    String readProcessFile(int pid, String name) throws IOException {
      if (denied) throw new IOException("PROC_DENIED");
      assertEquals("environ", name);
      return env;
    }

    @Override
    ProcessState inspect(int pid) throws IOException {
      if (denied) throw new IOException("PROC_DENIED");
      long start = changed && ++reads > 1 ? 124 : 123;
      var identity =
          WebPidIdentity.parse("42 (node) S 1 1 1 " + "0 ".repeat(15) + start + " 0 0", 42);
      return new ProcessState(
          Kind.WEB,
          identity,
          launcher
              ? "/app/lib/libproot.so\0--rootfs=/private/linux/ubuntu\0"
              : "node /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web");
    }
  }

  @Test
  public void exactEnvironmentAndTwoConsistentBirthsAreCaptured() throws Exception {
    Kernel kernel = new Kernel();
    var evidence = kernel.recoveryProcess(42);
    assertTrue(evidence.alive());
    assertEquals(123, evidence.started());
    assertEquals("7", evidence.generation());
    assertEquals(INSTANCE, evidence.instance());
    assertEquals("ab".repeat(32), evidence.nonce());
    assertEquals(10001, evidence.uid());
  }

  @Test
  public void duplicateNonceProcDenialAndBirthChangeRemainUnknown() throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      Kernel kernel = new Kernel();
      if (kind == 0) kernel.env += "DSH_TASK_TOKEN=" + "cd".repeat(32) + "\0";
      if (kind == 1) kernel.denied = true;
      if (kind == 2) kernel.changed = true;
      try {
        kernel.recoveryProcess(42);
        fail("未知证据不能被认领");
      } catch (IOException expected) {
        assertNotNull(expected.getMessage());
      }
    }
  }

  @Test
  public void legacyAbsenceScanDoesNotReleaseLiveOrUnreadableLauncher() throws Exception {
    Kernel kernel = new Kernel();
    kernel.launcher = true;
    assertFalse(kernel.confirmNoUntrackedWebLaunchers());
    kernel.env = "HOME=/root\0"; // 未识别的旧argv或终端也不是“全部已退出”的证据。
    assertFalse(kernel.confirmNoUntrackedWebLaunchers());
    kernel.denied = true;
    try {
      kernel.confirmNoUntrackedWebLaunchers();
      fail("拒读不是退出证明");
    } catch (IOException expected) {
      assertNotNull(expected);
    }
    kernel.denied = false;
    kernel.uid = 10002;
    assertTrue(kernel.confirmNoUntrackedWebLaunchers());
  }

  @Test
  public void guestObservationNeverTurnsProcDenialOrIdentityMismatchIntoExit() {
    String boot = "12345678-1234-1234-1234-123456789abc";
    var saved =
        new com.deepseekharness.app.util.WebSessionProof.Saved(
            boot, 7, INSTANCE, "ab".repeat(32), 42, 123, "web", 41, 122, "launcher");
    class ObservationKernel extends WebProcessManager {
      boolean denied, live = true, verified = true;
      long birth = 123;
      String actualBoot = boot;

      ObservationKernel() {
        super(null);
      }

      @Override
      String recoveryBoot() throws IOException {
        if (denied) throw new IOException("PROC_DENIED");
        return actualBoot;
      }

      @Override
      com.deepseekharness.app.util.WebSessionProof.Process recoveryProcess(int pid) {
        return new com.deepseekharness.app.util.WebSessionProof.Process(
            pid, birth, 10001, "web", "7", INSTANCE, "ab".repeat(32), live);
      }

      @Override
      public boolean verifySession(com.deepseekharness.app.util.WebSessionProof.Saved proof) {
        return verified;
      }
    }
    var kernel = new ObservationKernel();
    assertEquals(WebProcessManager.SessionObservation.LIVE, kernel.observeSession(saved));
    kernel.denied = true;
    assertEquals(WebProcessManager.SessionObservation.UNKNOWN, kernel.observeSession(saved));
    kernel.denied = false;
    kernel.verified = false;
    assertEquals(WebProcessManager.SessionObservation.UNKNOWN, kernel.observeSession(saved));
    kernel.verified = true;
    assertEquals(WebProcessManager.SessionObservation.LIVE, kernel.observeSession(saved));
    kernel.live = false;
    assertEquals(WebProcessManager.SessionObservation.EXITED, kernel.observeSession(saved));
    kernel.live = true;
    kernel.birth++;
    assertEquals(WebProcessManager.SessionObservation.EXITED, kernel.observeSession(saved));
    kernel.birth = 123;
    kernel.actualBoot = "other-boot";
    assertEquals(WebProcessManager.SessionObservation.EXITED, kernel.observeSession(saved));
  }
}
