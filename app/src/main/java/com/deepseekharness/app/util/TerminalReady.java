package com.deepseekharness.app.util;

/** Identity reported by the fixed simple-terminal bootstrap before it execs the shell. */
public final class TerminalReady {
  public final int session;
  public final long started;

  private TerminalReady(int session, long started) {
    this.session = session;
    this.started = started;
  }

  public static TerminalReady parse(String message) {
    if (message == null || !message.matches("READY:[1-9][0-9]{0,9}:[1-9][0-9]{0,18}")) return null;
    int separator = message.indexOf(':', 6);
    try {
      int session = Integer.parseInt(message.substring(6, separator));
      long started = Long.parseLong(message.substring(separator + 1));
      return session > 1 && started > 0 ? new TerminalReady(session, started) : null;
    } catch (NumberFormatException invalid) {
      return null;
    }
  }
}
