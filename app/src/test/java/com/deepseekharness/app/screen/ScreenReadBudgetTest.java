package com.deepseekharness.app.screen;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class ScreenReadBudgetTest {
  @Test
  public void oneSlowPlatformReturnPreventsTheNextChildExpansion() {
    AtomicLong now = new AtomicLong();
    ScreenReadBudget budget = new ScreenReadBudget(now::get, () -> true);
    assertTrue(budget.enter(0, 0));
    assertTrue(budget.childSlot(0));
    // Represents an already issued Binder read. The budget cannot interrupt this elapsed time.
    now.set(5000);
    assertFalse(budget.enter(1, 0));
    assertFalse(budget.childSlot(0));
    assertEquals(2, budget.visits());
    assertTrue(budget.timedOut());
    assertTrue(budget.exhausted());
  }

  @Test
  public void revocationRefusesTheResultInsteadOfPublishingAPartialAuthorizedTree() {
    AtomicBoolean current = new AtomicBoolean(true);
    ScreenReadBudget budget = new ScreenReadBudget(() -> 0, current::get);
    assertTrue(budget.enter(0, 0));
    current.set(false);
    var refused = assertThrows(IllegalStateException.class, () -> budget.childSlot(0));
    assertEquals("SCREEN_RUN_CHANGED", refused.getMessage());
  }

  @Test
  public void timeBudgetIsMonotonicAndNeverRestartsForEachNode() {
    AtomicLong now = new AtomicLong(100);
    ScreenReadBudget budget = new ScreenReadBudget(now::get, () -> true);
    assertTrue(budget.enter(0, 0));
    now.set(899);
    assertTrue(budget.childSlot(0));
    now.set(900);
    assertFalse(budget.childSlot(0));
    now.set(100);
    assertFalse(budget.childSlot(0));
  }

  @Test
  public void fastSparseTreesStillHaveTheOriginalTotalNodeLimit() {
    ScreenReadBudget budget = new ScreenReadBudget(() -> 0, () -> true);
    int visited = 0;
    while (budget.childSlot(0)) visited++;
    assertEquals(4096, visited);
    assertTrue(budget.exhausted());
    assertFalse(budget.timedOut());
  }
}
