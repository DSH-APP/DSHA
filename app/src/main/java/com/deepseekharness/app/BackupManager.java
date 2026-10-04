package com.deepseekharness.app;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.data.DownloadsExport;
import com.deepseekharness.app.util.BackupScope;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Android 协调层：URI 输入输出与配置；归档、范围和事务由容器内核心完成。 */
public final class BackupManager {
  private BackupManager() {}

  private static final Object LOCK =
      com.deepseekharness.app.core.MaintenanceCoordinator.archiveLock();
  private static final long MAX_ARCHIVE = 16L * 1024 * 1024 * 1024;
  private static volatile String error = "";
  public static final String LATEST_BACKUP_NAME = "DSHA-backup-latest.tar.gz";

  public static String lastError() {
    return SensitiveData.redact(error);
  }

  public static boolean isRestoring() {
    return com.deepseekharness.app.core.MaintenanceCoordinator.isExclusive();
  }

  /** 安全入口供页面任务使用：停止队列排空后才拿归档锁，避免运行任务互等。 */
  public interface DataOperation<T> {
    T run() throws Exception;
  }

  public static boolean isDataTaskOwner() {
    return com.deepseekharness.app.core.MaintenanceCoordinator.isOwner();
  }

  /** 快照/预检只取得数据锁，保留正在运行的 Web；引擎检测到数据变化时返回失败。 */
  public static <T> T runSnapshotTask(HarnessController controller, DataOperation<T> operation)
      throws Exception {
    return com.deepseekharness.app.core.MaintenanceCoordinator.snapshot(controller, operation::run);
  }

  public static <T> T runDataTask(HarnessController controller, DataOperation<T> operation)
      throws Exception {
    return com.deepseekharness.app.core.MaintenanceCoordinator.exclusive(
        controller, operation::run);
  }

  /** 主线程与安装入口的只读门控；调用方仍需原子取得 EnvironmentTaskGate.Lease 才能开始工作。 */
  public static boolean isEnvironmentTaskBusy() {
    return com.deepseekharness.app.core.MaintenanceCoordinator.isEnvironmentTaskBusy();
  }

  /** 与普通停止共用 PID 身份与同 UID 进程核验，未知读取错误仍阻止维护。 */
  public static void stopWebForMaintenance(HarnessController controller) throws Exception {
    com.deepseekharness.app.core.MaintenanceCoordinator.stopWeb(controller);
  }

  /** 新增的启动查询供安装/运行 worker 接入；未完成的磁盘事务必须先回滚。 */
  public static boolean hasPendingMaintenance(HarnessController controller) {
    return com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller);
  }

  public static boolean hasPendingMaintenance(File filesDir) {
    return com.deepseekharness.app.core.MaintenanceCoordinator.pending(filesDir);
  }

  public static void recoverMaintenanceBeforeStart(HarnessController controller) throws Exception {
    if (!com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller)) return;
    com.deepseekharness.app.core.MaintenanceCoordinator.exclusive(
        controller,
        () -> {
          com.deepseekharness.app.core.EnvironmentMaintenance.recover(controller);
          return null;
        });
  }

  /** 安全归档永远位于 linux 之外，不导出公共存储，也不覆盖已有归档。 */
  public static String createMaintenanceBackup(HarnessController controller, File destination)
      throws Exception {
    if (!isDataTaskOwner()) throw new IOException("BACKUP_REQUIRES_MAINTENANCE");
    var fs = files();
    File privateFiles = controller.context().getFilesDir().getCanonicalFile();
    if (!com.deepseekharness.app.util.DocumentPaths.within(
            privateFiles, destination.getAbsoluteFile())
        || com.deepseekharness.app.util.DocumentPaths.within(
            controller.proot().getRootfsDir(), destination.getAbsoluteFile()))
      throw new IOException("BACKUP_PRIVATE_DESTINATION");
    var selection = new com.deepseekharness.app.backup.NativeDataLocations.Selection();
    selection.includeApiKey = controller.config().isBackupKey();
    var control = com.deepseekharness.app.core.BackupTask.currentControl(null);
    var located =
        new com.deepseekharness.app.backup.NativeDataLocations(controller.context())
            .locate(selection, control);
    File home = com.deepseekharness.app.backup.HostOperationArchive.reserve(fs, privateFiles);
    File operation = fs.child(home, UUID.randomUUID().toString());
    fs.directory(operation);
    File archive = fs.child(operation, "maintenance.dshdata");
    java.util.Map<String, Object> provenance = new java.util.LinkedHashMap<>();
    provenance.put("requestedScope", "application");
    provenance.put("createdAt", System.currentTimeMillis());
    provenance.put("appVersion", BuildConfig.VERSION_NAME);
    provenance.put("appCode", (long) BuildConfig.VERSION_CODE);
    provenance.put(
        "plugins", located.plugins == null ? java.util.Map.of() : located.plugins.description());
    var summary =
        com.deepseekharness.app.backup.HostSnapshot.create(
            fs,
            located.sources,
            operation,
            archive,
            provenance,
            false,
            control,
            located.plugins == null ? null : located.plugins::verify);
    fs.atomic(
        operation,
        "maintenance-snapshot.json",
        com.deepseekharness.app.backup.BackupJson.write(
            summary, com.deepseekharness.app.backup.BackupLimits.MANIFEST));
    FileIntegrity.Result expected;
    try (InputStream input = fs.read(archive, fs.stat(archive))) {
      expected = FileIntegrity.copy(input, null, MAX_ARCHIVE);
    }
    com.deepseekharness.app.backup.VerifiedFilePublication.publish(
        fs, archive, destination, expected);
    return expected.sha256;
  }

  /** 独立任务内恢复，共用原有引擎的预检、逐文件校验与延迟提交协议。 */
  public static String restoreWithinDataTask(HarnessController controller, PreparedRestore prepared)
      throws Exception {
    if (!isDataTaskOwner())
      throw new IOException(com.deepseekharness.app.util.UiText.text("恢复必须持有全局任务锁"));
    try (InputStream in = files().read(prepared.archive, files().stat(prepared.archive))) {
      if (!prepared.hash.equals(FileIntegrity.copy(in, null, MAX_ARCHIVE).sha256))
        throw new IOException(com.deepseekharness.app.util.UiText.text("待恢复文件已变化"));
    }
    try {
      controller.config().beginRestoreSettings();
      JSONObject result =
          run(
              controller,
              "restore --archive "
                  + ShellQuote.arg("/root/" + prepared.archive.getName())
                  + " --scope "
                  + BackupScope.id(prepared.scope)
                  + " --defer-commit");
      if (!result.optBoolean("committed"))
        throw new IOException(com.deepseekharness.app.util.UiText.text("恢复尚未提交"));
      JSONObject settings = result.optJSONObject("nativeConfig");
      if (settings != null) controller.config().importBackupSettings(settings);
      // Native configuration is the only credential input in this legacy
      // path. Historical key files use the explicit native restore option;
      // never read an unselected old active .dsha-apikey as restored data.
      run(controller, "finalize");
      controller.config().finishRestoreSettings(false);
      com.deepseekharness.app.HttpShellService.resetTokenAfterRestore(controller.context());
      return restoreMessage(
          controller.config(),
          BackupScope.label(prepared.scope),
          com.deepseekharness.app.util.UiText.text("；原数据已保留，完成后可手动启动 Web。"));
    } catch (Exception e) {
      try {
        recoverInterrupted(controller);
      } catch (Exception rollback) {
        e.addSuppressed(rollback);
      }
      throw e;
    }
  }

  /** 从私有安全归档恢复到新容器；只拷贝本次输入，绝不把安全归档交给 close 删除。 */
  public static void restoreMaintenanceBackup(HarnessController controller, File archive)
      throws Exception {
    if (!isDataTaskOwner())
      throw new IOException(com.deepseekharness.app.util.UiText.text("维护恢复必须持有全局任务锁"));
    try (InputStream source = files().read(archive, files().stat(archive))) {
      byte[] header = new byte[8];
      int length = source.read(header);
      if (length == header.length
          && com.deepseekharness.app.backup.BackupArchive.hasMagic(header)) {
        restoreHostSnapshot(controller, archive);
        return;
      }
    }
    File input =
        new File(
            controller.proot().getRootfsDir(),
            "root/.dsha-maintenance-input-" + UUID.randomUUID() + ".tar.gz");
    try {
      String hash;
      try (InputStream in = files().read(archive, files().stat(archive));
          java.io.OutputStream out = files().create(input)) {
        hash = FileIntegrity.copy(in, out, MAX_ARCHIVE).sha256;
      }
      files().syncDirectory(input.getParentFile());
      // 原生偏好及 Keystore 保持原位；只恢复容器数据，避免维护引入跨层设置事务。
      JSONObject result =
          run(
              controller,
              "restore --scope full --archive " + ShellQuote.arg("/root/" + input.getName()));
      if (!result.optBoolean("committed"))
        throw new IOException(com.deepseekharness.app.util.UiText.text("环境数据未完整恢复"));
      try (InputStream in = files().read(input, files().stat(input))) {
        if (!hash.equals(FileIntegrity.copy(in, null, MAX_ARCHIVE).sha256))
          throw new IOException(com.deepseekharness.app.util.UiText.text("安全归档发生变化"));
      }
    } finally {
      if (!files().stat(input).type.equals("MISSING")) files().delete(input);
    }
  }

  private static void restoreHostSnapshot(HarnessController controller, File archive)
      throws Exception {
    var fs = files();
    File privateFiles = controller.context().getFilesDir().getCanonicalFile();
    File home = com.deepseekharness.app.backup.HostOperationArchive.reserve(fs, privateFiles);
    File operation = fs.child(home, UUID.randomUUID().toString());
    fs.directory(operation);
    var control = com.deepseekharness.app.core.BackupTask.currentControl(null);
    var mapping =
        new com.deepseekharness.app.backup.NativeRestoreTargets(controller.context(), operation);
    var plan =
        com.deepseekharness.app.backup.NativeRestorePlan.inspect(
            fs, operation, archive, mapping, java.util.Set.of("application"), control);
    var roots = plan.buildCandidates(true, control);
    File candidate = fs.child(operation, "candidate");
    mapping.prepareCredentialProjection(candidate, control);
    mapping.prepareLayout(roots, plan.before, candidate);
    mapping.prepareParents(roots);
    if (roots.contains("plugin-store") && plan.manifest.get("plugins") instanceof java.util.Map) {
      @SuppressWarnings("unchecked")
      java.util.Map<String, Object> graph =
          (java.util.Map<String, Object>) plan.manifest.get("plugins");
      new com.deepseekharness.app.backup.PluginRestoreGraph(
              fs,
              fs.child(candidate, "plugin-store"),
              new com.deepseekharness.app.backup.CurrentManagedPackages(controller.context())
                  ::verified,
              controller.proot().getRootfsDir())
          .rebuild(graph, control);
      mapping.prepareSystemPluginState(graph, roots, plan.before, candidate, control);
    }
    var proposed = mapping.proposedSettings(candidate, false);
    var transaction =
        new com.deepseekharness.app.backup.HostDataTransaction(
            fs, operation, mapping, mapping, null);
    transaction.prepare(roots, plan.before, proposed, control);
    transaction.commit(control);
    com.deepseekharness.app.HttpShellService.resetTokenAfterRestore(controller.context());
  }

  public static String backupToExternal(Context ctx, HarnessController controller) {
    return backupToExternal(ctx, controller, BackupScope.FULL);
  }

  public static String backupToExternal(Context ctx, HarnessController controller, int scope) {
    error = "";
    String operation = "";
    var jobs = com.deepseekharness.app.backup.NativeBackupJobs.get(ctx);
    try {
      if (isDataTaskOwner()) throw new IOException("HOST_BACKUP_OWNER_ALREADY_HELD");
      if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        throw new IOException("HOST_BACKUP_REQUIRES_WORKER");
      var selection = new com.deepseekharness.app.backup.NativeDataLocations.Selection();
      selection.scope =
          switch (scope) {
            case BackupScope.FULL -> "application";
            case BackupScope.SESSIONS -> "sessions";
            case BackupScope.SETTINGS -> "settings";
            case BackupScope.PLUGINS -> "plugins";
            default -> throw new IOException("UNKNOWN_SCOPE");
          };
      selection.includeApiKey = controller.config().isBackupKey();
      synchronized (jobs) {
        if (!jobs.exportToDownloads(selection, false)) throw new IOException("HOST_BACKUP_BUSY");
        operation = jobs.state().id;
      }
      for (; ; ) {
        var state = jobs.state();
        if (!operation.equals(state.id)) throw new IOException("HOST_BACKUP_OWNER_CHANGED");
        if (!state.busy) {
          if (!state.stage.equals("FINISHED")) throw new IOException(state.error);
          break;
        }
        Thread.sleep(50);
      }
      var fs = files();
      File owned =
          com.deepseekharness.app.backup.HostOperationArchive.locate(
              fs,
              com.deepseekharness.app.backup.HostOperationArchive.root(
                  ctx.getFilesDir().getCanonicalFile()),
              operation);
      var published =
          com.deepseekharness.app.backup.BackupJson.read(
              fs.small(fs.child(owned, "exported.json"), 16384), 16384);
      if (com.deepseekharness.app.backup.BackupJson.number(published, "version") != 1
          || !operation.equals(
              com.deepseekharness.app.backup.BackupJson.string(published, "operation")))
        throw new IOException("EXPORT_RECEIPT_CHANGED");
      return com.deepseekharness.app.backup.BackupJson.string(published, "uri")
          + "\nSHA-256: "
          + com.deepseekharness.app.backup.BackupJson.string(published, "sha256");
    } catch (InterruptedException cancelled) {
      synchronized (jobs) {
        if (!operation.isEmpty() && operation.equals(jobs.state().id) && jobs.state().busy)
          jobs.cancel();
      }
      Thread.currentThread().interrupt();
      error = "CANCELLED";
      return null;
    } catch (Exception failure) {
      error = safeError(failure);
      return null;
    }
  }

  private static com.deepseekharness.app.backup.BackupFileSystem files() {
    return new com.deepseekharness.app.backup.AndroidBackupFileSystem();
  }

  private static void writeContainer(HarnessController controller, File target, byte[] bytes)
      throws IOException {
    File root = controller.proot().getRootfsDir().getAbsoluteFile();
    String path = target.getAbsolutePath();
    if (!path.startsWith(root.getAbsolutePath() + File.separator))
      throw new IOException("BACKUP_RUNTIME_PATH");
    var fs = files();
    File checked =
        fs.child(
            root,
            path.substring(root.getAbsolutePath().length() + 1).replace(File.separatorChar, '/'));
    fs.atomic(checked.getParentFile(), checked.getName(), bytes);
  }

  private static JSONObject run(HarnessController controller, String arguments) throws Exception {
    // 升级门禁不能挡住旧环境的迁移备份；只有持有停止屏障的数据任务能使用旧版本。
    if (!controller.isEnvironmentReady() && !isDataTaskOwner())
      throw new IOException(com.deepseekharness.app.util.UiText.text("环境未就绪，请先完成安装"));
    boolean rescue = isDataTaskOwner();
    if (isDataTaskOwner()) controller.proot().prepareDataMaintenance();
    if (!rescue && !controller.proot().ensureBundledPython())
      throw new IOException(com.deepseekharness.app.util.UiText.text("内置 Python 无法使用，请从诊断页修复工具"));
    File script = new File(controller.proot().getRootfsDir(), "root/.dsha-backup-engine.py");
    String asset = controller.readAsset("backup-engine.py");
    if (asset.isEmpty())
      throw new IOException(com.deepseekharness.app.util.UiText.text("缺少备份核心脚本"));
    writeContainer(controller, script, asset.getBytes(StandardCharsets.UTF_8));
    for (String helper :
        new String[] {
          "backup-plugin-graph.py", "register-builtin-plugins.py", "credential-yaml-filter.cjs"
        }) {
      String body = controller.readAsset(helper);
      if (body.isEmpty())
        throw new IOException(com.deepseekharness.app.util.UiText.format("缺少备份支持脚本：%s", helper));
      writeContainer(
          controller,
          new File(controller.proot().getRootfsDir(), "root/.dsha-" + helper),
          body.getBytes(StandardCharsets.UTF_8));
    }
    writeContainer(
        controller,
        new File(controller.proot().getRootfsDir(), "root/builtin-plugins.json"),
        controller.readAsset("builtin-plugins.json").getBytes(StandardCharsets.UTF_8));
    for (String identity : new String[] {"credential-paths.json", "adb-wheels.lock.json"}) {
      String body = controller.readAsset(identity);
      if (body.isEmpty()) throw new IOException("BACKUP_IDENTITY_MISSING:" + identity);
      writeContainer(
          controller,
          new File(controller.proot().getRootfsDir(), "root/.dsha-" + identity),
          body.getBytes(StandardCharsets.UTF_8));
    }
    String command =
        "/usr/bin/python3 -B /root/.dsha-backup-engine.py "
            + arguments
            + " --workdir "
            + ShellQuote.arg(controller.config().getWorkdir())
            + " 2>&1";
    String out;
    if (rescue) {
      com.deepseekharness.app.util.BoundedProcessRunner.Result execution =
          controller.proot().runRecoveryMaintenance(command, null, 600_000);
      out =
          com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
              execution, "BACKUP_ENGINE");
    } else
      out =
          com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
              controller.proot().execAndReadWithProotResult(command, 600_000), "BACKUP_ENGINE");
    String marker = "DSHA_BACKUP_RESULT=";
    int start = out == null ? -1 : out.lastIndexOf(marker);
    if (start < 0)
      throw new IOException(
          out == null ? com.deepseekharness.app.util.UiText.text("备份核心没有返回结果") : out);
    return new JSONObject(out.substring(start + marker.length()).trim());
  }

  /** 在下次启动 dsh 之前回滚上次异常退出的恢复事务。 */
  public static void recoverInterrupted(HarnessController controller) throws Exception {
    synchronized (LOCK) {
      File journal = new File(controller.proot().getRootfsDir(), "root/.dsha-restore-journal.json");
      boolean interrupted =
          journal.isFile()
              && !new JSONObject(new String(Compat.readAllBytes(journal), StandardCharsets.UTF_8))
                  .optBoolean("complete");
      if (journal.isFile()) run(controller, "recover");
      controller.config().finishRestoreSettings(interrupted);
    }
  }

  private static String warnings(JSONObject result) {
    org.json.JSONArray values = result.optJSONArray("warnings");
    if (values == null || values.length() == 0) return "";
    StringBuilder text = new StringBuilder(com.deepseekharness.app.util.UiText.text("\n注意："));
    for (int i = 0; i < Math.min(10, values.length()); i++)
      text.append('\n').append(values.optString(i));
    if (values.length() > 10)
      text.append(com.deepseekharness.app.util.UiText.text("\n其余缺失项见备份清单。"));
    return text.toString();
  }

  public static final class PreparedRestore implements AutoCloseable {
    final File archive;
    final String hash;
    public final int scope;
    public final String summary;

    PreparedRestore(File archive, String hash, int scope, String summary) {
      this.archive = archive;
      this.hash = hash;
      this.scope = scope;
      this.summary = summary;
    }

    @Override
    public void close() throws IOException {
      if (!files().stat(archive).type.equals("MISSING")) files().delete(archive);
    }
  }

  /** 先保存独立副本并完整预检，再给用户展示真实内容和影响范围。 */
  public static PreparedRestore prepareRestore(Context ctx, HarnessController controller, Uri uri)
      throws Exception {
    synchronized (LOCK) {
      String name = "";
      try (android.database.Cursor cursor =
          com.deepseekharness.app.backup.DocumentStreams.query(
              ctx.getContentResolver(),
              uri,
              new String[] {OpenableColumns.DISPLAY_NAME},
              com.deepseekharness.app.core.BackupTask.currentControl(null))) {
        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
      } catch (Exception ignored) {
        name = uri.getLastPathSegment();
      }
      if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
      File target =
          new File(
              controller.proot().getRootfsDir(),
              "root/.dsha-restore-input-" + UUID.randomUUID() + ".tar.gz");
      boolean ready = false;
      try (com.deepseekharness.app.core.RuntimeTasks work =
          com.deepseekharness.app.core.RuntimeTasks.begin("数据维护")) {
        FileIntegrity.Result copied;
        try (InputStream in =
                com.deepseekharness.app.backup.DocumentStreams.input(
                    ctx.getContentResolver(),
                    uri,
                    com.deepseekharness.app.core.BackupTask.currentControl(null));
            java.io.OutputStream out = files().create(target)) {
          copied =
              FileIntegrity.copy(
                  in,
                  out,
                  Math.min(
                      MAX_ARCHIVE,
                      Math.max(0, target.getParentFile().getUsableSpace() - 32L * 1024 * 1024)));
        }
        files().syncDirectory(target.getParentFile());
        if (copied.size == 0)
          throw new IOException(com.deepseekharness.app.util.UiText.text("所选备份为空"));
        int guessed = BackupScope.fromFileName(name);
        JSONObject result =
            run(
                controller,
                "inspect --archive "
                    + ShellQuote.arg("/root/" + target.getName())
                    + " --scope "
                    + BackupScope.id(guessed));
        int scope = BackupScope.fromId(result.getString("scope"));
        JSONObject manifest = result.optJSONObject("manifest");
        String summary =
            com.deepseekharness.app.util.UiText.format(
                "%s\n%s\n文件数：%d\n解压内容：%s\n来自版本：%s%s%s\n\n恢复会先停止 Web。当前数据会保留为 .pre-restore-*，恢复失败自动回滚；完成后可手动启动 Web。",
                BackupScope.label(scope),
                BackupScope.restoreImpact(scope),
                result.getInt("files"),
                HarnessController.fmtBytes(result.getLong("bytes")),
                manifest == null
                    ? com.deepseekharness.app.util.UiText.text("未知")
                    : manifest.optString(
                        "appVersion", com.deepseekharness.app.util.UiText.text("未知")),
                result.optBoolean("legacy")
                    ? com.deepseekharness.app.util.UiText.text("\n旧格式：已验证归档完整性，但包内没有逐文件摘要。")
                    : com.deepseekharness.app.util.UiText.text("\n归档与逐文件 SHA-256 校验通过。"),
                warnings(result));
        ready = true;
        return new PreparedRestore(target, copied.sha256, scope, summary);
      } finally {
        if (!ready && !files().stat(target).type.equals("MISSING")) files().delete(target);
      }
    }
  }

  public static String restorePrepared(HarnessController controller, PreparedRestore prepared)
      throws Exception {
    try {
      return com.deepseekharness.app.core.MaintenanceCoordinator.exclusive(
          controller,
          () -> {
            synchronized (LOCK) {
              try {
                try (InputStream in =
                    files().read(prepared.archive, files().stat(prepared.archive))) {
                  if (!prepared.hash.equals(FileIntegrity.copy(in, null, MAX_ARCHIVE).sha256))
                    throw new IOException(
                        com.deepseekharness.app.util.UiText.text("待恢复文件发生变化，请重新选择"));
                }
                if (controller.isWebRunning())
                  throw new IOException(
                      com.deepseekharness.app.util.UiText.text("Web 尚未停止，现有数据未覆盖，请稍后重试"));
                controller.config().beginRestoreSettings();
                JSONObject result =
                    run(
                        controller,
                        "restore --archive "
                            + ShellQuote.arg("/root/" + prepared.archive.getName())
                            + " --scope "
                            + BackupScope.id(prepared.scope)
                            + " --defer-commit");
                if (!result.optBoolean("committed"))
                  throw new IOException(com.deepseekharness.app.util.UiText.text("恢复尚未提交"));
                // 恢复出来的 .dsh 可能带着【备份那台机器】的本机凭据：
                //   · .bridge_token —— 3090 桥的共享凭据（新版备份已排除，但老备份仍带）
                //   · browser-session —— 登录 cookie 的签名密钥（新版按字段剔除）
                // 与本机内存里的值不一致时桥会拒绝所有请求，用户看到「需要 token，
                // 请在 DSHA 应用内打开」。这里在提交之后立即让本机凭据重新对齐。
                com.deepseekharness.app.HttpShellService.resetTokenAfterRestore(
                    controller.context());
                JSONObject nativeConfig = result.optJSONObject("nativeConfig");
                if (nativeConfig != null) controller.config().importBackupSettings(nativeConfig);
                run(controller, "finalize");
                controller.config().finishRestoreSettings(false);
                return restoreMessage(
                    controller.config(),
                    com.deepseekharness.app.util.UiText.format(
                        "%s\n文件数：%d", BackupScope.label(prepared.scope), result.getInt("files")),
                    com.deepseekharness.app.util.UiText.text("\n原数据已保留，重新启动 Web 后可查看恢复内容。"));
              } catch (Exception failure) {
                try {
                  recoverInterrupted(controller);
                } catch (Exception rollback) {
                  failure.addSuppressed(rollback);
                }
                throw failure;
              }
            }
          });
    } finally {
      prepared.close();
    }
  }

  public static String restoreFromBackup(Context ctx, HarnessController controller, Uri uri)
      throws Exception {
    try (PreparedRestore prepared = prepareRestore(ctx, controller, uri)) {
      return restorePrepared(controller, prepared);
    }
  }

  public static String restoreFromBackup(Context ctx, HarnessController controller, File file)
      throws Exception {
    return restoreFromBackup(ctx, controller, Uri.fromFile(file));
  }

  private static boolean hasReadableApiKey(com.deepseekharness.app.core.ConfigStore config) {
    try {
      return config.readApiKey().state
          == com.deepseekharness.app.util.CredentialRead.State.AVAILABLE;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static String restoreMessage(
      com.deepseekharness.app.core.ConfigStore config, String scope, String suffix) {
    String key =
        hasReadableApiKey(config)
            ? com.deepseekharness.app.util.UiText.choose(
                "\n本机有可用的 API Key。", "\nAn API key is available on this device.")
            : com.deepseekharness.app.util.UiText.choose(
                "\n本机尚无可用 API Key，请在配置页填写或明确选择恢复历史凭据。",
                "\nNo API key is currently available. Enter one in Configuration or explicitly choose historical credential restore.");
    return com.deepseekharness.app.util.UiText.format("恢复完成：%s%s%s", scope, key, suffix);
  }

  public static String exportToDownloads(Context ctx, File source, String name) {
    try (com.deepseekharness.app.core.RuntimeTasks work =
        com.deepseekharness.app.core.RuntimeTasks.begin("数据维护")) {
      return DownloadsExport.write(ctx, source, name).uri.toString();
    } catch (Exception e) {
      error = safeError(e);
      return null;
    }
  }

  public static String safeError(Exception e) {
    String message =
        SensitiveData.redact(e.getMessage() == null ? e.toString() : e.getMessage()).trim();
    // Android/ROM 的 SecurityException 通常只有这句无上下文正文；收敛为
    // 稳定错误码后，维护页才能给出可执行入口，也不会把权限失败误显示成完成。
    if (message.equalsIgnoreCase("Permission denied")) message = "PERMISSION_DENIED";
    return message.length() < 800 ? message : message.substring(message.length() - 800);
  }
}
