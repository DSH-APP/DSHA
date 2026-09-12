package com.deepseekharness.app.util;

/** 页面无所有权的任务状态；等待确认也占用任务，旧页面不能确认下一次任务。 */
public final class BackupTaskState {
    public enum Status { IDLE, RUNNING, PREVIEW, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }
    private long id;
    private String kind = "", detail = "";
    private Status status = Status.IDLE;

    public synchronized long start(String kind) {
        if (busy()) return -1;
        id = Math.max(System.currentTimeMillis(), id + 1);
        this.kind = kind; status = Status.RUNNING; detail = com.deepseekharness.app.util.UiText.text("正在准备…");
        return id;
    }
    public synchronized boolean busy() { return status == Status.RUNNING || status == Status.PREVIEW; }
    public synchronized void update(long expected, Status next, String message) {
        if (expected != id || !busy()) return;
        status = next; detail = SensitiveData.redact(message == null ? "" : message);
    }
    public synchronized boolean confirm(long expected) {
        if (id != expected || status != Status.PREVIEW) return false;
        status = Status.RUNNING; detail = com.deepseekharness.app.util.UiText.text("正在恢复…"); return true;
    }
    public synchronized Snapshot snapshot() { return new Snapshot(id, kind, status, detail); }
    public synchronized void restore(long id, String kind, Status status, String detail) {
        this.id = id; this.kind = kind; this.status = status;
        this.detail = SensitiveData.redact(detail);
        if (busy()) {
            this.status = Status.INTERRUPTED;
            this.detail = com.deepseekharness.app.util.UiText.text("上次任务因进程退出而中断，未自动重试。请先处理未完成的维护或重新选择备份。\n") + this.detail;
        }
    }
    public static final class Snapshot {
        public final long id;
        public final String kind, detail;
        public final Status status;
        Snapshot(long id, String kind, Status status, String detail) {
            this.id = id; this.kind = kind; this.status = status; this.detail = detail;
        }
        public boolean busy() { return status == Status.RUNNING || status == Status.PREVIEW; }
    }
}
