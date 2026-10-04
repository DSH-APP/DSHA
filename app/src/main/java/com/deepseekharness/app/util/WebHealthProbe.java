package com.deepseekharness.app.util;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.BooleanSupplier;

/** Current-port listening, authentication and HTTP readiness are separate evidence. */
public final class WebHealthProbe {
  private WebHealthProbe() {}

  public enum State {
    UNCONFIGURED,
    UNAVAILABLE,
    UNKNOWN,
    LISTENING,
    AUTH_EXPIRED,
    INVALID_RESPONSE,
    HTTP_READY,
    STALE
  }

  public static final class Result {
    public final State state;

    private Result(State state) {
      this.state = state;
    }

    public boolean healthy() {
      return state == State.HTTP_READY;
    }

    /** A broken page/stream/auth response never authorizes a Node restart. */
    public boolean restartCandidate() {
      return state == State.UNAVAILABLE;
    }
  }

  public static Result probe(String authUrl, int port, BooleanSupplier current, int budgetMs) {
    if (!current.getAsBoolean()) return new Result(State.STALE);
    DshAuthUrl.Parsed parsed = DshAuthUrl.parse(authUrl);
    if (parsed == null || !parsed.loopbackBaseUrl.equals("http://127.0.0.1:" + port + "/"))
      return new Result(State.UNCONFIGURED);
    long deadline = System.nanoTime() + Math.max(1, budgetMs) * 1_000_000L;
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), Math.max(1, budgetMs));
    } catch (ConnectException refused) {
      return new Result(current.getAsBoolean() ? State.UNAVAILABLE : State.STALE);
    } catch (java.io.IOException unavailable) {
      return new Result(current.getAsBoolean() ? State.UNKNOWN : State.STALE);
    }
    if (!current.getAsBoolean()) return new Result(State.STALE);
    int remaining = (int) Math.min(Integer.MAX_VALUE, (deadline - System.nanoTime()) / 1_000_000L);
    if (remaining <= 0) return new Result(State.LISTENING);
    DshAuthSession.Result authentication =
        DshAuthSession.exchange(authUrl, port, current, remaining);
    if (!current.getAsBoolean() || authentication.status == DshAuthSession.Status.CANCELLED)
      return new Result(State.STALE);
    switch (authentication.status) {
      case READY:
        return new Result(State.HTTP_READY);
      case EXPIRED:
        return new Result(State.AUTH_EXPIRED);
      case INVALID_RESPONSE:
        return new Result(State.INVALID_RESPONSE);
      default:
        return new Result(State.LISTENING);
    }
  }
}
