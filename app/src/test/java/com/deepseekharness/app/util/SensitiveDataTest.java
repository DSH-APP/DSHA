package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class SensitiveDataTest {
  @Test
  public void removesCredentialsAcrossReportFormats() {
    for (String text :
        new String[] {
          "DEEPSEEK_API_KEY=my-secret-value",
          "export OPENAI_API_KEY='my-secret-value'",
          "https://host/?token=my-secret-value&x=1",
          "{\"apiKey\":\"my-secret-value\"}",
          "Authorization: Bearer my-secret-value",
          "Cookie: session=my-secret-value; extra=ok",
          "password：my-secret-value",
          "https://user:my-secret-value@host/file"
        }) assertFalse(text, SensitiveData.redact(text).contains("my-secret-value"));
    assertEquals("DEEPSEEK_API_KEY=***", SensitiveData.redact("DEEPSEEK_API_KEY=value"));
  }

  @Test
  public void preservesUsefulErrorsAndHidesPrivateKeys() {
    assertEquals(
        "CERTIFICATE_VERIFY_FAILED HTTP 503",
        SensitiveData.redact("CERTIFICATE_VERIFY_FAILED HTTP 503"));
    assertFalse(
        SensitiveData.redact("-----BEGIN PRIVATE KEY-----\nabc123\n-----END PRIVATE KEY-----")
            .contains("abc123"));
    assertNull(SensitiveData.redact(null));
  }

  @Test
  public void additionalKnownFormatsAndRegisteredOpaqueSecretsAreMasked() {
    for (String value :
        new String[] {
          "AIza" + "A".repeat(35),
          "AKIA" + "A".repeat(16),
          "xoxb-" + "a".repeat(20),
          "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.abcdefghijk"
        }) assertFalse(value, SensitiveData.redact(value).contains(value));
    String opaque = "opaque-fixture-credential-123";
    SensitiveData.registerKnownSecret(opaque);
    try {
      assertEquals(
          "https://host/***/file", SensitiveData.redact("https://host/" + opaque + "/file"));
      assertEquals("X-Token: ***", SensitiveData.redact("X-Token: " + opaque));
    } finally {
      SensitiveData.forgetKnownSecret(opaque);
    }
    assertEquals(opaque, SensitiveData.redact(opaque));
  }
}
