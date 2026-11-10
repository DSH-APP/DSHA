package com.deepseekharness.app.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import static org.junit.Assert.assertThrows;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class BridgePortPolicyTest {
  @Test
  public void candidatesLeaveLegacyPortFreeAndStayBounded() {
    int[] ports = BridgePortPolicy.candidates();
    assertEquals(10, ports.length);
    assertEquals(3091, ports[0]);
    assertEquals(3090, ports[9]);
    assertArrayEquals(
        new int[] {3091, 3092, 3093, 3094, 3095, 3096, 3097, 3098, 3099, 3090}, ports);
    assertArrayEquals(
        new int[] {3090, 3091, 3092, 3093, 3094, 3095, 3096, 3097, 3098, 3099},
        BridgePortPolicy.candidates("com.dsh.client"));
  }

  @Test
  public void validRejectsReservedAndOutOfRange() {
    assertTrue(BridgePortPolicy.valid(1));
    assertTrue(BridgePortPolicy.valid(3090));
    assertTrue(BridgePortPolicy.valid(65535));
    assertFalse(BridgePortPolicy.valid(0));
    assertFalse(BridgePortPolicy.valid(-1));
    assertFalse(BridgePortPolicy.valid(65536));
    assertFalse(BridgePortPolicy.valid(3080));
    assertFalse(BridgePortPolicy.valid(3081));
    assertFalse(BridgePortPolicy.valid(3082));
  }

  @Test
  public void occupiedFirstPortFallsBackAndReleasedPrimaryCanBeReused() throws Exception {
    // 仅使用系统分配的临时端口，不触碰本机真实设备桥。
    try (ServerSocket first = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      int primary = first.getLocalPort(), fallback = reservation.getLocalPort();
      reservation.close();
      try (ServerSocket second = BridgePortPolicy.bind(new int[] {primary, fallback})) {
        assertEquals(fallback, second.getLocalPort());
        assertEquals("127.0.0.1", second.getInetAddress().getHostAddress());
        assertThrows(
            BridgePortPolicy.Exhausted.class,
            () -> BridgePortPolicy.bind(new int[] {primary, fallback}));
        assertFalse(first.isClosed());
        assertFalse(second.isClosed());
        first.close();
        try (ServerSocket restarted = BridgePortPolicy.bind(new int[] {primary, fallback})) {
          assertEquals(primary, restarted.getLocalPort());
          assertFalse(second.isClosed());
        }
      }
    }
  }

  @Test
  public void permissionAndUnknownBindFailuresAreNotPortConflicts() {
    assertTrue(BridgePortPolicy.addressInUse(new BindException("bind failed: EADDRINUSE")));
    assertTrue(BridgePortPolicy.addressInUse(new BindException("Address already in use")));
    assertFalse(BridgePortPolicy.addressInUse(new BindException("bind failed: EACCES")));
    assertFalse(BridgePortPolicy.addressInUse(new BindException()));
  }

  @Test
  public void rangeLabelMatchesCandidates() {
    assertEquals("3090-3099", BridgePortPolicy.rangeLabel());
  }

  @Test
  public void bothAppStartupOrdersKeepTheirOwnSocketsAndCredentials() throws Exception {
    // Remap the production candidate order onto host ephemeral ports, leaving
    // any actual DSHA bridge on this computer entirely untouched.
    for (boolean currentFirst : new boolean[] {true, false}) {
      int[] slots = new int[BridgePortPolicy.ATTEMPTS];
      ServerSocket[] reservations = new ServerSocket[slots.length];
      try {
        for (int i = 0; i < slots.length; i++) {
          reservations[i] = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
          slots[i] = reservations[i].getLocalPort();
        }
      } finally {
        for (ServerSocket reservation : reservations) if (reservation != null) reservation.close();
      }
      int[] current = mapped("io.github.sunsetrne.dsha", slots),
          legacy = mapped("com.dsh.client", slots);
      try (ServerSocket first = BridgePortPolicy.bind(currentFirst ? current : legacy);
          ServerSocket second = BridgePortPolicy.bind(currentFirst ? legacy : current)) {
        ServerSocket currentSocket = currentFirst ? first : second,
            legacySocket = currentFirst ? second : first;
        assertEquals(slots[0], legacySocket.getLocalPort());
        assertEquals(slots[1], currentSocket.getLocalPort());
        assertEquals(
            "[CURRENT]",
            exchange(currentSocket, "current-credential", "current-credential", "[CURRENT]"));
        assertEquals(
            "[LEGACY]",
            exchange(legacySocket, "legacy-credential", "legacy-credential", "[LEGACY]"));
        assertEquals(
            "[UNAUTHORIZED]",
            exchange(currentSocket, "current-credential", "legacy-credential", "[CURRENT]"));
        assertEquals(
            "[UNAUTHORIZED]",
            exchange(legacySocket, "legacy-credential", "current-credential", "[LEGACY]"));
        currentSocket.close();
        assertEquals(
            "[LEGACY]",
            exchange(legacySocket, "legacy-credential", "legacy-credential", "[LEGACY]"));
      }
    }
  }

  private static int[] mapped(String applicationId, int[] slots) {
    int[] result = BridgePortPolicy.candidates(applicationId);
    for (int i = 0; i < result.length; i++)
      result[i] = slots[result[i] - BridgePortPolicy.PRIMARY_PORT];
    return result;
  }

  private static String exchange(
      ServerSocket listener, String expected, String presented, String label) throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    Future<?> request =
        worker.submit(
            () -> {
              try (Socket peer = listener.accept()) {
                var head =
                    HttpProtocol.readHead(
                        peer.getInputStream(),
                        peer,
                        HttpProtocol.BRIDGE,
                        HttpProtocol.deadline(3000),
                        true);
                // This is the same bounded HTTP parser and credential gate used by
                // HttpShellService, with generated fixture values only.
                boolean authorized =
                    BridgeCredentialAuth.authorized(head.target, head.values("X-Token"), expected);
                byte[] body =
                    (authorized ? label : "[UNAUTHORIZED]").getBytes(StandardCharsets.US_ASCII);
                peer.getOutputStream()
                    .write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: "
                                + body.length
                                + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                peer.getOutputStream().write(body);
              } catch (Exception error) {
                throw new RuntimeException(error);
              }
            });
    try (Socket peer = new Socket("127.0.0.1", listener.getLocalPort())) {
      peer.setSoTimeout(3000);
      peer.getOutputStream()
          .write(
              ("GET /app/native-policy HTTP/1.1\r\nHost: 127.0.0.1:"
                      + listener.getLocalPort()
                      + "\r\nX-Token: "
                      + presented
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      String reply = new String(peer.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
      request.get(3, TimeUnit.SECONDS);
      return reply.substring(reply.indexOf("\r\n\r\n") + 4);
    } finally {
      worker.shutdownNow();
    }
  }
}
