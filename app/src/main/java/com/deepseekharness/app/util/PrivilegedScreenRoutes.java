package com.deepseekharness.app.util;

/**
 * 实体屏特权执行（T130 Phase A）的固定端点表，纯逻辑、不依赖 Android。
 *
 * <p>桥端点与特权 Core 端点各自只接受完整精确路径；未知子路径不能借末尾名称执行动作。
 * 动作路由表同时在 Core 进程侧（{@code PhysicalScreenCore.route}）与桥侧（{@code HttpShellService.appPriv}）
 * 使用，两边新增动作路由时必须同步。
 */
public final class PrivilegedScreenRoutes {
  public static final String CORE_PREFIX = "/priv/";
  public static final String BRIDGE_PREFIX = "/app/priv/";

  private PrivilegedScreenRoutes() {}

  /** Core 启动探针：只回答 ok，不创建镜像显示、不签发观察。 */
  public static boolean corePing(String route) {
    return "/priv/ping".equals(route);
  }

  /** 桥端点 {@code /app/priv/*} → 操作名；未识别返回空串。 */
  public static String operation(String route) {
    if (route == null || !route.startsWith(BRIDGE_PREFIX)) return "";
    String verb = route.substring(BRIDGE_PREFIX.length());
    // benchmark 是 App 侧循环（一次动作回执带新帧），没有对应的 Core 路由。
    if (verb.equals("benchmark")) return "benchmark";
    return coreOperation(CORE_PREFIX + verb);
  }

  /** Core 端点 {@code /priv/*} → 操作名；未识别返回空串。 */
  public static String coreOperation(String route) {
    if (route == null || !route.startsWith(CORE_PREFIX)) return "";
    return switch (route.substring(CORE_PREFIX.length())) {
      case "info" -> "info";
      case "frame" -> "frame";
      case "config" -> "config";
      case "tap" -> "tap";
      case "swipe" -> "swipe";
      case "key" -> "key";
      case "close" -> "close";
      default -> "";
    };
  }

  /** Core 路由是否会向 display 0 注入输入；传输失败时这些端点可能已经执行，不能当成未发送。 */
  public static boolean mutates(String operation) {
    if (operation == null) return false;
    return switch (operation) {
      case "tap", "swipe", "key" -> true;
      default -> false;
    };
  }

  /** 会改动 Core 帧通道配置（尺寸/质量/帧率）的端点；配置变化会重建显示代次。 */
  public static boolean configures(String operation) {
    return "config".equals(operation);
  }

  /** 会把实体屏内容返回给调用方的端点；敏感/目标不明时一律 fail-closed。 */
  public static boolean readsContent(String operation) {
    return "frame".equals(operation) || "info".equals(operation);
  }

  /** 桥端点的动作语义：只读查询与生命周期端点不产生注入。 */
  public static boolean mutatesBridge(String operation) {
    return mutates(operation);
  }

  /** Core 路由是否会注入输入，供 {@code VirtualScreenTransport} 标记 UNKNOWN。 */
  public static boolean mutatesCore(String route) {
    return mutates(coreOperation(route));
  }
}
