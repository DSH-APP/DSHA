package com.deepseekharness.app.util;

import java.util.List;

/** Historic persisted stage IDs remain 1..6; enum ordinals are never serialized. */
public enum InstallStage {
  ENVIRONMENT(1),
  TOOLS(2),
  NODE(3),
  PNPM(4),
  DSH(5),
  PATCHES(6);
  public static final int ENVIRONMENT_ID = 1,
      TOOLS_ID = 2,
      NODE_ID = 3,
      PNPM_ID = 4,
      DSH_ID = 5,
      PATCHES_ID = 6;
  private final int id;

  InstallStage(int id) {
    this.id = id;
  }

  public int id() {
    return id;
  }

  public static List<InstallStage> ordered() {
    return List.of(values());
  }

  public static InstallStage fromId(int id) {
    for (var value : values()) if (value.id == id) return value;
    throw new IllegalArgumentException("INSTALL_STAGE_ID");
  }
}
