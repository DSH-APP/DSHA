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
  public void privateKeyVariantsAreHiddenUntilTheirMatchingEndOrEndOfInput() {
    for (String type : new String[] {"", "RSA ", "EC ", "OPENSSH ", "ENCRYPTED ", "DSA "}) {
      String begin = "-----BEGIN " + type + "PRIVATE KEY-----\n";
      String end = "-----END " + type + "PRIVATE KEY-----";
      String marker = UiText.text("[私钥已隐藏]");
      assertEquals(marker, SensitiveData.redact(begin + "private material\n"));
      assertEquals(
          "before\n" + marker + "\nafter",
          SensitiveData.redact("before\n" + begin + "private material\n" + end + "\nafter"));
    }
    assertEquals(
        UiText.text("[私钥已隐藏]"),
        SensitiveData.redact(
            "-----BEGIN RSA PRIVATE KEY-----\nprivate one\n"
                + "-----END EC PRIVATE KEY-----\nprivate two"));
  }

  @Test
  public void unterminatedQuotedValuesStayHiddenThroughEndOfInput() {
    for (String quote : new String[] {"\"", "'"}) {
      assertEquals(
          "password=***",
          SensitiveData.redact("password=" + quote + "first private line\nsecond private line"));
      assertEquals(
          "password=***\npublic tail",
          SensitiveData.redact(
              "password="
                  + quote
                  + "first private line\nsecond private line"
                  + quote
                  + "\npublic tail"));
    }
  }

  @Test
  public void rangeProjectionUsesBothSidesWithoutIncludingUnselectedText() {
    String source = "before\npassword=\"private head\nprivate middle\nprivate tail\"\nafter";
    int start = source.indexOf("private middle");
    String[] safe =
        SensitiveData.redactRanges(
            source, start, start + "private middle".length(), 0, source.length());
    assertEquals("***", safe[0]);
    assertEquals("before\npassword=***\nafter", safe[1]);
    assertEquals("", SensitiveData.redactRanges(source, start, start)[0]);
  }

  @Test
  public void rangeProjectionMasksAllExistingRuleFamiliesWhenTheirPrefixIsOutside() {
    String secret = "synthetic-private-value";
    for (String source :
        new String[] {
          "Authorization: " + secret,
          "https://host/?token=" + secret + "&ok=yes",
          "api_key='" + secret + "'",
          "https://user:" + secret + "@host/path",
          "Bearer " + secret,
          "sk-" + secret
        }) {
      int start = source.indexOf(secret);
      assertEquals(source, "***", SensitiveData.redactRanges(source, start + 3, start + 12)[0]);
      assertFalse(SensitiveData.redactRanges(source, 0, source.length())[0].contains(secret));
    }
  }

  @Test
  public void knownSecretsAreProjectedAcrossLinesAndCannotDestroyPrivateKeyDelimiters() {
    String secret = "opaque head\nopaque middle\nopaque tail";
    SensitiveData.registerKnownSecret(secret);
    SensitiveData.registerKnownSecret("PRIVATE KEY");
    try {
      String source = "before\n" + secret + "\nafter";
      int start = source.indexOf("opaque middle");
      assertEquals("***", SensitiveData.redactRanges(source, start, start + 13)[0]);
      String key = "-----BEGIN PRIVATE KEY-----\nprivate material\n-----END PRIVATE KEY-----";
      assertEquals(UiText.text("[私钥已隐藏]"), SensitiveData.redactRanges(key, 0, key.length())[0]);
    } finally {
      SensitiveData.forgetKnownSecret(secret);
      SensitiveData.forgetKnownSecret("PRIVATE KEY");
    }
  }

  @Test
  public void overlappingKnownSecretsHideTheirEntireUnionAndSeparateOccurrences() {
    String secret = "A".repeat(4096);
    SensitiveData.registerKnownSecret(secret);
    try {
      String source = "before " + "A".repeat(8191) + " middle " + secret + " after";
      assertEquals(
          "before *** middle *** after", SensitiveData.redactRanges(source, 0, source.length())[0]);
      assertEquals("***", SensitiveData.redactRanges(source, 8000, 8198)[0]);
    } finally {
      SensitiveData.forgetKnownSecret(secret);
    }
  }

  @Test
  public void rangeProjectionNeverReturnsHalfOfASurrogatePair() {
    String source = "a😀b";
    assertArrayEquals(
        new String[] {"b", "", "😀"}, SensitiveData.redactRanges(source, 2, 4, 1, 2, 1, 3));
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
