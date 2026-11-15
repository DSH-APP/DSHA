package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Bounded host-only facts. Never reads backup payloads, exported URIs or diagnostic messages. */
public final class BackupOperationDiagnostics {
  private static final int MAX_NAMES = 500, MAX_JOBS = 400, MAX_ROWS = 40, MAX_TEXT = 32768;
  private static final int RECORD_LIMIT = 16384;
  private static final String ROOT = "host-backup-operations";
  private static final Set<String> STAGES =
      Set.of(
          "IDLE",
          "PREPARING",
          "VERIFYING",
          "COPYING_INPUT",
          "AUTHENTICATING",
          "PREVIEW",
          "STOPPING_WRITERS",
          "CAPTURING",
          "ARCHIVING",
          "WRITING",
          "ENCRYPTING",
          "EXPORTING",
          "COMMITTING",
          "FINISHED",
          "FAILED",
          "FAILED_RETAINED",
          "CANCELLED",
          "CANCELLED_RETAINED",
          "INTERRUPTED",
          "INTERRUPTED_RETAINED");
  private static final Set<String> RESULTS =
      Set.of(
          "",
          "COMPLETE",
          "DATA_SAVED_PLUGIN_WARNINGS",
          "WRITTEN_UNVERIFIED",
          "PARTIAL_RESCUE",
          "BEST_EFFORT_RESCUE",
          "CONFIRM_STOP_AND_RESTORE",
          "DATA_RESTORED",
          "DATA_RESTORED_WITH_WARNINGS",
          "DATA_RESTORED_PLUGINS_QUARANTINED",
          "DATA_RESTORED_SETTINGS_REVIEW",
          "DATA_RESTORED_API_KEY_MISSING",
          "DATA_RESTORED_API_KEY_OMITTED",
          "RECOVERED_INTERRUPTED_COMMIT");
  private static final Set<String> ERRORS =
      Set.of(
          "",
          "IO_FAILURE",
          "NO_SPACE",
          "PERMISSION_DENIED",
          "CANCELLED",
          "SOURCE_CHANGED",
          "DATA_LOCATION_CHANGED",
          "PASSWORD_NOT_RETAINED",
          "STATE_PERSISTENCE_FAILED",
          "OPERATION_RECORD_UNREADABLE",
          "SAF_PERMISSION_REVOKED",
          "SAF_IO_FAILURE",
          "SAF_UNREADABLE",
          "SAF_WRITE_FAILED",
          "EXPORT_DOWNLOADS_FAILED",
          "EXPORT_DIGEST_MISMATCH",
          "ENVELOPE_VERIFICATION",
          "DESTINATION_CHECKSUM",
          "DESTINATION_NOT_NEW",
          "EXISTING_BACKUP_DESTINATION",
          "AUTOMATIC_BACKUP_DEFERRED",
          "PRIVATE_ARTIFACT_VERIFICATION",
          "TEMPORARY_FILE_TYPE",
          "WORKER_UNAVAILABLE",
          "FACTORY_RESET_QUIESCING",
          "FOREGROUND_SERVICE_UNAVAILABLE");
  private static final String[][] MARKERS = {
    {"record", "operation.json"},
    {"previous", "operation.json.previous"},
    {"verified", "verified.json"},
    {"exported", "exported.json"},
    {"failure", "failure.json"},
    {"portable", "portable.dshbak"},
    {"snapshot", "snapshot.dshdata"},
    {"sourceChecks", "source-checks"},
    {"capture", "capture"},
    {"envelope", "export.tar.gz"},
    {"envelopeCheck", "export-verified.dshbak"}
  };

  private BackupOperationDiagnostics() {}

  /**
   * The argument is the trusted Context files root, not a user-selected path. Resolve that one
   * Android-provided root before the filesystem's strict NOFOLLOW descendant traversal.
   * All failure facts are fixed labels; exception messages are never read.
   */
  public static String describe(BackupFileSystem fs, File files) {
    String phase = "FILES_CANONICAL", canonicalSame = "UNKNOWN";
    try {
      File canonical = files.getCanonicalFile();
      canonicalSame = Boolean.toString(files.getAbsoluteFile().equals(canonical));
      files = canonical;
      phase = "FILES_STAT";
      if (!fs.stat(files).type.equals("DIRECTORY"))
        return unavailable("FILES_ROOT_UNAVAILABLE", canonicalSame, Failure.none());
      File root = new File(files, ROOT);
      phase = "ROOT_STAT";
      BackupFileSystem.Node rootNode = fs.stat(root);
      if (rootNode.type.equals("MISSING"))
        return unavailable("ROOT_MISSING", canonicalSame, Failure.none());
      if (!rootNode.type.equals("DIRECTORY"))
        return unavailable("ROOT_TYPE_UNAVAILABLE", canonicalSame, Failure.none());
      Scan scan = new Scan();
      scan.canonicalSame = canonicalSame;
      scan.active = collect(fs, root, "ACTIVE", scan);
      File completed = new File(root, "completed");
      phase = "COMPLETED_STAT";
      BackupFileSystem.Node completedNode = fs.stat(completed);
      if (completedNode.type.equals("MISSING")) scan.completed = "MISSING";
      else if (completedNode.type.equals("DIRECTORY"))
        scan.completed = collect(fs, completed, "COMPLETED", scan);
      else {
        scan.completed = "TYPE_UNAVAILABLE";
        scan.complete = false;
      }
      phase = "ROOT_RECHECK";
      if (!rootNode.same(fs.stat(root))) {
        scan.active = "CHANGED";
        scan.complete = false;
      }
      return render(scan);
    } catch (IOException unavailable) {
      return unavailable("IO_UNAVAILABLE", canonicalSame, Failure.from(phase, unavailable));
    } catch (RuntimeException unavailable) {
      return unavailable("RUNTIME_UNAVAILABLE", canonicalSame, Failure.from(phase, unavailable));
    }
  }

  private static String unavailable(String status, String canonicalSame, Failure failure) {
    return "BACKUP_OPERATIONS version=1 status="
        + status
        + " complete=false jobs=UNKNOWN canonicalSame="
        + canonicalSame
        + failure.fields("failure");
  }

  private static final class Scan {
    String active = "UNKNOWN", completed = "UNKNOWN";
    String canonicalSame = "UNKNOWN";
    Failure activeFailure = Failure.none(), completedFailure = Failure.none();
    boolean complete = true;
    int attempted, unavailable;
    final List<Row> rows = new ArrayList<>();
  }

  private static final class Row {
    String location, current = "recordState=UNKNOWN", previous = "", markers = "";
    long updated = -1;
    boolean attention = true, readable;
  }

  private static String collect(BackupFileSystem fs, File directory, String location, Scan scan) {
    String phase = "DIRECTORY_STAT";
    try {
      BackupFileSystem.Node before = fs.stat(directory);
      if (!before.type.equals("DIRECTORY")) throw new IOException();
      phase = "DIRECTORY_LIST";
      List<String> names = fs.list(directory);
      if (names.size() > MAX_NAMES) {
        scan.complete = false;
        return "NAME_LIMIT";
      }
      phase = "ITERATING_JOBS";
      for (String name : names) {
        if (!com.deepseekharness.app.util.Ids.uuid(name)) continue;
        if (scan.attempted == MAX_JOBS) {
          scan.complete = false;
          return "JOB_LIMIT";
        }
        scan.attempted++;
        Row row = new Row();
        row.location = location;
        try {
          File job = new File(directory, name);
          BackupFileSystem.Node jobBefore = fs.stat(job);
          if (!jobBefore.type.equals("DIRECTORY")) throw new IOException();
          row.current = record(fs, job, name, "operation.json", false, row);
          row.previous = record(fs, job, name, "operation.json.previous", true, row);
          StringBuilder markers = new StringBuilder();
          for (String[] marker : MARKERS) {
            BackupFileSystem.Node node = fs.stat(new File(job, marker[1]));
            markers.append(' ').append(marker[0]).append('=');
            if (node.type.equals("MISSING")) markers.append("MISSING");
            else if (node.type.equals("FILE"))
              markers.append("FILE:").append(Math.max(0, node.size));
            else markers.append("NON_FILE");
          }
          row.markers = markers.toString();
          if (!jobBefore.same(fs.stat(job))) throw new IOException();
        } catch (IOException | RuntimeException unavailable) {
          row.current = "recordState=UNAVAILABLE";
          row.previous = "";
          row.markers = "";
          row.attention = true;
          row.readable = false;
        }
        if (!row.readable) {
          scan.unavailable++;
          scan.complete = false;
        }
        scan.rows.add(row);
      }
      phase = "DIRECTORY_RECHECK";
      if (!before.same(fs.stat(directory))) {
        scan.complete = false;
        return "CHANGED";
      }
      return "READ";
    } catch (IOException | RuntimeException unavailable) {
      scan.complete = false;
      if (location.equals("ACTIVE")) scan.activeFailure = Failure.from(phase, unavailable);
      else scan.completedFailure = Failure.from(phase, unavailable);
      return "UNAVAILABLE";
    }
  }

  private static final class Failure {
    private static final Set<String> SITES =
        Set.of(
            "BackupOperationDiagnostics",
            "AndroidBackupFileSystem",
            "BackupFileSystem",
            "BackupJson");
    final String phase, type, site;
    final int errno;

    Failure(String phase, String type, int errno, String site) {
      this.phase = phase;
      this.type = type;
      this.errno = errno;
      this.site = site;
    }

    static Failure none() {
      return new Failure("NONE", "NONE", -1, "NONE");
    }

    static Failure from(String phase, Throwable error) {
      String type =
          error instanceof java.io.InterruptedIOException
              ? "INTERRUPTED_IO"
              : error instanceof IOException
                  ? "IO"
                  : error instanceof SecurityException
                      ? "SECURITY"
                      : error instanceof IllegalArgumentException
                          ? "ARGUMENT"
                          : error instanceof IllegalStateException
                              ? "STATE"
                              : error instanceof RuntimeException ? "RUNTIME" : "OTHER";
      int errno = -1;
      String site = "NONE";
      Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      for (Throwable at = error;
          at != null && seen.size() < 8 && seen.add(at);
          at = at.getCause()) {
        if (errno == -1 && at.getClass().getName().equals("android.system.ErrnoException"))
          try {
            int value = at.getClass().getField("errno").getInt(at);
            if (value >= 0 && value <= 4095) errno = value;
          } catch (ReflectiveOperationException | RuntimeException ignored) {
          }
        if (site.equals("NONE")) {
          int checked = 0;
          for (StackTraceElement frame : at.getStackTrace()) {
            if (++checked > 64) break;
            String className = frame.getClassName();
            String prefix = "com.deepseekharness.app.backup.";
            if (!className.startsWith(prefix)) continue;
            String name = className.substring(prefix.length()).split("\\$", 2)[0];
            if (SITES.contains(name)
                && frame.getLineNumber() > 0
                && frame.getLineNumber() < 1000000) {
              site = name + ":" + frame.getLineNumber();
              break;
            }
          }
        }
      }
      return new Failure(phase, type, errno, site);
    }

    String fields(String prefix) {
      return " " + prefix + "Stage=" + phase + " " + prefix + "Type=" + type + " " + prefix
          + "Errno=" + errno + " " + prefix + "Site=" + site;
    }
  }

  private static String record(
      BackupFileSystem fs, File job, String id, String filename, boolean previous, Row row)
      throws IOException {
    File file = new File(job, filename);
    BackupFileSystem.Node node = fs.stat(file);
    String prefix = previous ? " previousRecordState=" : "recordState=";
    if (node.type.equals("MISSING")) return previous ? "" : prefix + "MISSING";
    if (!node.type.equals("FILE") || node.size < 0 || node.size > RECORD_LIMIT)
      return prefix + "UNREADABLE";
    Map<String, Object> value;
    try {
      value = BackupJson.read(fs.small(file, RECORD_LIMIT), RECORD_LIMIT);
    } catch (IOException | RuntimeException unavailable) {
      return prefix + "UNREADABLE";
    }
    if (!id.equals(value.get("id")) || !Long.valueOf(1).equals(value.get("version")))
      return prefix + "INVALID";
    String stage = allowed(value.get("stage"), STAGES);
    String error = allowed(value.get("error"), ERRORS);
    if (value.get("error") instanceof String
        && ((String) value.get("error")).matches("FILESYSTEM_[0-9]{1,4}"))
      error = (String) value.get("error");
    long updated = number(value.get("updatedAt"));
    if (previous)
      return prefix
          + "READ previousStage="
          + stage
          + " previousError="
          + empty(error)
          + " previousUpdatedAtMs="
          + updated;
    row.updated = updated;
    row.readable = true;
    row.attention = !error.isEmpty() || !stage.equals("FINISHED");
    String artifact =
        "portable.dshbak".equals(value.get("artifact"))
            ? "PORTABLE"
            : "".equals(value.get("artifact")) ? "NONE" : "UNRECOGNIZED";
    String busy = value.get("busy") instanceof Boolean ? value.get("busy").toString() : "UNKNOWN";
    return prefix
        + "READ stage="
        + stage
        + " result="
        + empty(allowed(value.get("result"), RESULTS))
        + " error="
        + empty(error)
        + " artifact="
        + artifact
        + " entries="
        + number(value.get("entries"))
        + " bytes="
        + number(value.get("bytes"))
        + " busy="
        + busy
        + " updatedAtMs="
        + updated;
  }

  private static String allowed(Object value, Set<String> allowed) {
    return value instanceof String && allowed.contains(value) ? (String) value : "UNRECOGNIZED";
  }

  private static String empty(String value) {
    return value.isEmpty() ? "NONE" : value;
  }

  private static long number(Object value) {
    return value instanceof Long && (Long) value >= 0 ? (Long) value : -1;
  }

  private static String render(Scan scan) {
    scan.rows.sort(
        Comparator.comparing((Row row) -> !row.attention)
            .thenComparing(Comparator.comparingLong((Row row) -> row.updated).reversed()));
    StringBuilder lines = new StringBuilder();
    int shown = 0;
    for (Row row : scan.rows) {
      if (shown == MAX_ROWS) break;
      String line =
          "\njob["
              + (shown + 1)
              + "] location="
              + row.location
              + " "
              + row.current
              + row.previous
              + row.markers;
      if (lines.length() + line.length() > MAX_TEXT - 512) break;
      lines.append(line);
      shown++;
    }
    return "BACKUP_OPERATIONS version=1 status=OBSERVED active="
        + scan.active
        + " completed="
        + scan.completed
        + " scanned="
        + scan.attempted
        + " shown="
        + shown
        + " omitted="
        + (scan.rows.size() - shown)
        + " unavailable="
        + scan.unavailable
        + " complete="
        + (scan.complete && shown == scan.rows.size())
        + " canonicalSame="
        + scan.canonicalSame
        + scan.activeFailure.fields("activeFailure")
        + scan.completedFailure.fields("completedFailure")
        + lines;
  }
}
