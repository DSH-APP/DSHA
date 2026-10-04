package com.deepseekharness.app.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 3090 桥的路径选择；只识别端点，不解释查询参数或执行平台动作。 */
public final class BridgeRoutes {
  public enum Route {
    DEVICE_VSCREEN_START(true),
    DEVICE_VSCREEN_COMMIT(false),
    DEVICE_PLAN(true),
    DEVICE_EXECUTE(true),
    NOTIFY(false),
    TOAST(false),
    READ_FILE(false),
    HEALTH(false),
    UI(false),
    VSCREEN(false),
    DEVICE(false),
    APPS(false),
    LAUNCH(false),
    CLIP(false),
    SHARE(false),
    OPEN(false),
    VIBRATE(false),
    ASK(false),
    VERSION(false),
    HELP(false),
    PLUGINS(false),
    OVERLAY(false),
    LOCATION(false),
    SENSORS(false),
    SENSOR(false),
    TORCH(false),
    EXPORT(false),
    CONFIRM(true),
    EXEC(true),
    UNKNOWN(false);

    private final boolean commandParameter;

    Route(boolean commandParameter) {
      this.commandParameter = commandParameter;
    }

    public boolean commandParameter() {
      return commandParameter;
    }
  }

  private static final Map<String, Route> EXACT = exactRoutes();

  private BridgeRoutes() {}

  private static Map<String, Route> exactRoutes() {
    Map<String, Route> routes = new HashMap<>();
    add(routes, "/device/vscreen/start", Route.DEVICE_VSCREEN_START);
    add(routes, "/device/vscreen/commit", Route.DEVICE_VSCREEN_COMMIT);
    add(routes, "/device/plan", Route.DEVICE_PLAN);
    add(routes, "/device/execute", Route.DEVICE_EXECUTE);
    add(routes, "/app/notify", Route.NOTIFY);
    add(routes, "/app/toast", Route.TOAST);
    add(routes, "/app/readfile", Route.READ_FILE);
    add(routes, "/health", Route.HEALTH);
    add(routes, "/app/device", Route.DEVICE);
    add(routes, "/app/apps", Route.APPS);
    add(routes, "/app/launch", Route.LAUNCH);
    add(routes, "/app/clip", Route.CLIP);
    add(routes, "/app/share", Route.SHARE);
    add(routes, "/app/open", Route.OPEN);
    add(routes, "/app/vibrate", Route.VIBRATE);
    add(routes, "/app/ask", Route.ASK);
    add(routes, "/app/version", Route.VERSION);
    add(routes, "/app/help", Route.HELP);
    add(routes, "/app/plugins", Route.PLUGINS);
    add(routes, "/app/overlay", Route.OVERLAY);
    add(routes, "/app/location", Route.LOCATION);
    add(routes, "/app/sensors", Route.SENSORS);
    add(routes, "/app/sensor", Route.SENSOR);
    add(routes, "/app/torch", Route.TORCH);
    add(routes, "/app/export", Route.EXPORT);
    add(routes, "/confirm", Route.CONFIRM);
    add(routes, "/exec", Route.EXEC);
    return Collections.unmodifiableMap(routes);
  }

  private static void add(Map<String, Route> routes, String path, Route route) {
    if (routes.put(path, route) != null) throw new IllegalStateException("duplicate bridge route");
  }

  public static Route match(String path) {
    if (path == null) return Route.UNKNOWN;
    Route exact = EXACT.get(path);
    if (exact != null) return exact;
    if (path.startsWith("/app/ui/")) return Route.UI;
    if (path.startsWith("/app/vscreen/")) return Route.VSCREEN;
    return Route.UNKNOWN;
  }
}
