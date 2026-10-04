package com.deepseekharness.app.util;

/** 只识别传入的 Android Process 与对应 /proc 条目；解析失败时禁止按 PID 发送信号。 */
public final class ProcessIdentity {
  public final int pid, parent, group, session;
  public final char state;
  public final long started;

  private ProcessIdentity(int pid, int parent, long started, int group, int session, char state) {
    this.pid = pid;
    this.parent = parent;
    this.started = started;
    this.group = group;
    this.session = session;
    this.state = state;
  }

  public static int androidPid(String type, String description) {
    if (!"java.lang.UNIXProcess".equals(type)
        && !"java.lang.ProcessImpl".equals(type)
        && !"java.lang.ProcessManager$ProcessImpl".equals(type)) return -1;
    if (description == null || !description.startsWith("Process[pid=")) return -1;
    int end = description.indexOf(',', 12);
    if (end < 0) return -1;
    try {
      String value = description.substring(12, end).trim();
      if (!value.matches("[1-9][0-9]{0,9}")) return -1;
      int pid = Integer.parseInt(value);
      return pid > 1 ? pid : -1;
    } catch (RuntimeException invalid) {
      return -1;
    }
  }

  public static ProcessIdentity fromStat(String stat, int pid, int owner) {
    if (pid <= 1 || pid == owner || owner <= 1 || stat == null) return null;
    ProcessIdentity value = parse(stat, pid);
    return value != null && value.parent == owner ? value : null;
  }

  /** PTY 通过 setsid 创建会话；子进程即使有独立作业组也必须属于这次会话。 */
  public static ProcessIdentity inSession(String stat, int pid, int session) {
    if (session <= 1) return null;
    ProcessIdentity value = parse(stat, pid);
    return value != null && value.session == session ? value : null;
  }

  private static ProcessIdentity parse(String stat, int pid) {
    ProcStat value = ProcStat.parse(stat, pid);
    return value == null || value.parent <= 0
        ? null
        : new ProcessIdentity(
            value.pid, value.parent, value.started, value.group, value.session, value.state);
  }

  public boolean sameProcess(ProcessIdentity other) {
    return other != null && pid == other.pid && parent == other.parent && started == other.started;
  }

  /** 只有本次亲子进程自行创建的独立会话，才允许对整个安装进程组发信号。 */
  public boolean ownsSession() {
    return group == pid && session == pid;
  }

  public boolean exited() {
    return ProcStat.exited(state);
  }

  public static boolean isProot(String executable) {
    if (executable == null) return false;
    if (executable.endsWith(" (deleted)"))
      executable = executable.substring(0, executable.length() - 10);
    String name = executable.substring(executable.lastIndexOf('/') + 1);
    return name.equals("libproot.so") || name.equals("libproot_legacy.so");
  }
}
