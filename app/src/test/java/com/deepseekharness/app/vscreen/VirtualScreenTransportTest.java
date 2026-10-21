package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** An action was committed before a lost/truncated response: the production transport sends once. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenTransportTest {
  private void lostReply(String response) throws Exception {
    AtomicInteger submissions = new AtomicInteger();
    AtomicBoolean running = new AtomicBoolean(true);
    try (ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
      listener.setSoTimeout(100);
      var endpoint =
          new VirtualScreenHandshake(
              listener.getLocalPort(),
              "a".repeat(48),
              VirtualScreenCoreIdentity.parse(
                  "123:456:2000:11111111-2222-3333-4444-555555555555:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
      Thread peer =
          new Thread(
              () -> {
                while (running.get()) {
                  try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    VirtualScreenPeerProof.server(socket, endpoint);
                    BufferedReader input =
                        new BufferedReader(
                            new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII));
                    if (input.readLine() == null) continue;
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {}
                    submissions.incrementAndGet();
                    if (!response.isEmpty()) {
                      socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                      socket.getOutputStream().flush();
                    }
                  } catch (SocketTimeoutException idle) {
                  } catch (Exception stopped) {
                    if (listener.isClosed()) return;
                  }
                }
              },
              "vscreen-committed-action-lost-response");
      peer.setDaemon(true);
      peer.start();
      try {
        var result =
            new VirtualScreenTransport()
                .request(endpoint, "/vscreen/tap", "generation=test&frameSeq=1&x=1&y=1");
        assertEquals("INPUT_RESULT_UNKNOWN", result.getString("error"));
        assertTrue(result.getBoolean("executionUnknown"));
        assertEquals(1, submissions.get());
      } finally {
        running.set(false);
        peer.join(2000);
      }
    }
  }

  @Test
  public void lostResponseNeverTransparentlyReplaysCommittedGet() throws Exception {
    lostReply("");
  }

  @Test
  public void truncatedResponseNeverTransparentlyReplaysCommittedGet() throws Exception {
    lostReply("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{\"ok\":");
  }

  @Test
  public void stalePortPeerGetsNeitherCredentialNorBusinessTarget() throws Exception {
    try (ServerSocket listener = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
      var expected =
          new VirtualScreenHandshake(
              listener.getLocalPort(),
              "a".repeat(48),
              VirtualScreenCoreIdentity.parse(
                  "123:456:2000:11111111-2222-3333-4444-555555555555:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
      var stale =
          new VirtualScreenHandshake(
              listener.getLocalPort(),
              expected.token,
              VirtualScreenCoreIdentity.parse(
                  "123:457:2000:11111111-2222-3333-4444-555555555555:bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee"));
      java.util.concurrent.atomic.AtomicReference<String> afterProof =
          new java.util.concurrent.atomic.AtomicReference<>();
      Thread peer =
          new Thread(
              () -> {
                try (Socket socket = listener.accept()) {
                  VirtualScreenPeerProof.server(socket, stale);
                  afterProof.set(
                      new String(
                          socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
                } catch (Exception failed) {
                  afterProof.set("PEER_FAILED");
                }
              },
              "vscreen-stale-port-peer");
      peer.setDaemon(true);
      peer.start();
      var result =
          new VirtualScreenTransport()
              .request(
                  expected,
                  "/vscreen/type",
                  "text=private-business-text&generation=test&frameSeq=1");
      peer.join(2000);
      assertTrue(result.getBoolean("executionUnknown"));
      assertEquals("", afterProof.get());
    }
  }
}
