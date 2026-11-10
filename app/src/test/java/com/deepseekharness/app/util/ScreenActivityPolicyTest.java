package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 纯规则：事件分类、敏感应用/密码框排除、单行截断与去重窗口，不依赖 Android。 */
public final class ScreenActivityPolicyTest {
  @Test
  public void classifiesAccessibilityEventsAndDispatchSource() {
    assertEquals(
        ScreenActivityEvent.Kind.TARGET_CHANGED,
        ScreenActivityPolicy.kind(ScreenActivityPolicy.EventType.WINDOW_STATE_CHANGED));
    assertEquals(
        ScreenActivityEvent.Kind.USER_TAP,
        ScreenActivityPolicy.kind(ScreenActivityPolicy.EventType.VIEW_CLICKED));
    assertEquals(
        ScreenActivityEvent.Kind.USER_SCROLL,
        ScreenActivityPolicy.kind(ScreenActivityPolicy.EventType.VIEW_SCROLLED));
    assertEquals(
        ScreenActivityEvent.Kind.CONTENT_CHANGED,
        ScreenActivityPolicy.kind(ScreenActivityPolicy.EventType.WINDOW_CONTENT_CHANGED));
    assertEquals(null, ScreenActivityPolicy.kind(null));
    assertEquals(ScreenActivityEvent.Source.USER, ScreenActivityPolicy.source(false));
    assertEquals(ScreenActivityEvent.Source.ASSISTANT, ScreenActivityPolicy.source(true));
  }

  @Test
  public void sensitiveAppsKeepOnlyTargetChanges() {
    assertTrue(ScreenActivityPolicy.recordable(ScreenActivityEvent.Kind.TARGET_CHANGED, true));
    assertFalse(ScreenActivityPolicy.recordable(ScreenActivityEvent.Kind.USER_TAP, true));
    assertFalse(ScreenActivityPolicy.recordable(ScreenActivityEvent.Kind.USER_SCROLL, true));
    assertFalse(ScreenActivityPolicy.recordable(ScreenActivityEvent.Kind.CONTENT_CHANGED, true));
    assertFalse(ScreenActivityPolicy.recordable(ScreenActivityEvent.Kind.ASSISTANT_ACTION, true));
    assertTrue(ScreenActivityPolicy.recordable(ScreenActivityEvent.Kind.USER_TAP, false));
    assertFalse(ScreenActivityPolicy.recordable(null, false));
  }

  @Test
  public void passwordFieldsNeverKeepTheTypedText() {
    assertEquals("已输入", ScreenActivityPolicy.label("hunter2", "密码", true));
    assertEquals("已输入", ScreenActivityPolicy.label(null, null, true));
    assertEquals("确认", ScreenActivityPolicy.label("确认", "确认按钮", false));
    assertEquals("描述", ScreenActivityPolicy.label("", "描述", false));
    assertEquals("", ScreenActivityPolicy.label(null, null, false));
  }

  @Test
  public void summariesAreSingleLineAndBounded() {
    assertEquals("a b", ScreenActivityPolicy.label("  a \n\t b  ", null, false));
    assertEquals("a b", ScreenActivityPolicy.label("a\u0001b", null, false));
    String longText = "x".repeat(100);
    String compact = ScreenActivityPolicy.label(longText, null, false);
    assertEquals(ScreenActivityPolicy.LABEL_MAX, compact.codePointCount(0, compact.length()));
    assertTrue(compact.endsWith("…"));
    assertEquals("x".repeat(39), compact.substring(0, compact.length() - 1));
    String exact = "y".repeat(ScreenActivityPolicy.LABEL_MAX);
    assertEquals(exact, ScreenActivityPolicy.label(exact, null, false));
    String emoji = "😀".repeat(50);
    String emojiCompact = ScreenActivityPolicy.label(emoji, null, false);
    assertEquals(
        ScreenActivityPolicy.LABEL_MAX, emojiCompact.codePointCount(0, emojiCompact.length()));
    assertFalse(Character.isHighSurrogate(emojiCompact.charAt(emojiCompact.length() - 2)));
  }

  @Test
  public void activityNamesKeepOnlyTheShortClassName() {
    assertEquals("MainActivity", ScreenActivityPolicy.activity("com.example.MainActivity"));
    assertEquals("MainActivity", ScreenActivityPolicy.activity("MainActivity"));
    assertEquals("", ScreenActivityPolicy.activity(null));
  }

  @Test
  public void contentChangesAreThrottledAndTargetChangesDeduplicated() {
    assertTrue(ScreenActivityPolicy.contentChangeAllowed(1_000L, -1L));
    assertFalse(ScreenActivityPolicy.contentChangeAllowed(1_100L, 1_000L));
    assertTrue(ScreenActivityPolicy.contentChangeAllowed(1_300L, 1_000L));

    assertTrue(ScreenActivityPolicy.targetChangeAllowed(1_000L, -1L, "", "", "a", "M"));
    assertFalse(ScreenActivityPolicy.targetChangeAllowed(1_100L, 1_000L, "a", "M", "a", "M"));
    assertTrue(ScreenActivityPolicy.targetChangeAllowed(1_100L, 1_000L, "a", "M", "a", "N"));
    assertTrue(ScreenActivityPolicy.targetChangeAllowed(1_100L, 1_000L, "a", "M", "b", "M"));
    assertTrue(ScreenActivityPolicy.targetChangeAllowed(1_500L, 1_000L, "a", "M", "a", "M"));
  }
}
