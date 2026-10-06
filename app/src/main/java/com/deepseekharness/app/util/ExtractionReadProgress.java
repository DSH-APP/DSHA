package com.deepseekharness.app.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** 本次分包解压的累计读取进度；读取和完整性检查不依赖界面通知频率。 */
public final class ExtractionReadProgress {
  private static final long INTERVAL_NANOS = 100_000_000L;
  private final long total;
  private final BiConsumer<Long, Long> consumer;
  private final LongSupplier clock;
  private long done, reported, lastReport;

  public ExtractionReadProgress(long total, BiConsumer<Long, Long> consumer) {
    this(total, consumer, System::nanoTime);
  }

  ExtractionReadProgress(long total, BiConsumer<Long, Long> consumer, LongSupplier clock) {
    this.total = total;
    this.consumer = consumer;
    this.clock = clock;
    lastReport = clock.getAsLong();
  }

  /** 多个签名分包共用本次计数，避免 dsh 阶段的读取量退回零。 */
  public InputStream count(InputStream input) {
    if (consumer == null) return input;
    return new FilterInputStream(input) {
      @Override
      public int read() throws IOException {
        int value = in.read();
        if (value >= 0) advanced(1);
        return value;
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        int count = in.read(bytes, offset, length);
        if (count > 0) advanced(count);
        return count;
      }

      @Override
      public long skip(long count) throws IOException {
        long skipped = in.skip(count);
        if (skipped > 0) advanced(skipped);
        return skipped;
      }
    };
  }

  private void advanced(long count) {
    done += count;
    long now = clock.getAsLong();
    if (now - lastReport >= INTERVAL_NANOS) report(now);
  }

  /** 仅在本分包解压和完整性检查成功返回后补发末次实际读取量。 */
  public void flush() {
    if (consumer != null && done != reported) report(clock.getAsLong());
  }

  private void report(long now) {
    consumer.accept(done, total);
    reported = done;
    lastReport = now;
  }
}
