package com.deepseekharness.app.vscreen;

/** Local native preview/input admission; this does not authorize any remote frame or input. */
final class VirtualScreenPreviewInputGate {
  private Fetch fetching;
  private Gesture gesture;

  final class Hold implements AutoCloseable {
    private final Fetch fetch;
    private boolean closed;

    private Hold(Fetch fetch) {
      this.fetch = fetch;
    }

    @Override
    public void close() {
      synchronized (VirtualScreenPreviewInputGate.this) {
        if (closed) return;
        closed = true;
        if (--fetch.references == 0 && fetching == fetch) fetching = null;
      }
    }
  }

  final class Fetch {
    private int references = 1;
    private final Hold producer = new Hold(this);

    Hold retain() {
      synchronized (VirtualScreenPreviewInputGate.this) {
        if (fetching != this || references <= 0) return null;
        references++;
        return new Hold(this);
      }
    }

    void offered() {
      producer.close();
    }
  }

  final class Gesture implements AutoCloseable {
    private boolean closed;

    @Override
    public void close() {
      synchronized (VirtualScreenPreviewInputGate.this) {
        if (closed) return;
        closed = true;
        if (gesture == this) gesture = null;
      }
    }
  }

  synchronized Fetch beginPreview() {
    if (fetching != null || gesture != null) return null;
    return fetching = new Fetch();
  }

  synchronized Gesture beginGesture() {
    if (fetching != null || gesture != null) return null;
    return gesture = new Gesture();
  }
}
