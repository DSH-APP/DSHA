package com.deepseekharness.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class HarnessControllerAssetTest {
  @Test
  public void normalizesAssetNewlinesAndClosesStream() throws Exception {
    TrackingStream source = new TrackingStream("a\r\nb\rc\n".getBytes(StandardCharsets.UTF_8));
    assertEquals("a\nb\nc\n", HarnessController.readAssetText(source));
    assertTrue(source.closed);
  }

  @Test
  public void closesAssetStreamWhenReadFails() {
    TrackingStream source =
        new TrackingStream(new byte[] {1}) {
          @Override
          public synchronized int read(byte[] data, int offset, int length) throws IOException {
            throw new IOException("synthetic read failure");
          }
        };
    try {
      HarnessController.readAssetText(source);
      throw new AssertionError("Expected read failure");
    } catch (IOException expected) {
      assertEquals("synthetic read failure", expected.getMessage());
      assertTrue(source.closed);
    }
  }

  private static class TrackingStream extends InputStream {
    final ByteArrayInputStream delegate;
    boolean closed;

    TrackingStream(byte[] bytes) {
      delegate = new ByteArrayInputStream(bytes);
    }

    @Override
    public int read() {
      return delegate.read();
    }

    @Override
    public int read(byte[] data, int offset, int length) throws IOException {
      return delegate.read(data, offset, length);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      delegate.close();
    }
  }
}
