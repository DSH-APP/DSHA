package com.deepseekharness.app.util;

import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class BoundedOutputDrainTest {
  @Test
  public void largeOutputDrainsFullBoundedPassWithoutUnboundedHistory() throws Exception {
    byte[] bytes = new byte[1024 * 1024];
    java.util.Arrays.fill(bytes, (byte) 'x');
    var input = new ByteArrayInputStream(bytes);
    var output = new BoundedOutputDrain();
    assertEquals(256 * 1024, output.drain(input));
    assertEquals(768 * 1024, input.available());
    assertTrue(output.diagnostics().length() <= 32768);
    for (int pass = 0; pass < 3; pass++) output.drain(input);
    assertEquals(0, input.available());
  }

  @Test
  public void absentOutputDoesNotReadAndCredentialsAreRedacted() throws Exception {
    var output = new BoundedOutputDrain();
    assertEquals(
        0,
        output.drain(
            new InputStream() {
              public int read() {
                throw new AssertionError("blocking read");
              }
            }));
    output.drain(new ByteArrayInputStream("DEEPSEEK_API_KEY=hidden-secret\n".getBytes()));
    assertFalse(output.diagnostics().contains("hidden-secret"));
  }

  @Test
  public void secretAcrossReadBoundaryIsRedactedBeforeAnyTailTrim() throws Exception {
    String secret = "opaque-client-secret-" + "q".repeat(80);
    SensitiveData.registerKnownSecret(secret);
    try {
      var output = new BoundedOutputDrain();
      String text = "x".repeat(8190) + secret + "\n";
      output.drain(new ByteArrayInputStream(text.getBytes()));
      assertFalse(output.diagnostics().contains(secret));
      assertFalse(output.diagnostics().contains("q".repeat(20)));
    } finally {
      SensitiveData.forgetKnownSecret(secret);
    }
  }
}
