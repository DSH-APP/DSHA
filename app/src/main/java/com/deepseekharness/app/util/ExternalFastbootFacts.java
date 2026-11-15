package com.deepseekharness.app.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Conservative parser for fastboot getvar output; unknown values remain unknown. */
public final class ExternalFastbootFacts {
  public final Map<String, String> values;

  private ExternalFastbootFacts(Map<String, String> values) {
    this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  public static ExternalFastbootFacts parse(String output) {
    Map<String, String> result = new LinkedHashMap<>();
    if (output == null) return new ExternalFastbootFacts(result);
    for (String line : output.split("\\R")) {
      String value = line.trim();
      if (value.startsWith("(bootloader)")) value = value.substring("(bootloader)".length()).trim();
      int separator = value.indexOf(": ");
      if (separator <= 0) continue;
      String key = value.substring(0, separator).trim().toLowerCase(Locale.ROOT);
      String val = value.substring(separator + 2).trim();
      if (!key.isEmpty() && !val.isEmpty()) result.put(key, val);
    }
    return new ExternalFastbootFacts(result);
  }

  public String get(String key) {
    return key == null ? null : values.get(key.toLowerCase(Locale.ROOT));
  }

  public boolean yes(String key) {
    String value = get(key);
    return value != null
        && (value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("true") || value.equals("1"));
  }

  public long size(String partition) {
    String raw = get("partition-size:" + partition);
    if (raw == null) return -1;
    try {
      return raw.toLowerCase(Locale.ROOT).startsWith("0x")
          ? Long.parseLong(raw.substring(2), 16)
          : Long.parseLong(raw);
    } catch (NumberFormatException invalid) {
      return -1;
    }
  }

  public long rollback(String partition) {
    String raw = get("rollback-index:" + partition);
    if (raw == null) raw = get("rollback-index");
    if (raw == null) return -1;
    try {
      return raw.toLowerCase(Locale.ROOT).startsWith("0x")
          ? Long.parseLong(raw.substring(2), 16)
          : Long.parseLong(raw);
    } catch (NumberFormatException invalid) {
      return -1;
    }
  }
}
