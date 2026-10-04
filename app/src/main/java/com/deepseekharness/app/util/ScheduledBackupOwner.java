package com.deepseekharness.app.util;

/** One event thread owns scheduler parameters and the exact native job produced by each request. */
public final class ScheduledBackupOwner<P> {
  public static final class Ticket<P> {
    public final P parameters;
    private String jobId = "";

    private Ticket(P parameters) {
      this.parameters = parameters;
    }
  }

  private final Thread eventThread = Thread.currentThread();
  private Ticket<P> active;

  private void checkThread() {
    if (Thread.currentThread() != eventThread)
      throw new IllegalStateException("SCHEDULED_BACKUP_OWNER_THREAD");
  }

  public Ticket<P> begin(P parameters) {
    checkThread();
    if (active != null) return null;
    active = new Ticket<>(parameters);
    return active;
  }

  public boolean current(Ticket<P> ticket) {
    checkThread();
    return ticket != null && active == ticket;
  }

  public boolean accept(Ticket<P> ticket, Runnable submit) {
    checkThread();
    if (!current(ticket)) return false;
    submit.run();
    return true;
  }

  public boolean submitted(Ticket<P> ticket, String jobId) {
    checkThread();
    if (!current(ticket)) return false;
    if (jobId == null || jobId.isEmpty() || !ticket.jobId.isEmpty())
      throw new IllegalStateException("SCHEDULED_BACKUP_JOB_ID");
    ticket.jobId = jobId;
    return true;
  }

  public String jobId(Ticket<P> ticket) {
    checkThread();
    return ticket == null ? "" : ticket.jobId;
  }

  public boolean owns(Ticket<P> ticket, String currentJobId) {
    checkThread();
    String id = jobId(ticket);
    return !id.isEmpty() && id.equals(currentJobId);
  }

  public boolean finish(Ticket<P> ticket) {
    checkThread();
    if (!current(ticket)) return false;
    active = null;
    return true;
  }

  public Ticket<P> stop(P parameters) {
    checkThread();
    if (active == null || active.parameters != parameters) return null;
    Ticket<P> stopped = active;
    active = null;
    return stopped;
  }

  public Ticket<P> stopCurrent() {
    checkThread();
    Ticket<P> stopped = active;
    active = null;
    return stopped;
  }
}
