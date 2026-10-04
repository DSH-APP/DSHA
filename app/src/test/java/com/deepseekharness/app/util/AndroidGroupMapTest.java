package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class AndroidGroupMapTest {
  @Test
  public void actualUserAndUidReplaceDevelopmentPhoneValues() {
    String value = AndroidGroupMap.appendMissing("root:x:0:\n", 1012345, List.of(99909997, 512345));
    assertTrue(value.contains("u10_a2345:x:1012345:"));
    assertTrue(value.contains("aid_99909997:x:99909997:"));
    assertFalse(value.contains("all_a428"));
    assertFalse(value.contains("u0_a428"));
    assertEquals(value, AndroidGroupMap.appendMissing(value, 1012345, List.of(99909997, 512345)));
  }

  @Test
  public void realGidFieldAndUserNamesArePreserved() {
    String original = "inet:x:1234:\nuser:x:3003:\n";
    String updated = AndroidGroupMap.appendMissing(original, 10234, List.of());
    assertTrue(updated.startsWith(original));
    assertEquals(1, updated.split(":3003:", -1).length - 1);
    assertFalse(updated.contains("inet:x:3003:"));
  }

  @Test
  public void supplementaryParserIsBoundedToGroupsAndRejectsInvalidValues() {
    assertEquals(
        List.of(1007, 99909997),
        AndroidGroupMap.groups("Uid: 999\nGroups: 1007 99909997 1007 -1 nope 2147483647\nMore: 1"));
  }

  @Test
  public void primaryUidOutsideOrdinaryAppRangeStillHasAnHonestGenericMapping() {
    assertTrue(
        AndroidGroupMap.appendMissing("root:x:0:\n", 20234, List.of())
            .contains("aid_20234:x:20234:"));
  }
}
