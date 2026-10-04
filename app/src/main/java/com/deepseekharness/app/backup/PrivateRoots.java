package com.deepseekharness.app.backup;

import java.util.Set;

/** Private app-owned roots. Public document IDs and user-created root children remain unchanged. */
public final class PrivateRoots {
  public static final String BACKUPS = "host-backup-operations",
      RUNTIMES = "host-runtime-operations",
      ENVIRONMENTS = "host-environment-operations",
      CONFIG_SNAPSHOTS = "startup-config-snapshots",
      CONFIG_OPERATIONS = "startup-config-operations",
      DATA_LOCATION = "data-locations-v5.json";
  private static final Set<String> TOP =
      Set.of(
          BACKUPS,
          RUNTIMES,
          ENVIRONMENTS,
          CONFIG_SNAPSHOTS,
          CONFIG_OPERATIONS,
          DATA_LOCATION,
          DATA_LOCATION + ".previous",
          "host-backup-catalogue",
          "host-native-settings-state.json",
          "runtime-updates",
          "maintenance",
          "runtime-trials",
          "runtime-health",
          "rc1-migration-state",
          "recovery-capsules",
          "recovery-sessions",
          "recovery-repairs",
          "recovery-active",
          "recovery-maintenance-tools",
          "bounded-guest-active",
          "cold-install-operations",
          "cold-install-diagnostics.txt",
          "diagnostic-events.txt",
          "diagnostic-events.1.txt",
          "startup-history",
          "plugin-imports",
          "startup-config-repair.pending",
          "host-backup-completed-v1.json",
          "host-runtime-completed-v1.json",
          "host-environment-completed-v1.json",
          "startup-config-completed-v1.json");
  public static final Set<String> DSH =
      Set.of(
          "plugin-install-operations",
          "plugin-activations.json",
          "plugin-safe-mode.json",
          "plugin-previews",
          "plugin-dependency-locks");

  private PrivateRoots() {}

  public static boolean top(String name) {
    return TOP.contains(name)
        || name.startsWith(".dsha-plugin-task-")
        || name.startsWith(".dsha-plugin-recovery-");
  }
}
