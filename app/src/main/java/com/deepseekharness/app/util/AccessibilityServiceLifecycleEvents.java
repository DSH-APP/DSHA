package com.deepseekharness.app.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded diagnostic facts only; recording a lifecycle never connects or authorizes a service. */
public final class AccessibilityServiceLifecycleEvents {
  public enum Kind {
    CONNECTED,
    UNBOUND,
    DESTROYED,
    INTERRUPTED;

    public String code() {
      return "ACCESSIBILITY_SERVICE_" + name();
    }
  }

  public record Event(Kind kind, long serviceEpoch) {}

  private static final int LIMIT = 32;
  private final ArrayDeque<Event> events = new ArrayDeque<>();

  public synchronized void record(Kind kind, long serviceEpoch) {
    if (kind == null || serviceEpoch < 0) return;
    if (events.size() == LIMIT) events.removeFirst();
    events.addLast(new Event(kind, serviceEpoch));
  }

  public synchronized boolean hasEvents() {
    return !events.isEmpty();
  }

  public synchronized List<Event> drainEvents() {
    List<Event> result = new ArrayList<>(events);
    events.clear();
    return result;
  }
}
