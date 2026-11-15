package com.deepseekharness.app.plugins;

import static org.junit.Assert.*;

import com.deepseekharness.app.plugins.PluginManagementEngine.BackupBoundary;
import com.deepseekharness.app.plugins.PluginManagementEngine.Cache;
import com.deepseekharness.app.plugins.PluginManagementEngine.Cancellation;
import com.deepseekharness.app.plugins.PluginManagementEngine.Coordinator;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import org.junit.Test;

/** 注入主队列、单后台调度器和只读来源；不使用真实 Android、磁盘或 guest。 */
public final class PluginManagementEngineTest {
  @Test
  public void constructionAndPageRequestNeverProbeOnCallerThread() {
    World w = new World();
    assertEquals(0, w.backend.probes);
    assertEquals(0, w.jobs.size());
    w.engine.ensureLoaded();
    assertEquals(0, w.backend.probes);
    assertEquals(0, w.backend.queries);
    w.pump();
    assertEquals(1, w.backend.probes);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void freshKnownSnapshotIsReusedAcrossRepeatedPageEntries() {
    World w = new World();
    w.known("A");
    for (int i = 0; i < 100; i++) w.engine.ensureLoaded();
    w.pump();
    assertEquals(0, w.backend.probes);
    assertEquals(0, w.backend.queries);
    w.advance(59_999);
    w.engine.ensureLoaded();
    w.pump();
    assertEquals(0, w.backend.queries);
    w.advance(60_000);
    w.engine.ensureLoaded();
    w.pump();
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void foregroundUsesOneReadyProbeBeforeFirstPrefetch() {
    World w = new World();
    w.engine.foreground(true);
    assertTrue(w.backend.observing);
    assertEquals(0, w.backend.probes);
    w.pump();
    assertEquals(1, w.backend.probes);
    assertEquals(1, w.backend.queries);
    assertEquals(List.of("A"), w.backend.bindings);
  }

  @Test
  public void automaticPrefetchDefersWhileUserActionSuspendsItButPageDemandStillReads() {
    World w = new World();
    w.backend.autoQueries = false;
    w.engine.foreground(true);
    w.pump();
    assertEquals(1, w.backend.probes);
    assertEquals(0, w.backend.queries);
    w.engine.ensureLoaded();
    w.advance(1_000);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void notReadyRetainsUnknownAndDoesNotSelfRetry() {
    World w = new World();
    w.backend.source = null;
    w.engine.foreground(true);
    w.pump();
    assertEquals(1, w.backend.probes);
    assertFalse(w.backend.cache.known);
    assertTrue(w.backend.cache.stale);
    assertFalse(w.backend.cache.error.isEmpty());
    for (int i = 0; i < 10; i++) w.engine.ensureLoaded();
    w.advance(59_999);
    assertEquals(1, w.backend.probes);
    assertEquals(0, w.backend.queries);
    w.backend.source = "A";
    w.advance(60_000);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void probeFailureKeepsKnownSnapshotAndExplicitRefreshCanRetry() {
    World w = new World();
    w.known("A");
    w.backend.failure = new IOException("unavailable");
    w.engine.refresh();
    w.pump();
    assertTrue(w.backend.cache.known);
    assertTrue(w.backend.cache.stale);
    assertEquals(0, w.backend.queries);
    w.engine.ensureLoaded();
    w.advance(1000);
    assertEquals(1, w.backend.probes);
    w.backend.failure = null;
    w.engine.refresh();
    w.pump();
    assertEquals(2, w.backend.probes);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void explicitRefreshForcesQueryEvenForFreshSnapshot() {
    World w = new World();
    w.known("A");
    w.engine.refresh();
    w.pump();
    assertEquals(1, w.backend.queries);
    w.finish(true);
    w.advance(1000);
    w.engine.refresh();
    w.pump();
    assertEquals(2, w.backend.queries);
  }

  @Test
  public void continuousFileStormKeepsFirstEventStartDeadline() {
    World w = new World();
    w.known("A");
    w.engine.foreground(true);
    w.pump();
    for (int at = 0; at < 2000; at += 100) {
      w.advance(at);
      w.dirty();
    }
    w.advance(1999);
    assertEquals(0, w.backend.queries);
    w.advance(2000);
    assertEquals(1, w.backend.queries);
    assertEquals(Long.valueOf(2000), w.backend.queryTimes.get(0));
  }

  @Test
  public void scanInFlightRetainsOneDirtyBatchAndNeverStartsConcurrentQuery() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    for (int at = 10; at < 1000; at += 10) {
      w.advance(at);
      w.dirty();
    }
    w.advance(5000);
    assertEquals(1, w.backend.queries);
    w.finish(true);
    assertEquals(2, w.backend.queries);
    w.finish(true);
    w.advance(10_000);
    assertEquals(2, w.backend.queries);
  }

  @Test
  public void queryFailureIsBoundaryButIsNotAnInvalidationEvent() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    w.finish(false);
    for (int i = 0; i < 20; i++) {
      w.engine.ensureLoaded();
      w.engine.reconcile();
      w.pump();
    }
    w.advance(59_999);
    assertEquals(1, w.backend.queries);
    w.advance(60_000);
    assertEquals(2, w.backend.queries);
  }

  @Test
  public void failureStillPreservesRealDirtyReceivedDuringQuery() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    w.advance(1000);
    w.dirty();
    w.finish(false);
    w.advance(1200);
    assertEquals(2, w.backend.queries);
    w.finish(false);
    w.advance(10_000);
    assertEquals(2, w.backend.queries);
  }

  @Test
  public void successfulQueryAndSourceResolvedDoNotCreateRefreshLoop() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    w.engine.sourceResolved("A");
    w.finish(true);
    for (int i = 0; i < 20; i++) w.engine.reconcile();
    w.advance(1000);
    assertEquals(1, w.backend.queries);
    assertTrue(w.backend.projections > 0);
  }

  @Test
  public void rotationAndBottomNavigationReuseKnownSnapshot() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    w.finish(true);
    for (int i = 1; i < 10; i++) {
      w.engine.foreground(false);
      assertFalse(w.backend.observing);
      w.advance(i * 1000L);
      w.engine.foreground(true);
      w.engine.ensureLoaded();
      w.pump();
    }
    assertEquals(1, w.backend.queries);
    assertTrue(w.backend.observing);
  }

  @Test
  public void backgroundStopsPeriodicWorkAndDefersDirtyUntilResume() {
    World w = new World();
    w.known("A");
    w.engine.foreground(true);
    w.pump();
    w.engine.foreground(false);
    w.dirty();
    w.advance(300_000);
    assertEquals(1, w.backend.probes);
    assertEquals(0, w.backend.queries);
    assertEquals(0, w.backend.projections);
    w.engine.foreground(true);
    w.pump();
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void heartbeatOnlyProjectsRuntimeAndDoesNotRenewCatalogTtl() {
    World w = new World();
    w.known("A");
    w.engine.foreground(true);
    w.pump();
    for (int at = 0; at < 60_000; at += 1000) {
      w.advance(at);
      w.engine.runtimeDirty();
    }
    w.advance(59_999);
    assertEquals(0, w.backend.queries);
    assertTrue(w.backend.projections > 0);
    w.advance(60_000);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void runtimeEvidenceExpiryProjectsOnceWithoutFullQueryOrSelfRearming() {
    World w = new World();
    w.known("A");
    w.engine.foreground(true);
    w.advance(200);
    assertEquals(1, w.backend.projections);
    w.advance(10_000);
    assertEquals(1, w.backend.projections);
    w.advance(10_001);
    assertEquals(2, w.backend.projections);
    w.advance(50_000);
    assertEquals(2, w.backend.projections);
    assertEquals(0, w.backend.queries);
    assertEquals(1, w.liveJobs()); // 只有原有低频来源核对。
  }

  @Test
  public void newRuntimeEventRetiresEvenAlreadyQueuedExpiry() {
    World w = new World();
    w.known("A");
    w.engine.foreground(true);
    w.advance(200);
    w.now = 10_001;
    assertTrue(w.workerStep()); // 旧到期任务已投主队列，cancel 无法撤回。
    w.engine.runtimeDirty();
    w.pump();
    assertEquals(1, w.backend.projections);
    w.advance(10_201);
    assertEquals(2, w.backend.projections);
    w.advance(20_001);
    assertEquals(2, w.backend.projections);
    w.advance(20_002);
    assertEquals(3, w.backend.projections);
    w.advance(50_000);
    assertEquals(3, w.backend.projections);
    assertEquals(0, w.backend.queries);
  }

  @Test
  public void backgroundRetiresQueuedRuntimeExpiryAndCloseDropsAllJobs() {
    World w = new World();
    w.known("A");
    w.engine.foreground(true);
    w.advance(200);
    w.now = 10_001;
    assertTrue(w.workerStep());
    w.engine.foreground(false);
    w.pump();
    w.advance(50_000);
    assertEquals(1, w.backend.projections);
    assertEquals(0, w.liveJobs());
    w.engine.foreground(true);
    w.pump();
    w.engine.close();
    w.advance(100_000);
    assertEquals(1, w.backend.projections);
    assertEquals(0, w.liveJobs());
  }

  @Test
  public void successfulInitialQueryArmsOneRuntimeEvidenceExpiry() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    w.finish(true);
    w.advance(200);
    assertEquals(1, w.backend.projections);
    w.advance(10_001);
    assertEquals(2, w.backend.projections);
    w.advance(50_000);
    assertEquals(2, w.backend.projections);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void wallClockRollbackCannotKeepSnapshotFreshForever() {
    World w = new World();
    w.known("A");
    w.engine.ensureLoaded();
    w.wallOffset -= 100_000;
    w.advance(60_000);
    w.engine.ensureLoaded();
    w.pump();
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void readyDuringProbeReconcilesLatestSourceOnce() {
    World w = new World();
    w.known("A");
    w.engine.foreground(true);
    assertTrue(w.workerStep()); // 旧 A 结果已产生但尚未被主线程接收。
    w.backend.source = "B";
    for (int i = 0; i < 100; i++) w.engine.reconcile();
    w.pump();
    assertEquals(2, w.backend.probes);
    assertEquals(List.of("A", "B"), w.backend.bindings);
    assertEquals("B", w.backend.cache.source);
    assertEquals(1, w.backend.queries);
    w.finish(true);
    w.advance(10_000);
    assertEquals(2, w.backend.probes);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void readyDuringScanReconcilesChangedSourceAfterCompletion() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    w.backend.source = "B";
    for (int i = 0; i < 100; i++) w.engine.reconcile();
    w.advance(1000);
    assertEquals(1, w.backend.probes);
    assertEquals(1, w.backend.queries);
    w.finish(true);
    assertEquals("B", w.backend.cache.source);
    assertEquals(2, w.backend.probes);
    assertEquals(2, w.backend.queries);
    w.finish(true);
    w.advance(10_000);
    assertEquals(2, w.backend.probes);
    assertEquals(2, w.backend.queries);
  }

  @Test
  public void sameSourceReadyDuringSuccessfulScanOnlyProjectsAfterOneRecheck() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    for (int i = 0; i < 100; i++) w.engine.reconcile();
    w.finish(true);
    w.advance(1000);
    assertEquals(2, w.backend.probes);
    assertEquals(1, w.backend.queries);
    assertEquals(1, w.backend.projections);
    w.advance(10_000);
    assertEquals(2, w.backend.probes);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void readyDuringFailedScanAllowsOneFollowupWithoutFailureLoop() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    for (int i = 0; i < 100; i++) w.engine.reconcile();
    w.finish(false);
    assertEquals(1, w.backend.queries);
    w.advance(750);
    assertEquals(2, w.backend.queries);
    w.finish(false);
    int probes = w.backend.probes;
    w.advance(10_000);
    assertEquals(probes, w.backend.probes);
    assertEquals(2, w.backend.queries);
  }

  @Test
  public void readyDuringUnavailableProbeRecoversSameSourceWithoutTtlDelay() {
    World w = new World();
    w.known("A");
    w.backend.source = null;
    w.engine.foreground(true);
    assertTrue(w.workerStep());
    w.backend.source = "A";
    for (int i = 0; i < 100; i++) w.engine.reconcile();
    w.pump();
    assertEquals(2, w.backend.probes);
    assertEquals(1, w.backend.queries);
    w.finish(true);
    w.advance(10_000);
    assertEquals(2, w.backend.probes);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void readyAfterUnavailableSourceRecoversSameIdentityBeforeCooldownExpires() {
    World w = new World();
    w.known("A");
    w.backend.source = null;
    w.engine.foreground(true);
    w.pump();
    assertEquals(0, w.backend.queries);
    w.backend.source = "A";
    w.engine.reconcile();
    w.pump();
    assertEquals(2, w.backend.probes);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void readyDuringScanProbeSurvivesTemporaryFailureAndMinimumScanInterval() {
    World w = new World();
    w.known("A");
    w.engine.foreground(true);
    w.pump();
    w.backend.source = null;
    w.engine.refresh();
    assertTrue(w.workerStep()); // beginScan 定时器进入主队列。
    w.mainStep();
    assertTrue(w.workerStep()); // null 探测结果等待主线程。
    w.backend.source = "A";
    for (int i = 0; i < 100; i++) w.engine.reconcile();
    w.pump();
    assertEquals(0, w.backend.queries);
    w.advance(749);
    assertEquals(0, w.backend.queries);
    w.advance(750);
    assertEquals(1, w.backend.queries);
    w.finish(true);
    w.advance(10_000);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void unavailableFollowupConsumesOneSignalBatchWithoutSelfRetry() {
    World w = new World();
    w.backend.source = null;
    w.engine.foreground(true);
    assertTrue(w.workerStep());
    for (int i = 0; i < 100; i++) w.engine.reconcile();
    w.pump();
    assertEquals(2, w.backend.probes);
    w.advance(59_999);
    assertEquals(2, w.backend.probes);
    assertEquals(0, w.backend.queries);
  }

  @Test
  public void obsoleteProbeCannotRollRegistryBackToPreviousSource() {
    World w = new World();
    w.known("A");
    w.engine.foreground(true);
    assertTrue(w.workerStep()); // A 的只读探测完成，主线程尚未接收。
    w.known("B");
    w.backend.source = "B";
    w.pump();
    assertEquals("B", w.backend.cache.source);
    assertFalse(w.backend.bindings.contains("A"));
    assertEquals(1, w.backend.queries);
    assertTrue(w.backend.bindings.contains("B"));
  }

  @Test
  public void obsoleteSourceResolvedCannotRebindDetectorToOldRoot() {
    World w = new World();
    w.known("B");
    w.backend.source = "B";
    w.engine.foreground(true);
    w.pump();
    w.engine.sourceResolved("A");
    w.pump();
    assertFalse(w.backend.bindings.contains("A"));
    assertEquals("B", w.backend.cache.source);
    assertEquals(0, w.backend.queries);
  }

  @Test
  public void foregroundWithoutPluginPageNeverRunsPeriodicCatalogQuery() {
    World w = World.unwatched();
    w.known("A");
    w.engine.foreground(true);
    w.pump();
    // 只做一次廉价来源核对；对话页前台 10 分钟也不应查询目录。
    w.advance(600_000);
    assertEquals(1, w.backend.probes);
    assertEquals(0, w.backend.queries);
  }

  @Test
  public void unwatchedDirtyIsDeferredUntilPluginPageIsVisible() {
    World w = World.unwatched();
    w.known("A");
    w.engine.foreground(true);
    w.pump();
    w.dirty();
    w.advance(300_000);
    assertEquals(0, w.backend.queries);
    w.engine.watched(true);
    w.pump();
    w.advance(301_000);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void leavingPluginPageStopsPeriodicRefresh() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    w.finish(true);
    w.advance(130_000);
    int whileWatched = w.backend.queries;
    assertTrue(whileWatched >= 2);
    w.engine.watched(false);
    w.advance(900_000);
    assertEquals(whileWatched, w.backend.queries);
  }

  @Test
  public void pageDemandStillLoadsWhileUnwatched() {
    World w = World.unwatched();
    w.engine.foreground(true);
    w.pump();
    assertEquals(0, w.backend.queries);
    w.engine.ensureLoaded();
    w.advance(1_000);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void pageLeftDuringProbeKeepsOneDeferredQueryForReturn() {
    World w = new World();
    w.engine.foreground(true);
    assertTrue(w.workerStep()); // probe 已执行，结果等待主线程。
    w.engine.watched(false);
    w.pump();
    assertEquals(0, w.backend.queries);
    w.advance(300_000);
    assertEquals(0, w.backend.queries);
    w.engine.watched(true);
    w.advance(301_000);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void pageRequestBeforeIdleForegroundCannotLeaveProbeScanStuck() {
    World w = new World();
    w.engine.ensureLoaded();
    assertTrue(w.workerStep()); // 到期计时器进入主队列。
    w.mainStep(); // beginScan 排队 probe。
    assertTrue(w.workerStep()); // probe 结果等待主线程。
    w.engine.foreground(true);
    w.pump();
    w.advance(750);
    assertEquals(1, w.backend.queries);
    w.finish(true);
    w.engine.ensureLoaded();
    w.pump();
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void pauseDuringProbeDiscardsPrefetchAndNextResumeRecovers() {
    World w = new World();
    w.engine.foreground(true);
    assertTrue(w.workerStep());
    w.engine.foreground(false);
    w.pump();
    assertEquals(0, w.backend.queries);
    assertTrue(w.backend.bindings.isEmpty());
    w.engine.foreground(true);
    w.pump();
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void delayedPeriodicCallbackCannotMultiplyAfterRotation() {
    World w = new World();
    w.known("A");
    w.engine.foreground(true);
    w.advance(1000);
    w.now = 60_000;
    assertTrue(w.workerStep()); // 旧周期通知已投主线程，cancel 无法撤回。
    w.engine.foreground(false);
    w.engine.foreground(true);
    w.pump();
    assertEquals(1, w.backend.queries);
    w.finish(true);
    w.advance(120_000);
    assertEquals(2, w.backend.queries);
    assertEquals(1, w.liveJobs()); // 下一次低频补偿只有一份。
  }

  @Test
  public void explicitRefreshDuringQuerySurvivesItsFinishedBoundary() {
    World w = new World();
    w.engine.ensureLoaded();
    w.pump();
    w.engine.refresh();
    w.finish(true);
    w.advance(750);
    assertEquals(2, w.backend.queries);
  }

  @Test
  public void closeReleasesObserversAndDropsQueuedProbeCompletion() {
    World w = new World();
    w.engine.foreground(true);
    assertTrue(w.workerStep());
    w.engine.close();
    w.pump();
    w.advance(120_000);
    assertTrue(w.stopped);
    assertFalse(w.backend.observing);
    assertEquals(0, w.backend.queries);
    assertEquals(0, w.liveJobs());
  }

  @Test
  public void automaticPrefetchCancelsOnBackgroundAndWaitsForQueryFinished() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    assertEquals(1, w.backend.queries);
    w.engine.foreground(false);
    assertEquals(1, w.backend.cancelledQueries);
    w.engine.foreground(true);
    w.pump();
    assertEquals(1, w.backend.queries);
    w.finish(false);
    w.advance(750);
    assertEquals(2, w.backend.queries);
    w.finish(false);
    w.advance(10_000);
    assertEquals(2, w.backend.queries);
  }

  @Test
  public void explicitRefreshContinuesInBackgroundWithoutCancellingQuery() {
    World w = new World();
    w.known("A");
    w.engine.foreground(true);
    w.pump();
    w.engine.refresh();
    w.pump();
    w.engine.foreground(false);
    assertEquals(0, w.backend.cancelledQueries);
    w.finish(true);
    w.advance(120_000);
    assertEquals(1, w.backend.queries);
    assertFalse(w.backend.observing);
  }

  @Test
  public void pageRequestTakesOverInFlightAutomaticPrefetch() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    w.engine.ensureLoaded();
    w.engine.foreground(false);
    assertEquals(0, w.backend.cancelledQueries);
    w.finish(true);
    w.advance(10_000);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void explicitPageProbeCanFinishAfterBackgroundTransition() {
    World w = new World();
    w.engine.foreground(true);
    w.engine.ensureLoaded();
    assertTrue(w.workerStep());
    w.engine.foreground(false);
    w.pump();
    w.advance(750);
    assertEquals(0, w.backend.cancelledQueries);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void cancelledPrefetchDoesNotRetryInBackgroundOrImposeFailureCooldown() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    w.engine.foreground(false);
    w.finish(false);
    w.advance(1000);
    assertEquals(1, w.backend.queries);
    w.engine.foreground(true);
    w.pump();
    assertEquals(2, w.backend.queries);
  }

  @Test
  public void repeatedPauseDoesNotReplayPendingQueryCancellation() {
    World w = new World();
    w.engine.foreground(true);
    w.pump();
    w.engine.foreground(false);
    w.engine.foreground(true);
    w.engine.foreground(false);
    assertEquals(1, w.backend.cancelledQueries);
    assertEquals(1, w.backend.queries);
    w.finish(false);
    w.advance(10_000);
    assertEquals(1, w.backend.queries);
  }

  @Test
  public void backgroundDoesNotCancelWhenOnlySourceProbeIsInFlight() {
    World w = new World();
    w.engine.foreground(true);
    assertTrue(w.workerStep());
    w.engine.foreground(false);
    w.pump();
    assertEquals(0, w.backend.cancelledQueries);
    assertEquals(0, w.backend.queries);
  }

  @Test
  public void backupProgressAndLiveDataReplayDoNotCauseScanningLoop() {
    BackupBoundary value = new BackupBoundary();
    assertFalse(value.accept("old", false));
    assertFalse(value.accept("old", false));
    assertFalse(value.accept("restore", true));
    assertFalse(value.accept("restore", true));
    assertTrue(value.accept("restore", false));
    assertFalse(value.accept("restore", false));
    assertTrue(value.accept("new-completed-while-background", false));
    assertFalse(value.accept("new-completed-while-background", false));
  }

  private static final class World
      implements PluginManagementEngine.Clock, PluginManagementEngine.Scheduler {
    long now, wallOffset = 1_000_000, serial;
    boolean background, stopped;
    final PriorityQueue<Job> jobs =
        new PriorityQueue<>(
            Comparator.comparingLong((Job job) -> job.at).thenComparingLong(job -> job.order));
    final ArrayDeque<Runnable> main = new ArrayDeque<>();
    final FakeBackend backend = new FakeBackend(this);
    final Coordinator<String> engine = new Coordinator<>(backend, this, this, main::addLast);

    /** 既有用例描述插件页可见时的行为；未观看场景用 {@link #unwatched()}。 */
    World() {
      engine.watched(true);
    }

    static World unwatched() {
      World w = new World();
      w.engine.watched(false);
      return w;
    }

    @Override
    public long now() {
      return now;
    }

    @Override
    public long wallTime() {
      return wallOffset + now;
    }

    @Override
    public void execute(Runnable action) {
      schedule(action, 0);
    }

    @Override
    public Cancellation schedule(Runnable action, long delayMillis) {
      assertFalse("调度器已关闭", stopped);
      Job job = new Job(now + delayMillis, serial++, action);
      jobs.add(job);
      return () -> job.cancelled = true;
    }

    @Override
    public void close() {
      stopped = true;
      jobs.clear();
    }

    void mainStep() {
      assertFalse(background);
      if (!main.isEmpty()) main.removeFirst().run();
    }

    boolean workerStep() {
      while (!jobs.isEmpty() && jobs.peek().cancelled) jobs.remove();
      if (jobs.isEmpty() || jobs.peek().at > now) return false;
      Job job = jobs.remove();
      background = true;
      try {
        job.action.run();
      } finally {
        background = false;
      }
      return true;
    }

    void pump() {
      int budget = 10_000;
      do {
        while (!main.isEmpty()) {
          assertTrue("主队列发生自旋", --budget > 0);
          mainStep();
        }
        assertTrue("后台队列发生自旋", --budget > 0);
      } while (workerStep());
    }

    void advance(long target) {
      assertTrue(target >= now);
      pump();
      while (true) {
        while (!jobs.isEmpty() && jobs.peek().cancelled) jobs.remove();
        if (jobs.isEmpty() || jobs.peek().at > target) break;
        now = jobs.peek().at;
        pump();
      }
      now = target;
      pump();
    }

    void known(String source) {
      backend.cache = new Cache(backend.cache.version + 1, source, true, false, wallTime(), "");
    }

    void dirty() {
      Cache value = backend.cache;
      backend.cache =
          new Cache(
              value.version + 1,
              value.source,
              value.known,
              true,
              value.verifiedAtMillis,
              value.error);
      engine.invalidated();
    }

    void finish(boolean success) {
      if (success) known((String) backend.cache.source);
      else backend.unavailable(new IOException("query failed"));
      engine.queryFinished();
      pump();
    }

    int liveJobs() {
      return (int) jobs.stream().filter(job -> !job.cancelled).count();
    }
  }

  private static final class FakeBackend implements PluginManagementEngine.Backend<String> {
    final World world;
    Cache cache = new Cache(0, null, false, true, 0, "");
    String source = "A";
    Exception failure;
    int probes, queries, projections, cancelledQueries;
    boolean observing, autoQueries = true;
    final List<String> bindings = new ArrayList<>();
    final List<Long> queryTimes = new ArrayList<>();

    FakeBackend(World world) {
      this.world = world;
    }

    @Override
    public Cache cache() {
      return cache;
    }

    @Override
    public String probe() throws Exception {
      assertTrue("probe 不能阻塞主线程", world.background);
      probes++;
      if (failure != null) throw failure;
      return source;
    }

    @Override
    public Object source(String source) {
      return source;
    }

    @Override
    public void bind(String source) {
      assertFalse("来源只能在主队列采纳", world.background);
      bindings.add(source);
      if (!Objects.equals(source, cache.source))
        cache = new Cache(cache.version + 1, source, false, true, 0, "");
    }

    @Override
    public boolean autoQueryAllowed() {
      return autoQueries;
    }

    @Override
    public void query(boolean automatic) {
      assertFalse(world.background);
      queries++;
      queryTimes.add(world.now);
    }

    @Override
    public void cancelQuery() {
      assertFalse(world.background);
      cancelledQueries++;
    }

    @Override
    public void projection() {
      assertTrue(world.background);
      projections++;
      cache =
          new Cache(
              cache.version + 1,
              cache.source,
              cache.known,
              cache.stale,
              cache.verifiedAtMillis,
              cache.error);
    }

    @Override
    public void observing(boolean value) {
      assertFalse(world.background);
      observing = value;
    }

    @Override
    public void unavailable(Exception error) {
      assertFalse(world.background);
      cache =
          new Cache(
              cache.version + 1,
              cache.source,
              cache.known,
              true,
              cache.verifiedAtMillis,
              error == null ? "not ready" : error.getMessage());
    }
  }

  private static final class Job {
    final long at, order;
    final Runnable action;
    boolean cancelled;

    Job(long at, long order, Runnable action) {
      this.at = at;
      this.order = order;
      this.action = action;
    }
  }
}
