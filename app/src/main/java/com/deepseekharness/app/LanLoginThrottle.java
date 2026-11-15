package com.deepseekharness.app;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Bound password attempts per peer and cap memory even under a peer flood. */
final class LanLoginThrottle {
  private static final long WINDOW_NS = 60_000_000_000L;
  private static final int MAX_PEERS = 256;
  private static final int MAX_ATTEMPTS = 6;
  private final Map<String, Attempts> peers = new HashMap<>();
  private final java.util.function.LongSupplier clock;

  LanLoginThrottle() {
    this(System::nanoTime);
  }

  LanLoginThrottle(java.util.function.LongSupplier clock) {
    this.clock = clock;
  }

  private static final class Attempts {
    final long start;
    int count;

    Attempts(long start) {
      this.start = start;
    }
  }

  synchronized boolean acquire(String peer) {
    long now = clock.getAsLong();
    Iterator<Attempts> it = peers.values().iterator();
    while (it.hasNext()) if (now - it.next().start >= WINDOW_NS) it.remove();
    Attempts attempts = peers.get(peer);
    if (attempts == null) {
      if (peers.size() >= MAX_PEERS) return false;
      attempts = new Attempts(now);
      peers.put(peer, attempts);
    }
    if (attempts.count >= MAX_ATTEMPTS) return false;
    attempts.count++;
    return true;
  }

  synchronized void success(String peer) {
    peers.remove(peer);
  }
}
