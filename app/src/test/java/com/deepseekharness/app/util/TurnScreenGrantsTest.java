package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class TurnScreenGrantsTest {
  private final TurnScreenGrants grants = new TurnScreenGrants();

  @Test
  public void ordinaryAppIsAskedOncePerTurn() {
    assertFalse(grants.allows("turn-1", "com.example", false, 3));
    grants.remember("turn-1", "com.example", 3);
    for (int i = 0; i < 50; i++) assertTrue(grants.allows("turn-1", "com.example", false, 3));
    assertFalse("另一个应用仍要确认", grants.allows("turn-1", "com.other", false, 3));
    assertFalse("下一轮重新确认", grants.allows("turn-2", "com.example", false, 3));
  }

  @Test
  public void sensitiveAppAsksAgainEveryFiveActions() {
    grants.remember("turn-1", "com.pay", 3);
    for (int i = 0; i < TurnScreenGrants.SENSITIVE_ACTIONS_PER_CONFIRM; i++)
      assertTrue("第 " + (i + 1) + " 次", grants.allows("turn-1", "com.pay", true, 3));
    assertFalse(grants.allows("turn-1", "com.pay", true, 3));
    assertFalse("用完后保持需要确认", grants.allows("turn-1", "com.pay", true, 3));
    grants.remember("turn-1", "com.pay", 3);
    assertTrue(grants.allows("turn-1", "com.pay", true, 3));
  }

  @Test
  public void policyChangeAndClearInvalidate() {
    grants.remember("turn-1", "com.example", 3);
    assertFalse("确认策略改动后失效", grants.allows("turn-1", "com.example", false, 4));
    grants.remember("turn-1", "com.example", 4);
    grants.clear();
    assertFalse(grants.allows("turn-1", "com.example", false, 4));
  }

  @Test
  public void missingTurnOrPackageNeverGrants() {
    grants.remember(null, "com.example", 1);
    grants.remember("turn-1", "", 1);
    assertFalse(grants.allows(null, "com.example", false, 1));
    assertFalse(grants.allows("turn-1", "", false, 1));
    assertFalse(grants.allows("", "com.example", false, 1));
  }

  @Test
  public void sizeIsBounded() {
    for (int i = 0; i < TurnScreenGrants.MAX_ENTRIES + 10; i++)
      grants.remember("turn-1", "com.app" + i, 1);
    assertFalse("最早的条目被淘汰", grants.allows("turn-1", "com.app0", false, 1));
    assertTrue(grants.allows("turn-1", "com.app" + (TurnScreenGrants.MAX_ENTRIES + 9), false, 1));
  }
}
