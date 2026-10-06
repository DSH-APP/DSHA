package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class VirtualScreenBootstrapTest {
  private static void feed(VirtualScreenBootstrap parser, String value) throws IOException {
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) parser.accept(b & 255);
  }

  @Test
  public void romNoiseDoesNotInvalidateExactBootstrap() throws Exception {
    var parser = new VirtualScreenBootstrap();
    feed(
        parser,
        "vivo resources warning\n[okhttp] noisy permission check\r\n"
            + "DSHA_VSCREEN_BOOTSTRAP "
            + "a".repeat(48)
            + "\n");
    assertEquals("a".repeat(48), parser.token());
  }

  @Test
  public void childFailureAfterExitIsParsedWithoutGenericConnectionError() throws Exception {
    String code = "CORE_CONTEXT_SDK30_NullPointerException";
    var parser = new VirtualScreenBootstrap();
    IOException failure =
        assertThrows(
            IOException.class, () -> feed(parser, "ROM notice\nDSHA_VSCREEN_ERROR=" + code + "\n"));
    assertEquals(code, failure.getMessage());
    assertEquals(code, VirtualScreenBootstrap.failureCode("LAUNCH", 30, failure));
  }

  @Test
  public void malformedMarkersAndFloodCannotGrantToken() {
    assertThrows(
        IOException.class,
        () -> feed(new VirtualScreenBootstrap(), "DSHA_VSCREEN_BOOTSTRAP arbitrary-secret\n"));
    assertThrows(IOException.class, () -> feed(new VirtualScreenBootstrap(), "x".repeat(4097)));
    assertThrows(
        IOException.class, () -> feed(new VirtualScreenBootstrap(), "noise\n".repeat(12000)));
    assertEquals(
        "x",
        VirtualScreenBootstrap.error(
            "DSHA_VSCREEN_ERROR=x\r\nX-Token: secret\ninline DSHA_VSCREEN_ERROR=BAD"));
    assertEquals("", VirtualScreenBootstrap.error("prefix DSHA_VSCREEN_ERROR=BAD"));
  }

  @Test
  public void codeHasSdkStageAndTypeButNeverExceptionSecret() {
    String value =
        VirtualScreenBootstrap.failureCode(
            "BIND_LISTENER",
            30,
            new IOException("token=secret", new SecurityException("sensitive")));
    assertEquals("CORE_BIND_LISTENER_SDK30_SecurityException", value);
    assertFalse(value.contains("secret"));
  }
}
