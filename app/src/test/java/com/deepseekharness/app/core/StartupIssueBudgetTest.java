package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class StartupIssueBudgetTest {
  @Test
  public void oneThousandChangingIssuesRemainBoundedAndStaleCallbacksCannotWrite() {
    StartupIssueBudget budget = new StartupIssueBudget();
    budget.begin(1);
    int writes = 0;
    for (int i = 0; i < 1000; i++)
      if (budget.record(1, "plugin-" + i % 100, "error-" + i)) writes++;
    assertEquals(StartupIssueBudget.MAX_PLUGINS * StartupIssueBudget.MAX_PER_PLUGIN, writes);
    budget.begin(2);
    assertFalse(budget.record(1, "plugin", "old-generation"));
    assertTrue(budget.record(2, "plugin", "new-generation"));
    budget.begin(0);
    assertFalse(budget.record(2, "plugin", "after-reset"));
  }

  @Test
  public void identicalMessagesDoNotConsumeBudgetAndANewRunResetsTheLimit() {
    StartupIssueBudget budget = new StartupIssueBudget();
    budget.begin(1);
    assertTrue(budget.record(1, "plugin", "same"));
    for (int i = 0; i < 1000; i++) assertFalse(budget.record(1, "plugin", "same"));
    assertTrue(budget.record(1, "plugin", "second"));
    assertTrue(budget.record(1, "plugin", "third"));
    assertFalse(budget.record(1, "plugin", "fourth"));
    budget.begin(2);
    assertTrue(budget.record(2, "plugin", "same"));
  }
}
