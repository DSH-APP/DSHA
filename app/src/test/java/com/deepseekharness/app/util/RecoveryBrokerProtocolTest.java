package com.deepseekharness.app.util;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryBrokerProtocolTest {
  private static final String TOKEN = "a".repeat(64);

  private static HttpProtocol.Head head(String path, String more) throws IOException {
    return HttpProtocol.parse(
        "POST "
            + path
            + " HTTP/1.1\r\nHost: 127.0.0.1:45000\r\nAuthorization: Bearer "
            + TOKEN
            + "\r\nContent-Length: 2\r\n"
            + more
            + "\r\n",
        true);
  }

  @Test
  public void fixedRoutesOnlyAndNoNativeConfirmation() throws Exception {
    for (String path : new String[] {"diagnostics", "targets", "read", "propose", "result"})
      RecoveryBrokerProtocol.validate(head("/v1/" + path, ""), 45000, TOKEN);
    for (String path :
        new String[] {"confirm", "reject", "shell", "read?path=/root/.env", "../confirm"})
      assertThrows(
          IOException.class,
          () -> RecoveryBrokerProtocol.validate(head("/v1/" + path, ""), 45000, TOKEN));
  }

  @Test
  public void previousGenerationTokenAndBrowserRequestsDenied() throws Exception {
    assertThrows(
        IOException.class,
        () -> RecoveryBrokerProtocol.validate(head("/v1/read", ""), 45000, "b".repeat(64)));
    assertThrows(
        IOException.class,
        () ->
            RecoveryBrokerProtocol.validate(
                head("/v1/read", "Origin: http://127.0.0.1:3080\r\n"), 45000, TOKEN));
    assertThrows(
        IOException.class,
        () ->
            RecoveryBrokerProtocol.validate(
                head("/v1/read", "Authorization: Bearer " + TOKEN + "\r\n"), 45000, TOKEN));
    assertThrows(
        IOException.class,
        () -> RecoveryBrokerProtocol.validate(head("/v1/read", ""), 45001, TOKEN));
  }

  @Test
  public void multilineCredentialsNeverEscape() {
    String text =
        "model: deepseek\napiKey: |\n  private-first-line\n  private-second-line\nport: 3080\n";
    String redacted = RecoveryBrokerProtocol.redact(text);
    assertFalse(redacted.contains("private-"));
    assertTrue(redacted.contains("model: deepseek"));
    assertTrue(redacted.contains("port: 3080"));
    assertFalse(
        RecoveryBrokerProtocol.redact("'password': >-\n   phrase secret\nmodel: ok")
            .contains("phrase secret"));
    assertEquals("hello\nworld\n", RecoveryBrokerProtocol.redact("hello\nworld\n"));
  }
}
