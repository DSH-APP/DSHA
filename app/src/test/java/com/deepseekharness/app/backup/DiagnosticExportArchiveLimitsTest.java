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

/** 测试包反馈导出用参数化上限；正式诊断导出仍固定 1 MB，不能跟着放宽。 */
public class DiagnosticExportArchiveLimitsTest {
  private static final DiagnosticExportArchive.Limits FEEDBACK =
      new DiagnosticExportArchive.Limits(48, 256 * 1024, 8 * 1024 * 1024);

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  public void parameterizedLimitsAllowLongerBoundedArchive() throws Exception {
    Map<String, byte[]> input = new LinkedHashMap<>();
    for (int i = 0; i < 40; i++) input.put("log-" + i + ".txt", new byte[100 * 1024]);
    Map<String, Object> capture =
        Map.of("kind", "DSHA_TEST_PACKAGE_FEEDBACK", "testPackageOnly", true);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    DiagnosticExportArchive.write(output, input, capture, new BackupControl(null), FEEDBACK);
    Map<String, byte[]> found = new LinkedHashMap<>();
    LegacyTarReader.read(
        new ByteArrayInputStream(output.toByteArray()),
        (member, stream) -> found.put(member.path, stream.readAllBytes()),
        new BackupControl(null));
    assertEquals(input.size() + 1, found.size());
    Map<String, Object> manifest = BackupJson.read(found.get("manifest.json"), 256 * 1024);
    assertEquals(capture, manifest.get("capture"));
    List<?> rows = (List<?>) manifest.get("entries");
    assertEquals(input.size(), rows.size());
    for (Object value : rows) {
      Map<?, ?> row = (Map<?, ?>) value;
      byte[] actual = found.get(row.get("path"));
      assertEquals((long) actual.length, row.get("bytes"));
      assertEquals(BackupArchive.hex(BackupArchive.sha().digest(actual)), row.get("sha256"));
    }
  }

  @Test
  public void productionOverloadStillEnforcesOneMegabyteTotal() throws Exception {
    Map<String, byte[]> input = new LinkedHashMap<>();
    for (int i = 0; i < 5; i++) input.put("log-" + i + ".txt", new byte[256 * 1024]);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertThrows(
        IOException.class,
        () -> DiagnosticExportArchive.write(output, input, Map.of(), new BackupControl(null)));
    assertEquals(0, output.size());
  }

  @Test
  public void parameterizedTotalLimitIsEnforced() throws Exception {
    DiagnosticExportArchive.Limits tight =
        new DiagnosticExportArchive.Limits(8, 256 * 1024, 300 * 1024);
    Map<String, byte[]> input = new LinkedHashMap<>();
    input.put("a.txt", new byte[200 * 1024]);
    input.put("b.txt", new byte[200 * 1024]);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertThrows(
        IOException.class,
        () ->
            DiagnosticExportArchive.write(output, input, Map.of(), new BackupControl(null), tight));
    assertEquals(0, output.size());
  }

  @Test
  public void invalidLimitsAreRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new DiagnosticExportArchive.Limits(0, 1024, 4096));
    assertThrows(
        IllegalArgumentException.class, () -> new DiagnosticExportArchive.Limits(8, 8192, 4096));
  }
}
