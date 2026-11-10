package com.deepseekharness.app.control;

import static org.junit.Assert.*;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.capabilities.ScreenTargetEvidence;
import com.deepseekharness.app.core.RuntimeSnapshot;
import org.junit.Test;

public final class ScreenAuthorityBindingsTest {
  private static CapabilityRequest request() {
    return CapabilityRequest.create(
        "screen",
        "tree",
        new RuntimeSnapshot.Epoch("installation", 1, "local-web-instance"),
        "installation",
        "local-web-instance",
        new ScreenTargetEvidence(0, 3, "example.target"),
        0,
        0,
        1000);
  }

  @Test
  public void fullTaskTurnAndRevisionTravelWithRequestBeyondThreadLifetime() throws Exception {
    var authority = new ControlTaskAuthority();
    var lease =
        authority.begin(
            new ControlTaskAuthority.Binding(
                ControlTaskAuthority.Owner.LOCAL_DSH, "bridge-generation-1", "session-full", "7"));
    var request = request();
    ScreenAuthorityBindings.bind(
        request,
        lease.binding().identity() + "\0" + lease.revision(),
        () -> authority.current(lease));
    var result = new java.util.concurrent.atomic.AtomicBoolean();
    Thread worker = new Thread(() -> result.set(ScreenAuthorityBindings.current(request)));
    worker.start();
    worker.join(1000);
    assertTrue(result.get());
    assertTrue(ScreenAuthorityBindings.identity(request).contains("session-full" + '\0' + "7"));
    authority.pause("LOCAL_USER_PAUSE");
    assertFalse(ScreenAuthorityBindings.current(request));
    authority.resume();
    assertFalse(ScreenAuthorityBindings.current(request));
  }

  @Test
  public void staleAuthorityCannotBeAttachedToNewRequest() {
    var authority = new ControlTaskAuthority();
    var lease =
        authority.begin(
            new ControlTaskAuthority.Binding(
                ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "session", "1"));
    authority.stop("DONE");
    assertThrows(
        IllegalStateException.class,
        () ->
            ScreenAuthorityBindings.bind(
                request(), lease.binding().identity(), () -> authority.current(lease)));
  }
}
