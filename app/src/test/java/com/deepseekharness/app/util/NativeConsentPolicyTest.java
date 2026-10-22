package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class NativeConsentPolicyTest {
  @Test
  public void defaultAndMalformedNeverEnableGlobal() {
    for (String value :
        new String[] {null, "", "true", "1\ntrue\n-1\n", "1\ntrue\n9007199254740992\n"})
      assertFalse(NativeConsentPolicy.decode(value).globalAllow());
  }

  @Test
  public void onOnlyBypassesAskAndPreservesExplicitDenial() {
    var on = new NativeConsentPolicy(true, 1);
    assertEquals(
        NativeConsentPolicy.Decision.ALLOW, on.effective(NativeConsentPolicy.Decision.ASK));
    assertEquals(
        NativeConsentPolicy.Decision.ALLOW, on.effective(NativeConsentPolicy.Decision.ALLOW));
    assertEquals(
        NativeConsentPolicy.Decision.DENY, on.effective(NativeConsentPolicy.Decision.DENY));
  }

  @Test
  public void offRestoresEveryIndividualDecision() {
    var off = new NativeConsentPolicy(false, 2);
    for (var decision : NativeConsentPolicy.Decision.values())
      assertEquals(decision, off.effective(decision));
  }

  @Test
  public void revisionPreventsOffOnAbaAndRoundTrips() {
    var first = new NativeConsentPolicy(false, 0).next(true);
    var next = first.next(false).next(true);
    assertFalse(next.currentGlobal(first.revision()));
    assertEquals(next, NativeConsentPolicy.decode(next.encode()));
  }

  @Test
  public void exhaustedRevisionDoesNotWrap() {
    assertThrows(
        IllegalStateException.class,
        () -> new NativeConsentPolicy(true, NativeConsentPolicy.MAX_REVISION).next(false));
  }

  @Test
  public void strictReaderRejectsMalformedWithoutSynthesizingARevision() {
    for (String value :
        new String[] {
          null, "", "1\ntrue\n01\n", "1\ntrue\n-1\n", "1\ntrue\n1\n    ", "2\ntrue\n1\n", "bad"
        })
      assertThrows(IllegalArgumentException.class, () -> NativeConsentPolicy.decodeStrict(value));
  }

  @Test
  public void newEncodingHasNoTrailingXmlWhitespaceAndKeepsValidLegacyReads() {
    var policy = new NativeConsentPolicy(true, 7);
    assertEquals("2\ntrue\n7", policy.encode());
    assertEquals(policy, NativeConsentPolicy.decodeStrict(policy.encode()));
    assertEquals(policy, NativeConsentPolicy.decodeStrict("1\ntrue\n7\n"));
  }
}
