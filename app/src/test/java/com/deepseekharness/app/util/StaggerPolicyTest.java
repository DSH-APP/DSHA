package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** F1 错峰闸门：前 8 个不同条目各一次，滚动回头不重放。 */
public class StaggerPolicyTest {

  @Test
  public void onlyFirstItemsAreClaimedOnce() {
    StaggerPolicy policy = new StaggerPolicy(8);
    for (int index = 0; index < 8; index++) assertTrue(policy.claim("item-" + index));
    assertFalse(policy.claim("item-8"));
    assertEquals(8, policy.count());
    // 已经播过的不再占用新名额，也不重播。
    assertFalse(policy.claim("item-0"));
    assertTrue(policy.revealed("item-0"));
  }

  @Test
  public void laterScrollBackDoesNotReplay() {
    StaggerPolicy policy = new StaggerPolicy(8);
    assertTrue(policy.claim("a"));
    assertFalse(policy.claim("a"));
    assertFalse(policy.revealed("b"));
  }

  @Test
  public void nullKeysAndZeroCapacityAreIgnored() {
    StaggerPolicy policy = new StaggerPolicy(8);
    assertFalse(policy.claim(null));
    StaggerPolicy disabled = new StaggerPolicy(0);
    assertFalse(disabled.claim("a"));
  }

  @Test
  public void resetAllowsANewFirstReveal() {
    StaggerPolicy policy = new StaggerPolicy(2);
    assertTrue(policy.claim("a"));
    policy.reset();
    assertTrue(policy.claim("a"));
  }
}
