package com.deepseekharness.app.core;

import java.io.IOException;
import com.deepseekharness.app.util.WebStopDiagnostic;
import java.util.ArrayList;
import java.util.List;

/** Stop attempts are evidence; only full guest and owned-launcher confirmation releases the lease. */
public final class WebStopCoordinator {
  public enum Status {
    STOPPED,
    UNCONFIRMED,
    FAILED,
    CANCELLED
  }

  public record Result(Status status, String code, List<WebStopDiagnostic> diagnostics) {
    public Result {
      diagnostics = List.copyOf(diagnostics);
    }

    /** Compatibility for retained/native callers; production ports return typed facts. */
    public Result(Status status, String code, String detail) {
      this(
          status,
          code,
          detail.isEmpty()
              ? List.of()
              : List.of(WebStopDiagnostic.of(WebStopDiagnostic.Code.LEGACY_DETAIL, detail)));
    }

    public String detail() {
      return WebStopText.renderAll(diagnostics);
    }

    public boolean stopped() {
      return status == Status.STOPPED;
    }
  }

  interface Ports {
    String stopGuest() throws IOException;

    default List<WebStopDiagnostic> stopGuestFacts() throws IOException {
      String detail = stopGuest();
      return detail.isEmpty()
          ? List.of()
          : List.of(WebStopDiagnostic.of(WebStopDiagnostic.Code.LEGACY_DETAIL, detail));
    }

    String stopOwnedLaunchers();

    default List<WebStopDiagnostic> stopOwnedLauncherFacts() {
      String detail = stopOwnedLaunchers();
      return detail.isEmpty()
          ? List.of()
          : List.of(WebStopDiagnostic.of(WebStopDiagnostic.Code.LEGACY_DETAIL, detail));
    }

    boolean confirm() throws IOException;
  }

  static Result stop(Ports ports) {
    List<WebStopDiagnostic> facts = new ArrayList<>();
    try {
      facts.addAll(ports.stopGuestFacts());
    } catch (IOException | RuntimeException error) {
      facts.add(WebStopDiagnostic.failure(WebStopDiagnostic.Code.STOP_FAILED, error));
    }
    try {
      facts.addAll(ports.stopOwnedLauncherFacts());
    } catch (RuntimeException error) {
      facts.add(WebStopDiagnostic.failure(WebStopDiagnostic.Code.STOP_FAILED, error));
    }
    try {
      if (ports.confirm()) return new Result(Status.STOPPED, "WEB_STOPPED", "");
    } catch (IOException | RuntimeException error) {
      facts.add(WebStopDiagnostic.failure(WebStopDiagnostic.Code.STOP_FAILED, error));
    }
    if (Thread.currentThread().isInterrupted())
      return new Result(Status.CANCELLED, "WEB_STOP_CANCELLED", facts);
    if (facts.isEmpty())
      facts.add(WebStopDiagnostic.of(WebStopDiagnostic.Code.EXIT_UNCONFIRMED, ""));
    return new Result(Status.UNCONFIRMED, "WEB_PROCESS_EXIT_UNCONFIRMED", facts);
  }

  private WebStopCoordinator() {}
}
