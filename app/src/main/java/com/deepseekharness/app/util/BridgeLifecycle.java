package com.deepseekharness.app.util;

/** 监听启动的独占代次；绑定中不是就绪，旧 accept 线程不能撤销后来的监听。 */
public final class BridgeLifecycle {
    private long generation;
    private boolean starting, ready;

    public synchronized long beginStart() {
        if (starting || ready) return -1;
        starting = true;
        return ++generation;
    }

    public synchronized boolean publish(long expected) {
        if (generation != expected || !starting) return false;
        starting = false;
        ready = true;
        return true;
    }

    public synchronized boolean finish(long expected) {
        if (!isCurrent(expected)) return false;
        starting = false;
        ready = false;
        return true;
    }

    public synchronized boolean isCurrent(long expected) {
        return generation == expected && (starting || ready);
    }
    public synchronized boolean isStarting() { return starting; }
    public synchronized boolean isReady() { return ready; }
}
