package com.deepseekharness.app.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Trusted native policy projection. Unknown local settings are never represented as valid OFF. */
public final class NativeConsentProjection {
  private NativeConsentProjection() {}

  public static Map<String, Object> payload(
      NativeConsentPolicy.Snapshot snapshot,
      boolean bridgeAndRuntimeAvailable,
      String installationId,
      String bridgeRunId,
      long webGeneration,
      String runtimeInstanceId) {
    boolean known =
        snapshot != null
            && snapshot.hasSwitchValue()
            && snapshot.revision() >= 0
            && snapshot.revision() <= NativeConsentPolicy.MAX_REVISION
            && (snapshot.saveState() == NativeConsentPolicy.SaveState.NONE
                || snapshot.saveState() == NativeConsentPolicy.SaveState.CACHE_VERIFIED);
    boolean available = bridgeAndRuntimeAvailable && known;
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", 1);
    value.put("available", available);
    value.put("globalAllow", available && snapshot.readState() == NativeConsentPolicy.ReadState.ON);
    value.put("revision", known ? snapshot.revision() : NativeConsentPolicy.MAX_REVISION);
    value.put("installationId", installationId);
    value.put("bridgeRunId", bridgeRunId);
    value.put("webGeneration", webGeneration);
    value.put("runtimeInstanceId", runtimeInstanceId);
    // A genuinely absent first-run setting is the defined default OFF. No value is saved here.
    return Collections.unmodifiableMap(value);
  }
}
