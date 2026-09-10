package com.deepseekharness.app.util;

/** Web PID 的内核启动时刻；同一数字被复用时不能沿用旧进程身份。 */
public final class WebPidIdentity {
    public final int pid;
    public final long started;
    public final char state;
    private WebPidIdentity(int pid, long started, char state) { this.pid = pid; this.started = started; this.state = state; }
    public static WebPidIdentity parse(String stat, int expectedPid) {
        if (stat == null || expectedPid <= 1) return null;
        int open = stat.indexOf(" ("), close = stat.lastIndexOf(')');
        if (open < 1 || close <= open) return null;
        try {
            if (Integer.parseInt(stat.substring(0, open)) != expectedPid) return null;
            String[] fields = stat.substring(close + 1).trim().split("\\s+");
            if (fields.length < 20 || fields[0].length() != 1) return null;
            long started = Long.parseLong(fields[19]);
            return started > 0 ? new WebPidIdentity(expectedPid, started, fields[0].charAt(0)) : null;
        } catch (NumberFormatException error) { return null; }
    }
    public boolean exited() { return state == 'Z' || state == 'X' || state == 'x'; }
    public String record() { return pid + " " + started; }
    public boolean matches(String record) { return record != null && record().equals(record.trim()); }
    public boolean sameProcess(WebPidIdentity other) { return other != null && pid == other.pid && started == other.started; }
}
