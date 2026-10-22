package com.deepseekharness.app.core;

import com.deepseekharness.app.runtime.WebProcessManager.SessionObservation;
import com.deepseekharness.app.util.DshAuthSession;

/** 同一活跃记录的只读复核；恢复授权前必须重新取得HTTP与第二次内核证明。 */
final class WebSessionRecheck {
  enum Result {
    LIVE,
    RESTORED,
    UNKNOWN,
    EXITED,
    STALE
  }

  record Outcome(Result result, DshAuthSession.Result authentication) {}

  interface Ports {
    boolean current();

    boolean alreadyLive();

    SessionObservation observe() throws Exception;

    DshAuthSession.Result authenticate() throws Exception;
  }

  static Outcome check(Ports ports) {
    try {
      if (!ports.current()) return new Outcome(Result.STALE, null);
      SessionObservation first = ports.observe();
      if (!ports.current()) return new Outcome(Result.STALE, null);
      if (first != SessionObservation.LIVE)
        return new Outcome(
            first == SessionObservation.EXITED ? Result.EXITED : Result.UNKNOWN, null);
      if (ports.alreadyLive()) return new Outcome(Result.LIVE, null);
      DshAuthSession.Result auth = ports.authenticate();
      if (!ports.current()) return new Outcome(Result.STALE, null);
      if (auth == null || !auth.ready()) return new Outcome(Result.UNKNOWN, null);
      SessionObservation second = ports.observe();
      if (!ports.current()) return new Outcome(Result.STALE, null);
      return new Outcome(
          second == SessionObservation.LIVE
              ? Result.RESTORED
              : second == SessionObservation.EXITED ? Result.EXITED : Result.UNKNOWN,
          second == SessionObservation.LIVE ? auth : null);
    } catch (Exception unavailable) {
      return new Outcome(ports.current() ? Result.UNKNOWN : Result.STALE, null);
    }
  }
}
