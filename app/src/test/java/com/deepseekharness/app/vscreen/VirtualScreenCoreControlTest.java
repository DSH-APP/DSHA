package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import com.deepseekharness.app.util.VirtualScreenInputResult;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Production bind/auth/parser/routes. Only Android display/process/clock facts are adapted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenCoreControlTest {
  static final class DelayedClosedListener extends ServerSocket {
    final CountDownLatch accepting = new CountDownLatch(1), releaseFailure = new CountDownLatch(1);
    volatile Thread listener;

    DelayedClosedListener() throws IOException {
      super(0, 8, InetAddress.getByName("127.0.0.1"));
    }

    @Override
    public Socket accept() throws IOException {
      listener = Thread.currentThread();
      accepting.countDown();
      try {
        return super.accept();
      } catch (IOException closed) {
        try {
          if (!releaseFailure.await(3, TimeUnit.SECONDS))
            throw new IOException("HOST_OLD_LISTENER_RELEASE_NOT_SIGNALED", closed);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        }
        throw closed;
      }
    }

    void releaseAndJoin() throws InterruptedException {
      releaseFailure.countDown();
      if (listener != null) {
        listener.join(3000);
        assertFalse("Old listener did not retire", listener.isAlive());
      }
    }
  }

  static VirtualScreenCore.HostControl bind(
      String instance, AtomicInteger exits, VirtualScreenCore.HostListenerFactory sockets)
      throws IOException {
    return VirtualScreenCore.bindControlForHost(
        VirtualScreenCoreIdentity.parse(
            "123:456:2000:11111111-2222-3333-4444-555555555555:" + instance),
        (width, height) -> null,
        () -> "com.example.app",
        () -> 100L,
        exits::incrementAndGet,
        sockets);
  }

  @Test
  public void delayedClosedOldListenerCannotRetirePublishedNewBinding() throws Exception {
    var socket = new DelayedClosedListener();
    VirtualScreenCore.HostControl old = null, next = null;
    AtomicInteger exitsA = new AtomicInteger(), exitsB = new AtomicInteger();
    try {
      old = bind("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", exitsA, () -> socket);
      assertTrue(socket.accepting.await(3, TimeUnit.SECONDS));
      old.close();
      next =
          bind(
              "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee",
              exitsB,
              () -> new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")));
      var endpoint = next.handshake();
      assertTrue(
          new VirtualScreenTransport().request(endpoint, "/vscreen/ping", "").optBoolean("ok"));
      socket.releaseAndJoin();
      var result = new VirtualScreenTransport().request(endpoint, "/vscreen/ping", "");
      assertTrue("Old listener retired B: " + result, result.optBoolean("ok"));
      assertEquals(0, exitsB.get());
    } finally {
      socket.releaseFailure.countDown();
      if (old != null) old.close();
      if (next != null) next.close();
      socket.close();
    }
  }

  @Test
  public void retiredHostControlCannotCloseOrExposeNextBinding() throws Exception {
    var socket = new DelayedClosedListener();
    VirtualScreenCore.HostControl old = null, next = null;
    try {
      old = bind("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", new AtomicInteger(), () -> socket);
      assertTrue(socket.accepting.await(3, TimeUnit.SECONDS));
      var endpointA = old.handshake();
      old.close();
      socket.releaseAndJoin();
      next =
          bind(
              "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee",
              new AtomicInteger(),
              () -> new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")));
      assertEquals(endpointA.identity.encode(), old.handshake().identity.encode());
      old.close();
      var result = new VirtualScreenTransport().request(next.handshake(), "/vscreen/ping", "");
      assertTrue(result.toString(), result.optBoolean("ok"));
    } finally {
      socket.releaseFailure.countDown();
      if (old != null) old.close();
      if (next != null) next.close();
      socket.close();
    }
  }

  @Test
  public void actualCoreBindsZeroAuthenticatesAndFencesDisplayGeneration() throws Exception {
    var birth =
        VirtualScreenCoreIdentity.parse(
            "123:456:2000:11111111-2222-3333-4444-555555555555:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    AtomicLong clock = new AtomicLong(100);
    AtomicInteger exits = new AtomicInteger(),
        injected = new AtomicInteger(),
        displays = new AtomicInteger(50);
    var control =
        VirtualScreenCore.bindControlForHost(
            birth,
            (width, height) ->
                new VirtualScreenDisplaySession(
                    width,
                    height,
                    displays.incrementAndGet(),
                    VirtualScreenInput.commandForTest(
                        displays.get(),
                        (argv, timeout) -> {
                          injected.incrementAndGet();
                          return VirtualScreenInputResult.REJECTED;
                        })),
            () -> "com.example.app",
            clock::get,
            exits::incrementAndGet);
    try {
      var endpoint = control.handshake();
      assertTrue(endpoint.port > 0);
      assertEquals(birth.encode(), endpoint.identity.encode());
      var transport = new VirtualScreenTransport();
      assertEquals(
          "VSCREEN_TRANSPORT_UNAVAILABLE",
          transport
              .request(
                  new VirtualScreenHandshake(endpoint.port, "b".repeat(48), endpoint.identity),
                  "/vscreen/create",
                  "width=1008&height=1792")
              .getString("error"));
      var created = transport.request(endpoint, "/vscreen/create", "width=1008&height=1792");
      assertTrue(created.toString(), created.optBoolean("ok"));
      assertEquals(1008, created.getInt("width"));
      assertEquals(1792, created.getInt("height"));
      assertEquals(birth.instance, created.getString("coreInstance"));
      var same = transport.request(endpoint, "/vscreen/create", "width=1008&height=1792");
      assertEquals(created.getString("generation"), same.getString("generation"));
      var rotated = transport.request(endpoint, "/vscreen/create", "width=1792&height=1008");
      assertNotEquals(created.getString("generation"), rotated.getString("generation"));
      var stale =
          transport.request(
              endpoint,
              "/vscreen/tap",
              "generation=" + created.getString("generation") + "&frameSeq=1&x=2&y=3");
      assertEquals("STALE_GENERATION", stale.getString("error"));
      assertEquals(0, injected.get());
      clock.set(40_000);
      assertEquals(
          "VSCREEN_TRANSPORT_UNAVAILABLE",
          transport
              .request(
                  new VirtualScreenHandshake(endpoint.port, "b".repeat(48), endpoint.identity),
                  "/vscreen/ping",
                  "")
              .getString("error"));
      clock.set(60_101);
      control.tick();
      assertEquals(1, exits.get()); // An unauthenticated request did not extend the real lease.
    } finally {
      control.close();
    }
  }
}
