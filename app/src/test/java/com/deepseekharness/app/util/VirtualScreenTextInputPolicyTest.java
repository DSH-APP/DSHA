package com.deepseekharness.app.util;

import static com.deepseekharness.app.util.VirtualScreenTextInputPolicy.Decision.*;
import static com.deepseekharness.app.util.VirtualScreenTextInputPolicy.FocusEvidence.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class VirtualScreenTextInputPolicyTest {
  @Test
  public void passwordFocusIsHardBlockedAndNeverRetried() {
    assertEquals(BLOCK_PASSWORD, VirtualScreenTextInputPolicy.decide(PASSWORD_FOCUSED));
    assertEquals("PASSWORD_INPUT_UNAVAILABLE", VirtualScreenTextInputPolicy.code(BLOCK_PASSWORD));
    assertEquals("REPORT_TO_USER", VirtualScreenTextInputPolicy.nextAction(BLOCK_PASSWORD));
    assertFalse(VirtualScreenTextInputPolicy.retryable(BLOCK_PASSWORD));
  }

  @Test
  public void readableNonPasswordFocusKeepsNormalInputWorking() {
    assertEquals(ALLOW, VirtualScreenTextInputPolicy.decide(SAFE_FOCUS));
    assertEquals("", VirtualScreenTextInputPolicy.code(ALLOW));
    assertEquals("", VirtualScreenTextInputPolicy.nextAction(ALLOW));
    assertFalse(VirtualScreenTextInputPolicy.retryable(ALLOW));
  }

  @Test
  public void unreadableFocusFailsClosedWhileAccessibilityIsConnected() {
    // 有语义证据通道却读不出焦点：无法排除密码框，宁可拒绝并让调用方重新观察。
    assertEquals(BLOCK_UNVERIFIED, VirtualScreenTextInputPolicy.decide(FOCUS_UNREADABLE));
    assertEquals("PASSWORD_FOCUS_UNVERIFIED", VirtualScreenTextInputPolicy.code(BLOCK_UNVERIFIED));
    assertEquals("SEE_AGAIN", VirtualScreenTextInputPolicy.nextAction(BLOCK_UNVERIFIED));
    assertTrue(VirtualScreenTextInputPolicy.retryable(BLOCK_UNVERIFIED));
  }

  @Test
  public void missingAccessibilityKeepsDocumentedAsciiPathButMarksItUnverified() {
    // 无障碍完全未连接：文档支持纯像素 + ASCII 输入，保持能力，但绝不声称已检查密码焦点。
    assertEquals(ALLOW_UNVERIFIED, VirtualScreenTextInputPolicy.decide(ACCESSIBILITY_UNAVAILABLE));
    assertEquals("", VirtualScreenTextInputPolicy.code(ALLOW_UNVERIFIED));
    assertEquals("", VirtualScreenTextInputPolicy.nextAction(ALLOW_UNVERIFIED));
    assertFalse(VirtualScreenTextInputPolicy.retryable(ALLOW_UNVERIFIED));
  }

  @Test
  public void unknownEvidenceIsTreatedAsUnreadableNotSafe() {
    assertEquals(BLOCK_UNVERIFIED, VirtualScreenTextInputPolicy.decide(null));
    assertEquals("FOCUS_UNREADABLE", VirtualScreenTextInputPolicy.evidenceName(null));
    assertEquals("PASSWORD_FOCUSED", VirtualScreenTextInputPolicy.evidenceName(PASSWORD_FOCUSED));
  }
}
