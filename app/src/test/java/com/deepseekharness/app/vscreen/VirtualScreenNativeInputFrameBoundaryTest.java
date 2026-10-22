package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.app.Application;
import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.screen.NativeScreenControlLease;
import com.deepseekharness.app.screen.NativeScreenUserActions;
import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import com.deepseekharness.app.util.VirtualScreenInputResult;
import com.deepseekharness.app.util.VirtualScreenPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Independent frame/authority boundaries through the real authenticated Core HTTP route. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = VirtualScreenNativeControlsTest.FreshWindows.class)
public final class VirtualScreenNativeInputFrameBoundaryTest {
  private static final String CORE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  private static final String STROKE = "12345678-abcd-abcd-abcd-123456789abc";
  private static final String PACKAGE = "com.example.calculator";
  private static final int DISPLAY = 51;

  private static final class CoreFixture implements AutoCloseable {
    final AtomicLong clock = new AtomicLong(100);
    final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
    final VirtualScreenTransport transport = new VirtualScreenTransport();
    final VirtualScreenCore.HostControl control;
    final VirtualScreenHandshake endpoint;
    volatile VirtualScreenInputResult completion = VirtualScreenInputResult.ACCEPTED;
    volatile VirtualScreenDisplaySession display;
    final String generation;

    CoreFixture() throws Exception {
      control =
          VirtualScreenCore.bindControlForHost(
              VirtualScreenCoreIdentity.parse(
                  "123:456:2000:11111111-2222-3333-4444-555555555555:" + CORE),
              (width, height) -> {
                display =
                    new VirtualScreenDisplaySession(
                        width,
                        height,
                        DISPLAY,
                        VirtualScreenInput.commandForTest(
                            DISPLAY,
                            (argv, timeout) -> {
                              commands.add(List.of(argv.clone()));
                              return completion;
                            }));
                return display;
              },
              () -> PACKAGE,
              clock::get,
              () -> {});
      endpoint = control.handshake();
      JSONObject created = transport.request(endpoint, "/vscreen/create", "width=1008&height=1792");
      assertTrue(created.toString(), created.optBoolean("ok"));
      generation = created.getString("generation");
      observed(237);
    }

    void observed(long sequence) {
      synchronized (display.lock) {
        display.sequence = sequence;
        display.observed = sequence;
        display.observedAt = clock.get();
      }
    }

    void captured(long sequence) {
      synchronized (display.lock) {
        display.sequence = sequence;
      }
    }

    String query(String gen, long sequence, String stroke, int action) {
      return "generation="
          + gen
          + "&frameSeq="
          + sequence
          + "&stroke="
          + stroke
          + "&action="
          + action
          + "&x=125&y=150";
    }

    JSONObject touch(int action) {
      return transport.request(endpoint, "/vscreen/touch", query(generation, 237, STROKE, action));
    }

    @Override
    public void close() {
      control.close();
    }
  }

  @Test
  public void capture237To238BeforeDownRejectsBeforeAnyInputAndUpCannotReplay() throws Exception {
    try (var fixture = new CoreFixture()) {
      fixture.captured(238);
      JSONObject down = fixture.touch(0);
      assertEquals("STALE_FRAME", down.getString("error"));
      assertEquals("FRAME_EXPIRED", down.getString("reason"));
      assertEquals(237, down.getLong("requestedFrameSeq"));
      assertEquals(238, down.getLong("currentFrameSeq"));
      assertEquals(237, down.getLong("observedFrameSeq"));
      assertFalse(fixture.display.input.active());
      assertTrue(fixture.commands.isEmpty());
      assertFalse(fixture.touch(1).optBoolean("ok"));
      fixture.touch(3);
      assertTrue("Rejected DOWN/UP/CANCEL never become a tap", fixture.commands.isEmpty());
    }
  }

  @Test
  public void acceptedDownThenCapture238DoesNotRevalidateUpAndSubmitsOnlyOnce() throws Exception {
    try (var fixture = new CoreFixture()) {
      assertTrue(fixture.touch(0).optBoolean("ok"));
      assertEquals(-1, fixture.display.observed);
      assertTrue(fixture.display.input.active());
      fixture.captured(238);
      assertTrue("UP owns its original admitted stroke", fixture.touch(1).optBoolean("ok"));
      assertEquals(1, fixture.commands.size());
      assertEquals(
          List.of("/system/bin/input", "-d", "51", "tap"), fixture.commands.get(0).subList(0, 4));
      assertEquals(125, Float.parseFloat(fixture.commands.get(0).get(4)), 0);
      assertEquals(150, Float.parseFloat(fixture.commands.get(0).get(5)), 0);
      assertFalse(fixture.display.input.active());
      assertFalse(fixture.touch(1).optBoolean("ok"));
      fixture.touch(3);
      assertEquals("Duplicate completion/cleanup never replay", 1, fixture.commands.size());
    }
  }

  @Test
  public void proofGenerationObservationAndTtlStillFenceDown() throws Exception {
    try (var fixture = new CoreFixture()) {
      var wrongToken =
          new VirtualScreenHandshake(
              fixture.endpoint.port, "b".repeat(48), fixture.endpoint.identity);
      assertFalse(
          fixture
              .transport
              .request(
                  wrongToken, "/vscreen/touch", fixture.query(fixture.generation, 237, STROKE, 0))
              .optBoolean("ok"));
      var wrongBirth =
          new VirtualScreenHandshake(
              fixture.endpoint.port,
              fixture.endpoint.token,
              VirtualScreenCoreIdentity.parse(
                  "123:456:2000:11111111-2222-3333-4444-555555555555:"
                      + "bbbbbbbb-cccc-dddd-eeee-ffffffffffff"));
      assertFalse(
          fixture
              .transport
              .request(
                  wrongBirth, "/vscreen/touch", fixture.query(fixture.generation, 237, STROKE, 0))
              .optBoolean("ok"));
      JSONObject oldGeneration =
          fixture.transport.request(
              fixture.endpoint,
              "/vscreen/touch",
              fixture.query("old-display-generation", 237, STROKE, 0));
      assertEquals("STALE_GENERATION", oldGeneration.getString("error"));
      JSONObject badStroke =
          fixture.transport.request(
              fixture.endpoint, "/vscreen/touch", fixture.query(fixture.generation, 237, "bad", 0));
      assertEquals("INVALID_STROKE", badStroke.getString("error"));
      fixture.display.observed = 236;
      assertEquals("FRAME_NOT_OBSERVED", fixture.touch(0).getString("reason"));
      fixture.observed(237);
      fixture.clock.addAndGet(VirtualScreenPolicy.OBSERVATION_WINDOW_MS + 1);
      assertEquals("OBSERVATION_TIMEOUT", fixture.touch(0).getString("reason"));
      assertTrue(fixture.commands.isEmpty());
      assertFalse(fixture.display.input.active());
    }
  }

  @Test
  public void unknownCompletionConsumesStrokeWithoutSecondInputOrFallback() throws Exception {
    try (var fixture = new CoreFixture()) {
      fixture.completion = VirtualScreenInputResult.UNKNOWN;
      assertTrue(fixture.touch(0).optBoolean("ok"));
      JSONObject result = fixture.touch(1);
      assertEquals("INPUT_RESULT_UNKNOWN", result.getString("error"));
      assertTrue(result.getBoolean("executionUnknown"));
      assertEquals(1, fixture.commands.size());
      assertFalse(fixture.touch(1).optBoolean("ok"));
      fixture.touch(3);
      assertEquals(1, fixture.commands.size());
    }
  }

  @Test
  public void nullSemanticContextAndForgedCoreHttpCannotMintNativeAuthority() throws Exception {
    try (var fixture = new CoreFixture()) {
      JSONObject args = new JSONObject().put("nodeId", "forged").put("action", "click");
      assertEquals(
          "EXPLICIT_SCREEN_REQUEST_REQUIRED",
          new VirtualScreenSemanticAdapter()
              .control(null, null, () -> true, DISPLAY, "node", args)
              .getString("error"));
      assertEquals(
          "EXPLICIT_SCREEN_REQUEST_REQUIRED",
          DshaAccessibilityService.virtualControl(
                  new VirtualScreenAccessibility(), DISPLAY, "node", args)
              .getString("error"));
      for (String route : List.of("/vscreen/nativetree", "/vscreen/nativenode", "/vscreen/node")) {
        JSONObject forged =
            fixture.transport.request(
                fixture.endpoint,
                route,
                "generation=" + fixture.generation + "&nodeId=forged&action=click&resumed=true");
        assertEquals("UNKNOWN_ROUTE", forged.getString("error"));
      }
      assertTrue(fixture.commands.isEmpty());
    }
  }

  private static void standardNativeControls() {
    org.junit.Assume.assumeFalse(com.deepseekharness.app.BuildConfig.LOW_ANDROID);
  }

  @Test
  public void nativeNodeDoesNotAuthorizeTheSameStaleCoordinateFrame() throws Exception {
    standardNativeControls();
    try (var fixture = new VirtualScreenNativeControlsTest.Fixture()) {
      String id = fixture.id();
      synchronized (fixture.display.lock) {
        fixture.display.sequence = 238;
        fixture.display.observed = 237;
        fixture.display.observedAt = android.os.SystemClock.elapsedRealtime();
      }
      assertTrue(fixture.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(1, fixture.actions.get());
      JSONObject stale =
          fixture.manager.action("tap", fixture.display.generation, 237, "x=125&y=150");
      assertEquals("FRAME_EXPIRED", stale.getString("reason"));
      assertEquals(237, stale.getLong("requestedFrameSeq"));
      assertEquals(238, stale.getLong("currentFrameSeq"));
      assertEquals(0, fixture.injected.get());
      assertEquals(1, fixture.actions.get());
    }
  }

  @Test
  public void publicBooleanAuthorityFactoryIsAbsentAndPausedSubjectCannotMintAValidLease()
      throws Exception {
    standardNativeControls();
    for (Class<?> type : List.of(DshaAccessibilityService.class, NativeScreenUserActions.class))
      for (var method : type.getDeclaredMethods())
        if (method.getName().equals("nativeVirtualControlLease")
            || method.getName().equals("virtualControlLease"))
          assertFalse(
              "A caller-supplied true predicate must not mint native authority",
              List.of(method.getParameterTypes())
                  .contains(java.util.function.BooleanSupplier.class));
    try (var fixture = new VirtualScreenNativeControlsTest.Fixture()) {
      fixture.activity.pause();
      try {
        var method =
            DshaAccessibilityService.class.getDeclaredMethod(
                "nativeVirtualControlLease", VirtualScreenNativeControls.class);
        try (var lease = (NativeScreenControlLease) method.invoke(null, fixture.controls)) {
          assertFalse(lease.current());
          assertFalse(
              new VirtualScreenAccessibility()
                  .runNative(lease, DISPLAY, "tree", new JSONObject())
                  .optBoolean("ok"));
          assertEquals(0, fixture.actions.get());
          assertEquals(0, fixture.injected.get());
        }
      } finally {
        fixture.activity.resume();
      }
      assertFalse("Resume cannot resurrect the previous owner", fixture.controls.current());
    }
  }

  @Test
  public void ownerPauseDuringFreshRootTraversalRefusesBeforeClaimWithoutInput() throws Exception {
    standardNativeControls();
    try (var fixture = new VirtualScreenNativeControlsTest.Fixture()) {
      String id = fixture.id();
      VirtualScreenNativeControlsTest.FreshWindows.nextRead = () -> fixture.activity.pause();
      try {
        JSONObject result = fixture.controls.node(id, "click", "");
        assertFalse(result.toString(), result.optBoolean("ok"));
        assertFalse("No platform call was admitted", result.optBoolean("executionUnknown"));
        assertEquals(0, fixture.actions.get());
        assertEquals(0, fixture.injected.get());
      } finally {
        fixture.activity.resume();
      }
      assertFalse(fixture.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(0, fixture.actions.get());
    }
  }

  @Test
  public void accessibilityRevokedInsidePlatformActionIsUnknownAndCannotReplay() throws Exception {
    standardNativeControls();
    try (var fixture = new VirtualScreenNativeControlsTest.Fixture()) {
      var connection = VirtualScreenNativeControlsTest.connection();
      VirtualScreenNativeControlsTest.nodeShadow(fixture.button)
          .setOnPerformActionListener(
              (action, args) -> {
                assertEquals(AccessibilityNodeInfo.ACTION_CLICK, action);
                fixture.actions.incrementAndGet();
                connection.revoke();
                return true;
              });
      String id = fixture.id();
      JSONObject result = fixture.controls.node(id, "click", "");
      assertTrue(result.toString(), result.optBoolean("executionUnknown"));
      assertEquals(1, fixture.actions.get());
      assertFalse(fixture.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(1, fixture.actions.get());
      assertEquals(0, fixture.injected.get());
    }
  }

  @Test
  public void nativeFactoryCannotBeEnteredFromAWorkerOrReuseAChangedManagerRevision()
      throws Exception {
    standardNativeControls();
    try (var fixture = new VirtualScreenNativeControlsTest.Fixture()) {
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread attempted =
          new Thread(
              () -> {
                try {
                  fixture.manager.openNativeControls(fixture.activity.get()).close();
                } catch (Throwable rejected) {
                  failure.set(rejected);
                }
              },
              "independent-native-factory-probe");
      attempted.start();
      attempted.join(2000);
      assertFalse("Factory cannot leave an unbounded worker", attempted.isAlive());
      assertTrue(failure.get() instanceof IllegalStateException);
      String id = fixture.id();
      var field = VirtualScreenManager.class.getDeclaredField("stateRevision");
      field.setAccessible(true);
      field.setLong(fixture.manager, field.getLong(fixture.manager) + 1);
      assertFalse(fixture.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(0, fixture.actions.get());
      assertEquals(0, fixture.injected.get());
    }
  }

  @Test
  public void nativeIdentityWindowDisplayAndTreeTtlChangesNeverDispatchOrFallback()
      throws Exception {
    standardNativeControls();
    for (String changed :
        List.of("coreUuid", "generation", "window", "package", "display", "treeTtl")) {
      try (var fixture = new VirtualScreenNativeControlsTest.Fixture()) {
        String id = fixture.id();
        switch (changed) {
          case "coreUuid":
            var old = fixture.core.handshake();
            VirtualScreenNativeControlsTest.set(
                fixture.manager,
                "core",
                new VirtualScreenHandshake(
                    old.port,
                    old.token,
                    VirtualScreenCoreIdentity.parse(
                        "123:456:2000:11111111-2222-3333-4444-555555555555:"
                            + "bbbbbbbb-cccc-dddd-eeee-ffffffffffff")));
            break;
          case "generation":
            VirtualScreenNativeControlsTest.set(
                fixture.manager, "generation", java.util.UUID.randomUUID().toString());
            break;
          case "window":
            VirtualScreenNativeControlsTest.windowShadow(fixture.window).setId(21);
            break;
          case "package":
            fixture.root.setPackageName("com.example.other");
            break;
          case "display":
            VirtualScreenNativeControlsTest.FreshWindows.originals.remove(DISPLAY);
            VirtualScreenNativeControlsTest.FreshWindows.originals.put(
                DISPLAY + 1, List.of(fixture.window));
            break;
          case "treeTtl":
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(31));
            break;
          default:
            throw new AssertionError(changed);
        }
        JSONObject result = fixture.controls.node(id, "click", "");
        assertFalse(changed + ": " + result, result.optBoolean("ok"));
        assertFalse(
            changed + " was rejected before platform admission",
            result.optBoolean("executionUnknown"));
        assertEquals(changed, 0, fixture.actions.get());
        assertEquals(changed, 0, fixture.injected.get());
        assertFalse(changed, fixture.controls.node(id, "click", "").optBoolean("ok"));
        assertEquals(changed, 0, fixture.actions.get());
      }
    }
  }

  @Test
  public void opaqueOwnerAndLimitedNativeNodeArgumentsCannotBecomeAnotherAction() throws Exception {
    standardNativeControls();
    assertTrue(
        java.lang.reflect.Modifier.isFinal(VirtualScreenNativeControls.class.getModifiers()));
    for (var constructor : VirtualScreenNativeControls.class.getDeclaredConstructors())
      assertFalse(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()));
    for (var method : VirtualScreenNativeControls.class.getDeclaredMethods())
      if (method.getName().equals("bind"))
        assertFalse(java.lang.reflect.Modifier.isPublic(method.getModifiers()));
    try (var fixture = new VirtualScreenNativeControlsTest.Fixture()) {
      String id = fixture.id();
      for (String action : List.of("long_click", "scroll_forward", "editor", "submit", "tap"))
        assertEquals(
            "INVALID_NATIVE_NODE_ACTION", fixture.controls.node(id, action, "").optString("error"));
      assertEquals(
          "INVALID_NATIVE_NODE_ACTION",
          fixture.controls.node("forged", "click", "").optString("error"));
      assertEquals(
          "INVALID_NATIVE_NODE_ACTION",
          fixture.controls.node(id, "set_text", "x".repeat(16001)).optString("error"));
      assertEquals(0, fixture.actions.get());
      assertEquals(0, fixture.injected.get());
      assertTrue(fixture.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(1, fixture.actions.get());
      assertFalse(fixture.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(1, fixture.actions.get());
    }
  }
}
