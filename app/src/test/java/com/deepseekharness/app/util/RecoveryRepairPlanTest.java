package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryRepairPlanTest {
  private static final String SESSION = "0123456789abcdef0123456789abcdef",
      HASH = "a".repeat(64),
      DATA = "b".repeat(64);

  private RecoveryRepairPlan plan() throws IOException {
    return new RecoveryRepairPlan(SESSION, 1, "profile-settings", "profile-web", HASH, DATA, "[]");
  }

  @Test
  public void oldSessionAndGenerationCannotConfirm() throws Exception {
    var p = plan();
    assertThrows(IOException.class, () -> p.begin(SESSION, 2, p.nonce, HASH, DATA));
    assertThrows(IOException.class, () -> p.begin("f".repeat(32), 1, p.nonce, HASH, DATA));
    assertEquals(RecoveryRepairPlan.State.PENDING, p.state());
  }

  @Test
  public void changedSourceOrGenerationExpiresCandidate() throws Exception {
    for (boolean source : new boolean[] {true, false}) {
      var p = plan();
      assertThrows(
          IOException.class,
          () -> p.begin(SESSION, 1, p.nonce, source ? DATA : HASH, source ? DATA : HASH));
      assertEquals(RecoveryRepairPlan.State.EXPIRED, p.state());
    }
  }

  @Test
  public void oneDecisionWinsConcurrentCallbacks() throws Exception {
    var p = plan();
    var ready = new CountDownLatch(1);
    var accepted = new AtomicInteger();
    Runnable action =
        () -> {
          try {
            ready.await();
            p.begin(SESSION, 1, p.nonce, HASH, DATA);
            accepted.incrementAndGet();
          } catch (Exception expected) {
          }
        };
    Thread a = new Thread(action), b = new Thread(action);
    a.start();
    b.start();
    ready.countDown();
    a.join();
    b.join();
    assertEquals(1, accepted.get());
    p.complete(true, "read back");
    assertThrows(IOException.class, () -> p.begin(SESSION, 1, p.nonce, HASH, DATA));
  }

  @Test
  public void rejectionAndClosureNeverBecomeApproval() throws Exception {
    var rejected = plan();
    rejected.reject();
    assertThrows(IOException.class, () -> rejected.begin(SESSION, 1, rejected.nonce, HASH, DATA));
    var expired = plan();
    expired.expire();
    assertThrows(IOException.class, () -> expired.begin(SESSION, 1, expired.nonce, HASH, DATA));
  }

  @Test
  public void arbitraryTargetsAndActionsRejected() throws Exception {
    assertThrows(
        IOException.class,
        () -> new RecoveryRepairPlan(SESSION, 1, "shell", "runtime", HASH, DATA, "rm"));
    assertThrows(
        IOException.class,
        () ->
            new RecoveryRepairPlan(
                SESSION, 1, "profile-settings", "../credentials", HASH, DATA, "[]"));
    assertThrows(
        IOException.class,
        () ->
            new RecoveryRepairPlan(
                SESSION, 1, "profile-settings", "profile-web", HASH, DATA, "x".repeat(262145)));
  }

  @Test
  public void cliDependencyRepairIsPinnedHostAction() throws Exception {
    assertTrue(RecoveryRepairPlan.ACTIONS.contains("repair-cli-dependencies"));
    var p =
        new RecoveryRepairPlan(SESSION, 1, "repair-cli-dependencies", "runtime", HASH, DATA, "");
    p.begin(SESSION, 1, p.nonce, HASH, DATA);
    assertEquals(RecoveryRepairPlan.State.APPLYING, p.state());
  }

  @Test
  public void failureDoesNotPretendApplication() throws Exception {
    var p = plan();
    p.begin(SESSION, 1, p.nonce, HASH, DATA);
    p.complete(false, "PERMISSION_DENIED");
    assertEquals(RecoveryRepairPlan.State.FAILED, p.state());
    assertEquals("PERMISSION_DENIED", p.message());
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        RecoveryRepairPlan.digest(new byte[0]));
  }

  @Test
  public void readOnlyIdentityDoesNotConsumePendingConfirmation() throws Exception {
    var p = plan();
    IOException refused =
        assertThrows(IOException.class, () -> RecoveryRepairPlan.requireWritable(true, "EPERM"));
    assertTrue(refused.getMessage().contains("EPERM"));
    assertEquals(RecoveryRepairPlan.State.PENDING, p.state());
    RecoveryRepairPlan.requireWritable(false, "");
    p.begin(SESSION, 1, p.nonce, HASH, DATA);
    assertThrows(
        IOException.class,
        () -> RecoveryRepairPlan.requireWritable(true, "PROCESS_IDENTITY_UNCONFIRMED"));
    p.complete(false, "REPAIR_READ_ONLY");
    assertEquals(RecoveryRepairPlan.State.FAILED, p.state());
  }

  @Test
  public void nativeRepairLeaseSurvivesBrokerExpiryAndClosesExactlyOnce() throws Exception {
    int baseline = RecoveryRepairPlan.activeNativeRepairs();
    var p = plan();
    try (var lease = p.beginNative(SESSION, 1, p.nonce, HASH, DATA)) {
      assertEquals(baseline + 1, RecoveryRepairPlan.activeNativeRepairs());
      p.expire();
      assertEquals(RecoveryRepairPlan.State.APPLYING, p.state());
      assertThrows(IOException.class, () -> p.beginNative(SESSION, 1, p.nonce, HASH, DATA));
      assertEquals(baseline + 1, RecoveryRepairPlan.activeNativeRepairs());
      p.complete(true, "verified");
      lease.close();
      assertEquals(baseline, RecoveryRepairPlan.activeNativeRepairs());
    }
    assertEquals(baseline, RecoveryRepairPlan.activeNativeRepairs());
    assertEquals(RecoveryRepairPlan.State.APPLIED, p.state());
  }

  @Test
  public void nativeRepairLeaseReleasesOnFailureAndRejectedBeginNeverCounts() throws Exception {
    int baseline = RecoveryRepairPlan.activeNativeRepairs();
    var invalid = plan();
    assertThrows(IOException.class, () -> invalid.beginNative(SESSION, 1, "old-nonce", HASH, DATA));
    assertEquals(baseline, RecoveryRepairPlan.activeNativeRepairs());
    var p = plan();
    assertThrows(
        IOException.class,
        () -> {
          try (var lease = p.beginNative(SESSION, 1, p.nonce, HASH, DATA)) {
            assertEquals(baseline + 1, RecoveryRepairPlan.activeNativeRepairs());
            p.complete(false, "INJECTED_FAILURE");
            throw new IOException("INJECTED_FAILURE");
          }
        });
    assertEquals(baseline, RecoveryRepairPlan.activeNativeRepairs());
    assertEquals(RecoveryRepairPlan.State.FAILED, p.state());
  }
}
