package com.deepseekharness.app.vscreen;

/** Main-thread ownership of feedback only; it never submits or replays an input. */
final class VirtualScreenTouchFeedback {
  enum Update {
    NONE,
    FAILED,
    COMPLETED
  }

  static final class Ticket {
    final long viewEpoch, runEpoch, sequence;
    final String generation, stroke;
    boolean failed, finished;

    Ticket(long viewEpoch, long runEpoch, String generation, long sequence, String stroke) {
      this.viewEpoch = viewEpoch;
      this.runEpoch = runEpoch;
      this.generation = generation;
      this.sequence = sequence;
      this.stroke = stroke;
    }
  }

  /** Worker-side ordering: once failed/terminal, queued UP can never become another submission. */
  static final class Dispatch {
    private boolean done;

    synchronized boolean allowed() {
      return !done;
    }

    synchronized boolean complete(int action, boolean accepted) {
      if (done) return false;
      done = !accepted || action == 1 || action == 3;
      return !accepted && action != 3;
    }

    synchronized boolean done() {
      return done;
    }

    synchronized void abandon() {
      done = true;
    }
  }

  private long revision;
  private Ticket active;

  long revision() {
    return revision;
  }

  long invalidate() {
    active = null;
    return ++revision;
  }

  void generation(String generation) {
    if (active != null && !active.generation.equals(generation)) invalidate();
  }

  Ticket begin(long viewEpoch, long runEpoch, String generation, long sequence, String stroke) {
    invalidate();
    return active = new Ticket(viewEpoch, runEpoch, generation, sequence, stroke);
  }

  Ticket find(String generation, long sequence, String stroke) {
    return active != null
            && active.sequence == sequence
            && active.generation.equals(generation)
            && active.stroke.equals(stroke)
        ? active
        : null;
  }

  Update receipt(
      Ticket ticket,
      long viewEpoch,
      long runEpoch,
      String generation,
      boolean ok,
      boolean terminal) {
    if (ticket == null
        || ticket != active
        || ticket.viewEpoch != viewEpoch
        || ticket.runEpoch != runEpoch
        || !ticket.generation.equals(generation)
        || ticket.sequence <= 0
        || ticket.failed
        || ticket.finished) return Update.NONE;
    if (!ok) {
      ticket.failed = true;
      return Update.FAILED;
    }
    if (!terminal) return Update.NONE;
    ticket.finished = true;
    return Update.COMPLETED;
  }
}
