package com.deepseekharness.app.runtime;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** 查询与短意图命令共用排队、准备和执行的协作总期限；不把安装预算套到列表。 */
public final class GuestScriptBudget {
  public static final long QUERY_TIMEOUT_MS = 30_000;
  private static final ThreadLocal<GuestScriptBudget> CURRENT = new ThreadLocal<>();
  private final long deadline;
  private final BooleanSupplier cancelled;
  private final String phase;
  private final GuestScriptBudget parent;

  public GuestScriptBudget(long millis, BooleanSupplier cancelled, String phase) {
    if (millis < 1) throw new IllegalArgumentException("SCRIPT_BUDGET");
    this.deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
    this.cancelled = java.util.Objects.requireNonNull(cancelled);
    this.phase = phase;
    this.parent = CURRENT.get();
  }

  public long remainingMillis() throws IOException, InterruptedException {
    if (Thread.interrupted()) throw new InterruptedException("SCRIPT_QUEUE_CANCELLED");
    if (cancelled.getAsBoolean()) throw new IOException(phase + "_CANCELLED");
    long left = deadline - System.nanoTime();
    if (left <= 0) throw new IOException(phase + "_TIMEOUT");
    long remaining = Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(left));
    return parent == null ? remaining : Math.min(remaining, parent.remainingMillis());
  }

  /** 保留原调用线程的任务所有权，让后续guest准备和执行继承提交时已开始的期限。 */
  public Scope enter() {
    GuestScriptBudget previous = CURRENT.get();
    CURRENT.set(this);
    return new Scope(previous);
  }

  /** 仅本线程已有查询期限时检查；不会中断正在提交的文件发布或更改其它调用。 */
  static void checkpoint() throws IOException {
    GuestScriptBudget active = CURRENT.get();
    if (active == null) return;
    try {
      active.remainingMillis();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new java.io.InterruptedIOException("SCRIPT_QUEUE_CANCELLED");
    }
  }

  /** 在实际读块前后协作截止；单次内核读取本身仍须等待其返回。 */
  static java.io.InputStream guard(java.io.InputStream source) {
    if (CURRENT.get() == null) return source;
    return new java.io.FilterInputStream(source) {
      @Override
      public int read() throws IOException {
        checkpoint();
        int value = in.read();
        checkpoint();
        return value;
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        checkpoint();
        int count = in.read(bytes, offset, length);
        checkpoint();
        return count;
      }

      @Override
      public long skip(long count) throws IOException {
        byte[] block = new byte[8192];
        long skipped = 0;
        while (skipped < count) {
          int read = read(block, 0, (int) Math.min(block.length, count - skipped));
          if (read < 0) break;
          skipped += read;
        }
        return skipped;
      }
    };
  }

  public final class Scope implements AutoCloseable {
    private final GuestScriptBudget previous;

    private Scope(GuestScriptBudget previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }
}
