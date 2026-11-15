package com.deepseekharness.app.util;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Close a stalled image-response socket without affecting any other bridge request. */
public final class BridgeImageWriteDeadline implements AutoCloseable {
  public static final long RESPONSE_TIMEOUT_MILLIS = 30_000;
  private static final ScheduledThreadPoolExecutor TIMER = timer();
  private final ScheduledFuture<?> task;

  private BridgeImageWriteDeadline(Socket socket, long timeoutMillis) {
    if (socket == null || timeoutMillis <= 0)
      throw new IllegalArgumentException("INVALID_DEADLINE");
    task =
        TIMER.schedule(
            () -> {
              try {
                socket.close();
              } catch (IOException ignored) {
              }
            },
            timeoutMillis,
            TimeUnit.MILLISECONDS);
  }

  private static ScheduledThreadPoolExecutor timer() {
    var timer =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "bridge-image-write-deadline");
              thread.setDaemon(true);
              return thread;
            });
    timer.setRemoveOnCancelPolicy(true);
    return timer;
  }

  public static BridgeImageWriteDeadline arm(Socket socket, long timeoutMillis) {
    return new BridgeImageWriteDeadline(socket, timeoutMillis);
  }

  @Override
  public void close() {
    task.cancel(false);
  }
}
