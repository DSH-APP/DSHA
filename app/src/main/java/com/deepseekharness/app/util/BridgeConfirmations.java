package com.deepseekharness.app.util;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 每次确认独立绑定桥代次与随机身份；所有确认渠道只允许一次决议。 */
public final class BridgeConfirmations {
    public static final class Request {
        public final long generation;
        public final String identity;
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean allowed;
        private Request(long generation) {
            this.generation = generation;
            identity = "dsha-confirm://request/" + generation + "/" + UUID.randomUUID();
        }
        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return done.await(timeout, unit) && allowed;
        }
    }
    private Request current;
    public synchronized Request begin(long generation) {
        if (generation <= 0 || current != null) return null;
        return current = new Request(generation);
    }
    public synchronized boolean pending(Request request) {
        return request != null && current == request && request.done.getCount() != 0;
    }
    public synchronized boolean resolve(long generation, String identity, boolean allow) {
        if (!pending(current) || current.generation != generation || !current.identity.equals(identity)) return false;
        current.allowed = allow;
        current.done.countDown();
        return true;
    }
    public synchronized void finish(Request request) {
        if (current != request) return;
        if (pending(request)) { request.allowed = false; request.done.countDown(); }
        current = null;
    }
}
