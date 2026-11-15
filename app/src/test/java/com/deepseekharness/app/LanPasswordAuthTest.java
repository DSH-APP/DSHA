package com.deepseekharness.app;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;
import com.deepseekharness.app.util.LanPassword;

public class LanPasswordAuthTest {
  private static String basic(String text) {
    return "Authorization: Basic "
        + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))
        + "\r\n";
  }

  @Test
  public void passwordAndSessionAreSeparateFromQueryCredential() {
    String verifier = LanPassword.create("secret123");
    assertFalse(verifier.contains("secret123"));
    assertTrue(
        LanAuth.passwordOk("GET / HTTP/1.1\r\n" + basic("dsha:secret123") + "\r\n", verifier));
    assertFalse(
        LanAuth.passwordOk("GET / HTTP/1.1\r\n" + basic("dsha:wrongpass") + "\r\n", verifier));
    assertFalse(
        LanAuth.passwordOk("POST / HTTP/1.1\r\n" + basic("dsha:secret123") + "\r\n", verifier));
    assertFalse(
        LanAuth.passwordOk(
            "GET / HTTP/1.1\r\nSec-Fetch-Site: cross-site\r\n" + basic("dsha:secret123") + "\r\n",
            verifier));
    assertFalse(LanAuth.sessionOk("GET /?token=secret123 HTTP/1.1\r\n\r\n", "secret123"));
    assertTrue(LanAuth.sessionOk("GET / HTTP/1.1\r\nCookie: dsha_lan=random\r\n\r\n", "random"));
    assertFalse(LanAuth.sessionOk("GET / HTTP/1.1\r\nCookie: dsha_lan=random\r\n\r\n", "rotated"));
  }
}
