package com.deepseekharness.app.screen;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.capabilities.CapabilityBroker;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.capabilities.ScreenTargetEvidence;
import com.deepseekharness.app.core.RuntimeSnapshot;
import com.deepseekharness.app.util.ScreenConnectionEpoch;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowLegacyBitmap;
import org.robolectric.shadows.ShadowSystemClock;

/** Actual Main/Capture/Shot/hash/ledger/Action/Context/Broker, with only in-memory OS facts. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    packageName = "com.dsh.client",
    shadows = {PhysicalScreenFlowTest.Pixels.class, PhysicalScreenFlowTest.Platform.class})
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PhysicalScreenFlowTest {
  @Test
  public void api23GestureRefusesBeforeObservationOrPlatformAccess() throws Exception {
    int current = android.os.Build.VERSION.SDK_INT;
    org.robolectric.util.ReflectionHelpers.setStaticField(
        android.os.Build.VERSION.class, "SDK_INT", 23);
    try {
      var executor = new ScreenActionExecutor(null, null, null);
      var coordinate =
          ScreenActionExecutor.class.getDeclaredMethod(
              "coordinate", ScreenRequestContext.class, String.class, JSONObject.class);
      coordinate.setAccessible(true);
      JSONObject result = (JSONObject) coordinate.invoke(executor, null, "tap", new JSONObject());
      assertEquals("NOT_SENT", result.getString("state"));
      assertEquals("GESTURE_UNSUPPORTED", result.getString("code"));
      var display =
          ScreenTargetResolver.class.getDeclaredMethod(
              "windowDisplay", AccessibilityWindowInfo.class);
      display.setAccessible(true);
      assertEquals(android.view.Display.DEFAULT_DISPLAY, display.invoke(null, new Object[] {null}));
    } finally {
      org.robolectric.util.ReflectionHelpers.setStaticField(
          android.os.Build.VERSION.class, "SDK_INT", current);
    }
  }

  @org.junit.Rule public final org.junit.rules.TestName method = new org.junit.rules.TestName();
  private static final String PACKAGE = "org.example.physical.fixture";
  private static final String INSTALL = "physical-install";
  private final AtomicLong clock = new AtomicLong(1000);
  private final AtomicInteger ids = new AtomicInteger();
  private final ScreenConnectionEpoch<DshaAccessibilityService> connection =
      new ScreenConnectionEpoch<>();
  private CapabilityBroker broker;
  private MainScreenActions main;
  private DshaAccessibilityService service;
  private AccessibilityWindowInfo app;
  private List<AccessibilityWindowInfo> windows;
  private AccessibilityNodeInfo root;
  private long policyRevision = 3, runtimeGeneration = 1;
  private int requestWindow = 41;
  private String requestPackage = PACKAGE;

  @Before
  public void setUp() {
    Pixels.reset();
    Platform.reset();
    broker = new CapabilityBroker(clock::get);
    service = Robolectric.buildService(DshaAccessibilityService.class).get();
    app = window(41, 1, AccessibilityWindowInfo.TYPE_APPLICATION, true, new Rect(0, 0, 8, 12));
    root = AccessibilityNodeInfo.obtain();
    root.setPackageName(PACKAGE);
    ((ShadowAccessibilityNodeInfo) Shadow.extract(root)).setAccessibilityWindowInfo(app);
    ((ShadowAccessibilityWindowInfo) Shadow.extract(app)).setRoot(root);
    windows =
        List.of(
            app,
            window(42, 8, AccessibilityWindowInfo.TYPE_SYSTEM, false, new Rect(0, 0, 8, 2)),
            window(43, 8, AccessibilityWindowInfo.TYPE_SYSTEM, false, new Rect(0, 10, 8, 12)));
    register(service);
    connection.connect(service);
    main = new MainScreenActions(connection);
    for (Class<?> production :
        new Class<?>[] {
          MainScreenActions.class,
          ScreenScreenshotCapture.class,
          ScreenActionExecutor.class,
          ScreenRequestContext.class,
          CapabilityBroker.class,
          com.deepseekharness.app.util.ScreenObservationLedger.class,
          com.deepseekharness.app.util.ScreenRequestExecutor.class
        }) assertProductionOrigin(production);
  }

  @After
  public void tearDown() {
    com.deepseekharness.app.control.OwnedScreenOverlay.clear("physical-fixture");
    Platform.captureRelease.countDown();
    Platform.gestureRelease.countDown();
    Platform.windowsRelease.countDown();
    if (main != null) main.revoke();
  }

  @Test
  public void exactOwnedCompactOverlayIsMaskedWhileForeignOverlayStillRefusesCoordinates() throws Exception {
    var overlay = window(77,20,AccessibilityWindowInfo.TYPE_SYSTEM,false,new Rect(5,3,7,5));
    var ownRoot = AccessibilityNodeInfo.obtain();
    ownRoot.setPackageName(service.getPackageName());
    ((ShadowAccessibilityWindowInfo)Shadow.extract(overlay)).setRoot(ownRoot);
    com.deepseekharness.app.control.OwnedScreenOverlay.publish("physical-fixture",77,
        new com.deepseekharness.app.util.ScreenWindowRegion.Bounds(5,3,7,5),false);
    windows = new ArrayList<>(windows); windows.add(overlay); register(service);
    JSONObject shot = shot();
    assertTrue(shot.getBoolean("coordinatesAvailable"));
    assertEquals(1,shot.getJSONArray("excludedRegions").length());
    byte[] png = android.util.Base64.decode(shot.getString("data"),android.util.Base64.NO_WRAP);
    Bitmap returned = android.graphics.BitmapFactory.decodeByteArray(png,0,png.length);
    try { assertEquals(android.graphics.Color.BLACK,returned.getPixel(5,3)); }
    finally { returned.recycle(); }
    assertEquals("GESTURE_OUTSIDE_OBSERVED_REGION",call("swipe",new JSONObject()
        .put("observationId",shot.getString("observationId")).put("x1",3).put("y1",4)
        .put("x2",6).put("y2",4),2000).getString("code"));
    assertEquals(0,Platform.sends.get());
    assertEquals("COMPLETED",call("tap",new JSONObject().put("observationId",shot().getString("observationId"))
        .put("x",3).put("y",8),2000).getString("state"));
    windows.add(window(78,21,AccessibilityWindowInfo.TYPE_SYSTEM,false,new Rect(2,3,4,5)));
    register(service);
    assertFalse(shot().getBoolean("coordinatesAvailable"));
  }

  @Test
  public void registeredViewInsidePaddedWindowMasksWholeWindowAndLayoutChangeConsumesFrame() throws Exception {
    var overlay = window(77,20,AccessibilityWindowInfo.TYPE_SYSTEM,false,new Rect(4,3,8,6));
    var ownRoot = AccessibilityNodeInfo.obtain();
    ownRoot.setPackageName(service.getPackageName());
    ((ShadowAccessibilityWindowInfo)Shadow.extract(overlay)).setRoot(ownRoot);
    com.deepseekharness.app.control.OwnedScreenOverlay.publish("physical-fixture",77,
        new com.deepseekharness.app.util.ScreenWindowRegion.Bounds(5,4,7,5),false);
    windows = new ArrayList<>(windows); windows.add(overlay); register(service);
    JSONObject observed = shot();
    assertTrue(observed.getBoolean("coordinatesAvailable"));
    JSONObject excluded = observed.getJSONArray("excludedRegions").getJSONObject(0).getJSONObject("bounds");
    assertEquals(4, excluded.getInt("left"));
    assertEquals(8, excluded.getInt("right"));
    byte[] png = android.util.Base64.decode(observed.getString("data"),android.util.Base64.NO_WRAP);
    Bitmap image = android.graphics.BitmapFactory.decodeByteArray(png,0,png.length);
    try { assertEquals(android.graphics.Color.BLACK,image.getPixel(4,3)); }
    finally { image.recycle(); }
    assertEquals("GESTURE_OUTSIDE_OBSERVED_REGION",call("tap", point(observed.getString("observationId"))
        .put("x",4).put("y",4),2000).getString("code"));
    String frame = shot().getString("observationId");
    ((ShadowAccessibilityWindowInfo)Shadow.extract(overlay)).setBoundsInScreen(new Rect(3,3,8,6));
    JSONObject moved = call("tap",point(frame).put("x",3).put("y",8),2000);
    assertEquals("STALE_FRAME", moved.getString("code"));
    assertEquals(4, moved.getJSONObject("observedGeometry").getJSONArray("excludedRegions")
        .getJSONObject(0).getJSONObject("bounds").getInt("left"));
    assertEquals(3, moved.getJSONObject("currentGeometry").getJSONArray("excludedRegions")
        .getJSONObject(0).getJSONObject("bounds").getInt("left"));
    assertTrue(moved.getBoolean("observationConsumed"));
    assertFalse(moved.getBoolean("automaticRetry"));
    assertEquals("FRAME_NOT_OBSERVED",tap(frame,2000).getString("code"));
    assertEquals(0,Platform.sends.get());
    ownRoot.setPackageName("org.example.foreign");
    JSONObject foreign = shot();
    assertFalse(foreign.getBoolean("coordinatesAvailable"));
    assertEquals(77,foreign.getJSONObject("geometry").getJSONArray("occludingWindows")
        .getJSONObject(0).getInt("windowId"));
    ownRoot.setPackageName(service.getPackageName());
    com.deepseekharness.app.control.OwnedScreenOverlay.publish("physical-fixture",77,
        new com.deepseekharness.app.util.ScreenWindowRegion.Bounds(5,4,7,5),true);
    assertFalse(shot().getBoolean("coordinatesAvailable"));
  }

  @Test
  public void treeAdvertisesRealScrollActionsAndDispatchesOneFreshNodeWithoutTouchFallback() throws Exception {
    AccessibilityNodeInfo scroll = semanticButton(new Rect(0,2,8,10));
    scroll.setClickable(false);
    scroll.setScrollable(true);
    scroll.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    scroll.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    AtomicInteger performed = new AtomicInteger();
    AtomicInteger last = new AtomicInteger();
    ((ShadowAccessibilityNodeInfo)Shadow.extract(scroll)).setOnPerformActionListener((action,bundle) -> {
      performed.incrementAndGet(); last.set(action); return true;
    });
    for (String name : List.of("scroll_forward","scroll_backward")) {
      JSONObject tree = call("tree",new JSONObject(),2000);
      JSONObject node = tree.getJSONArray("nodes").getJSONObject(0);
      assertTrue(node.getJSONArray("supportedActions").toString().contains(name));
      assertTrue(tree.getString("text").contains(name));
      JSONObject action = new JSONObject().put("treeId",tree.getString("treeId"))
          .put("nodeId",node.getString("nodeId")).put("action",name);
      JSONObject result = call("node",action,2000);
      assertEquals("SCREEN_ACTION_SUBMITTED",result.getString("code"));
      assertEquals(name.equals("scroll_forward") ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
          : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,last.get());
      assertFalse(result.getBoolean("effectVerified"));
      assertFalse(result.getBoolean("automaticRetry"));
      assertEquals("STALE_NODE",call("node",action,2000).getString("code"));
    }
    assertEquals(2,performed.get());
    scroll.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    JSONObject tree = call("tree",new JSONObject(),2000);
    JSONObject action = new JSONObject().put("treeId",tree.getString("treeId"))
        .put("nodeId",tree.getJSONArray("nodes").getJSONObject(0).getString("nodeId"))
        .put("action","scroll_forward");
    assertEquals("NODE_SCROLL_ACTION_UNAVAILABLE",call("node",action,2000).getString("code"));
    assertEquals(2,performed.get());
    assertEquals(0,Platform.sends.get());
  }

  @Test
  public void scrollAllowsPartlyVisibleContainerButRejectsForeignOrFullyCoveredContainer() throws Exception {
    AccessibilityNodeInfo scroll = semanticButton(new Rect(0,2,8,10));
    scroll.setScrollable(true);
    scroll.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    AtomicInteger performed = new AtomicInteger();
    ((ShadowAccessibilityNodeInfo)Shadow.extract(scroll)).setOnPerformActionListener((action,bundle) -> {
      performed.incrementAndGet(); return true;
    });
    var overlay = window(77,20,AccessibilityWindowInfo.TYPE_SYSTEM,false,new Rect(4,3,8,6));
    var ownRoot = AccessibilityNodeInfo.obtain(); ownRoot.setPackageName(service.getPackageName());
    ((ShadowAccessibilityWindowInfo)Shadow.extract(overlay)).setRoot(ownRoot);
    windows = new ArrayList<>(windows); windows.add(overlay); register(service);
    com.deepseekharness.app.control.OwnedScreenOverlay.publish("physical-fixture",77,
        new com.deepseekharness.app.util.ScreenWindowRegion.Bounds(5,4,7,5),false);
    assertEquals("SCREEN_ACTION_SUBMITTED",scrollNode().getString("code"));
    assertEquals(1,performed.get());
    ownRoot.setPackageName("org.example.foreign");
    assertEquals("NODE_SCROLL_REGION_UNAVAILABLE",scrollNode().getString("code"));
    ownRoot.setPackageName(service.getPackageName());
    ((ShadowAccessibilityWindowInfo)Shadow.extract(overlay)).setBoundsInScreen(new Rect(0,2,8,10));
    assertEquals("NODE_SCROLL_REGION_UNAVAILABLE",scrollNode().getString("code"));
    assertFalse(shot().getBoolean("coordinatesAvailable"));
    assertEquals(1,performed.get());
    assertEquals(0,Platform.sends.get());
  }

  private JSONObject scrollNode() throws Exception {
    JSONObject tree = call("tree",new JSONObject(),2000);
    return call("node",new JSONObject().put("treeId",tree.getString("treeId"))
        .put("nodeId",tree.getJSONArray("nodes").getJSONObject(0).getString("nodeId"))
        .put("action","scroll_forward"),2000);
  }

  @Test
  public void productionMcpNodeCallTraversesHttpIntoRealSemanticExecutorWithoutGestureFallback()
      throws Exception {
    AccessibilityNodeInfo scroll = semanticButton(new Rect(0,2,8,10));
    scroll.setScrollable(true);
    scroll.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    scroll.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    AtomicInteger actions = new AtomicInteger(), requests = new AtomicInteger();
    var dispatched = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
    ((ShadowAccessibilityNodeInfo)Shadow.extract(scroll)).setOnPerformActionListener((action,bundle) -> {
      dispatched.add(action);
      if (actions.incrementAndGet() == 3) throw new IllegalStateException("CONTROLLED_SCROLL_UNKNOWN");
      return true;
    });
    var authority = new com.deepseekharness.app.control.ControlTaskAuthority();
    var binding = new com.deepseekharness.app.control.ControlTaskAuthority.Binding(
        com.deepseekharness.app.control.ControlTaskAuthority.Owner.LOCAL_DSH,
        "physical-bridge", "fixture-mcp-node-task", "1");
    var lease = authority.begin(binding);
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    var workers = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
      Thread worker = new Thread(r,"physical-mcp-node-http"); worker.setDaemon(true); return worker;
    });
    http.setExecutor(workers);
    http.createContext("/app/ui/", exchange -> {
      String result;
      try {
        requests.incrementAndGet();
        assertEquals("fixture-token",exchange.getRequestHeaders().getFirst("X-Token"));
        String operation = exchange.getRequestURI().getPath().substring("/app/ui/".length());
        assertTrue("No coordinate fallback route",java.util.Set.of("dump","node").contains(operation));
        var args = new JSONObject();
        for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
          String[] pair = part.split("=",2);
          args.put(java.net.URLDecoder.decode(pair[0],java.nio.charset.StandardCharsets.UTF_8),
              java.net.URLDecoder.decode(pair.length==2 ? pair[1] : "",java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(binding.taskSessionId(),args.getString("taskSessionId"));
        assertEquals(binding.turnId(),args.getString("turnId"));
        CapabilityRequest request = request(operation,5000);
        com.deepseekharness.app.control.ScreenAuthorityBindings.bind(request,
            binding.identity()+"\0"+lease.revision(),()->authority.current(lease));
        assertTrue(broker.authorize(request));
        result = executeOffMain(request,operation,args);
      } catch (Throwable unexpected) {
        failure.set(unexpected); result="[ERR] HOST_FIXTURE_FAILURE";
      }
      byte[] bytes;
      try { bytes = new JSONObject().put("result",result).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); }
      catch (org.json.JSONException impossible) { throw new java.io.IOException(impossible); }
      exchange.getResponseHeaders().set("Content-Type","application/json");
      exchange.sendResponseHeaders(200,bytes.length);
      try (var body = exchange.getResponseBody()) { body.write(bytes); }
    });
    http.start();
    try {
      String script = """
          const assert=require('node:assert/strict'),server=require(process.argv[1]);
          const base=process.argv[2], task={sessionId:'fixture-mcp-node-task',turnId:'1'};
          const deps={fs:{async readFile(path){assert.equal(path,'/root/.dsh/.bridge_token');return 'fixture-token';}},
            fetch(url,options){return fetch(base+url.pathname+url.search,options);}};
          const receipts=[];
          async function call(name,args){const result=await server.call(name,args,new AbortController().signal,deps,task);
            const native=JSON.parse(result.content[0].text);receipts.push({name,code:native.code,isError:result.isError});
            return {result,native};}
          async function nodeArgs(action){const {native}=await call('android_get_state',{});
            const node=native.nodes.find(node=>node.supportedActions.includes(action));assert.ok(node);
            return {treeId:native.treeId,nodeId:node.nodeId,action};}
          (async()=>{
            for(const action of ['scroll_forward','scroll_backward']){
              const args=await nodeArgs(action),{result,native}=await call('android_node',args);
              assert.equal(result.isError,false);assert.equal(native.code,'SCREEN_ACTION_SUBMITTED');
              assert.equal(native.accessibilityAction,action==='scroll_forward'?4096:8192);
              assert.equal(native.effectVerified,false);assert.equal(native.automaticRetry,false);
              if(action==='scroll_forward')assert.equal((await call('android_node',args)).native.code,'STALE_NODE');
            }
            const args=await nodeArgs('scroll_forward'),unknown=await call('android_node',args);
            assert.equal(unknown.result.isError,true);assert.equal(unknown.native.state,'UNKNOWN');
            assert.equal(unknown.native.executionUnknown,true);
            assert.equal((await call('android_node',args)).native.code,'STALE_NODE');
            await assert.rejects(server.call('android_node',{...args,action:'swipe'},new AbortController().signal,deps,task),/INVALID_action/);
            console.log(JSON.stringify({scope:'PRODUCTION_MCP_CALL_HTTP_REAL_SCREEN_EXECUTOR',receipts}));
          })().catch(error=>{console.error(error);process.exitCode=1;});
          """;
      java.nio.file.Path server = java.nio.file.Path.of(
          "app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/server.cjs").toAbsolutePath();
      Process process = new ProcessBuilder("node","-e",script,server.toString(),
          "http://127.0.0.1:"+http.getAddress().getPort()).redirectErrorStream(true).start();
      assertTrue("bounded production MCP host test",process.waitFor(20,TimeUnit.SECONDS));
      String output = new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(output,0,process.exitValue());
      if (failure.get()!=null) throw new AssertionError(failure.get());
      assertEquals(8,requests.get());
      assertEquals(java.util.List.of(4096,8192,4096),dispatched);
      assertEquals(3,actions.get());
      assertEquals(0,Platform.sends.get());
      String evidence = System.getProperty("physical.evidence");
      if (evidence != null) java.nio.file.Files.writeString(
          java.nio.file.Path.of(evidence,"mcp-node-chain.json"),output);
    } finally { http.stop(0); workers.shutdownNow(); }
  }

  @Test
  public void pauseFencesBoundNativeRequestAndFreshResumeCannotReuseItsFrame() throws Exception {
    var authority = new com.deepseekharness.app.control.ControlTaskAuthority();
    var binding = new com.deepseekharness.app.control.ControlTaskAuthority.Binding(
        com.deepseekharness.app.control.ControlTaskAuthority.Owner.LOCAL_DSH,
        "authenticated-native-connection","full-task-session","17");
    var lease = authority.begin(binding);
    CapabilityRequest shotRequest = request("screenshot",2000);
    com.deepseekharness.app.control.ScreenAuthorityBindings.bind(shotRequest,
        binding.identity()+"\0"+lease.revision(),()->authority.current(lease));
    assertTrue(broker.authorize(shotRequest));
    JSONObject observed = new JSONObject(executeOffMain(shotRequest,"screenshot",new JSONObject().put("format","mcp")));
    String id = observed.getString("observationId");
    CapabilityRequest stale = request("tap",2000);
    com.deepseekharness.app.control.ScreenAuthorityBindings.bind(stale,
        binding.identity()+"\0"+lease.revision(),()->authority.current(lease));
    assertTrue(broker.authorize(stale));
    authority.pause("LOCAL_USER_PAUSE");
    assertEquals("NOT_SENT",new JSONObject(executeOffMain(stale,"tap",point(id))).getString("state"));
    authority.resume();
    var resumed = authority.lease(binding);
    CapabilityRequest next = request("tap",2000);
    com.deepseekharness.app.control.ScreenAuthorityBindings.bind(next,
        binding.identity()+"\0"+resumed.revision(),()->authority.current(resumed));
    assertTrue(broker.authorize(next));
    assertEquals("FRAME_NOT_OBSERVED",new JSONObject(executeOffMain(next,"tap",point(id))).getString("code"));
    assertEquals(0,Platform.sends.get());
  }

  @Test
  public void newestReturnedObservationAllowsOutsidePixelChangeAtFiftyMillis() throws Exception {
    String first = shot().getString("observationId");
    JSONObject second = shot();
    assertTrue(second.getBoolean("coordinatesAvailable"));
    assertEquals(2, second.getJSONObject("observationRegion").getInt("top"));
    assertEquals(10, second.getJSONObject("observationRegion").getInt("bottom"));
    Pixels.values[0] ^= 1;
    Pixels.values[95] ^= 1;
    clock.addAndGet(50);
    ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(50));
    assertEquals("COMPLETED", tap(second.getString("observationId"), 2000).getString("state"));
    assertEquals(1, Platform.sends.get());
    assertEquals(3, Pixels.acquisitions.get());
    assertEquals("FRAME_NOT_OBSERVED", tap(first, 2000).getString("code"));
    assertEquals(1, Platform.sends.get());
  }

  @Test
  public void insidePixelAndWindowGeometryChangesRefuseWithoutDispatch() throws Exception {
    String id = shot().getString("observationId");
    Pixels.values[6 * 8 + 4] ^= 1;
    JSONObject changed = tap(id, 2000);
    assertEquals("NOT_SENT", changed.getString("state"));
    assertEquals("STALE_FRAME", changed.getString("code"));
    assertEquals("OBSERVED_REGION_PIXELS_CHANGED", changed.getString("reason"));
    assertFalse(changed.getBoolean("automaticRetry"));
    assertEquals("FRAME_NOT_OBSERVED", tap(id, 2000).getString("code"));
    id = shot().getString("observationId");
    ((ShadowAccessibilityWindowInfo) Shadow.extract(app)).setBoundsInScreen(new Rect(1, 0, 8, 12));
    JSONObject moved = tap(id, 2000);
    assertEquals("NOT_SENT", moved.getString("state"));
    assertEquals("TARGET_WINDOW_GEOMETRY_OR_OCCLUSION_CHANGED", moved.getString("reason"));
    assertEquals(0,moved.getJSONObject("observedGeometry").getJSONObject("windowBounds").getInt("left"));
    assertEquals(1,moved.getJSONObject("currentGeometry").getJSONObject("windowBounds").getInt("left"));
    assertEquals(0, Platform.sends.get());
  }

  @Test
  public void dumpCannotAuthorizeCoordinatesButReturnedScreenshotCan() throws Exception {
    JSONObject tree = call("dump", new JSONObject(), 2000);
    assertEquals("SCREENSHOT_OBSERVATION_REQUIRED", tree.getString("coordinateAuthorization"));
    assertFalse(tree.has("observationId"));
    assertEquals(
        "FRAME_NOT_OBSERVED",
        call("tap", new JSONObject().put("x", 4).put("y", 6), 2000).getString("code"));
    assertEquals(0, Platform.sends.get());
    JSONObject screenshot = shot();
    assertTrue(screenshot.getBoolean("coordinatesAvailable"));
    String evidence = System.getProperty("physical.evidence");
    if (evidence != null)
      java.nio.file.Files.writeString(
          java.nio.file.Path.of(evidence, "native-screenshot.json"), screenshot.toString());
    assertEquals("COMPLETED", tap(screenshot.getString("observationId"), 2000).getString("state"));
    assertEquals(1, Platform.sends.get());
  }

  @Test
  public void actualBridgeHelpExplainsCredentialAndEffectBoundaries() throws Exception {
    assertProductionOrigin(com.deepseekharness.app.HttpShellService.class);
    var bridge =
        new com.deepseekharness.app.HttpShellService(
            org.robolectric.RuntimeEnvironment.getApplication());
    var help = com.deepseekharness.app.HttpShellService.class.getDeclaredMethod("appHelp");
    help.setAccessible(true);
    String text = (String) help.invoke(bridge);
    assertTrue(text.contains("observationId"));
    assertTrue(text.contains("dump/tree不签发像素凭据"));
    assertTrue(text.contains("effectVerified:false"));
    assertTrue(text.contains("gesture=long_press"));
    assertTrue(text.contains("gesture=double_tap"));
    assertTrue(text.contains("/app/ui/node?treeId="));
    assertTrue(text.contains("supportedActions"));
    assertTrue(text.contains("scroll_backward"));
    assertFalse(text.contains("/app/ui/swipe?x1="));
    assertEquals(0, Platform.sends.get());
  }

  @Test
  public void tapDoubleTapLongPressAndSwipeEachDispatchOneBoundedGesture() throws Exception {
    for (String kind : List.of("tap", "double_tap", "long_press", "swipe")) {
      String id = shot().getString("observationId");
      JSONObject args =
          kind.equals("swipe")
              ? new JSONObject()
                  .put("observationId", id)
                  .put("x1", 3)
                  .put("y1", 4)
                  .put("x2", 5)
                  .put("y2", 8)
                  .put("ms", 300)
              : point(id).put("gesture", kind).put("ms", 650);
      int before = Platform.sends.get();
      JSONObject result = call(kind.equals("swipe") ? "swipe" : "tap", args, 2000);
      assertEquals("COMPLETED", result.getString("state"));
      assertEquals(kind, result.getString("gesture"));
      assertTrue(result.getBoolean("gestureCompleted"));
      assertFalse(result.getBoolean("effectVerified"));
      assertFalse(result.getBoolean("automaticRetry"));
      assertEquals(before + 1, Platform.sends.get());
      GestureDescription gesture = Platform.lastGesture;
      assertEquals(kind.equals("double_tap") ? 2 : 1, gesture.getStrokeCount());
      assertEquals(0, gesture.getStroke(0).getStartTime());
      assertEquals(
          kind.equals("long_press") ? 650 : kind.equals("swipe") ? 300 : 50,
          gesture.getStroke(0).getDuration());
      if (kind.equals("double_tap")) {
        assertEquals(150, gesture.getStroke(1).getStartTime());
        assertEquals(50, gesture.getStroke(1).getDuration());
      }
      for (int i = 0; i < gesture.getStrokeCount(); i++) {
        android.graphics.RectF bounds = new android.graphics.RectF();
        gesture.getStroke(i).getPath().computeBounds(bounds, true);
        assertEquals(
            kind.equals("swipe")
                ? new android.graphics.RectF(3, 4, 5, 8)
                : new android.graphics.RectF(4, 6, 4, 6),
            bounds);
      }
      assertEquals("FRAME_NOT_OBSERVED", tap(id, 2000).getString("code"));
      assertEquals(before + 1, Platform.sends.get());
    }
  }

  @Test
  public void expiredOccludedOutsideAndInvalidGesturesHaveZeroDispatch() throws Exception {
    String id = shot().getString("observationId");
    ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(30_001));
    clock.addAndGet(30_001);
    assertEquals("FRAME_NOT_OBSERVED", tap(id, 2000).getString("code"));
    id = shot().getString("observationId");
    assertEquals(
        "GESTURE_OUTSIDE_OBSERVED_REGION",
        call("tap", point(id).put("gesture", "long_press").put("ms", 499), 2000).getString("code"));
    id = shot().getString("observationId");
    assertEquals(
        "INVALID_GESTURE", call("tap", point(id).put("gesture", "pinch"), 2000).getString("code"));
    id = shot().getString("observationId");
    assertEquals(
        "GESTURE_OUTSIDE_OBSERVED_REGION",
        call(
                "swipe",
                new JSONObject()
                    .put("observationId", id)
                    .put("x1", 4)
                    .put("y1", 6)
                    .put("x2", 4)
                    .put("y2", 11),
                2000)
            .getString("code"));
    var overlay =
        window(55, 20, AccessibilityWindowInfo.TYPE_APPLICATION, false, new Rect(2, 3, 7, 8));
    windows = new ArrayList<>(windows);
    windows.add(overlay);
    register(service);
    JSONObject shot = shot();
    assertFalse(shot.getBoolean("coordinatesAvailable"));
    assertFalse(shot.has("observationId"));
    assertEquals("TARGET_WINDOW_OCCLUDED", shot.getString("reason"));
    assertEquals("FRAME_NOT_OBSERVED", tap(id, 2000).getString("code"));
    assertEquals(0, Platform.sends.get());
  }

  @Test
  public void cancelledDoubleTapIsPartialAndNeverAutomaticallyRepeated() throws Exception {
    String id = shot().getString("observationId");
    Platform.cancelGesture = true;
    JSONObject result = call("tap", point(id).put("gesture", "double_tap"), 2000);
    assertEquals("PARTIAL", result.getString("state"));
    assertEquals("GESTURE_CANCELLED", result.getString("code"));
    assertEquals(1, Platform.sends.get());
    assertEquals("FRAME_NOT_OBSERVED", tap(id, 2000).getString("code"));
    assertEquals(1, Platform.sends.get());
  }

  @Test
  public void semanticWebViewAcknowledgementDoesNotClaimDomEffectOrFallback() throws Exception {
    AtomicInteger accepted = new AtomicInteger();
    AccessibilityNodeInfo button = semanticButton(new Rect(2, 4, 6, 8));
    ((ShadowAccessibilityNodeInfo) Shadow.extract(button))
        .setOnPerformActionListener(
            (action, bundle) -> {
              accepted.incrementAndGet();
              return true;
            });
    JSONObject result = call("tapText", new JSONObject().put("text", "Web button"), 2000);
    assertEquals("SCREEN_ACTION_SUBMITTED", result.getString("code"));
    assertTrue(result.getBoolean("actionAccepted"));
    assertFalse(result.getBoolean("effectVerified"));
    assertFalse(result.getBoolean("automaticRetry"));
    assertEquals("ACCESSIBILITY_NODE_ACTION", result.getString("executionMechanism"));
    assertEquals(1, accepted.get());
    assertEquals(0, Platform.sends.get());
    assertEquals("Web button", button.getText().toString());
    // A later touch is an explicit different request with a new screenshot, never a fallback.
    assertEquals("COMPLETED", tap(shot().getString("observationId"), 2000).getString("state"));
    assertEquals(1, accepted.get());
    assertEquals(1, Platform.sends.get());
  }

  @Test
  public void zeroHeightTextIsSemanticOnlyAndHasNoTouchCenterHint() throws Exception {
    semanticButton(new Rect(2, 6, 6, 6));
    JSONObject tree = call("dump", new JSONObject(), 2000);
    JSONObject node = tree.getJSONArray("nodes").getJSONObject(0);
    assertFalse(node.getBoolean("boundsHaveArea"));
    assertEquals("Web button", node.getString("text"));
    assertTrue(tree.getString("text").contains("仅语义节点"));
    assertFalse(tree.getString("text").contains("中心="));
    assertFalse(tree.has("observationId"));
    assertEquals(0, Platform.sends.get());
  }

  @Test
  public void observationCannotCrossPolicyRuntimeWindowOrPackageIdentity() throws Exception {
    String policy = shot().getString("observationId");
    policyRevision++;
    assertEquals("FRAME_NOT_OBSERVED", tap(policy, 2000).getString("code"));
    String generation = shot().getString("observationId");
    runtimeGeneration++;
    assertEquals("FRAME_NOT_OBSERVED", tap(generation, 2000).getString("code"));
    String window = shot().getString("observationId");
    requestWindow = 51;
    ((ShadowAccessibilityWindowInfo) Shadow.extract(app)).setId(51);
    assertEquals("FRAME_NOT_OBSERVED", tap(window, 2000).getString("code"));
    String oldPackage = shot().getString("observationId");
    requestPackage = "org.example.other.fixture";
    root.setPackageName(requestPackage);
    assertEquals("FRAME_NOT_OBSERVED", tap(oldPackage, 2000).getString("code"));
    assertEquals(0, Platform.sends.get());
    assertEquals("COMPLETED", tap(shot().getString("observationId"), 2000).getString("state"));
    assertEquals(1, Platform.sends.get());
  }

  @Test
  public void semanticExceptionAfterDispatchIsUnknownAndNodeCannotReplay() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    AccessibilityNodeInfo button = semanticButton(new Rect(2, 4, 6, 8));
    ((ShadowAccessibilityNodeInfo) Shadow.extract(button))
        .setOnPerformActionListener(
            (action, bundle) -> {
              attempts.incrementAndGet();
              throw new IllegalStateException("CONTROLLED_PLATFORM_FAILURE");
            });
    JSONObject tree = call("tree", new JSONObject(), 2000);
    JSONObject action =
        new JSONObject()
            .put("treeId", tree.getString("treeId"))
            .put("nodeId", tree.getJSONArray("nodes").getJSONObject(0).getString("nodeId"))
            .put("action", "click");
    JSONObject first = call("node", action, 2000);
    assertEquals("UNKNOWN", first.getString("state"));
    assertTrue(first.getBoolean("executionUnknown"));
    assertEquals("STALE_NODE", call("node", action, 2000).getString("code"));
    assertEquals(1, attempts.get());
    assertEquals(0, Platform.sends.get());
  }

  private AccessibilityNodeInfo semanticButton(Rect bounds) {
    AccessibilityNodeInfo button = AccessibilityNodeInfo.obtain();
    button.setPackageName(PACKAGE);
    button.setClassName("android.webkit.WebView");
    button.setText("Web button");
    button.setBoundsInScreen(bounds);
    button.setEnabled(true);
    button.setClickable(true);
    ((ShadowAccessibilityNodeInfo) Shadow.extract(button)).setAccessibilityWindowInfo(app);
    ((ShadowAccessibilityNodeInfo) Shadow.extract(root)).addChild(button);
    return button;
  }

  @Test
  public void blockedPreclaimCallReturnsButKeepsTheWorkerUntilActualExit() throws Exception {
    String id = shot().getString("observationId");
    Platform.blockCapture = true;
    long started = System.nanoTime();
    JSONObject expired = tap(id, 150);
    assertEquals("NOT_SENT", expired.getString("state"));
    assertEquals("SCREEN_REQUEST_TIMEOUT", expired.getString("code"));
    assertTrue(expired.getBoolean("underlyingRequestMayStillBeRunning"));
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
    assertEquals(1L, Platform.captureRelease.getCount());
    assertEquals(0, Platform.sends.get());
    // The old Android call is still blocked. A new caller cannot replace its worker.
    JSONObject queued = call("key", new JSONObject().put("name", "back"), 80);
    assertEquals("NOT_SENT", queued.getString("state"));
    assertEquals("SCREEN_REQUEST_TIMEOUT", queued.getString("code"));
    assertEquals(0, Platform.globalActions.get());
    List<Thread> waiting = new ArrayList<>();
    var callerFailures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
    AtomicInteger cancelledCallers = new AtomicInteger();
    try {
      for (int i = 0; i < 15; i++) {
        Thread caller =
            new Thread(
                () -> {
                  try {
                    JSONObject answer = call("key", new JSONObject().put("name", "back"), 2000);
                    assertEquals("NOT_SENT", answer.getString("state"));
                    assertEquals("SCREEN_REQUEST_CANCELLED", answer.getString("code"));
                    cancelledCallers.incrementAndGet();
                  } catch (Throwable failure) {
                    callerFailures.add(failure);
                  }
                },
                "physical-queued-caller-" + i);
        caller.setDaemon(true);
        waiting.add(caller);
        caller.start();
      }
      long queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (queuedRequests() != 15 && System.nanoTime() < queueDeadline) Thread.sleep(2);
      assertEquals(15, queuedRequests());
      JSONObject busy = call("key", new JSONObject().put("name", "back"), 500);
      assertEquals("NOT_SENT", busy.getString("state"));
      assertEquals("SCREEN_QUEUE_FULL", busy.getString("code"));
      assertEquals(0, Platform.globalActions.get());
    } finally {
      for (Thread caller : waiting) caller.interrupt();
      for (Thread caller : waiting) caller.join(1000);
      for (Thread caller : waiting) assertFalse(caller.isAlive());
    }
    assertTrue(callerFailures.toString(), callerFailures.isEmpty());
    assertEquals(15, cancelledCallers.get());
    Platform.blockCapture = false;
    Platform.captureRelease.countDown();
    assertEquals("COMPLETED", shot().getString("state")); // drains the same owner, then new work
    assertEquals(0, Platform.sends.get());
    assertEquals("FRAME_NOT_OBSERVED", tap(id, 2000).getString("code"));
    Platform.blockWindows = true;
    MainScreenActions.TargetPreflight preflight = preflightOffMain(100);
    assertNull(preflight.target);
    assertEquals("SCREEN_TARGET_PRECHECK_TIMEOUT", preflight.failure().getString("code"));
    assertEquals("TARGET_OBSERVATION", preflight.failure().getString("stage"));
    assertEquals("NOT_SENT", preflight.failure().getString("state"));
    assertTrue(preflight.failure().getBoolean("underlyingRequestMayStillBeRunning"));
    Platform.blockWindows = false;
    Platform.windowsRelease.countDown();
    assertNotNull(preflightOffMain(2000).target);
    assertEquals(0, Platform.sends.get());
  }

  @Test
  public void alreadyEnteredPlatformActionTimesOutUnknownAndNeverReplays() throws Exception {
    String id = shot().getString("observationId");
    Platform.blockGesture = true;
    JSONObject expired = tap(id, 150);
    assertTrue(Platform.gestureEntered.await(1, TimeUnit.SECONDS));
    assertEquals("UNKNOWN", expired.getString("state"));
    assertEquals("SCREEN_REQUEST_TIMEOUT", expired.getString("code"));
    assertTrue(expired.getBoolean("executionUnknown"));
    assertTrue(expired.getBoolean("underlyingRequestMayStillBeRunning"));
    assertFalse(expired.getBoolean("automaticRetry"));
    Platform.blockGesture = false;
    Platform.gestureRelease.countDown();
    assertEquals("COMPLETED", shot().getString("state"));
    assertEquals("FRAME_NOT_OBSERVED", tap(id, 2000).getString("code"));
    assertEquals(1, Platform.sends.get());
  }

  @Test
  public void cancellationWinningAClaimRaceCannotAdmitTheLatePermit() throws Exception {
    for (int blockedCheck : new int[] {2, 3}) {
      CapabilityRequest request = request("key", 2000);
      assertTrue(broker.authorize(request));
      CountDownLatch claiming = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicInteger authorityChecks = new AtomicInteger();
      ScreenRequestContext context =
          new ScreenRequestContext(
              broker,
              request,
              () -> {
                if (authorityChecks.incrementAndGet() == blockedCheck) {
                  claiming.countDown();
                  await(release);
                }
                return true;
              },
              connection,
              true);
      AtomicBoolean admitted = new AtomicBoolean(true);
      Thread claim = new Thread(() -> admitted.set(context.claimDispatch()), "physical-claim-race");
      claim.setDaemon(true);
      claim.start();
      try {
        assertTrue(claiming.await(1, TimeUnit.SECONDS));
        assertEquals(blockedCheck == 3, context.submitted());
        assertTrue(
            context.cancelAndMayHaveSubmitted()); // unresolved claim is conservatively UNKNOWN
      } finally {
        release.countDown();
        claim.join(1000);
      }
      assertFalse(claim.isAlive());
      assertFalse(admitted.get());
      assertNull(broker.claimDispatch(request));
      assertEquals(0, Platform.sends.get());
    }
  }

  @Test
  public void reconnectFencesOldAuthorityButNewRequestCanObtainANewObservation() throws Exception {
    String oldId = shot().getString("observationId");
    CapabilityRequest old = request("tap", 2000);
    assertTrue(broker.authorize(old));
    DshaAccessibilityService oldService = service;
    assertTrue(connection.disconnect(oldService));
    main.revoke();
    broker.revoke("screen");
    service = Robolectric.buildService(DshaAccessibilityService.class).get();
    register(service);
    connection.connect(service);
    assertFalse(connection.disconnect(oldService));
    JSONObject oldResult = new JSONObject(executeOffMain(old, "tap", point(oldId)));
    assertEquals("NOT_SENT", oldResult.getString("state"));
    assertEquals("FRAME_NOT_OBSERVED", tap(oldId, 2000).getString("code"));
    JSONObject fresh = shot();
    assertNotEquals(oldId, fresh.getString("observationId"));
    assertEquals("COMPLETED", tap(fresh.getString("observationId"), 2000).getString("state"));
    assertEquals(1, Platform.sends.get());
    // Default constructor is the existing virtual path: repeat claims retain its same permit.
    CapabilityRequest virtualCompatibility = request("tree", 2000);
    assertTrue(broker.authorize(virtualCompatibility));
    ScreenRequestContext unchanged =
        new ScreenRequestContext(broker, virtualCompatibility, () -> true, connection);
    assertTrue(unchanged.claimDispatch());
    assertTrue(unchanged.claimDispatch());
    assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
    CapabilityRequest ui = request("key", 2000);
    assertTrue(broker.authorize(ui));
    long beforeUi = System.nanoTime();
    JSONObject refusedUi =
        new JSONObject(
            main.execute(broker, ui, () -> true, "key", new JSONObject().put("name", "back")));
    assertEquals("SCREEN_CALLER_MAIN_THREAD", refusedUi.getString("code"));
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beforeUi) < 500);
    assertEquals(0, Platform.globalActions.get());
  }

  private JSONObject shot() throws Exception {
    return call("screenshot", new JSONObject().put("format", "mcp"), 5000);
  }

  private JSONObject tap(String id, long deadline) throws Exception {
    return call("tap", point(id), deadline);
  }

  private JSONObject point(String id) throws Exception {
    return new JSONObject().put("observationId", id).put("x", 4).put("y", 6);
  }

  private JSONObject call(String operation, JSONObject args, long deadline) throws Exception {
    CapabilityRequest request = request(operation, deadline);
    assertTrue(broker.authorize(request));
    long started = System.nanoTime();
    JSONObject reply = new JSONObject(executeOffMain(request, operation, args));
    System.out.println(
        "PHYSICAL_REPLY|method="
            + method.getMethodName()
            + "|operation="
            + operation
            + "|state="
            + reply.optString("state")
            + "|code="
            + reply.optString("code")
            + "|milliseconds="
            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            + "|gestureSends="
            + Platform.sends.get()
            + "|pixelAcquisitions="
            + Pixels.acquisitions.get());
    return reply;
  }

  private String executeOffMain(CapabilityRequest request, String operation, JSONObject args)
      throws Exception {
    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
      return main.execute(broker, request, () -> true, operation, args);
    var result = new java.util.concurrent.atomic.AtomicReference<String>();
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    Thread caller =
        new Thread(
            () -> {
              try {
                result.set(main.execute(broker, request, () -> true, operation, args));
              } catch (Throwable thrown) {
                failure.set(thrown);
              }
            },
            "physical-http-caller-fact");
    caller.setDaemon(true);
    caller.start();
    caller.join(6500);
    assertFalse("bounded background caller must actually exit", caller.isAlive());
    if (failure.get() != null) throw new AssertionError(failure.get());
    return result.get();
  }

  private MainScreenActions.TargetPreflight preflightOffMain(long budget) throws Exception {
    var task = new java.util.concurrent.FutureTask<>(() -> main.observeTargetForRequest(budget));
    Thread caller = new Thread(task, "physical-http-preflight-fact");
    caller.setDaemon(true);
    caller.start();
    var result = task.get(2500, TimeUnit.MILLISECONDS);
    caller.join(1000);
    assertFalse(caller.isAlive());
    System.out.println(
        "PHYSICAL_PREFLIGHT|method="
            + method.getMethodName()
            + "|targetAvailable="
            + (result.target != null)
            + "|code="
            + (result.target == null ? result.failure().optString("code") : "READABLE"));
    return result;
  }

  private CapabilityRequest request(String operation, long deadline) {
    return new CapabilityRequest(
        "physical-" + ids.incrementAndGet(),
        "screen",
        operation,
        new RuntimeSnapshot.Epoch(INSTALL, runtimeGeneration, "physical-runtime"),
        INSTALL,
        "physical-bridge",
        new ScreenTargetEvidence(0, requestWindow, requestPackage),
        policyRevision,
        broker.epoch("screen"),
        clock.get() + deadline);
  }

  private void register(DshaAccessibilityService target) {
    ((ShadowAccessibilityService) Shadow.extract(target)).setWindowsOnDisplay(0, windows);
  }

  /** Read-only queue inspection for deterministic saturation; never writes policy or evidence. */
  private int queuedRequests() throws Exception {
    var owner = MainScreenActions.class.getDeclaredField("requests");
    owner.setAccessible(true);
    Object executor = owner.get(main);
    var worker = executor.getClass().getDeclaredField("worker");
    worker.setAccessible(true);
    return ((java.util.concurrent.ThreadPoolExecutor) worker.get(executor)).getQueue().size();
  }

  private static AccessibilityWindowInfo window(
      int id, int layer, int type, boolean current, Rect bounds) {
    AccessibilityWindowInfo result = AccessibilityWindowInfo.obtain();
    ShadowAccessibilityWindowInfo facts = Shadow.extract(result);
    facts.setId(id);
    facts.setDisplayId(0);
    facts.setLayer(layer);
    facts.setType(type);
    facts.setFocused(current);
    facts.setActive(current);
    facts.setBoundsInScreen(bounds);
    return result;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS))
        throw new AssertionError("CONTROLLED_PLATFORM_NOT_RELEASED");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static void assertProductionOrigin(Class<?> type) {
    String member = type.getName().replace('.', '/') + ".class";
    try {
      java.nio.file.Path expected =
          java.nio.file.Path.of(System.getProperty("physical.classes")).resolve(member);
      try (var actual = type.getClassLoader().getResourceAsStream(member)) {
        assertNotNull("actual production resource: " + member, actual);
        assertArrayEquals(
            "actual compiled production bytes: " + member,
            java.nio.file.Files.readAllBytes(expected),
            actual.readAllBytes());
      }
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  /** The missing hardware-buffer conversion only; stock copy/getPixels/PNG and production hash. */
  @Implements(Bitmap.class)
  public static final class Pixels extends ShadowLegacyBitmap {
    static final AtomicInteger acquisitions = new AtomicInteger();
    static final int[] values = new int[8 * 12];

    static void reset() {
      acquisitions.set(0);
      java.util.Arrays.fill(values, 0xff234567);
    }

    @Implementation(minSdk = 26)
    protected static Bitmap wrapHardwareBuffer(HardwareBuffer buffer, ColorSpace colorSpace) {
      if (buffer == null || colorSpace == null) return null;
      acquisitions.incrementAndGet();
      Bitmap bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888);
      bitmap.setPixels(values.clone(), 0, 8, 0, 0, 8, 12);
      return bitmap;
    }
  }

  /** Fresh OS window handles and memory-only input delivery. Never starts/binds an Android service. */
  @Implements(AccessibilityService.class)
  public static final class Platform extends ShadowAccessibilityService {
    static final AtomicInteger sends = new AtomicInteger(), globalActions = new AtomicInteger();
    static volatile boolean blockCapture, blockGesture, blockWindows;
    static volatile boolean cancelGesture;
    static volatile GestureDescription lastGesture;
    static CountDownLatch captureRelease, gestureRelease, gestureEntered, windowsRelease;

    static void reset() {
      sends.set(0);
      globalActions.set(0);
      blockCapture = blockGesture = blockWindows = false;
      cancelGesture = false;
      lastGesture = null;
      captureRelease = new CountDownLatch(1);
      gestureRelease = new CountDownLatch(1);
      gestureEntered = new CountDownLatch(1);
      windowsRelease = new CountDownLatch(1);
    }

    @Implementation(minSdk = 30)
    @Override
    public android.util.SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
      if (blockWindows) await(windowsRelease);
      var registered = super.getWindowsOnAllDisplays();
      var supplied = new android.util.SparseArray<List<AccessibilityWindowInfo>>();
      for (int i = 0; i < registered.size(); i++) {
        var owned = new ArrayList<AccessibilityWindowInfo>();
        for (var canonical : registered.valueAt(i)) {
          var copy = AccessibilityWindowInfo.obtain(canonical);
          // The stock shadow stores an optional bounds override separately from real fields.
          Rect bounds = new Rect();
          canonical.getBoundsInScreen(bounds);
          ((ShadowAccessibilityWindowInfo) Shadow.extract(copy)).setBoundsInScreen(bounds);
          owned.add(copy);
        }
        supplied.put(registered.keyAt(i), owned);
      }
      return supplied;
    }

    @Implementation(minSdk = 30)
    @Override
    protected void takeScreenshot(
        int displayId,
        java.util.concurrent.Executor executor,
        AccessibilityService.TakeScreenshotCallback callback) {
      if (blockCapture) await(captureRelease);
      super.takeScreenshot(displayId, executor, callback);
    }

    @Implementation(minSdk = 24)
    protected boolean dispatchGesture(
        GestureDescription gesture,
        AccessibilityService.GestureResultCallback callback,
        Handler handler) {
      sends.incrementAndGet();
      lastGesture = gesture;
      gestureEntered.countDown();
      if (blockGesture) await(gestureRelease);
      if (cancelGesture) callback.onCancelled(gesture);
      else callback.onCompleted(gesture);
      return true;
    }

    @Implementation
    @Override
    protected boolean performGlobalAction(int action) {
      globalActions.incrementAndGet();
      return super.performGlobalAction(action);
    }
  }
}
