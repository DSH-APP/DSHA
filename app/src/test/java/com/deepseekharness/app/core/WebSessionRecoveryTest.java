package com.deepseekharness.app.core;

import static org.junit.Assert.*;
import com.deepseekharness.app.runtime.StoredWebSession;
import com.deepseekharness.app.util.DshAuthSession;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class WebSessionRecoveryTest {
  private static class Fixture implements WebSessionRecovery.Ports {
    final List<String> calls = new ArrayList<>();
    boolean current = true, compatible = true, valid = true, authenticated = true, deny;
    int probes;
    StoredWebSession.Record record = new StoredWebSession.Record(null, "", "", "", "", false, "");

    public StoredWebSession.Record read() {
      calls.add("read");
      return record;
    }

    public boolean current() {
      return current;
    }

    public boolean compatible(StoredWebSession.Record r) {
      calls.add("compatible");
      return compatible;
    }

    public boolean verify(StoredWebSession.Record r) throws Exception {
      calls.add("verify");
      probes++;
      if (deny) throw new java.io.IOException("PROC_DENIED");
      return valid;
    }

    public boolean authenticate(StoredWebSession.Record r) {
      calls.add("http");
      return authenticated;
    }

    public boolean adopt(StoredWebSession.Record r) {
      calls.add("adopt");
      return true;
    }
  }

  @Test
  public void noRecordDoesNotArmWorkOrTouchBackend() {
    Fixture f = new Fixture();
    f.record = null;
    assertEquals(WebSessionRecovery.Result.NONE, WebSessionRecovery.recover(f));
    assertEquals(List.of("read"), f.calls);
  }

  @Test
  public void onlyTwoKernelChecksAndHttpCanAdopt() {
    Fixture f = new Fixture();
    assertEquals(WebSessionRecovery.Result.RESTORED, WebSessionRecovery.recover(f));
    assertEquals(List.of("read", "compatible", "verify", "http", "verify", "adopt"), f.calls);
  }

  @Test
  public void procDeniedAndMissingGuestRemainUnknownWithoutHttpOrAdoption() {
    for (boolean denied : new boolean[] {true, false}) {
      Fixture f = new Fixture();
      f.deny = denied;
      f.valid = false;
      assertEquals(WebSessionRecovery.Result.UNKNOWN, WebSessionRecovery.recover(f));
      assertFalse(f.calls.contains("http"));
      assertFalse(f.calls.contains("adopt"));
    }
  }

  @Test
  public void packageUpgradeOrFailedAuthenticationRemainsUnknown() {
    Fixture f = new Fixture();
    f.compatible = false;
    assertEquals(WebSessionRecovery.Result.UNKNOWN, WebSessionRecovery.recover(f));
    assertEquals(0, f.probes);
    f = new Fixture();
    f.authenticated = false;
    assertEquals(WebSessionRecovery.Result.UNKNOWN, WebSessionRecovery.recover(f));
    assertEquals(1, f.probes);
    assertFalse(f.calls.contains("adopt"));
  }

  @Test
  public void stopOrIdentityChangeDuringHttpCannotAdopt() {
    Fixture stopped =
        new Fixture() {
          public boolean authenticate(StoredWebSession.Record r) {
            current = false;
            return true;
          }
        };
    assertEquals(WebSessionRecovery.Result.STALE, WebSessionRecovery.recover(stopped));
    assertFalse(stopped.calls.contains("adopt"));
    Fixture changed =
        new Fixture() {
          public boolean authenticate(StoredWebSession.Record r) {
            valid = false;
            return true;
          }
        };
    assertEquals(WebSessionRecovery.Result.UNKNOWN, WebSessionRecovery.recover(changed));
    assertFalse(changed.calls.contains("adopt"));
  }

  @Test
  public void earlierStopStillLoadsReadOnlyEvidenceBeforeRejectingAdoption() {
    Fixture stopped = new Fixture();
    stopped.current = false;
    assertEquals(WebSessionRecovery.Result.STALE, WebSessionRecovery.recover(stopped));
    assertEquals(List.of("read"), stopped.calls);
  }

  @Test
  public void actualLoopbackAuthenticationMustAcceptCookieBeforeAdoption() throws Exception {
    try (ServerSocket listener =
        new ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))) {
      String name = "dsh-auth-" + "a".repeat(43);
      String cookie = name + "=v1.payload." + "b".repeat(43);
      var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      Thread server =
          new Thread(
              () -> {
                try {
                  for (int i = 0; i < 2; i++)
                    try (var socket = listener.accept()) {
                      var reader =
                          new java.io.BufferedReader(
                              new java.io.InputStreamReader(
                                  socket.getInputStream(), StandardCharsets.US_ASCII));
                      StringBuilder request = new StringBuilder();
                      for (String line; (line = reader.readLine()) != null && !line.isEmpty(); )
                        request.append(line).append('\n');
                      if (i == 0) assertTrue(request.toString().contains("?token="));
                      else assertTrue(request.toString().contains("Cookie: " + cookie));
                      String response =
                          i == 0
                              ? "HTTP/1.1 303 See Other\r\nLocation: ./\r\nSet-Cookie: "
                                  + cookie
                                  + "; HttpOnly\r\nContent-Length: 0\r\n\r\n"
                              : "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
                      socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    }
                } catch (Throwable error) {
                  failure.set(error);
                }
              });
      server.setDaemon(true);
      server.start();
      Fixture f =
          new Fixture() {
            public boolean authenticate(StoredWebSession.Record r) {
              calls.add("http");
              return DshAuthSession.exchange(
                      "http://127.0.0.1:" + listener.getLocalPort() + "/?token=" + "t".repeat(43),
                      listener.getLocalPort(),
                      () -> current,
                      2000)
                  .ready();
            }
          };
      assertEquals(WebSessionRecovery.Result.RESTORED, WebSessionRecovery.recover(f));
      server.join(3000);
      assertFalse(server.isAlive());
      if (failure.get() != null) throw new AssertionError(failure.get());
    }
  }
}
