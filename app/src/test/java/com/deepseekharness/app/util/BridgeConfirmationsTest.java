package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BridgeConfirmationsTest {
  @Test
  public void oldNotificationCannotResolveNextRequestOrNewService() throws Exception {
    BridgeConfirmations gate = new BridgeConfirmations();
    var old = gate.begin(1);
    gate.finish(old);
    var next = gate.begin(1);
    assertNotEquals(old.identity, next.identity);
    assertFalse(gate.resolve(1, old.identity, true));
    assertTrue(gate.pending(next));
    BridgeConfirmations restarted = new BridgeConfirmations();
    var fresh = restarted.begin(1);
    assertFalse(restarted.resolve(1, next.identity, true));
    assertFalse(restarted.resolve(2, fresh.identity, true));
    assertTrue(restarted.resolve(1, fresh.identity, false));
    assertFalse(fresh.await(0, TimeUnit.SECONDS));
  }

  @Test
  public void timeoutStopAndOldCleanupDoNotGrantOrClearNewRequest() throws Exception {
    BridgeConfirmations gate = new BridgeConfirmations();
    var old = gate.begin(2);
    assertNull(gate.begin(2));
    assertFalse(old.await(1, TimeUnit.MILLISECONDS));
    gate.finish(old);
    assertFalse(gate.resolve(2, old.identity, true));
    var next = gate.begin(3);
    gate.finish(old);
    assertTrue(gate.pending(next));
    gate.finish(next);
    assertFalse(next.await(0, TimeUnit.SECONDS));
  }

  @Test
  public void simultaneousAllowDenyChannelsResolveExactlyOnce() throws Exception {
    BridgeConfirmations gate = new BridgeConfirmations();
    var request = gate.begin(7);
    AtomicInteger wins = new AtomicInteger();
    CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(20);
    for (int i = 0; i < 20; i++) {
      final boolean allow = i % 2 == 0;
      new Thread(
              () -> {
                try {
                  go.await();
                  if (gate.resolve(7, request.identity, allow)) wins.incrementAndGet();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              })
          .start();
    }
    go.countDown();
    assertTrue(done.await(3, TimeUnit.SECONDS));
    assertEquals(1, wins.get());
    boolean decision = request.await(0, TimeUnit.SECONDS);
    assertFalse(gate.resolve(7, request.identity, !decision));
    assertEquals(decision, request.await(0, TimeUnit.SECONDS));
  }

  @Test
  public void anExplicitDenialIsDistinguishableFromNobodyAnswering() throws Exception {
    // 桥必须能把"用户点了拒绝"与"用户没来得及回答"报成不同结果（issue #123：
    // /app/vscreen/create 等授权时挂起 60 秒，与真正被拒绝共用 USER_REJECTED）。
    BridgeConfirmations gate = new BridgeConfirmations();
    var denied = gate.begin(11);
    assertTrue(gate.resolve(11, denied.identity, false));
    assertTrue(denied.answered());
    assertFalse(denied.await(1, TimeUnit.MILLISECONDS));
    gate.finish(denied);

    var unanswered = gate.begin(12);
    assertFalse(unanswered.await(1, TimeUnit.MILLISECONDS));
    assertFalse(unanswered.answered());
    gate.finish(unanswered);

    var allowed = gate.begin(13);
    assertTrue(gate.resolve(13, allowed.identity, true));
    assertTrue(allowed.answered());
    assertTrue(allowed.await(1, TimeUnit.MILLISECONDS));
  }

  @Test
  public void cleanupIsNotAnAnswer() throws Exception {
    BridgeConfirmations gate = new BridgeConfirmations();
    var request = gate.begin(14);
    // 窗口关闭 / 代次失效 / 桥停止走的都是 finish，不得被当成用户的选择。
    gate.finish(request);
    assertFalse(request.answered());
    assertFalse(request.await(0, TimeUnit.SECONDS));
  }
}
