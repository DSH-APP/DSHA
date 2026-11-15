package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class RetainedFilterTest {
  @Test
  public void allMatchesEverything() {
    for (String kind : new String[] {"BACKUP", "PRESET", "NEW_KIND", null})
      assertTrue(RetainedFilter.matches(RetainedFilter.ALL, kind));
  }

  @Test
  public void groupsKnownKinds() {
    assertEquals(RetainedFilter.BACKUP, RetainedFilter.group("BACKUP"));
    assertEquals(RetainedFilter.BACKUP, RetainedFilter.group("RESTORE"));
    assertEquals(RetainedFilter.ENVIRONMENT, RetainedFilter.group("ENVIRONMENT"));
    assertEquals(RetainedFilter.ENVIRONMENT, RetainedFilter.group("RUNTIME"));
    assertEquals(RetainedFilter.PLUGIN, RetainedFilter.group("PLUGIN"));
    assertEquals(RetainedFilter.PLUGIN, RetainedFilter.group("QUARANTINE"));
    for (String kind : new String[] {"SETTINGS", "PRESET", "MIGRATION"})
      assertEquals(RetainedFilter.OTHER, RetainedFilter.group(kind));
  }

  @Test
  public void unknownKindsStayVisibleUnderOther() {
    assertTrue(RetainedFilter.matches(RetainedFilter.OTHER, "FUTURE_KIND"));
    assertTrue(RetainedFilter.matches(RetainedFilter.OTHER, null));
    assertFalse(RetainedFilter.matches(RetainedFilter.PLUGIN, "FUTURE_KIND"));
  }

  @Test
  public void everyKnownKindBelongsToExactlyOneGroup() {
    for (String kind :
        new String[] {
          "BACKUP",
          "ENVIRONMENT",
          "RUNTIME",
          "RESTORE",
          "PLUGIN",
          "QUARANTINE",
          "SETTINGS",
          "PRESET",
          "MIGRATION"
        }) {
      int hits = 0;
      for (int f = 1; f < RetainedFilter.COUNT; f++) if (RetainedFilter.matches(f, kind)) hits++;
      assertEquals(kind, 1, hits);
    }
  }
}
