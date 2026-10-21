package com.deepseekharness.app.diagnostics;

import com.deepseekharness.app.util.SensitiveData;
import java.util.*;

/** Technical metadata only. Arbitrary business objects are never reflected or stringified. */
public final class DiagnosticPrivacy {
  private DiagnosticPrivacy() {}

  public static String text(String value) {
    String safe = SensitiveData.redact(value == null ? "" : value);
    safe = safe.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "?");
    safe =
        safe.replaceAll(
            "(?i)(?:/data/(?:user/[0-9]+|data)/[^/\\s]+|/storage/[^\\s/]+|(?<![A-Za-z0-9])[A-Z]:[\\\\/])[^\\s\"'<>]*",
            "<private-path>");
    safe = safe.replaceAll("(?:/root/|/home/)[^\\s\"'<>]*", "<guest-path>");
    safe = safe.replaceAll("(?i)(https?://[^\\s?#]+)[?][^\\s#]*", "$1?<query-omitted>");
    return safe;
  }

  public static Map<String, Object> fields(Map<String, Object> input) {
    return fields(input, 0, new int[] {0});
  }

  private static Map<String, Object> fields(Map<?, ?> input, int depth, int[] count) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (input == null) return out;
    for (Map.Entry<?, ?> entry : input.entrySet()) {
      if (++count[0] > 2048 || depth > 24) {
        out.put("fieldsOmitted", true);
        break;
      }
      String key = entry.getKey() instanceof String ? (String) entry.getKey() : "invalid-key";
      if (!key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) continue;
      if (key.matches(
          "(?i).*(?:secret|token|password|credential|authorization|cookie|api.?key|body|prompt|conversation|argv|environment).*")) {
        out.put(key, "<omitted>");
        continue;
      }
      Object v = entry.getValue();
      if (v == null || v instanceof Boolean || v instanceof Long || v instanceof Integer)
        out.put(key, v);
      else if (v instanceof String) {
        String safe = text((String) v);
        out.put(key, safe.length() <= 32768 ? safe : safe.substring(0, 32768));
        if (safe.length() > 32768) out.put(key + "OmittedChars", (long) safe.length() - 32768);
      } else if (v instanceof Map) out.put(key, fields((Map<?, ?>) v, depth + 1, count));
      else if (v instanceof List) out.put(key, list((List<?>) v, depth + 1, count));
      else out.put(key, "<unsupported-value>");
    }
    return out;
  }

  private static List<Object> list(List<?> input, int depth, int[] count) {
    List<Object> out = new ArrayList<>();
    for (Object value : input) {
      if (++count[0] > 2048 || depth > 24) {
        out.add(Map.of("fieldsOmitted", true));
        break;
      }
      if (value == null
          || value instanceof Boolean
          || value instanceof Long
          || value instanceof Integer) out.add(value);
      else if (value instanceof String) {
        String safe = text((String) value);
        out.add(
            safe.length() <= 32768
                ? safe
                : Map.of(
                    "text",
                    safe.substring(0, 32768),
                    "textOmittedChars",
                    (long) safe.length() - 32768));
      } else if (value instanceof Map) out.add(fields((Map<?, ?>) value, depth + 1, count));
      else if (value instanceof List) out.add(list((List<?>) value, depth + 1, count));
      else out.add("<unsupported-value>");
    }
    return out;
  }

  public static boolean hasOmissions(Object value) {
    if ("<unsupported-value>".equals(value)) return true;
    if (value instanceof Map) {
      for (var entry : ((Map<?, ?>) value).entrySet())
        if (Boolean.TRUE.equals(entry.getValue()) && "fieldsOmitted".equals(entry.getKey())
            || entry.getKey() instanceof String
                && ((String) entry.getKey()).endsWith("OmittedChars")
            || hasOmissions(entry.getValue())) return true;
    } else if (value instanceof List)
      for (Object item : (List<?>) value) if (hasOmissions(item)) return true;
    return false;
  }
}
