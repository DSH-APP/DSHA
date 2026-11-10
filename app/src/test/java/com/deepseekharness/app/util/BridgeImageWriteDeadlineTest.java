package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class BridgeImageWriteDeadlineTest {
  @Test
  public void stalledSocketIsClosedAndPermitBecomesAvailable() throws Exception {
    var pool = Executors.newSingleThreadExecutor();
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      var writer =
          pool.submit(
              () -> {
                try (Socket peer = listener.accept()) {
                  peer.setSendBufferSize(4096);
                  BridgeImageBudget.Lease lease = BridgeImageBudget.tryAcquire();
                  if (lease == null) throw new AssertionError("missing image permit");
                  try (lease;
                      BridgeImageWriteDeadline deadline = BridgeImageWriteDeadline.arm(peer, 200)) {
                    byte[] bytes = new byte[64 * 1024];
                    try {
                      peer.getOutputStream()
                          .write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                      for (int i = 0; i < 4096; i++) peer.getOutputStream().write(bytes);
                      return false; // A non-reading peer must not accept the entire large body.
                    } catch (IOException expected) {
                      return peer.isClosed();
                    }
                  }
                }
              });
      try (Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
        client.setReceiveBufferSize(4096);
        assertTrue("deadline must close a blocked writer", writer.get(6, TimeUnit.SECONDS));
      }
      BridgeImageBudget.Lease recovered = BridgeImageBudget.tryAcquire();
      assertNotNull("timed-out response must release the image permit", recovered);
      recovered.close();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void normalSocketResponseCompletesBeforeDeadline() throws Exception {
    var pool = Executors.newSingleThreadExecutor();
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      byte[] response =
          "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes(StandardCharsets.US_ASCII);
      var writer =
          pool.submit(
              () -> {
                try (Socket peer = listener.accept()) {
                  BridgeImageBudget.Lease lease = BridgeImageBudget.tryAcquire();
                  if (lease == null) throw new AssertionError("missing image permit");
                  try (lease;
                      BridgeImageWriteDeadline deadline =
                          BridgeImageWriteDeadline.arm(peer, 2000)) {
                    peer.getOutputStream().write(response);
                    peer.getOutputStream().flush();
                    return !peer.isClosed();
                  }
                }
              });
      try (Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
        client.setSoTimeout(3000);
        assertArrayEquals(response, client.getInputStream().readAllBytes());
        assertTrue(writer.get(3, TimeUnit.SECONDS));
      }
      BridgeImageBudget.Lease next = BridgeImageBudget.tryAcquire();
      assertNotNull(next);
      next.close();
    } finally {
      pool.shutdownNow();
    }
  }
}
