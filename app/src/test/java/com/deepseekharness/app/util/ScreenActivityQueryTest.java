package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/** history 查询参数：默认值、边界与稳定错误码；不猜测或修正非法输入。 */
public final class ScreenActivityQueryTest {
  @Test
  public void defaultsWhenParametersAreMissing() {
    assertEquals(new ScreenActivityQuery.Parsed(0L, 20), ScreenActivityQuery.parse(null, null));
    assertEquals(new ScreenActivityQuery.Parsed(0L, 20), ScreenActivityQuery.parse("", ""));
  }

  @Test
  public void acceptsSequenceAndBoundedLimit() {
    assertEquals(new ScreenActivityQuery.Parsed(12L, 100), ScreenActivityQuery.parse("12", "100"));
    assertEquals(
        new ScreenActivityQuery.Parsed(9_007_199_254_740_991L, 1),
        ScreenActivityQuery.parse("9007199254740991", "1"));
    assertEquals(new ScreenActivityQuery.Parsed(7L, 20), ScreenActivityQuery.parse(" 7 ", "  "));
  }

  @Test
  public void rejectsInvalidSinceWithItsOwnCode() {
    for (String value :
        new String[] {"-1", "1.5", "abc", "9007199254740992", "1x", "+1", "0x10", "1 2"}) {
      try {
        ScreenActivityQuery.parse(value, null);
        fail("invalid since accepted: " + value);
      } catch (IllegalArgumentException expected) {
        assertEquals("SCREEN_HISTORY_INVALID_SINCE", expected.getMessage());
      }
    }
  }

  @Test
  public void rejectsInvalidLimitWithItsOwnCode() {
    for (String value : new String[] {"0", "101", "-1", "abc", "1.0", "1x", "1000"}) {
      try {
        ScreenActivityQuery.parse(null, value);
        fail("invalid limit accepted: " + value);
      } catch (IllegalArgumentException expected) {
        assertEquals("SCREEN_HISTORY_INVALID_LIMIT", expected.getMessage());
      }
    }
  }
}
