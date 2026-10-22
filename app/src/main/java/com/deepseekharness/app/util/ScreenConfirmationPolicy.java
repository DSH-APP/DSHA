package com.deepseekharness.app.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Native DSHA confirmation preference; never grants Android or device-channel permissions. */
public final class ScreenConfirmationPolicy {
  public enum Mode {
    MANUAL,
    CUSTOM,
    ALLOW_ALL
  }

  public enum Rule {
    ASK,
    ALLOW
  }

  public final Mode mode;
  public final Map<String, Rule> rules;
  public final long revision;

  public ScreenConfirmationPolicy(Mode mode, Map<String, Rule> rules, long revision) {
    if (mode == null || revision < 0 || rules.size() > 256) throw new IllegalArgumentException();
    Map<String, Rule> copy = new LinkedHashMap<>();
    for (var entry : rules.entrySet()) {
      if (!validPackage(entry.getKey()) || entry.getValue() == null)
        throw new IllegalArgumentException("INVALID_SCREEN_PACKAGE_RULE");
      copy.put(entry.getKey(), entry.getValue());
    }
    this.mode = mode;
    this.rules = Collections.unmodifiableMap(copy);
    this.revision = revision;
  }

  public static ScreenConfirmationPolicy manual() {
    return new ScreenConfirmationPolicy(Mode.MANUAL, Map.of(), 0);
  }

  public String encode() {
    StringBuilder value =
        new StringBuilder("2\n").append(mode.name()).append('\n').append(revision);
    for (var rule : rules.entrySet())
      value.append('\n').append(rule.getKey()).append('=').append(rule.getValue().name());
    return value.toString();
  }

  /** Bounded schema, exact package keys and strict enums; malformed/future data fails closed. */
  public static ScreenConfirmationPolicy decode(String value) {
    try {
      if (value == null || value.length() > 100_000) return manual();
      String[] lines = value.split("\n", -1);
      boolean current = lines.length >= 3 && lines.length <= 259 && lines[0].equals("2");
      boolean legacy =
          lines.length >= 4
              && lines.length <= 260
              && lines[0].equals("1")
              && lines[lines.length - 1].isEmpty();
      if (!(current || legacy)) return manual();
      Map<String, Rule> rules = new LinkedHashMap<>();
      for (int i = 3; i < (legacy ? lines.length - 1 : lines.length); i++) {
        String[] parts = lines[i].split("=", -1);
        if (parts.length != 2 || rules.put(parts[0], Rule.valueOf(parts[1])) != null)
          return manual();
      }
      return new ScreenConfirmationPolicy(Mode.valueOf(lines[1]), rules, Long.parseLong(lines[2]));
    } catch (IllegalArgumentException malformed) {
      return manual();
    }
  }

  public boolean allows(String packageName) {
    return mode == Mode.ALLOW_ALL || mode == Mode.CUSTOM && rules.get(packageName) == Rule.ALLOW;
  }

  public static boolean validPackage(String value) {
    return value != null
        && value.length() <= 255
        && value.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
  }
}
