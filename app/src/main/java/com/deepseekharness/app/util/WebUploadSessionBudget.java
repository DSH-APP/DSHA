package com.deepseekharness.app.util;

/** Thread-safe upload quota for one retained browser document/session. */
public final class WebUploadSessionBudget {
  public static final int MAX_BATCH_FILES = 20;
  public static final int MAX_SESSION_FILES = 40;
  public static final long MAX_SESSION_BYTES = 512L * 1024L * 1024L;

  private enum State {
    COPYING,
    READY,
    COMMITTED,
    ROLLED_BACK
  }

  public static final class Reservation {
    private final WebUploadSessionBudget owner;
    private final int files;
    private long bytes;
    private State state = State.COPYING;

    private Reservation(WebUploadSessionBudget owner, int files) {
      this.owner = owner;
      this.files = files;
    }
  }

  public static final class LimitExceededException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final String limit;

    private LimitExceededException(String limit) {
      super("UPLOAD_SESSION_" + limit);
      this.limit = limit;
    }

    public String limit() {
      return limit;
    }
  }

  public static final class SessionClosedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private SessionClosedException() {
      super("UPLOAD_SESSION_CLOSED");
    }
  }

  private int committedFiles;
  private int reservedFiles;
  private long committedBytes;
  private long reservedBytes;
  private int copiesInFlight;
  private boolean closed;

  /** Reserve the selected file count before opening any content URI. */
  public synchronized Reservation beginBatch(int files) {
    if (closed) throw new SessionClosedException();
    if (files < 1 || files > MAX_BATCH_FILES)
      throw new IllegalArgumentException("UPLOAD_BATCH_COUNT");
    if (files > MAX_SESSION_FILES - committedFiles - reservedFiles)
      throw new LimitExceededException("FILES");
    reservedFiles += files;
    copiesInFlight++;
    return new Reservation(this, files);
  }

  /** Reserve bytes as they are copied, before writing each chunk to disk. */
  public synchronized void addBytes(Reservation reservation, long bytes) {
    requireOwned(reservation);
    if (reservation.state != State.COPYING)
      throw new IllegalStateException("UPLOAD_RESERVATION_STATE");
    if (closed) throw new SessionClosedException();
    if (bytes < 0) throw new IllegalArgumentException("UPLOAD_BYTE_COUNT");
    if (bytes > MAX_SESSION_BYTES - committedBytes - reservedBytes)
      throw new LimitExceededException("BYTES");
    reservation.bytes += bytes;
    reservedBytes += bytes;
  }

  /** Finish copying while leaving the batch reserved until its browser callback accepts it. */
  public synchronized void finishCopy(Reservation reservation) {
    requireOwned(reservation);
    if (reservation.state != State.COPYING)
      throw new IllegalStateException("UPLOAD_RESERVATION_STATE");
    reservation.state = State.READY;
    copiesInFlight--;
  }

  /** Commit only after the originating live document accepts the copied files. */
  public synchronized boolean commit(Reservation reservation) {
    requireOwned(reservation);
    if (closed || reservation.state != State.READY) return false;
    reservedFiles -= reservation.files;
    committedFiles += reservation.files;
    reservedBytes -= reservation.bytes;
    committedBytes += reservation.bytes;
    reservation.state = State.COMMITTED;
    return true;
  }

  /** Release a failed, cancelled, or stale batch's reservation. Safe to call once from cleanup. */
  public synchronized void rollback(Reservation reservation) {
    requireOwned(reservation);
    if (reservation.state == State.COPYING) copiesInFlight--;
    if (reservation.state == State.COPYING || reservation.state == State.READY) {
      reservedFiles -= reservation.files;
      reservedBytes -= reservation.bytes;
      reservation.state = State.ROLLED_BACK;
    }
  }

  /** Prevent new writes/commits; in-flight copies observe closure at their next chunk boundary. */
  public synchronized void close() {
    closed = true;
  }

  public synchronized boolean isClosed() {
    return closed;
  }

  public synchronized boolean canDeleteOwnedFiles() {
    return copiesInFlight == 0;
  }

  public synchronized int committedFiles() {
    return committedFiles;
  }

  public synchronized long committedBytes() {
    return committedBytes;
  }

  public synchronized int reservedFiles() {
    return reservedFiles;
  }

  public synchronized long reservedBytes() {
    return reservedBytes;
  }

  private void requireOwned(Reservation reservation) {
    if (reservation == null || reservation.owner != this)
      throw new IllegalArgumentException("UPLOAD_RESERVATION_OWNER");
  }
}
