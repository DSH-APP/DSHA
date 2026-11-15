package com.deepseekharness.app.screen;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;

/** 虚拟屏窗口证据与实际无障碍窗口对象的接线；不改变唯一窗口选择规则。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = android.app.Application.class,
    shadows = ScreenTargetResolverEvidenceTest.FreshWindows.class)
public final class ScreenTargetResolverEvidenceTest {
  private static final int DISPLAY = 8;

  @Implements(AccessibilityService.class)
  public static class FreshWindows extends ShadowAccessibilityService {
    static SparseArray<List<AccessibilityWindowInfo>> inventory = new SparseArray<>();

    @Implementation
    protected SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
      return inventory;
    }
  }

  private static AccessibilityWindowInfo window(
      int display,
      int id,
      int type,
      int layer,
      boolean focused,
      boolean active,
      String pkg,
      String title) {
    var window = AccessibilityWindowInfo.obtain();
    ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
    shadow.setDisplayId(display);
    shadow.setId(id);
    shadow.setType(type);
    shadow.setLayer(layer);
    shadow.setFocused(focused);
    shadow.setActive(active);
    shadow.setBoundsInScreen(new Rect(0, 0, 1008, 1792));
    shadow.setTitle(title);
    var root = AccessibilityNodeInfo.obtain(new View(RuntimeEnvironment.getApplication()));
    root.setPackageName(pkg);
    root.setBoundsInScreen(new Rect(0, 0, 1008, 1792));
    root.setVisibleToUser(true);
    Shadow.<ShadowAccessibilityNodeInfo>extract(root).setAccessibilityWindowInfo(window);
    shadow.setRoot(root);
    return window;
  }

  @Test
  public void readableVirtualDisplayCarriesTargetWindowEvidence() {
    var service = new DshaAccessibilityService();
    FreshWindows.inventory = new SparseArray<>();
    FreshWindows.inventory.put(
        DISPLAY,
        List.of(
            window(
                DISPLAY,
                20,
                AccessibilityWindowInfo.TYPE_APPLICATION,
                1,
                true,
                true,
                "com.example.mail",
                "Inbox"),
            window(
                DISPLAY,
                21,
                AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                2,
                false,
                false,
                "",
                "Keyboard")));
    try (var result = ScreenTargetResolver.resolve(service, DISPLAY)) {
      assertEquals("READABLE", result.diagnostic);
      assertNotNull(result.root);
      assertNotNull(result.evidence);
      assertEquals(0, result.evidence.selectedIndex);
      assertEquals("com.example.mail", result.evidence.targetPackage);
      var fields = result.evidence.fields();
      assertEquals(Integer.valueOf(20), fields.get("targetWindowId"));
      assertEquals("Inbox", fields.get("targetWindowTitle"));
      assertEquals("com.example.mail", fields.get("targetWindowPackage"));
      assertEquals(Integer.valueOf(1), fields.get("applicationWindowCount"));
      assertEquals(Integer.valueOf(1), fields.get("inputMethodWindowCount"));
      assertEquals(Boolean.FALSE, fields.get("windowAmbiguous"));
    }
  }

  @Test
  public void ambiguousApplicationWindowsExposeNoTargetWindow() {
    var service = new DshaAccessibilityService();
    FreshWindows.inventory = new SparseArray<>();
    FreshWindows.inventory.put(
        DISPLAY,
        List.of(
            window(
                DISPLAY,
                20,
                AccessibilityWindowInfo.TYPE_APPLICATION,
                1,
                true,
                true,
                "com.example.a",
                "A"),
            window(
                DISPLAY,
                21,
                AccessibilityWindowInfo.TYPE_APPLICATION,
                1,
                true,
                true,
                "com.example.b",
                "B")));
    try (var result = ScreenTargetResolver.resolve(service, DISPLAY)) {
      assertEquals("NO_UNIQUE_APPLICATION_WINDOW", result.diagnostic);
      assertNull(result.root);
      assertNotNull(result.evidence);
      assertTrue(result.evidence.ambiguous);
      assertEquals(-1, result.evidence.selectedIndex);
      assertEquals(2, result.evidence.applicationWindows);
      assertNull(result.evidence.target);
      var fields = result.evidence.fields();
      assertEquals(Boolean.TRUE, fields.get("windowAmbiguous"));
      assertFalse(fields.containsKey("targetWindowId"));
      assertFalse(fields.containsKey("targetWindowPackage"));
    }
  }

  @Test
  public void emptyVirtualDisplayHasZeroCounts() {
    var service = new DshaAccessibilityService();
    FreshWindows.inventory = new SparseArray<>();
    FreshWindows.inventory.put(DISPLAY, List.of());
    try (var result = ScreenTargetResolver.resolve(service, DISPLAY)) {
      assertEquals("NO_WINDOWS", result.diagnostic);
      assertNull(result.root);
      assertNotNull(result.evidence);
      assertEquals(0, result.evidence.applicationWindows);
      assertNull(result.evidence.target);
    }
  }
}
