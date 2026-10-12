package com.deepseekharness.app.util;

/** 虚拟屏桥仅接受完整、精确的端点；未知子路径不能借末尾名称执行动作。 */
public final class VirtualScreenRoutes {
  private VirtualScreenRoutes() {}

  public static String operation(String route) {
    if (route == null) return "";
    switch (route) {
      case "/app/vscreen/create":
        return "create";
      case "/app/vscreen/status":
        return "status";
      case "/app/vscreen/launch":
        return "launch";
      case "/app/vscreen/tree":
        return "tree";
      case "/app/vscreen/node":
        return "node";
      case "/app/vscreen/editor":
        return "editor";
      case "/app/vscreen/edit":
        return "edit";
      case "/app/vscreen/submit":
        return "submit";
      case "/app/vscreen/touch":
        return "touch";
      case "/app/vscreen/preview":
        return "preview";
      case "/app/vscreen/see":
        return "see";
      case "/app/vscreen/tap":
        return "tap";
      case "/app/vscreen/swipe":
        return "swipe";
      case "/app/vscreen/key":
        return "key";
      case "/app/vscreen/type":
        return "type";
      case "/app/vscreen/close":
        return "close";
      default:
        return "";
    }
  }

  /**
   * 该端点会向目标应用注入输入或无障碍动作：这些端点「执行后校验失败」时动作可能已经落地，调用方不能把它
   * 当成「未执行」。只读端点（status/preview/see/tree 与 editor 的 get）和生命周期端点不在此列。
   *
   * <p>权威的动作路由表在核心进程侧（{@code VirtualScreenCore.route} 的 {@code /vscreen/tap} 等分支，
   * 与内置插件 {@code dsh-tool-vscreen/lib/server.cjs} 的同名表）：核心新增动作路由时，这里必须同步。
   */
  public static boolean mutates(String operation) {
    if (operation == null) return false;
    switch (operation) {
      case "tap":
      case "swipe":
      case "key":
      case "type":
      case "touch":
      case "node":
      case "edit":
      case "submit":
        return true;
      default:
        return false;
    }
  }

  /** 核心进程侧的路由（{@code /vscreen/*}）是否会向目标应用注入输入或无障碍动作。 */
  public static boolean mutatesCore(String route) {
    String prefix = "/vscreen/";
    if (route == null || !route.startsWith(prefix)) return false;
    return mutates(route.substring(prefix.length()));
  }
}
