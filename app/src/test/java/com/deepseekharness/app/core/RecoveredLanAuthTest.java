package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.runtime.StoredWebSession;
import com.deepseekharness.app.LanProxyService;
import com.deepseekharness.app.util.DshAuthSession;
import com.deepseekharness.app.util.LanTokenState;
import com.deepseekharness.app.util.SocketDispatch;
import com.deepseekharness.app.util.WebSessionProof;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.Test;

/** Actual recovery/auth bridge on random loopback sockets; no production3081 listener. */
public final class RecoveredLanAuthTest {
  private static final String TOKEN = "t".repeat(43);
  private static final String COOKIE =
      "dsh-auth-" + "a".repeat(43) + "=v1.payload." + "b".repeat(43);

  private static final class Backend implements AutoCloseable {
    final ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    final AtomicInteger requests = new AtomicInteger();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread worker;

    Backend() throws IOException {
      worker =
          new Thread(
              () -> {
                while (!listener.isClosed()) {
                  try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    var input =
                        new BufferedReader(
                            new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII));
                    StringBuilder request = new StringBuilder();
                    for (String line; (line = input.readLine()) != null && !line.isEmpty(); )
                      request.append(line).append('\n');
                    requests.incrementAndGet();
                    assertTrue(
                        request.toString().contains("Host: 127.0.0.1:" + listener.getLocalPort()));
                    boolean launch = request.toString().startsWith("GET /?token=" + TOKEN + " ");
                    if (!launch) assertTrue(request.toString().contains("Cookie: " + COOKIE));
                    String reply =
                        launch
                            ? "HTTP/1.1 303 See Other\r\nLocation: ./\r\nSet-Cookie: "
                                + COOKIE
                                + "; HttpOnly\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            : "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                  } catch (Throwable error) {
                    if (!listener.isClosed()) failure.compareAndSet(null, error);
                    return;
                  }
                }
              },
              "restored-lan-fixture-backend");
      worker.setDaemon(true);
      worker.start();
    }

    String url() {
      return "http://127.0.0.1:" + listener.getLocalPort() + "/?token=" + TOKEN;
    }

    public void close() throws Exception {
      listener.close();
      worker.join(3000);
      assertFalse(worker.isAlive());
      if (failure.get() != null) throw new AssertionError(failure.get());
    }
  }

  private static final class Fixture
      implements WebSessionRecovery.Ports, LanAuthBridge.Ports, AutoCloseable {
    final HarnessSessionState state = new HarnessSessionState();
    final Backend backend;
    final AtomicBoolean enabled = new AtomicBoolean(true);
    final AtomicInteger exchanges = new AtomicInteger(),
        published = new AtomicInteger(),
        bound = new AtomicInteger(),
        warnings = new AtomicInteger();
    final StoredWebSession.Record record;
    final LanAuthBridge bridge;
    WebLifecycleController.RecoveredAuth verified;
    boolean valid = true, rejectSecondProof, failBinding, stopDuringAdopt;
    int proofs;
    Runnable afterCapture = () -> {};
    Runnable beforePublish = () -> {};
    Runnable beforeBind = () -> {};

    Fixture(Backend backend) {
      this.backend = backend;
      var proof =
          new WebSessionProof.Saved(
              "12345678-1234-1234-1234-123456789abc",
              7,
              "22345678-1234-1234-1234-123456789abc",
              "ab".repeat(32),
              101,
              1234,
              "web",
              102,
              1235,
              "launcher");
      record = new StoredWebSession.Record(proof, backend.url(), "", "", "", false, "");
      bridge =
          new LanAuthBridge(
              this,
              (url, port, current) -> {
                exchanges.incrementAndGet();
                throw new AssertionError(
                    "restored LAN must reuse the verified cookie without another exchange");
              });
    }

    public StoredWebSession.Record read() {
      return record;
    }

    public boolean current() {
      return state.lifecycle.generation() == 0 && !state.lifecycle.isUserStopped();
    }

    public boolean compatible(StoredWebSession.Record saved) {
      return true;
    }

    public boolean verify(StoredWebSession.Record saved) {
      return valid && (!rejectSecondProof || ++proofs != 2);
    }

    public boolean authenticate(StoredWebSession.Record saved) {
      assertFalse(Thread.holdsLock(state.lifecycle));
      var result =
          DshAuthSession.exchange(
              saved.authUrl(), backend.listener.getLocalPort(), this::current, 2500);
      if (result.ready()) verified = new WebLifecycleController.RecoveredAuth(saved, result);
      return result.ready();
    }

    public boolean adopt(StoredWebSession.Record saved) {
      synchronized (state.lifecycle) {
        if (stopDuringAdopt) state.lifecycle.beginStop();
        if (!state.lifecycle.adoptRunning(saved.proof().generation())) return false;
        var run = new WebLifecycleController.WebRun(saved.proof().generation());
        run.authUrl = saved.authUrl();
        run.port = backend.listener.getLocalPort();
        run.recovered = saved;
        run.recoveredLive = true;
        state.currentRun = run;
        state.runs.put(run.generation, run);
        return true;
      }
    }

    public LanAuthBridge.Snapshot capture(long generation) {
      LanAuthBridge.Snapshot snapshot;
      synchronized (state.lifecycle) {
        var run = state.runs.get(generation);
        snapshot =
            state.lifecycle.isCurrent(generation) && run != null && !run.authUrl.isEmpty()
                ? new LanAuthBridge.Snapshot(generation, run.authUrl, run.port)
                : null;
        if (snapshot == null || !LanAuthBridge.current(state, snapshot)) return null;
      }
      afterCapture.run();
      return snapshot;
    }

    public boolean current(LanAuthBridge.Snapshot snapshot) {
      return LanAuthBridge.current(state, snapshot);
    }

    public boolean publish(LanAuthBridge.Snapshot snapshot, LanAuthBridge.Exchange result) {
      beforePublish.run();
      if (!current(snapshot)) return false;
      published.incrementAndGet();
      return true;
    }

    public void bind(LanAuthBridge.Snapshot snapshot, String cookie) {
      beforeBind.run();
      if (!current(snapshot)) return;
      assertEquals(COOKIE, cookie);
      if (failBinding) throw new IllegalStateException("FIXTURE_BIND_FAILED");
      if (enabled.get()) bound.incrementAndGet();
    }

    public void warning(RuntimeException error) {
      warnings.incrementAndGet();
    }

    WebSessionRecovery.Result recover() {
      return WebSessionRecovery.recover(this);
    }

    String publish(WebSessionRecovery.Result result) {
      return WebLifecycleController.publishRecoveredLan(result, state, verified, bridge);
    }

    public void close() {
      state.io.shutdownNow();
    }
  }

  /** Real proxy auth/worker ownership, with a random listener instead of LAN3081. */
  private static final class ProxyFixture implements AutoCloseable {
    final ServerSocket listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
    final Socket client = new Socket("127.0.0.1", listener.getLocalPort());
    final Socket companionPeer;
    final Object run, snapshot;
    final java.lang.reflect.Method currentSnapshot, writeRequest;
    final String token;

    ProxyFixture(long generation) throws Exception {
      LanProxyService.clearDshAuth(0);
      assertTrue(LanProxyService.setDshAuthCookie(COOKIE, generation));
      token = LanProxyService.getLanToken(null);
      Class<?> runType = Class.forName(LanProxyService.class.getName() + "$ProxyRun");
      var constructor = runType.getDeclaredConstructor(long.class, long.class, int.class);
      constructor.setAccessible(true);
      run = constructor.newInstance(1L, generation, listener.getLocalPort());
      var server = runType.getDeclaredField("server");
      server.setAccessible(true);
      server.set(run, listener);
      var bound = runType.getDeclaredField("bound");
      bound.setAccessible(true);
      bound.setBoolean(run, true);
      var active = LanProxyService.class.getDeclaredField("activeRun");
      active.setAccessible(true);
      active.set(null, run);
      var dispatchField = runType.getDeclaredField("dispatch");
      dispatchField.setAccessible(true);
      SocketDispatch dispatch = (SocketDispatch) dispatchField.get(run);
      var ticket = dispatch.accept(listener.accept());
      companionPeer = new Socket("127.0.0.1", listener.getLocalPort());
      ticket.attach(listener.accept());
      client.setSoTimeout(2000);
      companionPeer.setSoTimeout(2000);
      var capture = LanProxyService.class.getDeclaredMethod("snapshotDshAuth", runType);
      capture.setAccessible(true);
      snapshot = capture.invoke(null, run);
      assertNotNull(snapshot);
      currentSnapshot =
          LanProxyService.class.getDeclaredMethod("isCurrentDshAuth", snapshot.getClass());
      currentSnapshot.setAccessible(true);
      writeRequest =
          LanProxyService.class.getDeclaredMethod(
              "writeCurrentRequest",
              runType,
              snapshot.getClass(),
              String.class,
              OutputStream.class);
      writeRequest.setAccessible(true);
    }

    boolean current() throws Exception {
      return (boolean) currentSnapshot.invoke(null, snapshot);
    }

    String write(ByteArrayOutputStream output) throws Exception {
      return (String)
          writeRequest.invoke(
              null, run, snapshot, "GET / HTTP/1.1\r\nHost: fixture.invalid\r\n\r\n", output);
    }

    String currentToken() throws Exception {
      var field = LanProxyService.class.getDeclaredField("LAN_TOKEN");
      field.setAccessible(true);
      return ((LanTokenState) field.get(null)).current();
    }

    public void close() throws Exception {
      LanProxyService.clearDshAuth(0);
      client.close();
      companionPeer.close();
      listener.close();
    }
  }

  @Test
  public void strictAdoptionAloneLeavesLanUnpublishedUntilVerifiedCookieIsPublished()
      throws Exception {
    try (Backend backend = new Backend();
        Fixture fixture = new Fixture(backend)) {
      var restored = fixture.recover();
      assertEquals(WebSessionRecovery.Result.RESTORED, restored);
      // Negative control: the unchanged old strict-recovery function adopts but
      // discards its cookie. HTTP success by itself never publishes LAN auth.
      assertEquals(2, backend.requests.get());
      assertEquals(0, fixture.published.get());
      assertEquals(0, fixture.bound.get());
      assertEquals(COOKIE, fixture.publish(restored));
      assertEquals("publish must not repeat HTTP authentication", 2, backend.requests.get());
      assertEquals(0, fixture.exchanges.get());
      assertEquals(1, fixture.published.get());
      assertEquals(1, fixture.bound.get());
    }
  }

  @Test
  public void unknownNoneAndStaleRecoveryNeverPublishVerifiedCookies() throws Exception {
    try (Backend backend = new Backend();
        Fixture fixture = new Fixture(backend)) {
      assertEquals(WebSessionRecovery.Result.RESTORED, fixture.recover());
      for (var result :
          new WebSessionRecovery.Result[] {
            WebSessionRecovery.Result.UNKNOWN,
            WebSessionRecovery.Result.NONE,
            WebSessionRecovery.Result.STALE
          }) assertNull(fixture.publish(result));
      assertEquals(0, fixture.published.get());
      assertEquals(0, fixture.bound.get());
      assertEquals(2, backend.requests.get());
    }
  }

  @Test
  public void secondKernelProofAndAdoptionMustSucceedAfterHttpBeforePublication() throws Exception {
    for (boolean failedProof : new boolean[] {false, true}) {
      try (Backend backend = new Backend();
          Fixture fixture = new Fixture(backend)) {
        fixture.rejectSecondProof = failedProof;
        fixture.stopDuringAdopt = !failedProof;
        var result = fixture.recover();
        assertEquals(
            failedProof ? WebSessionRecovery.Result.UNKNOWN : WebSessionRecovery.Result.STALE,
            result);
        assertNotNull("the HTTP result exists but must not be published", fixture.verified);
        assertNull(fixture.publish(result));
        assertEquals(0, fixture.published.get());
        assertEquals(0, fixture.bound.get());
        assertEquals(2, backend.requests.get());
      }
    }
  }

  @Test
  public void stopOrChangedGenerationBeforePublishRejectsOldCookie() throws Exception {
    try (Backend backend = new Backend();
        Fixture fixture = new Fixture(backend)) {
      var restored = fixture.recover();
      fixture.state.lifecycle.beginStop();
      assertNull(fixture.publish(restored));
      fixture.state.lifecycle.finishStop(fixture.state.lifecycle.generation());
      fixture.state.lifecycle.beginStart(false, true);
      assertNull(fixture.publish(restored));
      assertEquals(0, fixture.published.get());
      assertEquals(0, fixture.bound.get());
    }
  }

  @Test
  public void manuallyDisabledLanDoesNotListenOrRepeatExchange() throws Exception {
    try (Backend backend = new Backend();
        Fixture fixture = new Fixture(backend)) {
      var restored = fixture.recover();
      fixture.enabled.set(false);
      assertEquals(COOKIE, fixture.publish(restored));
      assertEquals(1, fixture.published.get());
      assertEquals(0, fixture.bound.get());
      assertEquals(0, fixture.exchanges.get());
      assertEquals(2, backend.requests.get());
    }
  }

  @Test
  public void verifiedPublisherRejectsWrongGenerationUrlAndPortWithoutNetwork() throws Exception {
    try (Backend backend = new Backend();
        Fixture fixture = new Fixture(backend)) {
      assertNull(
          fixture.bridge.publishVerified(7, backend.url(), backend.listener.getLocalPort(), null));
      fixture.recover();
      var verified = fixture.verified.authentication();
      assertNull(
          fixture.bridge.publishVerified(
              8, backend.url(), backend.listener.getLocalPort(), verified));
      assertNull(
          fixture.bridge.publishVerified(
              7,
              backend.url().replace(TOKEN, "s".repeat(43)),
              backend.listener.getLocalPort(),
              verified));
      assertNull(
          fixture.bridge.publishVerified(
              7, backend.url(), backend.listener.getLocalPort() + 1, verified));
      assertEquals(0, fixture.published.get());
      assertEquals(0, fixture.bound.get());
      assertEquals(0, fixture.exchanges.get());
      assertEquals(2, backend.requests.get());
    }
  }

  @Test
  public void changedPortUrlRecordOrLiveProofRejectsPublication() throws Exception {
    for (String changed : new String[] {"port", "url", "record", "live", "unknown"}) {
      try (Backend backend = new Backend();
          Fixture fixture = new Fixture(backend)) {
        var restored = fixture.recover();
        var run = fixture.state.currentRun;
        switch (changed) {
          case "port" -> run.port++;
          case "url" -> run.authUrl = run.authUrl.replace(TOKEN, "s".repeat(43));
          case "record" -> run.recovered = null;
          case "live" -> run.recoveredLive = false;
          case "unknown" -> fixture.state.restoreUnknown = true;
        }
        assertNull(changed, fixture.publish(restored));
        assertEquals(changed, 0, fixture.published.get());
        assertEquals(0, fixture.bound.get());
      }
    }
  }

  @Test
  public void bridgeRejectsStopOrAddressChangeBetweenCaptureAndPublication() throws Exception {
    for (boolean stop : new boolean[] {false, true}) {
      try (Backend backend = new Backend();
          Fixture fixture = new Fixture(backend)) {
        var restored = fixture.recover();
        fixture.beforePublish =
            () -> {
              if (stop) fixture.state.lifecycle.beginStop();
              else fixture.state.currentRun.port++;
            };
        assertNull(fixture.publish(restored));
        assertEquals(0, fixture.published.get());
        assertEquals(0, fixture.bound.get());
      }
    }
  }

  private static void rejectUnknownAt(String phase) throws Exception {
    try (Backend backend = new Backend();
        Fixture fixture = new Fixture(backend)) {
      var restored = fixture.recover();
      var run = fixture.state.currentRun;
      var snapshot = new LanAuthBridge.Snapshot(run.generation, run.authUrl, run.port);
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      Runnable pause =
          () -> {
            assertFalse(
                "the network boundary must not hold the lifecycle monitor",
                Thread.holdsLock(fixture.state.lifecycle));
            entered.countDown();
            try {
              assertTrue(
                  "publication boundary was not released", release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new AssertionError(error);
            }
          };
      switch (phase) {
        case "capture" -> fixture.afterCapture = pause;
        case "publish" -> fixture.beforePublish = pause;
        case "bind" -> fixture.beforeBind = pause;
        default -> throw new AssertionError(phase);
      }
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread publisher =
          new Thread(
              () -> {
                try {
                  fixture.publish(restored);
                } catch (Throwable error) {
                  failure.set(error);
                }
              },
              "recovered-lan-publish-boundary");
      publisher.start();
      try {
        assertTrue("publisher did not reach " + phase, entered.await(5, TimeUnit.SECONDS));
        assertTrue(WebLifecycleController.applyRecoveredWebProof(fixture.state, run, false));
        assertEquals(snapshot.generation(), fixture.state.lifecycle.generation());
        assertEquals(snapshot.authUrl(), run.authUrl);
        assertEquals(snapshot.port(), run.port);
      } finally {
        release.countDown();
        publisher.join(5000);
      }
      assertFalse("publisher did not finish", publisher.isAlive());
      if (failure.get() != null) throw new AssertionError(failure.get());
      assertEquals(
          "UNKNOWN may not publish after " + phase,
          phase.equals("bind") ? 1 : 0,
          fixture.published.get());
      assertEquals("UNKNOWN may not bind after " + phase, 0, fixture.bound.get());
      assertEquals(0, fixture.exchanges.get());
      assertEquals(2, backend.requests.get());
    }
  }

  @Test
  public void sameGenerationUnknownAfterCaptureCannotPublish() throws Exception {
    rejectUnknownAt("capture");
  }

  @Test
  public void sameGenerationUnknownBeforePublicationCannotPublish() throws Exception {
    rejectUnknownAt("publish");
  }

  @Test
  public void sameGenerationUnknownBeforeBindCannotBind() throws Exception {
    rejectUnknownAt("bind");
  }

  @Test
  public void productionGateAllowsFreshRunsAndRequiresCurrentRecoveredProof() throws Exception {
    try (Backend backend = new Backend();
        Fixture fixture = new Fixture(backend)) {
      assertTrue(fixture.state.lifecycle.beginStart(false, true) > 0);
      var run = new WebLifecycleController.WebRun(fixture.state.lifecycle.generation());
      run.authUrl = backend.url();
      run.port = backend.listener.getLocalPort();
      fixture.state.currentRun = run;
      fixture.state.runs.put(run.generation, run);
      var snapshot = new LanAuthBridge.Snapshot(run.generation, run.authUrl, run.port);
      assertTrue(
          "fresh runs have no recovered record", LanAuthBridge.current(fixture.state, snapshot));
      run.recovered = fixture.record;
      assertFalse(LanAuthBridge.current(fixture.state, snapshot));
      run.recoveredLive = true;
      assertTrue(LanAuthBridge.current(fixture.state, snapshot));
      fixture.state.restoreUnknown = true;
      assertFalse(LanAuthBridge.current(fixture.state, snapshot));
      fixture.state.restoreUnknown = false;
      fixture.state.currentRun = new WebLifecycleController.WebRun(run.generation);
      assertFalse(
          "a matching triple does not substitute the current run",
          LanAuthBridge.current(fixture.state, snapshot));
    }
  }

  @Test
  public void recoveredUnknownRetiresActualAuthListenerAndOwnedSockets() throws Exception {
    try (Backend backend = new Backend();
        Fixture fixture = new Fixture(backend)) {
      assertEquals(WebSessionRecovery.Result.RESTORED, fixture.recover());
      var run = fixture.state.currentRun;
      try (ProxyFixture proxy = new ProxyFixture(run.generation)) {
        assertTrue(LanProxyService.hasDshAuth(run.generation));
        assertTrue(proxy.current());
        assertTrue(LanProxyService.isBound());
        assertEquals(proxy.token, proxy.currentToken());
        var before = new ByteArrayOutputStream();
        assertTrue(proxy.write(before).contains("Cookie: " + COOKIE));
        assertTrue(before.size() > 0);
        assertTrue(WebLifecycleController.applyRecoveredWebProof(fixture.state, run, false));
        assertTrue(fixture.state.restoreUnknown);
        assertFalse(run.recoveredLive);
        assertFalse(LanProxyService.hasDshAuth(run.generation));
        assertFalse("the real worker AuthState snapshot must become stale", proxy.current());
        assertEquals("", proxy.currentToken());
        assertTrue(proxy.listener.isClosed());
        assertEquals(-1, proxy.client.getInputStream().read());
        assertEquals(-1, proxy.companionPeer.getInputStream().read());
        assertFalse(LanProxyService.isBound());
        var after = new ByteArrayOutputStream();
        assertNull(proxy.write(after));
        assertEquals(0, after.size());
        // HarnessService's watchdog requires hasDshAuth; the actual start also
        // rejects this generation before creating a fixed-port listener.
        LanProxyService.start(null, null, run.port, run.generation);
        assertFalse(LanProxyService.isBound());
      }
    }
  }

  @Test
  public void delayedOldUnknownCannotRevokeTheNewGenerationCookie() throws Exception {
    try (Backend backend = new Backend();
        Fixture fixture = new Fixture(backend)) {
      assertEquals(WebSessionRecovery.Result.RESTORED, fixture.recover());
      var oldRun = fixture.state.currentRun;
      try (ProxyFixture proxy = new ProxyFixture(oldRun.generation)) {
        fixture.state.lifecycle.beginStop();
        fixture.state.lifecycle.finishStop(fixture.state.lifecycle.generation());
        assertTrue(fixture.state.lifecycle.beginStart(false, true) > 0);
        var newRun = new WebLifecycleController.WebRun(fixture.state.lifecycle.generation());
        fixture.state.currentRun = newRun;
        fixture.state.runs.put(newRun.generation, newRun);
        assertTrue(LanProxyService.setDshAuthCookie(COOKIE, newRun.generation));
        assertFalse(WebLifecycleController.applyRecoveredWebProof(fixture.state, oldRun, false));
        assertTrue(LanProxyService.hasDshAuth(newRun.generation));
        assertFalse(fixture.state.restoreUnknown);
        assertTrue("the late old probe must not change its old run", oldRun.recoveredLive);
        assertFalse(proxy.current());
      }
    }
  }

  @Test
  public void bindingFailureDoesNotUndoTheAdoptedLocalRun() throws Exception {
    try (Backend backend = new Backend();
        Fixture fixture = new Fixture(backend)) {
      var restored = fixture.recover();
      fixture.failBinding = true;
      assertEquals(COOKIE, fixture.publish(restored));
      assertEquals(1, fixture.warnings.get());
      assertTrue(fixture.state.currentRun.recoveredLive);
      assertFalse(fixture.state.restoreUnknown);
      assertTrue(fixture.state.lifecycle.isCurrent(7));
      assertEquals(0, fixture.bound.get());
      assertEquals(2, backend.requests.get());
    }
  }
}
