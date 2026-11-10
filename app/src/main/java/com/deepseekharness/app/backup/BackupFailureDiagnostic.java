package com.deepseekharness.app.backup;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Backup failure location without exception messages, source paths, URIs or user content. */
final class BackupFailureDiagnostic {
  private BackupFailureDiagnostic() {}

  static String describe(String stage, Throwable error) {
    StringBuilder result = new StringBuilder("stage=").append(code(stage));
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    append(result, error, "cause", seen);
    return result.toString();
  }

  private static void append(
      StringBuilder result, Throwable at, String relation, Set<Throwable> seen) {
    if (at == null || seen.size() >= 8 || !seen.add(at)) return;
    result.append(' ').append(relation).append('=').append(symbol(at.getClass().getName()));
    if ("android.system.ErrnoException".equals(at.getClass().getName())) {
      try {
        result.append(" errno=").append(at.getClass().getField("errno").getInt(at));
      } catch (ReflectiveOperationException ignored) {
      }
    }
    int frames = 0, examined = 0;
    for (StackTraceElement frame : at.getStackTrace()) {
      if (++examined > 64) break;
      if (!frame.getClassName().startsWith("com.deepseekharness.app.")) continue;
      result
          .append(" at=")
          .append(symbol(frame.getClassName()))
          .append('.')
          .append(symbol(frame.getMethodName()))
          .append(':')
          .append(frame.getLineNumber());
      if (++frames == 4) break;
    }
    append(result, at.getCause(), "cause", seen);
    Throwable[] suppressed = at.getSuppressed();
    for (int i = 0; i < suppressed.length && i < 8 && seen.size() < 8; i++) {
      append(result, suppressed[i], "suppressed", seen);
    }
  }

  private static String code(String value) {
    return value != null && value.matches("[A-Z_0-9]{2,100}") ? value : "UNKNOWN";
  }

  private static String symbol(String value) {
    return value != null && value.length() <= 180 && value.matches("[A-Za-z0-9_.$<>]+")
        ? value
        : "UNKNOWN";
  }
}
