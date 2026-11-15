package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 宿主六文件检查点。正文逐文件保存，日志在 rootfs 外；不读取会话、工作区或 Keystore。 */
public final class ConfigurationSnapshots {
  public static final List<String> FILES =
      List.of(
          "profiles/web/package.json",
          "profiles/web/cordis.patch.yml",
          "profiles/web/pnpm-workspace.yaml",
          "profiles/web/pnpm-lock.yaml",
          "settings.yaml",
          "cordis.patch.yml");
  public static final String STORE = PrivateRoots.CONFIG_SNAPSHOTS,
      OPERATIONS = PrivateRoots.CONFIG_OPERATIONS;
  private static final String HISTORY_PROOF = "startup-config-completed-v1.json";
  private static final int LIMIT = LegacyConfigurationSnapshot.FILE_LIMIT;

  /** 未被目录引用且无法通过校验的快照目录：保留给用户确认，不参与轮换与数量上限。 */
  public static final String RETAINED = "retained";

  /** 两段删除的暂存区：先一次 rename 退出快照命名空间，再递归删除；中断后下次继续清理。 */
  static final String RETIRING = "retiring";

  static final String MARKERS = "retained-markers";
  static final int SNAPSHOT_LIMIT = 72, RETAINED_LIMIT = 32, MARKER_KEEP = 8;
  private static final int RETAINED_SHOWN = 50;

  /** 同一进程内所有快照写入口串行；启动候选、健康记录与恢复页操作不能交错改写目录。 */
  private static final Object LOCK = new Object();

  private final BackupFileSystem fs;
  private final File files, dsh, store, operations;
  private final HostDataTransaction.Fault fault;

  /** 已提交操作之后的整理失败只记录在这里，由调用方写诊断，不能把已生效的写入报告为失败。 */
  private final List<String> warnings = new ArrayList<>();

  private static final HostDataTransaction.Settings NATIVE_UNCHANGED =
      new HostDataTransaction.Settings() {
        public Map<String, Object> current() {
          return Collections.emptyMap();
        }

        public void apply(Map<String, Object> values) throws IOException {
          if (!values.isEmpty()) throw new IOException("RECOVERY_NATIVE_SETTINGS");
        }
      };

  public ConfigurationSnapshots(BackupFileSystem fs, File files, File dsh) {
    this(fs, files, dsh, null);
  }

  public ConfigurationSnapshots(
      BackupFileSystem fs, File files, File dsh, HostDataTransaction.Fault fault) {
    this.fs = fs;
    this.files = files;
    this.dsh = dsh;
    this.fault = fault;
    store = new File(files, STORE);
    operations = new File(files, OPERATIONS);
  }

  private static String uuid(String value) throws IOException {
    if (value == null || !value.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
      throw new IOException("RECOVERY_ID");
    return value;
  }

  private static boolean snapshotId(String value) {
    return value != null && value.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN);
  }

  /** 取消与线程中断必须原样上抛，不能被当成“快照损坏”而触发隔离。 */
  private static boolean cancelled(IOException error) {
    return error instanceof InterruptedIOException;
  }

  /** 诊断与界面只显示受控错误码，不回显路径或系统原文。 */
  public static String errorCode(Throwable error) {
    String message = error == null ? null : error.getMessage();
    if (message != null && message.matches("[A-Z][A-Z0-9_]{2,63}")) return message;
    return error == null ? "UNKNOWN" : error.getClass().getSimpleName();
  }

  /** 读取并清空本次实例积累的整理告警。 */
  public List<String> drainWarnings() {
    List<String> result = new ArrayList<>(warnings);
    warnings.clear();
    return result;
  }

  private void warn(String stage, String id, Throwable error) {
    if (warnings.size() < 32) warnings.add(stage + ":" + id + ":" + errorCode(error));
  }

  /** 第一段：一次 rename 让目录离开快照命名空间；之后任何中断都只会留下 retiring 中的残片。 */
  private void retire(String id) throws IOException {
    File source = fs.child(store, id);
    if (!fs.stat(source).type.equals("DIRECTORY")) return;
    File holding = fs.child(store, RETIRING);
    directory(holding);
    File target = fs.child(holding, id);
    if (!fs.stat(target).type.equals("MISSING")) {
      // 同名残片先清掉；仍不能清理时保留原目录，交给下一次整理。
      fs.removeOwned(holding, id);
    }
    fs.move(source, target);
  }

  /** 第二段：清理 retiring 中的全部残片。失败只记告警，残片留待下一次继续。 */
  private void sweepRetiring() throws IOException {
    File holding = new File(store, RETIRING);
    var node = fs.stat(holding);
    if (node.type.equals("MISSING")) return;
    if (!node.type.equals("DIRECTORY")) throw new IOException("RECOVERY_LINK");
    for (String name : fs.list(holding)) {
      try {
        fs.removeOwned(holding, name);
      } catch (InterruptedIOException stop) {
        throw stop;
      } catch (IOException failed) {
        warn("retiring", name, failed);
      }
    }
  }

  /** 异常原件不删除，只移入隔离区；隔离区已满时原地保留并记告警，不阻断后续快照。 */
  private boolean quarantine(String id) throws IOException {
    File source = fs.child(store, id);
    if (!fs.stat(source).type.equals("DIRECTORY")) return false;
    File holding = fs.child(store, RETAINED);
    directory(holding);
    if (fs.list(holding).size() >= RETAINED_LIMIT) {
      warn("retained-full", id, new IOException("RECOVERY_RETAINED_FULL"));
      return false;
    }
    File target = fs.child(holding, id);
    if (!fs.stat(target).type.equals("MISSING")) {
      warn("retained-duplicate", id, new IOException("RECOVERY_OPERATION_DUPLICATE"));
      return false;
    }
    fs.move(source, target);
    return true;
  }

  /**
   * 整理快照目录：未被引用且校验通过的旧快照两段删除；未被引用且无法校验的目录移入隔离区。
   * 被目录引用或固定的快照永不触碰。单项失败只记告警，取消原样上抛。
   */
  private void tidy(Map<String, Object> index, Collection<?> pinned, BackupControl control)
      throws IOException {
    if (!fs.stat(store).type.equals("DIRECTORY")) return;
    sweepRetiring();
    Set<Object> keep = new HashSet<>(index.values());
    keep.addAll(pinned);
    for (String id : fs.list(store)) {
      if (!snapshotId(id) || keep.contains(id)) continue;
      control.check();
      boolean valid;
      try {
        load(id, control);
        valid = true;
      } catch (IOException unreadable) {
        if (cancelled(unreadable)) throw unreadable;
        valid = false;
      }
      try {
        if (valid) retire(id);
        else quarantine(id);
      } catch (InterruptedIOException stop) {
        throw stop;
      } catch (IOException failed) {
        warn(valid ? "retire" : "quarantine", id, failed);
      }
    }
    sweepRetiring();
  }

  /** 已提交之后的整理：失败（包括取消）都不改变已生效的结果，残留交给下一次整理。 */
  private void tidyAfterCommit(
      Map<String, Object> index, Collection<?> pinned, BackupControl control) {
    try {
      tidy(index, pinned, control);
    } catch (IOException failed) {
      warn("tidy", "-", failed);
    }
  }

  private int snapshotCount() throws IOException {
    int count = 0;
    for (String name : fs.list(store)) if (snapshotId(name)) count++;
    return count;
  }

  /** 新建正文失败时只回收本次刚创建、inode 未变的目录；它从未进入目录索引，不属于异常原件。 */
  private void discardOwn(File folder, String key, IOException cause) {
    try {
      var node = fs.stat(folder);
      if (node.type.equals("DIRECTORY") && node.key.equals(key)) retire(folder.getName());
      sweepRetiring();
    } catch (IOException cleanup) {
      cause.addSuppressed(cleanup);
      warn("capture-cleanup", folder.getName(), cleanup);
    }
  }

  private void directory(File file) throws IOException {
    var state = fs.stat(file);
    if (state.type.equals("MISSING")) fs.directory(file);
    else if (!state.type.equals("DIRECTORY")) throw new IOException("RECOVERY_LINK");
  }

  private void ensureStore() throws IOException {
    directory(store);
    directory(operations);
  }

  private List<String> activeOperations() throws IOException {
    if (!fs.stat(operations).type.equals("DIRECTORY")) return Collections.emptyList();
    List<String> result = new ArrayList<>();
    for (String id : fs.list(operations)) {
      if (id.equals(HostOperationArchive.COMPLETED)) {
        if (!fs.stat(fs.child(operations, id)).type.equals("DIRECTORY"))
          throw new IOException("RECOVERY_LINK");
      } else result.add(id);
    }
    return result;
  }

  /** Old completed journals are checked once before ordinary startup treats this tree as inert history. */
  private void verifyCompleted() throws IOException {
    File history = new File(operations, HostOperationArchive.COMPLETED),
        proof = new File(files, HISTORY_PROOF);
    String type = fs.stat(history).type;
    if (type.equals("MISSING")) {
      if (!fs.stat(proof).type.equals("MISSING"))
        throw new IOException("RECOVERY_HISTORY_PROOF_ORPHAN");
      return;
    }
    if (!type.equals("DIRECTORY")) throw new IOException("RECOVERY_LINK");
    if (!fs.stat(operations).type.equals("DIRECTORY")) throw new IOException("RECOVERY_LINK");
    String filesKey = fs.stat(files).key, historyKey = fs.stat(history).key;
    if (CompletedHistory.matches(fs, proof, "filesKey", filesKey, historyKey, "RECOVERY_HISTORY_"))
      return;
    for (String id : fs.list(history)) {
      uuid(id);
      File directory = fs.child(history, id);
      if (!fs.stat(directory).type.equals("DIRECTORY"))
        throw new IOException("RECOVERY_HISTORY_TYPE");
      boolean finalized = mark(directory, "finalized"), rolledBack = mark(directory, "rolled-back");
      if (finalized == rolledBack) throw new IOException("RECOVERY_HISTORY_NOT_TERMINAL");
      Map<String, Object> plan =
          BackupJson.read(
              fs.small(fs.child(directory, "plan.json"), BackupLimits.MANIFEST),
              BackupLimits.MANIFEST);
      if (BackupJson.number(plan, "version") != 1 || !id.equals(BackupJson.string(plan, "id")))
        throw new IOException("RECOVERY_FORMAT");
      if (!fs.stat(fs.child(operations, id)).type.equals("MISSING"))
        throw new IOException("RECOVERY_OPERATION_DUPLICATE");
    }
    CompletedHistory.write(fs, proof, "filesKey", filesKey, historyKey);
  }

  private void archiveCompletedOperations() throws IOException {
    File history = fs.child(operations, HostOperationArchive.COMPLETED);
    directory(history);
    verifyCompleted();
    for (String id : new ArrayList<>(activeOperations())) {
      if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN)) continue;
      File source = fs.child(operations, id);
      if (!fs.stat(source).type.equals("DIRECTORY")) continue;
      boolean finalized = mark(source, "finalized"),
          rolledBack = mark(source, "rolled-back"),
          switching = mark(source, "switching");
      if (finalized && rolledBack) throw new IOException("RECOVERY_TERMINAL_CONFLICT");
      if (!finalized && !rolledBack || switching && !finalized && !rolledBack) continue;
      File planFile = fs.child(source, "plan.json");
      Map<String, Object> plan =
          BackupJson.read(fs.small(planFile, BackupLimits.MANIFEST), BackupLimits.MANIFEST);
      if (BackupJson.number(plan, "version") != 1 || !id.equals(BackupJson.string(plan, "id")))
        throw new IOException("RECOVERY_FORMAT");
      File destination = fs.child(history, id);
      if (!fs.stat(destination).type.equals("MISSING"))
        throw new IOException("RECOVERY_OPERATION_DUPLICATE");
      BackupFileSystem.Node before = fs.stat(source);
      String proof = operationProof(source);
      fs.move(source, destination);
      fs.syncDirectory(operations);
      fs.syncDirectory(history);
      if (!before.key.equals(fs.stat(destination).key)
          || !proof.equals(operationProof(destination)))
        throw new IOException("RECOVERY_ARCHIVE_IDENTITY");
    }
  }

  private String operationProof(File directory) throws IOException {
    java.security.MessageDigest hash = BackupArchive.sha();
    for (String name : List.of("plan.json", "switching", "finalized", "rolled-back")) {
      File file = fs.child(directory, name);
      var node = fs.stat(file);
      hash.update(name.getBytes(StandardCharsets.UTF_8));
      hash.update((byte) 0);
      if (node.type.equals("FILE"))
        hash.update(fs.small(file, name.equals("plan.json") ? BackupLimits.MANIFEST : 256));
    }
    return BackupArchive.hex(hash.digest());
  }

  private File document(String relative) throws IOException {
    if (!FILES.contains(relative)) throw new IOException("RECOVERY_FORMAT");
    File cursor = dsh;
    String[] parts = relative.split("/");
    for (int i = 0; i < parts.length; i++) {
      var parent = fs.stat(cursor);
      if (parent.type.equals("MISSING")) return new File(dsh, relative);
      if (!parent.type.equals("DIRECTORY")) throw new IOException("RECOVERY_LINK");
      cursor = new File(cursor, parts[i]);
    }
    return cursor;
  }

  private byte[] readData(String name) throws IOException {
    File file = document(name);
    var state = fs.stat(file);
    if (state.type.equals("MISSING")) return null;
    if (!state.type.equals("FILE")) throw new IOException("RECOVERY_LINK");
    if (state.size > LIMIT) throw new IOException("RECOVERY_SIZE");
    return fs.small(file, LIMIT);
  }

  static Map<String, Object> entry(byte[] bytes) {
    return bytes == null
        ? Map.of("present", false, "size", 0L, "sha256", "")
        : Map.of(
            "present",
            true,
            "size",
            (long) bytes.length,
            "sha256",
            BackupArchive.hex(BackupArchive.sha().digest(bytes)));
  }

  private Map<String, Object> signatures(BackupControl control) throws IOException {
    if (!fs.stat(dsh).type.equals("DIRECTORY")) throw new IOException("RECOVERY_DATA_UNAVAILABLE");
    Map<String, Object> result = new LinkedHashMap<>();
    for (String name : FILES) {
      control.check();
      result.put(name, entry(readData(name)));
    }
    return result;
  }

  private Map<String, Object> capture(String startup, Collection<?> pinned, BackupControl control)
      throws IOException {
    ensureStore();
    // 先整理再计数：上限只统计合法命名的快照目录，隔离区、暂存区和目录文件都不占名额。
    // 目录不可读时引用关系未知：不整理、不新建，直接失败（所有调用方随后都要写目录）。
    tidy(writableCatalog(), pinned, control);
    if (snapshotCount() >= SNAPSHOT_LIMIT) throw new IOException("RECOVERY_RETENTION_LIMIT");
    if (!fs.stat(dsh).type.equals("DIRECTORY")) throw new IOException("RECOVERY_DATA_UNAVAILABLE");
    String id = UUID.randomUUID().toString();
    File folder = fs.child(store, id);
    fs.directory(folder);
    String key = fs.stat(folder).key;
    try {
      Map<String, Object> content = new LinkedHashMap<>();
      for (int i = 0; i < FILES.size(); i++) {
        control.check();
        byte[] bytes = readData(FILES.get(i));
        content.put(FILES.get(i), entry(bytes));
        if (bytes != null)
          try (OutputStream out = fs.create(fs.child(folder, String.valueOf(i)))) {
            out.write(bytes);
          }
      }
      if (!content.equals(signatures(control))) throw new IOException("RECOVERY_CHANGED");
      Map<String, Object> doc = new LinkedHashMap<>();
      doc.put("version", 2L);
      doc.put("id", id);
      doc.put("created", System.currentTimeMillis());
      doc.put("dshVersion", com.deepseekharness.app.util.Constants.DSH_VERSION);
      doc.put("files", content);
      if (startup != null) doc.put("startupId", startup);
      try (OutputStream out = fs.create(fs.child(folder, "metadata.json"))) {
        out.write(BackupJson.write(doc, 16384));
      }
      fs.syncDirectory(folder);
      fs.syncDirectory(store);
      return load(id, control);
    } catch (IOException failed) {
      discardOwn(folder, key, failed);
      throw failed;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> entries(Map<String, Object> doc) throws IOException {
    if (!(doc.get("files") instanceof Map)) throw new IOException("RECOVERY_FORMAT");
    Map<String, Object> records = (Map<String, Object>) doc.get("files");
    if (!records.keySet().equals(new HashSet<>(FILES))) throw new IOException("RECOVERY_FORMAT");
    for (Object value : records.values()) {
      if (!(value instanceof Map)) throw new IOException("RECOVERY_FORMAT");
      Map<String, Object> row = (Map<String, Object>) value;
      long size = BackupJson.number(row, "size");
      String hash = BackupJson.string(row, "sha256");
      if (!(row.get("present") instanceof Boolean)
          || size < 0
          || size > LIMIT
          || Boolean.TRUE.equals(row.get("present")) && !hash.matches("[a-f0-9]{64}")
          || Boolean.FALSE.equals(row.get("present")) && (size != 0 || !hash.isEmpty()))
        throw new IOException("RECOVERY_FORMAT");
    }
    return records;
  }

  private Map<String, Object> load(String id, BackupControl control) throws IOException {
    File folder = fs.child(store, uuid(id));
    Map<String, Object> doc =
        BackupJson.read(fs.small(fs.child(folder, "metadata.json"), 16384), 16384);
    if (BackupJson.number(doc, "version") != 2
        || !id.equals(BackupJson.string(doc, "id"))
        || BackupJson.number(doc, "created") < 0) throw new IOException("RECOVERY_FORMAT");
    Map<String, Object> rows = entries(doc);
    for (int i = 0; i < FILES.size(); i++) {
      @SuppressWarnings("unchecked")
      Map<String, Object> row = (Map<String, Object>) rows.get(FILES.get(i));
      File file = fs.child(folder, String.valueOf(i));
      if (Boolean.TRUE.equals(row.get("present"))) {
        control.check();
        byte[] bytes = fs.small(file, LIMIT);
        if (!entry(bytes).equals(row)) throw new IOException("RECOVERY_CHECKSUM");
      } else if (!fs.stat(file).type.equals("MISSING")) throw new IOException("RECOVERY_CHECKSUM");
    }
    return doc;
  }

  private boolean slot(String value) {
    return value != null
        && (value.equals("candidate") || value.matches("(?:healthy|before)-[1-3]"));
  }

  private Map<String, Object> readCatalog(File path) throws IOException {
    if (fs.stat(path).type.equals("MISSING")) return new LinkedHashMap<>();
    Map<String, Object> result = BackupJson.read(fs.small(path, 4096), 4096);
    for (var entry : result.entrySet())
      if (!slot(entry.getKey()) || !(entry.getValue() instanceof String))
        throw new IOException("RECOVERY_CATALOG");
      else uuid((String) entry.getValue());
    return result;
  }

  /**
   * 当前目录不可读时允许列表只读回退到 previous；它可能缺少较新的引用，不能用于写入或整理。
   */
  private Map<String, Object> catalog() throws IOException {
    return catalog(true);
  }

  /** 写入必须掌握当前完整引用；备用目录不能证明哪些快照已无引用。 */
  private Map<String, Object> writableCatalog() throws IOException {
    return catalog(false);
  }

  private Map<String, Object> catalog(boolean allowPrevious) throws IOException {
    if (fs.stat(store).type.equals("MISSING")) return new LinkedHashMap<>();
    File current = fs.child(store, "catalog.json"),
        previous = fs.child(store, "catalog.json.previous");
    if (fs.stat(current).type.equals("MISSING")) {
      if (!allowPrevious && !fs.stat(previous).type.equals("MISSING"))
        throw new IOException("RECOVERY_CATALOG");
      return readCatalog(previous);
    }
    try {
      return readCatalog(current);
    } catch (IOException damaged) {
      if (!allowPrevious || cancelled(damaged) || fs.stat(previous).type.equals("MISSING"))
        throw damaged;
      try {
        return readCatalog(previous);
      } catch (IOException alsoDamaged) {
        damaged.addSuppressed(alsoDamaged);
        throw damaged;
      }
    }
  }

  private void catalog(Map<String, Object> values) throws IOException {
    // 不得用备用目录补写当前目录并丢失未知引用，也不能先删除用于只读恢复的 previous。
    writableCatalog();
    File previous = fs.child(store, "catalog.json.previous");
    var old = fs.stat(previous);
    if (old.type.equals("FILE")) {
      fs.delete(previous);
      fs.syncDirectory(store);
    } else if (!old.type.equals("MISSING")) throw new IOException("RECOVERY_LINK");
    fs.atomic(store, "catalog.json", BackupJson.write(values, 4096));
  }

  private String save(Map<String, Object> doc, String kind, BackupControl control)
      throws IOException {
    return save(doc, kind, control, Collections.emptySet());
  }

  /**
   * 槽位优先级：已损坏或悬空 → 空 → 最旧。任何一个槽位坏掉都不能让整次写入失败，
   * 被替换下来的坏快照随后由整理移入隔离区。
   */
  private String save(
      Map<String, Object> doc, String kind, BackupControl control, Collection<?> pinned)
      throws IOException {
    Map<String, Object> index = writableCatalog();
    String target = null;
    long oldest = Long.MAX_VALUE;
    for (int i = 1; i <= 3; i++) {
      String key = kind + "-" + i;
      long time;
      if (!index.containsKey(key)) time = -1;
      else
        try {
          time = BackupJson.number(load((String) index.get(key), control), "created");
        } catch (IOException damaged) {
          if (cancelled(damaged)) throw damaged;
          time = -2;
        }
      if (time < oldest) {
        target = key;
        oldest = time;
      }
    }
    if (target == null) throw new IOException("RECOVERY_CATALOG");
    index.put(target, doc.get("id"));
    catalog(index);
    tidyAfterCommit(index, pinned, control);
    return target;
  }

  private File legacy(String slot) throws IOException {
    if (!slot.matches("(?:healthy|before)-[1-3]|pending")) throw new IOException("RECOVERY_SLOT");
    File root = new File(dsh, "dsha-startup-checkpoints");
    return new File(root, slot + ".json");
  }

  /** 旧检查点路径必须位于私有根内，且每一级都不是链接。 */
  private void legacyPath(File file) throws IOException {
    if (!file.getAbsolutePath().startsWith(files.getAbsolutePath() + File.separator))
      throw new IOException("RECOVERY_LINK");
    File current = files;
    String relative = file.getAbsolutePath().substring(files.getAbsolutePath().length() + 1);
    for (String part : relative.split(java.util.regex.Pattern.quote(File.separator))) {
      current = new File(current, part);
      if (fs.stat(current).type.equals("LINK")) throw new IOException("RECOVERY_LINK");
    }
  }

  /** 旧检查点没有可信 id；删除前用 inode、大小与修改时间绑定用户看到的那一份。 */
  private static String fingerprint(BackupFileSystem.Node node) {
    return node.key + ":" + node.size + ":" + node.modified;
  }

  private Map<String, Object> legacy(
      File file, LegacyConfigurationSnapshot.Data data, BackupControl control) throws IOException {
    // 与历史检查点相同，含链接的配置/检查点路径保持原样并拒绝写入。
    legacyPath(file);
    var state = fs.stat(file);
    if (!state.type.equals("FILE") || state.size > LegacyConfigurationSnapshot.DOCUMENT_LIMIT)
      throw new IOException("RECOVERY_SIZE");
    try (InputStream input = fs.read(file, state)) {
      return LegacyConfigurationSnapshot.read(input, data, control);
    }
  }

  private Map<String, Object> metadata(String slot, Map<String, Object> doc) throws IOException {
    List<String> present = new ArrayList<>(), missing = new ArrayList<>();
    for (var entry : entries(doc).entrySet()) {
      if (Boolean.TRUE.equals(((Map<?, ?>) entry.getValue()).get("present")))
        present.add(entry.getKey());
      else missing.add(entry.getKey());
    }
    return Map.of(
        "slot",
        slot,
        "id",
        doc.get("id"),
        "created",
        doc.get("created"),
        "dshVersion",
        doc.getOrDefault("dshVersion", "unknown"),
        "files",
        present,
        "missing",
        missing,
        "healthy",
        slot.contains("healthy-"));
  }

  private Map<String, Object> invalidRow(String slot, Object id, IOException error) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("slot", slot);
    row.put("invalid", true);
    if (id != null) row.put("id", id);
    row.put("error", errorCode(error));
    return row;
  }

  /** 隔离区摘要：只看是否存在元数据，不读取正文；按修改时间从新到旧。 */
  private Map<String, Object> retainedSummary() throws IOException {
    File holding = new File(store, RETAINED);
    List<Map<String, Object>> items = new ArrayList<>();
    int count = 0;
    var node = fs.stat(holding);
    if (node.type.equals("DIRECTORY")) {
      List<String> names = fs.list(holding);
      count = names.size();
      List<Object[]> rows = new ArrayList<>();
      for (String name : names) {
        File entry = fs.child(holding, name);
        var state = fs.stat(entry);
        if (!state.type.equals("DIRECTORY")) continue; // 非目录条目不是本引擎移入的内容。
        boolean metadata = fs.stat(new File(entry, "metadata.json")).type.equals("FILE");
        rows.add(new Object[] {name, state.modified, metadata ? "damaged" : "incomplete"});
      }
      rows.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));
      for (Object[] row : rows.subList(0, Math.min(RETAINED_SHOWN, rows.size())))
        items.add(Map.of("id", row[0], "reason", row[2])); // 修改时间单位随实现不同，只用于排序。
    } else if (!node.type.equals("MISSING")) throw new IOException("RECOVERY_LINK");
    return Map.of("count", count, "items", items);
  }

  public Map<String, Object> list(BackupControl control) throws IOException {
    synchronized (LOCK) {
      List<Map<String, Object>> result = new ArrayList<>();
      Map<String, Object> index = catalog();
      for (String kind : List.of("healthy", "before"))
        for (int i = 1; i <= 3; i++) {
          String slot = kind + "-" + i;
          if (index.containsKey(slot))
            try {
              result.add(metadata(slot, load((String) index.get(slot), control)));
            } catch (IOException invalid) {
              if (cancelled(invalid)) throw invalid;
              result.add(invalidRow(slot, index.get(slot), invalid));
            }
          File legacy = legacy(slot);
          var legacyNode = fs.stat(legacy);
          if (!legacyNode.type.equals("MISSING")) {
            Map<String, Object> row;
            try {
              row = new LinkedHashMap<>(metadata("legacy-" + slot, legacy(legacy, null, control)));
            } catch (IOException invalid) {
              if (cancelled(invalid)) throw invalid;
              row = invalidRow("legacy-" + slot, null, invalid);
            }
            row.put("fingerprint", fingerprint(legacyNode));
            result.add(row);
          }
        }
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("status", "ok");
      out.put("snapshots", result);
      out.put("pending", pending());
      out.put("retained", retainedSummary());
      return out;
    }
  }

  public void prepare(String startup, BackupControl control) throws IOException {
    synchronized (LOCK) {
      if (pending()) throw new IOException("RECOVERY_PENDING");
      if (startup == null || startup.length() > 128) throw new IOException("RECOVERY_FORMAT");
      Map<String, Object> doc = capture(startup, Collections.emptySet(), control),
          index = writableCatalog();
      index.put("candidate", doc.get("id"));
      catalog(index);
      tidyAfterCommit(index, Collections.emptySet(), control);
    }
  }

  public Map<String, Object> healthy(String startup, BackupControl control) throws IOException {
    synchronized (LOCK) {
      if (pending()) throw new IOException("RECOVERY_PENDING");
      Map<String, Object> index = writableCatalog();
      Object id = index.get("candidate");
      if (!(id instanceof String)) throw new IOException("RECOVERY_CHANGED");
      Map<String, Object> doc = load((String) id, control);
      if (!Objects.equals(startup, doc.get("startupId"))
          || !entries(doc).equals(signatures(control))) throw new IOException("RECOVERY_CHANGED");
      String slot = save(doc, "healthy", control);
      return Map.of("status", "ok", "snapshot", metadata(slot, doc));
    }
  }

  public String before(BackupControl control) throws IOException {
    synchronized (LOCK) {
      return save(capture(null, Collections.emptySet(), control), "before", control);
    }
  }

  /**
   * 用户明确删除一份快照。普通槽位按 slot + id 绑定目录当前值，先改目录再两段删除正文；
   * 旧检查点按 inode/大小/修改时间绑定。启动候选与中断修复输入不能删除。
   * 返回 "RECOVERY_DISCARDED"，正文清理推迟时返回 "RECOVERY_DISCARD_DEFERRED"。
   */
  public String discard(String slot, String id, String fingerprint, BackupControl control)
      throws IOException {
    synchronized (LOCK) {
      if (slot == null) throw new IOException("RECOVERY_FORMAT");
      if (pending()) throw new IOException("RECOVERY_PENDING");
      if (slot.startsWith("legacy-")) {
        String original = slot.substring(7);
        if (original.equals("pending")) throw new IOException("RECOVERY_SLOT");
        File file = legacy(original);
        legacyPath(file);
        var node = fs.stat(file);
        if (!node.type.equals("FILE")) throw new IOException("RECOVERY_CHANGED");
        if (fingerprint == null || !fingerprint.equals(fingerprint(node)))
          throw new IOException("RECOVERY_CHANGED");
        fs.delete(file);
        fs.syncDirectory(file.getParentFile());
        return "RECOVERY_DISCARDED";
      }
      if (!slot(slot) || slot.equals("candidate")) throw new IOException("RECOVERY_SLOT");
      uuid(id);
      Map<String, Object> index = writableCatalog();
      if (!id.equals(index.get(slot))) throw new IOException("RECOVERY_CHANGED");
      index.remove(slot);
      catalog(index);
      if (index.containsValue(id)) return "RECOVERY_DISCARDED"; // 仍被候选等其他引用持有。
      try {
        retire(id);
        sweepRetiring();
      } catch (InterruptedIOException stop) {
        return "RECOVERY_DISCARD_DEFERRED";
      } catch (IOException failed) {
        warn("discard", id, failed);
        return "RECOVERY_DISCARD_DEFERRED";
      }
      return fs.stat(new File(store, id)).type.equals("MISSING")
          ? "RECOVERY_DISCARDED"
          : "RECOVERY_DISCARD_DEFERRED";
    }
  }

  /** 删除隔离区中的一项（id）或全部（"all"）。隔离区内容从不被恢复或引用。返回删除数量。 */
  public int discardRetained(String target, BackupControl control) throws IOException {
    synchronized (LOCK) {
      if (target == null || !target.equals("all") && !snapshotId(target))
        throw new IOException("RECOVERY_FORMAT");
      File holding = new File(store, RETAINED);
      var node = fs.stat(holding);
      if (node.type.equals("MISSING")) {
        if (target.equals("all")) return 0;
        throw new IOException("RECOVERY_CHANGED");
      }
      if (!node.type.equals("DIRECTORY")) throw new IOException("RECOVERY_LINK");
      sweepRetiring();
      List<String> names = fs.list(holding);
      if (!target.equals("all")) {
        if (!names.contains(target)) throw new IOException("RECOVERY_CHANGED");
        names = List.of(target);
      }
      File retiring = fs.child(store, RETIRING);
      directory(retiring);
      int removed = 0;
      for (String name : names) {
        control.check();
        if (!snapshotId(name)) continue; // 非本引擎命名的条目不动。
        File target0 = fs.child(retiring, name);
        if (!fs.stat(target0).type.equals("MISSING")) fs.removeOwned(retiring, name);
        fs.move(fs.child(holding, name), target0);
        removed++;
      }
      sweepRetiring();
      return removed;
    }
  }

  private HostDataTransaction transaction(File operation) throws IOException {
    return new HostDataTransaction(
        fs,
        operation,
        id -> {
          if (!id.matches("config-[0-5]")) throw new IOException("RECOVERY_FORMAT");
          return document(FILES.get(Integer.parseInt(id.substring(7))));
        },
        NATIVE_UNCHANGED,
        fault);
  }

  private File operation() throws IOException {
    ensureStore();
    archiveCompletedOperations();
    if (activeOperations().size() >= BackupLimits.CONFIGURATION_OPERATION_ACTIVE_RECORDS)
      throw new IOException("RECOVERY_RETENTION_LIMIT");
    File result = fs.child(operations, UUID.randomUUID().toString());
    fs.directory(result);
    fs.directory(new File(result, "candidate"));
    return result;
  }

  private void change(
      Map<String, byte[]> defaults,
      Map<String, Object> snapshot,
      boolean legacySource,
      String legacySlot,
      String expectedId,
      BackupControl control)
      throws IOException {
    if (!pendingNative().isEmpty()) throw new IOException("RECOVERY_PENDING");
    Map<String, String> original = new LinkedHashMap<>();
    for (String name : FILES) original.put(name, BackupTree.digest(fs, document(name), control));
    Collection<?> pinned =
        snapshot != null && !legacySource
            ? Collections.singleton(snapshot.get("id"))
            : Collections.emptySet();
    save(capture(null, pinned, control), "before", control, pinned);
    File operation = operation();
    Set<String> selected = new LinkedHashSet<>();
    String legacyHash = null;
    if (defaults != null) {
      for (var entry : defaults.entrySet()) {
        int ordinal = FILES.indexOf(entry.getKey());
        if (ordinal < 0) throw new IOException("RECOVERY_FORMAT");
        selected.add(entry.getKey());
        if (entry.getValue() != null)
          try (OutputStream out = fs.create(fs.child(operation, "candidate/config-" + ordinal))) {
            out.write(entry.getValue());
          }
      }
    } else if (legacySource) {
      File originalInput = legacy(legacySlot), fixed = fs.child(operation, "legacy-source.json");
      var beforeInput = fs.stat(originalInput);
      if (!beforeInput.type.equals("FILE")
          || beforeInput.size > LegacyConfigurationSnapshot.DOCUMENT_LIMIT)
        throw new IOException("RECOVERY_SIZE");
      var hash = BackupArchive.sha();
      long total = 0;
      try (InputStream in = fs.read(originalInput, beforeInput);
          OutputStream out = fs.create(fixed)) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) {
          control.check();
          total = BackupLimits.add(total, n, LegacyConfigurationSnapshot.DOCUMENT_LIMIT);
          out.write(buffer, 0, n);
          hash.update(buffer, 0, n);
        }
      }
      legacyHash = BackupArchive.hex(hash.digest());
      Map<String, Object> current =
          legacy(
              fixed,
              (name, bytes) -> {
                selected.add(name);
                if (bytes != null)
                  try (OutputStream out =
                      fs.create(fs.child(operation, "candidate/config-" + FILES.indexOf(name)))) {
                    out.write(bytes);
                  }
              },
              control);
      if (!expectedId.equals(current.get("id")) || !entries(current).equals(entries(snapshot)))
        throw new IOException("RECOVERY_CHANGED");
      snapshot = current;
    } else {
      String id = BackupJson.string(snapshot, "id");
      snapshot = load(id, control);
      selected.addAll(FILES);
      Map<String, Object> rows = entries(snapshot);
      for (int i = 0; i < FILES.size(); i++)
        if (Boolean.TRUE.equals(((Map<?, ?>) rows.get(FILES.get(i))).get("present")))
          BackupTree.copy(
              fs,
              fs.child(store, id + "/" + i),
              fs.child(operation, "candidate/config-" + i),
              control);
    }
    if (legacySlot != null && legacySlot.equals("pending")) {
      try (OutputStream out = fs.create(fs.child(operation, "legacy.json"))) {
        out.write(BackupJson.write(Map.of("sha256", legacyHash), 1024));
      }
    }
    Map<String, String> before = new LinkedHashMap<>();
    List<String> roots = new ArrayList<>();
    Set<String> removals = new HashSet<>();
    for (String name : selected) {
      int ordinal = FILES.indexOf(name);
      String id = "config-" + ordinal;
      File target = document(name);
      if (fs.stat(target).type.equals("LINK")) throw new IOException("RECOVERY_LINK");
      fs.parents(dsh, name);
      roots.add(id);
      before.put(id, original.get(name));
      if (fs.stat(fs.child(operation, "candidate/" + id)).type.equals("MISSING")) removals.add(id);
    }
    if (legacySource)
      try (InputStream input = fs.read(legacy(legacySlot), fs.stat(legacy(legacySlot)))) {
        if (!legacyHash.equals(BackupArchive.digest(input, control)))
          throw new IOException("RECOVERY_CHANGED");
      }
    HostDataTransaction transaction = transaction(operation);
    transaction.prepare(roots, before, Collections.emptyMap(), control, removals);
    transaction.commit(control);
    finishLegacy(operation, control);
    try {
      archiveCompletedOperations();
    } catch (IOException retained) {
      /* committed configuration remains authoritative; the journal stays addressable by either path */
    }
  }

  public void create(String target, BackupControl control) throws IOException {
    synchronized (LOCK) {
      createLocked(target, control);
    }
  }

  private void createLocked(String target, BackupControl control) throws IOException {
    if (pending()) throw new IOException("RECOVERY_PENDING");
    Map<String, byte[]> desired = new LinkedHashMap<>();
    if (target.equals("web")) {
      Map<String, Object> manifest =
          Map.of(
              "name",
              "dsh-profile-web",
              "private",
              true,
              "dependencies",
              Collections.emptyMap(),
              "dsh",
              Map.of(
                  "profile",
                  Map.of(
                      "bundles",
                      List.of("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app"),
                      "patchReload",
                      "startup")));
      desired.put(FILES.get(0), BackupJson.write(manifest, 16384));
      desired.put(FILES.get(1), "[]\n".getBytes(StandardCharsets.UTF_8));
    } else if (target.equals("settings.yaml") || target.equals("cordis.patch.yml"))
      desired.put(
          target,
          (target.equals("settings.yaml") ? "{}\n" : "[]\n").getBytes(StandardCharsets.UTF_8));
    else throw new IOException("RECOVERY_FORMAT");
    change(desired, null, false, null, null, control);
  }

  public void restore(String slot, String id, BackupControl control) throws IOException {
    synchronized (LOCK) {
      restoreLocked(slot, id, control);
    }
  }

  private void restoreLocked(String slot, String id, BackupControl control) throws IOException {
    if (slot == null || id == null) throw new IOException("RECOVERY_FORMAT");
    if (pending()) throw new IOException("RECOVERY_PENDING");
    if (slot.startsWith("legacy-")) {
      String original = slot.substring(7);
      Map<String, Object> snapshot = legacy(legacy(original), null, control);
      if (!id.equals(snapshot.get("id"))) throw new IOException("RECOVERY_CHANGED");
      change(null, snapshot, true, original, id, control);
    } else {
      if (!slot(slot) || slot.equals("candidate")) throw new IOException("RECOVERY_SLOT");
      Map<String, Object> index = writableCatalog();
      if (!id.equals(index.get(slot))) throw new IOException("RECOVERY_CHANGED");
      change(null, load(id, control), false, null, id, control);
    }
  }

  private boolean mark(File directory, String name) throws IOException {
    File file = fs.child(directory, name);
    if (fs.stat(file).type.equals("MISSING")) return false;
    if (!(directory.getName() + "\n" + name + "\n")
        .equals(new String(fs.small(file, 256), StandardCharsets.UTF_8)))
      throw new IOException("RECOVERY_FORMAT");
    return true;
  }

  public List<File> pendingNative() throws IOException {
    verifyCompleted();
    if (fs.stat(operations).type.equals("MISSING")) return Collections.emptyList();
    List<File> pending = new ArrayList<>();
    File history = fs.child(operations, HostOperationArchive.COMPLETED);
    for (String id : activeOperations()) {
      uuid(id);
      File directory = fs.child(operations, id);
      if (!fs.stat(directory).type.equals("DIRECTORY"))
        throw new IOException("RECOVERY_OPERATION_TYPE");
      if (fs.stat(history).type.equals("DIRECTORY")
          && !fs.stat(fs.child(history, id)).type.equals("MISSING"))
        throw new IOException("RECOVERY_OPERATION_DUPLICATE");
      if (mark(directory, "switching")
          && !mark(directory, "finalized")
          && !mark(directory, "rolled-back")) pending.add(directory);
    }
    if (pending.size() > 1) throw new IOException("RECOVERY_MULTIPLE_TRANSACTIONS");
    return pending;
  }

  public boolean pending() throws IOException {
    return !pendingNative().isEmpty()
        || !fs.stat(legacy("pending")).type.equals("MISSING")
        || !fs.stat(new File(files, "startup-config-repair.pending")).type.equals("MISSING");
  }

  private void finishLegacy(File operation, BackupControl control) throws IOException {
    File receipt = fs.child(operation, "legacy.json");
    if (fs.stat(receipt).type.equals("MISSING") || !mark(operation, "finalized")) return;
    File old = legacy("pending");
    if (fs.stat(old).type.equals("MISSING")) return;
    String wanted = BackupJson.string(BackupJson.read(fs.small(receipt, 1024), 1024), "sha256"),
        actual;
    try (InputStream input = fs.read(old, fs.stat(old))) {
      actual = BackupArchive.digest(input, control);
    }
    if (!wanted.matches("[a-f0-9]{64}")) throw new IOException("RECOVERY_FORMAT");
    if (wanted.equals(actual)) fs.move(old, fs.child(operation, "legacy-pending-original.json"));
  }

  public String recover(BackupControl control) throws IOException {
    synchronized (LOCK) {
      return recoverLocked(control);
    }
  }

  private String recoverLocked(BackupControl control) throws IOException {
    List<File> interrupted = pendingNative();
    boolean repaired = !interrupted.isEmpty();
    for (File pending : interrupted) transaction(pending).recover();
    if (!fs.stat(operations).type.equals("MISSING"))
      for (String id : activeOperations())
        if (id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
          finishLegacy(fs.child(operations, id), control);
    File old = legacy("pending");
    if (!fs.stat(old).type.equals("MISSING")) {
      Map<String, Object> snapshot = legacy(old, null, control);
      change(null, snapshot, true, "pending", BackupJson.string(snapshot, "id"), control);
      repaired = true;
    }
    File marker = new File(files, "startup-config-repair.pending");
    if (!fs.stat(marker).type.equals("MISSING")) {
      // 没有旧日志时只解除旧入口标记，不猜测哪份历史数据应覆盖当前文件。
      if (!fs.stat(marker).type.equals("FILE")) throw new IOException("RECOVERY_FORMAT");
      ensureStore();
      File retained = fs.child(store, MARKERS);
      directory(retained);
      fs.move(marker, fs.child(retained, UUID.randomUUID() + ".bin"));
      pruneMarkers(retained);
      return repaired ? "RECOVERY_ROLLED_BACK" : "RECOVERY_MARKER_RETAINED";
    }
    archiveCompletedOperations();
    return repaired ? "RECOVERY_ROLLED_BACK" : "RECOVERY_NO_DATA_CHANGED";
  }

  /** 旧状态标记只作诊断证据：保留最近 MARKER_KEEP 份，更早的直接删除，失败只记告警。 */
  private void pruneMarkers(File retained) {
    try {
      List<Object[]> rows = new ArrayList<>();
      for (String name : fs.list(retained)) {
        var node = fs.stat(fs.child(retained, name));
        if (node.type.equals("FILE") && name.matches("[a-f0-9-]{36}\\.bin"))
          rows.add(new Object[] {name, node.modified});
      }
      rows.sort(
          (a, b) -> {
            int order = Long.compare((long) b[1], (long) a[1]);
            return order != 0 ? order : ((String) b[0]).compareTo((String) a[0]);
          });
      for (Object[] row : rows.subList(Math.min(MARKER_KEEP, rows.size()), rows.size()))
        fs.delete(fs.child(retained, (String) row[0]));
      if (rows.size() > MARKER_KEEP) fs.syncDirectory(retained);
    } catch (IOException failed) {
      warn("markers", "-", failed);
    }
  }

  public static boolean blocked(File files) {
    try {
      File root = files.getCanonicalFile();
      var fs = new AndroidBackupFileSystem();
      return new ConfigurationSnapshots(fs, root, new UserDataLayout(fs, root).current()).pending();
    } catch (IOException error) {
      return true;
    }
  }
}
