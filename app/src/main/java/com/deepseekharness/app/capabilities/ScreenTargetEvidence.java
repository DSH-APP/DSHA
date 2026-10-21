package com.deepseekharness.app.capabilities;

import java.util.Objects;

/** Exact display/window/package selection; never falls back to a different display. */
public final class ScreenTargetEvidence implements WindowTargetEvidence {
  public final int displayId, windowId;
  public final String packageName;

  public ScreenTargetEvidence(int displayId, int windowId, String packageName) {
    if (displayId < 0
        || windowId < 0
        || packageName == null
        || packageName.isEmpty()
        || packageName.length() > 255) throw new IllegalArgumentException("INVALID_SCREEN_TARGET");
    this.displayId = displayId;
    this.windowId = windowId;
    this.packageName = packageName;
  }

  @Override
  public String kind() {
    return "screen-window";
  }

  @Override
  public int displayId() {
    return displayId;
  }

  @Override
  public int windowId() {
    return windowId;
  }

  @Override
  public String packageName() {
    return packageName;
  }

  public boolean matches(WindowTargetEvidence current) {
    return current != null
        && displayId == current.displayId()
        && windowId == current.windowId()
        && Objects.equals(packageName, current.packageName());
  }
}
