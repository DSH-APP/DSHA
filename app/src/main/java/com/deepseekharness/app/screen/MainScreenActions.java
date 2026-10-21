package com.deepseekharness.app.screen;

import android.graphics.Bitmap;
import android.os.SystemClock;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.capabilities.CapabilityBroker;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.util.ScreenConnectionEpoch;
import com.deepseekharness.app.util.ScreenNodeLeaseStore;
import com.deepseekharness.app.util.ScreenObservationLedger;
import com.deepseekharness.app.util.ScreenRequestExecutor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** 主屏编排：查询、像素观察与语义动作分离，不持有Web或持久授权状态。 */
public final class MainScreenActions {
  enum FailureStage {
    MAIN("SCREEN_MAIN_FAILURE"),
    CAPTURE("SCREEN_CAPTURE_FAILURE"),
    LATE_CAPTURE("SCREEN_CAPTURE_LATE_FAILURE");

    final String code;

    FailureStage(String code) {
      this.code = code;
    }
  }

  private static final java.util.Set<String> FIXED_SCREEN_MESSAGES =
      java.util.Set.of(
          "SCREEN_TARGET_CHANGED",
          "SCREEN_QUEUE_FULL",
          "SCREEN_RUN_CHANGED",
          "SCREEN_QUEUE_TIMEOUT",
          "SCREEN_REQUEST_CANCELLED",
          "INVALID_TEXT",
          "TEXT_LIMIT");
  private final ScreenConnectionEpoch<DshaAccessibilityService> connection;
  private final ScreenRequestExecutor requests = new ScreenRequestExecutor();
  private final ScreenObservationLedger observations =
      new ScreenObservationLedger(SystemClock::elapsedRealtime);
  private final ScreenNodeLeaseStore<ScreenSemanticNode> nodes =
      new ScreenNodeLeaseStore<>(SystemClock::elapsedRealtime, ScreenSemanticNode::close);
  private final ScreenScreenshotCapture captures = new ScreenScreenshotCapture();
  private final ScreenSnapshotReader reader = new ScreenSnapshotReader();
  private final ScreenActionExecutor actions =
      new ScreenActionExecutor(captures, observations, nodes);

  public MainScreenActions(ScreenConnectionEpoch<DshaAccessibilityService> connection) {
    this.connection = connection;
  }

  /** HTTP pre-authorization metadata only. No root, image or authority escapes the worker. */
  public static final class TargetPreflight {
    public final com.deepseekharness.app.util.ScreenTarget target;
    private final String code, diagnostic;
    private final boolean pending;

    private TargetPreflight(
        com.deepseekharness.app.util.ScreenTarget target,
        String code,
        String diagnostic,
        boolean pending) {
      this.target = target;
      this.code = code;
      this.diagnostic = diagnostic;
      this.pending = pending;
    }

    public JSONObject failure() {
      try {
        JSONObject reply =
            ScreenReply.cancelled(code, false, pending)
                .put("stage", "TARGET_OBSERVATION")
                .put("targetDiagnostic", diagnostic);
        if (code.equals("ACCESSIBILITY_UNAVAILABLE"))
          reply
              .put("availability", "UNAVAILABLE")
              .put("connectionState", "NOT_CONNECTED")
              .put("nextAction", "OPEN_ACCESSIBILITY_SETTINGS")
              .put("settingsAction", "android.settings.ACCESSIBILITY_SETTINGS")
              .put("repair", AccessibilityRepairOwner.status());
        return reply;
      } catch (org.json.JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
  }

  public TargetPreflight observeTargetForRequest(BooleanSupplier current) {
    return observeTargetForRequest(current, ScreenRequestExecutor.RESPONSE_LIMIT_MILLIS);
  }

  TargetPreflight observeTargetForRequest(long budgetMillis) {
    return observeTargetForRequest(() -> true, budgetMillis);
  }

  private TargetPreflight observeTargetForRequest(BooleanSupplier current, long budgetMillis) {
    if (android.os.Looper.myLooper() != null
        && android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
      return new TargetPreflight(
          null, "SCREEN_CALLER_MAIN_THREAD", "BACKGROUND_CALLER_REQUIRED", false);
    var lease = connection.snapshot();
    if (lease == null)
      return new TargetPreflight(
          null, "ACCESSIBILITY_UNAVAILABLE", "ACCESSIBILITY_UNAVAILABLE", false);
    try {
      return requests.execute(
          () -> connection.current(lease) && current.getAsBoolean(),
          SystemClock::elapsedRealtime,
          SystemClock.elapsedRealtime()
              + Math.min(budgetMillis, ScreenRequestExecutor.RESPONSE_LIMIT_MILLIS),
          () -> {
            try (var resolved = ScreenTargetResolver.resolve(lease.service, 0)) {
              if (!connection.current(lease) || !current.getAsBoolean())
                return new TargetPreflight(null, "SCREEN_RUN_CHANGED", "CONNECTION_CHANGED", false);
              return new TargetPreflight(
                  resolved.target, "SCREEN_TARGET_UNAVAILABLE", resolved.diagnostic, false);
            }
          },
          failure -> {
            try {
              com.deepseekharness.app.core.DiagnosticLog.record(
                  lease.service.getApplicationContext(),
                  "SCREEN_TARGET_PRECHECK_FAILURE",
                  "stage=TARGET_OBSERVATION serviceEpoch="
                      + lease.epoch
                      + " failure="
                      + failure.getClass().getName());
            } catch (Throwable diagnosticUnavailable) {
              /* The response remains bounded. */
            }
            return new TargetPreflight(
                null, "SCREEN_TARGET_PRECHECK_UNAVAILABLE", "PLATFORM_UNAVAILABLE", false);
          });
    } catch (ScreenRequestExecutor.Refused refused) {
      return new TargetPreflight(
          null,
          refused.code.equals("SCREEN_REQUEST_TIMEOUT")
              ? "SCREEN_TARGET_PRECHECK_TIMEOUT"
              : refused.code,
          "TARGET_OBSERVATION_NOT_COMPLETED",
          refused.requestMayStillBeRunning);
    }
  }

  public void revoke() {
    connection.revoke();
    observations.clear();
    nodes.clear();
  }

  public String execute(
      CapabilityBroker broker,
      CapabilityRequest request,
      BooleanSupplier authority,
      String operation,
      JSONObject args) {
    if (android.os.Looper.myLooper() != null
        && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      if (broker != null && request != null) broker.cancel(request);
      return ScreenReply.refused("SCREEN_CALLER_MAIN_THREAD").toString();
    }
    ScreenRequestContext context =
        new ScreenRequestContext(broker, request, authority, connection, true);
    if (context.service() == null) return ScreenReply.unavailable().toString();
    if (context.target() == null || context.target().displayId != 0)
      return ScreenReply.refused("SCREEN_RUN_CHANGED").toString();
    try {
      return requests
          .execute(
              context::current,
              broker::nowMillis,
              request.deadlineElapsedRealtimeMillis,
              () -> run(context, operation, args),
              failure -> failureReply(context, operation, failure))
          .toString();
    } catch (ScreenRequestExecutor.Refused refusal) {
      boolean mayHaveSubmitted = context.cancelAndMayHaveSubmitted();
      observations.clear();
      nodes.clear();
      return ScreenReply.cancelled(refusal.code, mayHaveSubmitted, refusal.requestMayStillBeRunning)
          .toString();
    }
  }

  private JSONObject failureReply(
      ScreenRequestContext context, String operation, Throwable failure) {
    String code =
        failure instanceof Exception error
            ? ScreenScreenshotCapture.safeCode(error)
            : "SCREEN_OPERATION_UNAVAILABLE";
    if (failure instanceof ScreenScreenshotCapture.CaptureFailure captureFailure) {
      // Only an actual callback Throwable is diagnostic evidence; system codes are not causes.
      if (captureFailure.getCause() != null)
        recordFailure(context, FailureStage.CAPTURE, code, captureFailure.getCause(), true);
    } else {
      recordFailure(context, FailureStage.MAIN, code, failure, "screenshot".equals(operation));
    }
    return ScreenReply.failure(context, code);
  }

  /** Diagnostics own no authority and cannot change the original result if their sink fails. */
  static void recordFailure(
      ScreenRequestContext context,
      FailureStage stage,
      String apiCode,
      Throwable failure,
      boolean screenshotInternal) {
    try {
      var request = context.request();
      var identity = diagnosticIdentity(request);
      java.util.Map<String, Object> fields =
          diagnosticFields(
              apiCode, context.submitted(), context.serviceEpoch(), request.bridgeRunId);
      var application = context.service().getApplicationContext();
      com.deepseekharness.app.core.DiagnosticLog.record(
          application, stage.code, fields.toString() + " failure=" + failure.getClass().getName());
    } catch (Throwable diagnosticUnavailable) {
      // The caller still receives the original API code and execution/UNKNOWN classification.
    }
  }

  static com.deepseekharness.app.diagnostics.DiagnosticContext diagnosticIdentity(
      CapabilityRequest request) {
    // The business request ID can originate in X-Request-Id; it is not trusted log metadata.
    return new com.deepseekharness.app.diagnostics.DiagnosticContext(
        "",
        request.runtime.instanceId,
        "",
        request.runtime.ownerId
            + ":"
            + request.runtime.generation
            + ":"
            + request.runtime.instanceId,
        "screen");
  }

  static java.util.Map<String, Object> diagnosticFields(
      String apiCode, boolean submitted, long serviceEpoch, String bridgeRunId) {
    boolean fixedCode = FIXED_SCREEN_MESSAGES.contains(apiCode);
    return java.util.Map.of(
        "apiCode", fixedCode ? apiCode : "UNKNOWN_CODE",
        "apiCodeOmitted", !fixedCode,
        "requestCorrelation", "NO_TRUSTED_INTERNAL_REQUEST_ID",
        "submitted", submitted,
        "serviceEpoch", serviceEpoch,
        "bridgeRunId", bridgeRunId);
  }

  private JSONObject run(ScreenRequestContext context, String operation, JSONObject args)
      throws Exception {
    boolean mutating =
        !operation.equals("dump") && !operation.equals("tree") && !operation.equals("screenshot");
    try {
      if (!mutating && !context.claimDispatch())
        return ScreenReply.refused("CAPABILITY_NOT_AUTHORIZED");
      if (operation.equals("dump") || operation.equals("tree")) return reader.read(context, nodes);
      if (operation.equals("screenshot"))
        return screenshot(context, args.optString("format", "path"));
      return actions.execute(context, operation, args);
    } finally {
      if (mutating) {
        observations.clear();
        nodes.clear();
      }
    }
  }

  private JSONObject screenshot(ScreenRequestContext context, String format) throws Exception {
    if (!format.equals("mcp") && !format.equals("path"))
      return ScreenReply.refused("SCREENSHOT_FORMAT_INVALID");
    try (var shot = captures.capture(context)) {
      boolean coordinatesAvailable = shot.region != null && !shot.region.obscured();
      JSONObject result =
          ScreenReply.completed()
              .put("kind", "dsha-screenshot-v1")
              .put("mimeType", "image/png")
              .put("width", shot.bitmap.getWidth())
              .put("height", shot.bitmap.getHeight())
              .put("rotation", shot.rotation)
              .put("coordinatesAvailable", coordinatesAvailable)
              .put("coordinateBoundaryReservePixels", 1)
              .put("serviceEpoch", context.serviceEpoch())
              .put("target", ScreenSnapshotReader.target(context.target()));
      if (coordinatesAvailable) {
        result.put("observationRegion", region(shot.region.content()));
        result.put("windowBounds", region(shot.region.window()));
      } else {
        result.put(
            "reason",
            shot.region == null ? "TARGET_WINDOW_GEOMETRY_UNAVAILABLE" : "TARGET_WINDOW_OCCLUDED");
        result.put("nextAction", "USE_FRESH_TREE_NODE");
      }
      if (format.equals("mcp")) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        if (!shot.bitmap.compress(Bitmap.CompressFormat.PNG, 90, encoded))
          throw new IllegalStateException("SCREENSHOT_ENCODE_FAILED");
        if (encoded.size() > 16 * 1024 * 1024)
          throw new IllegalStateException("SCREENSHOT_TOO_LARGE");
        result.put(
            "data",
            android.util.Base64.encodeToString(encoded.toByteArray(), android.util.Base64.NO_WRAP));
      } else {
        File base =
            context.service().getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
        if (base == null) throw new IllegalStateException("SCREENSHOT_STORAGE_UNAVAILABLE");
        File directory = new File(base, "DSHA");
        if (!directory.isDirectory() && !directory.mkdirs())
          throw new IllegalStateException("SCREENSHOT_STORAGE_UNAVAILABLE");
        File target = new File(directory, "screen-" + UUID.randomUUID() + ".png");
        try (FileOutputStream stream = new FileOutputStream(target)) {
          if (!shot.bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream))
            throw new IllegalStateException("SCREENSHOT_ENCODE_FAILED");
        }
        try {
          context.validateCurrentTarget();
        } catch (RuntimeException revoked) {
          // 本次尚未发布的私有截图不留在磁盘，也不能交给后续请求。
          if (!target.delete()) target.deleteOnExit();
          throw revoked;
        }
        result.put("path", target.getAbsolutePath());
      }
      context.validateCurrentTarget();
      if (!coordinatesAvailable) {
        observations.clear();
        if (!context.current()) return ScreenReply.refused("SCREEN_RUN_CHANGED");
        return result;
      }
      var observation =
          observations.publish(
              context.target(),
              context.serviceEpoch(),
              context.ownerIdentity(),
              shot.bitmap.getWidth(),
              shot.bitmap.getHeight(),
              shot.rotation,
              shot.fingerprint,
              shot.region);
      result.put("observationId", observation.id);
      if (!context.current()) {
        observations.clear();
        return ScreenReply.refused("SCREEN_RUN_CHANGED");
      }
      return result;
    }
  }

  private static JSONObject region(com.deepseekharness.app.util.ScreenWindowRegion.Bounds bounds)
      throws org.json.JSONException {
    return new JSONObject()
        .put("left", bounds.left())
        .put("top", bounds.top())
        .put("right", bounds.right())
        .put("bottom", bounds.bottom());
  }
}
