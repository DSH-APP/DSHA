package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.net.*;
import java.util.concurrent.*;
import org.junit.Test;

public final class LanPortPolicyTest {
  @Test
  public void currentPreferenceLeavesTheLegacyOnlyPortAvailable() {
    assertEquals(3081, LanPortPolicy.preferred("com.dsh.client"));
    assertEquals(3082, LanPortPolicy.preferred("io.github.sunsetrne.dsha"));
  }

  @Test
  public void occupiedPreferenceFallsBackToAHeldKernelPort() throws Exception {
    try (ServerSocket owner = new ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))) {
      int preferred = owner.getLocalPort();
      try (ServerSocket actual = LanPortPolicy.bind(preferred)) {
        assertNotEquals(preferred, actual.getLocalPort());
        assertTrue(actual.isBound());
        assertEquals("0.0.0.0", actual.getInetAddress().getHostAddress());
        assertThrows(
            BridgePortPolicy.Exhausted.class,
            () -> BridgePortPolicy.bind(new int[] {actual.getLocalPort()}));
        assertFalse(owner.isClosed());
      }
    }
  }

  @Test
  public void twoContendersBindSeparateListenersWithoutAProbeRace() throws Exception {
    // 临时端口释放后会被系统重新分配；同进程其他测试的 bind(0) 可能正好拿到它，
    // 于是两个竞争者都只能回退，CI run 41 attempt 1 就是这样偶发失败的。
    // 这里改用临时端口段以外的固定候选端口，避免被自动分配走；仍保留几次重试，
    // 覆盖并行测试/外部进程恰巧占用候选端口的情况。
    for (int attempt = 1; attempt <= 5; attempt++) {
      int preferred = freeFixedPort();
      ExecutorService workers = Executors.newFixedThreadPool(2);
      CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
      Callable<ServerSocket> bind =
          () -> {
            ready.countDown();
            go.await();
            return LanPortPolicy.bind(preferred);
          };
      Future<ServerSocket> first = workers.submit(bind), second = workers.submit(bind);
      ServerSocket a = null, b = null;
      try {
        assertTrue(ready.await(3, TimeUnit.SECONDS));
        go.countDown();
        a = first.get(3, TimeUnit.SECONDS);
        b = second.get(3, TimeUnit.SECONDS);
        if (a.getLocalPort() != preferred && b.getLocalPort() != preferred) continue;
        assertNotEquals(a.getLocalPort(), b.getLocalPort());
        assertTrue(a.getLocalPort() == preferred ^ b.getLocalPort() == preferred);
        return;
      } finally {
        closeQuietly(a);
        closeQuietly(b);
        workers.shutdownNow();
      }
    }
    fail("preferred port was taken by another process in every attempt");
  }

  /** 从固定候选段取一个当前空闲端口；监听套接字关闭后不会留下 TIME_WAIT。 */
  private static int freeFixedPort() throws Exception {
    for (int port = 20100; port <= 20199; port++) {
      try (ServerSocket candidate = new ServerSocket()) {
        candidate.setReuseAddress(true);
        candidate.bind(new InetSocketAddress("0.0.0.0", port));
        return port;
      } catch (BindException busy) {
        // 候选端口被占用，继续试下一个。
      }
    }
    throw new IllegalStateException("NO_FIXED_TEST_PORT");
  }

  private static void closeQuietly(ServerSocket socket) {
    if (socket == null) return;
    try {
      socket.close();
    } catch (Exception ignored) {
      // 测试清理失败不应掩盖断言结果。
    }
  }
}
