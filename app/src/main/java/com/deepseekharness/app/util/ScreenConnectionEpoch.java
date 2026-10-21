package com.deepseekharness.app.util;

import java.util.function.Supplier;

/** 服务连接与请求围栏。旧服务退出不能清空已连接的新服务。 */
public final class ScreenConnectionEpoch<T> {
  private T service;
  private long epoch;

  public static final class Lease<T> {
    public final T service;
    public final long epoch;

    private Lease(T service, long epoch) {
      this.service = service;
      this.epoch = epoch;
    }
  }

  public synchronized void connect(T value) {
    if (value == null) throw new IllegalArgumentException("SCREEN_SERVICE_MISSING");
    service = value;
    epoch++;
  }

  public synchronized boolean disconnect(T value) {
    if (service != value) return false;
    service = null;
    epoch++;
    return true;
  }

  public synchronized void revoke() {
    epoch++;
  }

  public synchronized Lease<T> snapshot() {
    return service == null ? null : new Lease<>(service, epoch);
  }

  public synchronized boolean current(Lease<T> lease) {
    return lease != null && service == lease.service && epoch == lease.epoch;
  }

  /** 这里只领取公共票据；不得在连接锁中等待系统调用或调用外部授权回调。 */
  public synchronized <R> R claim(Lease<T> lease, Supplier<R> claim) {
    return current(lease) ? claim.get() : null;
  }
}
