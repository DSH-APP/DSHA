package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class StorageUsageTest {
  private static Map<String, Long> sizes(Object... pairs) {
    Map<String, Long> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (Long) pairs[i + 1]);
    return map;
  }

  @Test
  public void sortsDescendingAndIgnoresEmpty() {
    var result = StorageUsage.segments(sizes("cache", 10L, "linux", 90L, "mozilla", 0L), 6);
    assertEquals(2, result.size());
    assertEquals("linux", result.get(0).key);
    assertEquals(0.9, result.get(0).fraction, 1e-9);
    assertEquals("cache", result.get(1).key);
  }

  @Test
  public void overflowMergesIntoOther() {
    var result = StorageUsage.segments(sizes("a", 50L, "b", 30L, "c", 15L, "d", 5L), 3);
    assertEquals(3, result.size());
    assertEquals("a", result.get(0).key);
    assertEquals("b", result.get(1).key);
    assertNull(result.get(2).key);
    assertEquals(20L, result.get(2).bytes);
  }

  @Test
  public void exactSlotCountHasNoOther() {
    var result = StorageUsage.segments(sizes("a", 2L, "b", 1L), 2);
    assertEquals(2, result.size());
    assertNotNull(result.get(1).key);
  }

  @Test
  public void tiesAreStableByKey() {
    var result = StorageUsage.segments(sizes("z", 5L, "a", 5L), 6);
    assertEquals("a", result.get(0).key);
  }

  @Test
  public void emptyOrNegativeTotalsYieldNoSegments() {
    assertTrue(StorageUsage.segments(sizes("a", 0L, "b", -1L), 6).isEmpty());
    assertEquals(0, StorageUsage.total(sizes("a", -5L)));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsTooFewSlots() {
    StorageUsage.segments(sizes("a", 1L), 1);
  }
}
