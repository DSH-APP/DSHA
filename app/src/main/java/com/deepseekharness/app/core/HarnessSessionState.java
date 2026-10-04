package com.deepseekharness.app.core;

import com.deepseekharness.app.util.WebLifecycle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

/** Application-owned Web lifetime. Legacy facades share this exact queue and generation. */
public final class HarnessSessionState {
  final WebLifecycle lifecycle = new WebLifecycle();
  final ScheduledExecutorService io =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread thread = new Thread(r, "dsh-io");
            thread.setDaemon(true);
            return thread;
          });
  final ConcurrentHashMap<Long, WebLifecycleController.WebRun> runs = new ConcurrentHashMap<>();
  volatile WebLifecycleController.WebRun currentRun;
  Future<?> stopTask;
  WebRecovery recovery;
  StartupDiagnostics diagnostics;
  volatile String lastStopError = "";
  volatile String authFailure;
  volatile WebStopCoordinator.Result lastStopResult =
      new WebStopCoordinator.Result(
          WebStopCoordinator.Status.UNCONFIRMED, "WEB_STOP_NOT_REQUESTED", "");
}
