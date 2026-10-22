package com.deepseekharness.app.core;

import static org.junit.Assert.*;
import com.deepseekharness.app.runtime.StoredWebSession;
import com.deepseekharness.app.runtime.WebProcessManager.SessionObservation;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

/** 生产复核编排、运行门禁、任务状态和实际loopback HTTP；不运行用户命令。 */
public final class WebBackgroundRecoveryTest {
  private static final String INSTANCE = "22345678-1234-1234-1234-123456789abc";
  private static final String NONCE = "ab".repeat(32), TOKEN = "t".repeat(43);
  private static final String COOKIE =
      "dsh-auth-" + "a".repeat(43) + "=v1.payload." + "b".repeat(43);
  private static final String CLIENT = "c".repeat(32);

  private static final class Fixture implements WebSessionRecheck.Ports, AutoCloseable {
    final HarnessSessionState state = new HarnessSessionState();
    final ServerSocket server;
    final Thread serverWorker;
    final Object serverLifecycle = new Object();
    final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    volatile Socket activeSocket;
    final AtomicInteger requests = new AtomicInteger(), proofs = new AtomicInteger();
    final WebLifecycleController.WebRun run;
    final StoredWebSession.Record record;
    final PreviewPageSession.Identity identity;
    final AgentTaskState tasks = new AgentTaskState();
    SessionObservation observation = SessionObservation.LIVE, second = SessionObservation.LIVE;
    volatile boolean rejectAuth;
    volatile Runnable duringAuth = () -> {};

    Fixture() throws Exception {
      server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
      serverWorker = new Thread(this::serve, "background-recheck-fixture-backend");
      serverWorker.setDaemon(true);
      serverWorker.start();
      String url = "http://127.0.0.1:" + server.getLocalPort() + "/?token=" + TOKEN;
      assertTrue(state.lifecycle.adoptRunning(7));
      run = new WebLifecycleController.WebRun(7, INSTANCE);
      var proof =
          new WebSessionProof.Saved(
              "12345678-1234-1234-1234-123456789abc",
              7,
              INSTANCE,
              NONCE,
              101,
              200,
              "node web",
              100,
              199,
              "libproot.so");
      record = new StoredWebSession.Record(proof, url, "app", "root", "runtime", false, NONCE);
      run.recovered = record;
      run.recoveredLive = true;
      run.authUrl = url;
      run.port = server.getLocalPort();
      run.registration = AgentTaskRegistration.restore(7, INSTANCE, NONCE);
      state.currentRun = run;
      state.runs.put(7L, run);
      identity = WebLifecycleController.readyWebPageIdentity(state.lifecycle, run, false);
    }

    private void serve() {
      while (!server.isClosed()) {
        try (Socket socket = server.accept()) {
          synchronized (serverLifecycle) {
            if (server.isClosed()) return;
            activeSocket = socket;
          }
          socket.setSoTimeout(3000);
          var input =
              new BufferedReader(
                  new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
          String request = input.readLine();
          boolean launch = ("GET /?token=" + TOKEN + " HTTP/1.1").equals(request);
          assertTrue(launch || "GET / HTTP/1.1".equals(request));
          String cookie = null, host = null;
          for (String line; (line = input.readLine()) != null && !line.isEmpty(); ) {
            int colon = line.indexOf(':');
            assertTrue(colon > 0);
            String name = line.substring(0, colon), value = line.substring(colon + 1).trim();
            if ("Cookie".equalsIgnoreCase(name)) cookie = value;
            if ("Host".equalsIgnoreCase(name)) host = value;
          }
          assertEquals("127.0.0.1:" + server.getLocalPort(), host);
          requests.incrementAndGet();
          duringAuth.run();
          String reply;
          if (rejectAuth) reply = "HTTP/1.1 401 Unauthorized\r\n";
          else if (launch)
            reply =
                "HTTP/1.1 303 See Other\r\nLocation: ./\r\nSet-Cookie: "
                    + COOKIE
                    + "; HttpOnly\r\n";
          else {
            assertEquals(COOKIE, cookie);
            reply = "HTTP/1.1 200 OK\r\n";
          }
          socket
              .getOutputStream()
              .write(
                  (reply + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                      .getBytes(StandardCharsets.US_ASCII));
        } catch (Throwable error) {
          if (!(error instanceof IOException && server.isClosed()))
            serverFailure.compareAndSet(null, error);
          return;
        } finally {
          synchronized (serverLifecycle) {
            activeSocket = null;
          }
        }
      }
    }

    public boolean current() {
      return WebLifecycleController.currentRecheck(state, run, record);
    }

    public boolean alreadyLive() {
      return run.recoveredLive && !state.restoreUnknown;
    }

    public SessionObservation observe() {
      return proofs.incrementAndGet() % 2 == 0 ? second : observation;
    }

    public DshAuthSession.Result authenticate() {
      assertFalse(Thread.holdsLock(state.lifecycle));
      return DshAuthSession.exchange(record.authUrl(), run.port, this::current, 1500);
    }

    WebSessionRecheck.Outcome check() {
      proofs.set(0);
      return WebSessionRecheck.check(this);
    }

    boolean apply(WebSessionRecheck.Outcome outcome) {
      return WebLifecycleController.applyRecheckedWebSession(state, run, record, outcome);
    }

    void revoke() {
      assertTrue(
          WebLifecycleController.finishAuthorityRevocation(
              state,
              run,
              record,
              () -> {
                assertFalse(Thread.holdsLock(state.lifecycle));
                tasks.invalidate(7, INSTANCE);
              }));
    }

    void unknown() {
      observation = SessionObservation.UNKNOWN;
      assertEquals(WebSessionRecheck.Result.UNKNOWN, check().result());
      assertTrue(apply(check()));
    }

    public void close() throws Exception {
      try {
        synchronized (serverLifecycle) {
          server.close();
          if (activeSocket != null) activeSocket.close();
        }
        serverWorker.join(3000);
        assertFalse(serverWorker.isAlive());
        if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
      } finally {
        state.io.shutdownNow();
      }
    }
  }

  @Test
  public void
      repeatedBackgroundUnknownStrictRestoreKeepsIdentityButNeverRevivesOldCapabilityOrStop()
          throws Exception {
    try (var f = new Fixture()) {
      var page = new PreviewPageSession();
      assertTrue(page.claim(f.identity));
      for (int cycle = 0; cycle < 3; cycle++) {
        var oldRegistration = f.run.registration;
        var old = oldRegistration.ready(f.identity);
        assertTrue(old.register(f.tasks, CLIENT, 7, INSTANCE, NONCE, f.run.port, cycle * 10));
        var key = new AgentTaskState.Key(7, INSTANCE, "session", cycle + 1, "d".repeat(32));
        assertTrue(f.tasks.event(CLIENT, key, cycle + 1, "running", "", cycle * 10 + 1));
        assertTrue(
            f.tasks.requestStop(
                key, f.tasks.snapshot(cycle * 10 + 1).get(cycle).action(), cycle * 10 + 2));
        f.unknown();
        assertNull(WebLifecycleController.readyWebPageIdentity(f.state.lifecycle, f.run, false));
        assertFalse(WebLifecycleController.runningWithProof(f.state, () -> true));
        assertSame(f.identity, page.identity());
        assertEquals("denied", old.whileActive(() -> "unsafe", "denied"));
        f.revoke();
        f.observation = f.second = SessionObservation.LIVE;
        var restored = f.check();
        assertEquals(WebSessionRecheck.Result.RESTORED, restored.result());
        assertEquals(2, f.proofs.get());
        assertTrue(f.apply(restored));
        assertFalse(f.state.restoreUnknown);
        assertNotSame(oldRegistration, f.run.registration);
        assertEquals(
            f.identity,
            WebLifecycleController.readyWebPageIdentity(f.state.lifecycle, f.run, false));
        assertFalse(old.register(f.tasks, CLIENT, 7, INSTANCE, NONCE, f.run.port, 50));
        assertTrue(
            f.run
                .registration
                .ready(f.identity)
                .register(f.tasks, CLIENT, 7, INSTANCE, NONCE, f.run.port, 51));
        assertNull(f.tasks.claim(CLIENT, 7, INSTANCE));
        assertFalse(f.tasks.event(CLIENT, key, 100, "running", "", 52));
      }
      assertEquals(6, f.requests.get());
      assertEquals(WebSessionRecheck.Result.LIVE, f.check().result());
      assertEquals(6, f.requests.get());
    }
  }

  @Test
  public void authFailureSecondProofFailureAndStopDuringHttpNeverRestore() throws Exception {
    for (int failure = 0; failure < 3; failure++)
      try (var f = new Fixture()) {
        f.unknown();
        f.revoke();
        f.observation = SessionObservation.LIVE;
        if (failure == 0) f.rejectAuth = true;
        if (failure == 1) f.second = SessionObservation.UNKNOWN;
        if (failure == 2) f.duringAuth = () -> f.state.lifecycle.beginStop();
        var result = f.check();
        assertEquals(
            failure == 2 ? WebSessionRecheck.Result.STALE : WebSessionRecheck.Result.UNKNOWN,
            result.result());
        assertFalse(f.apply(result));
        assertFalse(f.run.recoveredLive);
        assertTrue(f.state.restoreUnknown);
        assertNull(f.run.registration.ready(f.identity));
      }
  }

  @Test
  public void confirmedGuestExitIsDistinctFromUnknownAndDoesNotAuthenticateOrRetireLauncherProof()
      throws Exception {
    try (var f = new Fixture()) {
      f.state.retainedWebStopProof = f.record.proof();
      f.observation = SessionObservation.EXITED;
      var result = f.check();
      assertEquals(WebSessionRecheck.Result.EXITED, result.result());
      assertTrue(f.apply(result));
      f.revoke();
      assertTrue(f.run.confirmedWebExit);
      assertFalse(f.run.recoveredLive);
      assertEquals(0, f.requests.get());
      assertSame(f.record.proof(), f.state.retainedWebStopProof);
      assertFalse(WebLifecycleController.runningWithProof(f.state, () -> true));
    }
  }

  @Test
  public void unknownDuringRawPidProbeCannotPassTheFinalAuthorizationGate() throws Exception {
    try (var f = new Fixture()) {
      assertFalse(
          WebLifecycleController.runningWithProof(
              f.state,
              () -> {
                f.unknown();
                return true;
              }));
      f.revoke();
      assertFalse(
          WebLifecycleController.runningWithProof(
              f.state,
              () -> {
                fail();
                return true;
              }));
    }
  }

  @Test
  public void pendingRevocationBlocksStrictPublicationAndRunsOutsideLifecycleBeforeTheNextStart()
      throws Exception {
    try (var f = new Fixture()) {
      f.unknown();
      f.observation = SessionObservation.LIVE;
      var verified = f.check();
      assertEquals(WebSessionRecheck.Result.RESTORED, verified.result());
      assertFalse(f.apply(verified));
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var order = new CopyOnWriteArrayList<String>();
      var revocation =
          f.state.io.submit(
              () ->
                  WebLifecycleController.finishAuthorityRevocation(
                      f.state,
                      f.run,
                      f.record,
                      () -> {
                        assertFalse(Thread.holdsLock(f.state.lifecycle));
                        order.add("revoke-start");
                        entered.countDown();
                        try {
                          assertTrue(release.await(2, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                          throw new AssertionError(e);
                        }
                        order.add("revoke-end");
                      }));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      long stopped = f.state.lifecycle.beginStop();
      f.state.lifecycle.finishStop(stopped);
      long next = f.state.lifecycle.beginStart(false, false);
      var newPublication =
          f.state.io.submit(
              () -> {
                order.add("new-publication");
                return next;
              });
      assertFalse(newPublication.isDone());
      release.countDown();
      assertFalse(revocation.get(2, TimeUnit.SECONDS));
      assertEquals(next, (long) newPublication.get(2, TimeUnit.SECONDS));
      assertEquals(List.of("revoke-start", "revoke-end", "new-publication"), order);
      assertTrue(f.run.authorityRevocationPending);
      assertFalse(f.apply(verified));
      AtomicInteger late = new AtomicInteger();
      assertFalse(
          WebLifecycleController.finishAuthorityRevocation(
              f.state, f.run, f.record, late::incrementAndGet));
      assertEquals(0, late.get());
    }
  }

  @Test
  public void actualHttpBridgeMonitorCanReadGenerationWhileRevocationWaitsForIt() throws Exception {
    try (var f = new Fixture()) {
      f.unknown();
      var field = com.deepseekharness.app.HttpShellService.class.getDeclaredField("LIFECYCLE");
      field.setAccessible(true);
      Object bridgeMonitor = field.get(null);
      var bridgeHeld = new CountDownLatch(1);
      var revokeEntered = new CountDownLatch(1);
      var failure = new AtomicReference<Throwable>();
      Thread bridge =
          new Thread(
              () -> {
                try {
                  synchronized (bridgeMonitor) {
                    bridgeHeld.countDown();
                    assertTrue(revokeEntered.await(2, TimeUnit.SECONDS));
                    assertEquals(7, f.state.lifecycle.generation());
                  }
                } catch (Throwable error) {
                  failure.set(error);
                }
              });
      bridge.start();
      assertTrue(bridgeHeld.await(1, TimeUnit.SECONDS));
      var revoke =
          f.state.io.submit(
              () ->
                  WebLifecycleController.finishAuthorityRevocation(
                      f.state,
                      f.run,
                      f.record,
                      () -> {
                        assertFalse(Thread.holdsLock(f.state.lifecycle));
                        revokeEntered.countDown();
                        synchronized (bridgeMonitor) {
                          /* 真实screen确认清理所用的同一monitor。 */
                        }
                      }));
      assertTrue(revoke.get(2, TimeUnit.SECONDS));
      bridge.join(2000);
      assertFalse(bridge.isAlive());
      assertNull(failure.get());
    }
  }
}
