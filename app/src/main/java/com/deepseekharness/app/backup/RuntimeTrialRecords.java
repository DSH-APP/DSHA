package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 隔离试运行的小型宿主记录轮换；未确认关闭的现场永不自动删除。 */
public final class RuntimeTrialRecords {
  private RuntimeTrialRecords() {}

  static final int KEEP_SUCCEEDED = 3;
  static final int KEEP_FAILED = 5;
  private static final int SCAN_LIMIT = 64;
  private static final String ID = com.deepseekharness.app.util.Ids.UUID_PATTERN;

  private static final class Record {
    final String id;
    final long closedAt;

    Record(String id, long closedAt) {
      this.id = id;
      this.closedAt = closedAt;
    }
  }

  /** 新试运行前先轮换完成项；存在中断现场时要求走既有恢复入口。 */
  public static void prepareForNew(BackupFileSystem fs, File home) throws IOException {
    int pending = pruneClosed(fs, home);
    if (pending != 0) throw new IOException("TRIAL_RECOVERY_REQUIRED");
  }

  /**
   * 保留最近的少量成功和失败记录。返回未关闭数量；扫描与校验全部完成前不删除任何内容。
   */
  public static int pruneClosed(BackupFileSystem fs, File home) throws IOException {
    BackupFileSystem.Node root = fs.stat(home);
    if (root.type.equals("MISSING")) return 0;
    if (!root.type.equals("DIRECTORY")) throw new IOException("TRIAL_DIRECTORY");
    List<String> entries = fs.list(home);
    if (entries.size() > SCAN_LIMIT) throw new IOException("TRIAL_RETENTION_LIMIT");
    List<Record> succeeded = new ArrayList<>(), failed = new ArrayList<>();
    int pending = 0;
    for (String id : entries) {
      if (!id.matches(ID)) throw new IOException("TRIAL_DIRECTORY");
      File operation = fs.child(home, id);
      if (!fs.stat(operation).type.equals("DIRECTORY")) throw new IOException("TRIAL_DIRECTORY");
      File closed = fs.child(operation, "closed");
      BackupFileSystem.Node marker = fs.stat(closed);
      if (marker.type.equals("MISSING")) {
        pending++;
        continue;
      }
      if (!marker.type.equals("FILE")
          || !id.equals(new String(fs.small(closed, 128), StandardCharsets.US_ASCII)))
        throw new IOException("TRIAL_MARKER");
      BackupFileSystem.Node failure = fs.stat(fs.child(operation, "failure.json"));
      if (!failure.type.equals("MISSING") && !failure.type.equals("FILE"))
        throw new IOException("TRIAL_FAILURE_RECORD");
      (failure.type.equals("FILE") ? failed : succeeded).add(new Record(id, marker.modified));
    }
    Comparator<Record> newest =
        Comparator.comparingLong((Record value) -> value.closedAt)
            .reversed()
            .thenComparing((Record value) -> value.id, Comparator.reverseOrder());
    succeeded.sort(newest);
    failed.sort(newest);
    removeAfter(fs, home, succeeded, KEEP_SUCCEEDED);
    removeAfter(fs, home, failed, KEEP_FAILED);
    return pending;
  }

  /** 最近一条已确认关闭的失败记录；只返回宿主生成的小型 JSON，不读取 payload 或用户数据。 */
  public static byte[] latestFailure(BackupFileSystem fs, File home) throws IOException {
    BackupFileSystem.Node root = fs.stat(home);
    if (root.type.equals("MISSING")) return new byte[0];
    if (!root.type.equals("DIRECTORY")) throw new IOException("TRIAL_DIRECTORY");
    List<String> entries = fs.list(home);
    if (entries.size() > SCAN_LIMIT) throw new IOException("TRIAL_RETENTION_LIMIT");
    File selected = null;
    long selectedAt = Long.MIN_VALUE;
    String selectedId = "";
    for (String id : entries) {
      if (!id.matches(ID)) throw new IOException("TRIAL_DIRECTORY");
      File operation = fs.child(home, id);
      if (!fs.stat(operation).type.equals("DIRECTORY")) throw new IOException("TRIAL_DIRECTORY");
      File failure = fs.child(operation, "failure.json");
      BackupFileSystem.Node failed = fs.stat(failure);
      if (failed.type.equals("MISSING")) continue;
      if (!failed.type.equals("FILE")) throw new IOException("TRIAL_FAILURE_RECORD");
      File closed = fs.child(operation, "closed");
      BackupFileSystem.Node marker = fs.stat(closed);
      if (marker.type.equals("MISSING")) continue;
      if (!marker.type.equals("FILE")
          || !id.equals(new String(fs.small(closed, 128), StandardCharsets.US_ASCII)))
        throw new IOException("TRIAL_MARKER");
      if (marker.modified > selectedAt
          || marker.modified == selectedAt && id.compareTo(selectedId) > 0) {
        selected = failure;
        selectedAt = marker.modified;
        selectedId = id;
      }
    }
    return selected == null ? new byte[0] : fs.small(selected, 64 * 1024);
  }

  public record FailureDiagnostic(
      byte[] failure,
      String recordId,
      String state,
      String launched,
      String pid,
      String stalePid,
      String identity) {}

  /** A pending failure must be visible without closing, moving or deleting its evidence. */
  public static FailureDiagnostic latestDiagnostic(BackupFileSystem fs, File home)
      throws IOException {
    BackupFileSystem.Node root = fs.stat(home);
    if (root.type.equals("MISSING")) return null;
    if (!root.type.equals("DIRECTORY")) throw new IOException("TRIAL_DIRECTORY");
    List<String> entries = fs.list(home);
    if (entries.size() > SCAN_LIMIT) throw new IOException("TRIAL_RETENTION_LIMIT");
    File selected = null;
    String selectedId = "";
    long selectedAt = Long.MIN_VALUE;
    boolean selectedPending = false;
    for (String id : entries) {
      if (!id.matches(ID)) throw new IOException("TRIAL_DIRECTORY");
      File entry = fs.child(home, id);
      if (!fs.stat(entry).type.equals("DIRECTORY")) throw new IOException("TRIAL_DIRECTORY");
      File failure = fs.child(entry, "failure.json");
      BackupFileSystem.Node failed = fs.stat(failure);
      if (failed.type.equals("MISSING")) continue;
      if (!failed.type.equals("FILE")) throw new IOException("TRIAL_FAILURE_RECORD");
      File marker = fs.child(entry, "closed");
      BackupFileSystem.Node closed = fs.stat(marker);
      boolean pending = closed.type.equals("MISSING");
      if (!pending
          && (!closed.type.equals("FILE")
              || !id.equals(new String(fs.small(marker, 128), StandardCharsets.US_ASCII))))
        throw new IOException("TRIAL_MARKER");
      if (selected == null
          || pending && !selectedPending
          || pending == selectedPending
              && (failed.modified > selectedAt
                  || failed.modified == selectedAt && id.compareTo(selectedId) > 0)) {
        selected = entry;
        selectedId = id;
        selectedAt = failed.modified;
        selectedPending = pending;
      }
    }
    if (selected == null) return null;
    File launched = fs.child(selected, "launched");
    String launchedType = fs.stat(launched).type;
    if (!launchedType.equals("MISSING")
        && (!launchedType.equals("FILE")
            || !selectedId.equals(new String(fs.small(launched, 128), StandardCharsets.US_ASCII))))
      throw new IOException("TRIAL_MARKER");
    File payload = fs.child(selected, "payload");
    String payloadType = fs.stat(payload).type;
    if (!payloadType.equals("DIRECTORY") && !payloadType.equals("MISSING"))
      throw new IOException("TRIAL_PAYLOAD_TYPE");
    return new FailureDiagnostic(
        fs.small(fs.child(selected, "failure.json"), 64 * 1024),
        selectedId,
        selectedPending ? "PENDING_EXIT_CONFIRMATION" : "CLOSED",
        launchedType,
        payloadType.equals("MISSING")
            ? "MISSING"
            : fs.stat(fs.child(payload, ".dsha-web.pid")).type,
        payloadType.equals("MISSING")
            ? "MISSING"
            : fs.stat(fs.child(payload, ".dsha-web.pid.stale")).type,
        payloadType.equals("MISSING")
            ? "MISSING"
            : fs.stat(fs.child(payload, ".dsha-web.identity")).type);
  }

  private static void removeAfter(BackupFileSystem fs, File home, List<Record> records, int keep)
      throws IOException {
    for (int i = keep; i < records.size(); i++) fs.removeOwned(home, records.get(i).id);
  }
}
