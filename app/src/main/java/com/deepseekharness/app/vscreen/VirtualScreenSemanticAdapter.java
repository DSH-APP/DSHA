package com.deepseekharness.app.vscreen;

import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.capabilities.CapabilityBroker;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** 语义节点的唯一所有者；显式上下文跨异步传递，不将无障碍连接当许可。 */
final class VirtualScreenSemanticAdapter {
  final VirtualScreenAccessibility session = new VirtualScreenAccessibility();

  JSONObject nativeControl(
      VirtualScreenAccessibility nativeSession,
      com.deepseekharness.app.screen.NativeScreenControlLease lease,
      int display,
      String operation,
      JSONObject args) {
    if (lease == null)
      return VirtualScreenTransport.failure("EXPLICIT_NATIVE_USER_REQUIRED", false);
    return nativeSession.runNative(lease, display, operation, args);
  }

  JSONObject control(
      CapabilityBroker broker,
      CapabilityRequest request,
      BooleanSupplier current,
      int display,
      String operation,
      JSONObject args) {
    if (request == null || broker == null)
      return VirtualScreenTransport.failure("EXPLICIT_SCREEN_REQUEST_REQUIRED", false);
    return DshaAccessibilityService.virtualControl(
        session, broker, request, current, display, operation, args);
  }

  JSONObject observed(
      CapabilityBroker broker,
      CapabilityRequest request,
      BooleanSupplier current,
      int display,
      String operation,
      JSONObject args,
      VirtualScreenAccessibility.ObservedPixels pixels) {
    if (request == null || broker == null)
      return VirtualScreenTransport.failure("EXPLICIT_SCREEN_REQUEST_REQUIRED", false);
    return DshaAccessibilityService.virtualObservedControl(
        session, broker, request, current, display, operation, args, pixels);
  }

  JSONObject type(
      CapabilityBroker broker,
      CapabilityRequest request,
      BooleanSupplier current,
      int display,
      String text) {
    if (request == null) return DshaAccessibilityService.typeVirtualUser(display, text, current);
    if (broker == null)
      return VirtualScreenTransport.failure("EXPLICIT_SCREEN_REQUEST_REQUIRED", false);
    return DshaAccessibilityService.typeVirtual(broker, request, current, display, text);
  }

  void invalidateObserved() {
    session.invalidateObserved();
  }

  void clearObserved() {
    session.clearObserved();
  }

  void clear() {
    session.clear();
  }
}
