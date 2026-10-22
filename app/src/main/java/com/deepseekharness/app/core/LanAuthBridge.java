package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.LanProxyService;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.DshAuthSession;
import java.util.function.BooleanSupplier;

/** Exchanges one captured Web credential and publishes only against that exact run. */
final class LanAuthBridge {
  record Snapshot(long generation, String authUrl, int port) {}

  record Exchange(String cookie, String message) {}

  interface Ports {
    Snapshot capture(long generation);

    boolean current(Snapshot snapshot);

    boolean publish(Snapshot snapshot, Exchange result);

    void bind(Snapshot snapshot, String cookie);

    void warning(RuntimeException error);
  }

  interface Transport {
    Exchange exchange(String authUrl, int port, BooleanSupplier current);
  }

  private final Ports ports;
  private final Transport transport;

  LanAuthBridge(Ports ports, Transport transport) {
    this.ports = java.util.Objects.requireNonNull(ports);
    this.transport = java.util.Objects.requireNonNull(transport);
  }

  LanAuthBridge(
      Context context, ConfigStore config, ProotBootstrap runtime, HarnessSessionState state) {
    this(
        new Ports() {
          public Snapshot capture(long generation) {
            synchronized (state.lifecycle) {
              if (!state.lifecycle.isCurrent(generation)) return null;
              var run = state.runs.get(generation);
              if (run == null || run.authUrl.isEmpty()) return null;
              Snapshot snapshot = new Snapshot(generation, run.authUrl, run.port);
              return current(snapshot) ? snapshot : null;
            }
          }

          public boolean current(Snapshot snapshot) {
            return LanAuthBridge.current(state, snapshot);
          }

          public boolean publish(Snapshot snapshot, Exchange result) {
            synchronized (state.lifecycle) {
              if (!current(snapshot)) return false;
              state.authFailure = result.message();
              return true;
            }
          }

          public void bind(Snapshot snapshot, String cookie) {
            synchronized (state.lifecycle) {
              if (!current(snapshot)) return;
              if (LanProxyService.setDshAuthCookie(cookie, snapshot.generation())
                  && config.isLanMode())
                LanProxyService.start(
                    runtime.getRootfsDir().getAbsolutePath(),
                    context,
                    snapshot.port(),
                    snapshot.generation());
            }
          }

          public void warning(RuntimeException error) {
            DiagnosticLog.record(context, "LAN_AUTH_BIND", error.getClass().getSimpleName());
          }
        },
        (url, port, current) -> {
          DshAuthSession.Result result = DshAuthSession.exchange(url, port, current);
          return new Exchange(result.cookie, result.message);
        });
  }

  /** The production capture, publication and bind gate, including same-generation UNKNOWN. */
  static boolean current(HarnessSessionState state, Snapshot snapshot) {
    synchronized (state.lifecycle) {
      if (snapshot == null) return false;
      var run = state.runs.get(snapshot.generation());
      return !state.restoreUnknown
          && state.lifecycle.isCurrent(snapshot.generation())
          && run != null
          && run == state.currentRun
          && !run.authorityRevocationPending
          && (run.recovered == null || run.recoveredLive)
          && snapshot.authUrl().equals(run.authUrl)
          && snapshot.port() == run.port;
    }
  }

  String exchange(long generation) {
    Snapshot snapshot = ports.capture(generation);
    if (snapshot == null || !ports.current(snapshot)) return null;
    // The potentially slow HTTP exchange runs without the lifecycle monitor.
    Exchange result =
        transport.exchange(snapshot.authUrl(), snapshot.port(), () -> ports.current(snapshot));
    return publish(snapshot, result);
  }

  /** Publish the strict recovery's already verified result without another HTTP request. */
  String publishVerified(
      long generation, String expectedUrl, int expectedPort, DshAuthSession.Result verified) {
    if (verified == null || !verified.ready()) return null;
    Snapshot snapshot = ports.capture(generation);
    if (snapshot == null
        || !snapshot.authUrl().equals(expectedUrl)
        || snapshot.port() != expectedPort
        || !ports.current(snapshot)) return null;
    return publish(snapshot, new Exchange(verified.cookie, verified.message));
  }

  private String publish(Snapshot snapshot, Exchange result) {
    if (!ports.publish(snapshot, result)) return null;
    if (result.cookie() != null) {
      try {
        if (ports.current(snapshot)) ports.bind(snapshot, result.cookie());
      } catch (RuntimeException error) {
        ports.warning(error);
      }
    }
    return result.cookie();
  }
}
