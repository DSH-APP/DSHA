package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

/** 敏感界面分档策略（T119）：默认 ASK、HANDOFF 可配、名单默认空、严格解码、修订号单调。 */
public final class SensitiveScreenPolicyTest {
  @Test
  public void defaultsAskEveryCategoryWithEmptyLists() {
    var policy = SensitiveScreenPolicy.defaults();
    assertEquals(0, policy.revision());
    for (var category : SensitiveScreenPolicy.Category.values()) {
      assertEquals(SensitiveScreenPolicy.Mode.ASK, policy.mode(category));
      assertEquals(SensitiveScreenPolicy.Decision.ASK, policy.decide(category, "com.example.app"));
    }
    assertTrue(policy.trustApps().isEmpty());
    assertTrue(policy.handoffApps().isEmpty());
    // 没有类别时什么都不做；不会凭空制造一次确认。
    assertEquals(SensitiveScreenPolicy.Decision.ALLOW, policy.decide(null, "com.example.app"));
  }

  @Test
  public void offOnlyDisablesTieredHandlingNotPackageLists() {
    var policy =
        SensitiveScreenPolicy.defaults()
            .withMode(SensitiveScreenPolicy.Category.PAYMENT, SensitiveScreenPolicy.Mode.OFF);
    assertEquals(
        SensitiveScreenPolicy.Decision.ALLOW,
        policy.decide(SensitiveScreenPolicy.Category.PAYMENT, "com.bank.app"));
    // 显式名单优先于类别 OFF：用户主动列出的应用仍交接。
    var withHandoff = policy.withApps(Set.of(), Set.of("com.example.shop"));
    assertEquals(
        SensitiveScreenPolicy.Decision.HANDOFF,
        withHandoff.decide(SensitiveScreenPolicy.Category.PAYMENT, "com.example.shop"));
    // OFF 只影响该类别；其它类别默认询问。
    assertEquals(
        SensitiveScreenPolicy.Decision.ASK,
        policy.decide(SensitiveScreenPolicy.Category.OTP, "com.example.app"));
  }

  @Test
  public void handoffModeAndPackageListsBecomeDecisions() {
    var policy =
        SensitiveScreenPolicy.defaults()
            .withMode(SensitiveScreenPolicy.Category.PAYMENT, SensitiveScreenPolicy.Mode.HANDOFF);
    assertEquals(1, policy.revision());
    assertEquals(
        SensitiveScreenPolicy.Decision.HANDOFF,
        policy.decide(SensitiveScreenPolicy.Category.PAYMENT, "com.bank.app"));
    assertEquals(
        SensitiveScreenPolicy.Decision.ASK,
        policy.decide(SensitiveScreenPolicy.Category.OTP, "com.bank.app"));

    var withLists = policy.withApps(Set.of("com.example.trusted"), Set.of("com.example.bank"));
    assertEquals(
        SensitiveScreenPolicy.Decision.ALLOW,
        withLists.decide(SensitiveScreenPolicy.Category.PASSWORD, "com.example.trusted"));
    assertEquals(
        SensitiveScreenPolicy.Decision.HANDOFF,
        withLists.decide(SensitiveScreenPolicy.Category.OTP, "com.example.bank"));
    assertTrue(withLists.revision() > policy.revision());
  }

  @Test
  public void encodeDecodeRoundTripsAndMalformedDataFailsBackToAsk() {
    var policy =
        SensitiveScreenPolicy.defaults()
            .withMode(
                SensitiveScreenPolicy.Category.SYSTEM_SECURITY, SensitiveScreenPolicy.Mode.HANDOFF)
            .withMode(
                SensitiveScreenPolicy.Category.SECURE_UNKNOWN, SensitiveScreenPolicy.Mode.HANDOFF)
            .withMode(SensitiveScreenPolicy.Category.OTP, SensitiveScreenPolicy.Mode.OFF)
            .withApps(Set.of("com.example.trusted"), Set.of("com.example.bank"));
    var decoded = SensitiveScreenPolicy.decode(policy.encode());
    assertEquals(policy.encode(), decoded.encode());
    assertEquals(policy.revision(), decoded.revision());

    for (String malformed :
        new String[] {
          null,
          "",
          "2\n0\nPASSWORD=ASK\nOTP=ASK\nPAYMENT=ASK\nSYSTEM_SECURITY=ASK\nSECURE_UNKNOWN=ASK\n",
          "1\n0\nPASSWORD=HANDOFF\nOTP=ASK\nPAYMENT=ASK\nSYSTEM_SECURITY=ASK\n",
          "1\n0\nPASSWORD=BOGUS\nOTP=ASK\nPAYMENT=ASK\nSYSTEM_SECURITY=ASK\nSECURE_UNKNOWN=ASK\n",
          "1\n0\nPASSWORD=ASK\nOTP=ASK\nPAYMENT=ASK\nSYSTEM_SECURITY=ASK\nSECURE_UNKNOWN=ASK\nX=com.example\n",
          "1\n0\nPASSWORD=ASK\nOTP=ASK\nPAYMENT=ASK\nSYSTEM_SECURITY=ASK\nSECURE_UNKNOWN=ASK\nT=not a package\n",
          "1\n0\nPASSWORD=ASK\nOTP=ASK\nPAYMENT=ASK\nSYSTEM_SECURITY=ASK\nSECURE_UNKNOWN=ASK\nT=com.example\nH=com.example\n"
        })
      assertEquals(
          malformed,
          SensitiveScreenPolicy.Mode.ASK,
          SensitiveScreenPolicy.decode(malformed).mode(SensitiveScreenPolicy.Category.PAYMENT));
  }

  @Test
  public void appListsAreBoundedExactAndMutuallyExclusive() {
    var trust = new LinkedHashSet<String>();
    for (int i = 0; i < SensitiveScreenPolicy.MAX_APPS_PER_LIST; i++)
      trust.add("com.example.app" + i);
    var policy = SensitiveScreenPolicy.defaults().withApps(trust, Set.of());
    assertEquals(SensitiveScreenPolicy.MAX_APPS_PER_LIST, policy.trustApps().size());

    trust.add("com.example.overflow");
    try {
      SensitiveScreenPolicy.defaults().withApps(trust, Set.of());
      fail("over limit must be rejected");
    } catch (IllegalArgumentException expected) {
      // bounded, never silently truncated.
    }
    try {
      SensitiveScreenPolicy.defaults()
          .withApps(Set.of("com.example.same"), Set.of("com.example.same"));
      fail("same package in both lists must be rejected");
    } catch (IllegalArgumentException expected) {
      // explicit conflict, not a silent precedence rule.
    }
    try {
      SensitiveScreenPolicy.defaults().withApps(Set.of("Invalid Package"), Set.of());
      fail("invalid package must be rejected");
    } catch (IllegalArgumentException expected) {
      // exact package names only.
    }
  }

  @Test
  public void resetKeepsRevisionMonotonic() {
    var configured =
        SensitiveScreenPolicy.defaults()
            .withMode(SensitiveScreenPolicy.Category.OTP, SensitiveScreenPolicy.Mode.HANDOFF)
            .withApps(Set.of("com.example.trusted"), Set.of());
    var reset = configured.reset();
    for (var category : SensitiveScreenPolicy.Category.values())
      assertEquals(SensitiveScreenPolicy.Mode.ASK, reset.mode(category));
    assertTrue(reset.trustApps().isEmpty());
    assertTrue(reset.handoffApps().isEmpty());
    assertTrue("reset must not reuse an old revision", reset.revision() > configured.revision());
  }
}
