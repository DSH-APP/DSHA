// DSHA project contribution, MIT; see the project LICENSE.
package com.termux.terminal;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Bounded FIFO for all terminal-to-process writes; producers never wait for the PTY. */
final class TerminalInputQueue {
    enum Offer { ACCEPTED, FULL, BLOCKED, CLOSED }
    static final class CloseResult {
        final int discardedBytes;
        final boolean writeInProgress;
        CloseResult(int discardedBytes, boolean writeInProgress) {
            this.discardedBytes = discardedBytes;
            this.writeInProgress = writeInProgress;
        }
    }

    private final int limit;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private int outstanding;
    private byte[] inFlight;
    private boolean closed;
    private boolean cancelled;
    private boolean overflowed;

    TerminalInputQueue(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit");
        this.limit = limit;
    }

    synchronized Offer offer(byte[] bytes, int offset, int count) {
        if (offset < 0 || count < 0 || offset > bytes.length - count)
            throw new IndexOutOfBoundsException();
        if (closed || cancelled) return Offer.CLOSED;
        if (overflowed) return Offer.BLOCKED;
        if (count == 0) return Offer.ACCEPTED;
        if (count > limit - outstanding) {
            overflowed = true;
            return Offer.FULL;
        }
        queue.addLast(Arrays.copyOfRange(bytes, offset, offset + count));
        outstanding += count;
        notifyAll();
        return Offer.ACCEPTED;
    }

    synchronized byte[] take() throws InterruptedException {
        while (queue.isEmpty() && !closed) wait();
        if (closed) return null;
        if (inFlight != null) throw new IllegalStateException("single consumer required");
        inFlight = queue.removeFirst();
        return inFlight;
    }

    synchronized void completed(byte[] bytes) {
        if (inFlight != bytes) throw new IllegalStateException("unexpected completion");
        outstanding -= bytes.length;
        inFlight = null;
    }

    synchronized boolean mayContinue(byte[] bytes) {
        return inFlight == bytes && !closed && !cancelled;
    }

    synchronized CloseResult close() {
        if (closed) return null;
        closed = true;
        int discarded = outstanding - (inFlight == null ? 0 : inFlight.length);
        queue.clear();
        outstanding -= discarded;
        notifyAll();
        return cancelled ? null : new CloseResult(discarded, inFlight != null);
    }

    /** Stop accepting and discard pending input, but leave the writer waiting until process exit. */
    synchronized CloseResult cancelPending() {
        if (closed || cancelled) return null;
        cancelled = true;
        int discarded = outstanding - (inFlight == null ? 0 : inFlight.length);
        queue.clear();
        outstanding -= discarded;
        return new CloseResult(discarded, inFlight != null);
    }

    /** Explicit user action only; do not resume while earlier bytes are still queued or writing. */
    synchronized boolean resumeAfterOverflow() {
        if (closed || cancelled || !overflowed || outstanding != 0) return false;
        overflowed = false;
        return true;
    }
}
