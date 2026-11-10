package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class DiagnosticOperationSummaryTest {
  private static final String YAML =
      "Error: failed to parse overlay /root/.dsh/profiles/web/cordis.patch.yml: YAMLException: end of the stream or a document separator is expected (92:1)\n 89 | private business prompt\n 92 | private user instructions";

  @Test
  public void yamlKeepsOnlySafeClassificationAndReportedPosition() {
    String result = DiagnosticOperationSummary.safe(YAML);
    assertEquals(
        "PARSER_FAILURE type=YAML target=WEB_PROFILE_PATCH reason=YAML_DOCUMENT_BOUNDARY line=92 column=1",
        result);
    assertFalse(result.contains("private"));
    assertFalse(result.contains("/root"));
  }

  @Test
  public void nestedEncodedAndOtherParserErrorsCannotCopySourceSnippets() {
    for (String input :
        new String[] {
          "wrapper error cause: " + YAML,
          YAML.replace("YAMLException", "YAML&#69;xception"),
          YAML.replace("YAMLException", "YAML\\u0045xception"),
          "SyntaxError: Unexpected token private-business in JSON at position 123",
          "org.json.JSONException: private-business",
          "org.xml.sax.SAXParseException: private-business <secret>"
        }) {
      String summary = DiagnosticOperationSummary.safe(input);
      assertTrue(summary.startsWith("PARSER_FAILURE type="));
      assertFalse(summary.contains("private-business"));
      assertFalse(summary.contains("private user instructions"));
    }
    assertEquals(
        "PARSER_FAILURE type=JSON target=UNSPECIFIED reason=UNEXPECTED_TOKEN position=123",
        DiagnosticOperationSummary.safe(
            "SyntaxError: Unexpected token private-business in JSON at position 123"));
  }

  @Test
  public void historicMultilineParserRecordIsSanitizedWithoutDroppingNextEvent() {
    String result =
        DiagnosticOperationSummary.history(
            "10-23 20:30:39 [STARTUP_ERROR] "
                + YAML
                + "\n10-23 20:33:48 [RECOVERY_FAILED] RECOVERY_INPUT_LIMIT\n");
    assertEquals(2, result.strip().split("\\n").length);
    assertFalse(result.contains("private"));
    assertTrue(result.contains("[RECOVERY_FAILED] RECOVERY_INPUT_LIMIT"));
    assertEquals(result, DiagnosticOperationSummary.history(result));
  }

  @Test
  public void ordinaryErrorsRetainTheirEvidenceAndKnownSecretsAreRedacted() {
    assertEquals(
        "plugin error: business text may remain",
        DiagnosticOperationSummary.safe("plugin error: business text may remain"));
    assertEquals(
        "HTTP 403 token=***", DiagnosticOperationSummary.safe("HTTP 403 token=private-token"));
    assertEquals(
        "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found",
        DiagnosticOperationSummary.safe(
            "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found"));
  }

  @Test
  public void unframedHistoricContentAndContinuationBodiesAreNotExportedAsOperations() {
    String history =
        "private unframed config\n10-23 20:30:39 [STARTUP_ERROR] wrapper failed\nCause: "
            + YAML
            + "\n10-23 20:33:48 [RECOVERY_FAILED] RECOVERY_INPUT_LIMIT\nprivate trailing context\n";
    String result = DiagnosticOperationSummary.history(history);
    assertEquals(2, result.strip().split("\\n").length);
    assertFalse(result.contains("private"));
    assertTrue(result.contains("PARSER_FAILURE type=YAML"));
  }
}
