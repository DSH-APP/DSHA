package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 整个 Ubuntu 重建的宿主日志；新环境与数据核验后可清理有完整证明的旧系统树。 */
public final class EnvironmentRebuildTransaction {
  public static final String HOME = PrivateRoots.ENVIRONMENTS;
  private static final String COMPLETED = "completed",
      HISTORY_PROOF = "host-environment-completed-v1.json";
  private final BackupFileSystem fs;
  private final File files, directory, linux;
  private final HostDataTransaction.Fault fault;

  private EnvironmentRebuildTransaction(
      BackupFileSystem fs, File files, File directory, HostDataTransaction.Fault fault)
      throws IOException {
    this.fs = fs;
    this.files = files;
    this.directory = directory;
    linux = fs.child(files, "linux");
    this.fault = fault == null ? name -> {} : fault;
    if (!directory.getName().matches(com.deepseekharness.app.util.Ids.UUID_PATTERN)
        || !fs.stat(directory).type.equals("DIRECTORY"))
      throw new IOException("ENVIRONMENT_TRANSACTION_ID");
  }

  public static EnvironmentRebuildTransaction create(
      BackupFileSystem fs, File files, HostDataTransaction.Fault fault) throws IOException {
    if (pending(fs, files) != null) throw new IOException("ENVIRONMENT_RECOVERY_REQUIRED");
    File home = fs.child(files, HOME);
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    pruneEmptyPlaceholders(fs, home);
    File directory = fs.child(home, UUID.randomUUID().toString());
    fs.directory(directory);
    EnvironmentRebuildTransaction created =
        new EnvironmentRebuildTransaction(fs, files, directory, fault);
    created.mark("created");
    return created;
  }

  public File directory() {
    File retained = new File(new File(files, HOME), COMPLETED + "/" + directory.getName());
    return directory.exists() ? directory : retained.exists() ? retained : directory;
  }

  public static EnvironmentRebuildTransaction open(
      BackupFileSystem fs, File files, String id, HostDataTransaction.Fault fault)
      throws IOException {
    if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
      throw new IOException("ENVIRONMENT_TRANSACTION_ID");
    File home = fs.child(files, HOME),
        active = fs.child(home, id),
        retained = new File(home, COMPLETED + "/" + id);
    String a = fs.stat(active).type, h = fs.stat(retained).type;
    if (!a.equals("MISSING") && !h.equals("MISSING"))
      throw new IOException("ENVIRONMENT_TRANSACTION_DUPLICATE");
    File selected = a.equals("DIRECTORY") ? active : h.equals("DIRECTORY") ? retained : null;
    if (selected == null) throw new IOException("ENVIRONMENT_TRANSACTION_ID");
    return new EnvironmentRebuildTransaction(fs, files, selected, fault);
  }

  private boolean marked(String name) throws IOException {
    return TransactionMarkers.read(fs, directory, name, "ENVIRONMENT_TRANSACTION_MARKER");
  }

  private void mark(String name) throws IOException {
    if (marked(name)) return;
    TransactionMarkers.write(fs, directory, name, "ENVIRONMENT_TRANSACTION_MARKER");
    fault.boundary(name);
  }

  public void prepare(String archiveHash, String mappingHash) throws IOException {
    File archive = fs.child(directory, "data.dshdata"),
        mapping = fs.child(directory, "data-roots.json");
    verify(archive, archiveHash);
    verify(mapping, mappingHash);
    var old = fs.stat(linux);
    if (!old.type.equals("MISSING") && !old.type.equals("DIRECTORY"))
      throw new IOException("ENVIRONMENT_ROOT_TYPE");
    Map<String, Object> intent =
        Map.of(
            "version",
            1L,
            "id",
            directory.getName(),
            "had",
            !old.type.equals("MISSING"),
            "oldKey",
            old.key,
            "archive",
            archiveHash,
            "mapping",
            mappingHash);
    try (OutputStream out = fs.create(fs.child(directory, "intent.json"))) {
      out.write(BackupJson.write(intent, 4096));
    }
    fs.syncDirectory(directory);
    mark("verified");
  }

  private void verify(File file, String expected) throws IOException {
    if (expected == null || !expected.matches("[a-f0-9]{64}"))
      throw new IOException("ENVIRONMENT_CHECKSUM");
    try (InputStream in = fs.read(file, fs.stat(file))) {
      if (!expected.equals(BackupArchive.digest(in, new BackupControl(null))))
        throw new IOException("ENVIRONMENT_CHECKSUM");
    }
  }

  private Map<String, Object> intent() throws IOException {
    var value = BackupJson.read(fs.small(fs.child(directory, "intent.json"), 4096), 4096);
    if (BackupJson.number(value, "version") != 1
        || !directory.getName().equals(BackupJson.string(value, "id"))
        || !(value.get("had") instanceof Boolean)
        || BackupJson.string(value, "oldKey").length() > 512)
      throw new IOException("ENVIRONMENT_TRANSACTION_FORMAT");
    for (String key : List.of("archive", "mapping"))
      if (!BackupJson.string(value, key).matches("[a-f0-9]{64}"))
        throw new IOException("ENVIRONMENT_TRANSACTION_FORMAT");
    return value;
  }

  public void begin() throws IOException {
    if (marked("switching") || marked("committed") || marked("rolled-back") || !marked("verified"))
      throw new IOException("ENVIRONMENT_TRANSACTION_STATE");
    var plan = intent();
    verify(fs.child(directory, "data.dshdata"), BackupJson.string(plan, "archive"));
    verify(fs.child(directory, "data-roots.json"), BackupJson.string(plan, "mapping"));
    var old = fs.stat(linux);
    if (Boolean.TRUE.equals(plan.get("had"))
        ? !old.type.equals("DIRECTORY") || !old.key.equals(plan.get("oldKey"))
        : !old.type.equals("MISSING")) throw new IOException("ENVIRONMENT_CHANGED");
    mark("switching");
    if (Boolean.TRUE.equals(plan.get("had"))) {
      fs.move(linux, fs.child(directory, "previous-linux"));
      fault.boundary("old-moved");
    }
    fs.directory(linux);
    try (OutputStream out = fs.create(fs.child(linux, ".maintenance-owner"))) {
      out.write(directory.getName().getBytes(StandardCharsets.US_ASCII));
    }
    fs.syncDirectory(linux);
    fault.boundary("candidate-created");
  }

  public void dataRestored(String hash) throws IOException {
    if (!hash.equals(intent().get("archive"))) throw new IOException("ENVIRONMENT_DATA_PROOF");
    mark("data-restored");
  }

  public void commit(RuntimeDescriptor descriptor, Map<String, Object> proof) throws IOException {
    if (!marked("switching")
        || !marked("data-restored")
        || !RuntimeDescriptor.healthy(proof, descriptor.id()))
      throw new IOException("ENVIRONMENT_HEALTH_REQUIRED");
    if (!directory
        .getName()
        .equals(
            new String(
                fs.small(fs.child(linux, ".maintenance-owner"), 128), StandardCharsets.US_ASCII)))
      throw new IOException("ENVIRONMENT_GENERATION");
    fs.atomic(directory, "health.json", BackupJson.write(proof, 512 * 1024));
    mark("committed");
  }

  public void rollback() throws IOException {
    if (marked("committed") || marked("rolled-back")) return;
    if (!marked("switching")) return;
    var plan = intent();
    mark("rolling-back");
    File old = fs.child(directory, "previous-linux"), failed = fs.child(directory, "failed-linux");
    var previous = fs.stat(old);
    var current = fs.stat(linux);
    if (Boolean.TRUE.equals(plan.get("had"))) {
      if (previous.type.equals("DIRECTORY")) {
        if (!previous.key.equals(plan.get("oldKey")))
          throw new IOException("ENVIRONMENT_ORIGINAL_CHANGED");
        if (!current.type.equals("MISSING")) {
          if (!fs.stat(failed).type.equals("MISSING"))
            throw new IOException("ENVIRONMENT_FAILED_RETAINED");
          fs.move(linux, failed);
          fault.boundary("rollback-new");
        }
        fs.move(old, linux);
        fault.boundary("rollback-old");
      } else if (!current.type.equals("DIRECTORY") || !current.key.equals(plan.get("oldKey")))
        throw new IOException("ENVIRONMENT_ORIGINAL_MISSING");
    } else if (!current.type.equals("MISSING")) {
      if (!fs.stat(failed).type.equals("MISSING"))
        throw new IOException("ENVIRONMENT_FAILED_RETAINED");
      fs.move(linux, failed);
      fault.boundary("rollback-new");
    }
    mark("rolled-back");
  }

  /** 仅新事务在提交时建立证明；不补猜历史事务是否曾完整保护个人数据。 */
  public void sealRetired(BackupControl control) throws IOException {
    if (!marked("committed") || !marked("data-restored"))
      throw new IOException("ENVIRONMENT_HEALTH_REQUIRED");
    var plan = intent();
    if (!Boolean.TRUE.equals(plan.get("had"))) return;
    verify(fs.child(directory, "data.dshdata"), BackupJson.string(plan, "archive"));
    verify(fs.child(directory, "data-roots.json"), BackupJson.string(plan, "mapping"));
    File old = fs.child(directory, "previous-linux");
    if (!fs.stat(old).key.equals(plan.get("oldKey")))
      throw new IOException("ENVIRONMENT_ORIGINAL_CHANGED");
    prepareRetired(old, control, 0);
    fs.atomic(
        directory,
        "retired-proof.json",
        BackupJson.write(
            Map.of(
                "version",
                1L,
                "id",
                directory.getName(),
                "oldKey",
                plan.get("oldKey"),
                "archive",
                plan.get("archive"),
                "mapping",
                plan.get("mapping"),
                "digest",
                BackupTree.digest(fs, old, control)),
            4096));
  }

  private void prepareRetired(File root, BackupControl control, int depth) throws IOException {
    control.check();
    if (depth > BackupLimits.DEPTH) throw new IOException("RETIRED_TREE_DEPTH");
    if (!fs.stat(root).type.equals("DIRECTORY")) return;
    fs.prepareOwnedRemoval(root);
    for (String child : fs.list(root)) prepareRetired(fs.child(root, child), control, depth + 1);
  }

  public boolean cleanupRetired(BackupControl control) throws IOException {
    if (marked("old-environment-cleaned")) return false;
    if (!marked("committed")
        || !marked("data-restored")
        || !fs.stat(fs.child(directory, "retired-proof.json")).type.equals("FILE")) return false;
    var plan = intent();
    var proof = BackupJson.read(fs.small(fs.child(directory, "retired-proof.json"), 4096), 4096);
    if (BackupJson.number(proof, "version") != 1
        || !directory.getName().equals(proof.get("id"))
        || !plan.get("oldKey").equals(proof.get("oldKey"))
        || !plan.get("archive").equals(proof.get("archive"))
        || !plan.get("mapping").equals(proof.get("mapping")))
      throw new IOException("RETIRED_PROOF_CHANGED");
    verify(fs.child(directory, "data.dshdata"), BackupJson.string(plan, "archive"));
    verify(fs.child(directory, "data-roots.json"), BackupJson.string(plan, "mapping"));
    File old = fs.child(directory, "previous-linux");
    if (!fs.stat(old).key.equals(plan.get("oldKey"))
        || !BackupTree.digest(fs, old, control).equals(proof.get("digest")))
      throw new IOException("RETIRED_TREE_CHANGED");
    control.check();
    fs.removeOwned(directory, "previous-linux");
    mark("old-environment-cleaned");
    return true;
  }

  public static void cleanupCompleted(BackupFileSystem fs, File files, BackupControl control)
      throws IOException {
    if (pending(fs, files) != null) throw new IOException("ENVIRONMENT_RECOVERY_REQUIRED");
    File home = fs.child(files, HOME);
    if (fs.stat(home).type.equals("MISSING")) return;
    for (File parent : List.of(home, new File(home, COMPLETED)))
      if (fs.stat(parent).type.equals("DIRECTORY"))
        for (String id : fs.list(parent)) {
          if (id.equals(COMPLETED)) continue;
          control.check();
          open(fs, files, id, null).cleanupRetired(control);
        }
  }

  public static EnvironmentRebuildTransaction pending(BackupFileSystem fs, File files)
      throws IOException {
    verifyHistory(fs, files);
    File home = fs.child(files, HOME);
    if (fs.stat(home).type.equals("MISSING")) return null;
    List<String> entries = fs.list(home);
    EnvironmentRebuildTransaction pending = null;
    for (String id : entries) {
      if (id.equals(COMPLETED)) continue;
      if (!fs.stat(new File(home, COMPLETED + "/" + id)).type.equals("MISSING"))
        throw new IOException("ENVIRONMENT_TRANSACTION_DUPLICATE");
      var item = new EnvironmentRebuildTransaction(fs, files, fs.child(home, id), null);
      if (item.marked("switching") && !item.marked("committed") && !item.marked("rolled-back")) {
        if (pending != null) throw new IOException("MULTIPLE_ENVIRONMENT_TRANSACTIONS");
        item.intent();
        pending = item;
      }
    }
    return pending;
  }

  private boolean terminal() throws IOException {
    boolean committed = marked("committed"), rolledBack = marked("rolled-back");
    if (committed == rolledBack) return false;
    intent();
    return true;
  }

  public static boolean isTerminalRecord(BackupFileSystem fs, File files, String id)
      throws IOException {
    if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN)) return false;
    File entry = new File(files, HOME + "/" + id);
    return fs.stat(entry).type.equals("DIRECTORY")
        && new EnvironmentRebuildTransaction(fs, files, entry, null).terminal();
  }

  private static void verifyHistory(BackupFileSystem fs, File files) throws IOException {
    File home = new File(files, HOME),
        history = new File(home, COMPLETED),
        proof = new File(files, HISTORY_PROOF);
    String type = fs.stat(history).type;
    if (type.equals("MISSING")) {
      if (!fs.stat(proof).type.equals("MISSING"))
        throw new IOException("ENVIRONMENT_HISTORY_PROOF_ORPHAN");
      return;
    }
    if (!type.equals("DIRECTORY") || !fs.stat(home).type.equals("DIRECTORY"))
      throw new IOException("ENVIRONMENT_HISTORY_TYPE");
    String filesKey = fs.stat(files).key, historyKey = fs.stat(history).key;
    if (CompletedHistory.matches(
        fs, proof, "filesKey", filesKey, historyKey, "ENVIRONMENT_HISTORY_")) return;
    for (String id : fs.list(history)) {
      EnvironmentRebuildTransaction item =
          new EnvironmentRebuildTransaction(fs, files, fs.child(history, id), null);
      if (!item.terminal()) throw new IOException("ENVIRONMENT_HISTORY_NOT_TERMINAL");
      if (!fs.stat(new File(home, id)).type.equals("MISSING"))
        throw new IOException("ENVIRONMENT_TRANSACTION_DUPLICATE");
    }
    CompletedHistory.write(fs, proof, "filesKey", filesKey, historyKey);
  }

  public static void archiveCompleted(BackupFileSystem fs, File files) throws IOException {
    File home = new File(files, HOME);
    verifyHistory(fs, files);
    if (fs.stat(home).type.equals("MISSING")) return;
    File history = new File(home, COMPLETED);
    for (String id : fs.list(home)) {
      if (id.equals(COMPLETED)) continue;
      EnvironmentRebuildTransaction item =
          new EnvironmentRebuildTransaction(fs, files, fs.child(home, id), null);
      if (!item.marked("committed") && !item.marked("rolled-back")) continue;
      if (!item.terminal()) throw new IOException("ENVIRONMENT_HISTORY_NOT_TERMINAL");
      if (fs.stat(history).type.equals("MISSING")) fs.directory(history);
      File destination = fs.child(history, id);
      if (!fs.stat(destination).type.equals("MISSING"))
        throw new IOException("ENVIRONMENT_TRANSACTION_DUPLICATE");
      BackupFileSystem.Node before = fs.stat(item.directory);
      String digest =
          BackupArchive.hex(
              BackupArchive.sha().digest(fs.small(fs.child(item.directory, "intent.json"), 4096)));
      fs.move(item.directory, destination);
      fs.syncDirectory(home);
      fs.syncDirectory(history);
      if (!before.key.equals(fs.stat(destination).key)
          || !digest.equals(
              BackupArchive.hex(
                  BackupArchive.sha()
                      .digest(fs.small(fs.child(destination, "intent.json"), 4096)))))
        throw new IOException("ENVIRONMENT_ARCHIVE_IDENTITY");
      verifyHistory(fs, files);
    }
  }

  /** An explicitly marked, otherwise empty pre-switch slot contains no recovery payload. */
  private static void pruneEmptyPlaceholders(BackupFileSystem fs, File home) throws IOException {
    if (!fs.stat(home).type.equals("DIRECTORY")) return;
    for (String id : fs.list(home)) {
      if (id.equals(COMPLETED)) continue;
      if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
        throw new IOException("ENVIRONMENT_TRANSACTION_ID");
      File directory = fs.child(home, id);
      if (!fs.stat(directory).type.equals("DIRECTORY"))
        throw new IOException("ENVIRONMENT_TRANSACTION_ID");
      File created = fs.child(directory, "created");
      var marker = fs.stat(created);
      if (marker.type.equals("MISSING")) continue;
      if (!marker.type.equals("FILE")
          || !(id + "\ncreated\n")
              .equals(new String(fs.small(created, 256), StandardCharsets.UTF_8))) continue;
      if (fs.list(directory).size() == 1) fs.removeOwned(home, id);
    }
  }

  public static boolean blocked(File files) {
    try {
      return pending(new AndroidBackupFileSystem(), files.getCanonicalFile()) != null;
    } catch (IOException error) {
      return true;
    }
  }
}
