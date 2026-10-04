package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenTargetTest {
  @Test
  public void executionTargetCannotChangePackageWindowOrDisplay() {
    var target = new ScreenTarget(2, 17, "fixture.app");
    assertTrue(target.matches(new ScreenTarget(2, 17, "fixture.app")));
    assertFalse(target.matches(new ScreenTarget(0, 17, "fixture.app")));
    assertFalse(target.matches(new ScreenTarget(2, 18, "fixture.app")));
    assertFalse(target.matches(new ScreenTarget(2, 17, "fixture.bank")));
    assertFalse(target.matches(null));
  }

  @Test
  public void missingWindowIsNotAnExecutionIdentityAndUnknownPackagesStaySensitive() {
    assertFalse(new ScreenTarget(2, -1, "").matches(new ScreenTarget(2, -1, "")));
    for (String pkg :
        new String[] {
          "",
          "com.fixture.bank",
          "com.eg.android.AlipayGphone",
          "com.fixture.sms",
          "com.android.systemui",
          "com.fixture.broker"
        }) assertTrue(pkg, SensitiveAppPolicy.sensitive(pkg));
    assertFalse(SensitiveAppPolicy.sensitive("fixture.editor"));
  }
}
