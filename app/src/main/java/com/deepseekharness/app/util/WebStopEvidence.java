package com.deepseekharness.app.util;

/** 停止屏障只接受明确退出或编号已复用的证据；不可读不等于不存在。 */
public final class WebStopEvidence {
    private WebStopEvidence() { }
    public enum Kind { GONE, WEB, OTHER, DENIED }

    public static boolean mayRetire(Kind kind, boolean differentUid, String saved, WebPidIdentity current) {
        if (kind == Kind.GONE || differentUid) return true;
        return current != null && saved != null && saved.matches(current.pid + " [1-9][0-9]*")
                && !current.matches(saved);
    }

    /** 全局扫描允许跳过已读明的无关命令；本 UID 或归属未知的 DENIED 保持屏障。 */
    public static boolean scanUnconfirmed(Kind kind, boolean differentUid) {
        return !differentUid && (kind == Kind.WEB || kind == Kind.DENIED);
    }
}
