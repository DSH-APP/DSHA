package com.deepseekharness.app.util;

import java.lang.ref.WeakReference;

/** 只弱引用最后恢复的宿主；旧页面的 pause/destroy 不能清除新页面。 */
public final class ForegroundReference<T> {
    private WeakReference<T> current = new WeakReference<>(null);

    public synchronized void resumed(T owner) {
        if (owner == null) throw new IllegalArgumentException("前台宿主不能为空");
        current = new WeakReference<>(owner);
    }

    public synchronized void left(T owner) {
        if (current.get() == owner) current.clear();
    }

    public synchronized T current() { return current.get(); }
}
