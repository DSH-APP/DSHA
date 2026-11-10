package com.deepseekharness.app.assistant;

import com.deepseekharness.app.util.UiText;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.deepseekharness.app.control.PhoneControlCoordinator;
import com.deepseekharness.app.util.SensitiveData;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** One selected full conversation. All mutations serialize and are never retried. */
public final class AssistantSessionController implements AutoCloseable {
  public record Snapshot(
      AssistantBinding binding,
      AssistantSessionState.Phase phase,
      String turnId,
      String taskId,
      String text,
      String detail,
      String draft,
      long draftRevision,
      boolean expanded,
      boolean terminalKnown,
      boolean paused) {}

  private static volatile AssistantSessionController active;

  public static AssistantSessionController active() {
    return active;
  }

  private final AssistantBinding binding;
  private final AssistantBackend backend;
  private final PhoneControlCoordinator fence;
  private final AssistantSessionState state = new AssistantSessionState();
  private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
  private final AtomicBoolean cancelling = new AtomicBoolean();
  private volatile boolean closed, expanded;
  private volatile boolean suppressCancellation;
  private volatile String text = "", draft = "";
  private volatile long draftRevision;
  private volatile String cancelAttempt = null;
  private Runnable deferredFinish;
  private long pollRevision;

  private record PollObservation(long revision, String turnId, String taskId, JSONObject args) {}

  private final java.util.function.Consumer<
          com.deepseekharness.app.control.ControlTaskAuthority.Binding>
      cancelled = this::cancelTurn;

  public AssistantSessionController(
      Context context, AssistantBinding binding, AssistantBackend backend) {
    this(context, binding, backend, null);
  }

  AssistantSessionController(
      Context context, AssistantBinding binding, AssistantBackend backend, String verifiedTurn) {
    this.binding = binding;
    this.backend = backend;
    if (verifiedTurn != null) state.observed(true, verifiedTurn, binding.sessionId());
    fence = PhoneControlCoordinator.get(context);
    AssistantSessionController previous = active;
    if (previous != null) previous.close();
    active = this;
    fence.setTurnCancellation(cancelled);
    worker.scheduleWithFixedDelay(this::poll, 0, 900, TimeUnit.MILLISECONDS);
  }

  /** Only the tool attachment path calls this after an exact live Host status receipt. */
  synchronized boolean followVerifiedTask(String turnId) {
    if (closed || expanded || state.paused()) return false;
    if (!turnId.equals(state.turnId()) || !binding.sessionId().equals(state.taskId()))
      pollRevision++;
    state.observed(true, turnId, binding.sessionId());
    changed();
    return true;
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(
        binding,
        state.phase(),
        state.turnId(),
        state.taskId(),
        text,
        state.detail(),
        draft,
        draftRevision,
        expanded,
        state.terminalKnown(),
        state.paused());
  }

  public void addListener(Runnable listener) {
    listeners.addIfAbsent(listener);
  }

  public void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  public synchronized void setDraft(String value) {
    String next = value == null ? "" : value;
    if (next.equals(draft)) return;
    draft = next;
    draftRevision++;
    changed();
  }

  private synchronized void admittedDraft(String submitted) {
    // A later edit belongs to the next prompt, even if this admission arrived late.
    if (draft.equals(submitted)) setDraft("");
  }

  /**
   * 展开只改界面：面板从不可聚焦，不是输入法目标，不暂停手机操作也不取消对话。
   * 面板几何变化后，下一次手机操作前必须重新观察（{@link #panelGeometryChanged()}）。
   * 用户输入期间由 {@link #beginComposing(String)} 让手机动作原地等待。
   */
  public void expand() {
    if (!expanded) {
      expanded = true;
      panelGeometryChanged();
    }
    changed();
  }

  public void collapse() {
    Runnable finish;
    synchronized (this) {
      boolean wasExpanded = expanded;
      expanded = false;
      finish = deferredFinish;
      deferredFinish = null;
      if (wasExpanded) panelGeometryChanged();
    }
    changed();
    // 本轮在面板展开时结束：收起后才关窗，避免窗口在用户手指下消失。
    if (finish != null) main.post(finish);
  }

  /** Called by turn-end cleanup only while expanded; a later new prompt discards it. */
  synchronized void deferFinishUntilCollapsed(Runnable finish) {
    deferredFinish = finish;
  }

  /** 用户拖动了悬浮窗：与展开/收起相同，只要求下一次手机操作前重新观察。 */
  void panelMoved() {
    panelGeometryChanged();
  }

  /** 自有面板位置或大小变化：作废旧观察与在途读屏，但不暂停、不取消对话。 */
  private void panelGeometryChanged() {
    var task = fence.snapshot();
    if (task.binding() == null || task.paused() || !owns(task.binding())) return;
    suppressCancellation = true;
    try {
      fence.requireObservation(task.binding());
    } catch (IllegalStateException changedConcurrently) {
      // 任务已在别处暂停或更换；那条路径自己负责围栏。
    } finally {
      suppressCancellation = false;
    }
  }

  /**
   * 用户开始给 AI 输入（窗内输入框、编辑器、语音）。不暂停、不取消：手机动作在桥上原地等待，
   * 读屏照常并标注 userComposing。{@code surface} 区分输入来源，避免交接时被提前放开。
   */
  public void beginComposing(String surface) {
    if (closed) return;
    fence.compose().begin(surface);
    changed();
  }

  /** 没发送就离开该输入来源：放开等待，并要求 AI 下一步先重新观察屏幕。 */
  public void endComposing(String surface) {
    if (closed) return;
    if (fence.compose().release(surface)) panelGeometryChanged();
    changed();
  }

  public boolean composing() {
    return fence.compose().composing();
  }

  public void pause(String reason) {
    state.pause(reason);
    suppressCancellation = true;
    try {
      fence.pause(reason);
    } finally {
      suppressCancellation = false;
    }
    requestCancellation(state.turnId());
    changed();
  }

  public void stop() {
    state.stop();
    suppressCancellation = true;
    try {
      fence.stop("ASSISTANT_STOPPED");
    } finally {
      suppressCancellation = false;
    }
    requestCancellation(state.turnId());
    changed();
  }

  private boolean owns(com.deepseekharness.app.control.ControlTaskAuthority.Binding control) {
    return backend.ownsControlBinding(control)
        && binding.sessionId().equals(control.taskSessionId());
  }

  synchronized boolean ownsTask(com.deepseekharness.app.control.ControlTaskAuthority.Binding task) {
    return !closed && owns(task) && task.turnId().equals(state.turnId());
  }

  private void cancelTurn(com.deepseekharness.app.control.ControlTaskAuthority.Binding previous) {
    // The native fence has already invalidated every queued screen operation.
    if (closed || suppressCancellation || !owns(previous)) return;
    var authority = fence.snapshot();
    if (authority.paused()
        && state.phase() != AssistantSessionState.Phase.UNKNOWN
        && state.phase() != AssistantSessionState.Phase.DISCONNECTED)
      state.pause(authority.reason());
    changed();
    requestCancellation(previous.turnId());
  }

  private void requestCancellation(String turn) {
    if (closed) return;
    if (!cancelling.compareAndSet(false, true)) return;
    String identity = turn + "\0" + binding.sessionId();
    if (identity.equals(cancelAttempt)) {
      cancelling.set(false);
      return;
    }
    cancelAttempt = identity;
    worker.execute(
        () -> {
          try {
            backend.call("sessions.cancel", args().put("turnId", turn));
          } catch (Exception unknown) {
            state.unknown(
                UiText.choose(
                    "取消结果未知；正在核对会话状态", "Cancellation result unknown; checking conversation state"));
          } finally {
            cancelling.set(false);
            changed();
          }
        });
  }

  public boolean resume() {
    return sendInternal(
        UiText.choose(
            "继续当前任务。操作手机前必须重新观察当前屏幕，再决定下一步；不要重放结果未知的操作。",
            "Continue the current task. Observe the current phone screen again before deciding the next action. Do not replay operations with unknown results."),
        true);
  }

  public boolean send(String message) {
    if (steerable()) return steer(message);
    return sendInternal(message, false);
  }

  /** 本轮仍在运行且没有被暂停 / 停止：新消息插进当前轮次，AI 下一步即可看到。 */
  private synchronized boolean steerable() {
    var phase = state.phase();
    return !closed
        && !state.paused()
        && !state.terminalKnown()
        && (phase == AssistantSessionState.Phase.RUNNING
            || phase == AssistantSessionState.Phase.SENDING);
  }

  private synchronized boolean steer(String message) {
    if (message == null || message.isBlank() || message.length() > 32768 || closed) return false;
    String requestId = UUID.randomUUID().toString();
    worker.execute(
        () -> {
          try {
            if (!backend.current() || closed) throw new java.io.IOException("CONNECTION_CHANGED");
            JSONObject request =
                args().put("text", message).put("requestId", requestId).put("mode", "steer");
            JSONObject result = backend.call("sessions.send", request);
            if (!result.optBoolean("accepted"))
              throw new java.io.IOException("NO_ADMISSION_RECEIPT");
            // 消息已进入当前轮次：唤醒正在等待的手机动作，让它返回“用户发来新消息”。
            fence.compose().sent();
            panelGeometryChanged();
            admittedDraft(message);
          } catch (AssistantBackend.Rejected rejected) {
            // 明确未接收：放开等待，AI 照原任务继续；草稿保留。
            fence.compose().release(fence.compose().surface());
            state.failed(
                UiText.choose(
                    "消息未被接收。请打开完整会话检查模型或会话设置；草稿已保留。",
                    "Message was not admitted. Open the full conversation to check its model or settings. Your draft is retained."));
          } catch (Exception failure) {
            // 结果未知：不重发。按“可能已收到”唤醒等待者，让 AI 先读会话再决定，不在原地卡满 60 秒。
            fence.compose().sent();
            state.failed(
                UiText.choose(
                    "发送结果未知；请先核对会话，勿重复发送",
                    "Send result unknown; check the conversation before sending again"));
          }
          changed();
        });
    return true;
  }

  private synchronized boolean sendInternal(String message, boolean continuation) {
    if (message == null || message.isBlank() || message.length() > 32768 || closed) return false;
    if (!state.terminalKnown() || cancelling.get()) {
      changed();
      return false;
    }
    var old = fence.snapshot().binding();
    if (old != null && (!owns(old) || !old.turnId().equals(state.turnId()))) {
      state.failed(
          UiText.choose(
              "另一项手机任务仍占用控制权。请先结束该任务，再发送或继续。",
              "Another phone task still owns control. End that task before sending or continuing."));
      changed();
      return false;
    }
    try {
      if (continuation) state.resume();
      else state.newTask();
      pollRevision++;
      // Retire only the old selected conversation's screen ownership, after real idle.
      suppressCancellation = true;
      try {
        if (old != null && owns(old)) fence.stop("CONTINUATION");
        fence.resume();
      } finally {
        suppressCancellation = false;
      }
      expanded = false;
      deferredFinish = null;
      cancelAttempt = null;
      state.sending();
    } catch (IllegalStateException blocked) {
      changed();
      return false;
    }
    String requestId = UUID.randomUUID().toString();
    changed();
    worker.execute(
        () -> {
          try {
            if (!backend.current() || closed) throw new java.io.IOException("CONNECTION_CHANGED");
            JSONObject request = args().put("text", message).put("requestId", requestId);
            JSONObject result = backend.call("sessions.send", request);
            if (!result.optBoolean("accepted"))
              throw new java.io.IOException("NO_ADMISSION_RECEIPT");
            state.accepted();
            fence.compose().sent();
            if (!continuation) admittedDraft(message);
          } catch (AssistantBackend.Rejected rejected) {
            // Only the Host's explicit pre-admission errors get this path. Transport
            // loss still remains UNKNOWN; no prompt is retried automatically.
            fence.compose().release(fence.compose().surface());
            suppressCancellation = true;
            try {
              fence.pause("ASSISTANT_PROMPT_REJECTED");
            } finally {
              suppressCancellation = false;
            }
            state.rejected(
                "session/model-unavailable".equals(rejected.getMessage())
                    ? UiText.choose(
                        "消息尚未发送：请打开完整 DSH 会话设置可用模型和 API Key，然后重新发送。草稿已保留。",
                        "Message not sent. Open the full DSH conversation to configure an available model and API key, then send again. Your draft is retained.")
                    : UiText.choose(
                        "消息未被接收。请打开完整会话检查模型或会话设置；草稿已保留。",
                        "Message was not admitted. Open the full conversation to check its model or settings. Your draft is retained."));
          } catch (Exception failure) {
            fence.compose().sent();
            state.unknown(
                UiText.choose(
                    "发送结果未知；请先核对会话，勿重复发送",
                    "Send result unknown; check the conversation before sending again"));
          }
          changed();
        });
    return true;
  }

  private JSONObject args() throws org.json.JSONException {
    return new JSONObject()
        .put("sessionId", binding.sessionId())
        .put("hostInstanceId", binding.hostInstanceId())
        .put("turnId", state.turnId())
        .put("taskId", state.taskId());
  }

  private void poll() {
    final PollObservation observed;
    synchronized (this) {
      if (closed || active != this || state.phase() == AssistantSessionState.Phase.DISCONNECTED)
        return;
      try {
        observed = new PollObservation(pollRevision, state.turnId(), state.taskId(), args());
      } catch (org.json.JSONException invalid) {
        return;
      }
    }
    try {
      // Host I/O never holds the controller monitor. A close or verified next turn can finish
      // while this request is blocked; its response then has no authority over that successor.
      if (!backend.current()) {
        pollFailed(observed, true);
        return;
      }
      JSONObject value = backend.call("sessions.status", observed.args());
      if (!binding.sessionId().equals(value.optString("sessionId")) || !value.has("running"))
        throw new java.io.IOException("STATUS_IDENTITY_OR_STATE_MISSING");
      String host = value.optString("hostInstanceId", binding.hostInstanceId());
      if (!binding.hostInstanceId().equals(host))
        throw new java.io.IOException("HOST_INSTANCE_CHANGED");
      var transcript = value.optJSONArray("messages");
      final String nextText;
      if (transcript == null) nextText = SensitiveData.redact(value.optString("text"));
      else {
        StringBuilder rendered = new StringBuilder();
        for (int index = Math.max(0, transcript.length() - 8);
            index < transcript.length();
            index++) {
          var message = transcript.optJSONObject(index);
          if (message == null) continue;
          String body = message.optString("text");
          if (body.isBlank()) continue;
          if (rendered.length() > 0) rendered.append("\n\n");
          rendered
              .append(
                  "user".equals(message.optString("role"))
                      ? UiText.choose("你：\n", "You:\n")
                      : UiText.choose("DSH：\n", "DSH:\n"))
              .append(body);
        }
        String stream = value.optString("streamText");
        if (!stream.isBlank())
          rendered
              .append(UiText.choose("\n\nDSH（生成中）：\n", "\n\nDSH (generating):\n"))
              .append(stream);
        nextText = SensitiveData.redact(rendered.toString());
      }
      boolean running = value.getBoolean("running");
      String turn = value.optString("turnId"), task = value.optString("taskId");
      synchronized (this) {
        if (!pollCurrent(observed)) return;
        text = nextText;
        if (!turn.equals(state.turnId()) || !task.equals(state.taskId())) pollRevision++;
        state.observed(running, turn, task);
        if (value.optBoolean("failed"))
          state.failed(
              UiText.choose(
                  "本轮执行失败。请打开完整 DSH 会话查看错误，并检查模型、密钥或网络设置。",
                  "This turn failed. Open the full DSH conversation to review the error and check your model, key, or network settings."));
        changed();
      }
    } catch (Exception failure) {
      pollFailed(observed, false);
    }
  }

  /** Called only under this monitor, also shared with followVerifiedTask/send/close. */
  private boolean pollCurrent(PollObservation observed) {
    return !closed
        && active == this
        && pollRevision == observed.revision()
        && state.turnId().equals(observed.turnId())
        && state.taskId().equals(observed.taskId());
  }

  private void pollFailed(PollObservation observed, boolean disconnected) {
    // Backend ownership checks remain outside the monitor as well. An idle/unrelated view has
    // no authority to pause a task merely because its own status could not be fetched.
    com.deepseekharness.app.control.ControlTaskAuthority.Lease owned = null;
    try {
      var screen = fence.snapshot();
      var task = screen.binding();
      if (task != null && task.turnId().equals(observed.turnId()) && owns(task))
        owned =
            new com.deepseekharness.app.control.ControlTaskAuthority.Lease(task, screen.revision());
    } catch (RuntimeException unavailable) {
      // Missing ownership evidence never becomes a global pause over another task.
    }
    synchronized (this) {
      if (!pollCurrent(observed)) return;
      if (disconnected) state.disconnected();
      else
        state.unknown(UiText.choose("状态暂不可确认；手机操作已暂停", "Status unavailable; phone actions paused"));
      if (owned != null)
        fence.pauseCurrent(
            owned, disconnected ? "ASSISTANT_DISCONNECTED" : "ASSISTANT_STATUS_UNKNOWN");
      changed();
    }
  }

  /** 打开完整会话只收起面板，不暂停、不取消；前台变化由下一次观察发现。 */
  public void returnConversation() {
    collapse();
    worker.execute(
        () -> {
          try {
            backend.returnConversation(binding);
          } catch (Exception failure) {
            state.unknown(
                UiText.choose(
                    "无法打开绑定会话；连接可能已改变",
                    "Unable to open the bound conversation; the connection may have changed"));
            changed();
          }
        });
  }

  private void changed() {
    if (closed) return;
    main.post(
        () -> {
          if (!closed) for (Runnable listener : listeners) listener.run();
        });
  }

  @Override
  public void close() {
    close(true);
  }

  /** Explicit finish retires the view without cancelling later model work. */
  void detachFinishedTask() {
    close(false);
  }

  private synchronized void close(boolean cancelModel) {
    if (closed) return;
    pollRevision++;
    fence.compose().reset();
    var previous = fence.snapshot().binding();
    boolean hadTask = !state.terminalKnown();
    // Invalidate screen work before stopping the subscription. Never stop the whole DSH.
    state.stop();
    suppressCancellation = true;
    try {
      // Retiring an idle view must not stop another conversation's phone task.
      if (previous != null && owns(previous)) fence.stop("ASSISTANT_CLOSED");
    } finally {
      suppressCancellation = false;
    }
    if (cancelModel && hadTask) requestCancellation(state.turnId());
    closed = true;
    worker.shutdown();
    listeners.clear();
    if (active == this) {
      active = null;
      fence.setTurnCancellation(null);
    }
  }
}
