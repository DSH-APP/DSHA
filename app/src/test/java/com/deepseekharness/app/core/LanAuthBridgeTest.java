package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.WebLifecycle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class LanAuthBridgeTest {
  static final class Fake implements LanAuthBridge.Ports {
    final WebLifecycle lifecycle = new WebLifecycle();
    final long generation = lifecycle.beginStart(false, false);
    volatile String url = "http://127.0.0.1:4179/?token=current";
    volatile int port = 4179;
    int published, bound, warnings;
    String message = "before";
    boolean failBinding;

    public LanAuthBridge.Snapshot capture(long expected) {
      synchronized (lifecycle) {
        return lifecycle.isCurrent(expected)
            ? new LanAuthBridge.Snapshot(expected, url, port)
            : null;
      }
    }

    public boolean current(LanAuthBridge.Snapshot snapshot) {
      synchronized (lifecycle) {
        return lifecycle.isCurrent(snapshot.generation())
            && url.equals(snapshot.authUrl())
            && port == snapshot.port();
      }
    }

    public boolean publish(LanAuthBridge.Snapshot snapshot, LanAuthBridge.Exchange result) {
      synchronized (lifecycle) {
        if (!current(snapshot)) return false;
        published++;
        message = result.message();
        return true;
      }
    }

    public void bind(LanAuthBridge.Snapshot snapshot, String cookie) {
      if (!current(snapshot)) return;
      if (failBinding) throw new IllegalStateException("listener failed");
      bound++;
    }

    public void warning(RuntimeException error) {
      warnings++;
    }
  }

  @Test
  public void slowExchangeDoesNotBlockStopAndCannotPublishItsStaleCookie() throws Exception {
    Fake ports = new Fake();
    CountDownLatch entered = new CountDownLatch(1), complete = new CountDownLatch(1);
    LanAuthBridge bridge =
        new LanAuthBridge(
            ports,
            (url, port, current) -> {
              assertFalse(Thread.holdsLock(ports.lifecycle));
              assertEquals(4179, port);
              entered.countDown();
              try {
                assertTrue(complete.await(2, TimeUnit.SECONDS));
              } catch (InterruptedException failure) {
                throw new AssertionError(failure);
              }
              assertFalse(current.getAsBoolean());
              return new LanAuthBridge.Exchange("old-cookie", "old-result");
            });
    AtomicReference<String> cookie = new AtomicReference<>("uncompleted");
    Thread exchange = new Thread(() -> cookie.set(bridge.exchange(ports.generation)));
    exchange.start();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    ports.lifecycle.beginStop();
    complete.countDown();
    exchange.join(2000);
    assertFalse(exchange.isAlive());
    assertNull(cookie.get());
    assertEquals(0, ports.published);
    assertEquals(0, ports.bound);
    assertEquals("before", ports.message);
  }

  @Test
  public void changedAddressWithinTheRunCannotPublishAnOldExchange() {
    Fake ports = new Fake();
    LanAuthBridge bridge =
        new LanAuthBridge(
            ports,
            (url, port, current) -> {
              ports.port = 5001;
              ports.url = "http://127.0.0.1:5001/?token=replaced";
              return new LanAuthBridge.Exchange("old-cookie", "old-result");
            });
    assertNull(bridge.exchange(ports.generation));
    assertEquals(0, ports.published);
    assertEquals(0, ports.bound);
  }

  @Test
  public void unavailableAuthenticationReportsFailureWithoutPublishingLan() {
    Fake ports = new Fake();
    LanAuthBridge bridge =
        new LanAuthBridge(
            ports, (url, port, current) -> new LanAuthBridge.Exchange(null, "auth-not-ready"));
    assertNull(bridge.exchange(ports.generation));
    assertEquals("auth-not-ready", ports.message);
    assertEquals(1, ports.published);
    assertEquals(0, ports.bound);
  }

  @Test
  public void lanBindingFailureIsRecordedAndDoesNotInvalidateLocalCookie() {
    Fake ports = new Fake();
    ports.failBinding = true;
    LanAuthBridge bridge =
        new LanAuthBridge(
            ports, (url, port, current) -> new LanAuthBridge.Exchange("cookie", "ready"));
    assertEquals("cookie", bridge.exchange(ports.generation));
    assertEquals(1, ports.warnings);
    assertEquals("ready", ports.message);
  }

  @Test
  public void staleRequestNeverSendsTheCredential() {
    Fake ports = new Fake();
    ports.lifecycle.beginStop();
    LanAuthBridge bridge =
        new LanAuthBridge(
            ports,
            (url, port, current) -> {
              throw new AssertionError("stale credentials must not be sent");
            });
    assertNull(bridge.exchange(ports.generation));
  }
}
