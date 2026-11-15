package com.deepseekharness.app.vscreen;

import android.content.Context;
import android.os.SystemClock;
import com.deepseekharness.app.device.DeviceTransportResult;
import com.deepseekharness.app.util.PrivilegedScreenPolicy;
import com.deepseekharness.app.util.PrivilegedScreenRoutes;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/**
 * 实体屏特权执行（T130 Phase A）的 App 侧管理器。
 *
 * <p>职责只有四件事：
 *
 * <ol>
 *   <li>按需通过既有受管通道（Root / Shizuku / ADB）拉起 {@code --physical} Core，并只保存回执里的
 *       端口/token/出生身份；
 *   <li>把桥请求变成固定动词的 Core 请求，并把 Core 回执整理成与 {@code /app/ui/*} 对齐的
 *       {@code state/error/nextAction/retryable/observation} 结构；
 *   <li>持有"最近一次观察"并在动作发出前作废它：一次性凭据、30 秒、代次绑定，动作失败不重放；
 *   <li>提供低延迟 benchmark：一次 action 请求就带回新帧与新凭据。
 * </ol>
 *
 * <p>这里不做授权决定，也不回退到无障碍：能力开关、任务身份与屏幕确认策略由
 * {@code HttpShellService.appPriv} 在调用本类之前执行。
 */
public final class PhysicalScreenManager {
  interface ChannelChooser {
    VirtualScreenChannel choose(Context context);
  }

  interface Transport {
    JSONObject request(VirtualScreenHandshake endpoint, String route, String query);
  }

  private static volatile PhysicalScreenManager shared;

  private final Context app;
  private final ChannelChooser channels;
  private final Transport transport;
  private final LongSupplier clock;
  private final Object LOCK = new Object();

  private VirtualScreenHandshake endpoint;
  private String channelId = "";
  private String coreInstance = "";
  private String generation = "";
  private String packageName = "";
  private boolean packageVerified;
  private String observationId = "";
  private long observedFrame = -1;
  private long observationAt;
  private int frameWidth = -1, frameHeight = -1;
  private String lastError = "";

  PhysicalScreenManager(
      Context app, ChannelChooser channels, Transport transport, LongSupplier clock) {
    this.app = app;
    this.channels = channels;
    this.transport = transport;
    this.clock = clock;
  }

  public static PhysicalScreenManager from(Context context) {
    PhysicalScreenManager value = shared;
    if (value != null) return value;
    synchronized (PhysicalScreenManager.class) {
      if (shared == null) {
        Context app = context.getApplicationContext();
        VirtualScreenTransport transport = new VirtualScreenTransport();
        shared =
            new PhysicalScreenManager(
                app,
                VirtualScreenChannels::choose,
                transport::request,
                SystemClock::elapsedRealtime);
      }
      return shared;
    }
  }

  public boolean running() {
    synchronized (LOCK) {
      return endpoint != null;
    }
  }

  public String channel() {
    synchronized (LOCK) {
      return channelId;
    }
  }

  public String generation() {
    synchronized (LOCK) {
      return generation;
    }
  }

  public String targetPackage() {
    synchronized (LOCK) {
      return packageName;
    }
  }

  public boolean targetVerified() {
    synchronized (LOCK) {
      return packageVerified;
    }
  }

  public String lastError() {
    synchronized (LOCK) {
      return lastError;
    }
  }

  /** 取状态；首次调用会拉起 Core。 */
  public JSONObject info() {
    synchronized (LOCK) {
      JSONObject started = ensureStartedLocked();
      if (started != null) return started;
      return performLocked("/priv/info", new LinkedHashMap<>());
    }
  }

  /** 取帧并签发一次性观察凭据；这是 agent 的 see。 */
  public JSONObject frame() {
    synchronized (LOCK) {
      JSONObject started = ensureStartedLocked();
      if (started != null) return started;
      Map<String, String> params = new LinkedHashMap<>();
      params.put("since", String.valueOf(observedFrame));
      return performLocked("/priv/frame", params);
    }
  }

  public JSONObject tap(float x, float y, int waitMs) {
    return action("/priv/tap", x, y, 0, 0, 0, waitMs, -1);
  }

  public JSONObject swipe(float x1, float y1, float x2, float y2, int ms, int waitMs) {
    return action("/priv/swipe", x1, y1, x2, y2, ms, waitMs, -1);
  }

  public JSONObject key(int keycode, int waitMs) {
    return action("/priv/key", 0, 0, 0, 0, 0, waitMs, keycode);
  }

  private JSONObject action(
      String route, float x1, float y1, float x2, float y2, int ms, int waitMs, int keycode) {
    String operation = PrivilegedScreenRoutes.coreOperation(route);
    String invalidWait = PrivilegedScreenPolicy.frameWaitRejection(waitMs);
    if (invalidWait != null) return refused(invalidWait, "FIX_THE_REQUEST");
    if ("swipe".equals(operation)) {
      String invalid = PrivilegedScreenPolicy.swipeDurationRejection(ms);
      if (invalid != null) return refused(invalid, "FIX_THE_REQUEST");
    }
    if ("key".equals(operation) && !PrivilegedScreenPolicy.keyAllowed(keycode))
      return refused("PRIV_INVALID_KEY", "FIX_THE_REQUEST");
    synchronized (LOCK) {
      JSONObject started = ensureStartedLocked();
      if (started != null) return started;
      String invalid = credentialRejectionLocked();
      if (invalid != null) return refused(invalid, "OBSERVE_THE_PHYSICAL_SCREEN_AGAIN");
      if ("tap".equals(operation) || "swipe".equals(operation)) {
        String coordinates =
            "tap".equals(operation)
                ? PrivilegedScreenPolicy.coordinateRejection(x1, y1, frameWidth, frameHeight)
                : firstCoordinateRejection(x1, y1, x2, y2);
        if (coordinates != null) return refused(coordinates, "FIX_THE_REQUEST");
      }
      Map<String, String> params = new LinkedHashMap<>();
      if ("tap".equals(operation)) {
        params.put("x", String.valueOf(x1));
        params.put("y", String.valueOf(y1));
      } else if ("swipe".equals(operation)) {
        params.put("x1", String.valueOf(x1));
        params.put("y1", String.valueOf(y1));
        params.put("x2", String.valueOf(x2));
        params.put("y2", String.valueOf(y2));
        params.put("ms", String.valueOf(ms));
      } else {
        params.put("keycode", String.valueOf(keycode));
      }
      params.put("frameSeq", String.valueOf(observedFrame));
      params.put("observationId", observationId);
      params.put("waitMs", String.valueOf(waitMs));
      // 凭据只允许用一次：即使传输失败也不恢复，调用方必须重新观察。
      observationId = "";
      observedFrame = -1;
      observationAt = 0;
      return performLocked(route, params);
    }
  }

  /** 帧通道可调参数（尺寸/质量/最大帧率）；变化会重建镜像并更换显示代次。 */
  public JSONObject configure(Integer maxWidth, Integer jpegQuality, Integer maxFps) {
    synchronized (LOCK) {
      JSONObject started = ensureStartedLocked();
      if (started != null) return started;
      Map<String, String> params = new LinkedHashMap<>();
      if (maxWidth != null) params.put("maxWidth", String.valueOf(maxWidth));
      if (jpegQuality != null) params.put("jpegQuality", String.valueOf(jpegQuality));
      if (maxFps != null) params.put("maxFps", String.valueOf(maxFps));
      JSONObject value = performLocked("/priv/config", params);
      if (value.optBoolean("ok")) {
        // 旧代次的观察与坐标尺寸全部失效；下一次 frame 会重建并签发新观察。
        clearObservationLocked();
        frameWidth = -1;
        frameHeight = -1;
      }
      return value;
    }
  }

  /**
   * 低延迟 benchmark：一次 tap 请求内带动"注入 → 等新帧 → 新观察"，循环 N 次，
   * 同时记录 App 侧往返（命令发出→回执到达）与 Core 侧 inputMs/frameWaitMs。
   * 不依赖无障碍；核心配置与样本数写进报告，真机窗口由主会话执行。
   */
  public JSONObject benchmark(int samples, float x, float y, int waitMs) {
    String invalid = PrivilegedScreenPolicy.benchmarkRejection(samples, waitMs);
    if (invalid != null) return refused(invalid, "FIX_THE_REQUEST");
    synchronized (LOCK) {
      JSONObject started = ensureStartedLocked();
      if (started != null) return started;
      if (frameWidth <= 0 || frameHeight <= 0 || observationId.isEmpty()) {
        JSONObject seen = frame();
        if (!seen.optBoolean("ok")) return seen;
      }
      String size = PrivilegedScreenPolicy.coordinateRejection(x, y, frameWidth, frameHeight);
      if (size != null) return refused(size, "FIX_THE_REQUEST");
      long[] roundTrip = new long[samples];
      long[] input = new long[samples];
      long[] frameWait = new long[samples];
      org.json.JSONArray rows = new org.json.JSONArray();
      int completed = 0;
      JSONObject failure = null;
      try {
        for (int index = 0; index < samples; index++) {
          long startedAt = clock.getAsLong();
          JSONObject receipt = tap(x, y, waitMs);
          long finishedAt = clock.getAsLong();
          if (!receipt.optBoolean("ok")) {
            failure = receipt;
            break;
          }
          roundTrip[index] = Math.max(0, finishedAt - startedAt);
          input[index] = receipt.optLong("inputMs", -1);
          frameWait[index] = receipt.optLong("frameWaitMs", -1);
          completed++;
          rows.put(
              new JSONObject()
                  .put("sample", index + 1)
                  .put("roundTripMs", roundTrip[index])
                  .put("inputMs", input[index])
                  .put("frameWaitMs", frameWait[index])
                  .put("frameSeq", receipt.optLong("frameSeq", -1))
                  .put("changed", !receipt.optBoolean("unchanged", false)));
        }
      } catch (Throwable error) {
        failure = refused("PRIV_BENCHMARK_FAILED", "CHECK_THE_DEVICE_STATE");
      }
      JSONObject report = new JSONObject();
      try {
        report
            .put("mode", "tap-action-with-new-frame")
            .put("samples", samples)
            .put("completedSamples", completed)
            .put("waitMs", waitMs)
            .put("frameConfig", lastFrameConfig())
            .put("roundTripMs", summaryJson(roundTrip, completed))
            .put("inputMs", summaryJson(input, completed))
            .put("frameWaitMs", summaryJson(frameWait, completed))
            .put("rows", rows)
            .put("channel", channelId)
            .put("coreInstance", coreInstance)
            .put("generation", generation)
            .put("accessibilityRequired", false)
            .put(
                "note",
                "roundTripMs = {请求发出 → 回执到达}（App 侧）；inputMs/frameWaitMs 由 Core 用同一单调时钟测量；"
                    + "每次动作回执都带新帧与一次性观察，不需要额外取帧。");
      } catch (Exception encoding) {
        return refused("PRIV_BENCHMARK_ENCODE_FAILED", "CHECK_THE_DEVICE_STATE");
      }
      if (failure != null) {
        try {
          report.put("failure", new JSONObject(failure.toString()));
          JSONObject value = new JSONObject(failure.toString());
          value.put("benchmark", report).put("partial", true);
          return value;
        } catch (Exception ignored) {
          return refused("PRIV_BENCHMARK_FAILED", "CHECK_THE_DEVICE_STATE");
        }
      }
      JSONObject value = new JSONObject();
      try {
        value
            .put("ok", true)
            .put("state", "COMPLETED")
            .put("benchmark", report)
            .put("retryable", false)
            .put("accessibilityRequired", false);
      } catch (Exception ignored) {
      }
      return value;
    }
  }

  /** 显式关闭；失败按 UNKNOWN 报告，不重试。 */
  public JSONObject close() {
    synchronized (LOCK) {
      if (endpoint == null) return completed();
      JSONObject value = performLocked("/priv/close", new LinkedHashMap<>());
      endpoint = null;
      channelId = "";
      coreInstance = "";
      generation = "";
      clearObservationLocked();
      return value;
    }
  }

  /** Core 被停止/卸载或用户撤销能力时丢弃本地连接；不向旧 Core 发送任何东西。 */
  public void revoke() {
    synchronized (LOCK) {
      endpoint = null;
      channelId = "";
      coreInstance = "";
      generation = "";
      packageName = "";
      packageVerified = false;
      clearObservationLocked();
    }
  }

  // ---------------------------------------------------------------------------------------------

  private JSONObject ensureStartedLocked() {
    if (endpoint != null) return null;
    VirtualScreenChannel channel = channels.choose(app);
    if (channel == null)
      return refused("PRIV_CHANNEL_UNAVAILABLE", "ENABLE_ROOT_SHIZUKU_OR_ADB_CHANNEL");
    String command = new PhysicalScreenLauncher().launchCommand(app);
    DeviceTransportResult result;
    try {
      result = channel.startTyped(app, command);
    } catch (Throwable error) {
      return unknown("PRIV_CORE_START_UNKNOWN", "VERIFY_DEVICE_STATE_BEFORE_ANY_RETRY");
    }
    if (result.outcome.state
        != com.deepseekharness.app.capabilities.CapabilityResult.State.COMPLETED) {
      boolean unknown =
          result.outcome.state
              == com.deepseekharness.app.capabilities.CapabilityResult.State.UNKNOWN;
      lastError = result.outcome.code;
      return unknown
          ? unknown("PRIV_CORE_START_UNKNOWN", "VERIFY_DEVICE_STATE_BEFORE_ANY_RETRY")
          : refused(
              result.outcome.code.isEmpty() ? "PRIV_CORE_START_FAILED" : result.outcome.code,
              "ENABLE_ROOT_SHIZUKU_OR_ADB_CHANNEL");
    }
    VirtualScreenHandshake handshake = VirtualScreenHandshake.started(result.output);
    if (handshake == null) {
      lastError = "PRIV_CORE_HANDSHAKE_MISSING";
      return unknown("PRIV_CORE_START_UNKNOWN", "VERIFY_DEVICE_STATE_BEFORE_ANY_RETRY");
    }
    endpoint = handshake;
    channelId = channel.id();
    JSONObject probe = transport.request(handshake, "/priv/info", "");
    if (probe == null || !probe.optBoolean("ok")) {
      lastError =
          probe == null
              ? "PRIV_CORE_UNREACHABLE"
              : probe.optString("error", "PRIV_CORE_UNREACHABLE");
      return unknown("PRIV_CORE_START_UNKNOWN", "VERIFY_DEVICE_STATE_BEFORE_ANY_RETRY");
    }
    remember(probe);
    return null;
  }

  private JSONObject performLocked(String route, Map<String, String> params) {
    if (endpoint == null)
      return refused("PRIV_CORE_NOT_RUNNING", "OBSERVE_THE_PHYSICAL_SCREEN_AGAIN");
    Map<String, String> query = new LinkedHashMap<>(params);
    if (!generation.isEmpty()) query.put("generation", generation);
    if (packageVerified && !packageName.isEmpty()) query.put("expectedPackage", packageName);
    JSONObject response = transport.request(endpoint, route, encode(query));
    if (response == null) return unreachableLocked();
    if (!response.optBoolean("ok")) {
      boolean unknown =
          response.optBoolean("executionUnknown", false)
              || "EXECUTION_UNKNOWN".equals(response.optString("state"));
      String coreError = response.optString("error", "");
      if (isUnreachable(coreError)) {
        if (!unknown) {
          endpoint = null;
          return unreachableLocked();
        }
        JSONObject value =
            unknown("PRIV_INPUT_RESULT_UNKNOWN", "OBSERVE_THE_SCREEN_BEFORE_ANY_RETRY");
        try {
          value.put("coreError", coreError);
        } catch (Exception ignored) {
        }
        return value;
      }
      lastError = coreError;
      JSONObject value;
      try {
        value = new JSONObject(response.toString());
      } catch (Exception unreadable) {
        return refused("PRIV_CORE_RESPONSE_INVALID", "CHECK_THE_DEVICE_STATE");
      }
      try {
        value
            .put("ok", false)
            .put("state", unknown ? "EXECUTION_UNKNOWN" : "NOT_SENT")
            .put("retryable", false)
            .put("accessibilityRequired", false)
            .put("channel", channelId)
            .put("coreInstance", coreInstance)
            .put("generation", generation)
            .put("nextAction", nextActionFor(coreError, unknown));
        if (unknown) value.put("error", "PRIV_INPUT_RESULT_UNKNOWN").put("coreError", coreError);
      } catch (Exception ignored) {
      }
      return value;
    }
    remember(response);
    return shapeSuccess(response);
  }

  private boolean isUnreachable(String code) {
    return "VSCREEN_TRANSPORT_UNAVAILABLE".equals(code)
        || "VSCREEN_NOT_RUNNING".equals(code)
        || "INPUT_RESULT_UNKNOWN".equals(code);
  }

  private JSONObject unreachableLocked() {
    endpoint = null;
    clearObservationLocked();
    lastError = "PRIV_CORE_UNREACHABLE";
    return refused("PRIV_CORE_UNREACHABLE", "CALL_PRIV_INFO_TO_START_THE_CORE_AGAIN");
  }

  private void remember(JSONObject receipt) {
    try {
      String instance = receipt.optString("coreInstance", "");
      if (!instance.isEmpty()) {
        if (!coreInstance.isEmpty() && !coreInstance.equals(instance)) {
          // 新实例：旧观察与旧代次全部作废。
          clearObservationLocked();
          generation = "";
        }
        coreInstance = instance;
      }
      String nextGeneration = receipt.optString("generation", "");
      if (!nextGeneration.isEmpty() && !nextGeneration.equals(generation)) {
        clearObservationLocked();
        generation = nextGeneration;
      }
      String pkg = receipt.optString("package", "");
      if (!pkg.isEmpty() || receipt.has("packageVerified")) {
        packageName = pkg;
        packageVerified = receipt.optBoolean("packageVerified", !pkg.isEmpty());
      }
      if (receipt.has("width") && receipt.has("height")) {
        frameWidth = receipt.optInt("width", frameWidth);
        frameHeight = receipt.optInt("height", frameHeight);
      }
      String observation = receipt.optString("observationId", "");
      if (!observation.isEmpty() && receipt.optLong("frameSeq", -1) > 0) {
        observationId = observation;
        observedFrame = receipt.optLong("frameSeq", -1);
        observationAt = clock.getAsLong();
      }
      if (receipt.has("frameConfig")) frameConfig = receipt.optJSONObject("frameConfig");
    } catch (Throwable ignored) {
      // 回执结构异常不改变本地状态：宁可让下一次动作被拒，也不猜。
    }
  }

  private JSONObject frameConfig;

  private JSONObject lastFrameConfig() {
    return frameConfig == null ? new JSONObject() : frameConfig;
  }

  private void clearObservationLocked() {
    observationId = "";
    observedFrame = -1;
    observationAt = 0;
  }

  private String credentialRejectionLocked() {
    if (observationId.isEmpty() || observedFrame <= 0) return "PRIV_FRAME_NOT_OBSERVED";
    if (clock.getAsLong() - observationAt > PrivilegedScreenPolicy.OBSERVATION_WINDOW_MS)
      return "PRIV_OBSERVATION_TIMEOUT";
    return null;
  }

  private String firstCoordinateRejection(float x1, float y1, float x2, float y2) {
    String first = PrivilegedScreenPolicy.coordinateRejection(x1, y1, frameWidth, frameHeight);
    return first != null
        ? first
        : PrivilegedScreenPolicy.coordinateRejection(x2, y2, frameWidth, frameHeight);
  }

  private JSONObject shapeSuccess(JSONObject receipt) {
    JSONObject value;
    try {
      value = new JSONObject(receipt.toString());
    } catch (Exception unreadable) {
      return refused("PRIV_CORE_RESPONSE_INVALID", "CHECK_THE_DEVICE_STATE");
    }
    try {
      value
          .put("ok", true)
          .put("state", receipt.optString("state", "COMPLETED"))
          .put("retryable", false)
          .put("accessibilityRequired", false)
          .put("channel", channelId)
          .put("coreInstance", coreInstance)
          .put("generation", generation);
      if (!value.has("observation") && value.optString("observationId", "").isEmpty()) {
        value.put("observationUnavailable", "PRIV_OBSERVATION_NOT_ISSUED");
      }
    } catch (Exception ignored) {
    }
    return value;
  }

  private JSONObject refused(String code, String nextAction) {
    JSONObject value = new JSONObject();
    try {
      value
          .put("ok", false)
          .put("state", "NOT_SENT")
          .put("error", code)
          .put("retryable", false)
          .put("automaticRetry", false)
          .put("accessibilityRequired", false)
          .put("nextAction", nextAction)
          .put("channel", channelId)
          .put("coreInstance", coreInstance)
          .put("generation", generation);
    } catch (Exception ignored) {
    }
    lastError = code;
    return value;
  }

  private JSONObject unknown(String code, String nextAction) {
    JSONObject value = new JSONObject();
    try {
      value
          .put("ok", false)
          .put("state", "EXECUTION_UNKNOWN")
          .put("error", code)
          .put("executionUnknown", true)
          .put("retryable", false)
          .put("automaticRetry", false)
          .put("accessibilityRequired", false)
          .put("nextAction", nextAction)
          .put("channel", channelId);
    } catch (Exception ignored) {
    }
    lastError = code;
    return value;
  }

  private JSONObject completed() {
    JSONObject value = new JSONObject();
    try {
      value.put("ok", true).put("state", "COMPLETED").put("accessibilityRequired", false);
    } catch (Exception ignored) {
    }
    return value;
  }

  private static String nextActionFor(String code, boolean unknown) {
    if (unknown) return "OBSERVE_THE_SCREEN_BEFORE_ANY_RETRY";
    switch (code == null ? "" : code) {
      case "PRIV_FRAME_NOT_OBSERVED":
      case "PRIV_OBSERVATION_TIMEOUT":
      case "PRIV_STALE_GENERATION":
      case "PRIV_TARGET_CHANGED":
      case "PRIV_TARGET_UNVERIFIABLE":
        return "OBSERVE_THE_PHYSICAL_SCREEN_AGAIN";
      case "PRIV_FRAME_NOT_READY":
      case "PRIV_DISPLAY_UNAVAILABLE":
        return "CHECK_SCREEN_STATE_AND_DEVICE_CHANNEL";
      case "PRIV_INPUT_REJECTED":
        return "OBSERVE_THE_PHYSICAL_SCREEN_AGAIN";
      default:
        return "FIX_THE_REQUEST_OR_CHECK_THE_DEVICE_STATE";
    }
  }

  private static JSONObject summaryJson(long[] samples, int count) {
    long[] used = new long[Math.max(0, Math.min(samples.length, count))];
    System.arraycopy(samples, 0, used, 0, used.length);
    PrivilegedScreenPolicy.Summary summary = PrivilegedScreenPolicy.Summary.of(used);
    JSONObject value = new JSONObject();
    try {
      value
          .put("count", summary.count())
          .put("p50", summary.p50())
          .put("p95", summary.p95())
          .put("max", summary.max())
          .put("min", summary.min());
    } catch (Exception ignored) {
    }
    return value;
  }

  private static String encode(Map<String, String> query) {
    StringBuilder value = new StringBuilder();
    for (Map.Entry<String, String> entry : query.entrySet()) {
      if (value.length() > 0) value.append('&');
      try {
        value
            .append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"))
            .append('=')
            .append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
      } catch (java.io.UnsupportedEncodingException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
    return value.toString();
  }
}
