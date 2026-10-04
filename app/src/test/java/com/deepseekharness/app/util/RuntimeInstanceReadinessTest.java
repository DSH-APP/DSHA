package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.deepseekharness.app.util.RuntimeInstanceReadiness.Identity.*;
import static com.deepseekharness.app.util.RuntimeInstanceReadiness.Result.*;

public class RuntimeInstanceReadinessTest {
  @Test
  public void verifiedStartupWithUnreadableProcAllowsOnlyReadOnlyEntry() {
    assertEquals(
        READY_READ_ONLY, RuntimeInstanceReadiness.decide(true, true, true, true, UNAVAILABLE));
    assertEquals(READY, RuntimeInstanceReadiness.decide(true, true, true, true, VERIFIED));
  }

  @Test
  public void failedEvidenceNeverObtainsTheFallback() {
    for (RuntimeInstanceReadiness.Identity identity : RuntimeInstanceReadiness.Identity.values()) {
      assertEquals(BLOCKED, RuntimeInstanceReadiness.decide(false, true, true, true, identity));
      assertEquals(BLOCKED, RuntimeInstanceReadiness.decide(true, false, true, true, identity));
      assertEquals(BLOCKED, RuntimeInstanceReadiness.decide(true, true, false, true, identity));
      assertEquals(BLOCKED, RuntimeInstanceReadiness.decide(true, true, true, false, identity));
    }
  }

  @Test
  public void malformedOrMissingIdentityIsNotPermissionDenial() {
    assertEquals(BLOCKED, RuntimeInstanceReadiness.decide(true, true, true, true, INVALID));
    assertEquals(BLOCKED, RuntimeInstanceReadiness.decide(true, true, true, true, null));
  }
}
