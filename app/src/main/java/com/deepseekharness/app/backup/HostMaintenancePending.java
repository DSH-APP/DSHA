package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.MaintenanceTransaction;
import com.deepseekharness.app.util.RuntimeUpdateTransaction;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Read-only inventory of active or uncertain host maintenance records. */
public final class HostMaintenancePending {
  private HostMaintenancePending() {}

  public static final class Check {
    public final boolean blocked;
    public final String domain, operation, error;

    private Check(boolean blocked, String domain, String operation, String error) {
      this.blocked = blocked;
      this.domain = domain;
      this.operation = operation;
      this.error = error;
    }

    public static Check clear() {
      return new Check(false, "", "", "");
    }

    public static Check pending(String domain, String operation) {
      return new Check(true, domain, operation, "");
    }

    public static Check unreadable(String domain, IOException failure) {
      String detail =
          SensitiveData.redact(
              failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage()));
      return new Check(true, domain, "unknown", detail);
    }
  }

  interface Probe {
    List<String> pendingOperations(File filesDir) throws IOException;
  }

  static final class Domain {
    final String name;
    final Probe probe;
    final boolean countEach;

    Domain(String name, Probe probe, boolean countEach) {
      this.name = name;
      this.probe = probe;
      this.countEach = countEach;
    }
  }

  private static List<String> names(List<File> files) {
    List<String> result = new ArrayList<>(files.size());
    for (File file : files) result.add(file.getName());
    return result;
  }

  static final List<Domain> RECOVERABLE =
      List.of(
          new Domain(
              "cold-install",
              root ->
                  ColdInstallTransaction.pending(
                      new AndroidBackupFileSystem(), root.getCanonicalFile()),
              false),
          new Domain(
              "legacy-maintenance",
              root -> {
                MaintenanceTransaction tx =
                    MaintenanceTransaction.pending(new AndroidBackupFileSystem(), root);
                return tx == null ? List.of() : List.of(tx.directory().getName());
              },
              false),
          new Domain(
              "legacy-runtime",
              root -> {
                RuntimeUpdateTransaction tx =
                    RuntimeUpdateTransaction.pending(new AndroidBackupFileSystem(), root);
                return tx == null ? List.of() : List.of(tx.directory().getName());
              },
              false),
          new Domain(
              "managed-runtime",
              root -> {
                List<String> ids = new ArrayList<>();
                for (ManagedRuntimeTransaction tx :
                    ManagedRuntimeTransaction.pending(
                        new AndroidBackupFileSystem(), root.getCanonicalFile()))
                  ids.add(tx.directory().getName());
                return ids;
              },
              true),
          new Domain(
              "startup-configuration",
              root -> {
                File files = root.getCanonicalFile();
                AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
                ConfigurationSnapshots snapshots =
                    new ConfigurationSnapshots(fs, files, new UserDataLayout(fs, files).current());
                return snapshots.pending() ? List.of("startup-config-repair") : List.of();
              },
              false),
          new Domain(
              "plugin-install",
              root -> {
                File files = root.getCanonicalFile();
                AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
                return names(
                    PluginInstallJournals.pending(fs, new UserDataLayout(fs, files).current()));
              },
              false),
          new Domain(
              "environment-rebuild",
              root -> {
                EnvironmentRebuildTransaction tx =
                    EnvironmentRebuildTransaction.pending(
                        new AndroidBackupFileSystem(), root.getCanonicalFile());
                return tx == null ? List.of() : List.of(tx.directory().getName());
              },
              false),
          new Domain(
              "host-data",
              root ->
                  names(
                      HostPendingTransactions.pending(
                          new AndroidBackupFileSystem(), root.getCanonicalFile())),
              true));

  private static Check check(Domain domain, File filesDir) {
    try {
      List<String> operations = domain.probe.pendingOperations(filesDir);
      return operations.isEmpty() ? Check.clear() : Check.pending(domain.name, operations.get(0));
    } catch (IOException failure) {
      return Check.unreadable(domain.name, failure);
    }
  }

  public static Check inspect(File filesDir) {
    Check found;
    try {
      String guest = com.deepseekharness.app.runtime.BoundedGuestSessions.firstPending(filesDir);
      found = guest == null ? Check.clear() : Check.pending("bounded-guest", guest);
    } catch (IOException failure) {
      found = Check.unreadable("bounded-guest", failure);
    }
    if (found.blocked) return found;
    return inspectRecoverable(filesDir, RECOVERABLE);
  }

  static Check inspectRecoverable(File filesDir, List<Domain> domains) {
    Check found;
    for (Domain domain : domains) {
      found = check(domain, filesDir);
      if (found.blocked) return found;
    }
    return Check.clear();
  }

  /** Recoverable journal count only; bounded guest is a separate process barrier. */
  public static boolean moreThanOneRecoverable(File filesDir) throws IOException {
    return moreThanOneRecoverable(filesDir, RECOVERABLE);
  }

  static boolean moreThanOneRecoverable(File filesDir, List<Domain> domains) throws IOException {
    int count = 0;
    for (Domain domain : domains) {
      List<String> operations = domain.probe.pendingOperations(filesDir);
      count += domain.countEach ? Math.min(2, operations.size()) : operations.isEmpty() ? 0 : 1;
      if (count > 1) return true;
    }
    return false;
  }

  public static boolean blocked(File filesDir) {
    return inspect(filesDir).blocked;
  }
}
