package com.deepseekharness.app.core;

import android.content.Context;
import android.os.SystemClock;
import com.deepseekharness.app.util.StartupTrace;
import com.deepseekharness.app.util.SensitiveData;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** 将实际运行输出和网页错误合并为有界时间线；不根据日志自动修改插件。 */
public final class StartupDiagnostics {
    private final StartupTrace trace = new StartupTrace();
    private final Map<String, String> owners = new LinkedHashMap<>();
    private final Context context;
    private String recentError = "";
    private int errorLines;
    public StartupDiagnostics(Context context) { this.context = context.getApplicationContext(); }
    public synchronized void begin(long generation, boolean safe) {
        owners.clear(); recentError = ""; errorLines = 0; trace.begin(generation, SystemClock.elapsedRealtime(), safe);
    }
    public StartupTrace.Snapshot snapshot() { return trace.snapshot(SystemClock.elapsedRealtime()); }
    public void stage(long generation, String stage) {
        trace.stage(generation, SystemClock.elapsedRealtime(), stage);
        DiagnosticLog.record(context, "STARTUP_STAGE", stage);
    }
    public synchronized void output(long generation, String chunk) {
        if (!trace.isCurrent(generation) || chunk == null) return;
        for (String line : chunk.split("\n")) {
            if (line.startsWith("[DSHA_STARTUP] ")) {
                try {
                    JSONObject event = new JSONObject(line.substring(15));
                    String type = event.optString("type"), name = event.optString("plugin"), message = event.optString("message");
                    if (name.length() > 214) name = "";
                    String path = event.optString("path");
                    if (!name.isEmpty() && path.startsWith("/") && owners.size() < 3000) owners.put(path + "/", name);
                    if (!name.isEmpty() && owners.size() < 3000) owners.put("\"" + name + "\"", name);
                    org.json.JSONArray ids = event.optJSONArray("ids");
                    if (ids != null) for (int i = 0; i < ids.length() && i < 100 && owners.size() < 3000; i++) {
                        String id = ids.optString(i);
                        String key = "\"" + id + "\"";
                        if (!id.isEmpty()) owners.put(key, owners.containsKey(key) && !name.equals(owners.get(key)) ? "" : name);
                    }
                    if ("stage".equals(type)) stage(generation, message);
                    else if ("issue".equals(type)) issue(generation, name, message);
                    else if (!message.isEmpty()) trace.add(generation, SystemClock.elapsedRealtime(), message);
                    continue;
                } catch (Exception ignored) { }
            }
            trace.add(generation, SystemClock.elapsedRealtime(), line);
            if (line.matches("(?i).*(error|failed|cannot|did not activate|missing).*")) {
                recentError = line; errorLines = 12;
                String name = owner(line);
                if (!name.isEmpty()) issue(generation, name, line);
            } else if (errorLines-- > 0) {
                String name = owner(line);
                if (!name.isEmpty()) issue(generation, name, recentError + "\n" + line);
            }
        }
    }
    private String owner(String text) {
        return com.deepseekharness.app.util.PluginFailureOwner.find(owners, text);
    }
    public synchronized void browser(long generation, String detail) {
        if (!trace.isCurrent(generation)) return;
        issue(generation, owner(detail), detail);
    }
    public synchronized boolean pageEvent(long generation, JSONObject event) {
        if (!trace.isCurrent(generation)) return false;
        String type = event.optString("type"), detail = event.optString("message");
        String id = event.optString("id");
        if ("ready".equals(type)) { browserReady(generation); return false; }
        if ("issue".equals(type)) {
            String name = owner("\"" + id + "\" " + detail);
            issue(generation, name, (id.isEmpty() ? "" : id + "：") + detail);
            if (name.isEmpty()) for (String line : detail.split("\n")) {
                String candidate = owner(line);
                if (!candidate.isEmpty()) issue(generation, candidate, line);
            }
            return event.optBoolean("fatal") && !snapshot().browserReady;
        }
        trace.add(generation, SystemClock.elapsedRealtime(), detail);
        return false;
    }
    public void issue(long generation, String name, String detail) {
        trace.issue(generation, SystemClock.elapsedRealtime(), name, detail);
        DiagnosticLog.record(context, "STARTUP_ERROR", name + ": " + SensitiveData.redact(detail));
    }
    public void browserReady(long generation) { trace.browserReady(generation, SystemClock.elapsedRealtime()); }

    /** 成功重试不得覆盖上次失败证据，供旧设备无输出退出时排查。 */
    public void preserveFailure(java.io.File runtimeLog, String reason) {
        try {
            String text = "最近启动失败\n" + new java.util.Date() + "\n" + reason
                    + "\n\n" + snapshot().log + "\n\n=== 进程输出 ===\n";
            if (runtimeLog.isFile() && !com.deepseekharness.app.util.Compat.isSymbolicLink(runtimeLog))
                text += com.deepseekharness.app.util.TextLogTail.read(runtimeLog, 256 * 1024);
            com.deepseekharness.app.util.Compat.write(new java.io.File(context.getFilesDir(), "last-startup-failure.log"),
                    SensitiveData.redact(text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    public static String lastFailure(Context context) {
        try {
            java.io.File file = new java.io.File(context.getFilesDir(), "last-startup-failure.log");
            return file.isFile() ? SensitiveData.redact(com.deepseekharness.app.util.TextLogTail.read(file, 384 * 1024)) : "暂无保留的失败记录\n";
        } catch (Exception error) { return "失败记录暂不可读\n"; }
    }
}
