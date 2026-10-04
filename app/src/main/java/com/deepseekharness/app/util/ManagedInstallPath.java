package com.deepseekharness.app.util;

/** Canonical relative spelling for paths in the signed runtime installation recipe. */
public final class ManagedInstallPath {
  private ManagedInstallPath() {}

  public static boolean valid(String value) {
    if (value == null
        || value.isEmpty()
        || value.startsWith("/")
        || value.indexOf('\\') >= 0
        || value.indexOf(':') >= 0
        || value.indexOf('\0') >= 0) return false;
    for (String part : value.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
    return true;
  }
}
