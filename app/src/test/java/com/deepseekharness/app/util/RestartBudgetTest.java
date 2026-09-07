package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class RestartBudgetTest {
    @Test public void repeatedCrashAndDuplicateCallbacksTripOncePerGeneration() {
        RestartBudget b = new RestartBudget(0);
        for (int i = 1; i <= 3; i++) {
            b.begin(i, i == 1); assertTrue(b.fail(i)); assertFalse(b.fail(i));
            assertFalse(b.healthy(i, 999999)); assertEquals(i, b.failures());
        }
        assertTrue(b.blocked());
        RestartBudget restored = new RestartBudget(b.failures()); assertTrue(restored.blocked());
        restored.begin(10, true); assertFalse(restored.blocked());
    }
    @Test public void shortSuccessAndIntermittentHealthDoNotResetBudget() {
        RestartBudget b = new RestartBudget(2); b.begin(4, false);
        assertFalse(b.healthy(4, 100)); assertFalse(b.healthy(4, 119999));
        b.unhealthy(4); assertFalse(b.healthy(4, 120100));
        assertFalse(b.healthy(3, 250000)); assertEquals(2, b.failures());
        assertTrue(b.healthy(4, 240100)); assertEquals(0, b.failures());
        assertFalse(b.fail(3)); assertTrue(b.fail(4));
    }
}
