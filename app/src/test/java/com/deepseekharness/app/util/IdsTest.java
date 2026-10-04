package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class IdsTest {
  @Test
  public void acceptsHistoricalFivePartUuidAndRejectsMissingSegment() {
    assertTrue(Ids.uuid("11111111-1111-4111-8111-111111111111"));
    assertFalse(Ids.uuid("11111111-1111-4111-111111111111"));
    assertFalse(Ids.uuid("FFFFFFFF-1111-4111-8111-111111111111"));
    assertFalse(Ids.uuid(null));
    assertFalse(Ids.uuid("../11111111-1111-4111-8111-111111111111"));
  }
}
