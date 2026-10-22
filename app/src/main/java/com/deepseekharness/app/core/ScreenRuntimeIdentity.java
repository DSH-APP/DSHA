package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.DshaApp;

/** Adds an unrepeatable process identity to the existing controller's monotonic Web generation. */
public final class ScreenRuntimeIdentity {
  private static final String PROCESS = java.util.UUID.randomUUID().toString();

  private ScreenRuntimeIdentity() {}

  public static RuntimeSnapshot.Epoch epoch(Context context) {
    long generation = HarnessController.get(context).getWebGeneration();
    return new RuntimeSnapshot.Epoch(
        DshaApp.from(context).installationId(),
        generation,
        generation > 0 ? PROCESS + ":" + generation : "");
  }
}
