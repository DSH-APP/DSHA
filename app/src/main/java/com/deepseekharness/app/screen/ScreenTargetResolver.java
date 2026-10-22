package com.deepseekharness.app.screen;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.deepseekharness.app.util.ScreenTarget;
import com.deepseekharness.app.util.VirtualScreenWindowPolicy;
import com.deepseekharness.app.util.MainScreenWindowPolicy;
import com.deepseekharness.app.util.ScreenWindowRegion;
import java.util.ArrayList;

/** 只在指定显示选择唯一窗口；根不可读时不换用背景应用。 */
public final class ScreenTargetResolver {
  private ScreenTargetResolver() {}

  public static final class Resolution implements AutoCloseable {
    public final AccessibilityNodeInfo root;
    public final ScreenTarget target;
    public final String diagnostic;
    public final ScreenWindowRegion region;

    private Resolution(int display, AccessibilityNodeInfo root, String diagnostic) {
      this(display, root, diagnostic, null);
    }

    private Resolution(
        int display, AccessibilityNodeInfo root, String diagnostic, ScreenWindowRegion region) {
      this.root = root;
      target = identity(display, root);
      this.diagnostic = diagnostic;
      this.region = region;
    }

    @Override
    public void close() {
      if (root != null) root.recycle();
    }
  }

  public static ScreenTarget identity(int display, AccessibilityNodeInfo root) {
    return root == null
        ? null
        : new ScreenTarget(
            display,
            root.getWindowId(),
            root.getPackageName() == null ? "" : root.getPackageName().toString());
  }

  private static int windowDisplay(AccessibilityWindowInfo window) {
    if (android.os.Build.VERSION.SDK_INT < 30) return android.view.Display.DEFAULT_DISPLAY;
    return Api30.display(window);
  }

  @androidx.annotation.RequiresApi(30)
  private static final class Api30 {
    static int display(AccessibilityWindowInfo window) {
      return window.getDisplayId();
    }
  }

  public static Resolution resolve(AccessibilityService service, int display) {
    if (service == null) return new Resolution(display, null, "ACCESSIBILITY_UNAVAILABLE");
    if (android.os.Build.VERSION.SDK_INT < 30)
      return new Resolution(
          display,
          display == 0 ? service.getRootInActiveWindow() : null,
          display == 0 ? "READABLE" : "INVALID_DISPLAY");
    var all = service.getWindowsOnAllDisplays();
    try {
      var windows = all == null ? null : all.get(display);
      if (windows == null || windows.isEmpty()) return new Resolution(display, null, "NO_WINDOWS");
      if (display == 0) return physical(service, windows);
      var candidates = new ArrayList<VirtualScreenWindowPolicy.Window>();
      for (AccessibilityWindowInfo window : windows)
        candidates.add(
            new VirtualScreenWindowPolicy.Window(
                windowDisplay(window),
                window.getId(),
                window.getLayer(),
                window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION,
                window.isFocused(),
                window.isActive()));
      int selected = VirtualScreenWindowPolicy.select(display, candidates);
      if (selected < 0) return new Resolution(display, null, "NO_UNIQUE_APPLICATION_WINDOW");
      AccessibilityWindowInfo window = windows.get(selected);
      AccessibilityNodeInfo root = window.getRoot();
      if (root == null) return new Resolution(display, null, "ROOT_UNAVAILABLE");
      if (root.getWindowId() != window.getId()) {
        root.recycle();
        return new Resolution(display, null, "WINDOW_ID_MISMATCH");
      }
      return new Resolution(display, root, "READABLE");
    } finally {
      // getWindowsOnAllDisplays transfers every display's handles, not only the requested list.
      var released =
          java.util.Collections.newSetFromMap(
              new java.util.IdentityHashMap<AccessibilityWindowInfo, Boolean>());
      if (all != null)
        for (int i = 0; i < all.size(); i++)
          for (AccessibilityWindowInfo window : all.valueAt(i))
            if (window != null && released.add(window)) window.recycle();
    }
  }

  private static Resolution physical(
      AccessibilityService service, java.util.List<AccessibilityWindowInfo> windows) {
    var candidates = new ArrayList<MainScreenWindowPolicy.Window>();
    for (var window : windows) {
      android.graphics.Rect bounds = new android.graphics.Rect();
      window.getBoundsInScreen(bounds);
      var kind =
          switch (window.getType()) {
            case AccessibilityWindowInfo.TYPE_APPLICATION ->
                MainScreenWindowPolicy.Kind.APPLICATION;
            case AccessibilityWindowInfo.TYPE_SYSTEM -> MainScreenWindowPolicy.Kind.SYSTEM;
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD ->
                MainScreenWindowPolicy.Kind.INPUT_METHOD;
            default -> MainScreenWindowPolicy.Kind.UNSUPPORTED;
          };
      candidates.add(
          new MainScreenWindowPolicy.Window(
              windowDisplay(window),
              window.getId(),
              window.getLayer(),
              kind,
              window.isFocused(),
              window.isActive(),
              new ScreenWindowRegion.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom)));
    }
    var selection = MainScreenWindowPolicy.selection(candidates);
    int selected = selection.index();
    if (selected < 0) return new Resolution(0, null, selection.diagnostic());
    var window = windows.get(selected);
    AccessibilityNodeInfo root = window.getRoot();
    if (root == null) {
      // A second source may recover the same selected window, never elect a different app/display.
      root = service.getRootInActiveWindow();
      if (root != null && root.getWindowId() != window.getId()) {
        root.recycle();
        root = null;
      }
    }
    if (root == null) return new Resolution(0, null, "CURRENT_WINDOW_ROOT_UNAVAILABLE");
    if (root.getWindowId() != window.getId()
        || root.getPackageName() == null
        || root.getPackageName().length() == 0) {
      root.recycle();
      return new Resolution(0, null, "CURRENT_WINDOW_IDENTITY_UNAVAILABLE");
    }
    return new Resolution(0, root, "READABLE", MainScreenWindowPolicy.region(selected, candidates));
  }
}
