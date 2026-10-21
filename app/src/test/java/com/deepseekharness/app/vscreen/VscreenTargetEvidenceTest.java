package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import org.junit.Test;

/** Caller source-frame authority cannot be upgraded to a new frame or another anchor. */
public final class VscreenTargetEvidenceTest {
  private static final String CORE = "11111111-2222-3333-4444-555555555555";
  private static final String ANCHOR = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

  private VscreenTargetEvidence evidence(long frame, String id, int display, int window) {
    return new VscreenTargetEvidence(
        CORE,
        "generation-A",
        display,
        window,
        "com.example.app",
        VscreenTargetEvidence.Observation.ANCHORED_NODE,
        frame,
        id,
        17);
  }

  @Test
  public void retainsTheOriginalFrameAndRejectsReplacingItsAuthority() {
    var value = evidence(14, ANCHOR, 2, 7);
    assertEquals("vscreen/anchored_node", value.kind());
    assertTrue(value.matchesAnchored(CORE, "generation-A", 14, ANCHOR, 17));
    assertFalse(value.matchesAnchored(CORE, "generation-A", 15, ANCHOR, 17));
    assertFalse(value.matchesAnchored(CORE, "generation-B", 14, ANCHOR, 17));
    assertFalse(value.matchesAnchored(ANCHOR, "generation-A", 14, ANCHOR, 17));
    assertFalse(value.matchesAnchored(CORE, "generation-A", 14, CORE, 17));
    assertFalse(value.matchesAnchored(CORE, "generation-A", 14, ANCHOR, 18));
    assertEquals(14, value.frameSequence);
  }

  @Test
  public void anchoredAuthorityRequiresCompleteExplicitIdentity() {
    assertThrows(IllegalArgumentException.class, () -> evidence(0, ANCHOR, 2, 7));
    assertThrows(IllegalArgumentException.class, () -> evidence(14, "latest", 2, 7));
    assertThrows(IllegalArgumentException.class, () -> evidence(14, ANCHOR, 0, 7));
    assertThrows(IllegalArgumentException.class, () -> evidence(14, ANCHOR, 2, -1));
    var pixel =
        new VscreenTargetEvidence(
            CORE,
            "generation-A",
            2,
            7,
            "com.example.app",
            VscreenTargetEvidence.Observation.PIXEL,
            14,
            ANCHOR);
    assertFalse(pixel.matchesAnchored(CORE, "generation-A", 14, ANCHOR, 17));
    var observation =
        new VscreenTargetEvidence(
            CORE,
            "generation-A",
            2,
            7,
            "com.example.app",
            VscreenTargetEvidence.Observation.OBSERVED_ANCHOR,
            14,
            ANCHOR,
            17);
    assertTrue(observation.matchesObservedAnchor(CORE, "generation-A", 14, ANCHOR, 17));
    assertFalse(observation.matchesAnchored(CORE, "generation-A", 14, ANCHOR, 17));
    assertFalse(observation.matchesObservedAnchor(CORE, "generation-A", 14, ANCHOR, 18));
  }
}
