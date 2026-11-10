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

  record Outcome(Result result, DshAuthSession.Result authentication, String reason, int attempts) {
    Outcome(Result result, DshAuthSession.Result authentication) {
      this(result, authentication, reason(result), 0);
    }

    private static String reason(Result result) {
      return switch (result) {
        case LIVE, RESTORED -> "RESTORED";
        case EXITED -> "EXIT_CONFIRMED";
        case UNKNOWN -> "IDENTITY_UNKNOWN";
        case STALE -> "STALE";
      };
    }
  }

  interface Ports {
    boolean current();

    boolean alreadyLive();

    SessionObservation observe() throws Exception;

    DshAuthSession.Result authenticate() throws Exception;
  }

  static Outcome check(Ports ports) {
    String reason = "IDENTITY_UNKNOWN";
    int attempts = 0;
    try {
      if (!ports.current()) return new Outcome(Result.STALE, null);
      SessionObservation first = ports.observe();
      if (!ports.current()) return new Outcome(Result.STALE, null);
      if (first != SessionObservation.LIVE)
        return new Outcome(
            first == SessionObservation.EXITED ? Result.EXITED : Result.UNKNOWN, null);
      if (ports.alreadyLive()) return new Outcome(Result.LIVE, null);
      reason = "AUTH_UNAVAILABLE";
      attempts = 1;
      DshAuthSession.Result auth = ports.authenticate();
      if (!ports.current()) return new Outcome(Result.STALE, null);
      if (auth == null || !auth.ready()) return new Outcome(Result.UNKNOWN, null, reason, attempts);
      reason = "IDENTITY_UNKNOWN";
      SessionObservation second = ports.observe();
      if (!ports.current()) return new Outcome(Result.STALE, null);
      Result result =
          second == SessionObservation.LIVE
              ? Result.RESTORED
              : second == SessionObservation.EXITED ? Result.EXITED : Result.UNKNOWN;
      return new Outcome(
          result,
          second == SessionObservation.LIVE ? auth : null,
          Outcome.reason(result),
          attempts);
    } catch (Exception unavailable) {
      return ports.current()
          ? new Outcome(Result.UNKNOWN, null, reason, attempts)
          : new Outcome(Result.STALE, null);
    }
  }
}
