package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** F3 页签状态：未知保存键退回第一项，切换只接受已知键。 */
public class CardTabStateTest {

  @Test
  public void unknownSavedKeyFallsBackToFirstTab() {
    CardTabState state = new CardTabState(List.of("status", "logs"), "missing");
    assertEquals("status", state.selectedKey());
    assertEquals(0, state.selectedIndex());
  }

  @Test
  public void savedKeyIsRestored() {
    CardTabState state = new CardTabState(List.of("status", "logs"), "logs");
    assertEquals("logs", state.selectedKey());
    assertEquals(1, state.selectedIndex());
  }

  @Test
  public void switchingIgnoresUnknownKeysAndRepeats() {
    CardTabState state = new CardTabState(List.of("a", "b"), null);
    assertTrue(state.select("b"));
    assertFalse(state.select("b"));
    assertFalse(state.select("c"));
    assertFalse(state.select(-1));
    assertFalse(state.select(9));
    assertEquals("b", state.selectedKey());
  }

  @Test
  public void emptyTabsHaveNoSelection() {
    CardTabState state = new CardTabState(List.of(), "a");
    assertNull(state.selectedKey());
    assertEquals(-1, state.selectedIndex());
    assertFalse(state.select("a"));
  }
}
