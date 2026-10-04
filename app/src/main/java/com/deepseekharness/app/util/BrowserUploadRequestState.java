package com.deepseekharness.app.util;

/** One retained browser document owns at most one file chooser response. */
public final class BrowserUploadRequestState<Browser, Cache> {
  public static final class Ticket<Browser, Cache> {
    private final Browser browser;
    private final Cache cache;

    private Ticket(Browser browser, Cache cache) {
      this.browser = browser;
      this.cache = cache;
    }
  }

  private Ticket<Browser, Cache> active;
  private boolean closed;

  /** The caller dismisses the old browser callback before beginning a replacement. */
  public synchronized Ticket<Browser, Cache> begin(Browser browser, Cache cache) {
    if (closed || browser == null || cache == null)
      throw new IllegalStateException("UPLOAD_REQUEST_STATE");
    active = new Ticket<>(browser, cache);
    return active;
  }

  /** Reject callbacks from a prior navigation, renderer, chooser, or cache session. */
  public synchronized void invalidate() {
    active = null;
  }

  public synchronized boolean owns(Ticket<Browser, Cache> ticket, Browser browser, Cache cache) {
    return !closed
        && ticket != null
        && active == ticket
        && ticket.browser == browser
        && ticket.cache == cache;
  }

  public synchronized void finish(Ticket<Browser, Cache> ticket) {
    if (active == ticket) active = null;
  }

  public synchronized void close() {
    closed = true;
    active = null;
  }
}
