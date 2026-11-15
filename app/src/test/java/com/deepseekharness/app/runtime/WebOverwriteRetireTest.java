package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.WebProofStop;
import com.deepseekharness.app.util.WebSessionProof;
import com.deepseekharness.app.util.WebStopDiagnostic;
import com.deepseekharness.app.util.WebStopException;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.Test;

/**
 * 覆盖更新弃旧版：只认记录里上一实例的完整出生身份。
 *
 * <p>本测试替换只读内核读口；不读取、不发送信号给任何宿主进程。任何一项出生身份不确定（PID 复用、
 * 他人 UID、命令行 / 代次 / 实例 / nonce 不符、两次观察变化）都必须不准备信号。
 */
public final class WebOverwriteRetireTest {
  private static final String BOOT = "12345678-1234-1234-1234-123456789abc";
  private static final String INSTANCE = "23456789-1234-1234-1234-123456789abc";
  private static final String NONCE = "ab".repeat(32);
  private static final String WEB =
      String.join(
          "\0",
          "node",
          "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
          "web",
          "--no-open",
          "--host",
          "127.0.0.1",
          "--port",
          "0",
          "");
  private static final String LAUNCHER =
      "/data/app/lib/arm64/libproot.so\0--rootfs=/data/user/0/com.dsh/client/files/linux/ubuntu\0";
  private static final WebSessionProof.Saved SAVED =
      new WebSessionProof.Saved(BOOT, 7, INSTANCE, NONCE, 42, 1234, WEB, 41, 1233, LAUNCHER);

  /** 只替换 recoveryBoot / recoveryHostUid / recoveryProcess，不访问真实内核。 */
  private static final class Kernel extends WebProcessManager {
    final Deque<WebSessionProof.Process> web = new ArrayDeque<>();
    final Deque<WebSessionProof.Process> launcher = new ArrayDeque<>();
    WebSessionProof.Process webFallback = live(SAVED.webPid(), 1234, WEB);
    WebSessionProof.Process launcherFallback = live(SAVED.launcherPid(), 1233, LAUNCHER);

    Kernel() {
      super(null);
    }

    @Override
    String recoveryBoot() {
      return BOOT;
    }

    @Override
    int recoveryHostUid() {
      return 10001;
    }

    @Override
    WebSessionProof.Process recoveryProcess(int pid) {
      boolean guest = pid == SAVED.webPid();
      Deque<WebSessionProof.Process> queue = guest ? web : launcher;
      return queue.isEmpty() ? (guest ? webFallback : launcherFallback) : queue.removeFirst();
    }
  }

  private static WebSessionProof.Process live(int pid, long started, String command) {
    return new WebSessionProof.Process(pid, started, 10001, command, "7", INSTANCE, NONCE, true);
  }

  private static WebSessionProof.Process exited(int pid) {
    return new WebSessionProof.Process(pid, 0, -1, "", "", "", "", false);
  }

  @Test
  public void livePreviousBuildPreparesGuestAndLauncherCandidate() throws Exception {
    Kernel kernel = new Kernel();
    var candidate = kernel.prepareOverwriteRetire(SAVED);
    assertNotNull(candidate);
    assertTrue(candidate.retiresPreviousLauncher());
    assertEquals(SAVED.webPid(), candidate.pid());
  }

  @Test
  public void guestAlreadyExitedStillPreparesLauncherOnlyRetire() throws Exception {
    Kernel kernel = new Kernel();
    kernel.webFallback = exited(SAVED.webPid());
    var candidate = kernel.prepareOverwriteRetire(SAVED);
    assertNotNull(candidate);
    assertTrue(candidate.retiresPreviousLauncher());
  }

  @Test
  public void bothExitedHasNothingToRetire() throws Exception {
    Kernel kernel = new Kernel();
    kernel.webFallback = exited(SAVED.webPid());
    kernel.launcherFallback = exited(SAVED.launcherPid());
    assertNull(kernel.prepareOverwriteRetire(SAVED));
  }

  @Test
  public void reusedLauncherPidIsExitProofNotASignalCandidate() throws Exception {
    Kernel kernel = new Kernel();
    kernel.webFallback = exited(SAVED.webPid());
    kernel.launcherFallback = live(SAVED.launcherPid(), SAVED.launcherStarted() + 1, LAUNCHER);
    assertEquals(WebProofStop.Observation.EXITED, kernel.observeRecordedLauncher(SAVED));
    assertNull(kernel.prepareOverwriteRetire(SAVED));
  }

  @Test
  public void launcherWithOtherUidOrUnreadableEnvironmentNeverPrepares() {
    assertFalse(
        WebSessionProof.matchesLauncher(
            SAVED,
            10001,
            new WebSessionProof.Process(
                SAVED.launcherPid(),
                SAVED.launcherStarted(),
                10002,
                LAUNCHER,
                "7",
                INSTANCE,
                NONCE,
                true)));
    Kernel kernel = new Kernel();
    kernel.launcherFallback =
        new WebSessionProof.Process(
            SAVED.launcherPid(),
            SAVED.launcherStarted(),
            10001,
            LAUNCHER,
            null,
            INSTANCE,
            NONCE,
            true);
    assertEquals(WebProofStop.Observation.UNKNOWN, kernel.observeRecordedLauncher(SAVED));
    WebStopException rejected =
        assertThrows(WebStopException.class, () -> kernel.prepareOverwriteRetire(SAVED));
    assertEquals(WebStopDiagnostic.Code.RECORDED_SESSION_UNCONFIRMED, rejected.diagnostic().code());
  }

  @Test
  public void launcherChangingBetweenTwoReadsNeverPreparesGuesses() {
    Kernel kernel = new Kernel();
    // guest 的两次观察读到 LIVE；启动器两次观察必须一致，第一次 LIVE、第二次 EXITED 时拒绝。
    kernel.launcher.add(live(SAVED.launcherPid(), 1233, LAUNCHER));
    kernel.launcher.add(live(SAVED.launcherPid(), 1233, LAUNCHER));
    kernel.launcher.add(live(SAVED.launcherPid(), 1233, LAUNCHER));
    kernel.launcher.add(exited(SAVED.launcherPid()));
    WebStopException rejected =
        assertThrows(WebStopException.class, () -> kernel.prepareOverwriteRetire(SAVED));
    assertEquals(WebStopDiagnostic.Code.RECORDED_SESSION_UNCONFIRMED, rejected.diagnostic().code());
  }
}
