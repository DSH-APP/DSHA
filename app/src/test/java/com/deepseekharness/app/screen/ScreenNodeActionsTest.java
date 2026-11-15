package com.deepseekharness.app.screen;

import static org.junit.Assert.*;

import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class ScreenNodeActionsTest {
  @Test
  public void actionSnapshotPreservesAdvertisedNamesAndSupportsDerivedFlags() {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.addAction(AccessibilityNodeInfo.ACTION_CLICK);
    node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT);
    // 旧 addAction(int) 按位掩码拆分；自定义动作必须用 AccessibilityAction，且不能映射成白名单动作。
    int custom = 0x7f0b0001;
    node.addAction(new AccessibilityNodeInfo.AccessibilityAction(custom, "custom"));
    try {
      var actions = ScreenNodeActions.snapshot(node);
      assertEquals("[\"click\",\"scroll_backward\",\"set_text\"]", actions.names().toString());
      assertTrue(actions.supports(AccessibilityNodeInfo.ACTION_CLICK));
      assertTrue(actions.supports(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD));
      assertFalse(actions.supports(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));
      assertFalse(actions.supports(AccessibilityNodeInfo.ACTION_LONG_CLICK));
      assertFalse(actions.supports(custom));
      assertTrue(actions.hasAny());
      assertFalse(ScreenNodeActions.snapshot(null).hasAny());
      assertEquals(AccessibilityNodeInfo.ACTION_CLICK, ScreenNodeActions.action("click"));
      assertEquals(
          AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
          ScreenNodeActions.action("scroll_backward"));
      assertEquals(-1, ScreenNodeActions.action("unknown"));
    } finally {
      node.recycle();
    }
  }
}
