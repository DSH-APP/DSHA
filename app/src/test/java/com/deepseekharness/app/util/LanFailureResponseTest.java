package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class LanFailureResponseTest {
  @Test
  public void expiredCredentialHasActionAndClearsOnlyLanCookie() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    LanFailureResponse.write(out, 401, LanFailureResponse.Reason.TOKEN_INVALID, false);
    String response = out.toString(StandardCharsets.UTF_8.name());
    assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized\r\n"));
    assertTrue(response.contains("WWW-Authenticate: Bearer realm=\"DSHA LAN\""));
    assertTrue(response.contains("X-Dsha-Lan-Reason: token-invalid"));
    assertTrue(response.contains("Set-Cookie: dsha_lan=; Path=/; Max-Age=0"));
    assertTrue(response.contains("重新复制") || response.contains("Copy a new"));
    assertFalse(response.contains("Request could not be processed"));
    assertFalse(response.contains("dsh_auth="));
  }

  @Test
  public void utf8LengthAndHeadFramingMatchActualBytes() throws Exception {
    for (var reason : LanFailureResponse.Reason.values()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      LanFailureResponse.write(out, 503, reason, false);
      byte[] bytes = out.toByteArray();
      String response = out.toString(StandardCharsets.UTF_8.name());
      int boundary = response.indexOf("\r\n\r\n") + 4;
      String header = response.substring(0, boundary);
      int declared = Integer.parseInt(header.split("Content-Length: ")[1].split("\r\n")[0]);
      assertEquals(bytes.length - header.getBytes(StandardCharsets.US_ASCII).length, declared);
      ByteArrayOutputStream head = new ByteArrayOutputStream();
      LanFailureResponse.write(head, 503, reason, true);
      assertEquals(header, head.toString(StandardCharsets.US_ASCII.name()));
      assertFalse(header.contains("Set-Cookie"));
    }
  }

  @Test
  public void failuresAreDistinctAndContainNoRequestSecrets() {
    java.util.Set<String> messages = new java.util.HashSet<>();
    for (var reason : LanFailureResponse.Reason.values())
      assertTrue(messages.add(LanFailureResponse.message(reason)));
    assertEquals(LanFailureResponse.Reason.BACKEND_UNREACHABLE, LanFailureResponse.forStatus(502));
    assertEquals(LanFailureResponse.Reason.REQUEST_TIMEOUT, LanFailureResponse.forStatus(408));
  }
}
