package com.deepseekharness.app.util;

/** 首次 Cookie 与导航归属于保留页面，Activity 换代不会吞掉唯一一次加载。 */
public final class PreviewNavigation {
    private long ticket;
    private boolean waiting, started, cookieAccepted;
    public long begin(boolean hasCookie) {
        ticket++;
        waiting = hasCookie;
        started = false;
        cookieAccepted = false;
        return ticket;
    }
    public boolean cookieCompleted(long expected, boolean accepted) {
        if (expected != ticket || !waiting || started) return false;
        waiting = false;
        cookieAccepted = accepted;
        return true;
    }
    public boolean claim() {
        if (waiting || started) return false;
        started = true;
        return true;
    }
    public boolean cookieAccepted() { return cookieAccepted; }
    public boolean started() { return started; }
    public void cancel() { ticket++; waiting = false; started = true; }
}
