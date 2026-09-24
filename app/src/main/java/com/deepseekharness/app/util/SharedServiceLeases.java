package com.deepseekharness.app.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 多入口共享一个服务；只有最后一份需求释放时才关闭该代资源。 */
public final class SharedServiceLeases<T> {
    private final Consumer<T> start, stop;
    private final Set<Lease> leases = Collections.newSetFromMap(new IdentityHashMap<>());
    private T current;

    public SharedServiceLeases(Consumer<T> start, Consumer<T> stop) { this.start = start; this.stop = stop; }

    public synchronized Lease acquire(Supplier<T> factory) {
        if (current == null) current = factory.get();
        Lease lease = new Lease(current);
        leases.add(lease);
        try { start.accept(current); return lease; }
        catch (RuntimeException error) { lease.close(); throw error; }
    }

    public final class Lease implements AutoCloseable {
        private final T service;
        private boolean closed;
        private Lease(T service) { this.service = service; }
        public void ensureStarted() {
            synchronized (SharedServiceLeases.this) {
                if (!closed && current == service && leases.contains(this)) start.accept(service);
            }
        }
        @Override public void close() {
            synchronized (SharedServiceLeases.this) {
                if (closed) return;
                closed = true;
                if (!leases.remove(this) || current != service || !leases.isEmpty()) return;
                // 释放完成之前不发布下一个对象；迟到的旧 lease 永远不能关闭新对象。
                try { stop.accept(service); } finally { current = null; }
            }
        }
    }
}
