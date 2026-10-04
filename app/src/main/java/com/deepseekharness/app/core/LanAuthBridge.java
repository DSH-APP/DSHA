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
              return new Snapshot(generation, run.authUrl, run.port);
            }
          }

          public boolean current(Snapshot snapshot) {
            synchronized (state.lifecycle) {
              var run = state.runs.get(snapshot.generation());
              return state.lifecycle.isCurrent(snapshot.generation())
                  && run != null
                  && snapshot.authUrl().equals(run.authUrl)
                  && snapshot.port() == run.port;
            }
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

  String exchange(long generation) {
    Snapshot snapshot = ports.capture(generation);
    if (snapshot == null || !ports.current(snapshot)) return null;
    // The potentially slow HTTP exchange runs without the lifecycle monitor.
    Exchange result =
        transport.exchange(snapshot.authUrl(), snapshot.port(), () -> ports.current(snapshot));
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
