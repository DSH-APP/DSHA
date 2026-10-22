package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import com.deepseekharness.app.util.OneShotLaunchAuthority;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import com.deepseekharness.app.util.VirtualScreenLifecycleState;
import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real manager + loopback HTTP. Android process/UID/probe are deliberately not claimed here. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenLifecycleTest {
  private VirtualScreenManager manager() {
    return new VirtualScreenManager(
        null,
        () -> 100,
        new VirtualScreenManager.Visuals() {
          public void present() {}

          public void stopped(long fence) {}
        });
  }

  private Object field(VirtualScreenManager owner, String name) throws Exception {
    var field = VirtualScreenManager.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private void set(VirtualScreenManager owner, String name, Object value) throws Exception {
    var field = VirtualScreenManager.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private void shutdown(VirtualScreenManager owner) throws Exception {
    ((ScheduledExecutorService) field(owner, "WORKER")).shutdownNow();
  }

  @Test
  public void releaseReplyDoesNotProveExitAndRetainsCleanupIdentity() throws Exception {
    var server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
    var handle =
        new VirtualScreenHandshake(
            server.getLocalPort(),
            "a".repeat(48),
            VirtualScreenCoreIdentity.parse(
                "123:456:2000:11111111-2222-3333-4444-555555555555:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
    AtomicInteger sent = new AtomicInteger();
    Thread peer =
        new Thread(
            () -> {
              try (Socket socket = server.accept()) {
                var proof = VirtualScreenPeerProof.server(socket, handle);
                BufferedReader input =
                    new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                assertEquals("GET /vscreen/close HTTP/1.1", input.readLine());
                String line, clientProof = "";
                while ((line = input.readLine()) != null && !line.isEmpty())
                  if (line.startsWith(VirtualScreenPeerProof.HEADER + ":"))
                    clientProof = line.substring(line.indexOf(':') + 1).trim();
                assertTrue(proof.authorizes("GET", "/vscreen/close", clientProof));
                sent.incrementAndGet();
                socket
                    .getOutputStream()
                    .write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}"
                            .getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
              } catch (Exception failed) {
                throw new RuntimeException(failed);
              }
            },
            "vscreen-test-close-peer");
    peer.setDaemon(true);
    peer.start();
    var manager = manager();
    try {
      set(manager, "core", handle);
      set(manager, "token", handle.token);
      set(manager, "port", handle.port);
      set(manager, "channel", "not-an-available-channel");
      set(manager, "lifecycleState", VirtualScreenLifecycleState.READY);
      var result = manager.close();
      assertFalse(result.optBoolean("ok"));
      assertTrue(result.optBoolean("displayReleaseAcknowledged"));
      assertEquals("UNKNOWN", result.getString("coreExitProof"));
      assertEquals(1, sent.get());
      assertEquals(VirtualScreenLifecycleState.CLOSE_UNKNOWN, manager.lifecycleState());
      assertNotNull(field(manager, "pendingCleanup"));
      assertEquals("", field(manager, "token"));
    } finally {
      server.close();
      peer.join(2000);
      shutdown(manager);
    }
  }

  @Test
  public void closingUnknownLaunchCannotTurnItIntoStopped() throws Exception {
    var manager = manager();
    try {
      set(manager, "lifecycleState", VirtualScreenLifecycleState.START_UNKNOWN);
      var result = manager.close();
      assertFalse(result.optBoolean("ok"));
      assertTrue(result.optBoolean("cleanupPending"));
      assertEquals(VirtualScreenLifecycleState.START_UNKNOWN, manager.lifecycleState());
    } finally {
      shutdown(manager);
    }
  }

  @Test
  public void cancellationBeforeDispatchIsKnownStopped() throws Exception {
    var manager = manager();
    try {
      set(manager, "lifecycleState", VirtualScreenLifecycleState.STARTING);
      set(manager, "starting", true);
      set(manager, "launchDispatched", false);
      assertTrue(manager.close().optBoolean("ok"));
      assertEquals(VirtualScreenLifecycleState.STOPPED, manager.lifecycleState());
      set(manager, "lifecycleState", VirtualScreenLifecycleState.STARTING);
      set(manager, "starting", true);
      set(manager, "launchDispatched", true);
      assertFalse(manager.close().optBoolean("ok"));
      assertEquals(VirtualScreenLifecycleState.START_UNKNOWN, manager.lifecycleState());
    } finally {
      shutdown(manager);
    }
  }

  @Test
  public void probeTicketIsSeparateAndOneUse() throws Exception {
    var manager = manager();
    try {
      var start = (OneShotLaunchAuthority) field(manager, "ADB_LAUNCH");
      var probe = (OneShotLaunchAuthority) field(manager, "ADB_PROBE");
      String a = "a".repeat(48), b = "b".repeat(48);
      assertTrue(start.issue(a, "typed launch", 0, 100, 1000));
      assertTrue(probe.issue(b, "typed read-only probe", 0, 100, 1000));
      assertFalse(manager.authorizeAdbLaunchPlan(b, "typed launch"));
      assertTrue(manager.authorizeAdbLaunchPlan(b, "typed read-only probe"));
      assertTrue(manager.commitAdbLaunch(b));
      assertFalse(manager.commitAdbLaunch(b));
      assertEquals(a, manager.adbLaunchTicketFor("typed launch"));
    } finally {
      shutdown(manager);
    }
  }
}
