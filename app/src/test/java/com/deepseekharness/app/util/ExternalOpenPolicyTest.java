package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ExternalOpenPolicyTest {
  @Test
  public void safeSchemesDoNotRequireConfirmation() {
    for (String url : new String[] {"http://example.test", "https://example.test", "geo:1,2"})
      assertEquals(ExternalOpenPolicy.Kind.SAFE, ExternalOpenPolicy.classify(url));
  }

  @Test
  public void externalSchemesRequireConfirmation() {
    for (String url : new String[] {"tel:+123", "mailto:a@example.test", "market://details?id=x"}) {
      assertEquals(ExternalOpenPolicy.Kind.CONFIRM, ExternalOpenPolicy.classify(url));
      assertTrue(ExternalOpenPolicy.requiresConfirmation(url));
    }
  }

  @Test
  public void unsupportedSchemesRemainForbidden() {
    for (String url : new String[] {"file:///secret", "intent://x", "javascript:alert(1)", ""})
      assertEquals(ExternalOpenPolicy.Kind.FORBIDDEN, ExternalOpenPolicy.classify(url));
  }
}
