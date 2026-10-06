package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class ExtractionReadProgressTest {
  @Test
  public void tinyReadsPreserveBytesWithoutPerReadNotifications() throws Exception {
    byte[] source = new byte[100_000];
    for (int i = 0; i < source.length; i++) source[i] = (byte) i;
    List<Long> updates = new ArrayList<>();
    AtomicLong clock = new AtomicLong();
    ExtractionReadProgress progress =
        new ExtractionReadProgress(
            source.length,
            (done, total) -> {
              assertEquals(source.length, total.longValue());
              updates.add(done);
            },
            () -> clock.getAndAdd(1_000));
    InputStream counted = progress.count(new ByteArrayInputStream(source));
    for (byte expected : source) assertEquals(expected & 255, counted.read());
    assertEquals(-1, counted.read());
    progress.flush();
    progress.flush();
    assertEquals(List.of(100_000L), updates);
  }

  @Test
  public void splitArchivesHaveExactCumulativeProgressAndOnlyOneFinalUpdate() throws Exception {
    List<Long> updates = new ArrayList<>();
    ExtractionReadProgress progress =
        new ExtractionReadProgress(7, (done, total) -> updates.add(done), () -> 0);
    assertArrayEquals(
        new byte[] {1, 2, 3},
        progress.count(new ByteArrayInputStream(new byte[] {1, 2, 3})).readAllBytes());
    progress.flush();
    assertArrayEquals(
        new byte[] {4, 5, 6, 7},
        progress.count(new ByteArrayInputStream(new byte[] {4, 5, 6, 7})).readAllBytes());
    progress.flush();
    progress.flush();
    assertEquals(List.of(3L, 7L), updates);
  }

  @Test
  public void bulkAndSkippedBytesAreNotDoubleCountedAndUnknownSizeStaysUnknown() throws Exception {
    List<Long> updates = new ArrayList<>();
    ExtractionReadProgress progress =
        new ExtractionReadProgress(
            -1,
            (done, total) -> {
              assertEquals(-1, total.longValue());
              updates.add(done);
            },
            () -> 0);
    InputStream counted = progress.count(new ByteArrayInputStream(new byte[] {0, 1, 2, 3, 4}));
    byte[] actual = new byte[4];
    assertEquals(2, counted.read(actual, 1, 2));
    assertArrayEquals(new byte[] {0, 0, 1, 0}, actual);
    assertEquals(2, counted.skip(2));
    assertEquals(4, counted.read());
    assertEquals(-1, counted.read(actual));
    assertEquals(-1, counted.read());
    progress.flush();
    assertEquals(List.of(5L), updates);
  }

  @Test
  public void updatesUseElapsedTimeAndFailuresDoNotPublishACompletion() throws Exception {
    List<Long> updates = new ArrayList<>();
    AtomicLong clock = new AtomicLong();
    ExtractionReadProgress progress =
        new ExtractionReadProgress(5, (done, total) -> updates.add(done), clock::get);
    InputStream counted = progress.count(new ByteArrayInputStream(new byte[] {1, 2, 3}));
    assertEquals(1, counted.read());
    clock.set(99_000_000L);
    assertEquals(2, counted.read());
    assertTrue(updates.isEmpty());
    clock.set(100_000_000L);
    assertEquals(3, counted.read());
    assertEquals(List.of(3L), updates);
    InputStream failed =
        progress.count(
            new InputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("ARCHIVE_TRUNCATED");
              }
            });
    assertThrows(IOException.class, failed::read);
    failed.close();
    assertEquals(List.of(3L), updates);
  }

  @Test
  public void missingObserverKeepsOriginalInputStream() {
    InputStream input = new ByteArrayInputStream(new byte[] {1});
    assertSame(input, new ExtractionReadProgress(1, null).count(input));
  }
}
