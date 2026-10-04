package com.deepseekharness.app.util;

import java.util.ArrayDeque;
import java.util.List;

/** A failed diagnostic sink never grants success or hides the original operation's failure. */
public final class DiagnosticFailures {
  public record Entry(long at, String operation, String type, String message) {}

  private final ArrayDeque<Entry> recent = new ArrayDeque<>();
  private Entry first;

  public synchronized void add(String operation, Throwable failure) {
    String message =
        SensitiveData.redact(String.valueOf(failure.getMessage())).split("\\r?\\n", 2)[0];
    if (message.length() > 300) message = message.substring(0, 300);
    Entry entry =
        new Entry(
            System.currentTimeMillis(), operation, failure.getClass().getSimpleName(), message);
    if (first == null) first = entry;
    if (recent.size() == 8) recent.removeFirst();
    recent.addLast(entry);
  }

  public synchronized List<Entry> snapshot() {
    return List.copyOf(recent);
  }

  public synchronized Entry first() {
    return first;
  }

  public synchronized String summary() {
    if (first == null) return "";
    StringBuilder value = new StringBuilder("first: ").append(render(first));
    for (Entry entry : recent) if (entry != first) value.append('\n').append(render(entry));
    return value.toString();
  }

  private static String render(Entry entry) {
    return entry.at() + " " + entry.operation() + " " + entry.type() + ": " + entry.message();
  }
}
