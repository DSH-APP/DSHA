package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class GuestCommandOutcomeTest {
  @Test
  public void completeZeroExitReturnsOriginalOutput() throws Exception {
    var result =
        BoundedProcessRunner.collect(new Child("DSHA_OK", 0, false), 500, 256, Process::destroy);
    assertEquals("DSHA_OK", GuestCommandOutcome.requireCompleted(result, "PROBE"));
  }

  @Test
  public void markerCannotConvertNonzeroExitIntoSuccess() throws Exception {
    var result =
        BoundedProcessRunner.collect(new Child("DSHA_OK", 7, false), 500, 256, Process::destroy);
    IOException failure =
        assertThrows(
            IOException.class, () -> GuestCommandOutcome.requireCompleted(result, "PROBE"));
    assertTrue(failure.getMessage().startsWith("PROBE_EXIT_7"));
  }

  @Test
  public void timeoutAndTruncationRemainDistinctFailures() throws Exception {
    var timeout = BoundedProcessRunner.collect(new Child("", 0, true), 20, 256, Process::destroy);
    assertTrue(
        assertThrows(
                IOException.class, () -> GuestCommandOutcome.requireCompleted(timeout, "PROBE"))
            .getMessage()
            .startsWith("PROBE_TIMEOUT"));
    var truncated =
        BoundedProcessRunner.collect(
            new Child("DSHA_OK plus trailing output", 0, false), 500, 4, Process::destroy);
    assertTrue(
        assertThrows(
                IOException.class, () -> GuestCommandOutcome.requireCompleted(truncated, "PROBE"))
            .getMessage()
            .startsWith("PROBE_OUTPUT_TRUNCATED"));
  }

  private static final class Child extends Process {
    private final InputStream output;
    private final OutputStream input = new ByteArrayOutputStream();
    private final int code;
    private boolean running;

    Child(String text, int code, boolean running) {
      output = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
      this.code = code;
      this.running = running;
    }

    @Override
    public OutputStream getOutputStream() {
      return input;
    }

    @Override
    public InputStream getInputStream() {
      return output;
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      throw new AssertionError("Unbounded wait");
    }

    @Override
    public int exitValue() {
      if (running) throw new IllegalThreadStateException();
      return code;
    }

    @Override
    public void destroy() {
      running = false;
    }
  }
}
