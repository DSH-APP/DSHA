package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class LogCompactorTest {
  @Test
  public void repeatsPreserveTimeRangeAndDifferentNumbers() {
    String a = "10-07 01:02:03 [BRIDGE_BIND] port 3090 unavailable";
    String b = "10-07 01:02:04 [BRIDGE_BIND] port 3090 unavailable";
    String c = "10-07 01:02:05 [BRIDGE_BIND] port 3091 unavailable";
    String output = LogCompactor.foldRepeats(a + "\n" + b + "\n" + c);
    assertTrue(output.contains("×2, 10-07 01:02:03 ~ 10-07 01:02:04"));
    assertTrue(output.contains("port 3091"));
    assertEquals(a + "\nboundary\n" + b, LogCompactor.foldRepeats(a + "\nboundary\n" + b));
  }

  @Test
  public void changedFailureNumbersAndPathsCannotMerge() {
    String input =
        "[1.0s] exec failed with code 1 at /tmp/task1\n[2.0s] exec failed with code 2 at /tmp/task2";
    assertEquals(input, LogCompactor.foldRepeats(input));
  }

  @Test
  public void recordsCompareUnshortenedContentAndKeepChronology() {
    String noise = "Unpacking a package\n".repeat(30);
    String first = "[1791331200000] RESULT\n" + noise + "middle1\n" + noise;
    String second = "[1791331201000] RESULT\n" + noise + "middle2\n" + noise;
    String different = LogCompactor.compactRecords(first + second, 2, 2);
    assertFalse(different.contains("×2"));
    assertTrue(different.indexOf("1791331200000") < different.indexOf("1791331201000"));
    String repeated =
        LogCompactor.compactRecords(first + first.replace("1791331200000", "1791331201000"), 2, 2);
    assertTrue(repeated.contains("×2"));
  }

  @Test
  public void boundedSummaryPrioritizesFailureInMiddleAndPreservesUnicode() {
    String input =
        "start\n"
            + "unpacking package\n".repeat(300)
            + "dpkg: error unique-root-cause\n"
            + "loading\n".repeat(300)
            + "end";
    String output = LogCompactor.compactReason(input, 1200);
    assertTrue(output.contains("unique-root-cause"));
    assertTrue(output.length() <= 1200);
    assertTrue(output.contains("end"));
    for (int limit = 0; limit < 250; limit++) {
      String unicode = LogCompactor.cap("🙂".repeat(200), limit);
      assertTrue(unicode.length() <= limit);
      assertFalse(unicode.startsWith("\udc42"));
      assertFalse(unicode.endsWith("\ud83d"));
    }
  }

  @Test
  public void hugeFailuresHaveExplicitBoundAndShortContentIsUnchanged() {
    String output =
        LogCompactor.compact("failed at file " + "x".repeat(20000) + " path-at-end", 8000);
    assertTrue(output.length() <= 8000);
    assertTrue(output.contains("path-at-end"));
    assertTrue(output.contains("省略") || output.contains("shortened"));
    assertEquals("small\ntext", LogCompactor.compact("small\ntext", 8000));
    assertEquals("", LogCompactor.compactInstall(null, 20));
  }
}
