package com.deepseekharness.app.util;

import java.util.IdentityHashMap;
import java.util.Map;

/** 记录同步作用域与异步进程寿命；维护检查与新任务登记共用同一把锁。 */
public final class RuntimeTaskRegistry {
    private final Map<Token, Thread> active = new IdentityHashMap<>();
    private Maintenance maintenance;
    private long nextId;

    public static final class Snapshot {
        public final long id, elapsedMillis;
        public final String kind, detail;
        private Snapshot(Token token,long now) {
            id=token.id;kind=token.kind;detail=token.detail;
            elapsedMillis=Math.max(0,(now-token.started)/1_000_000);
        }
    }

    public synchronized Token begin(boolean detached) {
        return begin(detached,"执行任务");
    }
    public synchronized Token begin(boolean detached,String kind) {
        Thread owner = Thread.currentThread();
        if (maintenance != null && (detached || maintenance.owner != owner))
            throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("正在维护环境，请完成后再启动终端或后台任务"));
        Token token = new Token(++nextId,kind); active.put(token, detached ? null : owner); return token;
    }
    /** 只读快照不释放工作锁，也不读取进程命令或用户输入。 */
    public synchronized java.util.List<Snapshot> snapshot() {
        long now=System.nanoTime();java.util.List<Snapshot> rows=new java.util.ArrayList<>();
        for(Token token:active.keySet())rows.add(new Snapshot(token,now));
        rows.sort(java.util.Comparator.comparingLong(row->row.id));
        return java.util.Collections.unmodifiableList(rows);
    }
    public synchronized int count() { return active.size(); }
    public synchronized boolean hasOtherTasks() {
        for (Thread owner : active.values()) if (owner != Thread.currentThread()) return true;
        return false;
    }
    /** 拒绝等待/回收其他任务；同线程上层同步作用域可以嵌套，异步终端始终视为其他任务。 */
    public synchronized Maintenance tryEnterMaintenance() {
        if (maintenance != null || hasOtherTasks()) return null;
        maintenance = new Maintenance(Thread.currentThread()); return maintenance;
    }
    public final class Token implements AutoCloseable {
        private final long id,started=System.nanoTime();
        private final String kind;
        private String detail="";
        private Token(long id,String kind) { this.id=id;this.kind=kind==null||kind.isEmpty()?"执行任务":kind; }
        public void describe(String value) {
            synchronized(RuntimeTaskRegistry.this){if(active.containsKey(this))detail=value==null?"":value;}
        }
        /** 原 token 转为异步寿命；不增减计数、不释放维护围栏，已关闭 token 不会复活。 */
        public void detach() {
            synchronized (RuntimeTaskRegistry.this) {
                if (active.containsKey(this)) active.put(this, null);
            }
        }
        @Override public void close() {
            synchronized (RuntimeTaskRegistry.this) { active.remove(this); }
        }
    }
    public final class Maintenance implements AutoCloseable {
        private final Thread owner;
        private boolean closed;
        private Maintenance(Thread owner) { this.owner = owner; }
        @Override public void close() {
            synchronized (RuntimeTaskRegistry.this) {
                if (closed) return;
                if (Thread.currentThread() != owner) throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("维护保护只能由持有线程释放"));
                closed = true;
                if (maintenance == this) maintenance = null;
            }
        }
    }
}
