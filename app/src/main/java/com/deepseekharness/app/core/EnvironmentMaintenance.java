package com.deepseekharness.app.core;

import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.backup.MaintenanceSpaceTrace;
import com.deepseekharness.app.backup.MaintenanceSpaceTrace.Stage;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.Fmt;
import com.deepseekharness.app.util.MaintenanceTransaction;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/** 维护顺序唯一入口：调用方已停止 Web 并持有 BackupManager 的数据任务锁。 */
public final class EnvironmentMaintenance {
  private EnvironmentMaintenance() {}

  /** 包内故障注入缝；正式入口始终使用真实 APK 解压，debug fixture 可在此抛出失败。 */
  interface ExtractStep {
    void extract(java.util.function.BiConsumer<Long, Long> progress) throws Exception;
  }

  public static String rebuild(HarnessController controller, Consumer<String> progress)
      throws Exception {
    if (!MaintenanceCoordinator.isOwner())
      throw new IOException("ENVIRONMENT_REQUIRES_MAINTENANCE");
    if (com.deepseekharness.app.backup.ColdInstallTransaction.eligibleFresh(
        new com.deepseekharness.app.backup.AndroidBackupFileSystem(),
        controller.context().getFilesDir().getCanonicalFile()))
      return initializeFresh(controller.context(), controller.proot(), progress);
    return rebuild(controller, progress, controller.proot()::extractOfflineBundle);
  }

  /** 真正的首次空环境不创建无数据可保护的重建快照；冷事务和完整运行核验仍执行。 */
  public static String initializeFresh(
      android.content.Context context,
      com.deepseekharness.app.runtime.ProotBootstrap boot,
      Consumer<String> progress)
      throws Exception {
    if (!MaintenanceCoordinator.isOwner())
      throw new IOException("ENVIRONMENT_REQUIRES_MAINTENANCE");
    try (var invocation = boot.hostPorts().open()) {
      var control = maintenanceControl(progress);
      boot.extractOfflineBundle(
          new com.deepseekharness.app.runtime.ProotBootstrap.ExtractionProgress() {
            @Override
            public void onStage(String stage) {
              progress.accept(stage);
            }

            @Override
            public void accept(Long done, Long total) {}
          });
      progress.accept(
          com.deepseekharness.app.util.UiText.choose("检查启动配置…", "Checking startup configuration…"));
      var proof =
          com.deepseekharness.app.runtime.RuntimeTrial.verify(
              context,
              boot,
              control,
              () -> validateRuntime(boot, false),
              boot.hostPorts().browserProbe());
      boot.hostPorts().confirmColdRuntime(boot.getRootfsDir(), proof);
      boot.confirmRuntimeHealth(proof);
      if (!boot.isEnvironmentReady()) throw new IOException("RUNTIME_READINESS_FAILED");
      return com.deepseekharness.app.util.UiText.text("解压与离线安装完成");
    }
  }

  public static String rollbackRuntime(
      HarnessController controller, String operation, Consumer<String> progress) throws Exception {
    if (!MaintenanceCoordinator.isOwner()) throw new IOException("RUNTIME_REQUIRES_MAINTENANCE");
    File files = controller.context().getFilesDir().getCanonicalFile();
    if (MaintenanceCoordinator.pending(files)) throw new IOException("RUNTIME_RECOVERY_REQUIRED");
    var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
    var control = maintenanceControl(progress);
    var transaction =
        com.deepseekharness.app.backup.ManagedRuntimeTransaction.prepareRollback(
            fs,
            files,
            operation,
            controller.proot().expectedRuntimeDescriptor(),
            controller.proot().installedRuntimeDescriptor(),
            controller.proot().runtimeHealth(),
            control);
    transaction.commit(
        control,
        () -> {
          var proof =
              com.deepseekharness.app.runtime.RuntimeTrial.verify(
                  controller.context(),
                  controller.proot(),
                  control,
                  () -> validateRuntime(controller.proot(), false),
                  controller.runtimeHostPorts().browserProbe());
          controller.proot().confirmRuntimeHealth(proof);
          if (!controller.isEnvironmentReady()) throw new IOException("RUNTIME_READINESS_FAILED");
          return proof;
        },
        () -> !RuntimeTasks.hasOtherTasks());
    com.deepseekharness.app.backup.ManagedRuntimeTransaction.archiveCompleted(fs, files);
    return com.deepseekharness.app.util.UiText.choose(
        "兼容运行时已通过本轮隔离试运行并启用。对话、配置和项目保持原位。",
        "The compatible runtime passed this isolated trial and is active. Conversations, configuration and projects stayed in place.");
  }

  /** 同一 Ubuntu 基础环境只替换 APK 受管树，现有 .dsh 数据与工作区无需复制。 */
  public static String update(HarnessController controller, Consumer<String> progress)
      throws Exception {
    if (!controller.proot().canUpdateManagedRuntime()) return rebuild(controller, progress);
    if (!MaintenanceCoordinator.isOwner())
      throw new IOException(com.deepseekharness.app.util.UiText.text("运行时更新必须持有停止屏障与数据任务锁"));
    File files = controller.context().getFilesDir().getCanonicalFile();
    if (MaintenanceCoordinator.pending(files))
      throw new IOException(com.deepseekharness.app.util.UiText.text("请先恢复中断的环境维护"));
    requireSpace(files, 512L * 1024 * 1024);
    var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
    com.deepseekharness.app.backup.RuntimeDescriptor previous = null;
    java.util.Map<String, Object> previousHealth = null;
    try {
      previous = controller.proot().installedRuntimeDescriptor();
      previousHealth = controller.proot().runtimeHealth();
    } catch (IOException unknown) {
      progress.accept(
          com.deepseekharness.app.util.UiText.choose(
              "旧运行时身份无法确认；原件保留，按当前 APK 修复受管文件。",
              "The old runtime identity cannot be verified. Retain originals and repair managed files from this APK."));
    }
    com.deepseekharness.app.backup.ManagedRuntimeTransaction transaction =
        com.deepseekharness.app.backup.ManagedRuntimeTransaction.create(fs, files);
    var control = maintenanceControl(progress);
    boolean began = false;
    try {
      java.util.List<String> paths =
          controller.proot().stageManagedRuntime(transaction.stage(), progress);
      transaction.prepare(
          paths, controller.proot().expectedRuntimeDescriptor(), previous, previousHealth, control);
      began = true;
      progress.accept(com.deepseekharness.app.util.UiText.text("新版运行时准备完成，正在保留并切换受管文件…"));
      transaction.commit(
          control,
          () -> {
            var proof =
                com.deepseekharness.app.runtime.RuntimeTrial.verify(
                    controller.context(),
                    controller.proot(),
                    control,
                    () -> validateRuntime(controller.proot(), false),
                    controller.runtimeHostPorts().browserProbe());
            controller.proot().confirmRuntimeHealth(proof);
            if (!controller.isEnvironmentReady()) throw new IOException("RUNTIME_READINESS_FAILED");
            return proof;
          },
          () -> !RuntimeTasks.hasOtherTasks());
    } catch (Exception failure) {
      if (began && !RuntimeTasks.hasOtherTasks())
        try {
          transaction.recover();
        } catch (Exception retained) {
          failure.addSuppressed(retained);
        }
      throw new IOException(
          com.deepseekharness.app.util.UiText.format(
              "运行时更新未完成，原运行时及个人数据已保留：\n%s\n%s",
              transaction.directory(), BackupManager.safeError(failure)),
          failure);
    }
    String cleanup = "";
    try {
      com.deepseekharness.app.backup.ManagedRuntimeTransaction.trimOlder(
          fs,
          files,
          controller.proot().installedRuntimeDescriptor(),
          controller.proot().runtimeHealth());
    } catch (IOException error) {
      cleanup =
          com.deepseekharness.app.util.UiText.format(
              "\n部分旧运行时待下次清理：%s", BackupManager.safeError(error));
    }
    return com.deepseekharness.app.util.UiText.format(
        "dsh 与内置插件更新完成；Ubuntu、个人目录、会话、配置和第三方插件保持原位。%s", cleanup);
  }

  static String rebuild(
      HarnessController controller, Consumer<String> progress, ExtractStep extract)
      throws Exception {
    if (!MaintenanceCoordinator.isOwner())
      throw new IOException("ENVIRONMENT_REQUIRES_MAINTENANCE");
    File files = controller.context().getFilesDir().getCanonicalFile();
    if (MaintenanceCoordinator.pending(files))
      throw new IOException("ENVIRONMENT_RECOVERY_REQUIRED");
    if (!controller.hasOfflineBundle())
      throw new IOException(com.deepseekharness.app.util.UiText.text("APK 缺少内置环境包，原环境保持原位"));
    var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
    var layout = new com.deepseekharness.app.backup.UserDataLayout(fs, files);
    if (layout.selected() == com.deepseekharness.app.backup.UserDataLayout.Home.STABLE)
      layout.choose(com.deepseekharness.app.backup.UserDataLayout.Home.STABLE);
    var transaction =
        com.deepseekharness.app.backup.EnvironmentRebuildTransaction.create(fs, files, null);
    var data =
        new com.deepseekharness.app.backup.MaintenanceDataSnapshot(
            fs,
            files,
            transaction.directory(),
            android.os.Environment.getExternalStorageDirectory().getCanonicalFile());
    var control = maintenanceControl(progress);
    long expanded = controller.proot().expandedEnvironmentBytes();
    var space =
        new MaintenanceSpaceTrace(
            files::getUsableSpace,
            sample -> DiagnosticLog.record(controller.context(), "REBUILD_SPACE", sample.record()));
    requireSpace(space.sample(Stage.PREFLIGHT, Math.addExact(expanded, 64L * 1024 * 1024)));
    boolean began = false;
    try {
      progress.accept(
          com.deepseekharness.app.util.UiText.choose(
              "正在由 Android 保护应用数据与个人目录；不执行旧环境…",
              "Android is protecting application data and personal folders without executing the old environment…"));
      String workdir = controller.config().getWorkdir();
      space.sample(Stage.SNAPSHOT_START, 0);
      var snapshot =
          data.capture(workdir, com.deepseekharness.app.BuildConfig.VERSION_NAME, control);
      if (!workdir.equals(controller.config().getWorkdir()))
        throw new IOException("WORKSPACE_CHANGED");
      String archiveHash, mappingHash;
      try (var input = fs.read(data.archive(), fs.stat(data.archive()))) {
        archiveHash = com.deepseekharness.app.backup.BackupArchive.digest(input, control);
      }
      try (var input = fs.read(data.mapping(), fs.stat(data.mapping()))) {
        mappingHash = com.deepseekharness.app.backup.BackupArchive.digest(input, control);
      }
      long bytes = com.deepseekharness.app.backup.BackupJson.number(snapshot, "bytes");
      requireSpace(
          space.sample(
              Stage.SNAPSHOT_READY,
              Math.addExact(
                  Math.addExact(expanded, Math.multiplyExact(bytes, 2L)), 64L * 1024 * 1024)));
      transaction.prepare(archiveHash, mappingHash);
      control.check();
      // begin() records switching before moving the old tree. Its later
      // boundaries may fail, so catch must already own rollback duty.
      // rollback() is a safe no-op if no switching marker was published.
      began = true;
      transaction.begin();
      space.sample(Stage.SWITCHED, 0);
      space.sample(Stage.EXTRACTION_STARTED, 0);
      final long[] last = {0};
      extract.extract(
          new com.deepseekharness.app.runtime.ProotBootstrap.ExtractionProgress() {
            @Override
            public void onStage(String stage) {
              progress.accept(stage + "…");
            }

            @Override
            public void accept(Long done, Long total) {
              long now = android.os.SystemClock.elapsedRealtime();
              if (now - last[0] >= 500) {
                last[0] = now;
                progress.accept(
                    com.deepseekharness.app.util.UiText.format("正在解压内置环境… %s", Fmt.bytes(done)));
              }
            }
          });
      if (!controller.proot().isEnvironmentInstalled())
        throw new IOException("ENVIRONMENT_INSTALL_INCOMPLETE");
      space.sample(Stage.EXTRACTION_READY, 0);
      progress.accept(
          com.deepseekharness.app.util.UiText.choose(
              "正在恢复本机数据并逐文件校验…", "Restoring local data and verifying every file…"));
      data.restore(com.deepseekharness.app.util.GuestPaths.rootfs(files), control);
      space.sample(Stage.DATA_RESTORED, 0);
      controller.proot().prepareRuntimeTools();
      var proof =
          com.deepseekharness.app.runtime.RuntimeTrial.verify(
              controller.context(),
              controller.proot(),
              control,
              () -> validateRuntime(controller.proot(), false),
              controller.runtimeHostPorts().browserProbe());
      controller.proot().confirmRuntimeHealth(proof);
      if (!controller.isEnvironmentReady()) throw new IOException("RUNTIME_READINESS_FAILED");
      space.sample(Stage.RUNTIME_READY, 0);
      File stopped = new File(controller.proot().getRootfsDir(), "root/.dsha-stopped");
      if (!stopped.exists() && !stopped.createNewFile())
        throw new IOException("ENVIRONMENT_STOP_MARKER");
      transaction.dataRestored(archiveHash);
      transaction.commit(controller.proot().installedRuntimeDescriptor(), proof);
      space.sample(Stage.COMMITTED, 0);
      String cleanup;
      try {
        transaction.sealRetired(control);
        transaction.cleanupRetired(control);
        cleanup =
            com.deepseekharness.app.util.UiText.choose(
                "旧 Ubuntu 系统已清理，个人数据验证副本与事务记录保留。",
                "The old Ubuntu system was cleaned. Verified personal-data copies and transaction records remain.");
      } catch (IOException retained) {
        cleanup =
            com.deepseekharness.app.util.UiText.format(
                "旧环境清理暂缓，原件保留：%s", com.deepseekharness.app.backup.NativeBackupJobs.code(retained));
      }
      try {
        com.deepseekharness.app.backup.EnvironmentRebuildTransaction.archiveCompleted(fs, files);
      } catch (IOException retained) {
        cleanup +=
            com.deepseekharness.app.util.UiText.format(
                "\n旧事务归档暂缓，原件保留：%s",
                com.deepseekharness.app.backup.NativeBackupJobs.code(retained));
      }
      space.sample(Stage.RETIRED_CLEANUP, 0);
      return com.deepseekharness.app.util.UiText.choose(
              "环境重建完成，数据逐文件核验与运行试验通过。\n",
              "Environment rebuilt; data verification and the runtime trial passed.\n")
          + cleanup;
    } catch (Exception failure) {
      space.sample(Stage.FAILED, 0);
      if (began && !RuntimeTasks.hasOtherTasks())
        try {
          transaction.rollback();
          space.sample(Stage.ROLLED_BACK, 0);
        } catch (Exception retained) {
          failure.addSuppressed(retained);
        }
      throw new IOException(
          com.deepseekharness.app.util.UiText.format(
              "环境重建未完成，原件及宿主数据副本已保留：\n%s\n%s",
              transaction.directory(), BackupManager.safeError(failure)),
          failure);
    }
  }

  private static void requireSpace(File files, long required) throws IOException {
    long available = files.getUsableSpace();
    requireSpace(available, required);
  }

  private static void requireSpace(MaintenanceSpaceTrace.Sample sample) throws IOException {
    requireSpace(sample.available, sample.required);
  }

  private static void requireSpace(long available, long required) throws IOException {
    if (available < required)
      throw new IOException(
          com.deepseekharness.app.util.UiText.format(
              "可用空间不足：本阶段预计需要 %s，当前可用 %s。原环境保持原位，请释放空间后重试。",
              Fmt.bytes(required), Fmt.bytes(available)));
  }

  private static com.deepseekharness.app.backup.BackupControl maintenanceControl(
      Consumer<String> progress) {
    final long[] last = {0};
    final String[] previousStage = {""};
    return BackupTask.currentControl(
        (stage, entries, bytes) -> {
          long now = android.os.SystemClock.elapsedRealtime();
          if (now - last[0] < 600
              && stage.equals(previousStage[0])
              && !stage.equals("TRIAL_COMPATIBILITY_RETRY")) return;
          last[0] = now;
          previousStage[0] = stage;
          String title =
              stage.equals("TRIAL_COMPATIBILITY_RETRY")
                  ? com.deepseekharness.app.util.UiText.choose(
                      "proroot 已确认退出，正在使用 proot 进行一次兼容验证…",
                      "proroot has stopped; verifying once with proot compatibility mode…")
                  : stage.startsWith("TRIAL_")
                      ? trialProgress(stage)
                      : com.deepseekharness.app.util.UiText.choose(
                          "正在核验受管文件…", "Verifying managed files…");
          progress.accept(title + (bytes > 0 ? " " + Fmt.bytes(bytes) : ""));
        });
  }

  private static String trialProgress(String stage) {
    if (stage.matches("TRIAL_NATIVE_BUILTIN_[0-9]+")) {
      int index = Integer.parseInt(stage.substring("TRIAL_NATIVE_BUILTIN_".length()));
      return com.deepseekharness.app.util.UiText.format(
          "正在加载内置组件：%d/%d…",
          index + 1, com.deepseekharness.app.util.BuiltinPluginRegistry.SIGNED.size());
    }
    return switch (stage) {
      case "TRIAL_NATIVE_MODULES" ->
          com.deepseekharness.app.util.UiText.choose("正在核验原生模块…", "Checking native modules…");
      case "TRIAL_NATIVE_LOADER" ->
          com.deepseekharness.app.util.UiText.choose(
              "正在准备组件加载器…", "Preparing the component loader…");
      case "TRIAL_NATIVE_FLOCK" ->
          com.deepseekharness.app.util.UiText.choose("正在核验文件锁…", "Checking file locking…");
      case "TRIAL_NATIVE_READY" ->
          com.deepseekharness.app.util.UiText.choose(
              "正在启动 DSH 网页…", "Starting the DSH browser interface…");
      case "TRIAL_NATIVE_PNPM" ->
          com.deepseekharness.app.util.UiText.choose("核验 pnpm 入口…", "Checking the pnpm entry…");
      case "TRIAL_NATIVE_STORAGE" ->
          com.deepseekharness.app.util.UiText.choose("核验数据读写…", "Checking data access…");
      case "TRIAL_NATIVE_FRESH" ->
          com.deepseekharness.app.util.UiText.choose(
              "在独立进程重开数据…", "Reopening data in a fresh process…");
      case "TRIAL_NATIVE_SESSION" ->
          com.deepseekharness.app.util.UiText.choose("核验会话重开…", "Checking session reopening…");
      case "TRIAL_NATIVE_PLUGIN_READY" ->
          com.deepseekharness.app.util.UiText.choose(
              "数据与会话已通过核验", "Data and session checks passed");
      case "TRIAL_AUTHENTICATING" ->
          com.deepseekharness.app.util.UiText.choose(
              "正在确认网页鉴权…", "Confirming browser authentication…");
      case "TRIAL_RENDERING" ->
          com.deepseekharness.app.util.UiText.choose(
              "正在确认网页与数据读写…", "Confirming the browser and data access…");
      default ->
          com.deepseekharness.app.util.UiText.choose(
              "正在确认隔离运行环境与网页连接；请保持应用在前台，可继续等待慢启动…",
              "Verifying the isolated runtime and browser connection; keep the app in the foreground. Slow startup continues waiting…");
    };
  }

  private static void validateRuntime(com.deepseekharness.app.runtime.ProotBootstrap proot)
      throws IOException {
    validateRuntime(proot, true);
  }

  private static void validateRuntime(
      com.deepseekharness.app.runtime.ProotBootstrap proot, boolean register) throws IOException {
    // 与安装页共用检查项，冷重建不能依赖用户先启动一次 Web 才补齐运行补丁。
    // 覆盖安装/权限变更可能改变 Android supplementary groups；与冷安装、PTY 一样先补齐当前真实组。
    // 只追加缺少的本机名称映射，随后仍以 id -Gn 实际复验，不能跳过第 6 步。
    proot.ensureAndroidGroups();
    // Node、Python、工具和原生模块在 RuntimeTrial 的同一次真实启动中核验。
    // 此处只核对不会启动 Node 的补丁/解析/组信息，避免重复加载整套 DSH。
    java.util.List<com.deepseekharness.app.util.InstallProbe.Check> checks =
        com.deepseekharness.app.util.InstallProbe.checks(6);
    var checked = new com.deepseekharness.app.util.InstallProbe.Results(checks);
    String probe =
        readCheckedProot(proot, com.deepseekharness.app.util.InstallProbe.script(checks), 30_000);
    for (String line : probe.split("\\r?\\n")) checked.accept(line);
    if (!checked.ok(6)) throw new IOException("RUNTIME_PATCH_CHECK_FAILED:" + checked.detail(6));
    if (register) readCheckedProot(proot, "python3 /root/.dsh/register-builtin-plugins.py", 30_000);
  }

  private static String readCheckedProot(
      com.deepseekharness.app.runtime.ProotBootstrap proot, String command, long timeoutMs)
      throws IOException {
    try {
      var result = proot.execAndReadWithProotResult(command, timeoutMs);
      return com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
          result, "RUNTIME_PROBE");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new java.io.InterruptedIOException("RUNTIME_PROBE_INTERRUPTED");
    }
  }

  private static String builtinImportProbe() {
    StringBuilder script = new StringBuilder();
    for (String name : com.deepseekharness.app.util.BuiltinPluginRegistry.SIGNED) {
      String path =
          com.deepseekharness.app.util.BuiltinPluginRegistry.guestDirectory(name)
              + "/"
              + com.deepseekharness.app.util.BuiltinPluginRegistry.entrypoint(name);
      // Registry paths are generated from the signed JSON and contain only safe ASCII.
      script.append("await import('file://").append(path).append("');");
    }
    return script.toString();
  }

  /** 只删除事务拥有的树；lstat 失败就中止，绝不沿链接清理公开存储。 */
  static void deleteTree(File root) throws IOException {
    try {
      android.system.StructStat info = android.system.Os.lstat(root.getAbsolutePath());
      if (android.system.OsConstants.S_ISDIR(info.st_mode)) {
        // proot 的虚拟挂载占位目录可能是 000；事务私有旧树已停用，可恢复本 UID 的遍历权限。
        // 先 lstat，绝不沿软链 chmod 到公开存储；未知所有者仍保留并报告。
        if ((info.st_mode & 0700) != 0700) {
          if (info.st_uid != android.os.Process.myUid())
            throw new IOException(
                com.deepseekharness.app.util.UiText.format("待清理目录不属于本应用：%s", root.getName()));
          android.system.Os.chmod(root.getAbsolutePath(), (info.st_mode & 0777) | 0700);
        }
        File[] children = root.listFiles();
        if (children == null)
          throw new IOException(
              com.deepseekharness.app.util.UiText.format("无法读取待清理目录：%s", root.getName()));
        for (File child : children) deleteTree(child);
      }
      if (!root.delete())
        throw new IOException(
            com.deepseekharness.app.util.UiText.format("无法清理旧文件：%s", root.getName()));
    } catch (android.system.ErrnoException error) {
      throw new IOException(com.deepseekharness.app.util.UiText.text("旧文件状态无法确认，已停止清理"), error);
    }
  }

  /** 仅回收新版已提交且保存过个人文件的事务，历史安全副本不在自动清理范围内。 */
  public static void cleanupCompleted(HarnessController controller) throws IOException {
    // 普通启动只读门禁，不在后台遍历或清理旧用户树。受管版本在健康提交后的屏障内轮换。
  }

  public static String recover(HarnessController controller) throws Exception {
    if (!MaintenanceCoordinator.isOwner())
      throw new IOException(com.deepseekharness.app.util.UiText.text("维护回滚必须经过停止屏障与全局数据任务锁"));
    ensureSingleRecovery(controller);
    File files = controller.context().getFilesDir().getCanonicalFile();
    com.deepseekharness.app.runtime.BoundedGuestSessions.reapExited(files);
    var filesystemCold = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
    if (!com.deepseekharness.app.backup.ColdInstallTransaction.pending(filesystemCold, files)
        .isEmpty()) {
      com.deepseekharness.app.backup.ColdInstallTransaction.recover(
          filesystemCold,
          files,
          () -> {
            if (com.deepseekharness.app.util.RuntimeWorkPort.hasOtherTasks()
                || com.deepseekharness.app.runtime.BoundedGuestSessions.firstPending(files) != null)
              throw new IOException("COLD_INSTALL_PROCESS_EXIT_UNCONFIRMED");
          },
          controller.runtimeHostPorts()::restoreColdSelection);
      return com.deepseekharness.app.util.UiText.choose(
          "中断的首次安装已在宿主侧恢复，原件和失败候选继续保留。",
          "Interrupted first installation recovered on the host; originals and failed candidates are retained.");
    }
    com.deepseekharness.app.runtime.RuntimeTrial.recoverPending(
        controller.context(), controller.proot());
    if (com.deepseekharness.app.backup.PluginInstallJournals.blocked(files))
      return PluginJournalRecovery.recover(controller);
    var environment =
        com.deepseekharness.app.backup.EnvironmentRebuildTransaction.pending(
            new com.deepseekharness.app.backup.AndroidBackupFileSystem(), files);
    if (environment != null) {
      if (RuntimeTasks.hasOtherTasks()) throw new IOException("TRIAL_PROCESS_UNCONFIRMED");
      environment.rollback();
      return com.deepseekharness.app.util.UiText.choose(
          "中断的环境重建已在宿主侧回切，数据副本及新旧环境均保留。",
          "Interrupted environment rebuild rolled back on the host; data snapshots and both environment trees are retained.");
    }
    var configuration = StartupRepairs.snapshots(controller.context());
    if (configuration.pending()) {
      configuration.recover(BackupTask.currentControl(null));
      return com.deepseekharness.app.util.UiText.choose(
          "配置事务已在宿主侧处理，原配置和日志继续保留。",
          "Configuration recovery was handled on the host; original configuration and records are retained.");
    }
    var runtimes =
        com.deepseekharness.app.backup.ManagedRuntimeTransaction.pending(
            new com.deepseekharness.app.backup.AndroidBackupFileSystem(), files);
    if (!runtimes.isEmpty()) {
      if (RuntimeTasks.hasOtherTasks()) throw new IOException("TRIAL_PROCESS_UNCONFIRMED");
      runtimes.get(0).recover();
      return com.deepseekharness.app.util.UiText.choose(
          "中断的运行时事务已回切，个人数据保持原位。",
          "Interrupted runtime transaction rolled back; personal data stayed in place.");
    }
    java.util.List<File> host =
        com.deepseekharness.app.backup.HostPendingTransactions.pending(
            new com.deepseekharness.app.backup.AndroidBackupFileSystem(), files);
    if (!host.isEmpty()) {
      if (host.size() != 1 || RuntimeTasks.hasOtherTasks())
        throw new IOException("HOST_RECOVERY_REQUIRES_REVIEW");
      var filesystem = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      if (com.deepseekharness.app.backup.ProfileSettingsTransaction.owns(filesystem, host.get(0))) {
        com.deepseekharness.app.backup.ProfileSettingsTransaction.recover(
            controller.context(), host.get(0));
        return com.deepseekharness.app.util.UiText.choose(
            "中断的 profile 设置事务已恢复，原件保留。",
            "Interrupted profile settings transaction recovered; originals retained.");
      }
      if (com.deepseekharness.app.backup.NativeConfigurationReset.owns(filesystem, host.get(0))) {
        com.deepseekharness.app.backup.NativeConfigurationReset.recover(
            filesystem, files, host.get(0), controller.nativeSettingsTransaction(), null);
        return com.deepseekharness.app.util.UiText.choose(
            "中断的配置重置已在宿主侧恢复，原件保留。",
            "Interrupted configuration reset recovered on the host; originals retained.");
      }
      com.deepseekharness.app.backup.NativeRestoreTargets mapping =
          new com.deepseekharness.app.backup.NativeRestoreTargets(
              controller.context(), host.get(0));
      new com.deepseekharness.app.backup.HostDataTransaction(
              new com.deepseekharness.app.backup.AndroidBackupFileSystem(),
              host.get(0),
              mapping,
              mapping,
              null)
          .recover();
      return com.deepseekharness.app.util.UiText.choose(
          "宿主数据事务已恢复一致状态，原件保留。", "Host data transaction recovered; originals retained.");
    }
    com.deepseekharness.app.util.RuntimeUpdateTransaction runtime =
        com.deepseekharness.app.util.RuntimeUpdateTransaction.pending(
            new com.deepseekharness.app.backup.AndroidBackupFileSystem(), files);
    if (runtime != null) {
      if (RuntimeTasks.hasOtherTasks())
        throw new IOException(com.deepseekharness.app.util.UiText.text("后台进程尚未退出，请稍后恢复运行时更新"));
      runtime.rollback();
      return com.deepseekharness.app.util.UiText.format(
          "中断的运行时更新已回切；个人目录、会话和配置保持原位。\n%s", runtime.directory());
    }
    MaintenanceTransaction pending =
        MaintenanceTransaction.pending(
            new com.deepseekharness.app.backup.AndroidBackupFileSystem(), files);
    if (pending == null) return com.deepseekharness.app.util.UiText.text("没有未完成的环境维护。");
    if (RuntimeTasks.hasOtherTasks())
      throw new IOException(
          com.deepseekharness.app.util.UiText.text("后台进程尚未退出，维护日志及新旧环境均保留。请等待进程退出后再恢复中断维护。"));
    pending.rollback();
    return com.deepseekharness.app.util.UiText.format(
        "中断的维护已回滚；安全备份、旧环境及失败的新环境均已保留。\n%s", pending.directory().getAbsolutePath());
  }

  public static void ensureSingleRecovery(HarnessController controller) throws IOException {
    File files = controller.context().getFilesDir().getCanonicalFile();
    if (com.deepseekharness.app.backup.HostMaintenancePending.moreThanOneRecoverable(files))
      throw new IOException("MULTIPLE_TRANSACTIONS");
  }
}
