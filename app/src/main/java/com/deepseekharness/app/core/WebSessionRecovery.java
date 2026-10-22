package com.deepseekharness.app.core;

import com.deepseekharness.app.runtime.StoredWebSession;

/** 有界只读识别；未知证据不启动、不停止，也不擦除任何现场。 */
final class WebSessionRecovery {
  enum Result {
    NONE,
    RESTORED,
    UNKNOWN,
    STALE
  }

  interface Ports {
    StoredWebSession.Record read() throws Exception;

    boolean current();

    boolean compatible(StoredWebSession.Record record) throws Exception;

    boolean verify(StoredWebSession.Record record) throws Exception;

    boolean authenticate(StoredWebSession.Record record) throws Exception;

    boolean adopt(StoredWebSession.Record record) throws Exception;
  }

  static Result recover(Ports ports) {
    try {
      var saved = ports.read();
      if (!ports.current()) return Result.STALE;
      if (saved == null) return Result.NONE;
      if (!ports.compatible(saved) || !ports.verify(saved)) return Result.UNKNOWN;
      if (!ports.current()) return Result.STALE;
      if (!ports.authenticate(saved)) return Result.UNKNOWN;
      if (!ports.current()) return Result.STALE;
      if (!ports.verify(saved)) return Result.UNKNOWN;
      return ports.current() && ports.adopt(saved) ? Result.RESTORED : Result.STALE;
    } catch (Exception unavailable) {
      return Result.UNKNOWN;
    }
  }
}
