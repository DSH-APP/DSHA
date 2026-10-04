package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;

/** 停止事实只含稳定代码和原始参数；语言由组合根注入的显示适配器决定。 */
public record WebStopDiagnostic(Code code, List<String> arguments) {
  public enum Code {
    OWNED_LAUNCHER_PENDING,
    FORCE_REQUIRES_UNCONFIRMED_STOP,
    FORCE_REQUEST_CHANGED,
    ROOT_LINK,
    ROOT_FOREIGN_UID,
    ROOT_ACCESS_UNCONFIRMED,
    PID_RECORD_INVALID,
    PID_INVALID,
    RECORD_FOREIGN_UID,
    RECORD_ACCESS_UNCONFIRMED,
    SENTINEL_UNWRITABLE,
    PROC_INFO_LIMIT,
    INSPECTION_INTERRUPTED,
    CHECK_FAILED,
    BIRTH_UNREADABLE,
    COMMAND_UNREADABLE,
    INSPECTION_FAILED,
    PID_STALE_LOCATION_INVALID,
    RECORD_RETIRE_FAILED,
    IDENTITY_RECORD_INVALID,
    IDENTITY_RECORD_RETIRE_FAILED,
    IDENTITY_RECORD_WRITE_FAILED,
    IDENTITY_RECORD_VALUE_INVALID,
    PRE_EXEC_WAIT,
    IDENTITY_CHANGED,
    INSPECTION_UNCONFIRMED,
    TRIAL_IDENTITY_CHANGED,
    SIGNAL_DENIED,
    EXIT_UNCONFIRMED,
    PENDING_EXIT,
    STOP_INTERRUPTED,
    STOP_FAILED,
    TRIAL_INSPECTION_DENIED,
    TRIAL_STOP_FAILED,
    TRIAL_UNCONFIRMED,
    OWNED_WEB_REMAINS,
    OWNED_WEB_SCAN_UNCONFIRMED,
    FORCE_EVIDENCE_UNCONFIRMED,
    LEGACY_DETAIL
  }

  public WebStopDiagnostic {
    java.util.Objects.requireNonNull(code);
    if (arguments == null || arguments.size() > 8)
      throw new IllegalArgumentException("WEB_STOP_ARGUMENTS");
    List<String> safe = new ArrayList<>();
    for (String argument : arguments) {
      String value = SensitiveData.redact(String.valueOf(argument));
      safe.add(value.length() > 1200 ? value.substring(0, 1200) : value);
    }
    arguments = List.copyOf(safe);
  }

  public static WebStopDiagnostic of(Code code, Object... arguments) {
    List<String> values = new ArrayList<>();
    for (Object value : arguments) values.add(String.valueOf(value));
    return new WebStopDiagnostic(code, values);
  }

  public static WebStopDiagnostic failure(Code context, Throwable failure, Object... prefix) {
    Throwable at = failure;
    for (int i = 0; at != null && i < 8; i++, at = at.getCause())
      if (at instanceof WebStopException) return ((WebStopException) at).diagnostic();
    List<Object> values = new ArrayList<>(java.util.Arrays.asList(prefix));
    values.add(
        failure == null
            ? "unknown"
            : failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage()));
    return of(context, values.toArray());
  }

  public String debug() {
    return "WEB_STOP_" + code.name() + (arguments.isEmpty() ? "" : " " + arguments);
  }
}
