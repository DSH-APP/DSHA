package com.deepseekharness.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import com.deepseekharness.app.util.TerminalSession;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class TerminalSessionOwnerTest {
  @Test
  public void failedCloseKeepsIdAndNumberUntilVerifiedRetry() throws Exception {
    TerminalSessionOwner owner = new TerminalSessionOwner();
    assertFalse(((Object) owner.simpleTabs()) instanceof com.deepseekharness.app.util.TerminalTabs);
    assertFalse(((Object) owner.ptyTabs()) instanceof com.deepseekharness.app.util.TerminalTabs);
    var first = owner.addSimple(backend());
    var second = owner.addSimple(backend());
    assertEquals(1, first.number);
    assertEquals(2, second.number);
    assertTrue(owner.beginCloseSimple(first.id));
    assertThrows(IOException.class, () -> owner.closeSimple(first.id, 1, (tab, wait) -> false));
    assertNotNull(owner.simpleTabs().find(first.id));
    assertFalse(owner.simpleTabs().find(first.id).isClosing());
    var third = owner.addSimple(backend());
    assertEquals(3, third.number);
    owner.closeSimple(first.id, 1, (tab, wait) -> true);
    var reused = owner.addSimple(backend());
    assertEquals(1, reused.number);
    assertTrue(first.id < second.id && second.id < third.id && third.id < reused.id);
    owner.closeAllSimpleAndConfirm(1000);
    assertTrue(owner.simpleTabs().snapshot().isEmpty());
  }

  @Test
  public void detachingOldObserverCannotRemoveNewPageObserver() {
    TerminalSessionOwner owner = new TerminalSessionOwner();
    AtomicInteger oldEvents = new AtomicInteger(), newEvents = new AtomicInteger();
    TerminalSessionOwner.SimpleObserver old = observer(oldEvents);
    TerminalSessionOwner.SimpleObserver current = observer(newEvents);
    owner.attachSimple(old);
    owner.addSimple(backend());
    assertEquals(1, oldEvents.get());
    owner.attachSimple(current);
    owner.detachSimple(old);
    owner.addSimple(backend());
    assertEquals(1, oldEvents.get());
    assertEquals(1, newEvents.get());
    owner.detachSimple(current);
    owner.addSimple(backend());
    assertEquals(1, newEvents.get());
  }

  @Test
  public void ownerEpochAndInitialIntentAreProcessScopedNotDisplayNumbers() throws Exception {
    TerminalSessionOwner first = new TerminalSessionOwner();
    TerminalSessionOwner second = new TerminalSessionOwner();
    assertFalse(first.ownerEpoch().equals(second.ownerEpoch()));
    assertEquals(first.ownerEpoch(), first.epoch());
    assertTrue(first.claimPtyInitialCreation());
    assertFalse(first.claimPtyInitialCreation());
    assertTrue(first.claimSimpleInitialCreation());
    assertFalse(first.claimSimpleInitialCreation());
    // 认领取消后不在旋转重放，但显式新建仍可用。
    var tab = first.addSimple(backend());
    first.closeSimple(tab.id, 1000);
    assertFalse(first.claimSimpleInitialCreation());
    assertEquals(1, first.addSimple(backend()).number);
    first.closeAllSimpleAndConfirm(1000);
  }

  @Test
  public void failedClosePublishesSanitizedPersistentStateAndBlocksInput() throws Exception {
    TerminalSessionOwner owner = new TerminalSessionOwner();
    var tab = owner.addSimple(backend());
    assertTrue(owner.isSimpleInputAllowed(tab.id));
    assertTrue(owner.renameSimple(tab.id, "API_KEY=private-value\nworkspace"));
    assertFalse(tab.customTitle().contains("private-value"));
    assertTrue(owner.beginCloseSimple(tab.id));
    assertFalse(owner.isSimpleInputAllowed(tab.id));
    assertThrows(
        IllegalStateException.class,
        () ->
            owner.closeSimple(
                tab.id,
                1,
                (value, wait) -> {
                  throw new IllegalStateException("password=private-value\nnot stopped");
                }));
    assertTrue(tab.isCloseUnconfirmed());
    assertFalse(tab.closeError().contains("private-value"));
    assertFalse(tab.closeError().contains("\n"));
    assertFalse(owner.isSimpleInputAllowed(tab.id));
    owner.detachSimple(null);
    assertNotNull(owner.simpleTabs().find(tab.id));
    owner.closeSimple(tab.id, 1000);
    assertEquals(
        com.deepseekharness.app.util.TerminalTabs.CleanupState.VERIFIED, tab.cleanupState());
  }

  @Test
  public void protectedOwnerRejectsBareCreationAndWrongEpochBeforeBackendOpens() throws Exception {
    var protection = new TerminalRuntimeProtection(() -> 1L);
    TerminalSessionOwner owner = new TerminalSessionOwner(protection);
    assertThrows(IllegalStateException.class, () -> owner.addSimple(backend()));
    var ticket =
        protection.prepare("wrong-owner", TerminalRuntimeProtection.Kind.SIMPLE, admission());
    protection.promoted(ticket.id(), new Object(), () -> true);
    assertThrows(IOException.class, () -> owner.createSimple(ticket, backend()));
    assertTrue(owner.simpleTabs().snapshot().isEmpty());
    assertTrue(ticket.cancel());
  }

  @Test
  public void simpleFailedStartKeepsLogicalHoldUntilExplicitVerifiedDispose() throws Exception {
    var protection = new TerminalRuntimeProtection(() -> 1L);
    TerminalSessionOwner owner = new TerminalSessionOwner(protection);
    CountDownLatch failure = new CountDownLatch(1);
    TerminalSession.Backend backend =
        new TerminalSession.Backend() {
          @Override
          public Process open() throws Exception {
            failure.countDown();
            throw new IOException("definitely not started");
          }

          @Override
          public void terminate(Process process, long group) {}
        };
    var ticket =
        protection.prepare(owner.epoch(), TerminalRuntimeProtection.Kind.SIMPLE, admission());
    Object service = new Object();
    protection.promoted(ticket.id(), service, () -> true);
    var tab = owner.createSimple(ticket, backend);
    assertTrue(failure.await(1, TimeUnit.SECONDS));
    assertEquals(1, protection.snapshot().held);
    assertFalse(ticket.cancel());
    assertThrows(IOException.class, () -> owner.createSimple(ticket, backend));
    // 服务丢失只降级，不删除同 ID，也不取消已创建后端。
    protection.serviceDestroyed(service);
    assertEquals(TerminalRuntimeProtection.State.LOST, owner.protectionSnapshot().state);
    assertFalse(owner.isSimpleInputAllowed(tab.id));
    assertNotNull(owner.simpleTabs().find(tab.id));
    owner.closeSimple(tab.id, 1000);
    assertEquals(TerminalRuntimeProtection.State.RELEASED, owner.protectionSnapshot().state);
  }

  @Test
  public void protectionLossIsReportedForTheExactSessionOnly() throws Exception {
    var protection = new TerminalRuntimeProtection(() -> 1L);
    var owner = new TerminalSessionOwner(protection);
    Object oldService = new Object(), newService = new Object();
    AtomicBoolean newServiceAlive = new AtomicBoolean(true);
    CountDownLatch oldStarted = new CountDownLatch(1);
    CountDownLatch failedStarts = new CountDownLatch(2);
    TerminalSession.Backend neverStarted =
        new TerminalSession.Backend() {
          @Override
          public Process open() throws IOException {
            oldStarted.countDown();
            failedStarts.countDown();
            throw new IOException("definitely not started");
          }

          @Override
          public void terminate(Process process, long group) {}
        };
    var oldSimpleTicket =
        protection.prepare(owner.epoch(), TerminalRuntimeProtection.Kind.SIMPLE, admission());
    assertTrue(protection.promoted(oldSimpleTicket.id(), oldService, () -> true));
    var oldSimple = owner.createSimple(oldSimpleTicket, neverStarted);
    var oldPtyTicket =
        protection.prepare(owner.epoch(), TerminalRuntimeProtection.Kind.PTY, admission());
    assertTrue(protection.promoted(oldPtyTicket.id(), oldService, () -> true));
    var oldPty = createUnstartedPty(owner, oldPtyTicket);
    assertTrue(oldStarted.await(2, TimeUnit.SECONDS));
    protection.serviceDestroyed(oldService);

    var newSimpleTicket =
        protection.prepare(owner.epoch(), TerminalRuntimeProtection.Kind.SIMPLE, admission());
    assertTrue(protection.promoted(newSimpleTicket.id(), newService, newServiceAlive::get));
    var newSimple = owner.createSimple(newSimpleTicket, neverStarted);
    var newPtyTicket =
        protection.prepare(owner.epoch(), TerminalRuntimeProtection.Kind.PTY, admission());
    assertTrue(protection.promoted(newPtyTicket.id(), newService, newServiceAlive::get));
    var newPty = createUnstartedPty(owner, newPtyTicket);
    try {
      assertTrue(failedStarts.await(2, TimeUnit.SECONDS));
      assertEquals(TerminalRuntimeProtection.State.LOST, owner.protectionSnapshot().state);
      assertTrue(owner.isSimpleProtectionLost(oldSimple.id));
      assertTrue(owner.isPtyProtectionLost(oldPty.id));
      assertFalse(owner.isSimpleInputAllowed(oldSimple.id));
      assertFalse(owner.isSimpleProtectionLost(newSimple.id));
      assertFalse(owner.isPtyProtectionLost(newPty.id));
      assertTrue(owner.isSimpleInputAllowed(newSimple.id));
      // A stale service supplier must also disable only its own active tickets.
      newServiceAlive.set(false);
      assertTrue(owner.isSimpleProtectionLost(newSimple.id));
      assertTrue(owner.isPtyProtectionLost(newPty.id));
      assertFalse(owner.isSimpleInputAllowed(newSimple.id));
    } finally {
      owner.closeSimple(oldSimple.id, 1000);
      owner.closePty(oldPty.id, 1000);
      owner.closeSimple(newSimple.id, 1000);
      owner.closePty(newPty.id, 1000);
    }
    assertFalse(protection.hasDemand());
  }

  @Test
  public void detachedSimplePaneStillNotifiesIndependentStateObserver() throws Exception {
    var owner = new TerminalSessionOwner();
    var tab =
        owner.addSimple(
            new TerminalSession.Backend() {
              @Override
              public Process open() throws IOException {
                throw new IOException("definitely not started");
              }

              @Override
              public void terminate(Process process, long group) {}
            });
    CountDownLatch failure = new CountDownLatch(1);
    AtomicInteger changes = new AtomicInteger();
    Runnable broken =
        () -> {
          throw new IllegalStateException("stale observer");
        };
    Runnable observer =
        () -> {
          changes.incrementAndGet();
          if (tab.value.session.state() == TerminalSession.State.FAILED) failure.countDown();
        };
    owner.addSimpleStateObserver(broken);
    owner.addSimpleStateObserver(observer);
    owner.addSimpleStateObserver(observer);
    tab.value.session.ensureStarted();
    assertTrue(failure.await(2, TimeUnit.SECONDS));
    assertEquals(2, changes.get()); // STARTING and FAILED, without duplicate registration.
    owner.removeSimpleStateObserver(observer);
    owner.removeSimpleStateObserver(broken);
    assertTrue(tab.value.session.shutdownAndWait(1000));
    assertEquals(2, changes.get());
    owner.closeSimple(tab.id, 1000);
  }

  private static com.deepseekharness.app.util.TerminalTabs.Tab<com.deepseekharness.app.PtySession>
      createUnstartedPty(TerminalSessionOwner owner, TerminalRuntimeProtection.Ticket ticket)
          throws Exception {
    var constructor = com.deepseekharness.app.PtySession.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    var session = constructor.newInstance();
    return owner.createPty(
        ticket,
        (verified, changed, beforeFork, allocated, guestCommand) -> {
          allocated.accept(session);
          beforeFork.run();
          return session;
        });
  }

  @Test
  public void unconfirmedSimpleGuestCloseRetainsHoldAndId() throws Exception {
    var protection = new TerminalRuntimeProtection(() -> 1L);
    TerminalSessionOwner owner = new TerminalSessionOwner(protection);
    CountDownLatch failed = new CountDownLatch(1);
    var ticket =
        protection.prepare(owner.epoch(), TerminalRuntimeProtection.Kind.SIMPLE, admission());
    protection.promoted(ticket.id(), new Object(), () -> true);
    var tab =
        owner.createSimple(
            ticket,
            new TerminalSession.Backend() {
              @Override
              public Process open() throws Exception {
                failed.countDown();
                throw new TerminalSession.UncertainStart(new IOException("handle lost"));
              }

              @Override
              public void terminate(Process process, long group) {}
            });
    assertTrue(failed.await(1, TimeUnit.SECONDS));
    assertThrows(IOException.class, () -> owner.closeSimple(tab.id, 1000));
    assertTrue(tab.isUnconfirmed());
    assertFalse(owner.isSimpleInputAllowed(tab.id));
    assertEquals(1, protection.snapshot().held);
    // 测试假后端无真实 guest；只在测试注入确切关闭证明，生产从不以 FAILED 释放。
    owner.closeSimple(tab.id, 1000, (value, wait) -> true);
    assertFalse(protection.hasDemand());
  }

  @Test
  public void asyncCloseOutlivesObserverDetachAndReturnsExactOwnerIdentity() throws Exception {
    TerminalSessionOwner owner = new TerminalSessionOwner();
    var tab = owner.addSimple(backend());
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<String> result = new AtomicReference<>();
    var observer = observer(new AtomicInteger());
    owner.attachSimple(observer);
    assertTrue(
        owner.closeSimpleAsync(
            tab.id,
            1000,
            (epoch, kind, id, error) -> {
              result.set(epoch + "/" + kind + "/" + id + "/" + error);
              done.countDown();
            }));
    owner.detachSimple(observer);
    assertTrue(done.await(2, TimeUnit.SECONDS));
    assertEquals(owner.epoch() + "/SIMPLE/" + tab.id + "/", result.get());
    assertTrue(owner.simpleTabs().snapshot().isEmpty());
  }

  @Test
  public void failedPendingPtyIsPublishedWithSameIdentityForExplicitClose() throws Exception {
    var protection = new TerminalRuntimeProtection(() -> 1L);
    TerminalSessionOwner owner = new TerminalSessionOwner(protection);
    var ticket = protection.prepare(owner.epoch(), TerminalRuntimeProtection.Kind.PTY, admission());
    protection.promoted(ticket.id(), new Object(), () -> true);
    var constructor = com.deepseekharness.app.PtySession.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    var pending = constructor.newInstance();
    var pendingField = com.deepseekharness.app.PtySession.class.getDeclaredField("pending");
    pendingField.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.Set<com.deepseekharness.app.PtySession> pendingSet =
        (java.util.Set<com.deepseekharness.app.PtySession>) pendingField.get(null);
    pendingSet.add(pending);
    try {
      assertThrows(
          IllegalStateException.class,
          () ->
              owner.createPty(
                  ticket,
                  (verified, changed, beforeFork, allocated, guestCommand) -> {
                    allocated.accept(pending);
                    beforeFork.run();
                    throw new IllegalStateException(
                        "password=private-value fork result unconfirmed");
                  }));
      var tab = owner.currentPty();
      assertNotNull(tab);
      org.junit.Assert.assertSame(pending, tab.value);
      assertTrue(tab.isUnconfirmed());
      assertFalse(tab.error().contains("private-value"));
      assertFalse(owner.isPtyInputAllowed(tab.id));
      assertEquals(1, protection.snapshot().held);
      // 无真实 guest 的夹具不能伪造出生身份；先撤销夹具pending，再验证显式owner关闭。
      pendingSet.remove(pending);
      owner.closePty(tab.id, 1);
      assertTrue(owner.ptyTabs().snapshot().isEmpty());
      assertFalse(protection.hasDemand());
    } finally {
      pendingSet.remove(pending);
    }
  }

  @Test
  public void definitelyUnstartedPtyFailureReleasesExactTicketWithoutTab() {
    var protection = new TerminalRuntimeProtection(() -> 1L);
    TerminalSessionOwner owner = new TerminalSessionOwner(protection);
    var ticket = protection.prepare(owner.epoch(), TerminalRuntimeProtection.Kind.PTY, admission());
    protection.promoted(ticket.id(), new Object(), () -> true);
    assertThrows(
        IllegalStateException.class,
        () ->
            owner.createPty(
                ticket,
                (verified, changed, beforeFork, allocated, guestCommand) -> {
                  throw new IllegalStateException("environment rejected before allocating PTY");
                }));
    assertTrue(owner.ptyTabs().snapshot().isEmpty());
    assertFalse(protection.hasDemand());
  }

  @Test
  public void fixedGuestCommandReachesPtyStartWithoutChangingProtectionTiming() throws Exception {
    var protection = new TerminalRuntimeProtection(() -> 1L);
    TerminalSessionOwner owner = new TerminalSessionOwner(protection);
    var ticket = protection.prepare(owner.epoch(), TerminalRuntimeProtection.Kind.PTY, admission());
    protection.promoted(ticket.id(), new Object(), () -> true);
    String[] command = {"/bin/bash", "-c", "exec /bin/bash -l"};
    var constructor = com.deepseekharness.app.PtySession.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    var session = constructor.newInstance();
    AtomicReference<String[]> seen = new AtomicReference<>();
    var tab =
        owner.createPty(
            ticket,
            command,
            (verified, changed, beforeFork, allocated, guestCommand) -> {
              seen.set(guestCommand);
              allocated.accept(session);
              // 保护票在进入 PtyStart 前已 retain；丢失会在这里失败，验证时序未变。
              beforeFork.run();
              return session;
            });
    org.junit.Assert.assertArrayEquals(command, seen.get());
    org.junit.Assert.assertSame(session, tab.value);
    assertEquals(1, protection.snapshot().held);
    owner.closePty(tab.id, 1000);
    assertFalse(protection.hasDemand());
  }

  private static TerminalRuntimeProtection.Admission admission() {
    return new TerminalRuntimeProtection.Admission() {
      @Override
      public void onReady(TerminalRuntimeProtection.Ticket ticket) {}

      @Override
      public void onRejected(TerminalRuntimeProtection.Ticket ticket, String reason) {}
    };
  }

  private static TerminalSessionOwner.SimpleObserver observer(AtomicInteger events) {
    return new TerminalSessionOwner.SimpleObserver() {
      @Override
      public void onOutput(TerminalSessionOwner.SimpleTab tab) {
        events.incrementAndGet();
      }

      @Override
      public void onState(TerminalSessionOwner.SimpleTab tab) {
        events.incrementAndGet();
      }

      @Override
      public void onTabsChanged() {
        events.incrementAndGet();
      }
    };
  }

  private static TerminalSession.Backend backend() {
    return new TerminalSession.Backend() {
      @Override
      public Process open() {
        throw new AssertionError("Do not start guest in owner policy test");
      }

      @Override
      public void terminate(Process process, long group) {}
    };
  }
}
