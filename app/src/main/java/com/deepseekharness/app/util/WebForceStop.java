package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 显式确认的停止证据；自动停止和兼容回退不会使用此入口。 */
public final class WebForceStop {
  public record Evidence(
      int pid,
      long started,
      int uid,
      int appUid,
      String pidRecord,
      String identityRecord,
      String generation,
      String command,
      boolean verifiedWeb) {
    boolean eligible() {
      return pid > 1
          && started > 0
          && uid == appUid
          && appUid >= 10000
          && verifiedWeb
          && WebProcSel.parsePid(pidRecord) == pid
          && (pid + " " + started).equals(identityRecord == null ? null : identityRecord.trim())
          && generation != null
          && generation.matches("[1-9][0-9]{0,18}")
          && WebProcSel.maySignalWeb(command);
    }
  }

  public interface Probe {
    Evidence read() throws IOException;
  }

  public interface Signal {
    void kill(int pid) throws IOException;
  }

  public static final class Candidate {
    private final Object owner;
    private final Evidence evidence;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private Candidate(Object owner, Evidence evidence) {
      this.owner = owner;
      this.evidence = evidence;
    }

    public int pid() {
      return evidence.pid;
    }

    public String generation() {
      return evidence.generation;
    }
  }

  public static Candidate prepare(Object owner, Probe probe) throws IOException {
    if (owner == null) throw new IOException("WEB_FORCE_OWNER");
    Evidence first = probe.read(), second = probe.read();
    if (first == null || !first.eligible() || !first.equals(second))
      throw new IOException("WEB_FORCE_EVIDENCE_UNCONFIRMED");
    return new Candidate(owner, first);
  }

  public static void signal(Object owner, Candidate candidate, Probe probe, Signal signal)
      throws IOException {
    if (candidate == null || candidate.owner != owner || candidate.consumed.get())
      throw new IOException("WEB_FORCE_CANDIDATE_STALE");
    Evidence first = probe.read(), second = probe.read();
    if (first == null
        || !first.eligible()
        || !candidate.evidence.equals(first)
        || !first.equals(second)) throw new IOException("WEB_FORCE_EVIDENCE_CHANGED");
    if (!candidate.consumed.compareAndSet(false, true))
      throw new IOException("WEB_FORCE_CANDIDATE_STALE");
    signal.kill(candidate.evidence.pid);
  }

  private WebForceStop() {}
}
