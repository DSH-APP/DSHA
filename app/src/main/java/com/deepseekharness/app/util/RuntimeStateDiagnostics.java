package com.deepseekharness.app.util;

import java.util.List;
import java.util.Set;

/** Fixed-field advisory snapshot. It cannot authorize a restart, retire a record or expose content. */
public final class RuntimeStateDiagnostics {
  private RuntimeStateDiagnostics() {}

  private static final Set<String> REASONS =
      Set.of(
          "NO_RECORD",
          "RESTORED",
          "EXIT_CONFIRMED",
          "IDENTITY_UNKNOWN",
          "AUTH_UNAVAILABLE",
          "AUTHENTICATING",
          "LAUNCHER_PENDING",
          "LAUNCHER_UNKNOWN",
          "RETIRE_UNAVAILABLE",
          "INCOMPATIBLE",
          "RECORD_UNAVAILABLE",
          "INTERRUPTED",
          "STALE",
          "ADOPT_UNAVAILABLE",
          "RETRY_UNAVAILABLE",
          "CURRENT_UNAVAILABLE");
  private static final Set<String> STOP_CODES =
      Set.of(
          "WEB_STOP_NOT_REQUESTED",
          "WEB_STOP_PENDING",
          "WEB_STOPPED",
          "WEB_STOP_FAILED",
          "WEB_STOP_CANCELLED",
          "WEB_PROCESS_EXIT_UNCONFIRMED");
  private static final Set<String> DOMAINS =
      Set.of(
          "bounded-guest",
          "cold-install",
          "legacy-maintenance",
          "legacy-runtime",
          "managed-runtime",
          "startup-configuration",
          "plugin-install",
          "environment-rebuild",
          "host-data");

  public record Maintenance(String domain, String state) {}

  public static final class Snapshot {
    public long generation, oldestTaskMillis;
    public boolean starting, stopping, userStopped, sessionUnknown;
    public boolean environmentExclusive, maintenanceExclusive;
    public int environmentSharedLeases;
    public int recoveryAttempts, activeTasks, boundedActive, boundedPending, boundedInvalid;
    public boolean boundedReadable = true;
    public String recoveryReason = "", stopStatus = "", stopCode = "";
    public List<WebStopDiagnostic.Code> stopFacts = List.of();
    public List<Maintenance> maintenance = List.of();
  }

  public static String render(Snapshot value) {
    StringBuilder out = new StringBuilder("scope=HOST_READ_ONLY\n");
    out.append("generation=").append(Math.max(0, value.generation));
    out.append("\nstarting=").append(value.starting);
    out.append("\nstopping=").append(value.stopping);
    out.append("\nuserStopped=").append(value.userStopped);
    out.append("\nsessionUnknown=").append(value.sessionUnknown);
    out.append("\nenvironmentExclusive=").append(value.environmentExclusive);
    out.append("\nenvironmentSharedLeases=").append(count(value.environmentSharedLeases));
    out.append("\nmaintenanceExclusive=").append(value.maintenanceExclusive);
    out.append("\nrecoveryReason=").append(known(value.recoveryReason, REASONS));
    out.append("\nauthChecks=").append(Math.max(0, Math.min(3, value.recoveryAttempts)));
    out.append("\nstopStatus=")
        .append(known(value.stopStatus, Set.of("STOPPED", "UNCONFIRMED", "FAILED", "CANCELLED")));
    out.append("\nstopCode=").append(known(value.stopCode, STOP_CODES));
    int facts = 0;
    for (WebStopDiagnostic.Code fact : value.stopFacts) {
      if (facts++ >= 16) break;
      if (fact != null) out.append("\nstopFact=").append(fact.name());
    }
    out.append("\nruntimeTasks=").append(count(value.activeTasks));
    out.append("\noldestTaskMillis=").append(Math.max(0, value.oldestTaskMillis));
    out.append("\nboundedRecordsReadable=").append(value.boundedReadable);
    out.append("\nboundedActiveRecords=").append(count(value.boundedActive));
    out.append("\nboundedPendingRecords=").append(count(value.boundedPending));
    out.append("\nboundedInvalidRecords=").append(count(value.boundedInvalid));
    int domains = 0;
    for (Maintenance item : value.maintenance) {
      if (domains++ >= 16) break;
      if (item != null)
        out.append("\nmaintenance=")
            .append(known(item.domain, DOMAINS))
            .append(':')
            .append(known(item.state, Set.of("CLEAR", "PENDING", "UNREADABLE")));
    }
    return out.toString();
  }

  private static int count(int value) {
    return Math.max(0, Math.min(65535, value));
  }

  private static String known(String value, Set<String> allowed) {
    return value != null && allowed.contains(value) ? value : "UNAVAILABLE";
  }
}
