package com.deepseekharness.app.util;

/** Web PID 的内核启动时刻；同一数字被复用时不能沿用旧进程身份。 */
public final class WebPidIdentity {
  public final int pid;
  public final long started;
  public final char state;

  private WebPidIdentity(int pid, long started, char state) {
    this.pid = pid;
    this.started = started;
    this.state = state;
  }

  public static WebPidIdentity parse(String stat, int expectedPid) {
    ProcStat parsed = ProcStat.parse(stat, expectedPid);
    return parsed == null ? null : new WebPidIdentity(parsed.pid, parsed.started, parsed.state);
  }

  public boolean exited() {
    return ProcStat.exited(state);
  }

  public String record() {
    return pid + " " + started;
  }

  public boolean matches(String record) {
    return record != null && record().equals(record.trim());
  }

  public boolean sameProcess(WebPidIdentity other) {
    return other != null && pid == other.pid && started == other.started;
  }
}
