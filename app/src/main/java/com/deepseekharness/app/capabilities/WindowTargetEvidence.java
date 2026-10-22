package com.deepseekharness.app.capabilities;

/** A selected Android window. Implementations can additionally bind pixel or semantic evidence. */
public interface WindowTargetEvidence extends TargetEvidence {
  int displayId();

  int windowId();

  String packageName();
}
