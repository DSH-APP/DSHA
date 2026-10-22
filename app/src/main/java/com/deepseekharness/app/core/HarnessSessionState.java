package com.deepseekharness.app.core;

import com.deepseekharness.app.util.WebLifecycle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

/** Application-owned Web lifetime. Legacy facades share this exact queue and generation. */
public final class HarnessSessionState {
  final WebLifecycle lifecycle = new WebLifecycle();
  final com.deepseekharness.app.util.WebStartProtection startProtection =
      new com.deepseekharness.app.util.WebStartProtection(
          lifecycle, android.os.SystemClock::elapsedRealtime);
  final ScheduledExecutorService io =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread thread = new Thread(r, "dsh-io");
            thread.setDaemon(true);
            return thread;
          });
  final ConcurrentHashMap<Long, WebLifecycleController.WebRun> runs = new ConcurrentHashMap<>();
  final java.util.concurrent.CopyOnWriteArraySet<Runnable> readyWebPageListeners =
      new java.util.concurrent.CopyOnWriteArraySet<>();
  volatile WebLifecycleController.WebRun currentRun;
  volatile boolean restoreChecking, restoreChecked, restoreUnknown;
  boolean recheckPending;
  final java.util.concurrent.CopyOnWriteArrayList<Runnable> recheckListeners =
      new java.util.concurrent.CopyOnWriteArrayList<>();
  volatile com.deepseekharness.app.util.WebSessionProof.Saved retainedWebStopProof;
  volatile boolean unreadableWebStopProof;
  volatile boolean webStopProofLoaded;
  final Object webStopProofLock = new Object();
  final java.util.concurrent.CopyOnWriteArrayList<Runnable> restoreListeners =
      new java.util.concurrent.CopyOnWriteArrayList<>();
  Future<?> stopTask;
  WebRecovery recovery;
  StartupDiagnostics diagnostics;
  volatile String lastStopError = "";
  volatile String authFailure;
  volatile WebStopCoordinator.Result lastStopResult =
      new WebStopCoordinator.Result(
          WebStopCoordinator.Status.UNCONFIRMED, "WEB_STOP_NOT_REQUESTED", "");
}
