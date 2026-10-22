package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Completed host operations move to a retained, non-destructive history directory. */
public final class HostOperationArchive {
  public static final String ROOT = PrivateRoots.BACKUPS, COMPLETED = "completed";
  private static final String HISTORY_PROOF = "host-backup-completed-v1.json";
  private static final int ACTIVE_LIMIT = BackupLimits.HOST_OPERATION_ACTIVE_RECORDS;

  private HostOperationArchive() {}

  public static File root(File files) {
    return new File(files.getAbsoluteFile(), ROOT);
  }

  public static File completedRoot(File files) {
    return new File(root(files), COMPLETED);
  }

  /** Completed records are inert history. A legacy completed tree is checked once before
   * ordinary startup stops traversing it; explicit history reads still inspect real bytes. */
  public static synchronized void verifyCompleted(BackupFileSystem fs, File files)
      throws IOException {
    File operations = root(files),
        history = completedRoot(files),
        proof = new File(files, HISTORY_PROOF);
    String type = fs.stat(history).type;
    if (type.equals("MISSING")) {
      if (!fs.stat(proof).type.equals("MISSING"))
        throw new IOException("OPERATION_HISTORY_PROOF_ORPHAN");
      return;
    }
    if (!type.equals("DIRECTORY")) throw new IOException("OPERATION_HISTORY_TYPE");
    if (!fs.stat(operations).type.equals("DIRECTORY")) throw new IOException("OPERATION_DIRECTORY");
    String filesKey = fs.stat(files).key, historyKey = fs.stat(history).key;
    if (CompletedHistory.matches(fs, proof, "filesKey", filesKey, historyKey, "OPERATION_HISTORY_"))
      return;
    for (String id : fs.list(history)) {
      if (!uuid(id)) throw new IOException("OPERATION_HISTORY_ID");
      File operation = fs.child(history, id);
      if (!fs.stat(operation).type.equals("DIRECTORY") || !terminal(fs, operation, id))
        throw new IOException("OPERATION_HISTORY_NOT_TERMINAL");
      if (!fs.stat(fs.child(operations, id)).type.equals("MISSING"))
        throw new IOException("OPERATION_DUPLICATE");
    }
    CompletedHistory.write(fs, proof, "filesKey", filesKey, historyKey);
  }

  /** Create an active slot only after safely archiving proven terminal journals. */
  public static File reserve(BackupFileSystem fs, File files) throws IOException {
    File operations = root(files);
    directory(fs, operations);
    verifyCompleted(fs, files);
    archiveTerminal(fs, operations);
    List<String> active = activeEntries(fs, operations);
    if (active.size() >= ACTIVE_LIMIT) throw new IOException("RETAINED_OPERATION_LIMIT");
    return operations;
  }

  public static List<File> roots(BackupFileSystem fs, File operations) throws IOException {
    List<File> result = new ArrayList<>();
    String rootType = fs.stat(operations).type;
    if (rootType.equals("MISSING")) return result;
    if (!rootType.equals("DIRECTORY")) throw new IOException("OPERATION_DIRECTORY");
    result.add(operations);
    File history = fs.child(operations, COMPLETED);
    String type = fs.stat(history).type;
    if (type.equals("DIRECTORY")) result.add(history);
    else if (!type.equals("MISSING")) throw new IOException("OPERATION_HISTORY_TYPE");
    return result;
  }

  public static List<String> activeEntries(BackupFileSystem fs, File operations)
      throws IOException {
    if (!fs.stat(operations).type.equals("DIRECTORY")) throw new IOException("OPERATION_DIRECTORY");
    List<String> result = new ArrayList<>();
    for (String name : fs.list(operations)) {
      if (name.equals(COMPLETED)) {
        if (!fs.stat(fs.child(operations, COMPLETED)).type.equals("DIRECTORY"))
          throw new IOException("OPERATION_HISTORY_TYPE");
      } else result.add(name);
    }
    return result;
  }

  /** Resolve an operation by id. A split active/completed duplicate is ambiguous and fails closed. */
  public static File locate(BackupFileSystem fs, File operations, String id) throws IOException {
    if (!uuid(id)) throw new IOException("OPERATION_ID");
    File active = fs.child(operations, id), historyRoot = fs.child(operations, COMPLETED);
    String a = fs.stat(active).type, historyType = fs.stat(historyRoot).type;
    if (!historyType.equals("DIRECTORY") && !historyType.equals("MISSING"))
      throw new IOException("OPERATION_HISTORY_TYPE");
    File history = new File(historyRoot, id);
    String h = historyType.equals("DIRECTORY") ? fs.stat(history).type : "MISSING";
    if (!a.equals("MISSING") && !h.equals("MISSING")) throw new IOException("OPERATION_DUPLICATE");
    if (a.equals("DIRECTORY")) return active;
    if (h.equals("DIRECTORY")) return history;
    if (!a.equals("MISSING") || !h.equals("MISSING")) throw new IOException("OPERATION_TYPE");
    throw new IOException("OPERATION_MISSING");
  }

  public static void archiveIfTerminal(BackupFileSystem fs, File files, File operation)
      throws IOException {
    File operations = root(files);
    if (!operation.getAbsoluteFile().getParentFile().equals(operations))
      throw new IOException("OPERATION_PATH");
    verifyCompleted(fs, files);
    String id = operation.getName();
    if (!uuid(id)) throw new IOException("OPERATION_ID");
    if (!fs.stat(operation).type.equals("DIRECTORY")) throw new IOException("OPERATION_DIRECTORY");
    if (NativeConfigurationReset.owns(fs, operation) && marker(fs, operation, "rolled-back"))
      NativeConfigurationReset.clearRolledBackPlaintext(fs, operation);
    if (terminalState(fs, operation, id) && hasRestorePlaintext(fs, operation))
      RestoreStaging.clearSensitivePlaintext(fs, operation);
    if (!terminal(fs, operation, id)) return;
    File history = fs.child(operations, COMPLETED);
    directory(fs, history);
    File destination = fs.child(history, id);
    if (!fs.stat(destination).type.equals("MISSING")) throw new IOException("OPERATION_DUPLICATE");

    BackupFileSystem.Node before = fs.stat(operation);
    String journal = journalDigest(fs, operation);
    BackupFileSystem.Node artifact = fs.stat(fs.child(operation, "portable.dshbak"));
    fs.move(operation, destination);
    fs.syncDirectory(operations);
    fs.syncDirectory(history);
    BackupFileSystem.Node after = fs.stat(destination);
    if (!before.key.equals(after.key)
        || !before.type.equals(after.type)
        || !journal.equals(journalDigest(fs, destination)))
      throw new IOException("OPERATION_ARCHIVE_IDENTITY");
    BackupFileSystem.Node movedArtifact = fs.stat(fs.child(destination, "portable.dshbak"));
    if (!artifact.key.equals(movedArtifact.key)
        || !artifact.type.equals(movedArtifact.type)
        || artifact.size != movedArtifact.size) throw new IOException("OPERATION_ARCHIVE_ARTIFACT");
    if (!fs.stat(operation).type.equals("MISSING"))
      throw new IOException("OPERATION_ARCHIVE_SOURCE_REMAINS");
    verifyCompleted(fs, files);
  }

  public static void archiveTerminal(BackupFileSystem fs, File operations) throws IOException {
    for (String id : new ArrayList<>(activeEntries(fs, operations))) {
      if (!uuid(id)) continue;
      File operation = fs.child(operations, id);
      if (!fs.stat(operation).type.equals("DIRECTORY")) continue;
      archiveIfTerminal(fs, operations.getParentFile(), operation);
    }
  }

  /** A reviewed profile preview is historical until its caller explicitly starts a commit. */
  public static File activateReviewedProfile(BackupFileSystem fs, File files, String id)
      throws IOException {
    File operations = root(files), source = locate(fs, operations, id);
    if (source.getParentFile().equals(operations)) return source;
    if (!source.getParentFile().equals(completedRoot(files)))
      throw new IOException("OPERATION_PATH");
    if (!terminal(fs, source, id)) throw new IOException("OPERATION_HISTORY_NOT_TERMINAL");
    File record = fs.child(source, "profile-settings.json");
    Map<String, Object> value =
        BackupJson.read(fs.small(record, BackupLimits.MANIFEST), BackupLimits.MANIFEST);
    if (!id.equals(BackupJson.string(value, "id"))
        || !"preview".equals(BackupJson.string(value, "mode"))
        || !(value.get("review") instanceof Map))
      throw new IOException("OPERATION_NOT_REVIEW_PREVIEW");
    if (marker(fs, source, "switching")
        || marker(fs, source, "finalized")
        || marker(fs, source, "rolled-back"))
      throw new IOException("OPERATION_PREVIEW_ALREADY_COMMITTED");
    reserve(fs, files);
    File destination = fs.child(operations, id);
    if (!fs.stat(destination).type.equals("MISSING")) throw new IOException("OPERATION_DUPLICATE");
    BackupFileSystem.Node before = fs.stat(source);
    String digest = journalDigest(fs, source);
    fs.move(source, destination);
    fs.syncDirectory(source.getParentFile());
    fs.syncDirectory(operations);
    BackupFileSystem.Node after = fs.stat(destination);
    if (!before.key.equals(after.key) || !digest.equals(journalDigest(fs, destination)))
      throw new IOException("OPERATION_ACTIVATION_IDENTITY");
    return destination;
  }

  private static boolean terminal(BackupFileSystem fs, File directory, String id)
      throws IOException {
    return terminalState(fs, directory, id) && !hasPlaintextStaging(fs, directory);
  }

  private static boolean terminalState(BackupFileSystem fs, File directory, String id)
      throws IOException {
    boolean switching = marker(fs, directory, "switching"),
        finalized = marker(fs, directory, "finalized"),
        rolledBack = marker(fs, directory, "rolled-back");
    boolean rollingBack = marker(fs, directory, "rolling-back");
    if (finalized && rolledBack) throw new IOException("OPERATION_TERMINAL_CONFLICT");
    if (rollingBack && !rolledBack) return false;
    if (switching && !finalized && !rolledBack) return false;

    File operationRecord = fs.child(directory, "operation.json");
    if (fs.stat(operationRecord).type.equals("FILE")) {
      Map<String, Object> value = BackupJson.read(fs.small(operationRecord, 16384), 16384);
      if (!id.equals(BackupJson.string(value, "id")) || BackupJson.number(value, "version") != 1)
        throw new IOException("OPERATION_RECORD");
      if (!(value.get("busy") instanceof Boolean)) throw new IOException("OPERATION_RECORD");
      String artifact = BackupJson.string(value, "artifact");
      if (!artifact.isEmpty() && !artifact.equals("portable.dshbak"))
        throw new IOException("OPERATION_RECORD");
      if (Boolean.TRUE.equals(value.get("busy"))) return false;
      String stage = BackupJson.string(value, "stage");
      return Set.of(
              "FINISHED",
              "FAILED",
              "FAILED_RETAINED",
              "CANCELLED",
              "CANCELLED_RETAINED",
              "INTERRUPTED_RETAINED")
          .contains(stage);
    }

    File profileRecord = fs.child(directory, "profile-settings.json");
    if (fs.stat(profileRecord).type.equals("FILE")) {
      Map<String, Object> value =
          BackupJson.read(fs.small(profileRecord, BackupLimits.MANIFEST), BackupLimits.MANIFEST);
      if (!id.equals(BackupJson.string(value, "id"))) throw new IOException("OPERATION_RECORD");
      if ("preview".equals(BackupJson.string(value, "mode"))
          && value.get("review") instanceof Map
          && BackupJson.string(value, "profileBefore").matches("[a-f0-9]{64}")
          && BackupJson.string(value, "incomingHash").matches("[a-f0-9]{64}")) return true;
    }

    File planFile = fs.child(directory, "plan.json");
    if (!fs.stat(planFile).type.equals("FILE") || !finalized && !rolledBack) return false;
    Map<String, Object> plan =
        BackupJson.read(fs.small(planFile, BackupLimits.MANIFEST), BackupLimits.MANIFEST);
    if (BackupJson.number(plan, "version") != 1 || !id.equals(BackupJson.string(plan, "id")))
      throw new IOException("OPERATION_PLAN");
    for (String record : List.of("config-reset.json", "profile-settings.json")) {
      File file = fs.child(directory, record);
      if (!fs.stat(file).type.equals("FILE")) continue;
      Map<String, Object> value =
          BackupJson.read(fs.small(file, BackupLimits.MANIFEST), BackupLimits.MANIFEST);
      if (!id.equals(BackupJson.string(value, "id"))) throw new IOException("OPERATION_RECORD");
    }
    return true;
  }

  private static boolean hasRestorePlaintext(BackupFileSystem fs, File directory)
      throws IOException {
    for (String name : List.of("restore.dshdata", "snapshot.dshdata", "payload"))
      if (!fs.stat(fs.child(directory, name)).type.equals("MISSING")) return true;
    return false;
  }

  public static boolean isTerminal(BackupFileSystem fs, File directory, String id)
      throws IOException {
    return uuid(id) && terminal(fs, directory, id);
  }

  private static boolean hasPlaintextStaging(BackupFileSystem fs, File directory)
      throws IOException {
    for (String name : List.of("restore.dshdata", "snapshot.dshdata", "payload"))
      if (!fs.stat(fs.child(directory, name)).type.equals("MISSING")) return true;
    if (!fs.stat(fs.child(directory, "environment-input")).type.equals("MISSING")) return true;
    File candidate = fs.child(directory, "candidate");
    String candidateType = fs.stat(candidate).type;
    if (candidateType.equals("DIRECTORY")) {
      if (!fs.stat(fs.child(candidate, "environment")).type.equals("MISSING")) return true;
    } else if (!candidateType.equals("MISSING")) throw new IOException("OPERATION_CANDIDATE_TYPE");
    return false;
  }

  private static boolean marker(BackupFileSystem fs, File directory, String name)
      throws IOException {
    return TransactionMarkers.read(fs, directory, name, "OPERATION_MARKER");
  }

  private static String journalDigest(BackupFileSystem fs, File directory) throws IOException {
    for (String name :
        List.of(
            "operation.json",
            "config-reset.json",
            "profile-settings.json",
            "plan.json",
            "verified.json")) {
      File file = fs.child(directory, name);
      if (fs.stat(file).type.equals("FILE"))
        return BackupArchive.hex(
            BackupArchive.sha()
                .digest(
                    fs.small(
                        file,
                        name.equals("plan.json") || name.equals("verified.json")
                            ? BackupLimits.MANIFEST
                            : 16384)));
    }
    throw new IOException("OPERATION_JOURNAL_MISSING");
  }

  private static void directory(BackupFileSystem fs, File file) throws IOException {
    String type = fs.stat(file).type;
    if (type.equals("MISSING")) fs.directory(file);
    else if (!type.equals("DIRECTORY")) throw new IOException("OPERATION_DIRECTORY");
  }

  private static boolean uuid(String value) {
    return value != null && value.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN);
  }
}
