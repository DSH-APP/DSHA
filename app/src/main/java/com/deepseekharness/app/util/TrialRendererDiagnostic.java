package com.deepseekharness.app.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Diagnostic facts only: no readiness decision and no untrusted strings in the exported map. */
public final class TrialRendererDiagnostic {
  public static final Set<String> SCRIPT_FLAGS =
      Set.of(
          "webReady",
          "rootReady",
          "socketConstructed",
          "socketOpen",
          "socketClosed",
          "readyFrameSeen",
          "clientIdValid",
          "homeMatched",
          "matchedReady",
          "postAttempted",
          "postAck",
          "postNetworkError",
          "documentHidden",
          "navigatorOnline",
          "timerObserved",
          "freezeObserved");
  private static final Set<String> NATIVE_FLAGS =
      Set.of(
          "bridgeSupported",
          "cookieCallback",
          "cookieAccepted",
          "pageStarted",
          "pageFinished",
          "viewAttached",
          "viewShown",
          "windowVisible",
          "sizePositive",
          "ownerResumed",
          "rendererGone",
          "closed");
  private final LongSupplier clock;
  private final Map<String, Object> nativeFacts = new LinkedHashMap<>();
  private Map<String, Object> scriptFacts = Map.of();
  private String reportNonce;
  private long receivedAt;

  public TrialRendererDiagnostic() {
    this(() -> System.nanoTime() / 1_000_000);
  }

  public TrialRendererDiagnostic(LongSupplier clock) {
    this.clock = clock;
  }

  public synchronized void nativeFlag(String key, boolean value) {
    if (!NATIVE_FLAGS.contains(key)) throw new IllegalArgumentException("TRIAL_DIAGNOSTIC_KEY");
    nativeFacts.put(key, value);
  }

  public synchronized void mainHttpStatus(int code) {
    nativeFacts.put("mainHttpStatus", code >= 100 && code <= 599 ? code : 0);
  }

  public synchronized void mainLoadError(int code) {
    nativeFacts.put("mainLoadError", code >= -100 && code < 0 ? code : 0);
  }

  public synchronized boolean accept(String nonce, Map<String, ?> incoming) {
    if (nonce == null || !nonce.matches("[a-f0-9]{32}") || incoming == null) return false;
    Map<String, Object> safe = new LinkedHashMap<>();
    for (String key : SCRIPT_FLAGS) {
      Object value = incoming.get(key);
      if (!(value instanceof Boolean)) return false;
      safe.put(key, value);
    }
    Object rawCode = incoming.get("postHttpStatus");
    if (!(rawCode instanceof Number)) return false;
    double code = ((Number) rawCode).doubleValue();
    if (!Double.isFinite(code) || code != Math.rint(code) || code < 0 || code > 599) return false;
    safe.put("postHttpStatus", (int) code);
    reportNonce = nonce; // Compared in memory only; never placed in a diagnostic record.
    scriptFacts = safe;
    receivedAt = clock.getAsLong();
    return true;
  }

  public synchronized Map<String, Object> snapshot(String expectedNonce) {
    Map<String, Object> safe = new LinkedHashMap<>(nativeFacts);
    boolean received = reportNonce != null;
    boolean matched = received && reportNonce.equals(expectedNonce);
    safe.put("scriptReportReceived", received);
    safe.put("nonceMatched", matched);
    if (matched) safe.putAll(scriptFacts);
    long age = Math.max(0, clock.getAsLong() - receivedAt);
    safe.put("reportAgeBucket", !received ? 0 : age < 15_000 ? 1 : age < 60_000 ? 2 : 3);
    return safe;
  }
}
