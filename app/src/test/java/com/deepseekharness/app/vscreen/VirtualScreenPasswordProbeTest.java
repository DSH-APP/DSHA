package com.deepseekharness.app.vscreen;

import static com.deepseekharness.app.util.VirtualScreenTextInputPolicy.FocusEvidence.*;
import static org.junit.Assert.*;

import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.util.ScreenConnectionEpoch;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 密码焦点探测：只读映射；不证明真机无障碍行为。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenPasswordProbeTest {
  @SuppressWarnings("unchecked")
  private static ScreenConnectionEpoch<DshaAccessibilityService> connection() throws Exception {
    var field = DshaAccessibilityService.class.getDeclaredField("CONNECTION");
    field.setAccessible(true);
    return (ScreenConnectionEpoch<DshaAccessibilityService>) field.get(null);
  }

  private static AccessibilityNodeInfo node(boolean editable, boolean enabled, boolean password) {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setEditable(editable);
    node.setEnabled(enabled);
    node.setPassword(password);
    return node;
  }

  @Test
  public void passwordFocusIsReportedEvenForEditableEnabledNode() {
    AccessibilityNodeInfo node = node(true, true, true);
    try {
      assertEquals(PASSWORD_FOCUSED, VirtualScreenPasswordProbe.focusEvidence(node));
    } finally {
      node.recycle();
    }
  }

  @Test
  public void ordinaryEditableFocusIsSafe() {
    AccessibilityNodeInfo node = node(true, true, false);
    try {
      assertEquals(SAFE_FOCUS, VirtualScreenPasswordProbe.focusEvidence(node));
    } finally {
      node.recycle();
    }
  }

  @Test
  public void unreadableOrNonEditableFocusIsNeverGuessedSafe() {
    assertEquals(FOCUS_UNREADABLE, VirtualScreenPasswordProbe.focusEvidence(null));
    AccessibilityNodeInfo nonEditable = node(false, true, false);
    try {
      assertEquals(FOCUS_UNREADABLE, VirtualScreenPasswordProbe.focusEvidence(nonEditable));
    } finally {
      nonEditable.recycle();
    }
    AccessibilityNodeInfo disabled = node(true, false, false);
    try {
      assertEquals(FOCUS_UNREADABLE, VirtualScreenPasswordProbe.focusEvidence(disabled));
    } finally {
      disabled.recycle();
    }
  }

  @Test
  public void missingAccessibilityIsReportedSeparatelyFromUnreadableFocus() throws Exception {
    connection().disconnect(new DshaAccessibilityService());
    VirtualScreenPasswordProbe.Evidence evidence = VirtualScreenPasswordProbe.read(51);
    assertEquals(ACCESSIBILITY_UNAVAILABLE, evidence.focus);
    assertFalse(evidence.targetChanged);
  }
}
