package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class WebUploadStreamTest {
  private InputStream bytes(long size) {
    return new InputStream() {
      long remaining = size;

      @Override
      public int read() {
        throw new AssertionError("逐字节读取不应发生");
      }

      @Override
      public int read(byte[] data, int offset, int count) {
        if (remaining == 0) return -1;
        int n = (int) Math.min(remaining, count);
        remaining -= n;
        return n;
      }
    };
  }

  @Test
  public void fiveGigabytesAndFiveGibibytesUseFixedChunksAndLongCounters() throws Exception {
    for (long size :
        new long[] {257L * 1024 * 1024, 5_000_000_000L, WebTransferPolicy.UPLOAD_LIMIT}) {
      AtomicLong written = new AtomicLong();
      long result =
          WebUploadStream.copy(
              bytes(size),
              0,
              WebTransferPolicy.UPLOAD_LIMIT,
              (buffer, n) -> {
                assertEquals(65536, buffer.length);
                written.addAndGet(n);
              },
              () -> {});
      assertEquals(size, result);
      assertEquals(size, written.get());
    }
  }

  @Test
  public void decimalFileBoundaryIsIndependentOfBatchAndUnknownLength() throws Exception {
    assertEquals(5_000_000_000L, WebTransferPolicy.UPLOAD_FILE_LIMIT);
    AtomicLong written = new AtomicLong();
    long result =
        WebUploadStream.copy(
            bytes(WebTransferPolicy.UPLOAD_FILE_LIMIT),
            0,
            WebTransferPolicy.UPLOAD_LIMIT,
            WebTransferPolicy.UPLOAD_FILE_LIMIT,
            (b, n) -> written.addAndGet(n),
            () -> {});
    assertEquals(WebTransferPolicy.UPLOAD_FILE_LIMIT, result);
    assertEquals(result, written.get());
    written.set(0);
    WebUploadStream.LimitExceededException tooLarge =
        assertThrows(
            WebUploadStream.LimitExceededException.class,
            () ->
                WebUploadStream.copy(
                    bytes(WebTransferPolicy.UPLOAD_FILE_LIMIT + 1),
                    0,
                    WebTransferPolicy.UPLOAD_LIMIT,
                    WebTransferPolicy.UPLOAD_FILE_LIMIT,
                    (b, n) -> written.addAndGet(n),
                    () -> {}));
    assertTrue(tooLarge.fileLimit());
    assertTrue(written.get() <= WebTransferPolicy.UPLOAD_FILE_LIMIT);
    // A second smaller file may still fill the independent 5GiB batch allowance.
    long remaining = WebTransferPolicy.UPLOAD_LIMIT - result;
    assertEquals(
        WebTransferPolicy.UPLOAD_LIMIT,
        WebUploadStream.copy(
            bytes(remaining),
            result,
            WebTransferPolicy.UPLOAD_LIMIT,
            WebTransferPolicy.UPLOAD_FILE_LIMIT,
            (b, n) -> {},
            () -> {}));
  }

  @Test
  public void fileLimitResetsAndOverflowNeverWritesCrossingChunk() throws Exception {
    AtomicLong written = new AtomicLong();
    WebUploadStream.LimitExceededException file =
        assertThrows(
            WebUploadStream.LimitExceededException.class,
            () ->
                WebUploadStream.copy(
                    bytes(65537), 12, 200000, 65536, (b, n) -> written.addAndGet(n), () -> {}));
    assertTrue(file.fileLimit());
    assertEquals(65536, written.get());
    WebUploadStream.LimitExceededException batch =
        assertThrows(
            WebUploadStream.LimitExceededException.class,
            () -> WebUploadStream.copy(bytes(2), 9, 10, 20, (b, n) -> fail(), () -> {}));
    assertFalse(batch.fileLimit());
    assertEquals(15, WebUploadStream.copy(bytes(5), 10, 20, 5, (b, n) -> {}, () -> {}));
  }

  @Test
  public void batchLimitUsesAllFilesAndRejectsBeforeWritingTheCrossingChunk() throws Exception {
    long first =
        WebUploadStream.copy(
            bytes(2_147_483_648L), 0, WebTransferPolicy.UPLOAD_LIMIT, (b, n) -> {}, () -> {});
    long second =
        WebUploadStream.copy(
            bytes(WebTransferPolicy.UPLOAD_LIMIT - first),
            first,
            WebTransferPolicy.UPLOAD_LIMIT,
            (b, n) -> {},
            () -> {});
    assertEquals(WebTransferPolicy.UPLOAD_LIMIT, second);
    AtomicLong writes = new AtomicLong();
    assertThrows(
        WebUploadStream.LimitExceededException.class,
        () ->
            WebUploadStream.copy(
                new ByteArrayInputStream(new byte[] {1}),
                second,
                WebTransferPolicy.UPLOAD_LIMIT,
                (b, n) -> writes.incrementAndGet(),
                () -> {}));
    assertEquals(0, writes.get());
    assertThrows(
        WebUploadStream.LimitExceededException.class,
        () ->
            WebUploadStream.copy(
                bytes(2), Long.MAX_VALUE - 1, Long.MAX_VALUE, (b, n) -> {}, () -> {}));
  }

  @Test
  public void sourceReadAndOutputFailureNeverReplayTheCopy() {
    AtomicLong writes = new AtomicLong();
    assertThrows(
        IOException.class,
        () ->
            WebUploadStream.copy(
                bytes(65537),
                0,
                100000,
                (b, n) -> {
                  writes.incrementAndGet();
                  throw new IOException("sink failed");
                },
                () -> {}));
    assertEquals(1, writes.get());
    assertThrows(
        InterruptedIOException.class,
        () ->
            WebUploadStream.copy(
                bytes(65537),
                0,
                100000,
                (b, n) -> writes.incrementAndGet(),
                () -> {
                  throw new InterruptedIOException();
                }));
    assertEquals(1, writes.get());
  }
}
