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

  @Test
  public void oauthQueryValuesAreHiddenWithoutReplacingDiagnosticWords() {
    String query =
        "http://127.0.0.1:41821/oauth/callback?code=fake-code&state=fake-state"
            + "&code_verifier=fake-verifier&code_challenge=fake-challenge"
            + "&authorize_id=fake-authorize&authorization_code=fake-authorization&error=access_denied";
    String safe = SensitiveData.redact(query);
    assertEquals(
        "http://127.0.0.1:41821/oauth/callback?code=***&state=***"
            + "&code_verifier=***&code_challenge=***&authorize_id=***&authorization_code=***"
            + "&error=access_denied",
        safe);
    assertEquals("?id_token=***", SensitiveData.redact("?id_token=synthetic-id-token"));
    assertEquals(
        "?token=***&error=network#reason",
        SensitiveData.redact("?token=synthetic-token&error=network#reason"));
    String pkceDiagnostic =
        SensitiveData.redact(
            "{\"code_verifier\":\"synthetic-verifier\",\"code_challenge\":\"synthetic-challenge\"}");
    assertFalse(pkceDiagnostic.contains("synthetic-verifier"));
    assertFalse(pkceDiagnostic.contains("synthetic-challenge"));
    assertTrue(pkceDiagnostic.contains("code_verifier"));
    assertEquals(
        "state=waiting-browser code=no-response HTTP 503 errorCode=protocol",
        SensitiveData.redact("state=waiting-browser code=no-response HTTP 503 errorCode=protocol"));
    assertEquals(
        "?CODE=***&STATE=***&status=waiting-browser",
        SensitiveData.redact("?CODE=synthetic&STATE=synthetic&status=waiting-browser"));
    assertEquals(
        "?error_code=400&stateful=ready&codeName=reason",
        SensitiveData.redact("?error_code=400&stateful=ready&codeName=reason"));
  }
}
