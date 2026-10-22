package com.deepseekharness.app.vscreen;

import com.deepseekharness.app.capabilities.CapabilityBroker;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.capabilities.WindowTargetEvidence;
import java.util.function.BooleanSupplier;

/** 请求上下文显式随编排调用传递；本机直接操作不借用助手许可。 */
final class VirtualScreenRequestScope {
  final CapabilityBroker broker;
  final CapabilityRequest request;
  final BooleanSupplier authority;
  final String target;
  private CapabilityBroker.DispatchPermit permit;

  VirtualScreenRequestScope(
      CapabilityBroker broker,
      CapabilityRequest request,
      BooleanSupplier authority,
      String target) {
    this.broker = broker;
    this.request = request;
    this.authority = authority;
    this.target =
        request != null && request.target instanceof WindowTargetEvidence evidence
            ? evidence.packageName()
            : target;
  }

  boolean current() {
    try {
      return (authority == null || authority.getAsBoolean())
          && (request == null
              || broker != null && "screen".equals(request.capability) && broker.current(request));
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  synchronized boolean claim() {
    if (!current()) return false;
    if (request == null) return true;
    if (permit == null) permit = broker.claimDispatch(request);
    return permit != null && permit.isCurrent();
  }
}
