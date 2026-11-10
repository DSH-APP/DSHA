package com.deepseekharness.app.plugins;

import static org.junit.Assert.*;

import com.deepseekharness.app.plugins.PluginOperationQueue.Entry;
import com.deepseekharness.app.plugins.PluginOperationQueue.Operation;
import com.deepseekharness.app.plugins.PluginOperationQueue.Phase;
import com.deepseekharness.app.plugins.PluginOperationQueue.Queue;
import com.deepseekharness.app.plugins.PluginOperationQueue.Type;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** 不启动 Android、环境任务或执行器业务的队列回归。 */
public final class PluginOperationQueueTest {
  @Test
  public void fifoUsesOneActiveSlotAndDoesNotExecuteWork() {
    Queue<Runnable> queue = new Queue<>();
    Runnable neverRun = () -> fail("队列不得执行工作");
    Entry<Runnable> first = queue.enqueue(Type.INSTALL, "a", null, "1", null, neverRun);
    Entry<Runnable> second = queue.enqueue(Type.DELETE, "b", null, "2", null, neverRun);
    Entry<Runnable> third = queue.enqueue(Type.UPDATE, "c", null, "3", null, neverRun);

    assertTrue(queue.hasPending());
    assertSame(first.operation(), queue.current());
    assertNull(queue.active());
    assertSame(first, queue.poll());
    assertSame(first, queue.active());
    assertEquals(Phase.RUNNING, queue.current().phase);
    assertNull(queue.poll());
    assertTrue(queue.complete(first.operationId, Phase.SUCCEEDED, "done"));
    assertNull(queue.active());
    assertSame(second.operation(), queue.current());
    assertSame(second, queue.poll());
    assertTrue(queue.complete(second.operationId, Phase.FAILED, "failed"));
    assertSame(third, queue.poll());
    assertTrue(queue.complete(third.operationId, Phase.UNCONFIRMED, "unknown"));
    assertFalse(queue.hasPending());
    assertNull(queue.active());
    assertNull(queue.current());
    assertNull(queue.poll());
    assertEquals(3, queue.snapshot().size());
  }

  @Test
  public void differentSourceTogglesKeepTheirOriginalQueueOrder() {
    Queue<String> queue = new Queue<>();
    PluginSourceIdentity oldSource = source(1);
    PluginSourceIdentity newSource = source(2);
    Entry<String> old = queue.enqueue(Type.SET_ENABLED, "a", true, "1", oldSource, "old work");
    Entry<String> other = enqueue(queue, Type.INSTALL, "b");
    Entry<String> latest = queue.enqueue(Type.SET_ENABLED, "a", false, "1", newSource, "new work");

    assertNotEquals(old.operationId, latest.operationId);
    assertTrue(oldSource.sameDataSource(newSource));
    assertNotEquals(oldSource, newSource);
    assertEquals(Phase.QUEUED, old.operation().phase);
    assertSame(old.request, old.operation());
    assertTrue(old.operation().pending());
    assertEquals(Boolean.TRUE, old.request.desired);
    assertEquals("1", old.request.expectedVersion);
    assertSame(oldSource, old.request.source);
    assertEquals("old work", old.work);
    assertEquals(Boolean.FALSE, latest.request.desired);
    assertEquals("1", latest.request.expectedVersion);
    assertSame(newSource, latest.request.source);
    assertEquals("new work", latest.work);
    assertEquals(Arrays.asList(old.operationId, other.operationId, latest.operationId), ids(queue));
    assertSame(old, queue.poll());
    assertTrue(queue.complete(old.operationId, Phase.SUCCEEDED, "done"));
    assertSame(other, queue.poll());
    assertTrue(queue.complete(other.operationId, Phase.SUCCEEDED, "done"));
    assertSame(latest, queue.poll());
  }

  @Test
  public void sameIdentityToggleIsSupersededAndNewRequestMovesToTail() {
    Queue<String> queue = new Queue<>();
    Entry<String> old = toggle(queue, "a", true);
    Entry<String> other = enqueue(queue, Type.INSTALL, "b");
    Entry<String> latest = toggle(queue, "a", false);
    assertNotSame(old.request.source, latest.request.source);
    assertEquals(old.request.source, latest.request.source);
    assertNotEquals(old.operationId, latest.operationId);
    assertEquals(Phase.SUPERSEDED, old.operation().phase);
    assertSame(old.operation(), queue.get(old.operationId));
    assertFalse(old.operation().pending());
    assertEquals(Phase.QUEUED, old.request.phase);
    assertEquals(Boolean.TRUE, old.request.desired);
    assertEquals(Boolean.FALSE, latest.request.desired);
    assertEquals(Arrays.asList(other.operationId, latest.operationId, old.operationId), ids(queue));
    assertSame(other, queue.poll());
    assertTrue(queue.complete(other.operationId, Phase.SUCCEEDED, "done"));
    assertSame(latest, queue.poll());
  }

  @Test
  public void differentVersionsNeverMergeOrSkipTheLastSameTargetRequest() {
    for (String[] versions : new String[][] {{"1", "2"}, {null, "1"}, {"1", null}}) {
      Queue<String> queue = new Queue<>();
      Entry<String> old = queue.enqueue(Type.SET_ENABLED, "a", true, versions[0], source(1), "old");
      Entry<String> changed =
          queue.enqueue(Type.SET_ENABLED, "a", false, versions[1], source(1), "changed");
      Entry<String> latest =
          queue.enqueue(Type.SET_ENABLED, "a", false, versions[0], source(1), "latest");
      assertEquals(
          Arrays.asList(old.operationId, changed.operationId, latest.operationId), ids(queue));
      for (Operation operation : queue.snapshot()) assertEquals(Phase.QUEUED, operation.phase);
    }
  }

  @Test
  public void unknownSourceOnEitherRequestPreventsMerging() {
    for (PluginSourceIdentity[] sources :
        new PluginSourceIdentity[][] {{null, null}, {null, source(1)}, {source(1), null}}) {
      Queue<String> queue = new Queue<>();
      Entry<String> old = queue.enqueue(Type.SET_ENABLED, "a", true, "1", sources[0], "old");
      Entry<String> latest = queue.enqueue(Type.SET_ENABLED, "a", false, "1", sources[1], "new");
      assertEquals(Arrays.asList(old.operationId, latest.operationId), ids(queue));
      assertEquals(Phase.QUEUED, old.operation().phase);
      assertEquals(Phase.QUEUED, latest.operation().phase);
    }
  }

  @Test
  public void fullQueueRejectsSourceOrVersionMismatchWithoutSuperseding() {
    Queue<String> queue = new Queue<>(1, 2);
    Entry<String> old = toggle(queue, "a", true);
    Entry<String> differentSource =
        queue.enqueue(Type.SET_ENABLED, "a", false, "1", source(2), "source changed");
    Entry<String> differentVersion =
        queue.enqueue(Type.SET_ENABLED, "a", false, "2", source(1), "version changed");
    assertEquals(Phase.QUEUED, old.operation().phase);
    assertEquals(Phase.REJECTED, differentSource.operation().phase);
    assertEquals(Phase.REJECTED, differentVersion.operation().phase);
    assertSame(old, queue.poll());
  }

  @Test
  public void installDeleteAndUpdateBlockToggleCoalescingAcrossTheSameTarget() {
    for (Type barrier : Arrays.asList(Type.INSTALL, Type.DELETE, Type.UPDATE)) {
      Queue<String> queue = new Queue<>();
      Entry<String> before = toggle(queue, "a", true);
      Entry<String> middle = enqueue(queue, barrier, "a");
      Entry<String> after = toggle(queue, "a", false);
      assertEquals(Phase.QUEUED, before.operation().phase);
      assertEquals(
          Arrays.asList(before.operationId, middle.operationId, after.operationId), ids(queue));

      Entry<String> newest = toggle(queue, "a", true);
      assertEquals(Phase.QUEUED, before.operation().phase);
      assertEquals(Phase.QUEUED, middle.operation().phase);
      assertEquals(Phase.SUPERSEDED, after.operation().phase);
      assertEquals(
          Arrays.asList(
              before.operationId, middle.operationId, newest.operationId, after.operationId),
          ids(queue));
      assertSame(before, queue.poll());
      assertTrue(queue.complete(before.operationId, Phase.SUCCEEDED, "done"));
      assertSame(middle, queue.poll());
      assertTrue(queue.complete(middle.operationId, Phase.SUCCEEDED, "done"));
      assertSame(newest, queue.poll());
    }
  }

  @Test
  public void everyNonToggleOnTheSameTargetIsABarrierAndNeverCoalesces() {
    for (Type type : Type.values()) {
      if (type == Type.SET_ENABLED) continue;
      Queue<String> queue = new Queue<>();
      Entry<String> toggle = toggle(queue, "a", true);
      Entry<String> first = enqueue(queue, type, "a");
      Entry<String> second = enqueue(queue, type, "a");
      Entry<String> after = toggle(queue, "a", false);
      assertEquals(
          Arrays.asList(
              toggle.operationId, first.operationId, second.operationId, after.operationId),
          ids(queue));
      for (Operation operation : queue.snapshot()) assertEquals(Phase.QUEUED, operation.phase);
    }
  }

  @Test
  public void runningAndCommittingEntriesCannotBeSupersededOrCancelledAsQueued() {
    for (Phase phase : Arrays.asList(Phase.RUNNING, Phase.COMMITTING)) {
      Queue<String> queue = new Queue<>();
      Entry<String> active = toggle(queue, "a", true);
      assertSame(active, queue.poll());
      assertTrue(queue.progress(active.operationId, phase, "busy", 40, "tx"));
      Operation busy = active.operation();
      Entry<String> waiting = toggle(queue, "a", false);
      Entry<String> latest = toggle(queue, "a", true);
      assertEquals(Phase.SUPERSEDED, waiting.operation().phase);
      assertSame(busy, active.operation());
      assertSame(active, queue.active());
      assertEquals(phase, active.operation().phase);
      assertFalse(queue.cancelQueued(active.operationId));
      assertNull(queue.poll());
      assertTrue(queue.complete(active.operationId, Phase.SUCCEEDED, "done"));
      assertSame(latest, queue.poll());
    }
  }

  @Test
  public void defaultCapacityIsThirtyTwoWaitingPlusOneActiveAndRejectsExplicitly() {
    Queue<String> queue = new Queue<>();
    Entry<String> active = enqueue(queue, Type.INSTALL, "active");
    assertSame(active, queue.poll());
    List<Entry<String>> waiting = new ArrayList<>();
    for (int i = 0; i < 32; i++) {
      Entry<String> entry = enqueue(queue, Type.INSTALL, "plugin-" + i);
      waiting.add(entry);
      assertEquals(Phase.QUEUED, entry.operation().phase);
    }
    Entry<String> rejected = enqueue(queue, Type.DELETE, "overflow");
    assertEquals(Phase.REJECTED, rejected.operation().phase);
    assertEquals("PLUGIN_QUEUE_FULL", rejected.operation().message);
    assertFalse(rejected.operation().pending());
    assertSame(rejected.operation(), queue.get(rejected.operationId));
    assertEquals(34, queue.snapshot().size());
    assertFalse(queue.cancelQueued(rejected.operationId));
    assertFalse(queue.complete(rejected.operationId, Phase.FAILED, "late"));
    assertSame(active, queue.active());

    assertTrue(queue.complete(active.operationId, Phase.SUCCEEDED, "done"));
    for (Entry<String> entry : waiting) {
      assertSame(entry, queue.poll());
      assertTrue(queue.complete(entry.operationId, Phase.SUCCEEDED, "done"));
    }
    assertNull(queue.poll());
    assertFalse(queue.hasPending());
  }

  @Test
  public void fullQueueCanReplaceEligibleToggleWithoutChangingOtherEntries() {
    Queue<String> queue = new Queue<>(2, 4);
    Entry<String> old = toggle(queue, "a", true);
    Entry<String> other = enqueue(queue, Type.DELETE, "b");
    Entry<String> latest = toggle(queue, "a", false);
    assertEquals(Phase.SUPERSEDED, old.operation().phase);
    assertEquals(Phase.QUEUED, latest.operation().phase);
    assertEquals(Arrays.asList(other.operationId, latest.operationId, old.operationId), ids(queue));
    assertSame(other, queue.poll());
    assertTrue(queue.complete(other.operationId, Phase.SUCCEEDED, "done"));
    assertSame(latest, queue.poll());
  }

  @Test
  public void fullQueueRejectsAcrossABarrierWithoutSupersedingOlderIntent() {
    Queue<String> queue = new Queue<>(2, 4);
    Entry<String> old = toggle(queue, "a", true);
    Entry<String> barrier = enqueue(queue, Type.DELETE, "a");
    Entry<String> rejected = toggle(queue, "a", false);
    assertEquals(Phase.QUEUED, old.operation().phase);
    assertEquals(Phase.QUEUED, barrier.operation().phase);
    assertEquals(Phase.REJECTED, rejected.operation().phase);
    assertEquals(
        Arrays.asList(old.operationId, barrier.operationId, rejected.operationId), ids(queue));
  }

  @Test
  public void callbacksUseExactActiveIdAndCannotCompleteQueuedOrSuccessorEntries() {
    Queue<String> queue = new Queue<>();
    Entry<String> first = enqueue(queue, Type.INSTALL, "a");
    Entry<String> next = enqueue(queue, Type.DELETE, "a");
    assertFalse(queue.progress(first.operationId, Phase.RUNNING, "early", 50, "wrong"));
    assertFalse(queue.complete(first.operationId, Phase.FAILED, "early"));
    assertSame(first, queue.poll());
    Operation initial = first.operation();
    for (String wrongId : Arrays.asList(null, "missing", "a", next.operationId)) {
      assertFalse(queue.progress(wrongId, Phase.COMMITTING, "wrong", 99, "wrong", "wrong"));
      assertFalse(queue.complete(wrongId, Phase.SUCCEEDED, "wrong"));
      assertSame(initial, first.operation());
      assertSame(first, queue.active());
    }
    assertTrue(queue.complete(first.operationId, Phase.UNCONFIRMED, "no replay"));
    Operation result = first.operation();
    assertSame(next, queue.poll());
    Operation nextRunning = next.operation();
    assertFalse(queue.progress(first.operationId, Phase.RUNNING, "late", 100, "late"));
    assertFalse(queue.complete(first.operationId, Phase.SUCCEEDED, "late"));
    assertFalse(queue.cancelQueued(first.operationId));
    assertSame(result, queue.get(first.operationId));
    assertSame(nextRunning, next.operation());
    assertSame(next, queue.active());
  }

  @Test
  public void progressKeepsRequestImmutableAndRetainsOptionalExecutionIdentity() {
    Queue<Object> queue = new Queue<>();
    Object work = new Object();
    PluginSourceIdentity source = source(9);
    Entry<Object> entry = queue.enqueue(Type.SET_ENABLED, "a", false, "1.2", source, work);
    Operation queued = entry.request;
    List<Operation> snapshot = queue.snapshot();
    assertSame(entry, queue.poll());
    Operation running = entry.operation();
    assertTrue(queue.progress(entry.operationId, Phase.RUNNING, "work", 20, "tx-1", "task-1"));
    Operation progress = entry.operation();
    assertTrue(queue.progress(entry.operationId, Phase.COMMITTING, "commit", 90, null));
    Operation committing = entry.operation();
    assertEquals("tx-1", committing.transactionId);
    assertEquals("task-1", committing.backendTaskId);
    assertFalse(queue.progress(entry.operationId, Phase.RUNNING, "late", 10, "wrong"));
    assertSame(committing, entry.operation());
    assertTrue(queue.complete(entry.operationId, Phase.SUCCEEDED, "done"));
    assertEquals(100, entry.operation().percent);
    assertEquals("tx-1", entry.operation().transactionId);
    assertEquals("task-1", entry.operation().backendTaskId);
    assertEquals(Phase.QUEUED, queued.phase);
    assertEquals(-1, queued.percent);
    assertNull(queued.transactionId);
    assertEquals(Phase.RUNNING, running.phase);
    assertEquals(-1, running.percent);
    assertEquals(20, progress.percent);
    assertEquals(Phase.RUNNING, progress.phase);
    assertEquals(90, committing.percent);
    assertSame(queued, snapshot.get(0));
    assertSame(queued, entry.request);
    assertSame(work, entry.work);
    assertSame(source, entry.operation().source);
    assertEquals(Boolean.FALSE, entry.operation().desired);
    assertEquals("1.2", entry.operation().expectedVersion);
    assertEquals("a", entry.operation().targetKey);
    assertEquals(Type.SET_ENABLED, entry.operation().type);
    assertThrows(UnsupportedOperationException.class, snapshot::clear);
    assertTrue(Modifier.isFinal(Operation.class.getModifiers()));
    for (Field field : Operation.class.getFields())
      assertTrue(Modifier.isFinal(field.getModifiers()));
  }

  @Test
  public void bindingUnknownExecutionSourcePreservesRequestAndRecordsHistorySource() {
    Queue<String> queue = new Queue<>();
    Entry<String> entry = queue.enqueue(Type.INSTALL, "a", null, "1", null, "install");
    Operation request = entry.request;
    assertSame(entry, queue.poll());
    assertTrue(queue.progress(entry.operationId, Phase.RUNNING, "ready", 20, "tx", "backend"));
    Operation before = entry.operation();
    PluginSourceIdentity actual = source(2);
    assertTrue(queue.bindExecutionSource(entry.operationId, actual));
    Operation bound = entry.operation();
    assertNotSame(before, bound);
    assertSame(actual, bound.source);
    assertSame(bound, queue.get(entry.operationId));
    assertSame(bound, queue.current());
    assertSame(bound, queue.snapshot().get(0));
    assertSame(request, entry.request);
    assertNull(request.source);
    assertNull(before.source);
    assertEquals(before.operationId, bound.operationId);
    assertEquals(before.type, bound.type);
    assertEquals(before.targetKey, bound.targetKey);
    assertEquals(before.desired, bound.desired);
    assertEquals(before.expectedVersion, bound.expectedVersion);
    assertEquals(before.phase, bound.phase);
    assertEquals(before.message, bound.message);
    assertEquals(before.percent, bound.percent);
    assertEquals(before.transactionId, bound.transactionId);
    assertEquals(before.backendTaskId, bound.backendTaskId);
    assertEquals("install", entry.work);
    assertTrue(queue.complete(entry.operationId, Phase.SUCCEEDED, "done"));
    assertSame(actual, queue.get(entry.operationId).source);
    assertNull(entry.request.source);
  }

  @Test
  public void bindingRefreshesSameDataSourceGenerationWithoutRewritingInitialIdentity() {
    Queue<String> queue = new Queue<>();
    PluginSourceIdentity original = source(1);
    PluginSourceIdentity actual = source(2);
    Entry<String> entry = queue.enqueue(Type.UPDATE, "a", null, "1", original, "update");
    assertSame(entry, queue.poll());
    Operation before = entry.operation();
    assertNotEquals(original, actual);
    assertTrue(original.sameDataSource(actual));
    assertTrue(queue.bindExecutionSource(entry.operationId, actual));
    assertSame(actual, entry.operation().source);
    assertSame(original, entry.request.source);
    assertSame(original, before.source);
    assertTrue(queue.progress(entry.operationId, Phase.COMMITTING, "commit", 90, null));
    assertSame(actual, entry.operation().source);
    assertTrue(queue.complete(entry.operationId, Phase.SUCCEEDED, "done"));
    assertSame(actual, queue.get(entry.operationId).source);
  }

  @Test
  public void bindingRequiresAnExactRunningIdAndRejectsNullOrChangedDataSource() {
    Queue<String> queue = new Queue<>();
    Entry<String> entry = toggle(queue, "a", true);
    Entry<String> waiting = enqueue(queue, Type.INSTALL, "b");
    assertFalse(queue.bindExecutionSource(entry.operationId, source(2)));
    assertSame(entry.request, entry.operation());
    assertSame(entry, queue.poll());
    Operation before = entry.operation();
    for (String wrongId : Arrays.asList(null, "missing", waiting.operationId))
      assertFalse(queue.bindExecutionSource(wrongId, source(2)));
    assertFalse(queue.bindExecutionSource(entry.operationId, null));
    PluginSourceIdentity changedRoot =
        new PluginSourceIdentity(
            new File("/fixture/other-data"),
            "data",
            1,
            new File("/fixture/root"),
            "root",
            2,
            "sha",
            2);
    assertFalse(before.source.sameDataSource(changedRoot));
    assertFalse(queue.bindExecutionSource(entry.operationId, changedRoot));
    assertSame(before, entry.operation());
    assertSame(entry, queue.active());
    assertEquals(Phase.QUEUED, waiting.operation().phase);
  }

  @Test
  public void bindingCannotChangeCommittingOrTerminalEntriesOrTheirSuccessor() {
    for (Phase terminal : Phase.values()) {
      if (terminal == Phase.QUEUED || terminal == Phase.RUNNING || terminal == Phase.COMMITTING)
        continue;
      Queue<String> queue = new Queue<>();
      Entry<String> entry = enqueue(queue, Type.INSTALL, "a");
      Entry<String> next = enqueue(queue, Type.INSTALL, "b");
      assertSame(entry, queue.poll());
      assertTrue(queue.bindExecutionSource(entry.operationId, source(1)));
      assertTrue(queue.progress(entry.operationId, Phase.COMMITTING, "commit", 90, null));
      Operation committing = entry.operation();
      assertFalse(queue.bindExecutionSource(entry.operationId, source(2)));
      assertSame(committing, entry.operation());
      assertTrue(queue.complete(entry.operationId, terminal, "done"));
      Operation result = entry.operation();
      assertFalse(queue.bindExecutionSource(entry.operationId, source(2)));
      assertSame(next, queue.poll());
      Operation nextRunning = next.operation();
      assertFalse(queue.bindExecutionSource(entry.operationId, source(2)));
      assertSame(result, entry.operation());
      assertSame(result, queue.get(entry.operationId));
      assertSame(nextRunning, next.operation());
      assertSame(next, queue.active());
    }
  }

  @Test
  public void wrongPhaseCannotReleaseActiveOrReviveTerminalResults() {
    for (Phase terminal : Phase.values()) {
      if (terminal == Phase.QUEUED || terminal == Phase.RUNNING || terminal == Phase.COMMITTING)
        continue;
      Queue<String> queue = new Queue<>();
      Entry<String> entry = enqueue(queue, Type.INSTALL, "a");
      assertSame(entry, queue.poll());
      for (Phase phase : Arrays.asList(null, Phase.QUEUED, Phase.RUNNING, Phase.COMMITTING)) {
        assertFalse(queue.complete(entry.operationId, phase, "wrong"));
        assertSame(entry, queue.active());
      }
      assertFalse(queue.progress(entry.operationId, terminal, "wrong", 100, null));
      assertFalse(queue.progress(entry.operationId, null, "wrong", 100, null));
      assertFalse(queue.progress(entry.operationId, Phase.QUEUED, "wrong", 100, null));
      assertTrue(queue.complete(entry.operationId, terminal, "final"));
      Operation result = entry.operation();
      assertFalse(result.pending());
      assertFalse(queue.hasPending());
      for (Phase phase : Phase.values()) {
        assertFalse(queue.complete(entry.operationId, phase, "rewrite"));
        assertFalse(queue.progress(entry.operationId, phase, "rewrite", 99, "rewrite"));
        assertSame(result, entry.operation());
        assertSame(result, queue.get(entry.operationId));
      }
    }
  }

  @Test
  public void cancelOnlyRemovesTheSelectedWaitingEntryAndFreesItsCapacity() {
    Queue<String> queue = new Queue<>(2, 4);
    Entry<String> active = enqueue(queue, Type.INSTALL, "active");
    assertSame(active, queue.poll());
    Entry<String> first = enqueue(queue, Type.INSTALL, "a");
    Entry<String> second = enqueue(queue, Type.INSTALL, "b");
    assertFalse(queue.cancelQueued("missing"));
    assertFalse(queue.cancelQueued(null));
    assertFalse(queue.cancelQueued(active.operationId));
    assertTrue(queue.cancelQueued(first.operationId));
    Operation cancelled = first.operation();
    assertEquals(Phase.CANCELLED, cancelled.phase);
    assertFalse(queue.cancelQueued(first.operationId));
    assertSame(cancelled, queue.get(first.operationId));
    Entry<String> third = enqueue(queue, Type.INSTALL, "c");
    assertEquals(Phase.QUEUED, third.operation().phase);
    assertSame(active, queue.active());
    assertTrue(queue.complete(active.operationId, Phase.SUCCEEDED, "done"));
    assertSame(second, queue.poll());
    assertTrue(queue.complete(second.operationId, Phase.SUCCEEDED, "done"));
    assertSame(third, queue.poll());
  }

  @Test
  public void boundedHistoryIsIndependentOfActiveWaitingAndRetainedEntryHandles() {
    Queue<String> queue = new Queue<>(1, 2);
    Entry<String> active = enqueue(queue, Type.INSTALL, "active");
    assertSame(active, queue.poll());
    Entry<String> superseded = toggle(queue, "a", true);
    Entry<String> cancelled = toggle(queue, "a", false);
    Entry<String> rejected = enqueue(queue, Type.INSTALL, "b");
    assertEquals(4, queue.snapshot().size());
    assertNotNull(queue.get(superseded.operationId));
    assertTrue(queue.cancelQueued(cancelled.operationId));
    assertNull(queue.get(superseded.operationId));
    assertEquals(Phase.SUPERSEDED, superseded.operation().phase);
    assertSame(active, queue.active());
    Entry<String> waiting = enqueue(queue, Type.INSTALL, "c");
    // 查询旧结果不改变按完成时间淘汰的顺序。
    assertNotNull(queue.get(rejected.operationId));
    assertTrue(queue.complete(active.operationId, Phase.INVALIDATED, "changed"));
    assertNull(queue.get(rejected.operationId));
    assertEquals(Phase.REJECTED, rejected.operation().phase);
    assertSame(cancelled.operation(), queue.get(cancelled.operationId));
    assertEquals(
        Arrays.asList(waiting.operationId, active.operationId, cancelled.operationId), ids(queue));
    assertSame(waiting, queue.poll());
  }

  @Test
  public void defaultHistoryRetainsOnlyTheMostRecentSixtyFourCompletions() {
    Queue<String> queue = new Queue<>();
    List<Entry<String>> all = new ArrayList<>();
    for (int i = 0; i < 65; i++) {
      Entry<String> entry = enqueue(queue, Type.INSTALL, "a");
      all.add(entry);
      assertSame(entry, queue.poll());
      assertTrue(queue.complete(entry.operationId, Phase.SUCCEEDED, "done"));
    }
    assertEquals(64, queue.snapshot().size());
    assertNull(queue.get(all.get(0).operationId));
    assertEquals(Phase.SUCCEEDED, all.get(0).operation().phase);
    assertSame(all.get(64).operation(), queue.snapshot().get(0));
    assertSame(all.get(1).operation(), queue.snapshot().get(63));
    assertFalse(queue.hasPending());
    assertNull(queue.current());
  }

  @Test
  public void invalidRequestsLeaveExistingWorkUntouched() {
    assertThrows(IllegalArgumentException.class, () -> new Queue<>(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new Queue<>(1, 0));
    Queue<String> queue = new Queue<>();
    Entry<String> entry = toggle(queue, "a", true);
    assertThrows(
        IllegalArgumentException.class,
        () -> queue.enqueue(Type.SET_ENABLED, "a", null, "", null, "work"));
    assertThrows(
        NullPointerException.class, () -> queue.enqueue(null, "a", null, "", null, "work"));
    assertThrows(
        NullPointerException.class,
        () -> queue.enqueue(Type.INSTALL, null, null, "", null, "work"));
    assertThrows(
        NullPointerException.class, () -> queue.enqueue(Type.INSTALL, "a", null, "", null, null));
    assertEquals(Collections.singletonList(entry.operationId), ids(queue));
    assertSame(entry, queue.poll());
  }

  @Test
  public void concurrentEnqueuesCannotExceedCapacityOrLoseRejectedResults() throws Exception {
    Queue<String> queue = new Queue<>();
    Entry<String> active = enqueue(queue, Type.INSTALL, "active");
    assertSame(active, queue.poll());
    ExecutorService workers = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Entry<String>>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < 40; i++) {
        String key = "plugin-" + i;
        futures.add(
            workers.submit(
                () -> {
                  await(start);
                  return enqueue(queue, Type.INSTALL, key);
                }));
      }
      start.countDown();
      int queued = 0;
      int rejected = 0;
      Set<String> allIds = new HashSet<>();
      for (Future<Entry<String>> future : futures) {
        Entry<String> entry = future.get(5, TimeUnit.SECONDS);
        assertTrue(allIds.add(entry.operationId));
        assertSame(entry.operation(), queue.get(entry.operationId));
        if (entry.operation().phase == Phase.QUEUED) queued++;
        else if (entry.operation().phase == Phase.REJECTED) rejected++;
        else fail("并发入队产生非预期状态");
      }
      assertEquals(32, queued);
      assertEquals(8, rejected);
      assertEquals(41, queue.snapshot().size());
      assertSame(active, queue.active());
      assertNull(queue.poll());
    } finally {
      start.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void simultaneousPollsHaveExactlyOneWinner() throws Exception {
    Queue<String> queue = new Queue<>();
    Entry<String> first = enqueue(queue, Type.INSTALL, "a");
    Entry<String> second = enqueue(queue, Type.INSTALL, "b");
    ExecutorService workers = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Entry<String>>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < 8; i++)
        futures.add(
            workers.submit(
                () -> {
                  await(start);
                  return queue.poll();
                }));
      start.countDown();
      int winners = 0;
      for (Future<Entry<String>> future : futures) {
        Entry<String> entry = future.get(5, TimeUnit.SECONDS);
        if (entry != null) {
          winners++;
          assertSame(first, entry);
        }
      }
      assertEquals(1, winners);
      assertSame(first, queue.active());
      assertEquals(Phase.QUEUED, second.operation().phase);
    } finally {
      start.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static Entry<String> enqueue(Queue<String> queue, Type type, String key) {
    return queue.enqueue(type, key, null, null, null, "work:" + key);
  }

  private static Entry<String> toggle(Queue<String> queue, String key, boolean enabled) {
    return queue.enqueue(Type.SET_ENABLED, key, enabled, "1", source(1), "work:" + enabled);
  }

  private static List<String> ids(Queue<?> queue) {
    List<String> ids = new ArrayList<>();
    for (Operation operation : queue.snapshot()) ids.add(operation.operationId);
    return ids;
  }

  private static PluginSourceIdentity source(long generation) {
    return new PluginSourceIdentity(
        new File("/fixture/data"),
        "data",
        1,
        new File("/fixture/root"),
        "root",
        2,
        "sha",
        generation);
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertTrue("并发测试起始信号超时", latch.await(5, TimeUnit.SECONDS));
  }
}
