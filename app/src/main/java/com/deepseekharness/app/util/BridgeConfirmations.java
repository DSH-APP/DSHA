package com.deepseekharness.app.util;

import com.deepseekharness.app.capabilities.CapabilityBroker;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 每次确认独立绑定桥代次与随机身份；所有确认渠道只允许一次决议。 */
public final class BridgeConfirmations {
  public static final class Request {
    public final long generation;
    public final String identity;
    public final CapabilityRequest context;
    private final CapabilityBroker broker;
    private final CapabilityBroker.Confirmation confirmation;
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean allowed;
    private volatile Boolean answer;

    private Request(long generation) {
      this.generation = generation;
      identity = "dsha-confirm://request/" + generation + "/" + UUID.randomUUID();
      context = null;
      broker = null;
      confirmation = null;
    }

    private Request(
        long generation,
        CapabilityBroker broker,
        CapabilityRequest context,
        CapabilityBroker.Confirmation confirmation) {
      this.generation = generation;
      this.context = context;
      this.broker = broker;
      this.confirmation = confirmation;
      // requestId is diagnostic and can be caller supplied/reused. UI authority needs its own
      // nonce.
      identity = "dsha-confirm://request/" + generation + "/" + UUID.randomUUID();
    }

    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
      if (confirmation != null) return confirmation.await(timeout, unit);
      return done.await(timeout, unit) && allowed;
    }

    /**
     * 用户的选择：{@code TRUE} 允许、{@code FALSE} 拒绝、{@code null} 没有人回答（超时、窗口关闭、
     * 代次失效、桥停止）。{@code await} 返回 false 只说明"期限内没等到"，调用方要区分结果必须读这里 ——
     * 否则用户迟到的"允许"会被报成"拒绝"（issue #123）。
     */
    public Boolean answer() {
      if (confirmation != null) return confirmation.answer();
      return answer;
    }
  }

  private Request current;

  public synchronized Request begin(long generation) {
    if (generation <= 0 || current != null) return null;
    return current = new Request(generation);
  }

  /** Explicit production context; no thread-local authority is captured by an asynchronous callback. */
  public synchronized Request begin(
      CapabilityBroker broker, CapabilityRequest context, long bridgeGeneration) {
    if (bridgeGeneration <= 0 || current != null || broker == null || context == null) return null;
    var confirmation = broker.beginConfirmation(context);
    if (confirmation == null) return null;
    return current = new Request(bridgeGeneration, broker, context, confirmation);
  }

  public synchronized boolean pending(Request request) {
    return request != null
        && current == request
        && (request.confirmation != null
            ? request.broker.pending(request.confirmation)
            : request.done.getCount() != 0);
  }

  public synchronized boolean resolve(long generation, String identity, boolean allow) {
    if (!pending(current) || current.generation != generation || !current.identity.equals(identity))
      return false;
    if (current.confirmation != null) return current.broker.resolve(current.confirmation, allow);
    current.allowed = allow;
    current.answer = allow;
    current.done.countDown();
    return true;
  }

  public synchronized void finish(Request request) {
    if (current != request || request == null) return;
    if (request.confirmation != null) {
      request.broker.finish(request.confirmation);
      current = null;
      return;
    }
    if (pending(request)) {
      request.allowed = false;
      request.done.countDown();
    }
    current = null;
  }
}
