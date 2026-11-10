package com.deepseekharness.app.plugins;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 插件操作的纯逻辑排队与结果模型；不申请环境票、不执行工作，也不决定是否重放。 */
public final class PluginOperationQueue {
  public static final int DEFAULT_WAITING_CAPACITY = 32;
  public static final int DEFAULT_HISTORY_CAPACITY = 64;

  private PluginOperationQueue() {}

  public enum Type {
    SET_ENABLED,
    INSTALL,
    DELETE,
    UPDATE,
    CHECK_UPDATES,
    ROLLBACK,
    INSPECT,
    IMPORT,
    EXPORT,
    CONFIRM_PREVIEW,
    DISCARD_PREVIEW,
    REVIEW_RESTORED,
    REVIEW_LEGACY,
    SAFE_MODE
  }

  public enum Phase {
    QUEUED,
    RUNNING,
    COMMITTING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    SUPERSEDED,
    INVALIDATED,
    UNCONFIRMED,
    REJECTED
  }

  /** 某一时刻的不可变快照；展示名称和阶段文案由 UI 决定。 */
  public static final class Operation {
    public final String operationId;
    public final Type type;
    public final String targetKey;
    public final Boolean desired;
    public final String expectedVersion;
    public final PluginSourceIdentity source;
    public final Phase phase;
    public final String message;

    /** -1 表示尚无可用进度，否则为 0 到 100。 */
    public final int percent;

    public final String transactionId;
    public final String backendTaskId;

    private Operation(
        String operationId,
        Type type,
        String targetKey,
        Boolean desired,
        String expectedVersion,
        PluginSourceIdentity source,
        Phase phase,
        String message,
        int percent,
        String transactionId,
        String backendTaskId) {
      this.operationId = operationId;
      this.type = type;
      this.targetKey = targetKey;
      this.desired = desired;
      this.expectedVersion = expectedVersion;
      this.source = source;
      this.phase = phase;
      this.message = message == null ? "" : message;
      this.percent = Math.max(-1, Math.min(100, percent));
      this.transactionId = transactionId;
      this.backendTaskId = backendTaskId;
    }

    public boolean pending() {
      return phase == Phase.QUEUED || phase == Phase.RUNNING || phase == Phase.COMMITTING;
    }

    private Operation withSource(PluginSourceIdentity source) {
      return new Operation(
          operationId,
          type,
          targetKey,
          desired,
          expectedVersion,
          source,
          phase,
          message,
          percent,
          transactionId,
          backendTaskId);
    }

    private Operation withState(
        Phase phase, String message, int percent, String transactionId, String backendTaskId) {
      return new Operation(
          operationId,
          type,
          targetKey,
          desired,
          expectedVersion,
          source,
          phase,
          message,
          percent,
          transactionId,
          backendTaskId);
    }
  }

  /** 入队时冻结工作引用及原请求；W 应由调用方准备为不可变工作快照，队列不复制或调用它。 即使历史已淘汰，持有者仍能从 operation() 读取这个条目的最终结果。 */
  public static final class Entry<W> {
    public final String operationId;
    public final W work;
    public final Operation request;
    private volatile Operation operation;

    private Entry(Operation request, W work) {
      this.operationId = request.operationId;
      this.work = work;
      this.request = request;
      this.operation = request;
    }

    public Operation operation() {
      return operation;
    }
  }

  /** 所有状态变更在同一监视器内完成；最多一个 active，等待容量不包括 active 和历史。 */
  public static final class Queue<W> {
    private final int waitingCapacity;
    private final int historyCapacity;
    private final Deque<Entry<W>> waiting = new ArrayDeque<>();
    // 只保存操作快照，不让有界历史继续持有工作或执行器引用。
    private final Map<String, Operation> history = new LinkedHashMap<>();
    private Entry<W> active;

    public Queue() {
      this(DEFAULT_WAITING_CAPACITY, DEFAULT_HISTORY_CAPACITY);
    }

    public Queue(int waitingCapacity, int historyCapacity) {
      if (waitingCapacity < 1 || historyCapacity < 1)
        throw new IllegalArgumentException("PLUGIN_QUEUE_CAPACITY_INVALID");
      this.waitingCapacity = waitingCapacity;
      this.historyCapacity = historyCapacity;
    }

    /**
     * 每次请求都有独立身份。满队列返回 REJECTED 条目并写入历史；不会抛弃请求结果。 同目标最后一个等待请求为启停，且双方来源已知且相等、预期版本相等时，才允许替换并将新请求排到队尾。
     */
    public synchronized Entry<W> enqueue(
        Type type,
        String targetKey,
        Boolean desired,
        String expectedVersion,
        PluginSourceIdentity source,
        W work) {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(targetKey, "targetKey");
      Objects.requireNonNull(work, "work");
      if (type == Type.SET_ENABLED && desired == null)
        throw new IllegalArgumentException("PLUGIN_ENABLE_DESIRED_REQUIRED");

      Entry<W> superseded =
          type == Type.SET_ENABLED ? lastQueuedToggle(targetKey, expectedVersion, source) : null;
      boolean rejected = waiting.size() >= waitingCapacity && superseded == null;
      Operation operation =
          new Operation(
              UUID.randomUUID().toString(),
              type,
              targetKey,
              desired,
              expectedVersion,
              source,
              rejected ? Phase.REJECTED : Phase.QUEUED,
              rejected ? "PLUGIN_QUEUE_FULL" : "",
              -1,
              null,
              null);
      Entry<W> entry = new Entry<>(operation, work);
      if (rejected) {
        remember(entry, operation);
        return entry;
      }
      if (superseded != null) {
        waiting.remove(superseded);
        finish(superseded, Phase.SUPERSEDED, "PLUGIN_OPERATION_SUPERSEDED");
      }
      waiting.addLast(entry);
      return entry;
    }

    private Entry<W> lastQueuedToggle(
        String targetKey, String expectedVersion, PluginSourceIdentity source) {
      if (source == null) return null;
      Iterator<Entry<W>> reverse = waiting.descendingIterator();
      while (reverse.hasNext()) {
        Entry<W> entry = reverse.next();
        Operation request = entry.request;
        if (request.targetKey.equals(targetKey))
          return request.type == Type.SET_ENABLED
                  && source.equals(request.source)
                  && Objects.equals(expectedVersion, request.expectedVersion)
              ? entry
              : null;
      }
      return null;
    }

    /** 有 active 或没有等待项时返回 null；取得条目时原子地进入 RUNNING。 */
    public synchronized Entry<W> poll() {
      if (active != null) return null;
      Entry<W> entry = waiting.pollFirst();
      if (entry == null) return null;
      Operation before = entry.operation;
      entry.operation =
          before.withState(
              Phase.RUNNING,
              before.message,
              before.percent,
              before.transactionId,
              before.backendTaskId);
      active = entry;
      return entry;
    }

    /** 仅将精确 RUNNING 请求绑定到执行器已核验的同一数据来源；原请求输入始终不变。 */
    public synchronized boolean bindExecutionSource(
        String operationId, PluginSourceIdentity source) {
      if (source == null || !isActive(operationId) || active.operation.phase != Phase.RUNNING)
        return false;
      Operation before = active.operation;
      if (before.source != null && !before.source.sameDataSource(source)) return false;
      active.operation = before.withSource(source);
      return true;
    }

    public synchronized boolean progress(
        String operationId, Phase phase, String message, int percent, String transactionId) {
      return progress(operationId, phase, message, percent, transactionId, null);
    }

    /** 只接受精确 active 身份的运行阶段；COMMITTING 不会被迟到的 RUNNING 回调退回。 可选事务或后端任务身份传 null 时保留之前已经观察到的值。 */
    public synchronized boolean progress(
        String operationId,
        Phase phase,
        String message,
        int percent,
        String transactionId,
        String backendTaskId) {
      if (!isActive(operationId) || (phase != Phase.RUNNING && phase != Phase.COMMITTING))
        return false;
      Operation before = active.operation;
      if (before.phase == Phase.COMMITTING && phase == Phase.RUNNING) return false;
      active.operation =
          before.withState(
              phase,
              message,
              percent,
              transactionId == null ? before.transactionId : transactionId,
              backendTaskId == null ? before.backendTaskId : backendTaskId);
      return true;
    }

    /** 只完成精确 active 身份，终态快照存入历史后释放唯一执行槽；不自动启动后继。 */
    public synchronized boolean complete(String operationId, Phase phase, String message) {
      if (!isActive(operationId) || !terminal(phase)) return false;
      finish(active, phase, message);
      active = null;
      return true;
    }

    /** 只取消尚未 poll 的请求；运行、提交与终态条目均不受影响。 */
    public synchronized boolean cancelQueued(String operationId) {
      Iterator<Entry<W>> entries = waiting.iterator();
      while (entries.hasNext()) {
        Entry<W> entry = entries.next();
        if (entry.operationId.equals(operationId)) {
          entries.remove();
          finish(entry, Phase.CANCELLED, "PLUGIN_OPERATION_CANCELLED");
          return true;
        }
      }
      return false;
    }

    /** 快照顺序为 active、等待 FIFO、最近完成的历史优先；列表与元素均不可变。 */
    public synchronized List<Operation> snapshot() {
      List<Operation> result = new ArrayList<>();
      if (active != null) result.add(active.operation);
      for (Entry<W> entry : waiting) result.add(entry.operation);
      List<Operation> completed = new ArrayList<>(history.values());
      Collections.reverse(completed);
      result.addAll(completed);
      return Collections.unmodifiableList(result);
    }

    /** 未知或已淘汰的终态身份返回 null；读取不会延长历史保留期。 */
    public synchronized Operation get(String operationId) {
      if (isActive(operationId)) return active.operation;
      for (Entry<W> entry : waiting)
        if (entry.operationId.equals(operationId)) return entry.operation;
      return history.get(operationId);
    }

    public synchronized boolean hasPending() {
      return active != null || !waiting.isEmpty();
    }

    public synchronized Entry<W> active() {
      return active;
    }

    /** 返回 active，否则返回首个等待项；纯历史不作为当前操作。 */
    public synchronized Operation current() {
      Entry<W> current = active == null ? waiting.peekFirst() : active;
      return current == null ? null : current.operation;
    }

    private boolean isActive(String operationId) {
      return active != null && active.operationId.equals(operationId);
    }

    private static boolean terminal(Phase phase) {
      return phase != null
          && phase != Phase.QUEUED
          && phase != Phase.RUNNING
          && phase != Phase.COMMITTING;
    }

    private void finish(Entry<W> entry, Phase phase, String message) {
      Operation before = entry.operation;
      remember(
          entry,
          before.withState(
              phase,
              message,
              phase == Phase.SUCCEEDED ? 100 : before.percent,
              before.transactionId,
              before.backendTaskId));
    }

    private void remember(Entry<W> entry, Operation operation) {
      entry.operation = operation;
      history.put(operation.operationId, operation);
      while (history.size() > historyCapacity) {
        Iterator<String> oldest = history.keySet().iterator();
        oldest.next();
        oldest.remove();
      }
    }
  }
}
