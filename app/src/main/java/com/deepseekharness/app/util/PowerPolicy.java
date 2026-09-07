package com.deepseekharness.app.util;

/** 省电只在已知持续空闲时放锁，不主动停止进程。 */
public final class PowerPolicy {
    public static final long IDLE_GRACE_MS = 60_000;
    private long idleSince = -1;
    public boolean keepCpu(boolean eco, boolean webActive, boolean starting, boolean interactive,
                           boolean lan, boolean nativeWork, boolean knownIdle, long now) {
        if (!webActive || !knownIdle || starting || nativeWork || lan || interactive) idleSince = -1;
        else if (idleSince < 0 || now < idleSince) idleSince = now;
        if (!webActive) return false;
        return !eco || starting || interactive || lan || nativeWork || !knownIdle
                || idleSince < 0 || now - idleSince < IDLE_GRACE_MS;
    }
}
