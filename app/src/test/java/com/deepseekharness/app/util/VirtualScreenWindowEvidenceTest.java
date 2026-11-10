package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public final class VirtualScreenWindowEvidenceTest {
  private static final int DISPLAY = 8;

  private static VirtualScreenWindowEvidence.Window window(
      int display, int id, int type, int layer, boolean focused, boolean active, String title) {
    return new VirtualScreenWindowEvidence.Window(
        display, id, type, layer, 0, 0, 1008, 1792, focused, active, title);
  }

  private static VirtualScreenWindowEvidence.Window app(
      int id, int layer, boolean focused, boolean active, String title) {
    return window(
        DISPLAY, id, VirtualScreenWindowPolicy.TYPE_APPLICATION, layer, focused, active, title);
  }

  @Test
  public void uniqueTargetSerializesOnlyControlledFields() {
    var evidence =
        VirtualScreenWindowEvidence.of(
                DISPLAY,
                List.of(
                    app(2, 10, true, true, "  Inbox\n\t(3)\u0000  "),
                    window(
                        DISPLAY,
                        4,
                        VirtualScreenWindowPolicy.TYPE_INPUT_METHOD,
                        20,
                        false,
                        false,
                        "Keyboard")))
            .withTargetPackage("com.example.mail");
    assertEquals(0, evidence.selectedIndex);
    assertFalse(evidence.ambiguous);
    assertEquals(1, evidence.applicationWindows);
    assertEquals(1, evidence.inputMethodWindows);
    assertEquals(2, evidence.target.id);
    assertTrue(evidence.target.focused);

    var fields = evidence.fields();
    assertEquals(Integer.valueOf(1), fields.get("applicationWindowCount"));
    assertEquals(Integer.valueOf(0), fields.get("systemWindowCount"));
    assertEquals(Integer.valueOf(1), fields.get("inputMethodWindowCount"));
    assertEquals(Integer.valueOf(2), fields.get("focusedWindowId"));
    assertEquals(Integer.valueOf(2), fields.get("activeWindowId"));
    assertEquals(Boolean.FALSE, fields.get("windowAmbiguous"));
    assertEquals(Integer.valueOf(DISPLAY), fields.get("targetWindowDisplayId"));
    assertEquals(Integer.valueOf(2), fields.get("targetWindowId"));
    assertEquals("APPLICATION", fields.get("targetWindowType"));
    assertEquals(Integer.valueOf(10), fields.get("targetWindowLayer"));
    assertEquals("0,0,1008,1792", fields.get("targetWindowBounds"));
    assertEquals(Boolean.TRUE, fields.get("targetWindowFocused"));
    assertEquals(Boolean.TRUE, fields.get("targetWindowActive"));
    assertEquals("Inbox (3)", fields.get("targetWindowTitle"));
    assertEquals("com.example.mail", fields.get("targetWindowPackage"));
  }

  @Test
  public void ambiguousApplicationWindowsNeverExposeAGuessedTarget() {
    var evidence =
        VirtualScreenWindowEvidence.of(
            DISPLAY,
            List.of(
                app(2, 1, true, false, "Left"),
                app(3, 1, true, false, "Right"),
                window(
                    DISPLAY,
                    4,
                    VirtualScreenWindowPolicy.TYPE_INPUT_METHOD,
                    9,
                    false,
                    true,
                    "Keyboard")));
    assertEquals(-1, evidence.selectedIndex);
    assertTrue(evidence.ambiguous);
    assertNull(evidence.target);
    assertEquals(2, evidence.applicationWindows);
    assertEquals(1, evidence.inputMethodWindows);
    var fields = evidence.fields();
    assertEquals(Integer.valueOf(2), fields.get("applicationWindowCount"));
    assertEquals(Boolean.TRUE, fields.get("windowAmbiguous"));
    assertFalse(fields.containsKey("targetWindowId"));
    assertFalse(fields.containsKey("targetWindowTitle"));
    assertFalse(fields.containsKey("targetWindowPackage"));
  }

  @Test
  public void displayWithoutApplicationWindowsIsNotAmbiguous() {
    var evidence =
        VirtualScreenWindowEvidence.of(
            DISPLAY,
            List.of(
                window(
                    DISPLAY,
                    4,
                    VirtualScreenWindowPolicy.TYPE_INPUT_METHOD,
                    9,
                    true,
                    true,
                    "Keyboard"),
                window(
                    DISPLAY, 5, VirtualScreenWindowPolicy.TYPE_SYSTEM, 1, false, false, "Bars")));
    assertEquals(-1, evidence.selectedIndex);
    assertFalse(evidence.ambiguous);
    assertEquals(0, evidence.applicationWindows);
    assertEquals(1, evidence.systemWindows);
    assertNull(evidence.target);
    // 其他显示与无效 id 不进入选择与计数。
    var otherDisplay =
        VirtualScreenWindowEvidence.of(
            DISPLAY,
            List.of(
                window(0, 6, VirtualScreenWindowPolicy.TYPE_APPLICATION, 1, true, true, "Other")));
    assertEquals(-1, otherDisplay.selectedIndex);
    assertEquals(0, otherDisplay.applicationWindows);
  }

  @Test
  public void titlesAreBoundedAndPackagesAreValidated() {
    String longTitle = "a".repeat(VirtualScreenWindowEvidence.TITLE_LIMIT + 6);
    var evidence =
        VirtualScreenWindowEvidence.of(DISPLAY, List.of(app(2, 1, true, true, longTitle)))
            .withTargetPackage("bad package!");
    assertEquals(
        "a".repeat(VirtualScreenWindowEvidence.TITLE_LIMIT) + "…",
        evidence.fields().get("targetWindowTitle"));
    assertFalse(evidence.fields().containsKey("targetWindowPackage"));
    assertEquals(
        "com.example.mail",
        VirtualScreenWindowEvidence.of(DISPLAY, List.of(app(2, 1, true, true, "邮件")))
            .withTargetPackage("com.example.mail")
            .fields()
            .get("targetWindowPackage"));
    assertNull(VirtualScreenWindowEvidence.sanitizeTitle(" \n\t\u0000 "));
    assertNull(VirtualScreenWindowEvidence.sanitizePackage("com.example/app"));
    assertNull(VirtualScreenWindowEvidence.sanitizePackage(""));
    assertEquals("ACCESSIBILITY_OVERLAY", VirtualScreenWindowEvidence.typeName(4));
    assertEquals("OTHER", VirtualScreenWindowEvidence.typeName(42));
  }
}
