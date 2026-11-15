package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class RegisterBudgetTest {
  private static final String ASSET = "a".repeat(64);
  private static final String OTHER = "b".repeat(64);

  @Test
  public void sameManagedAssetKeepsNormalBudget() {
    assertEquals(RegisterBudget.NORMAL_MS, RegisterBudget.timeoutMs(ASSET, ASSET));
  }

  @Test
  public void firstStartOrUnknownIdentityUsesWideBudget() {
    assertEquals(RegisterBudget.FIRST_START_MS, RegisterBudget.timeoutMs(ASSET, OTHER));
    assertEquals(RegisterBudget.FIRST_START_MS, RegisterBudget.timeoutMs(null, ASSET));
    assertEquals(RegisterBudget.FIRST_START_MS, RegisterBudget.timeoutMs(ASSET, null));
    assertEquals(RegisterBudget.FIRST_START_MS, RegisterBudget.timeoutMs("", ""));
    assertEquals(
        RegisterBudget.FIRST_START_MS, RegisterBudget.timeoutMs("not-a-hash", "not-a-hash"));
  }

  @Test
  public void identityMustBeSixtyFourLowercaseHexAndIsTrimmed() {
    assertEquals(ASSET, RegisterBudget.identity(" " + ASSET + "\n"));
    assertNull(RegisterBudget.identity(ASSET.toUpperCase(java.util.Locale.ROOT)));
    assertNull(RegisterBudget.identity(ASSET.substring(1)));
  }

  @Test
  public void firstStartBudgetIsStrictlyWiderThanNormal() {
    assertTrue(RegisterBudget.FIRST_START_MS > RegisterBudget.NORMAL_MS);
    assertEquals(90_000L, RegisterBudget.NORMAL_MS);
    assertEquals(240_000L, RegisterBudget.FIRST_START_MS);
  }
}
