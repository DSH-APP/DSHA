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
    private final com.deepseekharness.app.util.WebSessionProof.Saved recorded;
    private final boolean retirePreviousLauncher;

    private ForceCandidate(com.deepseekharness.app.util.WebForceStop.Candidate proof) {
      this(proof, null, false);
    }

    private ForceCandidate(
        com.deepseekharness.app.util.WebSessionProof.Saved recorded,
        boolean retirePreviousLauncher) {
      this(null, recorded, retirePreviousLauncher);
    }

    private ForceCandidate(
        com.deepseekharness.app.util.WebForceStop.Candidate proof,
        com.deepseekharness.app.util.WebSessionProof.Saved recorded,
        boolean retirePreviousLauncher) {
      this.proof = proof;
      this.recorded = recorded;
      this.retirePreviousLauncher = retirePreviousLauncher;
    }

    public int pid() {
      return recorded != null ? recorded.webPid() : proof.pid();
    }

    public String generation() {
      return recorded != null ? Long.toString(recorded.generation()) : proof.generation();
    }

    /** 覆盖更新弃旧版：用户确认后还会结束记录中的上一版容器启动器。 */
    public boolean retiresPreviousLauncher() {
      return retirePreviousLauncher;
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

  /**
   * 覆盖安装后 App 已被结束、PID/身份文件不可用时，按会话记录里的出生身份准备候选。
   *
   * <p>只有记录与当前进程完全一致（同一 boot、PID、出生时间、命令行、代次/实例/nonce）才返回候选；
   * 记录缺失、PID 复用或任一项不匹配都拒绝，不发送任何信号。
   */
  public ForceCandidate prepareRecordedForceStop(
      com.deepseekharness.app.util.WebSessionProof.Saved saved) throws IOException {
    if (records != null)
      throw new WebStopException(Code.FORCE_EVIDENCE_UNCONFIRMED, "TRIAL_FORBIDDEN");
    if (saved == null) return null;
    if (observeRecordedSession(saved) != SessionObservation.LIVE)
      throw new WebStopException(Code.RECORDED_SESSION_UNCONFIRMED);
    return new ForceCandidate(saved, false);
  }

  /**
   * 覆盖更新弃旧版：只有当前已装 APK 与记录中的会话身份不同（上一实例）时，调用方才应准备本候选。
   *
   * <p>guest 与旧启动器各自连读两次出生身份：只有同一结论才继续；任何一次 UNKNOWN（PID 复用、
   * 出生时间 / 命令行 / UID / 代次 / 实例 / nonce 不符或环境不可读）都不发送任何信号。guest 已退出时
   * 允许只为旧启动器准备候选；两者都已退出返回 {@code null}。
   */
  public ForceCandidate prepareOverwriteRetire(
      com.deepseekharness.app.util.WebSessionProof.Saved saved) throws IOException {
    if (records != null)
      throw new WebStopException(Code.FORCE_EVIDENCE_UNCONFIRMED, "TRIAL_FORBIDDEN");
    if (saved == null) return null;
    SessionObservation guestFirst = observeRecordedSession(saved);
    SessionObservation guestSecond = observeRecordedSession(saved);
    if (guestFirst != guestSecond || guestFirst == SessionObservation.UNKNOWN)
      throw new WebStopException(Code.RECORDED_SESSION_UNCONFIRMED);
    com.deepseekharness.app.util.WebProofStop.Observation launcherFirst =
        observeRecordedLauncher(saved);
    com.deepseekharness.app.util.WebProofStop.Observation launcherSecond =
        observeRecordedLauncher(saved);
    if (launcherFirst != launcherSecond
        || launcherFirst == com.deepseekharness.app.util.WebProofStop.Observation.UNKNOWN)
      throw new WebStopException(Code.RECORDED_SESSION_UNCONFIRMED);
    if (guestFirst == SessionObservation.EXITED
        && launcherFirst == com.deepseekharness.app.util.WebProofStop.Observation.EXITED)
      return null;
    return new ForceCandidate(saved, true);
  }

  /**
   * 只读核对记录中的上一版启动器；退出或编号已复用是退出证明，读不了是不匹配。
   *
   * <p>覆盖更新后旧启动器仍存活时，它不属于本次实例，只能凭记录里的完整出生身份识别；这里不发送
   * 信号，也不按名称 / 端口 / 进程组扫描。
   */
  com.deepseekharness.app.util.WebProofStop.Observation observeRecordedLauncher(
      com.deepseekharness.app.util.WebSessionProof.Saved saved) {
    if (saved == null || records != null)
      return com.deepseekharness.app.util.WebProofStop.Observation.UNKNOWN;
    try {
      if (!saved.boot().equals(recoveryBoot()))
        return com.deepseekharness.app.util.WebProofStop.Observation.EXITED;
      var launcher = recoveryProcess(saved.launcherPid());
      if (!launcher.alive() || launcher.started() != saved.launcherStarted())
        return com.deepseekharness.app.util.WebProofStop.Observation.EXITED;
      return com.deepseekharness.app.util.WebSessionProof.matchesLauncher(
              saved, recoveryHostUid(), launcher)
          ? com.deepseekharness.app.util.WebProofStop.Observation.LIVE
          : com.deepseekharness.app.util.WebProofStop.Observation.UNKNOWN;
    } catch (IOException | RuntimeException unavailable) {
      if (recordedProcessExited(saved.launcherPid(), saved.launcherStarted()))
        return com.deepseekharness.app.util.WebProofStop.Observation.EXITED;
      return com.deepseekharness.app.util.WebProofStop.Observation.UNKNOWN;
    }
  }

  /**
   * 只读核对会话记录里的 Web 是否仍是当前进程。与 {@link #verifySession} 的区别是不再要求
   * `/root/.dsha-web.identity` 文件存在：覆盖安装后文件可能缺失，但完整出生身份仍可自证。
   */
  public SessionObservation observeRecordedSession(
      com.deepseekharness.app.util.WebSessionProof.Saved saved) {
    if (saved == null || records != null) return SessionObservation.UNKNOWN;
    try {
      String boot = recoveryBoot();
      if (!saved.boot().equals(boot)) return SessionObservation.EXITED;
      var web = recoveryProcess(saved.webPid());
      if (!web.alive() || web.started() != saved.webStarted()) return SessionObservation.EXITED;
      var launcher = recoveryProcess(saved.launcherPid());
      return com.deepseekharness.app.util.WebSessionProof.matches(
              saved, boot, recoveryHostUid(), web, launcher)
          ? SessionObservation.LIVE
          : SessionObservation.UNKNOWN;
    } catch (IOException | RuntimeException unavailable) {
      // 读写错误不等于退出；只有内核身份已变化才可作为退出证明。
      if (recordedProcessExited(saved.webPid(), saved.webStarted()))
        return SessionObservation.EXITED;
      return SessionObservation.UNKNOWN;
    }
  }

  /**
   * 启动恢复与前后台复核专用：先按 PID / 身份文件严格核验；文件缺失、陈旧或被退休（UNKNOWN）时，
   * 改用保存记录里的完整出生身份（boot / PID / 出生时间 / 命令行 / UID / generation+instance+nonce）
   * 再判定一次。
   *
   * <p>严格观察已经给出 LIVE / EXITED 时不再降级，避免同一结论出现两套证据；证明版也读不到时保持
   * UNKNOWN，绝不把不确定当作退出或可启动依据。退出证明仍只来自 boot 结束、编号退出或出生变化。
   */
  public SessionObservation observeRecoveredSession(
      com.deepseekharness.app.util.WebSessionProof.Saved saved) {
    SessionObservation strict = observeSession(saved);
    if (strict != SessionObservation.UNKNOWN) return strict;
    return observeRecordedSession(saved);
  }

  public StopResult forceStopResult(ForceCandidate candidate) throws IOException {
    if (records != null)
      throw new WebStopException(Code.FORCE_EVIDENCE_UNCONFIRMED, "TRIAL_FORBIDDEN");
    if (candidate != null && candidate.recorded != null)
      return forceRecordedStop(candidate.recorded, candidate.retirePreviousLauncher);
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

  /**
   * 按会话记录里的出生身份结束同一 guest；覆盖更新弃旧版时，guest 确认退出后再按同一份出生身份结束
   * 该记录对应的旧容器启动器。核验不过不发送任何信号；普通停止 / 试运行路径不放宽。
   */
  private StopResult forceRecordedStop(
      com.deepseekharness.app.util.WebSessionProof.Saved saved, boolean retireLauncher)
      throws IOException {
    try {
      sentinel();
      var clock =
          new com.deepseekharness.app.util.WebProofStop.Clock() {
            public long now() {
              return android.os.SystemClock.elapsedRealtime();
            }

            public void pause(long millis) throws InterruptedException {
              Thread.sleep(millis);
            }
          };
      var guest =
          com.deepseekharness.app.util.WebProofStop.run(
              true,
              () ->
                  switch (observeRecordedSession(saved)) {
                    case LIVE -> com.deepseekharness.app.util.WebProofStop.Observation.LIVE;
                    case EXITED -> com.deepseekharness.app.util.WebProofStop.Observation.EXITED;
                    case UNKNOWN -> com.deepseekharness.app.util.WebProofStop.Observation.UNKNOWN;
                  },
              () -> signalProofTerm(saved.webPid()),
              () -> signalKill(saved.webPid()),
              clock);
      com.deepseekharness.app.util.WebProofStop.Outcome launcher = null;
      // 先结束 guest，再结束旧启动器；guest 未确认退出时绝不碰启动器。
      if (guest.stopped() && retireLauncher)
        launcher =
            com.deepseekharness.app.util.WebProofStop.run(
                true,
                () -> observeRecordedLauncher(saved),
                () -> signalProofTerm(saved.launcherPid()),
                () -> signalKill(saved.launcherPid()),
                clock);
      StopResult barrier = result(stopAll());
      if (barrier.stopped()) return barrier;
      java.util.List<WebStopDiagnostic> facts = new java.util.ArrayList<>();
      if (guest.diagnostic() != null) facts.add(guest.diagnostic());
      if (launcher != null && launcher.diagnostic() != null) facts.add(launcher.diagnostic());
      if (launcher != null && !launcher.stopped())
        facts.add(WebStopDiagnostic.of(Code.PREVIOUS_LAUNCHER_UNCONFIRMED));
      facts.addAll(barrier.diagnostics());
      return new StopResult(
          StopStatus.UNCONFIRMED, "WEB_RECORDED_STOP_UNCONFIRMED", facts, proot.hostPorts());
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

  File root() {
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
  Integer processOwner(int pid) {
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
    String[] entries = recoveryProcessEntries();
    if (entries == null) throw new WebStopException(Code.OWNED_WEB_SCAN_UNCONFIRMED);
    int appUid = recoveryHostUid();
    for (String value : entries) {
      int pid = WebProcSel.parsePid(value);
      if (pid < 0 || pid == recoveryHostPid()) continue;
      // hidepid 可列出其它应用的数字目录，却禁止读取其 uid/cmdline，
      // 这不能当作“本应用还有 Web”的证据。已记录 PID 另由 stopOne/
      // confirmStopped 单独严格核验；全局扫描只检查证实同 UID 的项。
      Integer owner = processOwner(pid);
      if (owner != null
          && !com.deepseekharness.app.util.WebStopEvidence.scanCandidate(owner, appUid)) continue;
      ProcessState state = inspect(pid);
      // uid 不可读时，只有已经完整读到 dsh Web 身份的条目才进入屏障；
      // 单纯的 DENIED/未知系统进程不能把维护永久锁死。
      // 应急进程只有本次出生身份、直启命令和受控工具检查均已登记才可独立运行。
      // 无法读取身份时仍走原有严格屏障，不能凭 profile 名排除未知进程。
      if (state.kind == Kind.WEB
          && com.deepseekharness.app.util.RuntimeInstanceRegistry.shared()
              .isIndependentRecovery(state.identity, state.command)) continue;
      if (globalScanUnconfirmed(state.kind, state.differentUid, owner, appUid)) {
        // A denied read still blocks maintenance, but it is not proof that a Web is running.
        if (state.kind == Kind.DENIED) throw new WebStopException(Code.OWNED_WEB_SCAN_UNCONFIRMED);
        return true;
      }
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

  /**
   * 只回收指定试运行 profile：只接受直接 dsh 试运行命令、同 UID 与两次一致出生身份，绝不按端口 /
   * 名称扫描。正常返回即表示当前已无该 profile 的进程；拒读、身份变化或退出未确认都抛错保留现场。
   */
  public void retireRecordedTrialProfile(String profile) throws IOException {
    if (records != null) throw new IOException("TRIAL_PROFILE_SCOPE");
    if (profile == null || !profile.matches("dsha-recovery-[0-9a-f]{16}"))
      throw new IOException("TRIAL_PROFILE");
    WebStopDiagnostic diagnostic =
        stopRecordedTrialProfiles(new java.util.LinkedHashSet<>(java.util.List.of(profile)));
    if (diagnostic != null) throw new WebStopException(diagnostic, null);
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

  public boolean hasRecordedSession() throws IOException {
    return pidRecord() != null;
  }

  /** 宿主重建后只读核验仍活跃实例的内核身份。 */
  com.deepseekharness.app.util.WebSessionProof.Process recoveryProcess(int pid) throws IOException {
    ProcessState state = inspect(pid);
    if (state.kind == Kind.GONE)
      return new com.deepseekharness.app.util.WebSessionProof.Process(
          pid, 0, -1, "", "", "", "", false);
    Integer uid = processOwner(pid);
    // 进程存活期间不会换 UID：编号已属于其它 UID，说明记录里的本应用进程已退出、编号被复用。
    // 与 inspectOnce 的 differentUid 判据一致；只用于退出证明，不据此发送任何信号。
    // 覆盖安装后旧启动器 PID 常被其它应用复用，若按“不可读”处理，停止屏障会一直不放行。
    if (state.differentUid || (uid != null && uid != recoveryHostUid()))
      return new com.deepseekharness.app.util.WebSessionProof.Process(
          pid, 0, -1, "", "", "", "", false);
    if (uid == null || state.identity == null || state.kind == Kind.DENIED)
      throw new IOException("WEB_SESSION_PROCESS_UNREADABLE");
    java.util.Map<String, String> env = new java.util.HashMap<>();
    String environment =
        sessionEvidence("WEB_SESSION_ENV_UNREADABLE", () -> readProcessFile(pid, "environ"));
    for (String value : environment.split("\u0000")) {
      int equals = value.indexOf('=');
      if (equals < 1) continue;
      String key = value.substring(0, equals);
      if (!java.util.Set.of("DSHA_WEB_GENERATION", "DSH_TASK_INSTANCE", "DSH_TASK_TOKEN")
          .contains(key)) continue;
      if (env.put(key, value.substring(equals + 1)) != null)
        throw new IOException("WEB_SESSION_ENV_DUPLICATE");
    }
    ProcessState again = inspect(pid);
    if (again.identity == null
        || !state.identity.sameProcess(again.identity)
        || !state.command.equals(again.command)
        || !uid.equals(processOwner(pid))) throw new IOException("WEB_SESSION_PROCESS_CHANGED");
    return new com.deepseekharness.app.util.WebSessionProof.Process(
        pid,
        state.identity.started,
        uid,
        state.command,
        env.get("DSHA_WEB_GENERATION"),
        env.get("DSH_TASK_INSTANCE"),
        env.get("DSH_TASK_TOKEN"),
        true);
  }

  String recoveryBoot() throws IOException {
    String boot;
    boot =
        sessionEvidence(
            "WEB_SESSION_BOOT_UNREADABLE",
            () -> {
              try (var input = new FileInputStream("/proc/sys/kernel/random/boot_id")) {
                byte[] bytes = new byte[81];
                int count = input.read(bytes);
                if (count < 1 || count == bytes.length)
                  throw new IOException("WEB_SESSION_BOOT_LIMIT");
                return new String(bytes, 0, count, StandardCharsets.US_ASCII).trim();
              }
            });
    if (!boot.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
      throw new IOException("WEB_SESSION_BOOT_UNREADABLE");
    return boot;
  }

  public com.deepseekharness.app.util.WebSessionProof.Saved captureSession(
      long generation, String instance, String nonce, Process launcher) throws IOException {
    if (records != null || launcher == null) throw new IOException("WEB_SESSION_LAUNCHER_MISSING");
    String record = sessionEvidence("WEB_SESSION_PID_RECORD", this::pidRecord);
    int pid = WebProcSel.parsePid(record);
    if (pid <= 1) throw new IOException("WEB_SESSION_PID_RECORD_INVALID");
    var web = sessionEvidence("WEB_SESSION_GUEST_PROCESS", () -> recoveryProcess(pid));
    int launcherPid =
        com.deepseekharness.app.util.ProcessIdentity.androidPid(
            launcher.getClass().getName(), launcher.toString());
    if (launcherPid <= 1) throw new IOException("WEB_SESSION_LAUNCHER_PID_INVALID");
    var parent =
        sessionEvidence("WEB_SESSION_LAUNCHER_PROCESS", () -> recoveryProcess(launcherPid));
    var saved =
        new com.deepseekharness.app.util.WebSessionProof.Saved(
            recoveryBoot(),
            generation,
            instance,
            nonce,
            pid,
            web.started(),
            web.command(),
            launcherPid,
            parent.started(),
            parent.command());
    if (!record.equals(pidRecord())
        || !web.alive()
        || !parent.alive()
        || !sessionEvidence("WEB_SESSION_CAPTURE_VERIFY", () -> verifySession(saved)))
      throw new IOException("WEB_SESSION_CAPTURE_UNCONFIRMED");
    return saved;
  }

  @FunctionalInterface
  private interface SessionEvidence<T> {
    T read() throws IOException;
  }

  private static <T> T sessionEvidence(String code, SessionEvidence<T> read) throws IOException {
    try {
      return read.read();
    } catch (IOException unavailable) {
      throw new IOException(code, unavailable);
    }
  }

  public boolean verifySession(com.deepseekharness.app.util.WebSessionProof.Saved saved)
      throws IOException {
    if (saved == null || records != null) return false;
    String record = pidRecord();
    if (WebProcSel.parsePid(record) != saved.webPid()
        || !java.util.Objects.equals(
            savedIdentity(saved.webPid()), saved.webPid() + " " + saved.webStarted())) return false;
    var web = recoveryProcess(saved.webPid());
    var launcher = recoveryProcess(saved.launcherPid());
    return record.equals(pidRecord())
        && com.deepseekharness.app.util.WebSessionProof.matches(
            saved, recoveryBoot(), android.os.Process.myUid(), web, launcher);
  }

  public enum SessionObservation {
    LIVE,
    UNKNOWN,
    EXITED
  }

  /** 拒读或标记不匹配只表示未知；只有原boot结束、原PID退出或出生变化才能确认原guest退出。 */
  public SessionObservation observeSession(
      com.deepseekharness.app.util.WebSessionProof.Saved saved) {
    if (saved == null || records != null) return SessionObservation.UNKNOWN;
    try {
      if (!saved.boot().equals(recoveryBoot())) return SessionObservation.EXITED;
      var web = recoveryProcess(saved.webPid());
      if (!web.alive() || web.started() != saved.webStarted()) return SessionObservation.EXITED;
      return verifySession(saved) ? SessionObservation.LIVE : SessionObservation.UNKNOWN;
    } catch (IOException | RuntimeException unavailable) {
      // An unrelated replacement may hide environ even though its readable birth already
      // proves the saved guest exited. Recheck only that narrow exit fact; never adopt it.
      if (recordedProcessExited(saved.webPid(), saved.webStarted()))
        return SessionObservation.EXITED;
      return SessionObservation.UNKNOWN;
    }
  }

  /** 未知继续阻挡；PID已复用只证明记录中的原启动器已退出。 */
  public boolean recoveredLauncherExited(com.deepseekharness.app.util.WebSessionProof.Saved saved)
      throws IOException {
    if (saved == null) return true;
    if (!saved.boot().equals(recoveryBoot())) return true;
    try {
      var process = recoveryProcess(saved.launcherPid());
      return !process.alive() || process.started() != saved.launcherStarted();
    } catch (IOException unavailable) {
      if (recordedProcessExited(saved.launcherPid(), saved.launcherStarted())) return true;
      throw unavailable;
    }
  }

  /** Exit proof needs the saved birth, never the replacement process's environment or token. */
  private boolean recordedProcessExited(int pid, long started) {
    if (pid <= 1 || started <= 0) return false;
    try {
      ProcessState current = inspect(pid);
      return current.kind == Kind.GONE
          || current.differentUid
          || current.identity != null && current.identity.started != started;
    } catch (IOException | RuntimeException unavailable) {
      return false;
    }
  }

  /**
   * 已确认原 guest 与启动器都退出后，隔离仍指向它的旧 PID 记录；不发送任何信号。
   *
   * <p>退出结论与恢复路径共用证明版观察：严格文件核验为 UNKNOWN 时仍可按出生身份确认退出，
   * 但只有 boot 结束、编号退出或出生变化才能退休记录。
   *
   * <p>PID 记录已被换成其它实例时保留现场并返回 false；记录已不在时视为已完成。
   * 只有与本次证明完全一致的旧记录才会被改名，异步检查不会误删新实例的记录。
   */
  public boolean retireConfirmedExit(com.deepseekharness.app.util.WebSessionProof.Saved saved)
      throws IOException {
    if (saved == null || records != null) return false;
    if (observeRecoveredSession(saved) != SessionObservation.EXITED) return false;
    if (!recoveredLauncherExited(saved)) return false;
    String record = pidRecord();
    if (record == null) return true;
    if (WebProcSel.parsePid(record) != saved.webPid()) return false;
    retire(record);
    return pidRecord() == null;
  }

  /** 旧记录无法解密时，只读核对同UID的Web启动器已全部消失；绝不按名称发送信号。 */
  public boolean confirmNoUntrackedWebLaunchers() throws IOException {
    String[] entries = recoveryProcessEntries();
    if (entries == null) throw new IOException("WEB_SESSION_LAUNCHER_SCAN_UNREADABLE");
    for (String entry : entries) {
      int pid = WebProcSel.parsePid(entry);
      if (pid < 0 || pid == recoveryHostPid()) continue;
      Integer uid = processOwner(pid);
      if (uid == null || uid != recoveryHostUid()) continue;
      ProcessState value = inspect(pid);
      if (value.kind == Kind.GONE) continue;
      if (value.kind == Kind.DENIED || value.identity == null)
        throw new IOException("WEB_SESSION_LAUNCHER_SCAN_UNCONFIRMED");
      String[] argv = value.command.split("\u0000");
      if (argv.length == 0) throw new IOException("WEB_SESSION_LAUNCHER_COMMAND_UNREADABLE");
      String name = new File(argv[0]).getName();
      if (!(name.startsWith("libproot")
          || name.startsWith("libproroot")
          || name.startsWith("libdsha-proroot"))) continue;
      // 此处只证明“没有”，不授予signal权限。旧记录缺少可信标记时，
      // 同UID的任何此类启动器都保留屏障；不能把未识别的终端或旧argv当作已经退出。
      return false;
    }
    return true;
  }

  String[] recoveryProcessEntries() {
    return new File("/proc").list();
  }

  int recoveryHostPid() {
    return android.os.Process.myPid();
  }

  int recoveryHostUid() {
    return android.os.Process.myUid();
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

  /**
   * 证明版观察确认 guest 仍是保存记录里的实例后，按该出生身份补写 PID / 身份文件。
   *
   * <p>恢复接回可能发生在旧文件缺失、陈旧或被退休之后；只补证据，不授予启动 / 停止权限。
   * PID 记录缺失或已指向同一编号时才补写，指向其它编号（可能是更新的实例）时原样保留；
   * 身份文件只在内容与「PID 出生时间」不一致时补写。写失败只上报诊断，后续仍可走证明版观察。
   */
  public boolean attestRecoveredSession(com.deepseekharness.app.util.WebSessionProof.Saved saved) {
    if (saved == null || records != null) return false;
    try {
      if (!saved.boot().equals(recoveryBoot())) return false;
      if (observeRecordedSession(saved) != SessionObservation.LIVE) return false;
      String record = readEvidence(pidFile(), 32);
      int recorded = record == null ? -1 : WebProcSel.parsePid(record);
      if (recorded > 0 && recorded != saved.webPid()) return false;
      String expectedIdentity = saved.webPid() + " " + saved.webStarted();
      boolean identityCurrent = expectedIdentity.equals(readEvidence(identityFile(), 80));
      if (recorded == saved.webPid() && identityCurrent) return true;
      if (recorded != saved.webPid())
        writeEvidence(pidFile(), ".dsha-web.pid.tmp", saved.webPid() + "\n");
      if (!identityCurrent)
        writeEvidence(identityFile(), ".dsha-web.identity.tmp", expectedIdentity + "\n");
      return true;
    } catch (Exception error) {
      proot
          .hostPorts()
          .record(
              "WEB_SESSION_ATTEST_WRITE_FAILED",
              WebStopDiagnostic.failure(Code.IDENTITY_RECORD_WRITE_FAILED, error).debug());
      return false;
    }
  }

  /** 证据文件只接受普通文件且不跟随链接；缺失返回 null，超长或链接视为不可用。 */
  private static String readEvidence(File file, int maxBytes) throws IOException {
    if (Compat.isSymbolicLink(file)) throw new IOException("WEB_SESSION_EVIDENCE_LINK");
    if (!file.exists()) return null;
    if (!file.isFile() || file.length() > maxBytes)
      throw new IOException("WEB_SESSION_EVIDENCE_INVALID");
    return Compat.readAll(file).trim();
  }

  /** 先写同目录临时文件再改名；目标与临时文件都不接受符号链接。 */
  private void writeEvidence(File target, String tempName, String value) throws IOException {
    File temp = new File(root(), tempName);
    if (Compat.isSymbolicLink(target) || Compat.isSymbolicLink(temp))
      throw new IOException("WEB_SESSION_EVIDENCE_LINK");
    Compat.write(temp, value);
    if (!temp.renameTo(target)) throw new IOException("WEB_SESSION_EVIDENCE_WRITE");
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

  /**
   * 试用收尾只按本记录 guest 的退出证据确认。
   *
   * <p>proroot 启动器在 Android 16 上可能在 guest 退出后晚退甚至残留；若把启动器 Process 对象仍活
   * 当成“试用进程未确认”，一次正常的试用也会留下半提交事务与无法自愈的 trial 记录。这里改为核验
   * 本记录 PID 已退休且没有本应用 Web 仍在运行；启动器本身由 {@code RuntimeTrial} 用本次持有的
   * Process 句柄做一次有界清理，不按 PID / 名称扫描。
   */
  boolean confirmTrackedTrialStopped(Process tracked) throws IOException {
    if (records == null || tracked == null) throw new IOException("TRIAL_RECORD_DIRECTORY");
    return confirmStopped(false);
  }

  private WebStopDiagnostic stopOne() {
    return WebRecordedStop.run(recordedStopIo(false));
  }

  /**
   * 只给隔离试运行收尾使用：本轮 payload 记录的 guest 在优雅停止窗口内没有退出时，按记录、
   * 本轮唯一 profile 与保存的出生身份复核后 SIGKILL，并做有界退出确认。
   *
   * <p>不扫描 /proc、不按 PID 或名称结束其它进程，也不作用于主 Web；返回值只用于诊断，
   * 是否真的停止由随后一次 {@link #stop()} / {@link #confirmStopped(boolean)} 决定。
   *
   * @return 空串表示记录已确认退出（或本来就没有记录 / 已退休）；否则是可导出的诊断码。
   */
  String forceStopTrialGuest() {
    if (records == null) return "WEB_FORCE_TRIAL_FORBIDDEN";
    try {
      WebStopDiagnostic forced = WebRecordedStop.run(recordedStopIo(true));
      return forced == null ? "" : SensitiveData.redact(forced.debug());
    } catch (RuntimeException error) {
      return SensitiveData.redact(WebStopDiagnostic.failure(Code.TRIAL_STOP_FAILED, error).debug());
    }
  }

  /** 生产记录停止的唯一适配器；force 只换信号并加出生身份复核，其它判据共用。 */
  private WebRecordedStop.Io recordedStopIo(boolean force) {
    return new WebRecordedStop.Io() {
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

      public boolean requireSavedBirth() {
        return force;
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
        return signal(pid, force ? OsConstants.SIGKILL : OsConstants.SIGTERM);
      }
    };
  }

  private com.deepseekharness.app.util.WebProofStop.Signal signalProofTerm(int pid)
      throws IOException {
    return proofSignal(pid, OsConstants.SIGTERM);
  }

  private com.deepseekharness.app.util.WebProofStop.Signal signalKill(int pid) throws IOException {
    return proofSignal(pid, OsConstants.SIGKILL);
  }

  private com.deepseekharness.app.util.WebProofStop.Signal proofSignal(int pid, int signal)
      throws IOException {
    return switch (signal(pid, signal)) {
      case SENT -> com.deepseekharness.app.util.WebProofStop.Signal.SENT;
      case GONE -> com.deepseekharness.app.util.WebProofStop.Signal.GONE;
      case FOREIGN_UID -> com.deepseekharness.app.util.WebProofStop.Signal.FOREIGN_UID;
      case DENIED -> com.deepseekharness.app.util.WebProofStop.Signal.DENIED;
    };
  }

  private WebRecordedStop.Signal signal(int pid, int signal) throws IOException {
    try {
      Os.kill(pid, signal);
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
