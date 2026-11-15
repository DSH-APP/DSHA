package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class GuestTimingEvidenceTest {
  @Test
  public void parsesApplicationProgressLineOnly() {
    GuestTimingEvidence.Phase phase =
        GuestTimingEvidence.parse(
            "REGISTER_TIMING: {\"phase\":\"runtime-links-stamp\",\"elapsedMs\":64.538,\"entries\":101}");
    assertNotNull(phase);
    assertEquals("runtime-links-stamp", phase.name);
    assertEquals(64, phase.elapsedMs);
    assertNull(GuestTimingEvidence.parse("REGISTER_TIMING: not-json"));
    assertNull(GuestTimingEvidence.parse("BUILTIN_REGISTER_OK: 10 个内置插件注册就绪"));
    assertNull(GuestTimingEvidence.parse("x".repeat(600)));
  }

  @Test
  public void lastLineWinsAndIgnoresUnparsableTrailingOutput() {
    List<String> lines =
        List.of(
            "REGISTER_TIMING: {\"phase\":\"runtime-links-stamp\",\"elapsedMs\":64}",
            "RUNTIME_LINKS_CHECK: 检查共享依赖链接",
            "REGISTER_TIMING: {\"phase\":\"runtime-links-repair\",\"elapsedMs\":530.8,\"changed\":0}",
            "用户或插件正文");
    GuestTimingEvidence.Phase phase = GuestTimingEvidence.last(lines);
    assertNotNull(phase);
    assertEquals("runtime-links-repair", phase.name);
    assertEquals(530, phase.elapsedMs);
    assertNull(GuestTimingEvidence.last(List.of("nothing")));
    assertNull(GuestTimingEvidence.last(null));
  }

  @Test
  public void evidenceKeepsHostAndGuestNumbersSeparate() {
    GuestTimingEvidence.Phase phase =
        GuestTimingEvidence.parse(
            "REGISTER_TIMING: {\"phase\":\"runtime-links-check\",\"elapsedMs\":87000}");
    String evidence = GuestTimingEvidence.evidence(90_012, phase);
    assertTrue(evidence, evidence.contains("hostElapsedMs=90012"));
    assertTrue(evidence, evidence.contains("lastGuestPhase=runtime-links-check"));
    assertTrue(evidence, evidence.contains("guestElapsedMs=87000"));
    assertFalse(evidence, evidence.contains("GUEST_TIMING_MISMATCH"));
    assertEquals(
        "hostElapsedMs=90012 lastGuestPhase=none", GuestTimingEvidence.evidence(90_012, null));
  }

  @Test
  public void guestTimeBeyondHostWallClockIsExplicitMismatch() {
    GuestTimingEvidence.Phase phase =
        GuestTimingEvidence.parse(
            "REGISTER_TIMING: {\"phase\":\"runtime-links-stamp\",\"elapsedMs\":479409}");
    String evidence = GuestTimingEvidence.evidence(90_000, phase);
    assertTrue(evidence, evidence.endsWith("GUEST_TIMING_MISMATCH"));
    // 容差内不误报：guest 只可能略晚于宿主超时判定。
    GuestTimingEvidence.Phase near =
        GuestTimingEvidence.parse(
            "REGISTER_TIMING: {\"phase\":\"runtime-links-check\",\"elapsedMs\":93000}");
    assertFalse(GuestTimingEvidence.evidence(90_000, near).contains("GUEST_TIMING_MISMATCH"));
  }
}
