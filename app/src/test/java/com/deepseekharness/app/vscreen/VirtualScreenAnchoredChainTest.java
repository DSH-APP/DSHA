package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.capabilities.*;
import com.deepseekharness.app.core.RuntimeSnapshot;
import com.deepseekharness.app.util.*;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.util.ReflectionHelpers;

/** Actual grammar/Manager/authenticated Core/Dsha entry/semantic owner; synthetic platform only. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = android.app.Application.class,
    shadows = VirtualScreenAnchoredChainTest.FreshWindows.class)
public final class VirtualScreenAnchoredChainTest {
  @org.junit.Before
  public void standardVirtualScreenFeatureOnly() {
    org.junit.Assume.assumeFalse(com.deepseekharness.app.BuildConfig.LOW_ANDROID);
  }

  static final String CORE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", PKG = "com.example.calculator";
  static final int DISPLAY = 51, WINDOW = 20;

  @Implements(AccessibilityService.class)
  public static class FreshWindows extends ShadowAccessibilityService {
    static AccessibilityServiceInfo info;
    static SparseArray<List<AccessibilityWindowInfo>> originals = new SparseArray<>();
    static List<AccessibilityWindowInfo> delivered = new ArrayList<>();
    static Runnable nextRead;

    @Implementation
    protected AccessibilityServiceInfo getServiceInfo() {
      return info;
    }

    @Implementation
    protected SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
      if (nextRead != null) {
        var callback = nextRead;
        nextRead = null;
        callback.run();
      }
      var result = new SparseArray<List<AccessibilityWindowInfo>>();
      delivered = new ArrayList<>();
      for (int i = 0; i < originals.size(); i++) {
        var clones = new ArrayList<AccessibilityWindowInfo>();
        for (var window : originals.valueAt(i)) {
          var clone = AccessibilityWindowInfo.obtain(window);
          clones.add(clone);
          delivered.add(clone);
        }
        result.put(originals.keyAt(i), clones);
      }
      return result;
    }
  }

  private static ShadowAccessibilityNodeInfo nodeShadow(AccessibilityNodeInfo node) {
    return Shadow.extract(node);
  }

  private static ShadowAccessibilityWindowInfo windowShadow(AccessibilityWindowInfo window) {
    return Shadow.extract(window);
  }

  private static Object field(Object owner, String name) throws Exception {
    var f = owner.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(owner);
  }

  private static void managerField(VirtualScreenManager owner, String name, Object value)
      throws Exception {
    var f = VirtualScreenManager.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(owner, value);
  }

  @SuppressWarnings("unchecked")
  private static ScreenConnectionEpoch<DshaAccessibilityService> connection() throws Exception {
    var f = DshaAccessibilityService.class.getDeclaredField("CONNECTION");
    f.setAccessible(true);
    return (ScreenConnectionEpoch<DshaAccessibilityService>) f.get(null);
  }

  private final class Fixture implements AutoCloseable {
    final DshaAccessibilityService service = new DshaAccessibilityService();
    final CapabilityBroker broker = new CapabilityBroker(() -> 0);
    final AtomicBoolean live = new AtomicBoolean(true);
    final AtomicInteger injected = new AtomicInteger(), actions = new AtomicInteger();
    final java.util.concurrent.atomic.AtomicLong coreClock =
        new java.util.concurrent.atomic.AtomicLong(android.os.SystemClock.elapsedRealtime());
    final VirtualScreenManager manager =
        new VirtualScreenManager(
            null,
            () -> 100,
            new VirtualScreenManager.Visuals() {
              public void present() {}

              public void stopped(long fence) {}
            });
    final AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
    final AccessibilityNodeInfo root = node("layout", new Rect(0, 0, 1008, 1792), false);
    final AccessibilityNodeInfo button = node("1", new Rect(100, 100, 200, 200), true);
    final AccessibilityNodeInfo label = node("value", new Rect(300, 100, 500, 200), false);
    final VirtualScreenCore.HostControl core;
    VirtualScreenDisplaySession display;
    CapabilityRequest lastRequest;
    String generation;

    Fixture() throws Exception {
      var info = new AccessibilityServiceInfo();
      info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
      ReflectionHelpers.setField(
          info, "mCapabilities", AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
      FreshWindows.info = info;
      FreshWindows.nextRead = null;
      var w = windowShadow(window);
      w.setId(WINDOW);
      w.setDisplayId(DISPLAY);
      w.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
      w.setLayer(1);
      w.setFocused(true);
      w.setActive(true);
      w.setBoundsInScreen(new Rect(0, 0, 1008, 1792));
      w.setRoot(root);
      nodeShadow(root).addChild(button);
      nodeShadow(root).addChild(label);
      FreshWindows.originals = new SparseArray<>();
      FreshWindows.originals.put(DISPLAY, List.of(window));
      connection().connect(service);
      nodeShadow(button)
          .setOnPerformActionListener(
              (action, args) -> {
                actions.incrementAndGet();
                return true;
              });
      core =
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
                              injected.incrementAndGet();
                              return VirtualScreenInputResult.REJECTED;
                            }));
                display.frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                display.frame.eraseColor(0xff123456);
                display.sequence = 10;
                return display;
              },
              () -> PKG,
              coreClock::get,
              () -> {});
      var endpoint = core.handshake();
      JSONObject created =
          new VirtualScreenTransport()
              .request(endpoint, "/vscreen/create", "width=1008&height=1792");
      assertTrue(created.toString(), created.optBoolean("ok"));
      generation = created.getString("generation");
      managerField(manager, "core", endpoint);
      managerField(manager, "token", endpoint.token);
      managerField(manager, "port", endpoint.port);
      managerField(manager, "channel", "shizuku");
      managerField(manager, "generation", generation);
      managerField(manager, "activeEpoch", 0L);
      managerField(manager, "lifecycleState", VirtualScreenLifecycleState.READY);
    }

    AccessibilityNodeInfo node(String text, Rect bounds, boolean click) {
      var node = AccessibilityNodeInfo.obtain(new View(RuntimeEnvironment.getApplication()));
      node.setPackageName(PKG);
      node.setClassName(click ? "android.widget.Button" : "android.view.View");
      node.setText(text);
      node.setBoundsInScreen(bounds);
      node.setVisibleToUser(true);
      node.setEnabled(true);
      node.setClickable(click);
      if (click) node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
      nodeShadow(node).setAccessibilityWindowInfo(window);
      return node;
    }

    CapabilityRequest permit(
        VscreenTargetEvidence.Observation kind, long frame, String id, long consent) {
      var evidence =
          new VscreenTargetEvidence(
              CORE, generation, DISPLAY, WINDOW, PKG, kind, frame, id, consent);
      String installation = "11111111-2222-3333-4444-555555555555";
      var request =
          CapabilityRequest.create(
              "screen",
              "controlled observed action",
              new RuntimeSnapshot.Epoch(installation, 1, "runtime-one"),
              installation,
              "bridge-one",
              evidence,
              0,
              broker.epoch("screen"),
              1_000_000);
      assertTrue(broker.authorize(request));
      lastRequest = request;
      return request;
    }

    JSONObject call(
        String route,
        Map<String, Object> body,
        VscreenTargetEvidence.Observation kind,
        long consent)
        throws Exception {
      long frame = ((Number) body.getOrDefault("frameSeq", -1L)).longValue();
      String id =
          (String)
              body.getOrDefault(
                  route.endsWith("anchored-click")
                      ? "anchorId"
                      : route.endsWith("node") ? "nodeId" : "observationId",
                  "");
      var request = VscreenBridgeRequest.json(route, body);
      return new JSONObject(
          manager.bridge(broker, permit(kind, frame, id, consent), request, live::get));
    }

    Map<String, Object> body() {
      var b = new LinkedHashMap<String, Object>();
      b.put("coreInstance", CORE);
      b.put("generation", generation);
      b.put("frameSeq", 10L);
      return b;
    }

    JSONObject see() throws Exception {
      return call(
          "/app/vscreen/see",
          Map.of("coreInstance", CORE),
          VscreenTargetEvidence.Observation.DISPLAY,
          0);
    }

    JSONObject anchor(JSONObject seen) throws Exception {
      var b = body();
      b.put("observationId", seen.getString("observationId"));
      b.put("x", 150);
      b.put("y", 150);
      return call("/app/vscreen/anchor", b, VscreenTargetEvidence.Observation.OBSERVED_ANCHOR, 0);
    }

    JSONObject click(JSONObject anchor, long consent) throws Exception {
      var b = body();
      b.put("anchorId", anchor.getString("anchorId"));
      return call(
          "/app/vscreen/anchored-click",
          b,
          VscreenTargetEvidence.Observation.ANCHORED_NODE,
          consent);
    }

    void drain() throws Exception {
      for (int i = 0; i < 3; i++)
        ((ScheduledExecutorService) field(manager, "WORKER"))
            .submit(() -> {})
            .get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void close() throws Exception {
      connection().disconnect(service);
      ((ScheduledExecutorService) field(manager, "WORKER")).shutdownNow();
      core.close();
      window.recycle();
      root.recycle();
      button.recycle();
      label.recycle();
      FreshWindows.originals.clear();
    }
  }

  @Test
  public void treeRecognizesTheVirtualButtonAndFreshNodeClickDoesNotUseAPixelFrame()
      throws Exception {
    try (Fixture f = new Fixture()) {
      JSONObject tree =
          f.call(
              "/app/vscreen/tree",
              Map.of("coreInstance", CORE),
              VscreenTargetEvidence.Observation.DISPLAY,
              0);
      assertTrue(tree.toString(), tree.optBoolean("ok"));
      String id = "";
      for (int i = 0; i < tree.getJSONArray("nodes").length(); i++) {
        var node = tree.getJSONArray("nodes").getJSONObject(i);
        if (node.optString("text").equals("1")) id = node.getString("nodeId");
      }
      assertFalse("The virtual app's button must be recognizable", id.isEmpty());
      f.display.sequence = 11;
      var body = new LinkedHashMap<String, Object>();
      body.put("coreInstance", CORE);
      body.put("generation", f.generation);
      body.put("nodeId", id);
      body.put("action", "click");
      JSONObject clicked =
          f.call("/app/vscreen/node", body, VscreenTargetEvidence.Observation.NODE, 0);
      assertTrue(clicked.toString(), clicked.optBoolean("ok"));
      assertEquals(1, f.actions.get());
      assertEquals(0, f.injected.get());
      assertEquals(
          "STALE_NODE",
          f.call("/app/vscreen/node", body, VscreenTargetEvidence.Observation.NODE, 0)
              .getString("error"));
      assertEquals(1, f.actions.get());
    }
  }

  @Test
  public void treeNodeWritesExactUnicodeTextOnlyOnceToTheCurrentVirtualEditor() throws Exception {
    try (Fixture f = new Fixture()) {
      f.button.setEditable(true);
      f.button.setClassName("android.widget.EditText");
      f.button.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
      String text = "虚拟屏输入 %s & = 中文";
      var written = new java.util.concurrent.atomic.AtomicReference<String>();
      nodeShadow(f.button)
          .setOnPerformActionListener(
              (action, args) -> {
                assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, action);
                written.set(
                    args.getCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                        .toString());
                f.actions.incrementAndGet();
                return true;
              });
      JSONObject tree =
          f.call(
              "/app/vscreen/tree",
              Map.of("coreInstance", CORE),
              VscreenTargetEvidence.Observation.DISPLAY,
              0);
      String id = "";
      for (int i = 0; i < tree.getJSONArray("nodes").length(); i++) {
        var node = tree.getJSONArray("nodes").getJSONObject(i);
        if (node.optBoolean("editable")) id = node.getString("nodeId");
      }
      assertFalse(id.isEmpty());
      var body = new LinkedHashMap<String, Object>();
      body.put("coreInstance", CORE);
      body.put("generation", f.generation);
      body.put("nodeId", id);
      body.put("action", "set_text");
      body.put("text", text);
      JSONObject typed =
          f.call("/app/vscreen/node", body, VscreenTargetEvidence.Observation.NODE, 0);
      assertTrue(typed.toString(), typed.optBoolean("ok"));
      assertEquals(text, written.get());
      assertEquals(1, f.actions.get());
      assertEquals(0, f.injected.get());
    }
  }

  @Test
  public void fullResolutionImageCoordinatesSelectAndClickTheVisibleOriginalButtonOnce()
      throws Exception {
    try (Fixture f = new Fixture()) {
      f.button.setBoundsInScreen(new Rect(820, 1200, 920, 1300));
      JSONObject seen = f.see();
      assertTrue(seen.toString(), seen.optBoolean("anchoredAvailable"));
      byte[] jpeg =
          android.util.Base64.decode(seen.getString("previewB64"), android.util.Base64.DEFAULT);
      var size = new android.graphics.BitmapFactory.Options();
      size.inJustDecodeBounds = true;
      android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, size);
      assertEquals(seen.getInt("width"), size.outWidth);
      assertEquals(seen.getInt("height"), size.outHeight);
      assertEquals(1008, size.outWidth);
      assertEquals(1792, size.outHeight);
      var body = f.body();
      body.put("observationId", seen.getString("observationId"));
      body.put("x", 870);
      body.put("y", 1250);
      JSONObject anchor =
          f.call("/app/vscreen/anchor", body, VscreenTargetEvidence.Observation.OBSERVED_ANCHOR, 0);
      assertTrue(anchor.toString(), anchor.optBoolean("ok"));
      assertTrue(f.click(anchor, 0).optBoolean("ok"));
      assertEquals(1, f.actions.get());
      assertEquals(0, f.injected.get());
      assertEquals("ANCHOR_EXPIRED", f.click(anchor, 0).getString("error"));
      assertEquals(1, f.actions.get());
    }
  }

  @Test
  public void fullProductionChainClicksOriginalNodeAfterUnrelatedPixelsAdvanceOnce()
      throws Exception {
    try (Fixture f = new Fixture()) {
      JSONObject seen = f.see();
      assertTrue(seen.toString(), seen.optBoolean("anchoredAvailable"));
      assertEquals(
          "VSCREEN_TRANSPORT_UNAVAILABLE",
          new VirtualScreenTransport()
              .request(f.core.handshake(), "/vscreen/observation-region-extra", "")
              .optString("error"));
      assertEquals(10, seen.getLong("sourceFrameSeq"));
      f.display.frame.setPixel(900, 1600, 0xffabcdef);
      f.display.sequence = 11;
      JSONObject anchor = f.anchor(seen);
      assertTrue(anchor.toString(), anchor.optBoolean("ok"));
      JSONObject clicked = f.click(anchor, 0);
      assertTrue(clicked.toString(), clicked.optBoolean("ok"));
      assertEquals("ANCHORED_NODE", clicked.getString("mode"));
      assertEquals(10, clicked.getLong("sourceFrameSeq"));
      assertEquals(11, clicked.getLong("validatedCurrentSeq"));
      assertEquals(1, f.actions.get());
      assertEquals(0, f.injected.get());
      assertNull(f.broker.claimDispatch(f.lastRequest));
      assertEquals("ANCHOR_EXPIRED", f.click(anchor, 0).getString("error"));
      assertEquals(1, f.actions.get());
      var legacy = f.body();
      legacy.put("x", 150);
      legacy.put("y", 150);
      JSONObject stale =
          f.call("/app/vscreen/tap", legacy, VscreenTargetEvidence.Observation.PIXEL, 0);
      assertEquals("STALE_FRAME", stale.getString("error"));
      assertEquals(0, f.injected.get());
      f.drain();
      assertEquals(0, f.display.observations.allocatedBytes());
    }
  }

  @Test
  public void changedSemanticContextAfterSeeCannotBeReplacedByNewTreeAtSelection()
      throws Exception {
    try (Fixture f = new Fixture()) {
      JSONObject seen = f.see();
      f.label.setText("new context");
      JSONObject anchor = f.anchor(seen);
      assertTrue(anchor.toString(), anchor.optBoolean("ok"));
      JSONObject result = f.click(anchor, 0);
      assertEquals("ANCHOR_TREE_CHANGED", result.getString("error"));
      assertEquals(0, f.actions.get());
      assertTrue(f.broker.authorized(f.lastRequest));
      f.drain();
      assertEquals(0, f.display.observations.allocatedBytes());
    }
  }

  @Test
  public void targetPixelOrSafetyMarginChangeRejectsBeforeSemanticClaim() throws Exception {
    try (Fixture f = new Fixture()) {
      JSONObject anchor = f.anchor(f.see());
      f.display.frame.setPixel(93, 100, 0xffabcdef);
      f.display.sequence = 11;
      assertEquals("ANCHOR_REGION_CHANGED", f.click(anchor, 0).getString("error"));
      assertEquals(0, f.actions.get());
      assertTrue(f.broker.authorized(f.lastRequest));
    }
  }

  @Test
  public void changedConsentCannotBorrowOldObservationOrRestoreConsumedAnchor() throws Exception {
    try (Fixture f = new Fixture()) {
      JSONObject anchor = f.anchor(f.see());
      assertEquals("ANCHOR_IDENTITY_CHANGED", f.click(anchor, 1).getString("error"));
      assertEquals(0, f.actions.get());
      assertTrue(f.broker.authorized(f.lastRequest));
      assertEquals("ANCHOR_EXPIRED", f.click(anchor, 0).getString("error"));
    }
  }

  @Test
  public void unknownAfterOneActualNodeDispatchNeverReplaysTheAnchor() throws Exception {
    try (Fixture f = new Fixture()) {
      nodeShadow(f.button)
          .setOnPerformActionListener(
              (action, args) -> {
                f.actions.incrementAndGet();
                f.live.set(false);
                return true;
              });
      JSONObject anchor = f.anchor(f.see());
      JSONObject result = f.click(anchor, 0);
      assertTrue(result.toString(), result.optBoolean("executionUnknown"));
      assertEquals(1, f.actions.get());
      f.live.set(true);
      assertEquals("ANCHOR_EXPIRED", f.click(anchor, 0).getString("error"));
      assertEquals(1, f.actions.get());
    }
  }

  @Test
  public void ambiguousActionableNodesCannotMintAnchorAndRepeatSelectionCannotRenewTtl()
      throws Exception {
    try (Fixture f = new Fixture()) {
      JSONObject seen = f.see(), anchor = f.anchor(seen);
      assertTrue(anchor.toString(), anchor.optBoolean("ok"));
      assertEquals("ANCHOR_ALREADY_ISSUED", f.anchor(seen).getString("error"));
      f.root.setClickable(true);
      f.root.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
      JSONObject ambiguous = f.anchor(f.see());
      assertEquals("ANCHOR_AMBIGUOUS", ambiguous.getString("error"));
      long prior = f.display.observations.allocatedBytes();
      for (int i = 0; i < 64; i++) f.root.getExtras().putIntArray("controlled-" + i, new int[4096]);
      JSONObject budget = f.see();
      assertTrue(budget.toString(), budget.optBoolean("ok"));
      assertFalse(budget.optBoolean("anchoredAvailable"));
      assertFalse(budget.has("observationId"));
      assertEquals("ANCHOR_TREE_BUDGET", budget.getString("availabilityReason"));
      assertEquals(prior, f.display.observations.allocatedBytes());
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  public void unavailableTargetWindowsReleaseAllTransferredHandlesAndStillReturnSeeImage()
      throws Exception {
    try (Fixture f = new Fixture()) {
      FreshWindows.originals.clear();
      FreshWindows.originals.put(DISPLAY + 1, List.of(f.window));
      JSONObject seen = f.see();
      assertTrue(seen.toString(), seen.optBoolean("ok"));
      assertFalse(seen.optBoolean("anchoredAvailable"));
      assertFalse(seen.has("observationId"));
      assertFalse(seen.getString("previewB64").isEmpty());
      for (var window : FreshWindows.delivered)
        assertNull(ReflectionHelpers.getField(windowShadow(window), "rootNode"));
      f.drain();
      assertEquals(0, f.display.observations.allocatedBytes());
    }
  }

  @Test
  public void thirdAgentSeeEvictsOldObservationAndClearingSemanticOwnerReleasesRawCopies()
      throws Exception {
    try (Fixture f = new Fixture()) {
      JSONObject old = f.see();
      f.see();
      f.see();
      assertEquals(2, f.display.observations.retainedCount());
      assertEquals("OBSERVATION_EXPIRED", f.anchor(old).getString("error"));
      var semantics = (VirtualScreenSemanticAdapter) field(f.manager, "semantics");
      semantics.clear();
      f.drain();
      assertEquals(0, f.display.observations.retainedCount());
      f.drain();
      assertEquals(0, f.display.observations.allocatedBytes());
    }
  }

  @Test
  public void invalidationDuringReaderCannotRepublishLeaseOrRecycleItsProofEarly()
      throws Exception {
    try (Fixture f = new Fixture()) {
      FreshWindows.nextRead = () -> f.manager.discardObservations();
      JSONObject seen = f.see();
      assertTrue(seen.toString(), seen.optBoolean("ok"));
      assertFalse(seen.optBoolean("anchoredAvailable"));
      assertFalse(seen.has("observationId"));
      f.drain();
      assertEquals(0, f.display.observations.allocatedBytes());
      assertEquals(0, f.actions.get());
      JSONObject next = f.see();
      assertTrue(next.toString(), next.optBoolean("anchoredAvailable"));
      JSONObject anchor = f.anchor(next);
      FreshWindows.nextRead = () -> f.manager.discardObservations();
      assertEquals("ANCHOR_EXPIRED", f.click(anchor, 0).optString("error"));
      assertEquals(0, f.actions.get());
      f.drain();
      assertEquals(0, f.display.observations.allocatedBytes());
      JSONObject expiring = f.anchor(f.see());
      FreshWindows.nextRead =
          () -> {
            try {
              var semantics = (VirtualScreenSemanticAdapter) field(f.manager, "semantics");
              Object owner = field(semantics.session, "observed");
              Object exact = ((Map<?, ?>) field(owner, "observations")).values().iterator().next();
              var expiry = owner.getClass().getDeclaredMethod("expire", exact.getClass());
              expiry.setAccessible(true);
              var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
              Thread timer =
                  new Thread(
                      () -> {
                        try {
                          expiry.invoke(owner, exact);
                        } catch (Throwable error) {
                          failure.set(error);
                        }
                      },
                      "controlled-expiry");
              timer.setDaemon(true);
              timer.start();
              timer.join(1000);
              assertFalse("expiry waited on the active remote reader", timer.isAlive());
              assertNull(failure.get());
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          };
      assertEquals("ANCHOR_EXPIRED", f.click(expiring, 0).optString("error"));
      assertEquals(0, f.actions.get());
      f.drain();
      assertEquals(0, f.display.observations.allocatedBytes());
      JSONObject timed = f.anchor(f.see());
      FreshWindows.nextRead =
          () ->
              org.robolectric.shadows.ShadowSystemClock.advanceBy(
                  java.time.Duration.ofMillis(30_001));
      // Core's controlled clock stays fixed: the native host must enforce its own original TTL.
      assertEquals("ANCHOR_EXPIRED", f.click(timed, 0).optString("error"));
      assertEquals(0, f.actions.get());
      assertTrue(f.broker.authorized(f.lastRequest));
      f.drain();
      assertEquals(0, f.display.observations.allocatedBytes());
    }
  }
}
