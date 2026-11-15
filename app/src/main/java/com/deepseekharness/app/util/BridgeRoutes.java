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
    EXTDEV_STATUS(false),
    EXTDEV_DISCOVER(false),
    EXTDEV_INSPECT(false),
    EXTDEV_BIND(false),
    EXTDEV_OPERATE(false),
    EXTDEV_WATCH(false),
    EXTDEV_PREFLIGHT(false),
    EXTDEV_IMPORT(false),
    EXTDEV_FORGET(false),
    NOTIFY(false),
    TASK_CONTEXT(false),
    TASK_EVENT(false),
    TASK_CLAIM(false),
    TASK_ACK(false),
    CONSENT_POLICY(false),
    TOAST(false),
    READ_FILE(false),
    SAF_TREES(false),
    SAF_LS(false),
    SAF_STAT(false),
    SAF_READ(false),
    SAF_WRITE(false),
    SAF_MKDIR(false),
    SAF_RM(false),
    SAF_PULL(false),
    SAF_PUSH(false),
    HEALTH(false),
    UI(false),
    SENSE(false),
    VSCREEN(false),
    PRIV(false),
    DEVICE(false),
    NET(false),
    NET_RESOLVE(false),
    APPS(false),
    LAUNCH(false),
    CLIP(false),
    SHARE(false),
    INSTALL(false),
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
    ENTRY_TARGET(false),
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
    add(routes, "/app/extdev/status", Route.EXTDEV_STATUS);
    add(routes, "/app/extdev/discover", Route.EXTDEV_DISCOVER);
    add(routes, "/app/extdev/inspect", Route.EXTDEV_INSPECT);
    add(routes, "/app/extdev/bind", Route.EXTDEV_BIND);
    add(routes, "/app/extdev/operate", Route.EXTDEV_OPERATE);
    add(routes, "/app/extdev/watch", Route.EXTDEV_WATCH);
    add(routes, "/app/extdev/preflight", Route.EXTDEV_PREFLIGHT);
    add(routes, "/app/extdev/import", Route.EXTDEV_IMPORT);
    add(routes, "/app/extdev/forget", Route.EXTDEV_FORGET);
    add(routes, "/app/notify", Route.NOTIFY);
    add(routes, "/app/tasks/context", Route.TASK_CONTEXT);
    add(routes, "/app/tasks/event", Route.TASK_EVENT);
    add(routes, "/app/tasks/claim", Route.TASK_CLAIM);
    add(routes, "/app/tasks/ack", Route.TASK_ACK);
    add(routes, "/app/native-policy", Route.CONSENT_POLICY);
    add(routes, "/device/consent", Route.CONSENT_POLICY);
    add(routes, "/app/toast", Route.TOAST);
    add(routes, "/app/readfile", Route.READ_FILE);
    add(routes, "/app/saf/trees", Route.SAF_TREES);
    add(routes, "/app/saf/ls", Route.SAF_LS);
    add(routes, "/app/saf/stat", Route.SAF_STAT);
    add(routes, "/app/saf/read", Route.SAF_READ);
    add(routes, "/app/saf/write", Route.SAF_WRITE);
    add(routes, "/app/saf/mkdir", Route.SAF_MKDIR);
    add(routes, "/app/saf/rm", Route.SAF_RM);
    add(routes, "/app/saf/pull", Route.SAF_PULL);
    add(routes, "/app/saf/push", Route.SAF_PUSH);
    add(routes, "/health", Route.HEALTH);
    add(routes, "/app/device", Route.DEVICE);
    add(routes, "/app/net", Route.NET);
    add(routes, "/app/net/resolve", Route.NET_RESOLVE);
    add(routes, "/app/apps", Route.APPS);
    add(routes, "/app/launch", Route.LAUNCH);
    add(routes, "/app/clip", Route.CLIP);
    add(routes, "/app/share", Route.SHARE);
    add(routes, "/app/install", Route.INSTALL);
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
    add(routes, "/app/entry-target", Route.ENTRY_TARGET);
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
    if (path.equals("/app/sense/state")) return Route.SENSE;
    if (path.startsWith("/app/vscreen/")) return Route.VSCREEN;
    // T130：实体屏特权执行固定端点；未知子路径由 appPriv 再拒绝一次。
    if (path.startsWith("/app/priv/")) return Route.PRIV;
    return Route.UNKNOWN;
  }
}
