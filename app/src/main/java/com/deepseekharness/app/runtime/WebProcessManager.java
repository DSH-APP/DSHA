package com.deepseekharness.app.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.WebPidIdentity;
import com.deepseekharness.app.util.WebProcSel;
import com.deepseekharness.app.util.WebStopDiagnostic;
import com.deepseekharness.app.util.WebStopDiagnostic.Code;
import com.deepseekharness.app.util.WebStopException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 宿主侧统一 Web 停止与维护判据；只给经身份核验的 PID 发信号。 */
public class WebProcessManager {
  public enum StopStatus {
    STOPPED,
    UNCONFIRMED,
    CANCELLED
  }

  public record StopResult(
      StopStatus status,
      String code,
      java.util.List<WebStopDiagnostic> diagnostics,
      RuntimeHostPorts display) {
    public StopResult {
      diagnostics = java.util.List.copyOf(diagnostics);
    }

    public StopResult(StopStatus status, String code, String legacyDetail) {
      this(
          status,
          code,
          legacyDetail.isEmpty()
              ? java.util.List.of()
              : java.util.List.of(WebStopDiagnostic.of(Code.LEGACY_DETAIL, legacyDetail)),
          null);
    }

    public WebStopDiagnostic diagnostic() {
      return diagnostics.isEmpty() ? null : diagnostics.get(0);
    }

    public String detail() {
      StringBuilder rendered = new StringBuilder();
      for (WebStopDiagnostic value : diagnostics) {
        String line =
            display != null
                ? display.describeWebStop(value)
                : value.code() == Code.LEGACY_DETAIL ? value.arguments().get(0) : value.debug();
        if (rendered.length() > 0) rendered.append('\n');
        rendered.append(line);
      }
      return rendered.toString();
    }

    public boolean stopped() {
      return status == StopStatus.STOPPED;
    }
  }

  enum Kind {
    GONE,
    WEB,
    OTHER,
    DENIED
  }

  static final class ProcessState {
    final Kind kind;
    final WebPidIdentity identity;
    final String command;
    final boolean differentUid, signalProbeForbidden;

    ProcessState(Kind kind, WebPidIdentity identity, String command) {
      this(kind, identity, command, false);
    }

    ProcessState(Kind kind, WebPidIdentity identity, String command, boolean differentUid) {
      this(kind, identity, command, differentUid, false);
    }

    ProcessState(
        Kind kind,
        WebPidIdentity identity,
        String command,
        boolean differentUid,
        boolean signalProbeForbidden) {
      this.kind = kind;
      this.identity = identity;
      this.command = command;
      this.differentUid = differentUid;
      this.signalProbeForbidden = signalProbeForbidden;
    }
  }

  static final class ProcessInspectionException extends IOException {
    ProcessInspectionException(IOException cause) {
      super(cause.getMessage(), cause);
    }
  }

  private final ProotBootstrap proot;
  private final File records;

  public static final class ForceCandidate {
    private final com.deepseekharness.app.util.WebForceStop.Candidate proof;

    private ForceCandidate(com.deepseekharness.app.util.WebForceStop.Candidate proof) {
      this.proof = proof;
    }

    public int pid() {
      return proof.pid();
    }

    public String generation() {
      return proof.generation();
    }
  }

  /** Capture before the native UI confirmation; trial and unknown processes have no force path. */
  public ForceCandidate prepareForceStop() throws IOException {
    if (records != null)
      throw new WebStopException(Code.FORCE_EVIDENCE_UNCONFIRMED, "TRIAL_FORBIDDEN");
    try {
      return new ForceCandidate(
          com.deepseekharness.app.util.WebForceStop.prepare(this, this::forceEvidence));
    } catch (IOException failure) {
      throw new WebStopException(
          WebStopDiagnostic.failure(Code.FORCE_EVIDENCE_UNCONFIRMED, failure), failure);
    }
  }

  /** Only explicit user confirmation may call this; it never signals a launcher or process group. */
  public String forceStop(ForceCandidate candidate) throws IOException {
    return forceStopResult(candidate).detail();
  }

  public StopResult forceStopResult(ForceCandidate candidate) throws IOException {
    if (records != null)
      throw new WebStopException(Code.FORCE_EVIDENCE_UNCONFIRMED, "TRIAL_FORBIDDEN");
    try {
      sentinel();
      com.deepseekharness.app.util.WebForceStop.signal(
          this,
          candidate == null ? null : candidate.proof,
          this::forceEvidence,
          pid -> {
            try {
              Os.kill(pid, OsConstants.SIGKILL);
            } catch (ErrnoException error) {
              throw new IOException("WEB_FORCE_SIGNAL_UNCONFIRMED", error);
            }
          });
      // Reuse the ordinary retirement/global guest proof. A sent signal alone never releases a
      // barrier.
      return result(stopAll());
    } catch (IOException failure) {
      throw new WebStopException(
          WebStopDiagnostic.failure(Code.FORCE_EVIDENCE_UNCONFIRMED, failure), failure);
    }
  }

  private com.deepseekharness.app.util.WebForceStop.Evidence forceEvidence() throws IOException {
    if (records != null) throw new IOException("WEB_FORCE_TRIAL_FORBIDDEN");
    File physical = RuntimeAssetFiles.root(proot.ctx, proot.getRootfsDir());
    var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
    File home = fs.child(physical, "root");
    File pidPath = fs.child(home, ".dsha-web.pid"), bornPath = fs.child(home, ".dsha-web.identity");
    String record = new String(fs.small(pidPath, 32), StandardCharsets.US_ASCII);
    String born = new String(fs.small(bornPath, 128), StandardCharsets.US_ASCII);
    int pid = WebProcSel.parsePid(record);
    ProcessState state = inspect(pid);
    Integer uid = processOwner(pid);
    String generation = null;
    for (String entry : readProcessFile(pid, "environ").split("\u0000"))
      if (entry.startsWith("DSHA_WEB_GENERATION=")) {
        if (generation != null) throw new IOException("WEB_FORCE_GENERATION_DUPLICATE");
        generation = entry.substring("DSHA_WEB_GENERATION=".length());
      }
    if (state.identity == null
        || uid == null
        || state.kind != Kind.WEB
        || state.signalProbeForbidden
        || !state.identity.matches(born)) throw new IOException("WEB_FORCE_EVIDENCE_UNCONFIRMED");
    return new com.deepseekharness.app.util.WebForceStop.Evidence(
        pid,
        state.identity.started,
        uid,
        android.os.Process.myUid(),
        record,
        born,
        generation,
        state.command,
        true);
  }

  public WebProcessManager(ProotBootstrap proot) {
    this.proot = proot;
    records = null;
  }

  WebProcessManager(ProotBootstrap proot, File records) throws IOException {
    this.proot = proot;
    this.records = records;
    File home =
        new File(proot.getRootfsDir().getParentFile().getParentFile(), "runtime-trials")
            .getCanonicalFile();
    if (!records.getCanonicalFile().equals(records.getAbsoluteFile())
        || !records.getParentFile().getParentFile().equals(home)
        || !records.getName().equals("payload")
        || !records
            .getParentFile()
            .getName()
            .matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
      throw new IOException("TRIAL_RECORD_DIRECTORY");
  }

  private File root() {
    return records == null ? new File(proot.getRootfsDir(), "root") : records;
  }

  private File pidFile() {
    return new File(root(), ".dsha-web.pid");
  }

  private File identityFile() {
    return new File(root(), ".dsha-web.identity");
  }

  /**
   * 旧 guest 命令可能把 /root 的宿主权限改成不可写。仅在 lstat 确认
   * 目录归本应用所有时恢复 owner 权限；链接、其它 UID 或无法核验都保留
   * 原状并阻止维护，不借 chmod 越过数据边界。
   */
  private void ensureRootWritable() throws IOException {
    File directory = root();
    if (Compat.isSymbolicLink(directory)) throw new WebStopException(Code.ROOT_LINK);
    try {
      android.system.StructStat stat = android.system.Os.stat(directory.getAbsolutePath());
      if (stat.st_uid != android.os.Process.myUid())
        throw new WebStopException(Code.ROOT_FOREIGN_UID);
      int mode = stat.st_mode & 0777;
      if ((mode & 0700) != 0700) android.system.Os.chmod(directory.getAbsolutePath(), mode | 0700);
    } catch (android.system.ErrnoException error) {
      throw new WebStopException(Code.ROOT_ACCESS_UNCONFIRMED, error);
    }
  }

  private String pidRecord() throws IOException {
    File file = pidFile();
    if (Compat.isSymbolicLink(file)) throw new WebStopException(Code.PID_RECORD_INVALID);
    if (!file.exists()) return null;
    if (!file.isFile() || file.length() > 32) throw new WebStopException(Code.PID_RECORD_INVALID);
    ensureOwnerReadable(file);
    String value = Compat.readAll(file);
    if (WebProcSel.parsePid(value) < 0) throw new WebStopException(Code.PID_INVALID);
    return value;
  }

  private void ensureOwnerReadable(File file) throws IOException {
    try {
      android.system.StructStat stat = android.system.Os.lstat(file.getAbsolutePath());
      if (stat.st_uid != android.os.Process.myUid())
        throw new WebStopException(Code.RECORD_FOREIGN_UID);
      int mode = stat.st_mode & 0777;
      if ((mode & 0400) == 0) android.system.Os.chmod(file.getAbsolutePath(), mode | 0400);
    } catch (android.system.ErrnoException error) {
      throw new WebStopException(Code.RECORD_ACCESS_UNCONFIRMED, error);
    }
  }

  private void sentinel() throws IOException {
    ensureRootWritable();
    File file = new File(root(), ".dsha-stopped");
    if (Compat.isSymbolicLink(file) || !file.exists() && !file.createNewFile())
      throw new WebStopException(Code.SENTINEL_UNWRITABLE);
  }

  private static String readProc(int pid, String name) throws IOException {
    try (FileInputStream input = new FileInputStream("/proc/" + pid + "/" + name)) {
      byte[] bytes = new byte[16384];
      int count = input.read(bytes);
      if (count == bytes.length) throw new WebStopException(Code.PROC_INFO_LIMIT);
      return count <= 0 ? "" : new String(bytes, 0, count, StandardCharsets.UTF_8);
    }
  }

  /**
   * Android 16/厂商 ROM 可能对已复用的旧 PID 隐藏 stat/cmdline。先读 proc
   * 目录本身的 uid：如果编号已经属于别的 UID，它不是本应用 Web，安全地
   * 隔离 stale pid；同 UID 但内容不可读仍按未知进程阻塞，避免把权限错误当作
   * 已停止而继续切换运行时。
   */
  private static Integer processOwner(int pid) {
    try {
      return Os.stat("/proc/" + pid).st_uid;
    } catch (ErrnoException ignored) {
      return null;
    }
  }

  /** 包内测试可模拟 stat/cmdline 之间发生退出；生产仍直接读取内核。 */
  String readProcessFile(int pid, String name) throws IOException {
    return readProc(pid, name);
  }

  /** 包内缝供测试注入系统读取失败；生产始终从内核取证。 */
  ProcessState inspect(int pid) throws IOException {
    IOException failure = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return inspectOnce(pid);
      } catch (IOException error) {
        failure = error;
        if (attempt < 2)
          try {
            Thread.sleep(10);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WebStopException(Code.INSPECTION_INTERRUPTED, interrupted);
          }
      }
    }
    throw new ProcessInspectionException(failure);
  }

  private ProcessState inspectOnce(int pid) throws IOException {
    try {
      Os.kill(pid, 0);
    } catch (ErrnoException error) {
      if (error.errno == OsConstants.ESRCH) return new ProcessState(Kind.GONE, null, "");
      if (error.errno == OsConstants.EPERM || error.errno == OsConstants.EACCES) {
        // A stale PID can be reused by another app between the previous
        // stop and this inspection. Android 16/vendor hidepid policies
        // report EPERM for that case; the proc directory owner lets us
        // quarantine the old record without signalling an unrelated UID.
        Integer owner = processOwner(pid);
        if (owner != null && owner != android.os.Process.myUid())
          return new ProcessState(Kind.OTHER, null, "", true);
        // 只保留 Linux signal-0 的 EPERM 证据。它与 /proc/stat 单独
        // EACCES 不同：后者不能说明目标进程不属于本应用。
        return new ProcessState(
            Kind.DENIED,
            null,
            "",
            false,
            com.deepseekharness.app.util.WebStopEvidence.hiddenUnsignalableCandidate(
                error.errno == OsConstants.EPERM, owner));
      }
      throw new WebStopException(Code.CHECK_FAILED, error, pid, error.errno);
    }
    try {
      WebPidIdentity identity = WebPidIdentity.parse(readProcessFile(pid, "stat"), pid);
      if (identity == null) throw new WebStopException(Code.BIRTH_UNREADABLE);
      if (identity.exited()) return new ProcessState(Kind.GONE, identity, "");
      String command = readProcessFile(pid, "cmdline");
      if (command.isEmpty()) throw new WebStopException(Code.COMMAND_UNREADABLE);
      return new ProcessState(
          WebProcSel.looksLikeWeb(command) ? Kind.WEB : Kind.OTHER, identity, command);
    } catch (IOException error) {
      Integer owner = processOwner(pid);
      if (owner != null && owner != android.os.Process.myUid())
        return new ProcessState(Kind.OTHER, null, "", true);
      try {
        Os.kill(pid, 0);
      } catch (ErrnoException gone) {
        if (gone.errno == OsConstants.ESRCH) return new ProcessState(Kind.GONE, null, "");
      }
      // /proc/stat 为活态之后，退出可能先清空 cmdline，再进入僵尸态；kill(pid,0) 仍成功。
      // 再读内核状态确认退出，不把空命令行当成活进程，也不据此给未知进程发信号。
      try {
        WebPidIdentity after = WebPidIdentity.parse(readProcessFile(pid, "stat"), pid);
        if (after != null && after.exited()) return new ProcessState(Kind.GONE, after, "");
      } catch (IOException ignored) {
      }
      throw new WebStopException(Code.INSPECTION_FAILED, error, pid, error.getMessage());
    }
  }

  private String savedIdentity(int pid) throws IOException {
    File file = identityFile();
    if (Compat.isSymbolicLink(file)) throw new WebStopException(Code.IDENTITY_RECORD_INVALID);
    if (!file.exists()) return null;
    if (!file.isFile() || file.length() > 80)
      throw new WebStopException(Code.IDENTITY_RECORD_VALUE_INVALID);
    ensureOwnerReadable(file);
    String saved = Compat.readAll(file).trim();
    if (!saved.matches(pid + " [1-9][0-9]*")) throw new IOException("WEB_IDENTITY_INVALID");
    return saved;
  }

  private static boolean mayRetire(ProcessState state, String saved) {
    return com.deepseekharness.app.util.WebStopEvidence.mayRetire(
        com.deepseekharness.app.util.WebStopEvidence.Kind.valueOf(state.kind.name()),
        state.differentUid,
        saved,
        state.identity);
  }

  private static boolean scanUnconfirmed(ProcessState state) {
    return com.deepseekharness.app.util.WebStopEvidence.scanUnconfirmed(
        com.deepseekharness.app.util.WebStopEvidence.Kind.valueOf(state.kind.name()),
        state.differentUid);
  }

  /** A hidden foreign PID is not evidence of our Web; a known app-UID denial remains fail-closed. */
  static boolean globalScanUnconfirmed(Kind kind, boolean differentUid, Integer owner, int appUid) {
    if (owner != null && !com.deepseekharness.app.util.WebStopEvidence.scanCandidate(owner, appUid))
      return false;
    if (owner == null && kind != Kind.WEB) return false;
    return scanUnconfirmed(new ProcessState(kind, null, "", differentUid));
  }

  /** 只读确认同 UID 可控进程；绝不按名称批量停止，也不依赖端口反查。 */
  boolean hasOwnedWeb() throws IOException {
    String[] entries = new File("/proc").list();
    if (entries == null) throw new WebStopException(Code.OWNED_WEB_SCAN_UNCONFIRMED);
    for (String value : entries) {
      int pid = WebProcSel.parsePid(value);
      if (pid < 0 || pid == android.os.Process.myPid()) continue;
      // hidepid 可列出其它应用的数字目录，却禁止读取其 uid/cmdline，
      // 这不能当作“本应用还有 Web”的证据。已记录 PID 另由 stopOne/
      // confirmStopped 单独严格核验；全局扫描只检查证实同 UID 的项。
      Integer owner = processOwner(pid);
      if (owner != null
          && !com.deepseekharness.app.util.WebStopEvidence.scanCandidate(
              owner, android.os.Process.myUid())) continue;
      ProcessState state = inspect(pid);
      // uid 不可读时，只有已经完整读到 dsh Web 身份的条目才进入屏障；
      // 单纯的 DENIED/未知系统进程不能把维护永久锁死。
      // 应急进程只有本次出生身份、直启命令和受控工具检查均已登记才可独立运行。
      // 无法读取身份时仍走原有严格屏障，不能凭 profile 名排除未知进程。
      if (state.kind == Kind.WEB
          && com.deepseekharness.app.util.RuntimeInstanceRegistry.shared()
              .isIndependentRecovery(state.identity, state.command)) continue;
      if (globalScanUnconfirmed(state.kind, state.differentUid, owner, android.os.Process.myUid()))
        return true;
    }
    return false;
  }

  /**
   * 回收旧版本可能遗留的隔离试运行。目标必须同时满足：本应用保存过 launched 记录、
   * profile 可由该 UUID 唯一推导、命令行是直接 dsh 试运行，并且两次内核身份一致。
   */
  private WebStopDiagnostic stopRecordedTrialProfiles(java.util.Set<String> profiles) {
    if (profiles.isEmpty()) return null;
    java.util.LinkedHashMap<Integer, ProcessState> targets = new java.util.LinkedHashMap<>();
    try {
      String[] entries = new File("/proc").list();
      if (entries == null) throw new WebStopException(Code.OWNED_WEB_SCAN_UNCONFIRMED);
      for (String value : entries) {
        int pid = WebProcSel.parsePid(value);
        if (pid < 0 || pid == android.os.Process.myPid()) continue;
        Integer owner = processOwner(pid);
        if (owner != null
            && !com.deepseekharness.app.util.WebStopEvidence.scanCandidate(
                owner, android.os.Process.myUid())) continue;
        ProcessState state = inspect(pid);
        if (!globalScanUnconfirmed(
            state.kind, state.differentUid, owner, android.os.Process.myUid())) continue;
        if (state.kind == Kind.DENIED) return WebStopDiagnostic.of(Code.TRIAL_INSPECTION_DENIED);
        String profile = state.kind == Kind.WEB ? WebProcSel.trialProfile(state.command) : "";
        if (!profiles.contains(profile)) continue;
        ProcessState again = inspect(pid);
        if (state.identity == null
            || again.kind != Kind.WEB
            || !state.identity.sameProcess(again.identity)
            || !profile.equals(WebProcSel.trialProfile(again.command)))
          return WebStopDiagnostic.of(Code.TRIAL_IDENTITY_CHANGED);
        try {
          Os.kill(pid, OsConstants.SIGTERM);
        } catch (ErrnoException gone) {
          if (gone.errno != OsConstants.ESRCH) throw gone;
          continue;
        }
        targets.put(pid, state);
      }
      long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
      while (!targets.isEmpty() && android.os.SystemClock.elapsedRealtime() < deadline) {
        java.util.Iterator<java.util.Map.Entry<Integer, ProcessState>> iterator =
            targets.entrySet().iterator();
        while (iterator.hasNext()) {
          java.util.Map.Entry<Integer, ProcessState> target = iterator.next();
          ProcessState current = inspect(target.getKey());
          if (mayRetire(current, target.getValue().identity.record())) {
            iterator.remove();
            continue;
          }
          String profile = WebProcSel.trialProfile(current.command);
          if (current.kind != Kind.WEB || !profiles.contains(profile))
            return WebStopDiagnostic.of(Code.TRIAL_IDENTITY_CHANGED);
        }
        if (!targets.isEmpty()) Thread.sleep(50);
      }
      return targets.isEmpty() ? null : WebStopDiagnostic.of(Code.TRIAL_UNCONFIRMED);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return WebStopDiagnostic.of(Code.STOP_INTERRUPTED);
    } catch (Exception error) {
      return WebStopDiagnostic.failure(Code.TRIAL_STOP_FAILED, error);
    }
  }

  /** 仅隔离仍与本次读取一致的旧记录；保留最后一份编号供诊断。 */
  private void retire(String record) throws IOException {
    if (record == null || !record.equals(pidRecord())) return;
    File stale = new File(root(), ".dsha-web.pid.stale");
    if (Compat.isSymbolicLink(stale)) throw new WebStopException(Code.PID_STALE_LOCATION_INVALID);
    try {
      Os.rename(pidFile().getAbsolutePath(), stale.getAbsolutePath());
    } catch (ErrnoException error) {
      throw new WebStopException(Code.RECORD_RETIRE_FAILED, error);
    }
    File identity = identityFile();
    if (Compat.isSymbolicLink(identity)) throw new WebStopException(Code.IDENTITY_RECORD_INVALID);
    if (identity.isFile() && !identity.delete())
      throw new WebStopException(Code.IDENTITY_RECORD_RETIRE_FAILED);
  }

  public boolean isRunning() {
    try {
      String record = pidRecord();
      return record != null && inspect(WebProcSel.parsePid(record)).kind == Kind.WEB;
    } catch (IOException error) {
      return false;
    }
  }

  /** 权限错误本身不构成放行依据，还须确认启动器已退出且没有本应用 Web。 */
  public boolean confirmStopped(boolean trackedProcessAlive) throws IOException {
    if (trackedProcessAlive) return false;
    if (!root().isDirectory()) return true;
    sentinel();
    String record = pidRecord();
    try {
      if (record != null) {
        int pid = WebProcSel.parsePid(record);
        if (!mayRetire(inspect(pid), savedIdentity(pid))) return false;
      }
      if (hasOwnedWeb()) return false;
    } catch (ProcessInspectionException transientState) {
      return false;
    }
    retire(record);
    return true;
  }

  /** 在鉴权就绪时记录 PID 的启动时刻，后续复用该编号的进程不继承停止权限。 */
  public void recordIdentity() {
    try {
      String record = pidRecord();
      if (record == null) return;
      ProcessState state = inspect(WebProcSel.parsePid(record));
      if (state.kind != Kind.WEB
          || state.identity == null
          || !WebProcSel.maySignalWeb(state.command)) return;
      if (!record.equals(pidRecord())) return;
      File target = identityFile(), temp = new File(root(), ".dsha-web.identity.tmp");
      if (Compat.isSymbolicLink(target) || Compat.isSymbolicLink(temp)) return;
      Compat.write(temp, state.identity.record());
      Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
    } catch (Exception error) {
      proot
          .hostPorts()
          .record(
              "WEB_IDENTITY_RECORD_WRITE_FAILED",
              WebStopDiagnostic.failure(Code.IDENTITY_RECORD_WRITE_FAILED, error).debug());
    }
  }

  /** 主服务与已登记的隔离试运行一起停止，再进行全局无 Web 核验，避免互相卡住停止屏障。 */
  public String stop() {
    return stopResult().detail();
  }

  public StopResult stopResult() {
    return result(stopAll());
  }

  private StopResult result(java.util.List<WebStopDiagnostic> facts) {
    if (facts.isEmpty())
      return new StopResult(StopStatus.STOPPED, "WEB_GUESTS_STOPPED", facts, proot.hostPorts());
    return new StopResult(
        Thread.currentThread().isInterrupted() ? StopStatus.CANCELLED : StopStatus.UNCONFIRMED,
        Thread.currentThread().isInterrupted() ? "WEB_STOP_CANCELLED" : "WEB_GUESTS_UNCONFIRMED",
        facts,
        proot.hostPorts());
  }

  private java.util.List<WebStopDiagnostic> stopAll() {
    java.util.ArrayList<WebStopDiagnostic> errors = new java.util.ArrayList<>();
    WebStopDiagnostic first = stopOne();
    if (first != null) errors.add(first);
    if (records == null)
      try {
        var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
        File files = proot.getRootfsDir().getParentFile().getParentFile().getCanonicalFile();
        File home = fs.child(files, "runtime-trials");
        if (!fs.stat(home).type.equals("MISSING")) {
          java.util.List<String> entries = fs.list(home);
          if (entries.size() > com.deepseekharness.app.backup.BackupLimits.TRANSACTION_RECORDS)
            throw new IOException("TRIAL_RETENTION_LIMIT");
          java.util.LinkedHashSet<String> profiles = new java.util.LinkedHashSet<>();
          for (String id : entries) {
            if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
              throw new IOException("TRIAL_RECORD_DIRECTORY");
            File launched = fs.child(home, id + "/launched");
            String launchedType = fs.stat(launched).type;
            if (launchedType.equals("FILE")) {
              if (!id.equals(new String(fs.small(launched, 128), StandardCharsets.US_ASCII)))
                throw new IOException("TRIAL_MARKER");
              profiles.add("dsha-recovery-" + id.replace("-", "").substring(0, 16));
            } else if (!launchedType.equals("MISSING")) throw new IOException("TRIAL_MARKER");
            File payload = fs.child(home, id + "/payload");
            if (fs.stat(payload).type.equals("MISSING")) continue;
            if (!fs.stat(payload).type.equals("DIRECTORY"))
              throw new IOException("TRIAL_RECORD_DIRECTORY");
            WebStopDiagnostic stopped = new WebProcessManager(proot, payload).stopOne();
            if (stopped != null) errors.add(stopped);
          }
          WebStopDiagnostic stopped = stopRecordedTrialProfiles(profiles);
          if (stopped != null) errors.add(stopped);
        }
      } catch (IOException failure) {
        errors.add(WebStopDiagnostic.failure(Code.TRIAL_STOP_FAILED, failure));
      }
    if (!errors.isEmpty()) return java.util.List.copyOf(errors);
    try {
      return hasOwnedWeb()
          ? java.util.List.of(WebStopDiagnostic.of(Code.OWNED_WEB_REMAINS))
          : java.util.List.of();
    } catch (IOException failure) {
      return java.util.List.of(WebStopDiagnostic.failure(Code.OWNED_WEB_SCAN_UNCONFIRMED, failure));
    }
  }

  /** 仅供本次持有真实 proot Process 对象的试运行使用，不能凭旧 PID 调用。 */
  boolean confirmTrackedTrialStopped(Process tracked) throws IOException {
    if (records == null) throw new IOException("TRIAL_RECORD_DIRECTORY");
    return confirmStopped(!com.deepseekharness.app.util.ProcessTermination.exited(tracked));
  }

  private WebStopDiagnostic stopOne() {
    return WebRecordedStop.run(
        new WebRecordedStop.Io() {
          public boolean rootExists() {
            return root().isDirectory();
          }

          public boolean trial() {
            return records != null;
          }

          public String trialProfile() {
            return "dsha-recovery-"
                + records.getParentFile().getName().replace("-", "").substring(0, 16);
          }

          public long now() {
            return android.os.SystemClock.elapsedRealtime();
          }

          public void pause(long millis) throws InterruptedException {
            Thread.sleep(millis);
          }

          public void sentinel() throws IOException {
            WebProcessManager.this.sentinel();
          }

          public String pidRecord() throws IOException {
            return WebProcessManager.this.pidRecord();
          }

          public String savedIdentity(int pid) throws IOException {
            return WebProcessManager.this.savedIdentity(pid);
          }

          public ProcessState inspect(int pid) throws IOException {
            return WebProcessManager.this.inspect(pid);
          }

          public void retire(String record) throws IOException {
            WebProcessManager.this.retire(record);
          }

          public WebRecordedStop.Signal term(int pid) throws IOException {
            return signalTerm(pid);
          }
        });
  }

  private WebRecordedStop.Signal signalTerm(int pid) throws IOException {
    try {
      Os.kill(pid, OsConstants.SIGTERM);
      return WebRecordedStop.Signal.SENT;
    } catch (ErrnoException denied) {
      if (denied.errno == OsConstants.ESRCH) return WebRecordedStop.Signal.GONE;
      if (denied.errno == OsConstants.EPERM || denied.errno == OsConstants.EACCES) {
        Integer owner = processOwner(pid);
        if (owner != null && owner != android.os.Process.myUid())
          return WebRecordedStop.Signal.FOREIGN_UID;
        return WebRecordedStop.Signal.DENIED;
      }
      throw new IOException(denied.getMessage(), denied);
    }
  }
}
