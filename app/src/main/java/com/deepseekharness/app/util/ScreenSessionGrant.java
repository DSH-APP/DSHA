package com.deepseekharness.app.util;

/** 授权绑定本次运行；撤销后，旧弹窗结果也不能重新授权。 */
public final class ScreenSessionGrant {
    private long generation = -1, revision;
    public synchronized long revision() { return revision; }
    public synchronized boolean allowed(long current) { return current > 0 && generation == current; }
    public synchronized boolean accept(long current, long observedRevision) {
        if (current <= 0 || observedRevision != revision) return false;
        generation = current;
        return true;
    }
    public synchronized void revoke() { generation = -1; revision++; }
}
