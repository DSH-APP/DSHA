package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class BoundedByteTailTest {
  private static void write(BoundedByteTail tail, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    tail.write(bytes, 0, bytes.length);
  }

  @Test
  public void shortOutputIsKeptVerbatim() {
    BoundedByteTail tail = new BoundedByteTail(64);
    write(tail, "line one\n");
    write(tail, "line two\n");
    assertEquals("line one\nline two\n", tail.text());
    assertFalse(tail.truncated());
    assertEquals(18, tail.size());
  }

  @Test
  public void overflowKeepsOnlyLatestCompleteLines() {
    BoundedByteTail tail = new BoundedByteTail(32);
    write(tail, "first-line\nsecond-line\nthird-line\n");
    assertTrue(tail.truncated());
    String text = tail.text();
    assertFalse(text.contains("first-line"));
    assertTrue(text.startsWith(UiText.text("（较早输出已省略")));
    assertTrue(text.endsWith("third-line\n"));
  }

  @Test
  public void exactCapacityIsNotTruncated() {
    BoundedByteTail tail = new BoundedByteTail(10);
    write(tail, "0123456789");
    assertFalse(tail.truncated());
    assertEquals("0123456789", tail.text());
  }

  @Test
  public void partialFirstLineWithoutNewlineIsDroppedInsteadOfExportingAFragment() {
    BoundedByteTail tail = new BoundedByteTail(10);
    write(tail, "API_KEY=0123456789");
    assertTrue(tail.truncated());
    assertEquals(UiText.text("（较早输出已省略，以下为最近日志）\n"), tail.text());
  }

  @Test
  public void truncatedUtf8DoesNotCreateBrokenCharacters() {
    BoundedByteTail tail = new BoundedByteTail(20);
    write(tail, "0123456789\n中文汉字\nEND\n");
    assertTrue(tail.truncated());
    String text = tail.text();
    assertFalse(text.contains("\uFFFD"));
    assertTrue(text.endsWith("中文汉字\nEND\n"));
  }

  @Test
  public void chunkedWritesAcrossTheRingBoundaryStayInOrder() {
    BoundedByteTail tail = new BoundedByteTail(9);
    write(tail, "ab\ncd\nef\n");
    write(tail, "gh\n");
    assertTrue(tail.truncated());
    assertTrue(tail.text().endsWith("\nef\ngh\n"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void invalidLimitIsRejected() {
    new BoundedByteTail(0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void invalidRangeIsRejected() {
    new BoundedByteTail(8).write(new byte[2], 1, 2);
  }
}
