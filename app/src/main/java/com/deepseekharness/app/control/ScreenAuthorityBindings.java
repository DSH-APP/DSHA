package com.deepseekharness.app.control;

import com.deepseekharness.app.capabilities.CapabilityRequest;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/** Full task/turn identity carried with each asynchronous adapter request, never ThreadLocal. */
public final class ScreenAuthorityBindings {
  private record Binding(String identity, BooleanSupplier current) {}

  private static final Map<CapabilityRequest, Binding> requests =
      Collections.synchronizedMap(new WeakHashMap<>());

  private ScreenAuthorityBindings() {}

  public static void bind(CapabilityRequest request, String identity, BooleanSupplier current) {
    if (request == null
        || identity == null
        || identity.isEmpty()
        || current == null
        || !current.getAsBoolean()) throw new IllegalStateException("SCREEN_TASK_CHANGED");
    requests.put(request, new Binding(identity, current));
  }

  public static boolean current(CapabilityRequest request) {
    var value = requests.get(request);
    return value == null || value.current.getAsBoolean();
  }

  public static String identity(CapabilityRequest request) {
    var value = requests.get(request);
    return value == null ? "" : value.identity;
  }
}
