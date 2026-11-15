package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.WebPidIdentity;
import com.deepseekharness.app.util.WebStopDiagnostic;
import com.deepseekharness.app.util.WebStopException;
import java.io.IOException;
import java.util.ArrayDeque;
import org.junit.Test;

/**
 * 09:13 真机现场回归：PID/identity 文件已退休、强停报 FORCE_EVIDENCE_UNCONFIRMED 时，候选只能来自
 * 停止扫描保留的“双次内核核验一致”的残留 Web 事实；身份变化必须拒绝，不能退化成裸 PID。
 */
public final class WebResidualForceCandidateTest {
  private static final String BRIDGE =
      "libproroot-bridge.so /data/app/pkg/lib/arm64/libproroot-linker.so --argv0 node "
          + "--preload /data/app/pkg/lib/arm64/libproroot-runtime.so "
          + "/data/data/com.dsh/files/linux/ubuntu/usr/local/bin/node "
          + "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web";

  private static WebPidIdentity birth(long started) {
    return WebPidIdentity.parse("42 (node) S 1 1 1 " + "0 ".repeat(15) + started + " 0 0", 42);
  }

  private static WebProcessManager.ProcessState bridge(long started) {
    return new WebProcessManager.ProcessState(WebProcessManager.Kind.WEB, birth(started), BRIDGE);
  }

  /** 只替换内核观察与 /proc 枚举；仍走真实的残留事实/双核验逻辑。 */
  private static final class Manager extends WebProcessManager {
    WebProcessManager.ProcessState state = bridge(100);
    final ArrayDeque<WebProcessManager.ProcessState> queue = new ArrayDeque<>();
    int owner = 10_042, hostUid = 10_042, hostPid = 1;
    String[] entries = {"42"};

    Manager() {
      super(null);
    }

    public String[] recoveryProcessEntries() {
      return entries;
    }

    int recoveryHostPid() {
      return hostPid;
    }

    int recoveryHostUid() {
      return hostUid;
    }

    Integer processOwner(int pid) {
      return owner;
    }

    WebProcessManager.ProcessState inspect(int pid) throws IOException {
      return queue.isEmpty() ? state : queue.removeFirst();
    }
  }

  @Test
  public void doubleVerifiedScanRetainsCandidateForForceStop() throws Exception {
    Manager manager = new Manager();
    java.util.List<WebStopDiagnostic> facts = manager.ownedWebFacts();
    assertEquals(1, facts.size());
    assertEquals(WebStopDiagnostic.Code.OWNED_WEB_REMAINS, facts.get(0).code());
    WebProcessManager.ForceCandidate candidate = manager.residualCandidateChecked();
    assertNotNull(candidate);
    assertEquals(42, candidate.pid());
    assertFalse(candidate.retiresPreviousLauncher());
  }

  @Test
  public void identityChangeAfterTheScanRefusesTheRetainedCandidate() throws Exception {
    Manager manager = new Manager();
    assertNotNull(manager.ownedWebFacts());
    assertNotNull(manager.residualCandidateChecked());
    manager.state = bridge(101); // 同一 PID 已被新进程复用
    WebStopException refused =
        assertThrows(WebStopException.class, manager::residualCandidateChecked);
    assertEquals(WebStopDiagnostic.Code.FORCE_EVIDENCE_UNCONFIRMED, refused.diagnostic().code());
    assertTrue(refused.diagnostic().arguments().contains("PID=42"));
    assertTrue(refused.diagnostic().arguments().contains("未通过=残留 Web 身份已变化"));
  }

  @Test
  public void scanThatCannotDoubleVerifyRetainsNothing() throws Exception {
    Manager manager = new Manager();
    manager.queue.add(bridge(100));
    manager.queue.add(bridge(101)); // 两次核验结论不一致
    assertNotNull(manager.ownedWebFacts());
    assertNull(manager.residualCandidateChecked());
  }

  @Test
  public void clearedScanDropsTheCandidate() throws Exception {
    Manager manager = new Manager();
    assertNotNull(manager.ownedWebFacts());
    manager.state = new WebProcessManager.ProcessState(WebProcessManager.Kind.GONE, null, "");
    manager.entries = new String[] {};
    assertTrue(manager.ownedWebFacts().isEmpty());
    assertNull(manager.residualCandidateChecked());
  }

  @Test
  public void foreignOwnerOrUnsignalableCommandIsNeverRetained() throws Exception {
    Manager manager = new Manager();
    manager.owner = 10_043; // 不是本应用 UID
    manager.ownedWebFacts();
    assertNull(manager.residualCandidateChecked());
    Manager other = new Manager();
    other.state =
        new WebProcessManager.ProcessState(
            WebProcessManager.Kind.WEB, birth(100), "node /usr/local/bin/other.js");
    other.ownedWebFacts();
    assertNull(other.residualCandidateChecked());
  }
}
