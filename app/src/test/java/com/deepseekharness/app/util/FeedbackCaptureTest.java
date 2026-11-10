package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FeedbackCaptureTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  @Test
  public void textIsRedactedBeforeItEntersTheArchive() {
    FeedbackCapture capture = new FeedbackCapture();
    assertEquals(
        FeedbackCapture.Outcome.INCLUDED,
        capture.addText(
            "app",
            "app.log",
            "API_KEY=sk-12345678901234567890\nAuthorization: Bearer private-token\nretry=3\n"));
    String archived = text(capture.entries().get("app.log"));
    assertFalse(archived.contains("12345678901234567890"));
    assertFalse(archived.contains("private-token"));
    assertTrue(archived.contains("retry=3"));
  }

  @Test
  public void unsafeNamesAreSkippedWithAStableOutcome() {
    FeedbackCapture capture = new FeedbackCapture();
    for (String name :
        List.of("", "../escape", "/absolute", "a/../b", "manifest.json", "中文.txt", "a/b\\c")) {
      assertEquals(
          name, FeedbackCapture.Outcome.SKIPPED_NAME, capture.addText("app", name, "value"));
    }
    assertEquals(0, capture.entries().size());
  }

  @Test
  public void duplicateEntriesAreSkipped() {
    FeedbackCapture capture = new FeedbackCapture();
    assertEquals(FeedbackCapture.Outcome.INCLUDED, capture.addText("app", "startup.txt", "first"));
    assertEquals(
        FeedbackCapture.Outcome.SKIPPED_DUPLICATE, capture.addText("app", "startup.txt", "second"));
    assertEquals("first", text(capture.entries().get("startup.txt")));
  }

  @Test
  public void entryCountAndTotalBytesAreBounded() {
    FeedbackCapture capture = new FeedbackCapture();
    for (int i = 0; i < FeedbackCapture.MAX_ENTRIES; i++)
      assertEquals(
          FeedbackCapture.Outcome.INCLUDED, capture.addText("app", "entry-" + i + ".txt", "value"));
    assertEquals(
        FeedbackCapture.Outcome.SKIPPED_ENTRY_LIMIT,
        capture.addText("app", "one-too-many.txt", "value"));

    FeedbackCapture bytes = new FeedbackCapture();
    byte[] chunk = new byte[250 * 1024];
    Arrays.fill(chunk, (byte) 'x');
    int accepted = 0;
    for (int i = 0; i < FeedbackCapture.MAX_ENTRIES; i++) {
      FeedbackCapture.Outcome outcome = bytes.addBytes("app", "chunk-" + i + ".bin", chunk);
      if (outcome == FeedbackCapture.Outcome.SKIPPED_TOTAL_LIMIT) break;
      assertEquals(FeedbackCapture.Outcome.INCLUDED, outcome);
      accepted++;
    }
    assertTrue("total cap must trigger", accepted < FeedbackCapture.MAX_ENTRIES);
    assertTrue(bytes.totalBytes() <= FeedbackCapture.MAX_TOTAL_BYTES);
  }

  @Test
  public void filesOutsideTheBaseDirectoryAreRejected() throws Exception {
    File base = temporary.newFolder("base");
    File outside = temporary.newFile("outside.log");
    Files.writeString(outside.toPath(), "secret");
    FeedbackCapture capture = new FeedbackCapture();
    assertEquals(
        FeedbackCapture.Outcome.SKIPPED_OUTSIDE_BASE,
        capture.addFile("app", "outside.log", outside, base, 1024));
    assertEquals(0, capture.entries().size());
  }

  @Test
  public void missingDirectoryAndSymlinkedFilesAreSkipped() throws Exception {
    File base = temporary.newFolder("base");
    FeedbackCapture capture = new FeedbackCapture();
    assertEquals(
        FeedbackCapture.Outcome.SKIPPED_MISSING,
        capture.addFile("app", "missing.log", new File(base, "missing.log"), base, 1024));
    assertEquals(
        FeedbackCapture.Outcome.SKIPPED_NOT_FILE,
        capture.addFile("app", "directory", base, temporary.getRoot(), 1024));
    Path target = temporary.newFile("target.log").toPath();
    Path link = temporary.getRoot().toPath().resolve("link.log");
    Files.createSymbolicLink(link, target);
    assertEquals(
        FeedbackCapture.Outcome.SKIPPED_SYMLINK,
        capture.addFile("app", "link.log", link.toFile(), temporary.getRoot(), 1024));
  }

  @Test
  public void oversizedFileKeepsLatestCompleteLinesAndIsMarkedTruncated() throws Exception {
    File base = temporary.newFolder("base");
    File log = new File(base, "dsh-web.log");
    Path path = log.toPath();
    Files.writeString(path, "API_KEY=" + "privatevalue".repeat(400) + "\nerror=timeout\n");
    FeedbackCapture capture = new FeedbackCapture();
    assertEquals(
        FeedbackCapture.Outcome.INCLUDED_TRUNCATED,
        capture.addFile("dsh-web", "dsh-web.log", log, base, 4096));
    String archived = text(capture.entries().get("dsh-web.log"));
    assertTrue(archived.endsWith("error=timeout\n"));
    assertFalse(archived.contains("privatevalue"));
    assertTrue(capture.entries().get("dsh-web.log").length <= FeedbackCapture.MAX_ENTRY_BYTES);
  }

  @Test
  public void unreadableFileFailureIsRecordedInsteadOfThrown() throws Exception {
    File base = temporary.newFolder("base");
    File denied =
        new File(base, "denied.log") {
          @Override
          public boolean exists() {
            return true;
          }

          @Override
          public boolean isFile() {
            return true;
          }

          @Override
          public long length() {
            throw new SecurityException("length denied");
          }
        };
    FeedbackCapture capture = new FeedbackCapture();
    assertEquals(
        FeedbackCapture.Outcome.FAILED_READ,
        capture.addFile("app", "denied.log", denied, base, 1024));
    assertTrue(capture.entries().isEmpty());
  }

  @Test
  public void statusDescribesLimitsSourcesAndRedactedFailures() {
    FeedbackCapture capture = new FeedbackCapture();
    capture.addText("app", "app.log", "ok");
    capture.fail("logcat", "EXIT_1:Bearer private-token");
    Map<String, Object> status = capture.status(1234L);
    assertEquals(1L, status.get("schema"));
    assertEquals("DSHA_TEST_PACKAGE_FEEDBACK", status.get("kind"));
    assertEquals(false, status.get("automaticUpload"));
    assertEquals(true, status.get("testPackageOnly"));
    assertEquals(1234L, status.get("capturedAtMillis"));
    Map<?, ?> limits = (Map<?, ?>) status.get("limits");
    assertEquals((long) FeedbackCapture.MAX_ENTRIES, limits.get("maxEntries"));
    assertEquals((long) FeedbackCapture.MAX_ENTRY_BYTES, limits.get("maxEntryBytes"));
    assertEquals((long) FeedbackCapture.MAX_TOTAL_BYTES, limits.get("maxTotalBytes"));
    assertEquals(1L, status.get("includedEntryCount"));
    assertEquals(2L, status.get("includedBytes"));
    List<?> sources = (List<?>) status.get("sources");
    assertEquals(2, sources.size());
    Map<?, ?> failed = (Map<?, ?>) sources.get(1);
    assertEquals("logcat", failed.get("source"));
    assertEquals("FAILED_READ", failed.get("outcome"));
    assertFalse(String.valueOf(failed.get("detail")).contains("private-token"));
    assertEquals("SensitiveData.RULES", status.get("redaction"));
  }
}
