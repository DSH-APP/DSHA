package com.deepseekharness.app.util;

/** 连续失败预算：同代次只计一次，持续健康后清零；时钟由调用方传入。 */
public final class RestartBudget {
    public static final int LIMIT = 3;
    public static final long STABLE_MS = 120_000;
    private int failures;
    private long generation = -1, failedGeneration = -1, healthySince = -1;

    public RestartBudget(int savedFailures) { failures = Math.max(0, Math.min(LIMIT, savedFailures)); }
    public void begin(long nextGeneration, boolean manual) {
        generation = nextGeneration;
        failedGeneration = -1;
        healthySince = -1;
        if (manual) failures = 0;
    }
    public boolean fail(long expected) {
        if (expected != generation || failedGeneration == expected) return false;
        failedGeneration = expected;
        healthySince = -1;
        failures = Math.min(LIMIT, failures + 1);
        return true;
    }
    public boolean healthy(long expected, long now) {
        if (expected != generation || failedGeneration == expected || blocked()) return false;
        if (healthySince < 0 || now < healthySince) healthySince = now;
        if (failures == 0 || now - healthySince < STABLE_MS) return false;
        failures = 0;
        return true;
    }
    public void unhealthy(long expected) { if (expected == generation) healthySince = -1; }
    public boolean failed(long expected) { return failedGeneration == expected; }
    public int failures() { return failures; }
    public boolean blocked() { return failures >= LIMIT; }
}
