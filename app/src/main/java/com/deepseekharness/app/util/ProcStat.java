package com.deepseekharness.app.util;

/** Read-only Linux stat values. Ownership/UID/signal permission remain caller policy. */
public final class ProcStat {
  public final int pid, parent, group, session;
  public final long started;
  public final char state;

  private ProcStat(int pid, int parent, int group, int session, long started, char state) {
    this.pid = pid;
    this.parent = parent;
    this.group = group;
    this.session = session;
    this.started = started;
    this.state = state;
  }

  public static ProcStat parse(String text, int expectedPid) {
    if (text == null || text.length() > 8192 || expectedPid <= 1) return null;
    int open = text.indexOf(" ("), close = text.lastIndexOf(')');
    if (open < 1 || close <= open) return null;
    try {
      if (Integer.parseInt(text.substring(0, open)) != expectedPid) return null;
      String[] fields = text.substring(close + 1).trim().split("\\s+");
      if (fields.length < 20 || fields[0].length() != 1) return null;
      long started = Long.parseLong(fields[19]);
      if (started <= 0) return null;
      return new ProcStat(
          expectedPid,
          Integer.parseInt(fields[1]),
          Integer.parseInt(fields[2]),
          Integer.parseInt(fields[3]),
          started,
          fields[0].charAt(0));
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  public static boolean exited(char state) {
    return state == 'Z' || state == 'X' || state == 'x';
  }

  public boolean exited() {
    return exited(state);
  }
}
