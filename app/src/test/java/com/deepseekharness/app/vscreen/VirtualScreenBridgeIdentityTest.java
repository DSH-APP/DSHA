package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import com.deepseekharness.app.capabilities.*;
import com.deepseekharness.app.core.RuntimeSnapshot;
import com.deepseekharness.app.util.*;
import java.util.concurrent.ScheduledExecutorService;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Actual bridge admission must not upgrade an approved core A observation to core B. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenBridgeIdentityTest {
  private static final String A = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  private static final String B = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee";

  private void set(VirtualScreenManager manager, String name, Object value) throws Exception {
    var field = VirtualScreenManager.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(manager, value);
  }

  private VirtualScreenManager manager() throws Exception {
    var owner =
        new VirtualScreenManager(
            null,
            () -> 100,
            new VirtualScreenManager.Visuals() {
              public void present() {}

              public void stopped(long fence) {}
            });
    var endpoint =
        new VirtualScreenHandshake(
            12345,
            "a".repeat(48),
            VirtualScreenCoreIdentity.parse(
                "123:456:2000:11111111-2222-3333-4444-555555555555:" + B));
    set(owner, "core", endpoint);
    set(owner, "token", endpoint.token);
    set(owner, "port", endpoint.port);
    set(owner, "generation", "display-B");
    set(owner, "activeEpoch", 0L);
    set(owner, "lifecycleState", VirtualScreenLifecycleState.READY);
    return owner;
  }

  private void shutdown(VirtualScreenManager owner) throws Exception {
    var field = VirtualScreenManager.class.getDeclaredField("WORKER");
    field.setAccessible(true);
    ((ScheduledExecutorService) field.get(owner)).shutdownNow();
  }

  private CapabilityRequest request(CapabilityBroker broker, TargetEvidence target) {
    String installation = "11111111-2222-3333-4444-555555555555";
    var request =
        CapabilityRequest.create(
            "screen",
            "vscreen:tree",
            new RuntimeSnapshot.Epoch(installation, 1, "runtime-one"),
            installation,
            "bridge-one",
            target,
            0,
            broker.epoch("screen"),
            1000);
    assertTrue(broker.authorize(request));
    return request;
  }

  private VscreenTargetEvidence evidence(String core, String generation) {
    return new VscreenTargetEvidence(
        core,
        generation,
        10,
        20,
        "com.example.app",
        VscreenTargetEvidence.Observation.DISPLAY,
        -1,
        "");
  }

  @Test
  public void approvalForCoreACannotReachCoreBWithSameWindowAndPackage() throws Exception {
    var manager = manager();
    var broker = new CapabilityBroker(() -> 100);
    try {
      var approvedA = request(broker, evidence(A, "display-A"));
      var result =
          new JSONObject(
              manager.bridge(
                  broker,
                  approvedA,
                  VscreenBridgeRequest.query("/app/vscreen/tree", "coreInstance=" + A),
                  () -> true));
      assertEquals("VSCREEN_CORE_IDENTITY_CHANGED", result.getString("error"));
      assertEquals("NOT_SENT", result.getString("state"));
      assertEquals(VirtualScreenLifecycleState.READY, manager.lifecycleState());
      assertEquals(0, manager.epoch());
    } finally {
      shutdown(manager);
    }
  }

  @Test
  public void missingCoreOrGenericWindowEvidenceCannotBorrowCurrentCore() throws Exception {
    var manager = manager();
    var broker = new CapabilityBroker(() -> 100);
    try {
      var approved = request(broker, evidence(B, "display-B"));
      assertEquals(
          "VSCREEN_CORE_IDENTITY_REQUIRED",
          new JSONObject(
                  manager.bridge(
                      broker,
                      approved,
                      VscreenBridgeRequest.query("/app/vscreen/tree", ""),
                      () -> true))
              .getString("error"));
      WindowTargetEvidence generic =
          new WindowTargetEvidence() {
            public String kind() {
              return "window";
            }

            public int displayId() {
              return 10;
            }

            public int windowId() {
              return 20;
            }

            public String packageName() {
              return "com.example.app";
            }
          };
      assertEquals(
          "CORE_EVIDENCE_REQUIRED",
          new JSONObject(
                  manager.bridge(
                      broker,
                      request(broker, generic),
                      VscreenBridgeRequest.query("/app/vscreen/tree", "coreInstance=" + B),
                      () -> true))
              .getString("error"));
    } finally {
      shutdown(manager);
    }
  }

  @Test
  public void staleCloseAndLateObservationNeverRelabelOrStopNewCore() throws Exception {
    var manager = manager();
    try {
      assertEquals(
          "VSCREEN_CORE_IDENTITY_CHANGED",
          new JSONObject(
                  manager.bridge(
                      VscreenBridgeRequest.query("/app/vscreen/close", "coreInstance=" + A)))
              .getString("error"));
      assertEquals(
          "VSCREEN_CORE_IDENTITY_REQUIRED",
          new JSONObject(manager.bridge(VscreenBridgeRequest.query("/app/vscreen/close", "")))
              .getString("error"));
      assertEquals(0, manager.epoch());
      var decorate = VirtualScreenManager.class.getDeclaredMethod("decorate", JSONObject.class);
      decorate.setAccessible(true);
      var old =
          new JSONObject().put("ok", true).put("coreInstance", A).put("generation", "display-A");
      assertEquals(A, ((JSONObject) decorate.invoke(manager, old)).getString("coreInstance"));
    } finally {
      shutdown(manager);
    }
  }

  @Test
  public void statusAndCloseCannotReplaceTheirApprovedEvidenceWithAnotherCore() throws Exception {
    var manager = manager();
    var broker = new CapabilityBroker(() -> 100);
    try {
      for (String operation : new String[] {"status", "close"}) {
        var approvedA = request(broker, evidence(A, "display-A"));
        var result =
            new JSONObject(
                manager.bridge(
                    broker,
                    approvedA,
                    VscreenBridgeRequest.query("/app/vscreen/" + operation, "coreInstance=" + B),
                    () -> true));
        assertEquals("VSCREEN_CORE_IDENTITY_CHANGED", result.getString("error"));
        assertEquals("NOT_SENT", result.getString("state"));
      }
      assertEquals(0, manager.epoch());
    } finally {
      shutdown(manager);
    }
  }
}
