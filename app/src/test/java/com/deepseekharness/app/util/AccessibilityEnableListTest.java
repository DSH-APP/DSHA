package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class AccessibilityEnableListTest {
  private static final String OWN =
      "com.dsh.client.experimental/com.deepseekharness.app.DshaAccessibilityService";

  @Test
  public void existingOtherServicesRemainByteForByte() {
    String before = "com.reader.app/.Reader:com.other.app/org.access.Service";
    assertEquals(before + ":" + OWN, AccessibilityEnableList.addOwn(before, OWN));
  }

  @Test
  public void nullAndEmptyListsOnlyAddOwnService() {
    assertEquals(OWN, AccessibilityEnableList.addOwn(null, OWN));
    assertEquals(OWN, AccessibilityEnableList.addOwn("", OWN));
  }

  @Test
  public void existingOwnServiceDoesNotDuplicateOrReorder() {
    String before = OWN + ":com.reader.app/.Reader";
    assertEquals(before, AccessibilityEnableList.addOwn(before, OWN));
    String relative = "com.dsh.client.experimental/.Service";
    assertEquals(
        relative,
        AccessibilityEnableList.addOwn(
            relative, "com.dsh.client.experimental/com.dsh.client.experimental.Service"));
  }

  @Test
  public void trailingDelimiterIsPreservedWithoutAnExtraEmptyEntry() {
    assertEquals(
        "com.reader.app/.Reader:" + OWN,
        AccessibilityEnableList.addOwn("com.reader.app/.Reader:", OWN));
  }

  @Test(expected = IllegalArgumentException.class)
  public void malformedOwnComponentIsRejected() {
    AccessibilityEnableList.addOwn("com.reader.app/.Reader", OWN + ":other/.Service");
  }

  @Test(expected = IllegalArgumentException.class)
  public void unboundedSystemSettingIsNotRewritten() {
    AccessibilityEnableList.addOwn("x".repeat(65537), OWN);
  }
}
