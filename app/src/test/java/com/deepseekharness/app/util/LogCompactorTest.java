package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class LogCompactorTest {
  private static String bind(String time, int port) {
    return time + " [BRIDGE_BIND] 设备桥端口 " + port + " 被占用，设备工具暂不可用";
  }

  @Test public void identicalLinesFoldWithCountAndTimeRange() {
    String in = bind("09-14 00:20:55", 3090) + "\n" + bind("09-14 00:21:30", 3090) + "\n" + bind("09-14 01:50:18", 3090);
    String out = LogCompactor.foldRepeats(in);
    assertEquals(1, out.split("\n").length);
    assertTrue(out, out.contains("(×3, 09-14 00:20:55 ~ 09-14 01:50:18)"));
  }

  @Test public void differingDigitsInPlainLinesStillFold() {
    String out = LogCompactor.foldRepeats("[1.0s] 正在加载插件：abcdef\n[2.5s] 正在加载插件：abcdef");
    assertTrue(out, out.contains("(×2"));
  }

  @Test public void elapsedSecondsPrefixDoesNotDefeatFolding() {
    String out = LogCompactor.foldRepeats("[78.1s] RUNTIME_ERROR: slot crashed in bar\n[281.6s] RUNTIME_ERROR: slot crashed in bar");
    assertTrue(out, out.contains("(×2"));
    assertEquals(1, out.split("\n").length);
  }

  @Test public void failureLinesWithDifferentDigitsAreNeverMerged() {
    String in = "10-05 03:20:08 [X] exec failed with code 1\n10-05 03:20:09 [X] exec failed with code 2";
    String out = LogCompactor.foldRepeats(in);
    assertTrue(out.contains("code 1"));
    assertTrue(out.contains("code 2"));
    assertFalse(out.contains("×"));
  }

  @Test public void uniqueAndShortLinesAreUntouched() {
    String in = "标题\n\nalpha line number one\nbeta line number two\n";
    assertEquals(in, LogCompactor.foldRepeats(in));
  }

  @Test public void nullAndEmptyAreSafe() {
    assertEquals("", LogCompactor.foldRepeats(null));
    assertEquals("", LogCompactor.foldRepeats(""));
    assertEquals("", LogCompactor.headTail(null, 2, 2));
    assertEquals("", LogCompactor.cap(null, 10));
    assertEquals("", LogCompactor.compactInstall(null, 10));
  }

  @Test public void headTailKeepsEdgesAndErrorsInTheMiddle() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 100; i++) sb.append(i == 50 ? "dpkg: error processing package libc-bin" : "Unpacking package" + i).append('\n');
    String out = LogCompactor.headTail(sb.toString(), 3, 3);
    assertTrue(out.contains("Unpacking package0"));
    assertTrue(out.contains("Unpacking package99"));
    assertTrue(out.contains("error processing package libc-bin"));
    assertFalse(out.contains("Unpacking package30"));
    assertTrue(out.contains("lines omitted"));
  }

  @Test public void longPlainLinesAreClippedButFailureLinesAreNot() {
    String longPlain = "x".repeat(400), longFail = "boom failed " + "y".repeat(400);
    String out = LogCompactor.clipLines(longPlain + "\n" + longFail, 100);
    assertTrue(out.contains("chars]"));
    assertTrue(out.contains("y".repeat(400)));
  }

  @Test public void sameContentIgnoresSurroundingWhitespace() {
    assertTrue(LogCompactor.sameContent(" a\nb \n", "a\nb"));
    assertFalse(LogCompactor.sameContent("", ""));
    assertFalse(LogCompactor.sameContent("a", "b"));
  }

  @Test public void shortTextIsReturnedAsIs() {
    assertEquals("a\nb\nc", LogCompactor.headTail("a\nb\nc", 3, 3));
  }

  @Test public void capKeepsBothEndsAndStatesOmission() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 2000; i++) sb.append("line ").append(i).append('\n');
    String out = LogCompactor.cap(sb.toString(), 1000);
    assertTrue(out.length() < 1200);
    assertTrue(out.startsWith("line 0\n"));
    assertTrue(out.trim().endsWith("line 1999"));
    assertTrue(out.contains("chars omitted"));
  }

  @Test public void capLeavesSmallTextAlone() {
    assertEquals("abc", LogCompactor.cap("abc", 10));
  }

  @Test public void installRecordsAreTrimmedPerRecordNotAsOneBlock() {
    StringBuilder sb = new StringBuilder();
    for (int round = 0; round < 3; round++) {
      sb.append("\n[17911416").append(round).append("0000] PROROOT_RESULT\n");
      for (int i = 0; i < 80; i++) sb.append("Unpacking lib").append(i).append('\n');
      sb.append("ldconfig.real: Renaming of /etc/ld.so.cache~ failed: Read-only file system\n");
    }
    String out = LogCompactor.compactInstall(sb.toString(), 100_000);
    assertTrue(out.contains("Read-only file system"));
    assertTrue(out.length() < sb.length() / 3);
  }
}
