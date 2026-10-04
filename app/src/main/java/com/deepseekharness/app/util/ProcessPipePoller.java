package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Polls only available pipe bytes, then confirms process exit and drains the final tail. */
final class ProcessPipePoller {
  interface Check {
    void run() throws InterruptedException;
  }

  static final class Event {
    final int count;
    final Integer exitCode;
    final boolean timedOut;

    private Event(int count, Integer exitCode, boolean timedOut) {
      this.count = count;
      this.exitCode = exitCode;
      this.timedOut = timedOut;
    }
  }

  private final Process process;
  private final InputStream input;
  private final byte[] buffer;

  ProcessPipePoller(Process process, byte[] buffer) {
    this.process = process;
    this.input = process.getInputStream();
    this.buffer = buffer;
  }

  Event next(long deadlineNanos, Check check) throws IOException, InterruptedException {
    while (true) {
      check.run();
      long remaining = deadlineNanos - System.nanoTime();
      if (remaining <= 0) return new Event(0, null, true);
      int available = input.available();
      if (available > 0) {
        int count = input.read(buffer, 0, Math.min(available, buffer.length));
        if (count > 0) return new Event(count, null, false);
      }
      Integer code = exitCode(process);
      if (code != null) {
        // A final write can arrive between the previous available() and exitValue().
        if (input.available() > 0) continue;
        return new Event(0, code, false);
      }
      TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
    }
  }

  private static Integer exitCode(Process process) {
    try {
      return process.exitValue();
    } catch (IllegalThreadStateException running) {
      return null;
    }
  }
}
