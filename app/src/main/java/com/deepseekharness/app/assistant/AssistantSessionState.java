package com.deepseekharness.app.assistant;

/** Small pure state machine: unknown outcomes never become an idle/success receipt. */
public final class AssistantSessionState {
  public enum Phase {
    CONNECTING,
    IDLE,
    SENDING,
    RUNNING,
    PAUSING,
    PAUSED,
    STOPPING,
    STOPPED,
    UNKNOWN,
    DISCONNECTED
  }

  private Phase phase = Phase.CONNECTING;
  private boolean paused, stopped, terminalKnown;
  private String turnId = "", taskId = "", detail = "";

  public synchronized Phase phase() {
    return phase;
  }

  public synchronized boolean paused() {
    return paused;
  }

  public synchronized boolean terminalKnown() {
    return terminalKnown;
  }

  public synchronized String turnId() {
    return turnId;
  }

  public synchronized String taskId() {
    return taskId;
  }

  public synchronized String detail() {
    return detail;
  }

  public synchronized void sending() {
    if (!terminalKnown || phase == Phase.DISCONNECTED || stopped || paused)
      throw new IllegalStateException("ASSISTANT_NOT_READY");
    terminalKnown = false;
    phase = Phase.SENDING;
    detail = "";
  }

  public synchronized void accepted() {
    if (!paused && !stopped) phase = Phase.RUNNING;
    // Acceptance means inbox admission; only a later actual status establishes idle.
  }

  public synchronized void pause(String reason) {
    paused = true;
    terminalKnown = false;
    phase = Phase.PAUSING;
    detail = reason;
  }

  public synchronized void stop() {
    stopped = true;
    paused = true;
    terminalKnown = false;
    phase = Phase.STOPPING;
    detail = "";
  }

  public synchronized void rejected(String reason) {
    if (phase == Phase.DISCONNECTED) return;
    paused = true;
    terminalKnown = true;
    phase = Phase.PAUSED;
    detail = reason;
  }

  public synchronized void failed(String reason) {
    if (phase != Phase.DISCONNECTED && terminalKnown) detail = reason;
  }

  public synchronized void resume() {
    if (!terminalKnown || stopped || phase == Phase.DISCONNECTED)
      throw new IllegalStateException("ASSISTANT_CANCEL_NOT_CONFIRMED");
    paused = false;
    phase = Phase.IDLE;
    detail = "OBSERVE_REQUIRED";
  }

  /** Explicit new user text may start a new task in the same stopped conversation. */
  public synchronized void newTask() {
    if (!terminalKnown || phase == Phase.DISCONNECTED)
      throw new IllegalStateException("ASSISTANT_CANCEL_NOT_CONFIRMED");
    stopped = false;
    paused = false;
    phase = Phase.IDLE;
    detail = "OBSERVE_REQUIRED";
  }

  public synchronized void observed(boolean running, String turn, String task) {
    if (phase == Phase.DISCONNECTED) return;
    turnId = turn == null ? "" : turn;
    taskId = task == null ? "" : task;
    terminalKnown = !running;
    phase =
        stopped
            ? (running ? Phase.STOPPING : Phase.STOPPED)
            : paused
                ? (running ? Phase.PAUSING : Phase.PAUSED)
                : running ? Phase.RUNNING : Phase.IDLE;
  }

  public synchronized void unknown(String reason) {
    if (phase == Phase.DISCONNECTED) return;
    terminalKnown = false;
    phase = Phase.UNKNOWN;
    detail = reason;
  }

  public synchronized void disconnected() {
    terminalKnown = false;
    paused = true;
    phase = Phase.DISCONNECTED;
    detail = "CONNECTION_CHANGED";
  }
}
