package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

/** 只测 DSHA 的输入策略；不把纯逻辑测试当作真机 IME 验证。 */
public class TerminalInputTest {
  @Test
  public void trailingEnterNeverBecomesAutomaticExecution() {
    var result = TerminalInput.preparePaste("echo hello\r\n\n\r");
    assertEquals("echo hello", result.text);
    assertTrue(result.removedTrailingNewlines);
    assertTrue(result.multiline);
    assertTrue(result.allowed());
  }

  @Test
  public void internalNewlinesArePreservedForExplicitlyConfirmedPaste() {
    var result = TerminalInput.preparePaste("printf one\r\nprintf two\r");
    assertEquals("printf one\nprintf two", result.text);
    assertTrue(result.multiline);
    assertFalse(result.text.endsWith("\n"));
  }

  @Test
  public void protocolControlsCannotEscapeBracketedPaste() {
    var result = TerminalInput.preparePaste("a\u001b[201~\u0000\u0003\u0004\u007f\u009b\u009f\tb");
    assertEquals("a[201~\tb", result.text);
    assertTrue(result.removedControls);
  }

  @Test
  public void unicodeCommittedTextIsNeitherTranslatedNorSplit() {
    String text = "中文日本語🙂e\u0301 العربية";
    assertEquals(text, TerminalInput.preparePaste(text).text);
    assertFalse(TerminalInput.preparePaste(text).removedControls);
  }

  @Test
  public void noEnterIsInventedForSingleLine() {
    var result = TerminalInput.preparePaste("printf hello ");
    assertEquals("printf hello ", result.text);
    assertFalse(result.multiline);
    assertFalse(result.removedTrailingNewlines);
  }

  @Test
  public void emptyOrAllNewlineInputIsRejected() {
    assertFalse(TerminalInput.preparePaste(null).allowed());
    assertFalse(TerminalInput.preparePaste("").allowed());
    assertFalse(TerminalInput.preparePaste("\r\n\n").allowed());
    assertFalse(TerminalInput.preparePaste("\u001b\u0000").allowed());
  }

  @Test
  public void overLimitIsRejectedRatherThanSilentlyTruncated() {
    assertTrue(TerminalInput.preparePaste("x".repeat(16 * 1024)).allowed());
    var tooLong = TerminalInput.preparePaste("x".repeat(16 * 1024 + 1));
    assertTrue(tooLong.tooLong);
    assertFalse(tooLong.allowed());
    assertEquals("", tooLong.text);
  }

  @Test
  public void modifierReadsDoNotConsumeBeforeNativeSnapshotsBothKeys() {
    var modifiers = new TerminalInput.Modifiers();
    modifiers.toggleControl();
    modifiers.toggleAlt();
    assertTrue(modifiers.control());
    assertTrue(modifiers.control());
    assertTrue(modifiers.alt());
    assertTrue(modifiers.alt());
    modifiers.clear();
    assertFalse(modifiers.control());
    assertFalse(modifiers.alt());
  }

  @Test
  public void modifiersToggleOffAndClearOnFocusOrSessionLoss() {
    var modifiers = new TerminalInput.Modifiers();
    modifiers.toggleControl();
    modifiers.toggleControl();
    assertFalse(modifiers.control());
    modifiers.toggleAlt();
    modifiers.clear();
    modifiers.clear();
    assertFalse(modifiers.alt());
    assertFalse(modifiers.control());
  }

  @Test
  public void confirmationRequiresSameEpochSessionViewAndInputPermission() {
    assertTrue(TerminalInput.sameTarget("epoch", 2, 8, "epoch", 2, 8, true));
    assertFalse(TerminalInput.sameTarget("epoch", 2, 8, "new", 2, 8, true));
    assertFalse(TerminalInput.sameTarget("epoch", 2, 8, "epoch", 3, 8, true));
    assertFalse(TerminalInput.sameTarget("epoch", 2, 8, "epoch", 2, 9, true));
    assertFalse(TerminalInput.sameTarget("epoch", 2, 8, "epoch", 2, 8, false));
    assertFalse(TerminalInput.sameTarget(null, 2, 8, null, 2, 8, true));
    assertFalse(TerminalInput.sameTarget("epoch", 0, 8, "epoch", 0, 8, true));
  }
}
