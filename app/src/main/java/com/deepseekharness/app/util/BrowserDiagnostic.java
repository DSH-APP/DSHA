package com.deepseekharness.app.util;

/** 浏览器消息的类别不决定进程状态；仅正式 fatal 事件可指示启动失败。 */
public final class BrowserDiagnostic {
    private BrowserDiagnostic() { }
    public static String category(String message, boolean ready, boolean explicitFatal) {
        String value = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("[intervention]") || value.contains("ignored attempt to cancel")
                || value.contains("unable to preventdefault") || value.contains("non-cancelable")) return "BROWSER_INTERVENTION";
        if (!ready && explicitFatal) return "STARTUP_ERROR";
        return ready ? "RUNTIME_ERROR" : "BROWSER_ERROR";
    }
}
