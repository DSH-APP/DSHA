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
import com.deepseekharness.app.util.ScreenSenseStateStore;
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

  /** 动作未执行、但值得直接补一份新树的拒绝码：新树能让调用方用新凭据重试，而不是重放旧凭据。 */
  private static final java.util.Set<String> REFUSALS_WORTH_A_FRESH_TREE =
      java.util.Set.of(
          "STALE_NODE",
          "FRAME_NOT_OBSERVED",
          "STALE_FRAME",
          "EDITOR_CHANGED",
          "NODE_NOT_FOUND",
          "INPUT_NOT_FOCUSED");

  /** 动作后观察要给读树留的最小预算；请求截止前不足时宁可只回动作回执，也不把回执拖成超时。 */
  private static final long OBSERVATION_READ_RESERVE_MILLIS = 1_000L;

  private final ScreenConnectionEpoch<DshaAccessibilityService> connection;
  private final com.deepseekharness.app.util.ScreenEventPulse events;
  private final ScreenRequestExecutor requests = new ScreenRequestExecutor();
  private final ScreenObservationLedger observations =
      new ScreenObservationLedger(SystemClock::elapsedRealtime);
  private final ScreenNodeLeaseStore<ScreenSemanticNode> nodes =
      new ScreenNodeLeaseStore<>(SystemClock::elapsedRealtime, ScreenSemanticNode::close);
  private final ScreenSenseStateStore senseStates =
      new ScreenSenseStateStore(SystemClock::elapsedRealtime);
  private final ScreenScreenshotCapture captures = new ScreenScreenshotCapture();
  private final ScreenSnapshotReader reader = new ScreenSnapshotReader();
  private final ScreenSenseReader senseReader = new ScreenSenseReader();
  private final ScreenActionExecutor actions =
      new ScreenActionExecutor(captures, observations, nodes);

  public MainScreenActions(ScreenConnectionEpoch<DshaAccessibilityService> connection) {
    this(connection, null);
  }

  public MainScreenActions(
      ScreenConnectionEpoch<DshaAccessibilityService> connection,
      com.deepseekharness.app.util.ScreenEventPulse events) {
    this.connection = connection;
    this.events = events;
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
    senseStates.clear();
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
    JSONObject result = ScreenReply.failure(context, code);
    if (!context.submitted()
        && java.util.Set.of("SCREEN_WINDOW_GEOMETRY_CHANGED", "SCREEN_GEOMETRY_CHANGED")
            .contains(code))
      try {
        result
            .put("nextAction", "OBSERVE_A_NEW_SCREENSHOT_AFTER_LAYOUT_SETTLES")
            .put("automaticRetry", false);
      } catch (org.json.JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
    return result;
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

  private static boolean flag(JSONObject args, String name) {
    Object value = args.opt(name);
    return value instanceof Boolean
        ? (Boolean) value
        : value != null
            && ("1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString()));
  }

  private JSONObject run(ScreenRequestContext context, String operation, JSONObject args)
      throws Exception {
    boolean senseState = operation.equals("senseState");
    boolean observing =
        senseState
            || operation.equals("dump")
            || operation.equals("tree")
            || operation.equals("screenshot");
    boolean mutating = !observing;
    // 动作即观察：调用方显式要求时，动作成功后在同一次请求里等界面稳定并生成新树；旧调用方不传
    // observe，行为与本改动前完全一致（动作回执、租约清理、请求预算都不变）。
    boolean observeAfterAction = mutating && events != null && flag(args, "observe");
    long dispatchAt = -1L;
    boolean keepFreshTree = false;
    if (observeAfterAction) {
      events.beginWatch();
      dispatchAt = SystemClock.elapsedRealtime();
    }
    try {
      if (!mutating && !context.claimDispatch())
        return ScreenReply.refused("CAPABILITY_NOT_AUTHORIZED");
      if (senseState) {
        nodes.clear();
        return senseReader.read(
            context,
            senseStates,
            flag(args, "geom"),
            args.has("max") ? args.optInt("max", 200) : 200,
            flag(args, "full"));
      }
      if (operation.equals("dump") || operation.equals("tree")) return reader.read(context, nodes);
      if (operation.equals("screenshot"))
        return screenshot(
            context,
            args.optString("format", "path"),
            args.optInt("maxImageBytes", 16 * 1024 * 1024));
      JSONObject reply = actions.execute(context, operation, args);
      if (observeAfterAction) keepFreshTree = attachObservation(context, reply, dispatchAt);
      return reply;
    } finally {
      if (observeAfterAction) events.endWatch();
      if (mutating) {
        observations.clear();
        // 新观察的节点租约必须比这一次动作活得久，下一次 android_node 才能直接用；其余情况仍
        // 和以前一样立刻清空。新树由 reader.read 的 begin 重新签发，旧租约已经全部作废。
        if (!keepFreshTree) nodes.clear();
        senseStates.clearOwner(context.ownerIdentity());
      }
    }
  }

  /**
   * 动作回执里附一份动作后生成的新观察。只有已验证完成（COMPLETED）或凭据失效（NOT_SENT）的动作才附；
   * 像素坐标动作不返回截图，坐标凭据仍必须单独走 /app/ui/screenshot。观察失败只写
   * {@code observationUnavailable}，绝不改写动作状态，也不把 UNKNOWN 变成成功。
   */
  private boolean attachObservation(
      ScreenRequestContext context, JSONObject reply, long dispatchAt) {
    boolean completed = "COMPLETED".equals(reply.optString("state"));
    if (!completed && !refusedForFreshCredentials(reply)) return false;
    try {
      if (completed) {
        if (context.request() == null) return false;
        long now = SystemClock.elapsedRealtime();
        long deadline =
            Math.min(
                dispatchAt + com.deepseekharness.app.util.ScreenSettlePolicy.DEFAULT.totalMillis(),
                context.request().deadlineElapsedRealtimeMillis - OBSERVATION_READ_RESERVE_MILLIS);
        if (deadline <= now) {
          reply.put("observationUnavailable", "SCREEN_OBSERVATION_DEADLINE");
          reply.put("nextAction", "OBSERVE_A_FRESH_SCREEN");
          return false;
        }
        var outcome =
            events.await(
                dispatchAt,
                context.target().packageName,
                context.target().windowId,
                deadline,
                com.deepseekharness.app.util.ScreenSettlePolicy.DEFAULT,
                context::current);
        reply.put("settle", settle(outcome));
        if (!context.current()) return false;
      }
      // 读屏会重新签发 treeId 与全部 nodeId；同一目标窗口、同一任务代次才可能成功。
      JSONObject tree = reader.read(context, nodes);
      JSONObject observation =
          new JSONObject()
              .put("kind", "dsha-screen-after-action-v1")
              .put("treeId", tree.optString("treeId"))
              .put("text", tree.optString("text"))
              .put("nodes", tree.optJSONArray("nodes"))
              .put("visited", tree.optInt("visited"))
              .put("serviceEpoch", tree.optLong("serviceEpoch"))
              .put("target", tree.optJSONObject("target"))
              .put("coordinateAuthorization", "SCREENSHOT_OBSERVATION_REQUIRED")
              .put("screenshotAvailable", false)
              .put(
                  "note",
                  completed
                      ? "动作后的新树：treeId/nodeId 是这份新树的一次性租约，可直接继续操作；坐标操作仍须单独 /app/ui/screenshot。"
                      : "动作未执行：这是当前屏的新树，可直接用新 treeId/nodeId 重试；不要重放旧凭据。");
      if (!completed) observation.put("staleObservation", true);
      reply.put("observation", observation);
      return true;
    } catch (Throwable unavailable) {
      // 观察失败不能把动作回执改成失败；调用方按 nextAction 重新观察即可。
      try {
        reply.put("observationUnavailable", observationFailureCode(unavailable));
        reply.put("nextAction", "OBSERVE_A_FRESH_SCREEN");
        reply.put("automaticRetry", false);
      } catch (Throwable ignored) {
        // 回执本身就是 JSONObject，这里不可能失败；失败也不能影响原动作结果。
      }
      return false;
    }
  }

  private static boolean refusedForFreshCredentials(JSONObject reply) {
    if (!"NOT_SENT".equals(reply.optString("state"))) return false;
    String code = reply.optString("error", reply.optString("code"));
    return REFUSALS_WORTH_A_FRESH_TREE.contains(code);
  }

  private static JSONObject settle(com.deepseekharness.app.util.ScreenSettlePolicy.Outcome outcome)
      throws org.json.JSONException {
    return new JSONObject()
        .put("settled", outcome.settled())
        .put("timedOut", outcome.timedOut())
        .put("sawEvent", outcome.sawEvent())
        .put("firstEventMs", outcome.firstEventMs())
        .put("quietMs", outcome.quietMs())
        .put("totalMs", outcome.totalMs())
        .put("events", outcome.events())
        .put(
            "firstEventBudgetMs",
            com.deepseekharness.app.util.ScreenSettlePolicy.DEFAULT.firstEventMillis())
        .put("quietBudgetMs", com.deepseekharness.app.util.ScreenSettlePolicy.DEFAULT.quietMillis())
        .put("totalBudgetMs", com.deepseekharness.app.util.ScreenSettlePolicy.DEFAULT.totalMillis())
        .put("source", "accessibility-event");
  }

  private static String observationFailureCode(Throwable failure) {
    if (failure == null) return "SCREEN_OBSERVATION_UNAVAILABLE";
    if (failure instanceof DshaAccessibilityService.TargetChangedException)
      return "SCREEN_TARGET_CHANGED";
    String message = failure.getMessage();
    if (message != null && message.matches("[A-Z][A-Z0-9_]{0,64}")) return message;
    return "SCREEN_OBSERVATION_UNAVAILABLE";
  }

  private JSONObject screenshot(ScreenRequestContext context, String format, int maxImageBytes)
      throws Exception {
    if (!format.equals("mcp") && !format.equals("path"))
      return ScreenReply.refused("SCREENSHOT_FORMAT_INVALID");
    try (var shot = captures.capture(context)) {
      boolean coordinatesAvailable =
          shot.region != null && shot.region.hasUnexcludedArea(shot.region.content());
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
              .put("geometry", ScreenGeometry.describe(shot.region))
              .put("target", ScreenSnapshotReader.target(context.target()));
      if (coordinatesAvailable) {
        result.put("observationRegion", region(shot.region.content()));
        result.put("windowBounds", region(shot.region.window()));
        var exclusions = new org.json.JSONArray();
        for (var exclusion : shot.region.exclusions())
          exclusions.put(
              new JSONObject()
                  .put("windowId", exclusion.windowId())
                  .put("epoch", exclusion.epoch())
                  .put("bounds", region(exclusion.bounds())));
        result.put("excludedRegions", exclusions);
      } else {
        result.put(
            "reason",
            shot.region == null
                ? "TARGET_WINDOW_GEOMETRY_UNAVAILABLE"
                : shot.region.obscured()
                    ? "TARGET_WINDOW_OCCLUDED"
                    : "OWNED_CONTROL_COVERS_TARGET");
        result.put("nextAction", "CLEAR_OCCLUSION_OR_USE_FRESH_TREE_NODE");
      }
      if (format.equals("mcp")) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        if (!shot.bitmap.compress(Bitmap.CompressFormat.PNG, 90, encoded))
          throw new IllegalStateException("SCREENSHOT_ENCODE_FAILED");
        if (maxImageBytes <= 0 || encoded.size() > Math.min(maxImageBytes, 16 * 1024 * 1024))
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
