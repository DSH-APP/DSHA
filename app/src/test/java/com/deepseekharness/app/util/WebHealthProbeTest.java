package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebHealthProbeTest {
  private static final String TOKEN = "a".repeat(43);
  private static final String COOKIE = "dsh-auth-" + TOKEN + "=v1.payload." + TOKEN;

  private static final class Server implements AutoCloseable {
    final ServerSocket listener = new ServerSocket(0);
    final Thread worker;

    Server(String... responses) throws Exception {
      worker =
          new Thread(
              () -> {
                try {
                  try (Socket initial = listener.accept()) {
                    /* listening-only probe */
                  }
                  for (String response : responses)
                    try (Socket client = listener.accept()) {
                      client.setSoTimeout(1500);
                      BufferedReader input =
                          new BufferedReader(
                              new InputStreamReader(
                                  client.getInputStream(), StandardCharsets.US_ASCII));
                      String line;
                      while ((line = input.readLine()) != null && !line.isEmpty()) {}
                      client
                          .getOutputStream()
                          .write(
                              ("HTTP/1.1 "
                                      + response
                                      + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                                  .getBytes(StandardCharsets.US_ASCII));
                    }
                  while (!listener.isClosed())
                    try (Socket ignored = listener.accept()) {
                      Thread.sleep(800);
                    }
                } catch (Exception ignored) {
                }
              });
      worker.setDaemon(true);
      worker.start();
    }

    String url() {
      return "http://127.0.0.1:" + listener.getLocalPort() + "/?token=" + TOKEN;
    }

    WebHealthProbe.Result probe() {
      return WebHealthProbe.probe(url(), listener.getLocalPort(), () -> true, 300);
    }

    public void close() throws Exception {
      listener.close();
      worker.interrupt();
      worker.join(1500);
    }
  }

  @Test
  public void listeningWithoutHttpResponseIsNotReadyOrRestartAuthority() throws Exception {
    try (Server server = new Server()) {
      WebHealthProbe.Result result = server.probe();
      assertEquals(WebHealthProbe.State.LISTENING, result.state);
      assertFalse(result.healthy());
      assertFalse(result.restartCandidate());
    }
  }

  @Test
  public void authenticationRejectionAndInvalidServerAreNotHealthy() throws Exception {
    try (Server server = new Server("401 Unauthorized\r\n")) {
      assertEquals(WebHealthProbe.State.AUTH_EXPIRED, server.probe().state);
    }
    try (Server server = new Server("200 OK\r\n")) {
      assertEquals(WebHealthProbe.State.INVALID_RESPONSE, server.probe().state);
    }
  }

  @Test
  public void verifiesCookieAndCleanRootWithoutDependingOnClientStream() throws Exception {
    try (Server server =
        new Server(
            "303 See Other\r\nLocation: ./\r\nSet-Cookie: " + COOKIE + "; HttpOnly\r\n",
            "200 OK\r\n")) {
      WebHealthProbe.Result result = server.probe();
      assertEquals(WebHealthProbe.State.HTTP_READY, result.state);
      assertTrue(result.healthy());
      assertFalse(result.restartCandidate());
    }
  }

  @Test
  public void generationChangeInvalidatesObservedResult() throws Exception {
    try (Server server = new Server("401 Unauthorized\r\n")) {
      java.util.concurrent.atomic.AtomicInteger calls =
          new java.util.concurrent.atomic.AtomicInteger();
      WebHealthProbe.Result result =
          WebHealthProbe.probe(
              server.url(), server.listener.getLocalPort(), () -> calls.incrementAndGet() < 3, 300);
      assertEquals(WebHealthProbe.State.STALE, result.state);
      assertFalse(result.restartCandidate());
    }
  }

  @Test
  public void onlyConnectionRefusalIsARestartCandidate() throws Exception {
    int port;
    try (ServerSocket listener = new ServerSocket(0)) {
      port = listener.getLocalPort();
    }
    WebHealthProbe.Result result =
        WebHealthProbe.probe(
            "http://127.0.0.1:" + port + "/?token=" + TOKEN, port, () -> true, 300);
    assertEquals(WebHealthProbe.State.UNAVAILABLE, result.state);
    assertTrue(result.restartCandidate());
  }
}
