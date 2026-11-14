package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.WebStopDiagnostic;
import com.deepseekharness.app.util.WebStopDiagnostic.Code;
import com.deepseekharness.app.util.WebProcSel;
import com.deepseekharness.app.util.WebStopEvidence;
import java.io.IOException;

/** The production recorded-Web SIGTERM algorithm; platform I/O has one explicit adapter. */
final class WebRecordedStop {
  enum Signal {
    SENT,
    GONE,
    FOREIGN_UID,
    DENIED
  }

  interface Io {
    boolean rootExists();

    boolean trial();

    String trialProfile();

    long now();

    void pause(long millis) throws InterruptedException;

    void sentinel() throws IOException;

    String pidRecord() throws IOException;

    String savedIdentity(int pid) throws IOException;

    /**
     * 强制结束模式必须复核保存的出生身份：缺记录或对不上时不发 SIGKILL。
     *
     * <p>普通停止只按记录 + 命令行核验；强杀是不可逆动作，多一层出生身份绑定。默认关闭，
     * 只有隔离试运行收尾的强制路径打开。
     */
    default boolean requireSavedBirth() {
      return false;
    }

    WebProcessManager.ProcessState inspect(int pid) throws IOException;

    void retire(String record) throws IOException;

    Signal term(int pid) throws IOException;
  }

  private WebRecordedStop() {}

  private static WebStopEvidence.Kind kind(WebProcessManager.ProcessState state) {
    return WebStopEvidence.Kind.valueOf(state.kind.name());
  }

  private static boolean mayRetire(WebProcessManager.ProcessState state, String saved) {
    return WebStopEvidence.mayRetire(kind(state), state.differentUid, saved, state.identity);
  }

  static WebStopDiagnostic run(Io io) {
    if (!io.rootExists()) return null;
    long retryUntil = io.now() + com.deepseekharness.app.util.WebStopBudget.INSPECTION_RETRY_MILLIS;
    while (true) {
      try {
        io.sentinel();
        String record = io.pidRecord();
        if (record == null) return null;
        int pid = WebProcSel.parsePid(record);
        WebProcessManager.ProcessState state = io.inspect(pid);
        String saved = io.savedIdentity(pid);
        if (mayRetire(state, saved)) {
          io.retire(record);
          return null;
        }
        // 强杀路径要求出生身份也可核对：PID 记录被换掉时宁可保留现场也不发 SIGKILL。
        if (io.requireSavedBirth()
            && (saved == null || state.identity == null || !state.identity.matches(saved)))
          return WebStopDiagnostic.of(Code.TRIAL_IDENTITY_CHANGED, "birth-evidence");
        if (!io.trial()
            && WebStopEvidence.mayRetireUnsignalableRecord(
                kind(state), state.signalProbeForbidden, pid, saved)) {
          WebProcessManager.ProcessState again = io.inspect(pid);
          if (!record.equals(io.pidRecord())
              || !saved.equals(io.savedIdentity(pid))
              || !WebStopEvidence.mayRetireUnsignalableRecord(
                  kind(again), again.signalProbeForbidden, pid, saved))
            return WebStopDiagnostic.of(Code.IDENTITY_CHANGED);
          io.retire(record);
          return null;
        }
        if (state.kind != WebProcessManager.Kind.WEB || state.identity == null)
          return WebStopDiagnostic.of(Code.INSPECTION_UNCONFIRMED, state.kind);
        if (io.trial() && !state.command.contains(io.trialProfile()))
          return WebStopDiagnostic.of(Code.TRIAL_IDENTITY_CHANGED);
        if (!WebProcSel.maySignalWeb(state.command))
          return WebStopDiagnostic.of(Code.PRE_EXEC_WAIT);
        WebProcessManager.ProcessState again = io.inspect(pid);
        if (!state.identity.sameProcess(again.identity)
            || !WebProcSel.maySignalWeb(again.command)
            || !record.equals(io.pidRecord())) return WebStopDiagnostic.of(Code.IDENTITY_CHANGED);
        Signal signal = io.term(pid);
        if (signal == Signal.GONE || signal == Signal.FOREIGN_UID) {
          io.retire(record);
          return null;
        }
        if (signal == Signal.DENIED) return WebStopDiagnostic.of(Code.SIGNAL_DENIED);
        return awaitExit(io, pid, state.identity, record);
      } catch (WebProcessManager.ProcessInspectionException error) {
        if (io.now() >= retryUntil) return WebStopDiagnostic.failure(Code.STOP_FAILED, error);
        try {
          io.pause(50);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return WebStopDiagnostic.of(Code.STOP_INTERRUPTED);
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return WebStopDiagnostic.of(Code.STOP_INTERRUPTED);
      } catch (Exception error) {
        return WebStopDiagnostic.failure(Code.STOP_FAILED, error);
      }
    }
  }

  /**
   * SIGTERM 之后按同一出生身份继续核验到预算尽头，绝不在 3 秒处提前收口。
   *
   * <p>内置 dsh 自己在收到 SIGTERM 后的优雅退出强制上限是 5 秒；宿主只等 3 秒必然把正常关闭
   * 判成“退出未确认”，随后的启动前置停止又会把这段“正在退出”当成失败。这里的结论分开处理：
   * 进程消失或编号已被复用 → 退休原记录并返回 {@code null}；身份/命令行在等待中变化 → 返回
   * {@link Code#IDENTITY_CHANGED} 且不再发任何信号；仍可核验存活或暂时不可读 → 到预算尽头才报
   * {@link Code#EXIT_UNCONFIRMED}。等待在调用线程（dsh-io）进行，不会占用主线程。
   */
  private static WebStopDiagnostic awaitExit(
      Io io, int pid, com.deepseekharness.app.util.WebPidIdentity identity, String record)
      throws IOException, InterruptedException {
    long deadline = io.now() + com.deepseekharness.app.util.WebStopBudget.EXIT_CONFIRM_MILLIS;
    WebStopDiagnostic pending = WebStopDiagnostic.of(Code.EXIT_UNCONFIRMED, pid, "仍在运行");
    while (true) {
      try {
        WebProcessManager.ProcessState current = io.inspect(pid);
        // 消失、换 UID 或编号被新进程复用都是退出证明；只有同一出生身份仍存活才继续等。
        if (mayRetire(current, identity.record())) {
          io.retire(record);
          return null;
        }
        if (current.kind == WebProcessManager.Kind.WEB
            && current.identity != null
            && (!identity.sameProcess(current.identity)
                || !WebProcSel.maySignalWeb(current.command)))
          return WebStopDiagnostic.of(Code.IDENTITY_CHANGED, pid);
        pending =
            current.kind == WebProcessManager.Kind.DENIED
                ? WebStopDiagnostic.of(Code.EXIT_UNCONFIRMED, pid, "身份暂不可读")
                : WebStopDiagnostic.of(Code.EXIT_UNCONFIRMED, pid, "仍在运行");
      } catch (WebProcessManager.ProcessInspectionException transientState) {
        // 退出瞬间 /proc 读取可能短暂失败；读不了不等于退出，继续等到预算尽头。
        pending = WebStopDiagnostic.failure(Code.EXIT_UNCONFIRMED, transientState, pid);
      }
      long now = io.now();
      if (now >= deadline) return pending;
      io.pause(Math.min(50, deadline - now));
    }
  }
}
