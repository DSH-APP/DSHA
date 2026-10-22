package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Map;

public final class ScreenConfirmationPolicyTest {
  @Test
  public void allModesCoverOrdinarySensitiveAndUnknownPackages() {
    for (String pkg : new String[] {"app.normal", "com.bank.pay", ""}) {
      assertFalse(ScreenConfirmationPolicy.manual().allows(pkg));
      assertTrue(
          new ScreenConfirmationPolicy(ScreenConfirmationPolicy.Mode.ALLOW_ALL, Map.of(), 1)
              .allows(pkg));
    }
    var custom =
        new ScreenConfirmationPolicy(
            ScreenConfirmationPolicy.Mode.CUSTOM,
            Map.of(
                "com.bank.pay",
                ScreenConfirmationPolicy.Rule.ALLOW,
                "app.normal",
                ScreenConfirmationPolicy.Rule.ASK),
            2);
    assertTrue(custom.allows("com.bank.pay"));
    for (String pkg :
        new String[] {"app.normal", "com.bank.pay.clone", "com.Bank.pay", "unknown.pkg", ""})
      assertFalse(custom.allows(pkg));
  }

  @Test
  public void rulesPersistButDoNotCreateRunAuthority() {
    var original =
        new ScreenConfirmationPolicy(
            ScreenConfirmationPolicy.Mode.CUSTOM,
            Map.of("com.bank.pay", ScreenConfirmationPolicy.Rule.ALLOW),
            17);
    var restored = ScreenConfirmationPolicy.decode(original.encode());
    assertEquals(17, restored.revision);
    assertTrue(restored.allows("com.bank.pay"));
    assertFalse(new ScreenSessionGrant().allowed(9));
  }

  @Test
  public void futureCorruptDuplicateAndInvalidRulesFailClosed() {
    for (String value :
        new String[] {
          null,
          "",
          "3\nALLOW_ALL\n1",
          "2\nALLOW_ALL\n1\n",
          "1\nALLOW_ALL\n-1\n",
          "1\nCUSTOM\n1\napp.a=ALLOW\napp.a=ASK\n",
          "1\nCUSTOM\n1\n*=ALLOW\n",
          "1\nALLOW_ALL\n1",
          "1\nBOGUS\n1\n"
        }) {
      var policy = ScreenConfirmationPolicy.decode(value);
      assertEquals(ScreenConfirmationPolicy.Mode.MANUAL, policy.mode);
      assertFalse(policy.allows("app.a"));
    }
  }

  @Test
  public void newEncodingHasNoFinalNewlineWithOrWithoutRules() {
    for (var mode : ScreenConfirmationPolicy.Mode.values()) {
      for (var rules :
          java.util.List.of(
              Map.<String, ScreenConfirmationPolicy.Rule>of(),
              Map.of(
                  "app.normal",
                  ScreenConfirmationPolicy.Rule.ASK,
                  "com.bank.pay",
                  ScreenConfirmationPolicy.Rule.ALLOW))) {
        var policy = new ScreenConfirmationPolicy(mode, rules, 17);
        assertTrue(policy.encode().startsWith("2\n"));
        assertFalse(policy.encode().endsWith("\n"));
        var decoded = ScreenConfirmationPolicy.decode(policy.encode());
        assertEquals(mode, decoded.mode);
        assertEquals(rules, decoded.rules);
        assertEquals(17, decoded.revision);
      }
    }
  }

  @Test
  public void strictValidLegacyModeAndRulesRemainReadable() {
    for (var mode : ScreenConfirmationPolicy.Mode.values()) {
      var empty = ScreenConfirmationPolicy.decode("1\n" + mode + "\n7\n");
      assertEquals(mode, empty.mode);
      assertEquals(7, empty.revision);
      var populated =
          ScreenConfirmationPolicy.decode(
              "1\n" + mode + "\n8\napp.normal=ASK\ncom.bank.pay=ALLOW\n");
      assertEquals(mode, populated.mode);
      assertEquals(8, populated.revision);
      assertEquals(
          Map.of(
              "app.normal",
              ScreenConfirmationPolicy.Rule.ASK,
              "com.bank.pay",
              ScreenConfirmationPolicy.Rule.ALLOW),
          populated.rules);
    }
  }

  @Test
  public void legacyXmlIndentationNeverRestoresAllowAllOrCustomRules() {
    for (String value :
        new String[] {
          "1\nALLOW_ALL\n7\n    ", "1\nCUSTOM\n8\napp.normal=ASK\ncom.bank.pay=ALLOW\n    "
        }) {
      var decoded = ScreenConfirmationPolicy.decode(value);
      assertEquals(ScreenConfirmationPolicy.Mode.MANUAL, decoded.mode);
      assertEquals(0, decoded.revision);
      assertTrue(decoded.rules.isEmpty());
      assertFalse(decoded.allows("com.bank.pay"));
    }
  }
}
