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

  static final class Backend implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    final String label;
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
            + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
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

  static void start(Backend backend, long generation) throws Exception {
    assertTrue(LanProxyService.setDshAuthCookie(COOKIE, generation));
    LanProxyService.start(null, null, backend.server.getLocalPort(), generation);
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!LanProxyService.isBound() && System.nanoTime() < until) Thread.sleep(10);
    assertTrue("fixture LAN listener did not bind", LanProxyService.isBound());
  }

  static byte[] request(String target, String token, boolean queryCredential) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", LanProxyService.LAN_PORT)) {
      socket.setSoTimeout(4000);
      String header = "GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1:3081\r\nConnection: close\r\n"
          + (queryCredential ? "" : "Cookie: dsha_lan=" + token + "\r\n") + "\r\n";
      socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
      return socket.getInputStream().readAllBytes();
    }
  }

  static String response(String target, String token) throws Exception {
    return new String(request(target, token, false), StandardCharsets.UTF_8);
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

  @Test public void tokenExchangeKeepsTheOriginalSafeCallbackTarget() throws Exception {
    try (Backend backend = new Backend("callback")) {
      start(backend, 14);
      String token = LanProxyService.getLanToken(null);
      String reply = new String(request("/auth/callback?code=fixture&token=" + token + "&state=fixture", token, true), StandardCharsets.UTF_8);
      assertTrue(reply.startsWith("HTTP/1.1 303"));
      assertTrue("credential cleanup must preserve callback path/code/state", reply.contains("\r\nLocation: /auth/callback?code=fixture&state=fixture\r\n"));
      assertFalse("redirect must not carry LAN query credential", reply.contains("Location: /auth/callback?code=fixture&token="));
    }
  }

  @Test public void eightIdleKeepAlivePeersLeaveShortRequestsResponsive() throws Exception {
    List<Socket> idle = new ArrayList<>();
    try (Backend backend = new Backend("idle-peers")) {
      start(backend, 15);
      String token = LanProxyService.getLanToken(null);
      for (int i = 0; i < 8; i++) {
        Socket socket = new Socket("127.0.0.1", LanProxyService.LAN_PORT);
        idle.add(socket);
        socket.setSoTimeout(2500);
        String header = "GET / HTTP/1.1\r\nHost: 127.0.0.1:3081\r\nCookie: dsha_lan=" + token + "\r\n\r\n";
        socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
        var input = socket.getInputStream();
        var head = HttpProtocol.readHead(input, socket, HttpProtocol.LAN, HttpProtocol.deadline(2500), false);
        assertEquals(200, head.status);
        HttpProtocol.copyBody(head.responseBody(HttpProtocol.parse(header, true)), input, new ByteArrayOutputStream());
      }
      try (Socket current = new Socket("127.0.0.1", LanProxyService.LAN_PORT)) {
        current.setSoTimeout(2000);
        current.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:3081\r\nCookie: dsha_lan=" + token + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
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
      String credential = " HTTP/1.1\r\nHost: 127.0.0.1:3081\r\nCookie: dsha_lan=" + token
          + "\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\n\r\n";
      try (Socket socket = new Socket("127.0.0.1", LanProxyService.LAN_PORT)) {
        socket.setSoTimeout(3000);
        socket.getOutputStream().write(("GET /invalid-upgrade" + credential).getBytes(StandardCharsets.US_ASCII));
        assertTrue(new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 502"));
      }
      try (Socket socket = new Socket("127.0.0.1", LanProxyService.LAN_PORT)) {
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
      String old = LanProxyService.getLanToken(null);
      var snapshot = LanProxyService.snapshotDshAuth(18);
      assertTrue(LanProxyService.isCurrentDshAuth(snapshot));
      LanProxyService.stop(18);
      start(backend, 19);
      assertFalse(LanProxyService.isCurrentDshAuth(snapshot));
      assertTrue(response("/", old).startsWith("HTTP/1.1 401"));
      LanProxyService.stop(18);
      assertTrue(LanProxyService.isBound());
      assertTrue(response("/", LanProxyService.getLanToken(null)).startsWith("HTTP/1.1 200"));
    }
  }
}
