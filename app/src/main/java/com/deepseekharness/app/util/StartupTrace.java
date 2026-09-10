package com.deepseekharness.app.util;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** 有界、脱敏的启动时间线；阶段和插件故障随任务保留，不依赖某个页面的回调。 */
public final class StartupTrace {
    private static final int MAX_CHARS = 48 * 1024;
    private static final int MAX_LINES = 300;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final LinkedHashMap<String, String> issues = new LinkedHashMap<>();
    private long generation, began, stageBegan, revision;
    private String stage = "尚未启动";
    private int length;
    private boolean safe, browserReady;

    public static final class Snapshot {
        public final long generation, revision, elapsedMs, stageElapsedMs;
        public final String stage, log;
        public final boolean safe, browserReady;
        public final Map<String, String> issues;
        Snapshot(long generation,long revision,long elapsed,long stageElapsed,String stage,String log,
                 boolean safe,boolean ready,Map<String,String> issues) {
            this.generation=generation;this.revision=revision;elapsedMs=elapsed;stageElapsedMs=stageElapsed;
            this.stage=stage;this.log=log;this.safe=safe;browserReady=ready;this.issues=java.util.Collections.unmodifiableMap(issues);
        }
    }
    public synchronized void begin(long next, long now, boolean safeMode) {
        generation=next;began=stageBegan=now;stage="排队启动";safe=safeMode;browserReady=false;
        lines.clear();issues.clear();length=0;revision++;
        add(next,now,safeMode?"开始安全启动：使用独立基础配置，原插件和配置保留":"开始启动 DSH");
    }
    public synchronized void stage(long expected,long now,String next) {
        if (expected!=generation || next==null || next.equals(stage)) return;
        add(expected,now,"完成「"+stage+"」，耗时 "+format(now-stageBegan));
        stage=clean(next);stageBegan=now;add(expected,now,"正在"+stage+"…");
    }
    public synchronized void add(long expected,long now,String text) {
        if(expected!=generation || text==null)return;
        for(String raw:text.replace('\r','\n').split("\n")) {
            String value=clean(raw);if(value.isEmpty())continue;
            if(value.length()>3000)value=value.substring(0,3000)+"…";
            String line="["+format(now-began)+"] "+value;
            if(!lines.isEmpty() && lines.peekLast().equals(line))continue;
            lines.addLast(line);length+=line.length()+1;
            while(length>MAX_CHARS || lines.size()>MAX_LINES)length-=lines.removeFirst().length()+1;
            revision++;
        }
    }
    public synchronized void issue(long expected,long now,String plugin,String detail) {
        if(expected!=generation)return;
        String name=plugin==null?"":clean(plugin), reason=clean(detail);
        if(name.length()>180 || reason.isEmpty())return;
        if(reason.length()>1200)reason=reason.substring(0,1200)+"…";
        if(reason.equals(issues.get(name)))return;
        issues.put(name,reason);
        while(issues.size()>20)issues.remove(issues.keySet().iterator().next());
        add(expected,now,(name.isEmpty()?"插件加载异常":"插件「"+name+"」异常")+"："+reason);
    }
    public synchronized void browserReady(long expected,long now) {
        if(expected!=generation)return;
        browserReady=true;stage(expected,now,"网页已就绪");revision++;
    }
    public synchronized Snapshot snapshot(long now) {
        return new Snapshot(generation,revision,Math.max(0,now-began),Math.max(0,now-stageBegan),stage,
                String.join("\n",lines),safe,browserReady,new LinkedHashMap<>(issues));
    }
    public synchronized boolean isCurrent(long expected) { return expected > 0 && expected == generation; }
    private static String format(long ms) { return String.format(java.util.Locale.ROOT,"%.1fs",Math.max(0,ms)/1000.0); }
    private static String clean(String text) {
        return SensitiveData.redact(text==null?"":text.replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", ""))
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
    }
}
