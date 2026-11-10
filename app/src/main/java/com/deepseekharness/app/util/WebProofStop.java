package com.deepseekharness.app.util;

import java.io.IOException;

/**
 * 按已保存的出生身份回收上一实例残留 Web 的纯逻辑。覆盖安装后 App 已被结束，旧 node 仍可能存活；
 * 本类只在“两次完整核验都确认是同一进程”时先请求退出，允许强制时再复核一次后结束该 PID。
 *
 * <p>调用方负责把 {@link WebSessionProof.Saved} 与当前进程核对成 {@link Observation}；缺失、读不了、
 * PID 复用、出生时间或命令行不一致都只能是 {@link Observation#UNKNOWN}/{@link Observation#EXITED}，
 * 绝不发送信号。这里不按端口、名称、命令行扫描或进程组结束。
 */
public final class WebProofStop {
  /** 观察结论只表示“是不是记录里的同一进程”，不代表业务授权。 */
  public enum Observation {
    LIVE,
    EXITED,
    UNKNOWN
  }

  /** 一次信号的结果；拒绝与进程已消失必须区分，前者保留现场。 */
  public enum Signal {
    SENT,
    GONE,
    FOREIGN_UID,
    DENIED
  }

  public interface Probe {
    Observation observe() throws IOException;
  }

  public interface SignalSender {
    Signal signal() throws IOException;
  }

  public interface Clock {
    long now();

    void pause(long millis) throws InterruptedException;
  }

  public record Outcome(boolean stopped, WebStopDiagnostic diagnostic) {}

  static final long EXIT_MILLIS = 3_000;
  static final long POLL_MILLIS = 50;

  private WebProofStop() {}

  /**
   * 普通退出未确认时，只在 {@code allowForce} 且复核仍是同一身份时升级一次强制结束。
   * 返回 {@code stopped=true} 只表示记录中的 guest 已退出；启动器与维护屏障仍由调用方另行确认。
   */
  public static Outcome run(
      boolean allowForce, Probe probe, SignalSender term, SignalSender kill, Clock clock) {
    Observation before;
    try {
      before = verifyTwice(probe);
    } catch (IOException unavailable) {
      return failed(WebStopDiagnostic.Code.RECORDED_SESSION_UNCONFIRMED, unavailable);
    }
    if (before == Observation.EXITED) return new Outcome(true, null);
    if (before != Observation.LIVE)
      return failed(WebStopDiagnostic.Code.RECORDED_SESSION_UNCONFIRMED, null);
    Signal requested;
    try {
      requested = term.signal();
    } catch (IOException unavailable) {
      return failed(WebStopDiagnostic.Code.STOP_FAILED, unavailable);
    }
    if (requested == Signal.GONE || requested == Signal.FOREIGN_UID) return new Outcome(true, null);
    if (requested == Signal.DENIED) return failed(WebStopDiagnostic.Code.SIGNAL_DENIED, null);
    Outcome exited = awaitExit(probe, clock);
    if (exited != null) return exited;
    if (!allowForce) return failed(WebStopDiagnostic.Code.PENDING_EXIT, null);
    Observation again;
    try {
      again = verifyTwice(probe);
    } catch (IOException unavailable) {
      return failed(WebStopDiagnostic.Code.IDENTITY_CHANGED, unavailable);
    }
    if (again == Observation.EXITED) return new Outcome(true, null);
    if (again != Observation.LIVE) return failed(WebStopDiagnostic.Code.IDENTITY_CHANGED, null);
    Signal forced;
    try {
      forced = kill.signal();
    } catch (IOException unavailable) {
      return failed(WebStopDiagnostic.Code.STOP_FAILED, unavailable);
    }
    if (forced == Signal.GONE || forced == Signal.FOREIGN_UID) return new Outcome(true, null);
    if (forced == Signal.DENIED) return failed(WebStopDiagnostic.Code.SIGNAL_DENIED, null);
    Outcome killed = awaitExit(probe, clock);
    return killed != null ? killed : failed(WebStopDiagnostic.Code.EXIT_UNCONFIRMED, null);
  }

  /**
   * 覆盖更新弃旧版：guest 与它记录中的启动器按顺序回收。
   *
   * <p>只有 guest 已确认退出后才观察 / 结束启动器；启动器 probe 只在完整出生身份（PID、出生时间、
   * 命令行、UID、代次/实例/nonce）两次一致时返回 {@link Observation#LIVE}。任一对象核验不过都不发送
   * 信号，并返回该对象的结论。这里不按端口、名称、进程组或扫描结束任何进程。
   */
  public static Outcome runRetiringLauncher(
      boolean allowForce,
      Probe guest,
      SignalSender guestTerm,
      SignalSender guestKill,
      Probe launcher,
      SignalSender launcherTerm,
      SignalSender launcherKill,
      Clock clock) {
    Outcome retiredGuest = run(allowForce, guest, guestTerm, guestKill, clock);
    if (!retiredGuest.stopped()) return retiredGuest;
    return run(allowForce, launcher, launcherTerm, launcherKill, clock);
  }

  /** 同一检查连读两次，避免 stat 与 environ 之间进程被替换。 */
  private static Observation verifyTwice(Probe probe) throws IOException {
    Observation first = probe.observe();
    if (first != Observation.LIVE) return first;
    return probe.observe();
  }

  /** 返回 null 表示仍在运行，需要调用方决定是否升级；退出或无法确认都直接给出结论。 */
  private static Outcome awaitExit(Probe probe, Clock clock) {
    long deadline = clock.now() + EXIT_MILLIS;
    while (true) {
      Observation current;
      try {
        current = probe.observe();
      } catch (IOException unavailable) {
        return failed(WebStopDiagnostic.Code.EXIT_UNCONFIRMED, unavailable);
      } catch (RuntimeException unavailable) {
        return failed(WebStopDiagnostic.Code.EXIT_UNCONFIRMED, unavailable);
      }
      if (current == Observation.EXITED) return new Outcome(true, null);
      if (current == Observation.UNKNOWN)
        return failed(WebStopDiagnostic.Code.IDENTITY_CHANGED, null);
      long now = clock.now();
      if (now >= deadline) return null;
      try {
        clock.pause(Math.min(POLL_MILLIS, deadline - now));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return failed(WebStopDiagnostic.Code.STOP_INTERRUPTED, interrupted);
      }
    }
  }

  private static Outcome failed(WebStopDiagnostic.Code code, Throwable failure) {
    return new Outcome(
        false,
        failure == null ? WebStopDiagnostic.of(code) : WebStopDiagnostic.failure(code, failure));
  }
}
