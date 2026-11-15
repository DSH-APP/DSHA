package com.deepseekharness.app.control;

import android.content.Context;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide native authority for the current local DSH conversation and turn. */
public final class PhoneControlCoordinator {
  private static PhoneControlCoordinator instance;
  private final Context context;
  private final ControlTaskAuthority authority = new ControlTaskAuthority();
  private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
  private volatile ScreenControlConfirmation confirmation;
  private volatile java.util.function.Consumer<ControlTaskAuthority.Binding> turnCancellation;
  private final com.deepseekharness.app.util.ComposeGate compose =
      new com.deepseekharness.app.util.ComposeGate(android.os.SystemClock::elapsedRealtime);

  private PhoneControlCoordinator(Context context) {
    this.context = context.getApplicationContext();
    OwnedScreenOverlay.setExpansionListener(
        () -> {
          var state = snapshot();
          if (!state.paused()) pause("ASSISTANT_PANEL_EXPANDED");
        });
  }

  public static synchronized PhoneControlCoordinator get(Context context) {
    if (instance == null) instance = new PhoneControlCoordinator(context);
    return instance;
  }

  /** 用户正在给 AI 输入消息：手机动作在此等待，不暂停、不取消对话。 */
  public com.deepseekharness.app.util.ComposeGate compose() {
    return compose;
  }

  private final com.deepseekharness.app.util.TurnScreenGrants turnGrants =
      new com.deepseekharness.app.util.TurnScreenGrants();
  private final com.deepseekharness.app.util.TurnCapabilityGrants capabilityGrants =
      new com.deepseekharness.app.util.TurnCapabilityGrants();

  /** 「本轮允许此应用」：只在当前这一轮内有效，结束、暂停或停止即清空。 */
  public com.deepseekharness.app.util.TurnScreenGrants turnGrants() {
    return turnGrants;
  }

  /** 非屏幕能力（如外跳）的本轮记忆；不与屏幕按应用记忆互相授权。 */
  public com.deepseekharness.app.util.TurnCapabilityGrants capabilityGrants() {
    return capabilityGrants;
  }

  public ControlTaskAuthority.Snapshot snapshot() {
    return authority.snapshot();
  }

  public ControlTaskAuthority.Lease begin(ControlTaskAuthority.Binding binding) {
    var lease = authority.begin(binding);
    changed();
    return lease;
  }

  public void checkBegin(ControlTaskAuthority.Binding binding) {
    authority.checkBegin(binding);
  }

  public ControlTaskAuthority.Lease lease(ControlTaskAuthority.Binding binding) {
    return authority.lease(binding);
  }

  public boolean current(ControlTaskAuthority.Lease lease) {
    return authority.current(lease);
  }

  public boolean actionAllowed(ControlTaskAuthority.Lease lease) {
    return authority.actionAllowed(lease);
  }

  public boolean observed(ControlTaskAuthority.Lease lease) {
    boolean result = authority.observed(lease);
    if (result) changed();
    return result;
  }

  public long beginObservation(ControlTaskAuthority.Lease lease) {
    return authority.beginObservation(lease);
  }

  public boolean observed(
      ControlTaskAuthority.Lease lease,
      long sequence,
      com.deepseekharness.app.util.ScreenTarget target) {
    boolean result = authority.observed(lease, sequence, target);
    if (result) changed();
    return result;
  }

  public boolean actionAllowed(
      ControlTaskAuthority.Lease lease, com.deepseekharness.app.util.ScreenTarget target) {
    return authority.actionAllowed(lease, target);
  }

  public boolean checkActionTarget(
      ControlTaskAuthority.Lease lease, com.deepseekharness.app.util.ScreenTarget target) {
    return authority.checkActionTarget(lease, target);
  }

  public void requireObservation(ControlTaskAuthority.Binding binding) {
    authority.requireObservation(binding);
    fence(false, null);
  }

  /** Completion fences work but must not invoke the selected assistant's cancel callback. */
  public void completeLocal(ControlTaskAuthority.Binding binding) {
    turnGrants.clear();
    capabilityGrants.clear();
    boolean ended = authority.completeLocal(binding);
    // 只结束该任务的记录；别的会话的延迟 task-end 不能清掉当前任务的活动。
    if (ended)
      com.deepseekharness.app.screen.ScreenActivityRecorder.clearIfTask(binding.identity());
    if (ended) fence(false, null);
  }

  public void pause(String reason) {
    turnGrants.clear();
    capabilityGrants.clear();
    var previous = authority.pause(reason);
    // 暂停立即清空内存活动：用户已经接管屏幕，旧摘要不能继续被当成当前上下文。
    com.deepseekharness.app.screen.ScreenActivityRecorder.clear();
    fence(true, previous);
  }

  public boolean pauseCurrent(ControlTaskAuthority.Lease lease, String reason) {
    var previous = authority.pauseCurrent(lease, reason);
    if (previous == null) return false;
    com.deepseekharness.app.screen.ScreenActivityRecorder.clear();
    fence(true, previous);
    return true;
  }

  public void resume() {
    authority.resume();
    fence(false, null);
  }

  public void stop(String reason) {
    compose.reset();
    turnGrants.clear();
    capabilityGrants.clear();
    var previous = authority.stop(reason);
    com.deepseekharness.app.screen.ScreenActivityRecorder.clear();
    fence(true, previous);
  }

  public void stopLocal(String reason) {
    var binding = snapshot().binding();
    if (binding != null) stop(reason);
  }

  public void bindRequest(CapabilityRequest request, ControlTaskAuthority.Lease lease) {
    if (!current(lease)) throw new IllegalStateException("SCREEN_TASK_CHANGED");
    ScreenAuthorityBindings.bind(
        request, lease.binding().identity() + "\0" + lease.revision(), () -> current(lease));
  }

  public void setConfirmationHandler(ScreenControlConfirmation handler) {
    confirmation = handler;
  }

  public ScreenControlConfirmation confirmationHandler() {
    return confirmation;
  }

  public void setTurnCancellation(
      java.util.function.Consumer<ControlTaskAuthority.Binding> callback) {
    turnCancellation = callback;
  }

  public void addListener(Runnable listener) {
    listeners.addIfAbsent(listener);
  }

  public void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  private void fence(boolean cancelTurn, ControlTaskAuthority.Binding previous) {
    DshaApp.from(context).capabilityBroker().revoke("screen");
    DshaAccessibilityService.revokeRequests();
    if (cancelTurn && previous != null) {
      var callback = turnCancellation;
      if (callback != null)
        try {
          callback.accept(previous);
        } catch (RuntimeException ignored) {
        }
    }
    changed();
  }

  private void changed() {
    for (Runnable listener : listeners)
      try {
        listener.run();
      } catch (RuntimeException ignored) {
      }
  }
}
