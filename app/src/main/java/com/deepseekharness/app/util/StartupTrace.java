package com.deepseekharness.app.util;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** 有界、脱敏的启动时间线；阶段和插件故障随任务保留，不依赖某个页面的回调。 */
public final class StartupTrace {
    private static final int MAX_CHARS = 48 * 1024;
    private static final int MAX_LINES = 300;
    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private final LinkedHashMap<String, String> issues = new LinkedHashMap<>();
    private long generation, began, stageBegan, revision;
    private String stage = com.deepseekharness.app.util.UiText.text("尚未启动");
    private int length;
    private static final class Line {
        final long at; final String text, previous; final int kind;
        Line(long at, String text, String previous, int kind) { this.at=at;this.text=text;this.previous=previous;this.kind=kind; }
        String render(String language) {
            String value=kind==0?text:StartupText.render(text,language);
            if(kind==2)value=StartupText.choose(language,"正在", "Current stage: ")+value+"…";
            if(kind==3)value=StartupText.choose(language,"完成「", "Completed “")+value+StartupText.choose(language,"」，耗时 ", "” in ")+previous;
            return "["+format(at)+"] "+value;
        }
    }
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
        generation=next;began=stageBegan=now;stage=com.deepseekharness.app.util.UiText.text("排队启动");safe=safeMode;browserReady=false;
        lines.clear();issues.clear();length=0;revision++;
        owned(next,now,safeMode?com.deepseekharness.app.util.UiText.text("开始安全启动：使用独立基础配置，原插件和配置保留"):com.deepseekharness.app.util.UiText.text("开始启动 DSH"));
    }
    public synchronized void stage(long expected,long now,String next) {
        if (expected!=generation || next==null || next.equals(stage)) return;
        append(now,stage,format(now-stageBegan),3);
        stage=clean(next);stageBegan=now;append(now,stage,"",2);
    }
    public synchronized void add(long expected,long now,String text) { add(expected,now,text,false); }
    public synchronized void owned(long expected,long now,String text) { add(expected,now,text,true); }
    private void add(long expected,long now,String text,boolean owned) {
        if(expected!=generation || text==null)return;
        for(String raw:text.replace('\r','\n').split("\n")) {
            String value=clean(raw);if(value.isEmpty())continue;
            if(value.length()>3000)value=value.substring(0,3000)+"…";
            append(now,value,"",owned?1:0);
        }
    }
    private void append(long now,String text,String previous,int kind) {
        Line line=new Line(now-began,text,previous,kind);
        if(!lines.isEmpty() && lines.peekLast().render("zh").equals(line.render("zh")))return;
        lines.addLast(line);length+=line.text.length()+line.previous.length()+80;
        while(length>MAX_CHARS || lines.size()>MAX_LINES) {
            Line old=lines.removeFirst();length-=old.text.length()+old.previous.length()+80;
        }
        revision++;
    }

    public synchronized void issue(long expected,long now,String plugin,String detail) {
        if(expected!=generation)return;
        String name=plugin==null?"":clean(plugin), reason=clean(detail);
        int conflict=WebPortPolicy.conflictPort(reason);
        if(conflict>0)name="";
        if(name.length()>180 || reason.isEmpty())return;
        if(reason.length()>1200)reason=reason.substring(0,1200)+"…";
        if(reason.equals(issues.get(name)))return;
        issues.put(name,reason);
        while(issues.size()>20)issues.remove(issues.keySet().iterator().next());
        owned(expected,now,conflict>0?"Web 端口被占用："+conflict:name.isEmpty()?"插件加载异常":"插件加载异常："+name);
        add(expected,now,reason);
    }
    public synchronized void browserReady(long expected,long now) {
        if(expected!=generation)return;
        browserReady=true;stage(expected,now,com.deepseekharness.app.util.UiText.text("网页已就绪"));revision++;
    }
    public synchronized Snapshot snapshot(long now) {
        return snapshot(now,UiText.language());
    }
    public synchronized Snapshot snapshot(long now,String language) {
        return new Snapshot(generation,revision,Math.max(0,now-began),Math.max(0,now-stageBegan),StartupText.render(stage,language),
                renderLog(language),safe,browserReady,new LinkedHashMap<>(issues));
    }
    private String renderLog(String language) {
        StringBuilder result=new StringBuilder();
        for(Line line:lines) { if(result.length()>0)result.append('\n');result.append(line.render(language)); }
        return result.toString();
    }
    public synchronized boolean isCurrent(long expected) { return expected > 0 && expected == generation; }
    private static String format(long ms) { return String.format(java.util.Locale.ROOT,"%.1fs",Math.max(0,ms)/1000.0); }
    private static String clean(String text) {
        return SensitiveData.redact(text==null?"":text.replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", ""))
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
    }
}
