package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.ColdInstallTransaction;
import com.deepseekharness.app.backup.RuntimeDescriptor;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.MaintenanceGate;
import com.deepseekharness.app.util.RuntimeWorkPort;
import com.deepseekharness.app.util.PluginDownloadSource;
import com.deepseekharness.app.util.BoundedProcessRunner;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 兼容门面：实际安装、执行、插件、维护和终端契约由职责协作者管理。 */
public class ProotBootstrap {

  final Context ctx;
  private final File baseDir;
  final File rootfsDir;
  private final File libDir;
  private final File tmpDir;
  final boolean forceProot;
  private final RuntimeInstallationState installation;
  private final RuntimeDataTransfers transfers;
  private final RuntimeLauncher launcher;
  private final GuestPluginScripts pluginScripts;
  private final RuntimeHostPorts hostPorts;
  private final RuntimeNativeFiles nativeFiles;
  private final RuntimeExecution execution;
  private final TerminalRuntime terminal;
  private final RecoveryToolchain recovery;
  final ColdInstallTransaction.Candidate coldCandidate;
  ColdRuntimeSelection preparedColdRuntime;

  public ProotBootstrap(Context c) {
    this(c, false);
  }

  /** 本次兼容重试使用 proot，不改用户保存的运行方式。 */
  public ProotBootstrap(Context c, boolean forceProot) {
    this(c, forceProot, null);
  }

  ProotBootstrap(Context c, boolean forceProot, ColdInstallTransaction.Candidate candidate) {
    this.forceProot = forceProot;
    this.coldCandidate = candidate;
    ctx = c.getApplicationContext();
    hostPorts = RuntimeHostPorts.fromOwner(ctx);
    baseDir = candidate == null ? new File(ctx.getFilesDir(), "linux") : candidate.linux();
    rootfsDir = new File(baseDir, "ubuntu");
    libDir = new File(baseDir, "lib");
    tmpDir = new File(baseDir, "tmp");
    installation = new RuntimeInstallationState(ctx, baseDir, rootfsDir);
    transfers = new RuntimeDataTransfers(ctx, rootfsDir);
    launcher = new RuntimeLauncher(ctx, rootfsDir, baseDir, libDir, tmpDir, hostPorts);
    nativeFiles = new RuntimeNativeFiles(ctx, baseDir, libDir, tmpDir);
    execution =
        new RuntimeExecution(
            ctx,
            rootfsDir,
            baseDir,
            tmpDir,
            forceProot,
            coldCandidate == null,
            hostPorts,
            this::requireUserRuntime,
            nativeFiles,
            launcher);
    terminal = new TerminalRuntime(ctx, rootfsDir, execution, hostPorts);
    recovery =
        new RecoveryToolchain(
            ctx, rootfsDir, baseDir, libDir, tmpDir, nativeFiles, launcher, hostPorts);
    pluginScripts =
        new GuestPluginScripts(
            ctx, rootfsDir, this::isEnvironmentReady, this::execAndReadWithProotResult);
  }

  public RuntimeHostPorts hostPorts() {
    return hostPorts;
  }

  public File getRootfsDir() {
    return rootfsDir;
  }

  public void installBundledDeviceScripts() throws IOException {
    RuntimeTools.installDeviceScripts(ctx, rootfsDir);
  }

  public boolean bundledDeviceScriptsMatch() {
    return RuntimeTools.deviceScriptsMatch(ctx, rootfsDir);
  }

  public static final String OFFLINE_VERSION_ASSET = RuntimeInstallationState.OFFLINE_VERSION_ASSET;

  public boolean isOfflineExtracted() {
    return installation.isOfflineExtracted();
  }

  public boolean hasBash() {
    return installation.hasBash();
  }

  public String environmentIdentity() {
    return installation.environmentIdentity();
  }

  public long expandedEnvironmentBytes() {
    return installation.expandedEnvironmentBytes();
  }

  public boolean rootfsVersionMatches() {
    return installation.rootfsVersionMatches();
  }

  public boolean isEnvironmentReady() {
    return installation.isEnvironmentReady();
  }

  public boolean isEnvironmentInstalled() {
    return installation.isEnvironmentInstalled();
  }

  public RuntimeDescriptor expectedRuntimeDescriptor() throws IOException {
    return installation.expectedRuntimeDescriptor();
  }

  public RuntimeDescriptor installedRuntimeDescriptor() throws IOException {
    return installation.installedRuntimeDescriptor();
  }

  public java.util.Map<String, Object> runtimeHealth() throws IOException {
    return installation.runtimeHealth();
  }

  public boolean needsRuntimeUpdate() {
    return installation.needsRuntimeUpdate();
  }

  public void confirmRuntimeHealth(java.util.Map<String, Object> proof) throws IOException {
    installation.confirmRuntimeHealth(proof);
  }

  public boolean canUpdateManagedRuntime() {
    return installation.canUpdateManagedRuntime();
  }

  public List<String> stageManagedRuntime(File stage, java.util.function.Consumer<String> progress)
      throws IOException {
    return installation.stageManagedRuntime(stage, progress);
  }

  public void markOfflineExtracted() throws IOException {
    installation.markOfflineExtracted();
  }

  public void markNotExtracted() {
    installation.markNotExtracted();
  }

  // ================= 运行时文件 =================

  File findNativeLib(String name) {
    return nativeFiles.find(name);
  }

  public void ensureRuntimeFiles() {
    try {
      nativeFiles.prepare();
    } catch (IOException error) {
      throw new IllegalStateException("NATIVE_LIBRARY_PREPARATION_FAILED", error);
    }
    ensureDshRuntimePatches();
  }

  /** Prepares the shared current recipe chain for cold, managed and installed roots. */
  public void ensureDshRuntimePatches() {
    execution.ensurePatches();
  }

  public static final String BUILTIN_REGISTER_SCRIPT = GuestPluginScripts.BUILTIN_REGISTER_SCRIPT;
  public static final String RC1_MIGRATION_SCRIPT = GuestPluginScripts.RC1_MIGRATION_SCRIPT;
  public static final String PLUGIN_MANAGER_SCRIPT = GuestPluginScripts.PLUGIN_MANAGER_SCRIPT;

  public String registerBuiltinPlugins() {
    return pluginScripts.register();
  }

  public BoundedProcessRunner.Result registerBuiltinPluginsResult()
      throws IOException, InterruptedException {
    return pluginScripts.builtin("");
  }

  public String prepareRc1Migration(String startupId) {
    return pluginScripts.migrationText("prepare", startupId);
  }

  public BoundedProcessRunner.Result prepareRc1MigrationResult(String startupId)
      throws IOException, InterruptedException {
    return pluginScripts.migration("prepare", startupId);
  }

  public String finalizeRc1Migration(String startupId) {
    return pluginScripts.migrationText("finalize", startupId);
  }

  public BoundedProcessRunner.Result finalizeRc1MigrationResult(String startupId)
      throws IOException, InterruptedException {
    return pluginScripts.migration("finalize", startupId);
  }

  public String setPluginEnabled(String name, boolean enable) {
    return pluginScripts.enable(name, enable);
  }

  public String runPluginManager(String args) {
    return runPluginManager(args, "");
  }

  public String runPluginManager(String args, String taskId) {
    return pluginScripts.managerText(args, taskId);
  }

  public BoundedProcessRunner.Result runPluginManagerResult(String args, String taskId)
      throws IOException, InterruptedException {
    return pluginScripts.manager(args, taskId, PluginDownloadSource.AUTO);
  }

  public BoundedProcessRunner.Result runPluginManagerResult(
      String args, String taskId, com.deepseekharness.app.util.PluginDownloadSource source)
      throws IOException, InterruptedException {
    return pluginScripts.manager(args, taskId, source);
  }

  public boolean pushFileIntoContainer(File source, String target) {
    return transfers.pushFileIntoContainer(source, target);
  }

  public boolean pullFileFromContainer(String source, File target) {
    return transfers.pullFileFromContainer(source, target);
  }

  String readAssetString(String name) {
    return RuntimeAssetText.read(ctx, name);
  }

  public void ensureAndroidGroups() {
    execution.prepareGroups();
  }

  public ContainerRuntime runtime() {
    return execution.runtime();
  }

  public void prepareRuntimeTools() throws IOException {
    execution.prepareTools();
  }

  LaunchSpec.Builder launch(ContainerRuntime runtime) {
    return execution.launch(runtime);
  }

  public Process execRootfs(String command) throws IOException {
    return execRootfs(command, java.util.Map.of());
  }

  public Process execRootfs(String command, java.util.Map<String, String> environment)
      throws IOException {
    return execution.exec(command, environment);
  }

  public Process execRootfsForInstall(String command) throws IOException {
    return execution.install(command, false);
  }

  public Process execRootfsForInstallWithPipedInput(String command) throws IOException {
    return execution.install(command, true);
  }

  public Process execRootfsForTrial(String command, File data, String guest, String mode)
      throws IOException {
    return execution.trial(command, data, guest, mode);
  }

  Process startRootfs(String command, LaunchSpec spec) throws IOException {
    return execution.start(command, spec);
  }

  Process execRootfsForColdInstall(String command) throws IOException {
    return execution.cold(command);
  }

  public String execAndRead(String command) {
    return execAndRead(command, 60_000);
  }

  public String execAndRead(String command, long timeoutMs) {
    return execution.read(command, timeoutMs, false);
  }

  public String execAndReadWithProot(String command, long timeoutMs) {
    return execution.read(command, timeoutMs, true);
  }

  public BoundedProcessRunner.Result execAndReadWithProotResult(String command, long timeoutMs)
      throws IOException, InterruptedException {
    return execution.collect(command, timeoutMs, true);
  }

  public void prepareDataMaintenance() throws IOException {
    recovery.prepareData();
  }

  public void releaseRecoveryTools() {
    recovery.release();
  }

  public BoundedProcessRunner.Result runRecoveryMaintenance(
      String command, java.util.function.Consumer<String> onLine, long timeoutMs)
      throws IOException, InterruptedException {
    return recovery.run(command, onLine, timeoutMs);
  }

  public BoundedProcessRunner.Result runPersonalMaintenance(
      String command, java.util.function.Consumer<String> onLine, boolean fast, long timeoutMs)
      throws IOException, InterruptedException {
    return recovery.run(command, onLine, timeoutMs);
  }

  public String execChecked(String command) throws IOException {
    return execution.checked(command);
  }

  public static final class PtyLaunch {
    public final String[] argv, environment;

    private PtyLaunch(String[] argv, String[] environment) {
      this.argv = argv;
      this.environment = environment;
    }
  }

  public PtyLaunch ptyLaunch() {
    TerminalRuntime.Launch value = terminal.launch();
    return new PtyLaunch(value.argv(), value.environment());
  }

  public Process execRootfsInteractive() throws IOException {
    return terminal.interactive();
  }

  public String[] ptyArgv(String... guestCommand) {
    return terminal.launch(guestCommand).argv();
  }

  public String[] ptyEnv() {
    return terminal.launch().environment();
  }

  public void requireUserRuntime() throws IOException {
    if (MaintenanceGate.shared().isOwner()) return;
    if (!isEnvironmentReady()
        || com.deepseekharness.app.backup.HostMaintenancePending.blocked(baseDir.getParentFile()))
      throw new IOException(UiText.text("运行环境尚未恢复，可先在主界面查看日志与配置，再进入安装与修复页处理"));
  }

  public static final class SmokeResult {
    public final String runtimeMode;
    public final BoundedProcessRunner.Result command;

    private SmokeResult(String runtimeMode, BoundedProcessRunner.Result command) {
      this.runtimeMode = runtimeMode;
      this.command = command;
    }
  }

  /** Read-only selected-runtime probe; mode and command use the same config image. */
  public SmokeResult smokeTestResult() throws IOException, InterruptedException {
    RuntimeExecution.Probe result = execution.probe();
    return new SmokeResult(result.runtimeMode(), result.command());
  }

  public String smokeTest() {
    return execution.smokeText();
  }

  // ================= 离线 rootfs 解压 =================

  public boolean hasOfflineBundle() {
    try (ZipFile z = new ZipFile(ctx.getPackageCodePath())) {
      if (findBundleEntry(z) != null) return true;
    } catch (Exception ignored) {
    }
    try {
      ctx.getAssets().open(com.deepseekharness.app.util.BundledRootfsAsset.NAME).close();
      return true;
    } catch (IOException unavailable) {
    }
    return false;
  }

  ZipEntry findBundleEntry(ZipFile z) {
    return com.deepseekharness.app.util.BundledRootfsAsset.find(z);
  }

  /** 解压字节与后续安装阶段分开报告，避免安装工具时界面仍显示“正在解压”。 */
  public interface ExtractionProgress extends java.util.function.BiConsumer<Long, Long> {
    void onStage(String stage);
  }

  void extractionStage(java.util.function.BiConsumer<Long, Long> progress, String stage) {
    hostPorts.stage(stage);
    if (progress instanceof ExtractionProgress) ((ExtractionProgress) progress).onStage(stage);
  }

  /** Stream signed APK bundle entries into a staged installation with separate extraction progress. */
  public void extractOfflineBundle(java.util.function.BiConsumer<Long, Long> onProgress)
      throws IOException {
    ColdBundleTransaction.run(this, onProgress);
  }

  void requireColdClosed() throws IOException {
    if (RuntimeWorkPort.hasOtherTasks()
        || BoundedGuestSessions.firstPending(ctx.getFilesDir()) != null)
      throw new IOException("COLD_INSTALL_PROCESS_EXIT_UNCONFIRMED");
  }

  void requireColdCandidate() throws IOException {
    if (coldCandidate == null) return;
    try {
      if (Os.lstat(coldCandidate.linux().getPath()).st_uid != android.os.Process.myUid()
          || Os.lstat(coldCandidate.root().getPath()).st_uid != android.os.Process.myUid())
        throw new IOException("COLD_CANDIDATE_OWNERSHIP");
    } catch (android.system.ErrnoException error) {
      throw new IOException("COLD_CANDIDATE_UNREADABLE", error);
    }
  }

  void extractOfflineBundleInternal(java.util.function.BiConsumer<Long, Long> onProgress)
      throws IOException {
    ColdBundleExtractor.run(this, onProgress);
  }

  /** 在新解压环境中通过 dpkg 离线安装，保留正常包数据库与维护脚本。 */
  static final class ColdRuntimeSelection {
    final com.deepseekharness.app.util.ColdInstallPlan.Mode mode;
    final String packageSlotSha;

    ColdRuntimeSelection(com.deepseekharness.app.util.ColdInstallPlan.Mode mode, String sha) {
      this.mode = mode;
      this.packageSlotSha = sha;
    }
  }

  static void requireOwnedColdFiles(File files) throws IOException {
    try {
      int uid = android.os.Process.myUid();
      if (Os.lstat(files.getPath()).st_uid != uid
          || Os.lstat(files.getParentFile().getPath()).st_uid != uid)
        throw new IOException("COLD_PACKAGE_HOST_ROOT_OWNERSHIP");
    } catch (android.system.ErrnoException unreadable) {
      throw new IOException("COLD_PACKAGE_HOST_ROOT_UNREADABLE", unreadable);
    }
  }

  ColdRuntimeSelection installBundledUbuntuTools(java.util.function.BiConsumer<Long, Long> progress)
      throws IOException {
    return ColdToolsInstaller.run(this, progress);
  }

  void installBundledPython(File stage) throws IOException {
    RootfsInstaller.python(ctx, stage);
  }

  /** 老用户覆盖安装时按需补齐 Python，不重解压或删除其 rootfs。 */
  public boolean ensureBundledPython() {
    return ensureGlibcPython();
  }

  public boolean ensureGlibcPython() {
    return RootfsInstaller.ensurePython(ctx, rootfsDir);
  }

  void installBundledPnpm(File stage) throws IOException {
    RootfsInstaller.pnpm(ctx, stage);
  }

  public boolean ensureBundledPnpm() {
    return RootfsInstaller.ensurePnpm(ctx, rootfsDir);
  }
}
