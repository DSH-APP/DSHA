package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupArchive;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.DiagnosticExportArchive;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.backup.LegacyTarReader;
import com.deepseekharness.app.util.AccessibilityServiceLifecycleEvents;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic callback facts through the real export transforms; no Android lifecycle acceptance. */
public final class AccessibilityLifecycleExportTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void oldAndReplacementServiceFactsSurviveHistoryJsonlAndChecksummedArchive()
      throws Exception {
    var callbacks = new AccessibilityServiceLifecycleEvents();
    callbacks.record(AccessibilityServiceLifecycleEvents.Kind.CONNECTED, 1);
    callbacks.record(AccessibilityServiceLifecycleEvents.Kind.UNBOUND, 1);
    callbacks.record(AccessibilityServiceLifecycleEvents.Kind.CONNECTED, 4);
    callbacks.record(AccessibilityServiceLifecycleEvents.Kind.DESTROYED, 1);
    callbacks.record(AccessibilityServiceLifecycleEvents.Kind.INTERRUPTED, 4);
    var facts = callbacks.drainEvents();
    StringBuilder history = new StringBuilder();
    for (var event : facts)
      history
          .append("10-23 21:30:00 [")
          .append(event.kind().code())
          .append("] serviceEpoch=")
          .append(event.serviceEpoch())
          .append('\n');
    var fs = new JvmBackupFileSystem();
    var directory = temporary.newFolder();
    DiagnosticHistory.write(fs, directory, history.toString());
    byte[] operationTail =
        DiagnosticExportArchive.operationEvents(DiagnosticLog.exportTail(fs, directory));
    ByteArrayOutputStream archive = new ByteArrayOutputStream();
    DiagnosticExportArchive.write(
        archive,
        Map.of("operations-tail.jsonl", operationTail),
        Map.of(
            "businessBodies", "MAY_BE_PRESENT_IN_FREE_FORM_ERRORS",
            "historicalRecordIdentity", "NOT_AUTHENTICATED"),
        new BackupControl(null));
    Map<String, byte[]> members = new LinkedHashMap<>();
    LegacyTarReader.read(
        new ByteArrayInputStream(archive.toByteArray()),
        (member, stream) -> members.put(member.path, stream.readAllBytes()),
        new BackupControl(null));
    assertArrayEquals(operationTail, members.get("operations-tail.jsonl"));
    String[] rows =
        new String(members.get("operations-tail.jsonl"), StandardCharsets.UTF_8)
            .strip()
            .split("\\n");
    assertEquals(facts.size(), rows.length);
    for (int index = 0; index < rows.length; index++) {
      var row = BackupJson.read(rows[index].getBytes(StandardCharsets.UTF_8), 65536);
      assertEquals(facts.get(index).kind().code(), row.get("stage"));
      assertEquals("serviceEpoch=" + facts.get(index).serviceEpoch(), row.get("message"));
      assertEquals("EXISTING_NATIVE_OPERATION_TAIL", row.get("source"));
      assertEquals("TAIL", row.get("capture"));
      assertEquals(index + 1L, row.get("sequenceInExport"));
      assertFalse(row.containsKey("timestampMillis"));
      assertFalse(row.containsKey("bound"));
      assertFalse(row.containsKey("repairSucceeded"));
    }
    var manifest = BackupJson.read(members.get("manifest.json"), 256 * 1024);
    var entry = (Map<?, ?>) ((List<?>) manifest.get("entries")).get(0);
    assertEquals(BackupArchive.hex(BackupArchive.sha().digest(operationTail)), entry.get("sha256"));
    assertEquals(
        "MAY_BE_PRESENT_IN_FREE_FORM_ERRORS",
        ((Map<?, ?>) manifest.get("capture")).get("businessBodies"));
    assertEquals(
        "NOT_AUTHENTICATED", ((Map<?, ?>) manifest.get("capture")).get("historicalRecordIdentity"));
  }

  @Test
  public void exportRetainsFreeFormBoundaryWhileOmittingRecognizedParserSourceAndContinuation()
      throws Exception {
    String history =
        "10-23 21:30:00 [STARTUP_ERROR] custom failure cites private-business-body\n"
            + "private unframed continuation\n"
            + "10-23 21:30:01 [RECOVERY_ERROR] YAMLException: bad indentation (92:1)\n"
            + "92 | private-parser-source\n";
    var fs = new JvmBackupFileSystem();
    var directory = temporary.newFolder();
    DiagnosticHistory.write(fs, directory, history);
    byte[] tail = DiagnosticExportArchive.operationEvents(DiagnosticLog.exportTail(fs, directory));
    String exported = new String(tail, StandardCharsets.UTF_8);
    assertTrue(exported.contains("private-business-body"));
    assertFalse(exported.contains("private unframed continuation"));
    assertFalse(exported.contains("private-parser-source"));
    assertTrue(exported.contains("PARSER_FAILURE type=YAML"));
    assertTrue(exported.contains("line=92 column=1"));
    assertEquals(2, exported.strip().split("\\n").length);
    assertEquals(history, DiagnosticHistory.read(fs, directory));
  }
}
