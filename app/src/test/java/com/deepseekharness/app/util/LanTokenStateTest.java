package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public class LanTokenStateTest {
  @Test
  public void generatorMustProduceTheActualUrlSafe256BitLength() {
    LanTokenState state = new LanTokenState();
    for (String value : new String[] {"", "a".repeat(42), "a".repeat(44), "a".repeat(42) + "+"})
      assertThrows(IllegalArgumentException.class, () -> state.ensure(() -> value));
    assertEquals("", state.current());
    assertEquals("a".repeat(43), state.ensure(() -> "a".repeat(43)));
  }
}
