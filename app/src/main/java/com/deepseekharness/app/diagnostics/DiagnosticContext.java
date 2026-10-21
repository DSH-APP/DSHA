package com.deepseekharness.app.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/** Correlation only: these values never confer execution, filesystem or recovery authority. */
public final class DiagnosticContext {
  public final String operationId, runId, requestId, epoch, component;

  public DiagnosticContext(
      String operationId, String runId, String requestId, long epoch, String component) {
    this(operationId, runId, requestId, Long.toString(epoch), component);
  }

  public DiagnosticContext(
      String operationId, String runId, String requestId, String epoch, String component) {
    this.operationId = id(operationId);
    this.runId = id(runId);
    this.requestId = id(requestId);
    this.epoch = id(epoch);
    this.component = id(component);
  }

  private static String id(String value) {
    if (value == null) return "";
    if (!value.equals(DiagnosticPrivacy.text(value))) return "redacted";
    return value.matches("[A-Za-z0-9_.:@-]{0,192}") ? value : "invalid";
  }

  public Map<String, Object> fields() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("operationId", operationId);
    map.put("runId", runId);
    map.put("requestId", requestId);
    map.put("epoch", epoch);
    map.put("component", component);
    return map;
  }
}
