package com.deepseekharness.app.backup;

import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.ColdInstallPackages;
import com.deepseekharness.app.util.Ids;
import java.io.*;
import java.util.*;

/** Fresh installation only. Never admits a nonempty user root or replaces an existing environment. */
public final class ColdInstallTransaction {
  public static final String HOME = "cold-install-operations";
  private static final Set<String> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

  public interface Action {
    void run() throws IOException;
  }

  public interface Fault {
    void at(String boundary) throws IOException;
  }

  public interface RestoreSelection {
    void restore(Map<String, Object> before, String expectedRoot) throws IOException;
  }

  public static final class Candidate {
    private final ColdInstallTransaction transaction;
    private final BackupFileSystem.Node identity, rootIdentity;

    private Candidate(ColdInstallTransaction transaction) throws IOException {
      this.transaction = transaction;
      identity = transaction.fs.stat(linux());
      rootIdentity = transaction.fs.stat(root());
    }

    public File linux() {
      return new File(transaction.directory, "linux");
    }

    public File root() {
      return new File(linux(), "ubuntu");
    }

    public void beforeLaunch(String boundedId) throws IOException {
      File file = transaction.fs.child(transaction.directory, "launches.json");
      List<Object> ids = new ArrayList<>();
      if (transaction.fs.stat(file).type.equals("FILE")) {
        var previous = BackupJson.read(transaction.fs.small(file, 16384), 16384);
        Object value = previous.get("ids");
        if (!(value instanceof List)) throw new IOException("COLD_LAUNCH_RECORD");
        ids.addAll((List<?>) value);
      }
      Set<String> seen = new HashSet<>();
      for (Object id : ids)
        if (!(id instanceof String)
            || (!Ids.uuid((String) id) && !id.equals("untracked"))
            || !seen.add((String) id)) throw new IOException("COLD_LAUNCH_RECORD");
      if (ids.size() >= 4) throw new IOException("COLD_LAUNCH_BUDGET");
      if (boundedId != null && !Ids.uuid(boundedId)) throw new IOException("COLD_LAUNCH_RECORD");
      String id = boundedId == null ? "untracked" : boundedId;
      if (seen.contains(id)) throw new IOException("COLD_LAUNCH_DUPLICATE");
      ids.add(id);
      transaction.fs.atomic(
          transaction.directory, "launches.json", BackupJson.write(Map.of("ids", ids), 16384));
      transaction.mark("guest-started");
    }

    public void verify(BackupFileSystem fs, ColdInstallPackages.HostRoot host) throws IOException {
      transaction.host.verify(fs);
      host.verify(fs);
      if (!host.files.equals(transaction.host.files)
          || !same(identity, fs.stat(linux()))
          || !fs.child(transaction.directory, "linux/ubuntu").equals(root())
          || !same(rootIdentity, fs.stat(root()))) throw new IOException("COLD_CANDIDATE_CHANGED");
    }
  }

  private final BackupFileSystem fs;
  private final ColdInstallPackages.HostRoot host;
  private final File directory, live;
  private final Map<String, Object> intent;
  private final Fault fault;
  private final Candidate candidate;

  private ColdInstallTransaction(
      BackupFileSystem fs,
      ColdInstallPackages.HostRoot host,
      File directory,
      Map<String, Object> intent,
      Fault fault)
      throws IOException {
    this.fs = fs;
    this.host = host;
    this.directory = directory;
    this.intent = intent;
    this.fault = fault == null ? at -> {} : fault;
    live = fs.child(host.files, "linux");
    candidate = new Candidate(this);
  }

  private static boolean same(BackupFileSystem.Node a, BackupFileSystem.Node b) {
    return a.type.equals("DIRECTORY")
        && b.type.equals("DIRECTORY")
        && a.device == b.device
        && a.key.equals(b.key);
  }

  public static ColdInstallTransaction create(
      BackupFileSystem fs, ColdInstallPackages.HostRoot host, String runtimeId, Fault fault)
      throws IOException {
    if (!runtimeId.matches("[a-f0-9]{64}")) throw new IOException("COLD_RUNTIME_ID");
    host.verify(fs);
    if (!pending(fs, host.files).isEmpty()) throw new IOException("COLD_INSTALL_RECOVERY_REQUIRED");
    File live = fs.child(host.files, "linux");
    requireFresh(fs, live);
    // A missing/empty rootfs is not a fresh install when persistent user data has its own
    // authority.
    if (!fs.stat(fs.child(host.files, "user-data-v5")).type.equals("MISSING")
        || !fs.stat(fs.child(host.files, UserDataLayout.RECORD)).type.equals("MISSING")
        || !fs.stat(fs.child(host.files, UserDataLayout.RECORD + ".previous"))
            .type
            .equals("MISSING")) throw new IOException("COLD_EXISTING_DATA_DOMAIN");
    var old = fs.stat(live);
    String oldDigest = BackupTree.digest(fs, live, new BackupControl(null));
    File home = fs.child(host.files, HOME);
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    File directory = fs.child(home, UUID.randomUUID().toString());
    fs.directory(directory);
    File linux = fs.child(directory, "linux");
    fs.directory(linux);
    fs.directory(fs.child(linux, "ubuntu"));
    Map<String, Object> intent = new LinkedHashMap<>();
    intent.put("schema", 1L);
    intent.put("id", directory.getName());
    intent.put("runtimeId", runtimeId);
    intent.put("had", old.type.equals("DIRECTORY"));
    intent.put("oldKey", old.key);
    intent.put("oldDigest", oldDigest);
    intent.put("candidateKey", fs.stat(linux).key);
    fs.atomic(directory, "intent.json", BackupJson.write(intent, 8192));
    ColdInstallTransaction result = new ColdInstallTransaction(fs, host, directory, intent, fault);
    ACTIVE.add(directory.getAbsolutePath());
    return result;
  }

  private static void requireFresh(BackupFileSystem fs, File live) throws IOException {
    String type = fs.stat(live).type;
    if (type.equals("MISSING")) return;
    if (!type.equals("DIRECTORY")) throw new IOException("COLD_EXISTING_ROOT_TYPE");
    for (String name : fs.list(live)) {
      if (name.equals("ubuntu")) {
        File root = fs.child(live, name);
        if (!fs.stat(root).type.equals("DIRECTORY") || !fs.list(root).isEmpty())
          throw new IOException("COLD_EXISTING_DATA");
      } else if (!Set.of("lib", "tmp").contains(name)
          || !fs.stat(fs.child(live, name)).type.equals("DIRECTORY"))
        throw new IOException("COLD_EXISTING_ENVIRONMENT");
    }
  }

  public Candidate candidate() {
    return candidate;
  }

  public File directory() {
    return directory;
  }

  public void release() {
    ACTIVE.remove(directory.getAbsolutePath());
  }

  public void selectionBefore(Map<String, Object> before) throws IOException {
    if (!com.deepseekharness.app.util.ColdRuntimeSelection.KEYS.containsAll(before.keySet()))
      throw new IOException("COLD_SELECTION_SCOPE");
    Map<String, Object> encoded = new LinkedHashMap<>();
    for (var row : before.entrySet()) {
      Object value = row.getValue();
      String type =
          value instanceof String
              ? "STRING"
              : value instanceof Boolean
                  ? "BOOLEAN"
                  : value instanceof Integer
                      ? "INT"
                      : value instanceof Long
                          ? "LONG"
                          : value instanceof Float ? "FLOAT" : "INVALID";
      if (type.equals("INVALID")) throw new IOException("COLD_SELECTION_TYPE");
      encoded.put(row.getKey(), Map.of("type", type, "value", value));
    }
    var node = fs.stat(candidate.root());
    String expected =
        new File(host.files, "linux/ubuntu").getAbsolutePath() + ":" + node.device + ":" + node.key;
    fs.atomic(
        directory,
        "selection-before.json",
        BackupJson.write(
            Map.of(
                "schema",
                1L,
                "id",
                directory.getName(),
                "before",
                encoded,
                "expectedRoot",
                expected),
            16384));
  }

  private void mark(String name) throws IOException {
    fs.atomic(
        directory, name, directory.getName().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    fault.at(name);
  }

  private void unchangedLive() throws IOException {
    var node = fs.stat(live);
    if (Boolean.TRUE.equals(intent.get("had"))
        ? !node.type.equals("DIRECTORY") || !node.key.equals(intent.get("oldKey"))
        : !node.type.equals("MISSING")) throw new IOException("COLD_ORIGINAL_CHANGED");
    if (!intent.get("oldDigest").equals(BackupTree.digest(fs, live, new BackupControl(null))))
      throw new IOException("COLD_ORIGINAL_CHANGED");
  }

  /** The caller supplies its existing full-process barrier, not a PID/name heuristic. */
  public void prepared(Action requireClosed) throws IOException {
    requireClosed.run();
    candidate.verify(fs, host);
    unchangedLive();
    fs.atomic(
        directory,
        "prepared.json",
        BackupJson.write(
            Map.of(
                "schema",
                1L,
                "id",
                directory.getName(),
                "key",
                fs.stat(candidate.linux()).key,
                "digest",
                BackupTree.digest(fs, candidate.linux(), new BackupControl(null))),
            8192));
    mark("guest-closed");
  }

  public void publish(Action requireClosed, Action persistMode, Action publishMarkers)
      throws IOException {
    publish(requireClosed, persistMode, publishMarkers, () -> {});
  }

  public void publish(
      Action requireClosed, Action persistMode, Action publishMarkers, Action restoreSelection)
      throws IOException {
    requireClosed.run();
    candidate.verify(fs, host);
    unchangedLive();
    var proof = BackupJson.read(fs.small(fs.child(directory, "prepared.json"), 8192), 8192);
    if (!directory.getName().equals(proof.get("id"))
        || !intent.get("candidateKey").equals(proof.get("key"))
        || !proof
            .get("digest")
            .equals(BackupTree.digest(fs, candidate.linux(), new BackupControl(null))))
      throw new IOException("COLD_PREPARED_CHANGED");
    if (!marker(fs, directory, "guest-closed")) throw new IOException("COLD_GUEST_EXIT_REQUIRED");
    try {
      mark("publishing");
      if (Boolean.TRUE.equals(intent.get("had"))) {
        fs.move(live, fs.child(directory, "original-linux"));
        fs.syncDirectory(host.files);
        fault.at("old-moved");
      }
      fs.move(candidate.linux(), live);
      fs.syncDirectory(host.files);
      fault.at("candidate-published");
      persistMode.run();
      publishMarkers.run();
      mark("committed");
    } catch (IOException | RuntimeException failure) {
      // A durable commit must never be undone by a later notification/fault callback.
      try {
        if (!marker(fs, directory, "committed")) {
          rollback(fs, host.files, directory, intent);
          restoreSelection.run();
          mark("rolled-back");
        }
      } catch (IOException rollback) {
        failure.addSuppressed(rollback);
      }
      throw failure;
    }
  }

  public void failed(Action requireClosed) throws IOException {
    requireClosed.run();
    host.verify(fs);
    candidate.verify(fs, host);
    fs.atomic(
        directory,
        "failure-tree.json",
        BackupJson.write(
            Map.of(
                "key",
                fs.stat(candidate.linux()).key,
                "digest",
                BackupTree.digest(fs, candidate.linux(), new BackupControl(null))),
            8192));
    mark("failed-closed");
  }

  private static boolean marker(BackupFileSystem fs, File directory, String name)
      throws IOException {
    File file = fs.child(directory, name);
    String type = fs.stat(file).type;
    if (type.equals("MISSING")) return false;
    if (!type.equals("FILE")
        || !directory
            .getName()
            .equals(new String(fs.small(file, 128), java.nio.charset.StandardCharsets.US_ASCII)))
      throw new IOException("COLD_MARKER_INVALID");
    return true;
  }

  private static Map<String, Object> intent(BackupFileSystem fs, File directory)
      throws IOException {
    var value = BackupJson.read(fs.small(fs.child(directory, "intent.json"), 8192), 8192);
    if (BackupJson.number(value, "schema") != 1
        || !directory.getName().equals(value.get("id"))
        || !(value.get("had") instanceof Boolean)
        || !String.valueOf(value.get("runtimeId")).matches("[a-f0-9]{64}")
        || !String.valueOf(value.get("oldDigest")).matches("[a-f0-9]{64}")
        || !(value.get("candidateKey") instanceof String)
        || !(value.get("oldKey") instanceof String)) throw new IOException("COLD_INTENT_INVALID");
    return value;
  }

  public static List<String> pending(BackupFileSystem fs, File files) throws IOException {
    File home = fs.child(files, HOME);
    if (fs.stat(home).type.equals("MISSING")) return List.of();
    List<String> result = new ArrayList<>();
    for (String id : fs.list(home)) {
      if (!Ids.uuid(id)) throw new IOException("COLD_RECORD_ID");
      File entry = fs.child(home, id);
      if (!fs.stat(entry).type.equals("DIRECTORY")) throw new IOException("COLD_RECORD_TYPE");
      intent(fs, entry);
      if (ACTIVE.contains(entry.getAbsolutePath())) continue;
      boolean finished = marker(fs, entry, "committed") || marker(fs, entry, "rolled-back");
      if (!finished
          && (marker(fs, entry, "publishing")
              || marker(fs, entry, "guest-started")
                  && !marker(fs, entry, "guest-closed")
                  && !marker(fs, entry, "failed-closed"))) result.add(id);
    }
    return List.copyOf(result);
  }

  public static void recover(BackupFileSystem fs, File files, Action requireClosed)
      throws IOException {
    recover(
        fs,
        files,
        requireClosed,
        (before, expected) -> {
          throw new IOException("COLD_SELECTION_RESTORE_REQUIRED");
        });
  }

  public static void recover(
      BackupFileSystem fs, File files, Action requireClosed, RestoreSelection restoreSelection)
      throws IOException {
    for (String id : pending(fs, files)) {
      File entry = fs.child(files, HOME + "/" + id);
      requireClosed.run();
      if (!marker(fs, entry, "guest-closed")) {
        var launches = BackupJson.read(fs.small(fs.child(entry, "launches.json"), 16384), 16384);
        Object value = launches.get("ids");
        if (!(value instanceof List) || ((List<?>) value).isEmpty())
          throw new IOException("COLD_GUEST_EXIT_REQUIRED");
        for (Object item : (List<?>) value) {
          if (!(item instanceof String)
              || !Ids.uuid((String) item)
              || !fs.stat(fs.child(files, "bounded-guest-active/" + item)).type.equals("MISSING"))
            throw new IOException("COLD_GUEST_EXIT_REQUIRED");
        }
        if (marker(fs, entry, "publishing"))
          throw new IOException("COLD_PUBLICATION_WITHOUT_EXIT_PROOF");
        fs.atomic(entry, "failed-closed", id.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        continue;
      }
      rollback(fs, files, entry, intent(fs, entry));
      restoreSelection(fs, entry, restoreSelection);
      fs.atomic(entry, "rolled-back", id.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
  }

  public void restoreSelection(RestoreSelection restore) throws IOException {
    restoreSelection(fs, directory, restore);
  }

  private static void restoreSelection(BackupFileSystem fs, File entry, RestoreSelection restore)
      throws IOException {
    File file = fs.child(entry, "selection-before.json");
    if (fs.stat(file).type.equals("MISSING")) return;
    var value = BackupJson.read(fs.small(file, 16384), 16384);
    if (BackupJson.number(value, "schema") != 1 || !entry.getName().equals(value.get("id")))
      throw new IOException("COLD_SELECTION_RECORD");
    Object encoded = value.get("before");
    if (!(encoded instanceof Map)) throw new IOException("COLD_SELECTION_RECORD");
    Map<String, Object> before = new LinkedHashMap<>();
    for (var row : ((Map<?, ?>) encoded).entrySet()) {
      if (!(row.getKey() instanceof String)
          || !com.deepseekharness.app.util.ColdRuntimeSelection.KEYS.contains(row.getKey())
          || !(row.getValue() instanceof Map)) throw new IOException("COLD_SELECTION_SCOPE");
      var item = (Map<?, ?>) row.getValue();
      Object v = item.get("value");
      String type = String.valueOf(item.get("type"));
      try {
        switch (type) {
          case "STRING":
            if (!(v instanceof String)) throw new IOException("COLD_SELECTION_TYPE");
            break;
          case "BOOLEAN":
            if (!(v instanceof Boolean)) throw new IOException("COLD_SELECTION_TYPE");
            break;
          case "INT":
            if (!(v instanceof Number)) throw new IOException("COLD_SELECTION_TYPE");
            v =
                v instanceof java.math.BigDecimal
                    ? ((java.math.BigDecimal) v).intValueExact()
                    : Math.toIntExact(((Number) v).longValue());
            break;
          case "LONG":
            if (!(v instanceof Number)) throw new IOException("COLD_SELECTION_TYPE");
            v =
                v instanceof java.math.BigDecimal
                    ? ((java.math.BigDecimal) v).longValueExact()
                    : ((Number) v).longValue();
            break;
          case "FLOAT":
            if (!(v instanceof Number)) throw new IOException("COLD_SELECTION_TYPE");
            v = ((Number) v).floatValue();
            if (Float.isNaN((Float) v) || Float.isInfinite((Float) v))
              throw new IOException("COLD_SELECTION_TYPE");
            break;
          default:
            throw new IOException("COLD_SELECTION_TYPE");
        }
      } catch (ArithmeticException invalid) {
        throw new IOException("COLD_SELECTION_TYPE", invalid);
      }
      before.put((String) row.getKey(), v);
    }
    restore.restore(before, BackupJson.string(value, "expectedRoot"));
  }

  private static void rollback(
      BackupFileSystem fs, File files, File directory, Map<String, Object> intent)
      throws IOException {
    File live = fs.child(files, "linux"),
        old = fs.child(directory, "original-linux"),
        failed = fs.child(directory, "failed-linux");
    var node = fs.stat(live);
    var original = fs.stat(old);
    if (node.type.equals("DIRECTORY") && node.key.equals(intent.get("candidateKey"))) {
      if (!fs.stat(failed).type.equals("MISSING")) throw new IOException("COLD_FAILED_RETAINED");
      fs.move(live, failed);
      fs.syncDirectory(files);
      node = fs.stat(live);
    }
    if (Boolean.TRUE.equals(intent.get("had"))) {
      if (original.type.equals("DIRECTORY")
          && original.key.equals(intent.get("oldKey"))
          && node.type.equals("MISSING")) {
        fs.move(old, live);
        fs.syncDirectory(files);
      } else if (!node.type.equals("DIRECTORY") || !node.key.equals(intent.get("oldKey")))
        throw new IOException("COLD_ORIGINAL_UNCONFIRMED");
    } else if (!node.type.equals("MISSING")) throw new IOException("COLD_CURRENT_UNCONFIRMED");
    fs.atomic(
        directory,
        "tree-restored",
        directory.getName().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }
}
