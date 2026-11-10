package com.deepseekharness.app.assistant;

import static org.junit.Assert.*;

import android.content.Context;
import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Looper;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.ForegroundActivity;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.control.ControlTaskAuthority;
import com.deepseekharness.app.control.PhoneControlCoordinator;
import com.deepseekharness.app.control.ScreenControlConfirmation;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.InstallationIdentity;
import com.deepseekharness.app.core.ScreenRuntimeIdentity;
import com.deepseekharness.app.runtime.RuntimeToolsRegistryAndroidTest;
import com.deepseekharness.app.screen.ScreenTargetResolver;
import com.deepseekharness.app.util.AppInstallIdentity;
import com.deepseekharness.app.util.BridgeLifecycle;
import com.deepseekharness.app.util.ScreenSessionGrant;
import com.deepseekharness.app.util.ScreenTarget;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.*;

/** Real local HTTP authorization and Service overlay; foreground metadata and host syscalls are ports. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 33,
    application = LocalTaskConfirmationAndroidTest.App.class,
    instrumentedPackages = {"com.deepseekharness.app.backup"},
    shadows = {
      LocalTaskConfirmationAndroidTest.Targets.class,
      LocalTaskConfirmationAndroidTest.WindowMetadata.class,
      RuntimeToolsRegistryAndroidTest.HostFileSystem.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class LocalTaskConfirmationAndroidTest {
  public static final class App extends DshaApp {
    private HarnessController controller;

    @Override
    public void onCreate() {}

    @Override
    public HarnessController harnessController() {
      if (controller == null) controller = new RunningController(this);
      return controller;
    }
  }

  private static final class RunningController extends HarnessController {
    RunningController(Context context) {
      super(context);
    }

    @Override
    public long getWebGeneration() {
      return 4;
    }

    @Override
    public boolean isWebRunning() {
      return true;
    }

    @Override
    public boolean isStarting() {
      return false;
    }

    @Override
    public boolean isStopping() {
      return false;
    }

    @Override
    public boolean isUserStopped() {
      return false;
    }
  }

  @Implements(value = DshaAccessibilityService.class, isInAndroidSdk = false)
  public static final class Targets extends WindowMetadata {
    static volatile ScreenTarget current;
    static volatile boolean promptStillAttached;
    static volatile AssistantConfirmationWindow window;

    @Implementation
    protected static ScreenTarget observeTarget(int display) {
      try {
        promptStillAttached |= window != null && get(window, "view") != null;
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
      return current;
    }
  }

  /** Android supplies window facts; production resolver/region/authority logic remains intact. */
  @Implements(AccessibilityService.class)
  public static class WindowMetadata extends ShadowAccessibilityService {
    static java.util.List<AccessibilityWindowInfo> windows = java.util.List.of();

    @Implementation
    protected SparseArray<java.util.List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
      var result = new SparseArray<java.util.List<AccessibilityWindowInfo>>();
      var copies = new java.util.ArrayList<AccessibilityWindowInfo>();
      for (var window : windows) copies.add(AccessibilityWindowInfo.obtain(window));
      result.put(0, copies);
      return result;
    }
  }

  private App app;
  private HttpShellService bridge;
  private BridgeLifecycle lifecycle;
  private long generation;
  private Object run, scope;
  private ScreenTarget target;
  private ControlTaskAuthority.Binding binding;
  private ControlTaskAuthority.Lease lease;
  private ExecutorService requests;
  private ServiceController<AssistantService> service;
  private AssistantConfirmationWindow window;

  private static Field field(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static Object get(Object owner, String name) throws Exception {
    return field(owner.getClass(), name).get(owner);
  }

  private static Object call(Object owner, String name, Object... args) throws Exception {
    for (Method method : owner.getClass().getDeclaredMethods()) {
      if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
      method.setAccessible(true);
      try {
        return method.invoke(owner, args);
      } catch (InvocationTargetException failure) {
        if (failure.getCause() instanceof Exception error) throw error;
        throw failure;
      }
    }
    throw new NoSuchMethodException(name);
  }

  private void idle() {
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
  }

  @Before
  public void before() throws Exception {
    app = (App) RuntimeEnvironment.getApplication();
    new com.deepseekharness.app.core.DeviceGrantsTest()
        .isolateProcessStateForIndependentPreferenceFixtures();
    WindowMetadata.windows = java.util.List.of();
    // Robolectric has no installed APK native directory. The real controller constructor still
    // records that directory even though this fixture never starts a guest or loads a library.
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    // Reuse the existing real NOFOLLOW host-files adapter for Linux descriptor syscalls. The
    // production installation store still creates, validates and reopens its own private record.
    String installed = InstallationIdentity.loadOrCreate(app);
    assertEquals(installed, app.installationId());
    assertEquals(installed, InstallationIdentity.loadOrCreate(app));
    assertEquals(
        installed,
        AppInstallIdentity.decode(
            java.nio.file.Files.readAllBytes(
                new java.io.File(app.getFilesDir(), AppInstallIdentity.FILE_NAME).toPath())));
    ShadowSettings.setCanDrawOverlays(true);
    Shadow.<ShadowApplication>extract(app)
        .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    Shadow.<ShadowNotificationManager>extract(
            app.getSystemService(android.app.NotificationManager.class))
        .setNotificationsEnabled(true);
    var coordinator = PhoneControlCoordinator.get(app);
    coordinator.stop("TEST_RESET");
    coordinator.resume();
    requests = Executors.newSingleThreadExecutor();
    bridge = new HttpShellService(app);
    lifecycle = (BridgeLifecycle) field(HttpShellService.class, "LIFECYCLE").get(null);
    generation = lifecycle.beginStart();
    assertTrue(generation > 0);
    var runType = Class.forName(HttpShellService.class.getName() + "$BridgeRun");
    var constructor = runType.getDeclaredConstructor(long.class);
    constructor.setAccessible(true);
    run = constructor.newInstance(generation);
    field(HttpShellService.class, "activeRun").set(bridge, run);
    field(HttpShellService.class, "running").set(bridge, true);
    var grant = (ScreenSessionGrant) field(HttpShellService.class, "uiGrant").get(null);
    grant.setPaused(false);
    freshScope();
    target = new ScreenTarget(0, 42, "com.android.settings");
    Targets.current = target;
    Targets.promptStillAttached = false;
    binding =
        new ControlTaskAuthority.Binding(
            ControlTaskAuthority.Owner.LOCAL_DSH,
            get(run, "runId") + ":" + ScreenRuntimeIdentity.epoch(app).instanceId,
            "original-full-conversation",
            "17");
    lease = coordinator.begin(binding);
    service = Robolectric.buildService(AssistantService.class).create();
    window = (AssistantConfirmationWindow) get(service.get(), "confirmations");
    Targets.window = window;
    assertNull(ForegroundActivity.current());
    assertFalse(com.deepseekharness.app.OverlayController.enabled(app));
  }

  @After
  public void after() throws Exception {
    try {
      if (bridge != null) field(HttpShellService.class, "running").set(bridge, false);
      if (app != null) app.capabilityBroker().revokeAll();
      if (requests != null) {
        requests.shutdownNow();
        assertTrue(requests.awaitTermination(2, TimeUnit.SECONDS));
      }
      idle();
      if (service != null) service.destroy();
      idle();
    } finally {
      try {
        if (run != null) {
          ((ExecutorService) get(run, "pool")).shutdownNow();
          ((ExecutorService) get(run, "cancellationPool")).shutdownNow();
        }
      } finally {
        // Release only the generation acquired by this fixture, including a failed @Before.
        if (lifecycle != null && generation > 0) lifecycle.finish(generation);
      }
    }
  }

  private CapabilityRequest capability(String operation, boolean bound) throws Exception {
    var request = (CapabilityRequest) call(bridge, "screenRequest", scope, operation, target);
    if (bound) PhoneControlCoordinator.get(app).bindRequest(request, lease);
    return request;
  }

  /** A policy change fences the old HTTP scope; the next actual request captures new revisions. */
  private void freshScope() throws Exception {
    var runType = Class.forName(HttpShellService.class.getName() + "$BridgeRun");
    var scopeType = Class.forName(HttpShellService.class.getName() + "$RequestScope");
    var constructor =
        scopeType.getDeclaredConstructor(runType, long.class, String.class, long.class, long.class);
    constructor.setAccessible(true);
    var grant = (ScreenSessionGrant) field(HttpShellService.class, "uiGrant").get(null);
    scope =
        constructor.newInstance(
            run,
            grant.revision(),
            "actual-request-" + java.util.UUID.randomUUID(),
            new DeviceGrants(app).consentPolicy().revision(),
            4L);
  }

  private Future<String> authorize(CapabilityRequest request) {
    return requests.submit(
        () ->
            String.valueOf(
                call(
                    bridge,
                    "screenAuthorization",
                    scope,
                    request.action,
                    target.packageName,
                    target,
                    request)));
  }

  private ViewGroup awaitPrompt(Future<?> result) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < until) {
      idle();
      ViewGroup view = (ViewGroup) get(window, "view");
      Object pending = get(bridge, "pendingConfirm");
      if (view != null && pending != null && get(pending, "taskPrompt") != null) return view;
      if (result.isDone())
        fail("Request finished before a native confirmation was visible: " + result.get());
      Thread.sleep(2);
    }
    throw new AssertionError("The task prompt must appear without a foreground DSHA Activity");
  }

  private String await(Future<String> result) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!result.isDone() && System.nanoTime() < until) {
      idle();
      Thread.sleep(2);
    }
    idle();
    return result.get(1, TimeUnit.SECONDS);
  }

  private AccessibilityWindowInfo window(
      int id, int type, int layer, Rect bounds, String pkg, boolean focused) {
    var window = AccessibilityWindowInfo.obtain();
    ShadowAccessibilityWindowInfo metadata = Shadow.extract(window);
    metadata.setId(id);
    metadata.setDisplayId(0);
    metadata.setType(type);
    metadata.setLayer(layer);
    metadata.setFocused(focused);
    metadata.setActive(true);
    metadata.setBoundsInScreen(bounds);
    var root = AccessibilityNodeInfo.obtain(new View(app));
    root.setPackageName(pkg);
    root.setBoundsInScreen(bounds);
    root.setVisibleToUser(true);
    Shadow.<ShadowAccessibilityNodeInfo>extract(root).setAccessibilityWindowInfo(window);
    metadata.setRoot(root);
    return window;
  }

  @Test
  public void launchCarryingTheCurrentTaskIdentityUsesTheTaskConfirmationWindow() throws Exception {
    var request =
        (CapabilityRequest)
            call(
                bridge,
                "screenRequestEvidence",
                scope,
                "launch",
                new com.deepseekharness.app.bridge.AppLaunchTargetEvidence("com.android.settings"));
    String path =
        "/app/launch?pkg=com.android.settings&taskSessionId=original-full-conversation&turnId=17";
    call(bridge, "bindLaunchToCurrentTask", path, scope, request);
    assertFalse(
        "绑定到当前这一轮",
        com.deepseekharness.app.control.ScreenAuthorityBindings.identity(request).isEmpty());
    var other =
        (CapabilityRequest)
            call(
                bridge,
                "screenRequestEvidence",
                scope,
                "launch",
                new com.deepseekharness.app.bridge.AppLaunchTargetEvidence("com.android.settings"));
    call(
        bridge,
        "bindLaunchToCurrentTask",
        "/app/launch?pkg=com.android.settings&taskSessionId=someone-else&turnId=17",
        scope,
        other);
    assertTrue(
        "别的会话不能借用当前任务",
        com.deepseekharness.app.control.ScreenAuthorityBindings.identity(other).isEmpty());
  }

  @Test
  public void allowForThisTurnSkipsLaterPromptsForTheSameAppUntilTheTurnEnds() throws Exception {
    var coordinator = PhoneControlCoordinator.get(app);
    var turnGrants = coordinator.turnGrants();
    turnGrants.clear();
    var first = capability("dump", true);
    var result = authorize(first);
    var prompt = awaitPrompt(result);
    assertEquals(4, prompt.getChildCount());
    assertTrue(prompt.getChildAt(3).performClick());
    assertEquals("ALLOWED", await(result));
    // 同一轮同一应用：不再弹窗。
    var second = capability("dump", true);
    assertEquals("ALLOWED", await(authorize(second)));
    assertNull("没有新弹窗", get(window, "view"));
    // 本轮结束后重新确认。
    coordinator.completeLocal(binding);
    assertFalse(
        turnGrants.allows(
            binding.identity(),
            target.packageName,
            false,
            new DeviceGrants(app).screenPolicy().revision));
  }

  @Test
  public void globalAllowWhileIdleAdmitsFreshBoundScreenReadWithoutNativePrompt() throws Exception {
    var coordinator = PhoneControlCoordinator.get(app);
    coordinator.stop("IDLE");
    assertTrue(new DeviceGrants(app).setGlobalAllowed(true));
    freshScope();
    lease = coordinator.begin(binding);
    for (String operation : new String[] {"dump", "screenshot"}) {
      var request = capability(operation, true);
      assertEquals("ALLOWED", await(authorize(request)));
      assertNull(get(window, "view"));
      assertNull(get(bridge, "pendingConfirm"));
      assertNotNull(app.capabilityBroker().claimDispatch(request));
      assertNull(app.capabilityBroker().claimDispatch(request));
    }
  }

  @Test
  public void globalAllowChangeFencesOldTaskButFreshRequestWorksAndOffRestoresManualConfirmation()
      throws Exception {
    var coordinator = PhoneControlCoordinator.get(app);
    var oldRequest = capability("dump", true);
    assertTrue(app.capabilityBroker().authorize(oldRequest));
    var oldPermit = app.capabilityBroker().claimDispatch(oldRequest);
    assertNotNull(oldPermit);
    var oldLease = lease;
    assertTrue(new DeviceGrants(app).setGlobalAllowed(true));
    assertFalse(oldPermit.isCurrent());
    assertFalse(coordinator.current(oldLease));
    assertEquals("UNAVAILABLE", await(authorize(oldRequest)));
    freshScope();
    lease = coordinator.begin(binding);
    var allowed = capability("dump", true);
    assertEquals("ALLOWED", await(authorize(allowed)));
    assertNull(get(window, "view"));
    var allowedPermit = app.capabilityBroker().claimDispatch(allowed);
    assertNotNull(allowedPermit);

    assertTrue(new DeviceGrants(app).setGlobalAllowed(false));
    assertFalse(allowedPermit.isCurrent());
    freshScope();
    lease = coordinator.begin(binding);
    var manual = capability("dump", true);
    var result = authorize(manual);
    var prompt = awaitPrompt(result);
    assertFalse(app.capabilityBroker().authorized(manual));
    prompt.getChildAt(1).performClick();
    assertEquals("ALLOWED", await(result));
    assertNotNull(app.capabilityBroker().claimDispatch(manual));
  }

  @Test
  public void globalConsentChangePreservesExplicitUserPause() throws Exception {
    var coordinator = PhoneControlCoordinator.get(app);
    HttpShellService.setScreenPaused(app, true);
    assertTrue(new DeviceGrants(app).setGlobalAllowed(true));
    freshScope();
    assertTrue(coordinator.snapshot().paused());
    assertEquals("LOCAL_USER_PAUSE", coordinator.snapshot().reason());
    assertTrue(HttpShellService.screenPaused());
    var request = capability("dump", false);
    assertEquals("UNAVAILABLE", await(authorize(request)));
    var failure = assertThrows(IllegalStateException.class, () -> coordinator.begin(binding));
    assertEquals("SCREEN_CONTROL_PAUSED", failure.getMessage());
    assertNull(app.capabilityBroker().claimDispatch(request));
  }

  @Test
  public void screenPolicyChangeWhileIdleAdmitsFreshBoundReadWithoutNativePrompt()
      throws Exception {
    var coordinator = PhoneControlCoordinator.get(app);
    coordinator.stop("IDLE");
    assertTrue(
        new DeviceGrants(app)
            .setScreenPolicy(
                com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.ALLOW_ALL,
                java.util.Map.of()));
    freshScope();
    lease = coordinator.begin(binding);
    var request = capability("dump", true);
    assertEquals("ALLOWED", await(authorize(request)));
    assertNull(get(window, "view"));
    assertNotNull(app.capabilityBroker().claimDispatch(request));
    assertNull(app.capabilityBroker().claimDispatch(request));
  }

  @Test
  public void screenPolicyChangePreservesExplicitUserPause() throws Exception {
    var coordinator = PhoneControlCoordinator.get(app);
    HttpShellService.setScreenPaused(app, true);
    assertTrue(
        new DeviceGrants(app)
            .setScreenPolicy(
                com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.ALLOW_ALL,
                java.util.Map.of()));
    freshScope();
    assertTrue(coordinator.snapshot().paused());
    assertEquals("LOCAL_USER_PAUSE", coordinator.snapshot().reason());
    assertTrue(HttpShellService.screenPaused());
    var request = capability("dump", false);
    assertEquals("UNAVAILABLE", await(authorize(request)));
    var failure = assertThrows(IllegalStateException.class, () -> coordinator.begin(binding));
    assertEquals("SCREEN_CONTROL_PAUSED", failure.getMessage());
    assertNull(app.capabilityBroker().claimDispatch(request));
  }

  @Test
  public void
      realResolverObservesFocusedAppWithImeWithoutCancelingTaskOrAllowingKeyboardCoordinates()
          throws Exception {
    var application =
        window(
            42,
            AccessibilityWindowInfo.TYPE_APPLICATION,
            1,
            new Rect(0, 0, 1000, 1800),
            app.getPackageName(),
            true);
    var keyboard =
        window(
            90,
            AccessibilityWindowInfo.TYPE_INPUT_METHOD,
            2,
            new Rect(0, 1100, 1000, 1800),
            "com.example.keyboard",
            false);
    WindowMetadata.windows = java.util.List.of(application, keyboard);
    var platform = Robolectric.buildService(DshaAccessibilityService.class).create();
    try (var observed = ScreenTargetResolver.resolve(platform.get(), 0)) {
      assertNotNull(observed.target);
      assertEquals(42, observed.target.windowId);
      assertEquals(app.getPackageName(), observed.target.packageName);
      assertEquals("READABLE", observed.diagnostic);
      assertTrue(PhoneControlCoordinator.get(app).current(lease));
      assertFalse(PhoneControlCoordinator.get(app).snapshot().paused());
      assertEquals(
          new com.deepseekharness.app.util.ScreenWindowRegion.Bounds(0, 0, 1000, 1100),
          observed.region.content());
      assertTrue(observed.region.containsTrajectory(500, 500, 500, 1000, 1));
      assertFalse(observed.region.containsTrajectory(500, 1200, 500, 1200, 1));
      assertFalse(observed.region.containsTrajectory(500, 1000, 500, 1200, 1));
      assertFalse(observed.region.containsTrajectory(500, 1099, 500, 1099, 1));
    } finally {
      platform.destroy();
      WindowMetadata.windows = java.util.List.of();
      application.recycle();
      keyboard.recycle();
    }
  }

  @Test
  public void backgroundDumpAndScreenshotUseOnlyTaskOverlayAndGrantOnlyAfterItIsRemoved()
      throws Exception {
    for (String operation : new String[] {"dump", "screenshot"}) {
      var request = capability(operation, true);
      var result = authorize(request);
      var prompt = awaitPrompt(result);
      assertTrue(prompt.isAttachedToWindow());
      assertNull(ShadowDialog.getLatestDialog());
      assertEquals(
          1,
          app.getSystemService(android.app.NotificationManager.class)
              .getActiveNotifications()
              .length);
      assertFalse(app.capabilityBroker().authorized(request));
      assertTrue(prompt.getChildAt(1).performClick());
      assertEquals("ALLOWED", await(result));
      assertFalse(prompt.isAttachedToWindow());
      assertFalse(Targets.promptStillAttached);
      assertNotNull(app.capabilityBroker().claimDispatch(request));
      assertNull(app.capabilityBroker().claimDispatch(request));
    }
  }

  @Test
  public void activeTaskDoesNotCaptureAnUnboundLegacyCommandConfirmation() throws Exception {
    var request = capability("dump", false);
    var result = authorize(request);
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    Object pending = null;
    while (System.nanoTime() < until) {
      idle();
      pending = get(bridge, "pendingConfirm");
      if (pending != null && get(pending, "allow") != null) break;
      Thread.sleep(2);
    }
    assertNotNull(pending);
    assertNotNull(get(pending, "allow"));
    assertNull(get(window, "view"));
    assertNull(get(pending, "taskLease"));
    Object confirmation = get(pending, "request");
    bridge.resolveConfirm(false, (String) get(confirmation, "identity"));
    assertEquals("DENIED", await(result));
    assertNull(app.capabilityBroker().claimDispatch(request));
  }

  @Test
  public void staleTurnCannotOpenTaskPromptOrFallBackToLegacyApproval() throws Exception {
    var request = capability("dump", true);
    var coordinator = PhoneControlCoordinator.get(app);
    coordinator.completeLocal(binding);
    coordinator.begin(
        new ControlTaskAuthority.Binding(
            binding.owner(), binding.connection(), binding.taskSessionId(), "18"));
    assertEquals("UNAVAILABLE", await(authorize(request)));
    assertNull(get(window, "view"));
    assertNull(get(bridge, "pendingConfirm"));
    assertNull(app.capabilityBroker().claimDispatch(request));
  }

  @Test
  public void missingTaskHandlerRefusesWithoutOpeningAnInvisibleLegacyPrompt() throws Exception {
    PhoneControlCoordinator.get(app).setConfirmationHandler(null);
    var request = capability("dump", true);
    assertEquals("UNAVAILABLE", await(authorize(request)));
    assertNull(get(window, "view"));
    assertNull(get(bridge, "pendingConfirm"));
  }

  @Test
  public void denyingNativePromptCannotAuthorizeOrReplayTheRequest() throws Exception {
    var request = capability("screenshot", true);
    var result = authorize(request);
    var prompt = awaitPrompt(result);
    assertTrue(prompt.getChildAt(2).performClick());
    assertEquals("DENIED", await(result));
    assertFalse(prompt.isAttachedToWindow());
    assertNull(app.capabilityBroker().claimDispatch(request));
    prompt.getChildAt(1).performClick();
    idle();
    assertNull(app.capabilityBroker().claimDispatch(request));
  }

  @Test
  public void targetChangedWhileConfirmingRejectsTheOriginalAction() throws Exception {
    var request = capability("dump", true);
    var result = authorize(request);
    var prompt = awaitPrompt(result);
    Targets.current = new ScreenTarget(0, 43, "com.other.application");
    prompt.getChildAt(1).performClick();
    ExecutionException failure = assertThrows(ExecutionException.class, () -> await(result));
    assertTrue(failure.getCause() instanceof DshaAccessibilityService.TargetChangedException);
    assertFalse(prompt.isAttachedToWindow());
    assertNull(app.capabilityBroker().claimDispatch(request));
  }

  @Test
  public void pauseWhileWaitingClosesThePromptAndLateAllowCannotResume() throws Exception {
    var request = capability("dump", true);
    var result = authorize(request);
    var prompt = awaitPrompt(result);
    PhoneControlCoordinator.get(app).pause("USER_PAUSED");
    assertFalse(PhoneControlCoordinator.get(app).current(lease));
    assertFalse(PhoneControlCoordinator.get(app).actionAllowed(lease));
    // PAUSED looper mode does not advance the native prompt's 100 ms authority watcher.
    // Advance that actual scheduled check before awaiting its cancellation callback.
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    assertNotEquals("ALLOWED", await(result));
    assertNull(get(window, "view"));
    assertFalse(prompt.isAttachedToWindow());
    prompt.getChildAt(1).performClick();
    idle();
    assertTrue(PhoneControlCoordinator.get(app).snapshot().paused());
    assertNull(app.capabilityBroker().claimDispatch(request));
  }

  @Test
  public void canceledPromptHandleAndDetachedButtonsCannotAnswerItsSuccessor() throws Exception {
    var first = capability("dump", true);
    var result = authorize(first);
    var oldPrompt = awaitPrompt(result);
    var pending = get(bridge, "pendingConfirm");
    var oldHandle = (ScreenControlConfirmation.Prompt) get(pending, "taskPrompt");
    app.capabilityBroker().cancel(first);
    assertNotEquals("ALLOWED", await(result));
    var second = capability("screenshot", true);
    var next = authorize(second);
    var newPrompt = awaitPrompt(next);
    oldHandle.close();
    oldPrompt.getChildAt(1).performClick();
    oldPrompt.getChildAt(2).performClick();
    idle();
    assertSame(newPrompt, get(window, "view"));
    assertFalse(next.isDone());
    assertFalse(app.capabilityBroker().authorized(second));
    newPrompt.getChildAt(2).performClick();
    assertEquals("DENIED", await(next));
    assertNull(app.capabilityBroker().claimDispatch(first));
    assertNull(app.capabilityBroker().claimDispatch(second));
  }

  @Test
  public void nativePromptTimeoutClosesTheActualWindowWithoutDispatch() throws Exception {
    var request = capability("dump", true);
    var result = authorize(request);
    var prompt = awaitPrompt(result);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60));
    assertNotEquals("ALLOWED", await(result));
    assertNull(get(window, "view"));
    assertFalse(prompt.isAttachedToWindow());
    prompt.getChildAt(1).performClick();
    assertNull(app.capabilityBroker().claimDispatch(request));
  }

  @Test
  public void requestDeadlineWakesTheBlockingWaiterAndClosesOnlyItsPrompt() throws Exception {
    var original = capability("dump", false);
    var request =
        new CapabilityRequest(
            original.requestId,
            original.capability,
            original.action,
            original.runtime,
            original.installInstanceId,
            original.bridgeRunId,
            original.target,
            original.policyRevision,
            original.authorityEpoch,
            android.os.SystemClock.elapsedRealtime() + 500);
    PhoneControlCoordinator.get(app).bindRequest(request, lease);
    var result = authorize(request);
    var prompt = awaitPrompt(result);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    assertNotEquals("ALLOWED", await(result));
    assertNull(get(window, "view"));
    assertFalse(prompt.isAttachedToWindow());
    assertNull(get(bridge, "pendingConfirm"));
    assertNull(app.capabilityBroker().claimDispatch(request));
  }

  @Test
  public void unavailableOrRevokedOverlayIsUnansweredRatherThanAUserRejection() throws Exception {
    ShadowSettings.setCanDrawOverlays(false);
    var unavailable = capability("dump", true);
    assertEquals("NO_ANSWER", await(authorize(unavailable)));
    assertNull(get(window, "view"));
    assertNull(app.capabilityBroker().claimDispatch(unavailable));

    ShadowSettings.setCanDrawOverlays(true);
    var revoked = capability("screenshot", true);
    var result = authorize(revoked);
    var prompt = awaitPrompt(result);
    ShadowSettings.setCanDrawOverlays(false);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    assertEquals("NO_ANSWER", await(result));
    assertFalse(prompt.isAttachedToWindow());
    assertNull(app.capabilityBroker().claimDispatch(revoked));
  }

  @Test
  public void cancellationBeforeMainThreadShowCannotCloseTheNextQueuedPrompt() throws Exception {
    var oldAnswers = new java.util.ArrayList<ScreenControlConfirmation.Decision>();
    var nextAnswers = new java.util.ArrayList<ScreenControlConfirmation.Decision>();
    var old = window.show(capability("dump", true), target, () -> true, oldAnswers::add);
    old.close();
    var next = window.show(capability("screenshot", true), target, () -> true, nextAnswers::add);
    idle();
    assertEquals(1, oldAnswers.size());
    assertNull(oldAnswers.get(0).allowed());
    var prompt = (ViewGroup) get(window, "view");
    assertNotNull(prompt);
    old.close();
    idle();
    assertSame(prompt, get(window, "view"));
    assertTrue(nextAnswers.isEmpty());
    next.close();
    idle();
    assertEquals(1, nextAnswers.size());
    assertNull(nextAnswers.get(0).allowed());
    assertNull(get(window, "view"));
  }

  @Test
  public void exactRemovedPromptWindowMayDrainButAnotherOwnedWindowCannotBeExcused()
      throws Exception {
    var answer =
        new AtomicReference<java.util.function.Consumer<ScreenControlConfirmation.Decision>>();
    PhoneControlCoordinator.get(app)
        .setConfirmationHandler(
            (request, target, current, completion) -> {
              answer.set(completion);
              return () -> {};
            });
    var request = capability("dump", true);
    var result = authorize(request);
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (answer.get() == null && System.nanoTime() < until) Thread.sleep(2);
    assertNotNull(answer.get());
    Targets.current = new ScreenTarget(0, 701, app.getPackageName());
    answer.get().accept(new ScreenControlConfirmation.Decision(true, 701));
    idle();
    assertFalse(result.isDone());
    assertFalse(app.capabilityBroker().authorized(request));
    Targets.current = new ScreenTarget(0, 702, app.getPackageName());
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
    ExecutionException failure = assertThrows(ExecutionException.class, () -> await(result));
    assertTrue(failure.getCause() instanceof DshaAccessibilityService.TargetChangedException);
    assertNull(app.capabilityBroker().claimDispatch(request));
  }
}
