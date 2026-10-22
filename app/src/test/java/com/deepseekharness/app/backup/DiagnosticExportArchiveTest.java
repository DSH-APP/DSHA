package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class DiagnosticExportArchiveTest {
  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  public void ordinaryTarGzipContainsExactEntriesAndChecksummedExplicitCaptureLimits()
      throws Exception {
    Map<String, byte[]> input = new LinkedHashMap<>();
    input.put("summary.txt", bytes("local status\n"));
    input.put("status.json", bytes("{\"ready\":false}"));
    input.put(
        "operations-tail.jsonl",
        DiagnosticExportArchive.operationEvents(
            "10-19 12:34:56 [MAINTENANCE] original retained\nunknown historic line\n"));
    Map<String, Object> capture =
        Map.of("operationHistory", "TAIL", "fullExceptionChains", "NOT_RECONSTRUCTED");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    DiagnosticExportArchive.write(output, input, capture, new BackupControl(null));
    Map<String, byte[]> found = new LinkedHashMap<>();
    LegacyTarReader.read(
        new ByteArrayInputStream(output.toByteArray()),
        (member, stream) -> {
          assertEquals("FILE", member.type);
          found.put(member.path, stream.readAllBytes());
        },
        new BackupControl(null));
    assertEquals(input.size() + 1, found.size());
    for (var entry : input.entrySet())
      assertArrayEquals(entry.getValue(), found.get(entry.getKey()));
    assertFalse(found.containsKey("additional-context.txt"));
    Map<String, Object> manifest = BackupJson.read(found.get("manifest.json"), 256 * 1024);
    assertEquals(false, manifest.get("dataBackup"));
    assertEquals(false, manifest.get("automaticUpload"));
    assertEquals(capture, manifest.get("capture"));
    for (Object value : (List<?>) manifest.get("entries")) {
      Map<?, ?> row = (Map<?, ?>) value;
      byte[] actual = found.get(row.get("path"));
      assertEquals((long) actual.length, row.get("bytes"));
      assertEquals(BackupArchive.hex(BackupArchive.sha().digest(actual)), row.get("sha256"));
    }
  }

  @Test
  public void historicalOperationTailIsStructuredAndRedactedWithoutInventedDates()
      throws Exception {
    String events =
        new String(
            DiagnosticExportArchive.operationEvents(
                "10-19 12:34:56 [STARTUP] apiKey=synthetic-secret-value\nunknown historic line\n"),
            StandardCharsets.UTF_8);
    assertFalse(events.contains("synthetic-secret-value"));
    String[] lines = events.strip().split("\\n");
    assertEquals(2, lines.length);
    var first = BackupJson.read(bytes(lines[0]), 65536);
    assertEquals("10-19 12:34:56", first.get("recordedTimeWithoutYearOrZone"));
    assertEquals("STARTUP", first.get("stage"));
    assertEquals("TAIL", first.get("capture"));
    assertEquals(1L, first.get("sequenceInExport"));
    assertFalse(first.containsKey("timestampMillis"));
    var second = BackupJson.read(bytes(lines[1]), 65536);
    assertEquals("UNPARSED_EXISTING_LINE", second.get("stage"));
    assertEquals("unknown historic line", second.get("message"));
  }

  @Test
  public void invalidOrOversizedEntryFailsBeforeWritingArchiveBytes() throws Exception {
    for (String name : List.of("../escape", "/absolute", "manifest.json", "a/../b", "")) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      assertThrows(
          IOException.class,
          () ->
              DiagnosticExportArchive.write(
                  output, Map.of(name, bytes("private")), Map.of(), new BackupControl(null)));
      assertEquals(0, output.size());
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertThrows(
        IOException.class,
        () ->
            DiagnosticExportArchive.write(
                output,
                Map.of("too-large.txt", new byte[256 * 1024 + 1]),
                Map.of(),
                new BackupControl(null)));
    assertEquals(0, output.size());
  }

  @Test
  public void explicitlySelectedAdditionalContextIsAnOrdinaryChecksummedMember() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    DiagnosticExportArchive.write(
        output,
        Map.of("additional-context.txt", bytes("user-provided note")),
        Map.of("additionalContextIncluded", true),
        new BackupControl(null));
    final boolean[] included = {false};
    LegacyTarReader.read(
        new ByteArrayInputStream(output.toByteArray()),
        (member, stream) -> {
          if (member.path.equals("additional-context.txt")) {
            included[0] = true;
            assertEquals(
                "user-provided note", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
          }
        },
        new BackupControl(null));
    assertTrue(included[0]);
  }

  @Test
  public void cancellationDoesNotPublishEvenAnEmptyArchiveHeader() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    BackupControl control = new BackupControl(null);
    control.cancel();
    assertThrows(
        java.io.InterruptedIOException.class,
        () ->
            DiagnosticExportArchive.write(
                output, Map.of("summary.txt", bytes("status")), Map.of(), control));
    assertEquals(0, output.size());
  }
}
