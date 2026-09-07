package com.deepseekharness.app.util;

/** 只识别传入的 Android Process 与对应 /proc 条目；解析失败时禁止按 PID 发送信号。 */
public final class ProcessIdentity {
    public final int pid, parent;
    public final long started;
    private ProcessIdentity(int pid, int parent, long started) { this.pid = pid; this.parent = parent; this.started = started; }

    public static int androidPid(String type, String description) {
        if (!"java.lang.UNIXProcess".equals(type) && !"java.lang.ProcessImpl".equals(type)
                && !"java.lang.ProcessManager$ProcessImpl".equals(type)) return -1;
        if (description == null || !description.startsWith("Process[pid=")) return -1;
        int end = description.indexOf(',', 12);
        if (end < 0) return -1;
        try {
            String value = description.substring(12, end).trim();
            if (!value.matches("[1-9][0-9]{0,9}")) return -1;
            int pid = Integer.parseInt(value); return pid > 1 ? pid : -1;
        } catch (RuntimeException invalid) { return -1; }
    }

    public static ProcessIdentity fromStat(String stat, int pid, int owner) {
        if (pid <= 1 || pid == owner || owner <= 1 || stat == null) return null;
        int open = stat.indexOf(" ("), close = stat.lastIndexOf(')');
        if (open < 1 || close <= open) return null;
        try {
            if (Integer.parseInt(stat.substring(0, open)) != pid) return null;
            String[] fields = stat.substring(close + 1).trim().split("\\s+");
            // ')' 后从字段 3（state）开始；PPID 是字段 4，starttime 是字段 22。
            if (fields.length < 20 || fields[0].length() != 1) return null;
            int parent = Integer.parseInt(fields[1]); long started = Long.parseLong(fields[19]);
            return parent == owner && started > 0 ? new ProcessIdentity(pid, parent, started) : null;
        } catch (RuntimeException invalid) { return null; }
    }
    public boolean sameProcess(ProcessIdentity other) {
        return other != null && pid == other.pid && parent == other.parent && started == other.started;
    }
    public static boolean isProot(String executable) {
        if (executable == null) return false;
        if (executable.endsWith(" (deleted)")) executable = executable.substring(0, executable.length() - 10);
        String name = executable.substring(executable.lastIndexOf('/') + 1);
        return name.equals("libproot.so") || name.equals("libproot_legacy.so");
    }
}
