package com.deepseekharness.app.util;

/** Internal file kinds retain the historical serialized names; unknown names fail explicitly. */
public enum FileKind {
  FILE,
  DIRECTORY,
  LINK,
  MISSING,
  UNREADABLE,
  EXCLUDED,
  SPECIAL,
  OTHER;

  public static FileKind fromWire(String name) {
    if (name == null) throw new IllegalArgumentException("FILE_KIND_MISSING");
    try {
      return valueOf(name);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("FILE_KIND_UNKNOWN", unknown);
    }
  }

  public String wire() {
    return name();
  }
}
