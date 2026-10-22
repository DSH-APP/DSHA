package com.deepseekharness.app.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 主屏语义节点租约：与像素观察分开，一次消费，并在所有退出路径释放。 */
public final class ScreenNodeLeaseStore<T> {
  private final Map<String, T> nodes = new LinkedHashMap<>();
  private final Consumer<T> close;
  private final LongSupplier clock;
  private ScreenTarget target;
  private long epoch, createdAt;
  private String treeId = "";
  private String owner = "";

  public ScreenNodeLeaseStore(LongSupplier clock, Consumer<T> close) {
    this.clock = clock;
    this.close = close;
  }

  public synchronized String begin(ScreenTarget target, long epoch) {
    return begin(target, epoch, "");
  }

  public synchronized String begin(ScreenTarget target, long epoch, String owner) {
    clear();
    this.target = target;
    this.epoch = epoch;
    this.owner = owner;
    createdAt = clock.getAsLong();
    treeId = UUID.randomUUID().toString();
    return treeId;
  }

  public synchronized String add(T node) {
    if (target == null || nodes.size() >= 200) {
      close.accept(node);
      return "";
    }
    String id = UUID.randomUUID().toString();
    nodes.put(id, node);
    return id;
  }

  public synchronized T consume(String tree, String node, ScreenTarget current, long epoch) {
    return consume(tree, node, current, epoch, "");
  }

  public synchronized T consume(
      String tree, String node, ScreenTarget current, long epoch, String owner) {
    long age = clock.getAsLong() - createdAt;
    if (!treeId.equals(tree)
        || target == null
        || !target.matches(current)
        || this.epoch != epoch
        || !this.owner.equals(owner)
        || age < 0
        || age > 30_000L) {
      clear();
      return null;
    }
    return nodes.remove(node);
  }

  public synchronized void clear() {
    for (T node : nodes.values()) close.accept(node);
    nodes.clear();
    target = null;
    treeId = "";
  }
}
