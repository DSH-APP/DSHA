package com.deepseekharness.app;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.HttpProtocol;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.After;
import org.junit.Test;

/** Host sockets execute the actual proxy. All credentials are generated fixture values. */
public class LanProxyHostTest {
  static final String COOKIE = "dsh-auth-fixture=v1.host.signature";
  static final String PASSWORD = "fixture密码123";
  static final String VERIFIER = com.deepseekharness.app.util.LanPassword.create(PASSWORD);
  static android.content.Context context() { return new android.content.Context(VERIFIER); }

  static final class Backend implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    final String label;
    final BlockingQueue<HttpProtocol.Head> requests = new LinkedBlockingQueue<>();
    final ExecutorService workers = Executors.newCachedThreadPool(r -> {
      Thread thread = new Thread(r, "lan-fixture-backend");
      thread.setDaemon(true);
      return thread;
    });

    Backend(String label) throws IOException {
      this.label = label;
      workers.execute(() -> {
        while (!server.isClosed()) {
          try {
            Socket socket = server.accept();
            workers.execute(() -> serve(socket));
          } catch (IOException closed) { break; }
        }
      });
    }

    void serve(Socket socket) {
      try (socket) {
        HttpProtocol.Head request = HttpProtocol.readHead(socket.getInputStream(), socket,
            HttpProtocol.LAN, HttpProtocol.deadline(3000), true);
        if (request == null) return;
        requests.add(request);
        assertEquals(COOKIE, request.value("Cookie"));
        if (request.target.equals("/upgrade")) {
          String accept = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1").digest(
              (request.value("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
          socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          socket.getOutputStream().flush();
          byte[] frame = socket.getInputStream().readNBytes(3);
          socket.getOutputStream().write(frame);
          socket.getOutputStream().flush();
          socket.getInputStream().read();
          return;
        }
        if (request.target.equals("/invalid-upgrade")) {
          socket.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: invalid\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          return;
        }
        if (request.target.equals("/chunked")) {
          socket.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nTrailer: X-Fixture\r\nConnection: close\r\n\r\n3\r\nabc\r\n0\r\nX-Fixture: kept\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          return;
        }
        int status = request.target.equals("/forbidden") ? 403 : request.target.equals("/unauthorized") ? 401 : 200;
        byte[] body = label.getBytes(StandardCharsets.US_ASCII);
        socket.getOutputStream().write(("HTTP/1.1 " + status + " Fixture\r\nContent-Length: "
            + body.length + "\r\nSet-Cookie: dsh-auth-fixture=backend-secret; HttpOnly\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
      } catch (IOException ignored) { }
      catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public void close() throws IOException {
      server.close();
      workers.shutdownNow();
    }
  }

  @After public void stop() { LanProxyService.stop(); }

  @Test public void readingConnectionCredentialRequiresTheCurrentBoundGenerationAndCannotReviveIt()
      throws Exception {
    LanProxyService.stop();
    assertEquals("", LanProxyService.boundLanToken(29));
    try (Backend backend = new Backend("credential-read")) {
      start(backend, 29);
      String current = LanProxyService.boundLanToken(29);
      assertFalse(current.isEmpty());
      assertEquals("", LanProxyService.boundLanToken(28));
      LanProxyService.stopLanListener();
      assertEquals("", LanProxyService.boundLanToken(29));
      assertEquals("", LanProxyService.boundLanToken(29));
      assertFalse(LanProxyService.isBound());
      assertTrue("disabling LAN retains local backend authentication", LanProxyService.hasDshAuth(29));
    }
  }

  static void start(Backend backend, long generation) throws Exception {
    assertTrue(LanProxyService.setDshAuthCookie(COOKIE, generation));
    LanProxyService.start(null, context(), backend.server.getLocalPort(), generation);
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!LanProxyService.isBound() && System.nanoTime() < until) Thread.sleep(10);
    assertTrue("fixture LAN listener did not bind", LanProxyService.isBound());
  }

  static byte[] request(String target, String token, boolean queryCredential) throws Exception {
    return requestAt(LanProxyService.port(), target, token, queryCredential);
  }

  static byte[] requestAt(int port, String target, String token, boolean queryCredential) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(4000);
      String header = "GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n"
          + (queryCredential ? "" : "Cookie: dsha_lan=" + token + "\r\n") + "\r\n";
      socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
      return socket.getInputStream().readAllBytes();
    }
  }

  static String response(String target, String token) throws Exception {
    return new String(request(target, token, false), StandardCharsets.UTF_8);
  }

  static String basic(String password) {
    return "Basic " + Base64.getEncoder().encodeToString(("dsha:" + password).getBytes(StandardCharsets.UTF_8));
  }

  static String raw(String method, String target, String headers) throws Exception {
    return rawHost(method, target, "127.0.0.1:" + LanProxyService.port(), headers);
  }

  static String rawHost(String method, String target, String host, String headers) throws Exception {
    int port = LanProxyService.port();
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write((method + " " + target + " HTTP/1.1\r\nHost: " + host
          + "\r\nConnection: close\r\n" + headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static String login(String password) throws Exception {
    return raw("GET", "/", "Authorization: " + basic(password) + "\r\n");
  }

  static String cookieFrom(String reply) {
    for (String line : reply.split("\r\n"))
      if (line.startsWith("Set-Cookie: dsha_lan=")) return line.substring("Set-Cookie: ".length()).split(";", 2)[0];
    throw new AssertionError("missing LAN session cookie: " + reply);
  }

  @Test public void passwordLoginAndSessionKeepCredentialsOutOfBackend() throws Exception {
    try (Backend backend = new Backend("authenticated")) {
      start(backend, 30);
      String denied = raw("GET", "/", "");
      assertTrue(denied.startsWith("HTTP/1.1 401"));
      assertTrue(denied.contains("WWW-Authenticate: Basic"));
      String reply = login(PASSWORD);
      assertTrue(reply.startsWith("HTTP/1.1 303"));
      assertTrue(reply.contains("HttpOnly; SameSite=Strict"));
      assertFalse(reply.contains(PASSWORD));
      assertFalse(reply.contains("Location: /?token="));
      String session = cookieFrom(reply);
      String forwarded = raw("GET", "/", "Cookie: " + session + "; external=private\r\nAuthorization: " + basic(PASSWORD) + "\r\n");
      assertTrue(forwarded.endsWith("authenticated"));
      HttpProtocol.Head request = backend.requests.poll(2, TimeUnit.SECONDS);
      assertNotNull(request);
      assertEquals(COOKIE, request.value("Cookie"));
      assertTrue(request.value("Authorization").isEmpty());
      assertFalse(forwarded.contains(COOKIE));
      assertTrue(login("wrong-password").startsWith("HTTP/1.1 401"));
      assertTrue(raw("POST", "/", "Authorization: " + basic(PASSWORD) + "\r\nContent-Length: 0\r\n").startsWith("HTTP/1.1 401"));
    }
  }

  @Test public void passwordAndSessionAreRevokedWhenListenerRestarts() throws Exception {
    try (Backend backend = new Backend("rotated")) {
      start(backend, 31);
      String oldCookie = cookieFrom(login(PASSWORD));
      LanProxyService.stopLanListener();
      String replacement = "replacement密码456";
      LanProxyService.start(null, new android.content.Context(com.deepseekharness.app.util.LanPassword.create(replacement)), backend.server.getLocalPort(), 31);
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (!LanProxyService.isBound() && System.nanoTime() < until) Thread.sleep(10);
      assertTrue(LanProxyService.isBound());
      assertTrue(raw("GET", "/", "Cookie: " + oldCookie + "\r\n").startsWith("HTTP/1.1 401"));
      assertTrue(login(PASSWORD).startsWith("HTTP/1.1 401"));
      assertTrue(login(replacement).startsWith("HTTP/1.1 303"));
    }
  }

  @Test public void crossOriginCannotUseAnAuthenticatedSession() throws Exception {
    try (Backend backend = new Backend("cross-origin")) {
      start(backend, 32);
      String session = cookieFrom(login(PASSWORD));
      String reply = raw("GET", "/", "Cookie: " + session + "\r\nOrigin: http://attacker.example\r\nSec-Fetch-Site: cross-site\r\n");
      assertFalse("cross-origin request must not reach backend", reply.startsWith("HTTP/1.1 200"));
      assertNull(backend.requests.poll(200, TimeUnit.MILLISECONDS));
    }
  }

  @Test public void sameOriginSessionRequestsRemainUsable() throws Exception {
    try (Backend backend = new Backend("same-origin")) {
      start(backend, 33);
      String session = cookieFrom(login(PASSWORD));
      String origin = "http://127.0.0.1:" + LanProxyService.port();
      String reply = raw("GET", "/", "Cookie: " + session + "\r\nOrigin: " + origin + "\r\nSec-Fetch-Site: same-origin\r\n");
      assertTrue(reply.endsWith("same-origin"));
      assertNotNull(backend.requests.poll(2, TimeUnit.SECONDS));
      assertFalse(reply.contains("backend-secret"));
      assertFalse(reply.contains("dsh-auth-fixture"));
    }
  }

  @Test public void crossOriginWebsocketCannotUseAnAuthenticatedSession() throws Exception {
    try (Backend backend = new Backend("cross-origin-ws")) {
      start(backend, 34);
      String session = cookieFrom(login(PASSWORD));
      try (Socket socket = new Socket("127.0.0.1", LanProxyService.port())) {
        socket.setSoTimeout(5000);
        String headers = "GET /upgrade HTTP/1.1\r\nHost: 127.0.0.1:" + LanProxyService.port()
            + "\r\nCookie: " + session + "\r\nOrigin: http://attacker.example\r\n"
            + "Sec-Fetch-Site: cross-site\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n"
            + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        HttpProtocol.Head head = HttpProtocol.readHead(socket.getInputStream(), socket, HttpProtocol.LAN, HttpProtocol.deadline(5000), false);
        assertNotEquals("cross-origin websocket must not upgrade", 101, head.status);
      }
      assertNull(backend.requests.poll(200, TimeUnit.MILLISECONDS));
    }
  }

  @Test public void rebindingAndDuplicateHostCannotUseSession() throws Exception {
    try (Backend backend = new Backend("rebind")) {
      start(backend, 35);
      String session = cookieFrom(login(PASSWORD));
      String remote = "attacker.example:" + LanProxyService.port();
      assertTrue(rawHost("GET", "/", remote, "Cookie: " + session + "\r\nOrigin: http://" + remote + "\r\nSec-Fetch-Site: same-origin\r\n").startsWith("HTTP/1.1 403"));
      assertFalse(raw("GET", "/", "Host: " + remote + "\r\nCookie: " + session + "\r\n").startsWith("HTTP/1.1 200"));
      assertNull(backend.requests.poll(200, TimeUnit.MILLISECONDS));
    }
  }

  @Test public void multipleBrowserLoginsAndCookiesWorkWithoutFalseThrottling() throws Exception {
    try (Backend backend = new Backend("many-browsers")) {
      start(backend, 36);
      List<String> cookies = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        String login = login(PASSWORD);
        assertTrue("successful logins from same peer must not exhaust the failure limit", login.startsWith("HTTP/1.1 303"));
        cookies.add(cookieFrom(login));
      }
      for (String cookie : cookies) {
        String result = raw("GET", "/", "Cookie: " + cookie + "\r\nSec-Fetch-Site: same-origin\r\n");
        assertTrue(result.endsWith("many-browsers"));
      }
    }
  }

  @Test public void rateLimitReturns429AndRecoversAfterWindow() throws Exception {
    try (Backend backend = new Backend("rate-limit")) {
      start(backend, 37);
      // Keep real TCP and production throttle, while advancing its injectable clock
      // to exercise the one-minute recovery without making the suite sleep a minute.
      java.lang.reflect.Field runField = LanProxyService.class.getDeclaredField("activeRun");
      runField.setAccessible(true);
      Object run = runField.get(null);
      java.lang.reflect.Field throttleField = run.getClass().getDeclaredField("loginThrottle");
      throttleField.setAccessible(true);
      Object throttle = throttleField.get(run);
      java.lang.reflect.Field clockField = throttle.getClass().getDeclaredField("clock");
      clockField.setAccessible(true);
      java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1);
      clockField.set(throttle, (java.util.function.LongSupplier) now::get);
      for (int i = 0; i < 6; i++) assertTrue(login("wrong-password").startsWith("HTTP/1.1 401"));
      String limited = login(PASSWORD);
      assertTrue(limited.startsWith("HTTP/1.1 429"));
      assertTrue(limited.contains("Retry-After: 60"));
      assertNull(backend.requests.poll(200, TimeUnit.MILLISECONDS));
      now.addAndGet(TimeUnit.SECONDS.toNanos(60));
      assertTrue(login(PASSWORD).startsWith("HTTP/1.1 303"));
    }
  }

  @Test public void routeDenialsDoNotPermanentlyPoisonOtherRequests() throws Exception {
    try (Backend backend = new Backend("route-denial")) {
      start(backend, 11);
      String token = LanProxyService.getLanToken(null);
      assertTrue(response("/forbidden", token).startsWith("HTTP/1.1 403"));
      assertTrue("route 403 must not destroy the current run's credential", response("/", token).startsWith("HTTP/1.1 200"));
      assertTrue(response("/unauthorized", token).startsWith("HTTP/1.1 401"));
      assertTrue("route 401 must not destroy the current run's credential", response("/", token).startsWith("HTTP/1.1 200"));
    }
  }

  @Test public void restartingListenerUsesTheNewActualBackendPort() throws Exception {
    try (Backend first = new Backend("first-port"); Backend second = new Backend("second-port")) {
      start(first, 12);
      String token = LanProxyService.getLanToken(null);
      assertTrue(response("/", token).endsWith("first-port"));
      start(second, 12);
      assertTrue("listener must capture the newly authenticated actual port", response("/", token).endsWith("second-port"));
    }
  }

  @Test public void unknownActualPortDoesNotFallBackToAnUnrelatedService() throws Exception {
    assertTrue(LanProxyService.setDshAuthCookie(COOKIE, 13));
    LanProxyService.start(null, null, 0, 13);
    Thread.sleep(80);
    assertFalse("an unknown actual port cannot silently become 3080", LanProxyService.isBound());
  }

  @Test public void legacyQueryTokenIsRejected() throws Exception {
    try (Backend backend = new Backend("callback")) {
      start(backend, 14);
      String token = LanProxyService.getLanToken(null);
      String reply = new String(request("/auth/callback?code=fixture&token=" + token + "&state=fixture", token, true), StandardCharsets.UTF_8);
      assertTrue(reply.startsWith("HTTP/1.1 401"));
      assertNull(backend.requests.poll(200, TimeUnit.MILLISECONDS));
    }
  }

  @Test public void eightIdleKeepAlivePeersLeaveShortRequestsResponsive() throws Exception {
    List<Socket> idle = new ArrayList<>();
    try (Backend backend = new Backend("idle-peers")) {
      start(backend, 15);
      String token = LanProxyService.getLanToken(null);
      for (int i = 0; i < 8; i++) {
        Socket socket = new Socket("127.0.0.1", LanProxyService.port());
        idle.add(socket);
        socket.setSoTimeout(2500);
        String header = "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + LanProxyService.port() + "\r\nCookie: dsha_lan=" + token + "\r\n\r\n";
        socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
        var input = socket.getInputStream();
        var head = HttpProtocol.readHead(input, socket, HttpProtocol.LAN, HttpProtocol.deadline(2500), false);
        assertEquals(200, head.status);
        HttpProtocol.copyBody(head.responseBody(HttpProtocol.parse(header, true)), input, new ByteArrayOutputStream());
      }
      try (Socket current = new Socket("127.0.0.1", LanProxyService.port())) {
        current.setSoTimeout(2000);
        current.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + LanProxyService.port() + "\r\nCookie: dsha_lan=" + token + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        assertTrue("idle keep-alive sockets must not exhaust request workers", new String(current.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 200"));
      }
    } finally { for (Socket socket : idle) socket.close(); }
  }

  @Test public void chunkedBodyAndTrailerKeepTheirExactBoundary() throws Exception {
    try (Backend backend = new Backend("chunked")) {
      start(backend, 16);
      String reply = response("/chunked", LanProxyService.getLanToken(null));
      assertTrue(reply.startsWith("HTTP/1.1 200"));
      assertTrue(reply.contains("Transfer-Encoding: chunked\r\n"));
      assertTrue(reply.endsWith("\r\n\r\n3\r\nabc\r\n0\r\nX-Fixture: kept\r\n\r\n"));
    }
  }

  @Test public void onlyValidWebsocketHandshakeUpgradesAndStopClosesTheTunnel() throws Exception {
    try (Backend backend = new Backend("websocket")) {
      start(backend, 17);
      String token = LanProxyService.getLanToken(null);
      String credential = " HTTP/1.1\r\nHost: 127.0.0.1:" + LanProxyService.port() + "\r\nOrigin: http://127.0.0.1:" + LanProxyService.port() + "\r\nCookie: dsha_lan=" + token
          + "\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\n\r\n";
      try (Socket socket = new Socket("127.0.0.1", LanProxyService.port())) {
        socket.setSoTimeout(3000);
        socket.getOutputStream().write(("GET /invalid-upgrade" + credential).getBytes(StandardCharsets.US_ASCII));
        assertTrue(new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 502"));
      }
      try (Socket socket = new Socket("127.0.0.1", LanProxyService.port())) {
        socket.setSoTimeout(3000);
        socket.getOutputStream().write(("GET /upgrade" + credential).getBytes(StandardCharsets.US_ASCII));
        var head = HttpProtocol.readHead(socket.getInputStream(), socket, HttpProtocol.LAN, HttpProtocol.deadline(3000), false);
        assertEquals(101, head.status);
        byte[] frame = {(byte) 0x82, 1, 0x42};
        socket.getOutputStream().write(frame);
        assertArrayEquals(frame, socket.getInputStream().readNBytes(3));
        LanProxyService.stop(17);
        assertEquals(-1, socket.getInputStream().read());
        assertFalse(LanProxyService.hasDshAuth(17));
        assertFalse(LanProxyService.isBound());
      }
    }
  }

  @Test public void oldGenerationCannotStopOrAuthorizeTheNewGeneration() throws Exception {
    try (Backend backend = new Backend("generation")) {
      start(backend, 18);
      assertTrue(LanProxyService.port(18) > 0);
      assertEquals(0, LanProxyService.port(17));
      String old = LanProxyService.getLanToken(null);
      var snapshot = LanProxyService.snapshotDshAuth(18);
      assertTrue(LanProxyService.isCurrentDshAuth(snapshot));
      LanProxyService.stop(18);
      assertEquals(0, LanProxyService.port(18));
      start(backend, 19);
      assertEquals(0, LanProxyService.port(18));
      assertTrue(LanProxyService.port(19) > 0);
      assertFalse(LanProxyService.isCurrentDshAuth(snapshot));
      assertTrue(response("/", old).startsWith("HTTP/1.1 401"));
      LanProxyService.stop(18);
      assertTrue(LanProxyService.isBound());
      assertTrue(response("/", LanProxyService.getLanToken(null)).startsWith("HTTP/1.1 200"));
    }
  }

  @Test public void independentApplicationsHoldSeparateLanPortsAndRejectEachOthersTokens() throws Exception {
    // Isolate static app state just as two Android processes do. Both execute
    // the same compiled production proxy; Android permission/context are stubs.
    URL location = LanProxyService.class.getProtectionDomain().getCodeSource().getLocation();
    try (java.net.URLClassLoader other = new java.net.URLClassLoader(
            new URL[] {location}, ClassLoader.getPlatformClassLoader());
         Backend first = new Backend("first-app"); Backend second = new Backend("second-app")) {
      Class<?> proxy = other.loadClass("com.deepseekharness.app.LanProxyService");
      Class<?> context = other.loadClass("android.content.Context");
      java.lang.reflect.Method otherStop = proxy.getMethod("stop");
      try {
        start(first, 21);
        int firstPort = LanProxyService.port(21);
        String firstToken = LanProxyService.getLanToken(null);
        assertEquals(true, proxy.getMethod("setDshAuthCookie", String.class, long.class).invoke(null, COOKIE, 22L));
        proxy.getMethod("start", String.class, context, int.class, long.class)
            .invoke(null, null, context.getConstructor(String.class).newInstance(VERIFIER), second.server.getLocalPort(), 22L);
        java.lang.reflect.Method otherPort = proxy.getMethod("port", long.class);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        int secondPort;
        while ((secondPort = (Integer) otherPort.invoke(null, 22L)) == 0 && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(secondPort > 0);
        assertNotEquals(firstPort, secondPort);
        String secondToken = (String) proxy.getMethod("getLanToken", context).invoke(null, new Object[] {null});
        assertNotEquals(firstToken, secondToken);
        assertTrue(new String(requestAt(firstPort, "/", firstToken, false), StandardCharsets.UTF_8).endsWith("first-app"));
        assertTrue(new String(requestAt(secondPort, "/", secondToken, false), StandardCharsets.UTF_8).endsWith("second-app"));
        assertTrue(new String(requestAt(firstPort, "/", secondToken, false), StandardCharsets.UTF_8).startsWith("HTTP/1.1 401"));
        assertTrue(new String(requestAt(secondPort, "/", firstToken, false), StandardCharsets.UTF_8).startsWith("HTTP/1.1 401"));
        LanProxyService.stop(21);
        assertEquals(0, LanProxyService.port(21));
        assertEquals(secondPort, otherPort.invoke(null, 22L));
        assertTrue(new String(requestAt(secondPort, "/", secondToken, false), StandardCharsets.UTF_8).endsWith("second-app"));
      } finally { otherStop.invoke(null); }
    }
  }
}
