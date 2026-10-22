package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class VirtualScreenBootstrapTest {
  private static void feed(VirtualScreenBootstrap parser, String value) throws IOException {
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) parser.accept(b & 255);
  }

  private static final String PAYLOAD =
      "1 49152 "
          + "a".repeat(48)
          + " 123:456:2000:11111111-2222-3333-4444-555555555555:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

  @Test
  public void romNoiseDoesNotInvalidateExactBootstrap() throws Exception {
    var parser = new VirtualScreenBootstrap();
    feed(
        parser,
        "vivo resources warning\n[okhttp] noisy permission check\r\n"
            + "DSHA_VSCREEN_BOOTSTRAP "
            + PAYLOAD
            + "\n");
    assertEquals("a".repeat(48), parser.token());
  }

  @Test
  public void childFailureAfterExitIsParsedWithoutGenericConnectionError() throws Exception {
    String code = "CORE_CONTEXT_SDK30_NullPointerException";
    var parser = new VirtualScreenBootstrap();
    IOException failure =
        assertThrows(
            IOException.class, () -> feed(parser, "ROM notice\nDSHA_VSCREEN_ERROR=" + code + "\n"));
    assertEquals(code, failure.getMessage());
    assertEquals(code, VirtualScreenBootstrap.failureCode("LAUNCH", 30, failure));
  }

  @Test
  public void malformedMarkersAndFloodCannotGrantToken() {
    assertThrows(
        IOException.class,
        () -> feed(new VirtualScreenBootstrap(), "DSHA_VSCREEN_BOOTSTRAP arbitrary-secret\n"));
    assertThrows(IOException.class, () -> feed(new VirtualScreenBootstrap(), "x".repeat(4097)));
    assertThrows(
        IOException.class, () -> feed(new VirtualScreenBootstrap(), "noise\n".repeat(12000)));
    assertEquals(
        "x",
        VirtualScreenBootstrap.error(
            "DSHA_VSCREEN_ERROR=x\r\nX-Token: secret\ninline DSHA_VSCREEN_ERROR=BAD"));
    assertEquals("", VirtualScreenBootstrap.error("prefix DSHA_VSCREEN_ERROR=BAD"));
  }

  @Test
  public void codeHasSdkStageAndTypeButNeverExceptionSecret() {
    String value =
        VirtualScreenBootstrap.failureCode(
            "BIND_LISTENER",
            30,
            new IOException("token=secret", new SecurityException("sensitive")));
    assertEquals("CORE_BIND_LISTENER_SDK30_PERMISSION_DENIED", value);
    assertFalse(value.contains("secret"));
  }

  @Test
  public void managedAdbFailuresBecomeFixedCodesWithoutOutputSecrets() {
    String secret = "private-token-value";
    assertEquals(
        "ADB_ENVIRONMENT_BUSY", VirtualScreenBootstrap.error("ENVIRONMENT_BUSY: " + secret));
    assertEquals(
        "ADB_START_BRIDGE_AUTH_REJECTED",
        VirtualScreenBootstrap.error("[POLICY_BLOCKED] ADB_START_BRIDGE_AUTH_REJECTED " + secret));
    assertEquals(
        "ADB_COMMIT_BRIDGE_TIMEOUT",
        VirtualScreenBootstrap.error("EXECUTION_UNKNOWN: ADB_COMMIT_BRIDGE_TIMEOUT " + secret));
    assertEquals(
        "ADB_TICKET_MISSING",
        VirtualScreenBootstrap.error(
            "[POLICY_BLOCKED] Native virtual-screen launch ticket missing " + secret));
    assertEquals("ADB_CONNECT_FAILED", VirtualScreenBootstrap.error("CONNECT_FAIL: " + secret));
    assertEquals("ADB_PROCESS_FAILED", VirtualScreenBootstrap.error("ADB_ERROR: " + secret));
    assertEquals(
        "ADB_EXECUTION_UNKNOWN", VirtualScreenBootstrap.error("EXECUTION_UNKNOWN: " + secret));
    assertEquals("", VirtualScreenBootstrap.error(secret));
  }

  @Test
  public void bootstrapAndCoreErrorStillHaveTheirExactOriginalMeaning() {
    assertEquals(
        "", VirtualScreenBootstrap.error("DSHA_VSCREEN_STARTED " + "a".repeat(48) + "\n[EXIT=0]"));
    assertEquals(
        "CORE_CONTEXT_SDK37_SecurityException",
        VirtualScreenBootstrap.error(
            "noise\nDSHA_VSCREEN_ERROR=CORE_CONTEXT_SDK37_SecurityException\n[EXIT=1]"));
  }

  @Test
  public void ordinaryRomOutputIsNotTreatedAsAnAdbFailureDuringBootstrap() throws Exception {
    var parser = new VirtualScreenBootstrap();
    feed(parser, "ROM notice ENVIRONMENT_BUSY\nDSHA_VSCREEN_BOOTSTRAP " + PAYLOAD + "\n");
    assertEquals("a".repeat(48), parser.token());
  }

  @Test
  public void logCodesRejectArbitrarySecretsAndKeepKnownMachineFailures() {
    assertEquals(
        "VSCREEN_CLOSE_UNKNOWN", VirtualScreenBootstrap.diagnosticCode("VSCREEN_CLOSE_UNKNOWN"));
    assertEquals(
        "VSCREEN_START_UNKNOWN", VirtualScreenBootstrap.diagnosticCode("VSCREEN_START_UNKNOWN"));
    assertEquals(
        "VSCREEN_FAILURE_UNCLASSIFIED",
        VirtualScreenBootstrap.diagnosticCode("VSCREEN_START_NoClassDefFoundError"));
    assertEquals(
        "VSCREEN_NATIVE_API_UNAVAILABLE",
        VirtualScreenBootstrap.diagnosticCode("VSCREEN_NATIVE_API_UNAVAILABLE"));
    assertEquals(
        "VSCREEN_CORE_CONTEXT_SDK37_PERMISSION_DENIED",
        VirtualScreenBootstrap.diagnosticCode("VSCREEN_CORE_CONTEXT_SDK37_PERMISSION_DENIED"));
    assertEquals(
        "VSCREEN_FAILURE_UNCLASSIFIED",
        VirtualScreenBootstrap.diagnosticCode("VSCREEN_CORE_CONTEXT_SDK37_SecurityException"));
    assertEquals(
        "VSCREEN_ADB_START_BRIDGE_TIMEOUT",
        VirtualScreenBootstrap.diagnosticCode("VSCREEN_ADB_START_BRIDGE_TIMEOUT"));
    assertEquals(
        "VSCREEN_FAILURE_UNCLASSIFIED", VirtualScreenBootstrap.diagnosticCode("token=private"));
    assertEquals(
        "VSCREEN_FAILURE_UNCLASSIFIED",
        VirtualScreenBootstrap.diagnosticCode("VSCREEN_ADB_START_BRIDGE_TIMEOUT_private"));
  }
}
