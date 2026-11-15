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

  /** CLEAR：无记录；PENDING：有已识别的待恢复事务；UNREADABLE：记录无法读取或校验。 */
  public enum State {
    CLEAR,
    PENDING,
    UNREADABLE
  }

  public static final class Check {
    /** 两种非 CLEAR 状态都阻塞正常启动；需要区分时读 {@link #state}。 */
    public final boolean blocked;

    public final State state;

    /** UNREADABLE 时为稳定错误码（仅大写字母、数字与下划线），否则为空。 */
    public final String code;

    public final String domain, operation, error;

    private Check(State state, String domain, String operation, String error, String code) {
      this.state = state;
      this.blocked = state != State.CLEAR;
      this.domain = domain;
      this.operation = operation;
      this.error = error;
      this.code = code;
    }

    public static Check clear() {
      return new Check(State.CLEAR, "", "", "", "");
    }

    public static Check pending(String domain, String operation) {
      return new Check(State.PENDING, domain, operation, "", "");
    }

    public static Check unreadable(String domain, IOException failure) {
      String message = String.valueOf(failure.getMessage());
      String detail = SensitiveData.redact(failure.getClass().getSimpleName() + ":" + message);
      String code = message.matches("[A-Z][A-Z0-9_]{2,63}") ? message : "UNREADABLE";
      return new Check(State.UNREADABLE, domain, "unknown", detail, code);
    }

    public boolean unreadable() {
      return state == State.UNREADABLE;
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
    } catch (RuntimeException failure) {
      // 探测中的意外异常同样视为记录不可读（fail closed），不能让门禁本身崩溃。
      return Check.unreadable(domain.name, new IOException("UNREADABLE_PROBE", failure));
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

  /**
   * 逐域检查全部记录，从不抛出：某个域读不了不会挡住对其他域的判断。恢复入口据此先区分
   * 「有待恢复事务」与「记录无法读取」，避免把探测异常原样冒充为恢复失败。
   */
  public static List<Check> inspectAll(File filesDir) {
    return inspectAll(filesDir, RECOVERABLE);
  }

  static List<Check> inspectAll(File filesDir, List<Domain> domains) {
    List<Check> result = new ArrayList<>();
    for (Domain domain : domains) {
      Check found = check(domain, filesDir);
      if (found.blocked) result.add(found);
    }
    return result;
  }

  /** 同 {@link #inspectAll}，另含有界 guest 进程屏障；供只需「是否全部可放行」的入口使用。 */
  public static List<Check> inspectAllWithGuest(File filesDir) {
    List<Check> result = new ArrayList<>();
    try {
      String guest = com.deepseekharness.app.runtime.BoundedGuestSessions.firstPending(filesDir);
      if (guest != null) result.add(Check.pending("bounded-guest", guest));
    } catch (IOException failure) {
      result.add(Check.unreadable("bounded-guest", failure));
    }
    result.addAll(inspectAll(filesDir));
    return result;
  }

  /**
   * 「选择数据目录」只被数据根以外的问题阻塞：数据根无法确定（DATA_LOCATION_*）正是该动作的修复
   * 对象，不能被自己挡住。返回第一个仍须先处理的检查，没有时返回 null。
   */
  public static Check blocksDataHomeSelection(List<Check> checks) {
    for (Check check : checks)
      if (check.blocked
          && !(check.unreadable()
              && com.deepseekharness.app.util.MaintenanceExitPolicy.dataLocation(check.code)))
        return check;
    return null;
  }

  /** 第一个无法读取的域；没有时返回 null。 */
  public static Check firstUnreadable(File filesDir) {
    for (Check check : inspectAll(filesDir)) if (check.unreadable()) return check;
    return null;
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
