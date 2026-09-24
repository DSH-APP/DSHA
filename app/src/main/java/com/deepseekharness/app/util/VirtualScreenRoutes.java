package com.deepseekharness.app.util;

/** 虚拟屏桥仅接受完整、精确的端点；未知子路径不能借末尾名称执行动作。 */
public final class VirtualScreenRoutes {
    private VirtualScreenRoutes() { }
    public static String operation(String route) {
        if (route == null) return "";
        switch (route) {
            case "/app/vscreen/create": return "create";
            case "/app/vscreen/status": return "status";
            case "/app/vscreen/launch": return "launch";
            case "/app/vscreen/tree": return "tree";
            case "/app/vscreen/node": return "node";
            case "/app/vscreen/editor": return "editor";
            case "/app/vscreen/edit": return "edit";
            case "/app/vscreen/submit": return "submit";
            case "/app/vscreen/touch": return "touch";
            case "/app/vscreen/preview": return "preview";
            case "/app/vscreen/see": return "see";
            case "/app/vscreen/tap": return "tap";
            case "/app/vscreen/swipe": return "swipe";
            case "/app/vscreen/key": return "key";
            case "/app/vscreen/type": return "type";
            case "/app/vscreen/close": return "close";
            default: return "";
        }
    }
}
