package com.deepseekharness.app.backup;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 核心不持有界面；取消只在可安全中止的流式阶段响应，事务提交自行收敛。 */
public final class BackupControl {
    public interface Progress { void update(String stage, long entries, long bytes) throws IOException; }
    private final AtomicBoolean cancelled;
    private final java.util.Set<Runnable> cancellation;
    public final Progress progress;
    public BackupControl(Progress progress) { this(progress,new AtomicBoolean(),java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>())); }
    private BackupControl(Progress progress,AtomicBoolean cancelled,java.util.Set<Runnable> cancellation){this.progress=progress==null?(s,n,b)->{}:progress;this.cancelled=cancelled;this.cancellation=cancellation;}
    public BackupControl withProgress(Progress progress){return new BackupControl(progress,cancelled,cancellation);}
    public boolean isCancelled(){return cancelled.get();}
    public void cancel() { if(cancelled.compareAndSet(false,true))for(Runnable action:cancellation)try{action.run();}catch(RuntimeException ignored){} }
    public AutoCloseable onCancel(Runnable action) {
        cancellation.add(action);if(cancelled.get())action.run();return ()->cancellation.remove(action);
    }
    public void check() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("CANCELLED");
    }
    public void report(String phase,long entries,long bytes) throws IOException { check(); progress.update(phase,entries,bytes); }
}
