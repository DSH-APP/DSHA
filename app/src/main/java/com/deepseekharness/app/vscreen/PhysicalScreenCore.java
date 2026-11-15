package com.deepseekharness.app.vscreen;

import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;
import com.deepseekharness.app.util.PrivilegedScreenPolicy;
import com.deepseekharness.app.util.PrivilegedScreenRoutes;
import com.deepseekharness.app.util.VirtualScreenFailures;
import com.deepseekharness.app.util.VirtualScreenInputResult;
import java.util.Map;
import org.json.JSONObject;

/**
 * 实体屏特权 Core 的路由与动作执行（T130 Phase A）。
 *
 * <p>只接受 typed 固定动词：{@code /priv/info|frame|config|tap|swipe|key|close}。没有任何 shell、路径、
 * 任意 keycode 或任意 displayId 参数；坐标是帧图像空间，由 Core 换算成 display 0 坐标后注入。
 * 凭据由 {@link PhysicalScreenDisplaySession} 签发并只消费一次；动作后返回新帧与新凭据，
 * 一次往返即可继续下一步。
 */
final class PhysicalScreenCore {
  static final int DISPLAY_ID = 0;

  private static PhysicalScreenDisplaySession session;
  private static PrivilegedScreenPolicy.FrameConfig config =
      PrivilegedScreenPolicy.FrameConfig.defaults();

  private PhysicalScreenCore() {}

  static synchronized JSONObject route(String path, Map<String, String> query) {
    if (PrivilegedScreenRoutes.corePing(path)) {
      JSONObject alive = new JSONObject();
      try {
        alive.put("ok", true);
      } catch (Exception ignored) {
      }
      return alive;
    }
    String operation = PrivilegedScreenRoutes.coreOperation(path);
    if (operation.isEmpty()) return failure("UNKNOWN_ROUTE", "NOT_SENT", false);
    try {
      if (operation.equals("config")) return configure(query);
      if (operation.equals("close")) {
        release();
        return new JSONObject().put("ok", true).put("state", "COMPLETED");
      }
      PhysicalScreenDisplaySession active = ensureSession();
      if (active == null) return failure("PRIV_DISPLAY_UNAVAILABLE", "NOT_SENT", false);
      long now = VirtualScreenCore.elapsed();
      if (operation.equals("info")) return active.status(now);
      if (operation.equals("frame")) return active.frame(longParam(query, "since", -1), now);
      return action(active, operation, query, now);
    } catch (Throwable error) {
      return failure(VirtualScreenFailures.code(error), "NOT_SENT", false);
    }
  }

  private static JSONObject action(
      PhysicalScreenDisplaySession active, String operation, Map<String, String> query, long now)
      throws Exception {
    long frameSeq = longParam(query, "frameSeq", -1);
    String observation = query.getOrDefault("observationId", "");
    String generation = query.getOrDefault("generation", "");
    int waitMs = (int) longParam(query, "waitMs", PrivilegedScreenPolicy.DEFAULT_FRAME_WAIT_MS);
    String invalidWait = PrivilegedScreenPolicy.frameWaitRejection(waitMs);
    if (invalidWait != null) return failure(invalidWait, "NOT_SENT", false);

    // 固定动词参数先校验，再消费凭据：参数错误不能白白作废一次观察。
    float[] coordinates = null;
    int keycode = -1;
    int duration = 0;
    switch (operation) {
      case "tap":
        coordinates = new float[] {floatParam(query, "x"), floatParam(query, "y")};
        break;
      case "swipe":
        coordinates =
            new float[] {
              floatParam(query, "x1"), floatParam(query, "y1"),
              floatParam(query, "x2"), floatParam(query, "y2")
            };
        duration = (int) longParam(query, "ms", 300);
        String invalidDuration = PrivilegedScreenPolicy.swipeDurationRejection(duration);
        if (invalidDuration != null) return failure(invalidDuration, "NOT_SENT", false);
        break;
      case "key":
        keycode = (int) longParam(query, "keycode", -1);
        if (!PrivilegedScreenPolicy.keyAllowed(keycode))
          return failure("PRIV_INVALID_KEY", "NOT_SENT", false);
        break;
      default:
        return failure("UNKNOWN_ROUTE", "NOT_SENT", false);
    }
    if (coordinates != null) {
      String invalid =
          operation.equals("tap")
              ? PrivilegedScreenPolicy.coordinateRejection(
                  coordinates[0], coordinates[1], active.captureWidth, active.captureHeight)
              : firstRejection(active, coordinates);
      if (invalid != null) return failure(invalid, "NOT_SENT", false);
    }

    String expectedPackage = query.getOrDefault("expectedPackage", "");
    String actualPackage = VirtualScreenCore.currentPackage(DISPLAY_ID);
    String target =
        PrivilegedScreenPolicy.targetRejection(
            expectedPackage, actualPackage, !actualPackage.isEmpty());
    if (target != null) return failure(target, "NOT_SENT", false);

    String credential = active.claim(frameSeq, observation, generation, now);
    if (credential != null) return failure(credential, "NOT_SENT", false);

    long started = VirtualScreenCore.elapsed();
    VirtualScreenInputResult result;
    switch (operation) {
      case "tap":
        result = active.tap(coordinates[0], coordinates[1]);
        break;
      case "swipe":
        result =
            active.swipe(coordinates[0], coordinates[1], coordinates[2], coordinates[3], duration);
        break;
      default:
        result = active.key(keycode);
        break;
    }
    long inputMs = Math.max(0, VirtualScreenCore.elapsed() - started);
    if (result == VirtualScreenInputResult.REJECTED)
      return failure("PRIV_INPUT_REJECTED", "NOT_SENT", false);

    JSONObject receipt = active.afterAction(waitMs, inputMs, VirtualScreenCore.elapsed());
    if (result == VirtualScreenInputResult.UNKNOWN) {
      // 注入可能已经落地：保留新帧与新观察，但绝不把这次动作报成成功、也绝不自动重放。
      receipt
          .put("ok", false)
          .put("state", "EXECUTION_UNKNOWN")
          .put("error", "PRIV_INPUT_RESULT_UNKNOWN")
          .put("executionUnknown", true)
          .put("retryable", false);
    }
    if (!receipt.has("package"))
      receipt.put("package", actualPackage).put("packageVerified", !actualPackage.isEmpty());
    return receipt;
  }

  private static String firstRejection(PhysicalScreenDisplaySession active, float[] points) {
    for (int i = 0; i + 1 < points.length; i += 2) {
      String rejected =
          PrivilegedScreenPolicy.coordinateRejection(
              points[i], points[i + 1], active.captureWidth, active.captureHeight);
      if (rejected != null) return rejected;
    }
    return null;
  }

  private static JSONObject configure(Map<String, String> query) throws Exception {
    Integer maxWidth = optionalInt(query, "maxWidth");
    Integer quality = optionalInt(query, "jpegQuality");
    Integer fps = optionalInt(query, "maxFps");
    String invalid = PrivilegedScreenPolicy.configRejection(maxWidth, quality, fps);
    if (invalid != null) return failure(invalid, "NOT_SENT", false);
    PrivilegedScreenPolicy.FrameConfig next =
        new PrivilegedScreenPolicy.FrameConfig(
            maxWidth == null ? config.maxWidth() : maxWidth,
            quality == null ? config.jpegQuality() : quality,
            fps == null ? config.maxFps() : fps);
    if (!next.equals(config)) {
      config = next;
      release();
    }
    return new JSONObject()
        .put("ok", true)
        .put("state", "COMPLETED")
        .put("frameConfig", frameConfigJson())
        .put("recreated", true)
        .put("accessibilityRequired", false);
  }

  /** 当前显示尺寸变化（旋转、分屏、分辨率切换）或帧配置变化时重建镜像并更换代次。 */
  private static PhysicalScreenDisplaySession ensureSession() throws Exception {
    Point size = currentDisplaySize();
    if (size == null) return null;
    if (session != null && session.matches(size, config)) return session;
    release();
    session = PhysicalScreenDisplaySession.create(config);
    return session;
  }

  private static Point currentDisplaySize() {
    try {
      android.content.Context context = VirtualScreenCore.privilegedContext();
      DisplayManager manager =
          (DisplayManager) context.getSystemService(android.content.Context.DISPLAY_SERVICE);
      Display display = manager == null ? null : manager.getDisplay(DISPLAY_ID);
      if (display == null) return null;
      Point size = new Point();
      display.getRealSize(size);
      return size.x > 0 && size.y > 0 ? size : null;
    } catch (Throwable unavailable) {
      return null;
    }
  }

  static synchronized void expire() {
    if (session != null) session.expire();
  }

  static synchronized void release() {
    PhysicalScreenDisplaySession current = session;
    session = null;
    if (current != null) current.close();
  }

  static synchronized JSONObject frameConfigJson() throws Exception {
    return new JSONObject()
        .put("maxWidth", config.maxWidth())
        .put("jpegQuality", config.jpegQuality())
        .put("maxFps", config.maxFps());
  }

  private static long longParam(Map<String, String> query, String name, long fallback) {
    String value = query.get(name);
    if (value == null || value.isEmpty()) return fallback;
    if (!value.matches("-?[0-9]{1,12}"))
      throw new IllegalArgumentException("PRIV_INVALID_ARGUMENT");
    return Long.parseLong(value);
  }

  private static float floatParam(Map<String, String> query, String name) {
    String value = query.get(name);
    if (value == null || value.isEmpty() || !value.matches("-?[0-9]{1,6}(\\.[0-9]{1,4})?"))
      throw new IllegalArgumentException("PRIV_INVALID_ARGUMENT");
    return Float.parseFloat(value);
  }

  private static Integer optionalInt(Map<String, String> query, String name) {
    String value = query.get(name);
    if (value == null || value.isEmpty()) return null;
    if (!value.matches("[0-9]{1,6}")) throw new IllegalArgumentException("PRIV_INVALID_ARGUMENT");
    return Integer.parseInt(value);
  }

  private static JSONObject failure(String code, String state, boolean unknown) {
    JSONObject value = VirtualScreenTransport.failure(code, unknown);
    try {
      value
          .put("state", state)
          .put("retryable", false)
          .put("automaticRetry", false)
          .put("accessibilityRequired", false);
    } catch (Throwable ignored) {
      // VirtualScreenTransport.failure 保证 ok/error 已经写入。
    }
    return value;
  }
}
