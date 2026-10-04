package com.deepseekharness.app.runtime;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class GuestScriptsTest {
  @Test
  public void largeSignedScriptDoesNotBecomeAnArgumentAndClosesStdin() throws Exception {
    byte[] source = ("echo fixture\r\n".repeat(17000)).getBytes();
    byte[] script = GuestScripts.read(new ByteArrayInputStream(source));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    boolean[] closed = {false};
    Process process =
        new Process() {
          public OutputStream getOutputStream() {
            return new FilterOutputStream(output) {
              public void close() throws IOException {
                closed[0] = true;
                super.close();
              }
            };
          }

          public InputStream getInputStream() {
            return InputStream.nullInputStream();
          }

          public InputStream getErrorStream() {
            return InputStream.nullInputStream();
          }

          public int waitFor() {
            return 0;
          }

          public int exitValue() {
            return 0;
          }

          public void destroy() {}
        };
    GuestScripts.send(process, script);
    assertArrayEquals(script, output.toByteArray());
    assertTrue(closed[0]);
    assertFalse(new String(script).contains("\r"));
    assertTrue(GuestScripts.INSTALL.length() < 512);
    assertFalse(GuestScripts.INSTALL.contains("fixture"));
    assertTrue(script.length > 128 * 1024);
  }

  @Test
  public void oversizedInputCannotStartAnUnboundedInjection() throws Exception {
    assertThrows(
        IOException.class,
        () -> GuestScripts.read(new ByteArrayInputStream(new byte[GuestScripts.LIMIT + 1])));
  }
}
