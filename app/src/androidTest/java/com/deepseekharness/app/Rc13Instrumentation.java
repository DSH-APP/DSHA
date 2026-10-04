package com.deepseekharness.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** Historical default runner retained only to reject obsolete device fixtures. */
public final class Rc13Instrumentation extends Instrumentation {
  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    result.putString("result", "RETIRED");
    result.putString(
        "failure",
        "The rc13 private-device/update APK audit is retired. Use current isolated host tests and authorized non-destructive checks in the signed release application.");
    finish(Activity.RESULT_CANCELED, result);
  }
}
