package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import com.deepseekharness.app.util.RuntimeWorkPort;
import org.junit.Test;

public class BoundedGuestLifetimeTest {
  @Test
  public void earlyForegroundStatusCannotReleaseWorkBeforeGroupClose() {
    List<String> order = new ArrayList<>();
    EarlyStatus process = new EarlyStatus();
    TrackingWork work = new TrackingWork(order);
    BoundedGuestLifetime.finish(
        process,
        () -> {
          order.add("group-close");
          process.groupGone = true;
        },
        null,
        work,
        null);
    assertEquals(List.of("group-close", "work-close"), order);
  }

  @Test
  public void unknownGroupRetainsOriginalWorkToken() {
    List<String> order = new ArrayList<>();
    EarlyStatus process = new EarlyStatus();
    TrackingWork work = new TrackingWork(order);
    IllegalStateException failure = new IllegalStateException("GROUP_UNKNOWN");
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                BoundedGuestLifetime.finish(
                    process,
                    () -> {
                      order.add("group-close");
                      process.closing = true;
                      throw failure;
                    },
                    () -> order.add("uncertain"),
                    work,
                    null)));
    assertEquals(List.of("group-close", "uncertain", "retain"), order);
    assertSame(process, work.retained);
  }

  private static final class TrackingWork implements RuntimeWorkPort.Work {
    final List<String> order;
    Process retained;

    TrackingWork(List<String> order) {
      this.order = order;
    }

    @Override
    public void retainUntilExit(Process process) {
      retained = process;
      order.add("retain");
    }

    @Override
    public void close() {
      order.add("work-close");
    }
  }

  private static final class EarlyStatus extends Process {
    boolean closing, groupGone;

    @Override
    public int exitValue() {
      if (closing && !groupGone) throw new IllegalThreadStateException("group remains");
      return 0;
    }

    @Override
    public int waitFor() {
      throw new AssertionError("Unbounded wait");
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public void destroy() {
      closing = true;
    }
  }
}
