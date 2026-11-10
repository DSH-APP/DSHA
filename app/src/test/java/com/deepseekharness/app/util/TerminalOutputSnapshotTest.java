package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSessionClient;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** 用实际终端模拟器核验可见区、换行、历史边界和不可变导出内容。 */
public class TerminalOutputSnapshotTest {
  private static TerminalEmulator emulator(int columns, int rows) {
    return emulator(columns, rows, 2000);
  }

  private static TerminalEmulator emulator(int columns, int rows, int transcriptRows) {
    TerminalSessionClient client =
        (TerminalSessionClient)
            Proxy.newProxyInstance(
                TerminalSessionClient.class.getClassLoader(),
                new Class<?>[] {TerminalSessionClient.class},
                (proxy, method, args) -> null);
    TerminalOutput output =
        new TerminalOutput() {
          @Override
          public void write(byte[] data, int offset, int count) {
            fail("Snapshot must not write terminal input");
          }

          @Override
          public void titleChanged(String oldTitle, String newTitle) {}

          @Override
          public void onCopyTextToClipboard(String text) {}

          @Override
          public void onPasteTextFromClipboard() {}

          @Override
          public void onBell() {}

          @Override
          public void onColorsChanged() {}
        };
    return new TerminalEmulator(output, columns, rows, transcriptRows, client);
  }

  private static void append(TerminalEmulator emulator, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    emulator.append(bytes, bytes.length);
  }

  @Test
  public void visibleScreenUsesScrolledViewportAndHistoryIncludesLiveScreen() {
    TerminalEmulator emulator = emulator(20, 3);
    append(emulator, "first\r\nsecond\r\nthird\r\nfourth");
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY 1", emulator, -1);
    assertEquals("first\nsecond\nthird", snapshot.screen.text());
    assertEquals("first\nsecond\nthird\nfourth", snapshot.history.text());
    assertFalse(snapshot.history.truncated());
  }

  @Test
  public void laterOutputDoesNotMutateEitherSnapshot() {
    TerminalEmulator emulator = emulator(20, 3);
    append(emulator, "before");
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("one", emulator, 0);
    String original = snapshot.history.text();
    append(emulator, "\r\nafter");
    assertEquals(original, snapshot.history.text());
    assertFalse(snapshot.screen.text().contains("after"));
    assertTrue(TerminalOutputSnapshot.capture("two", emulator, 0).history.text().contains("after"));
    assertEquals("one", snapshot.sessionName);
  }

  @Test
  public void unicodeSoftWrapsJoinButHardNewlinesAndIndentationRemain() {
    TerminalEmulator emulator = emulator(6, 4);
    append(emulator, "  😀中文Z\r\nnext");
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, 0);
    assertTrue(snapshot.history.text(), snapshot.history.text().startsWith("  😀中文Z\nnext"));
  }

  @Test
  public void redactsKnownPatternsAfterJoiningWrappedRows() {
    TerminalEmulator emulator = emulator(16, 8);
    String secret = "sk-abcdefghijklmnopqrstuvwx";
    append(emulator, "api_key=" + secret);
    TerminalOutputSnapshot snapshot =
        TerminalOutputSnapshot.capture("token=" + secret, emulator, 0);
    assertFalse(snapshot.history.text().contains(secret));
    assertTrue(snapshot.history.text().contains("api_key=***"));
    assertEquals("token=***", snapshot.sessionName);
  }

  @Test
  public void longHistoryRetainsRecentRowsAndReportsItsLimit() {
    TerminalEmulator emulator = emulator(80, 24);
    StringBuilder text = new StringBuilder("OLDEST\r\n");
    for (int i = 0; i < 1500; i++)
      text.append("line ").append(i).append(" ").append("x".repeat(60)).append("\r\n");
    text.append("LATEST 😀");
    append(emulator, text.toString());
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, 0);
    assertTrue(snapshot.history.truncated());
    assertTrue(snapshot.history.text().length() <= TerminalOutputSnapshot.MAX_CHARACTERS);
    assertFalse(snapshot.history.text().contains("OLDEST"));
    assertTrue(snapshot.history.text().contains("LATEST 😀"));
    assertFalse(Character.isLowSurrogate(snapshot.history.text().charAt(0)));
    assertFalse(snapshot.screen.truncated());
  }

  @Test
  public void redactionExpansionStillRespectsFinalCharacterLimit() {
    TerminalEmulator emulator = emulator(80, 24);
    append(emulator, ("token=x ".repeat(8) + "\r\n").repeat(1100));
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, 0);
    assertTrue(snapshot.history.truncated());
    assertTrue(snapshot.history.text().length() <= TerminalOutputSnapshot.MAX_CHARACTERS);
    assertFalse(snapshot.history.text().contains("token=x"));
    assertTrue(snapshot.history.text().contains("token=***"));
  }

  @Test
  public void truncatedSoftWrappedCredentialIsOmittedBeforeRedaction() {
    TerminalEmulator emulator = emulator(80, 24);
    append(
        emulator,
        "password=" + "A".repeat(TerminalOutputSnapshot.MAX_CHARACTERS + 160) + "\r\nsafe tail");
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, 0);
    assertTrue(snapshot.history.truncated());
    assertTrue(snapshot.history.text().contains("safe tail"));
    assertFalse(snapshot.history.text().contains("AAAA"));
  }

  @Test
  public void finalContinuationRowIsDiscardedEvenWhenItDoesNotWrapAgain() {
    TerminalEmulator emulator = emulator(80, 24);
    String complete = "b".repeat(TerminalOutputSnapshot.MAX_CHARACTERS - 20);
    append(emulator, "password=" + "A".repeat(82) + "\r\n" + complete);
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, 0);
    assertTrue(snapshot.history.truncated());
    assertEquals(complete, snapshot.history.text());
  }

  @Test
  public void oversizedLogicalLineHasNoCredentialTailPreview() {
    TerminalEmulator emulator = emulator(80, 24);
    append(emulator, "password=" + "A".repeat(TerminalOutputSnapshot.MAX_CHARACTERS + 160));
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, 0);
    assertTrue(snapshot.history.truncated());
    assertTrue(snapshot.history.text().isEmpty());
  }

  @Test
  public void historyCharacterCutKeepsMultilinePrivateKeyContext() {
    TerminalEmulator emulator = emulator(80, 24);
    String payload = "private-material-" + "A".repeat(48);
    append(
        emulator,
        "outside before\r\n-----BEGIN RSA PRIVATE KEY-----\r\n"
            + (payload + "\r\n").repeat(1200)
            + "-----END RSA PRIVATE KEY-----\r\nsafe tail 😀");
    String raw = emulator.getScreen().getTranscriptText();
    assertTrue(raw.contains("-----BEGIN RSA PRIVATE KEY-----"));
    assertTrue(raw.length() > TerminalOutputSnapshot.MAX_CHARACTERS);
    String rawTail = raw.substring(raw.length() - TerminalOutputSnapshot.MAX_CHARACTERS);
    assertFalse(rawTail.contains("-----BEGIN"));
    assertTrue(rawTail.contains(payload));
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, 0);
    assertTrue(snapshot.history.truncated());
    assertEquals(UiText.text("[私钥已隐藏]") + "\nsafe tail 😀", snapshot.history.text());
    assertFalse(snapshot.screen.text().contains(payload));
    assertTrue(snapshot.screen.text().endsWith("safe tail 😀"));
  }

  @Test
  public void historyRowCutKeepsPrivateKeyContextEvenBelowCharacterBudget() {
    TerminalEmulator emulator = emulator(80, 3, 12000);
    append(
        emulator,
        "-----BEGIN PRIVATE KEY-----\r\n"
            + "ZQ==\r\n".repeat(10050)
            + "-----END PRIVATE KEY-----\r\nsafe tail");
    String raw = emulator.getScreen().getTranscriptText();
    assertTrue(raw.contains("-----BEGIN PRIVATE KEY-----"));
    assertTrue(raw.length() < TerminalOutputSnapshot.MAX_CHARACTERS);
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, 0);
    assertTrue(snapshot.history.truncated());
    assertEquals(UiText.text("[私钥已隐藏]") + "\nsafe tail", snapshot.history.text());
  }

  @Test
  public void viewportUsesPrivateKeyContextOnBothSidesWithoutExportingOutsideRows() {
    TerminalEmulator emulator = emulator(80, 3);
    append(
        emulator,
        "outside before\r\n-----BEGIN EC PRIVATE KEY-----\r\n"
            + "private one\r\nprivate two\r\nprivate three\r\n"
            + "-----END EC PRIVATE KEY-----\r\noutside after");
    int first = -emulator.getScreen().getActiveTranscriptRows();
    for (int offset = 1; offset <= 3; offset++) {
      TerminalOutputSnapshot snapshot =
          TerminalOutputSnapshot.capture("PTY", emulator, first + offset);
      assertEquals(UiText.text("[私钥已隐藏]"), snapshot.screen.text());
      assertFalse(snapshot.screen.truncated());
      assertTrue(snapshot.history.text().startsWith("outside before\n"));
      assertTrue(snapshot.history.text().endsWith("\noutside after"));
    }
    assertEquals(
        UiText.text("[私钥已隐藏]") + "\noutside after",
        TerminalOutputSnapshot.capture("PTY", emulator, 0).screen.text());
  }

  @Test
  public void viewportInsideSoftWrappedValueKeepsItsOffscreenCredentialPrefix() {
    TerminalEmulator emulator = emulator(16, 3);
    append(emulator, "before\r\npassword=" + "opaque".repeat(20) + "\r\nafter");
    int first = -emulator.getScreen().getActiveTranscriptRows();
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, first + 2);
    assertEquals("***", snapshot.screen.text());
    assertEquals("before\npassword=***\nafter", snapshot.history.text());
    assertFalse(snapshot.screen.truncated());
  }

  @Test
  public void historyCharacterCutKeepsMultilineQuotedValueContext() {
    for (String quote : new String[] {"\"", "'"}) {
      TerminalEmulator emulator = emulator(80, 24);
      String payload = "quoted-private-material-" + "Q".repeat(40);
      append(
          emulator,
          "outside before\r\npassword="
              + quote
              + "\r\n"
              + (payload + "\r\n").repeat(1200)
              + quote
              + "\r\nsafe tail");
      TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, 0);
      assertTrue(snapshot.history.truncated());
      assertEquals("***\nsafe tail", snapshot.history.text());
      assertFalse(snapshot.screen.text().contains(payload));
      assertTrue(snapshot.screen.text().endsWith("safe tail"));
    }
  }

  @Test
  public void incompletePrivateKeyRemainsHiddenWhileTerminalIsStillWriting() {
    for (String type : new String[] {"", "RSA ", "EC ", "OPENSSH ", "ENCRYPTED ", "DSA "}) {
      TerminalEmulator emulator = emulator(80, 3);
      append(
          emulator,
          "public before\r\n-----BEGIN "
              + type
              + "PRIVATE KEY-----\r\nprivate one\r\nprivate two\r\nprivate three");
      TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, 0);
      assertEquals(UiText.text("[私钥已隐藏]"), snapshot.screen.text());
      assertEquals("public before\n" + UiText.text("[私钥已隐藏]"), snapshot.history.text());
    }
  }

  @Test
  public void finalBudgetNeverStartsInsideSurrogatePairAfterRedactionExpansion() {
    TerminalEmulator emulator = emulator(80, 24);
    String body = "b".repeat(TerminalOutputSnapshot.MAX_CHARACTERS - 11);
    append(emulator, "a😀" + body + "\r\ntoken=x");
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, 0);
    assertTrue(snapshot.history.truncated());
    // 原文正好 64K；替换增加两字符，未经代理对保护的尾部会从 😀 的低代理项开始。
    assertEquals(body + "\ntoken=***", snapshot.history.text());
    assertEquals(TerminalOutputSnapshot.MAX_CHARACTERS - 1, snapshot.history.text().length());
  }

  @Test
  public void contextBudgetIsInclusiveAndOverflowDoesNotFallBackToUnredactedTail() {
    TerminalEmulator emulator = emulator(1024, 3);
    append(
        emulator, "x".repeat(TerminalOutputSnapshot.MAX_CONTEXT_CHARACTERS - 10) + "\r\nLATEST 😀");
    TerminalOutputSnapshot atLimit = TerminalOutputSnapshot.capture("PTY", emulator, 0);
    assertTrue(atLimit.history.truncated());
    assertEquals("LATEST 😀", atLimit.history.text());
    append(emulator, "!");
    TerminalOutputSnapshot overflow = TerminalOutputSnapshot.capture("PTY", emulator, 0);
    assertTrue(overflow.screen.truncated());
    assertTrue(overflow.history.truncated());
    assertEquals("", overflow.screen.text());
    assertEquals("", overflow.history.text());
    assertEquals("LATEST 😀", atLimit.history.text());
  }

  @Test
  public void staleScrollPositionClampsToAvailableHistory() {
    TerminalEmulator emulator = emulator(20, 3);
    append(emulator, "first\r\nsecond\r\nthird\r\nfourth");
    assertEquals(
        "first\nsecond\nthird",
        TerminalOutputSnapshot.capture("PTY", emulator, -999).screen.text());
    assertEquals(
        "second\nthird\nfourth", TerminalOutputSnapshot.capture("PTY", emulator, 99).screen.text());
  }

  @Test
  public void alternateScreenNeverMixesHiddenMainHistory() {
    TerminalEmulator emulator = emulator(20, 3);
    append(emulator, "main history\r\n\u001b[?1049hALT");
    TerminalOutputSnapshot snapshot = TerminalOutputSnapshot.capture("PTY", emulator, 0);
    assertTrue(snapshot.history.text().contains("ALT"));
    assertFalse(snapshot.history.text().contains("main history"));
    assertEquals(snapshot.screen.text(), snapshot.history.text());
  }
}
