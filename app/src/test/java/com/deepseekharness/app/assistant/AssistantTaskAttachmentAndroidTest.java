package com.deepseekharness.app.assistant;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.control.ControlTaskAuthority;
import com.deepseekharness.app.control.PhoneControlCoordinator;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.*;

/** Real Service/window/permission Activity entry points; Host responses are an isolated port. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = AssistantTaskAttachmentAndroidTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AssistantTaskAttachmentAndroidTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private App app;
  private ExecutorService requests;
  private final List<ServiceController<AssistantService>> services = new ArrayList<>();

  private static AssistantBinding address(String session) {
    return new AssistantBinding("real-host", session, "local-4-real-host", 4);
  }

  private static class Host implements AssistantBackend {
    final AssistantBinding binding;
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final List<String> cancelledTurns = Collections.synchronizedList(new ArrayList<>());
    volatile JSONObject status;
    volatile boolean connected = true;
    volatile Runnable nextMainCurrent;

    Host(AssistantBinding binding, String turn) throws Exception {
      this.binding = binding;
      status =
          new JSONObject()
              .put("hostInstanceId", binding.hostInstanceId())
              .put("sessionId", binding.sessionId())
              .put("taskId", binding.sessionId())
              .put("turnId", turn)
              .put("turnActive", true)
              .put("running", true);
    }

    public boolean current() {
      Runnable callback = nextMainCurrent;
      if (callback != null && Looper.myLooper() == Looper.getMainLooper()) {
        nextMainCurrent = null;
        callback.run();
      }
      return connected;
    }

    public JSONObject call(String method, JSONObject args) throws java.io.IOException {
      calls.add(method);
      if (!method.equals("sessions.status") && !method.equals("sessions.cancel"))
        throw new AssertionError("Attaching must not create or send a conversation: " + method);
      assertEquals(binding.hostInstanceId(), args.optString("hostInstanceId"));
      assertEquals(binding.sessionId(), args.optString("sessionId"));
      if (method.equals("sessions.cancel")) cancelledTurns.add(args.optString("turnId"));
      return status;
    }

    public boolean ownsControlBinding(ControlTaskAuthority.Binding task) {
      return task.owner() == ControlTaskAuthority.Owner.LOCAL_DSH
          && task.connection().equals("native-connection");
    }

    public void returnConversation(AssistantBinding selected) {}
  }

  private static final class DelayedStatus extends Host {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final boolean fail;
    boolean delayed;

    DelayedStatus(String session, boolean fail) throws Exception {
      super(address(session), "17");
      this.fail = fail;
    }

    @Override
    public JSONObject call(String method, JSONObject args) throws java.io.IOException {
      JSONObject captured = super.call(method, args);
      if (method.equals("sessions.status") && !delayed) {
        delayed = true;
        entered.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS))
            throw new java.io.IOException("TEST_STATUS_GATE_TIMEOUT");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new java.io.IOException(interrupted);
        }
        if (fail) throw new java.io.IOException("DELAYED_OLD_STATUS_FAILURE");
      }
      return captured;
    }
  }

  private static ScheduledExecutorService worker(AssistantSessionController selected)
      throws Exception {
    Field field = AssistantSessionController.class.getDeclaredField("worker");
    field.setAccessible(true);
    return (ScheduledExecutorService) field.get(selected);
  }

  private static void drain(AssistantSessionController selected) throws Exception {
    worker(selected).submit(() -> {}).get(2, TimeUnit.SECONDS);
    // A status failure can queue its one cancellation behind the first barrier.
    worker(selected).submit(() -> {}).get(2, TimeUnit.SECONDS);
  }

  private static ControlTaskAuthority.Binding task(Host host, String turn) {
    return new ControlTaskAuthority.Binding(
        ControlTaskAuthority.Owner.LOCAL_DSH, "native-connection", host.binding.sessionId(), turn);
  }

  @Test
  public void delayedFailureAfterExactFinishCannotPauseOrCancelANewControllerTask()
      throws Exception {
    var oldHost = new DelayedStatus("poll-replaced", true);
    var old = new AssistantSessionController(app, oldHost.binding, oldHost, "17");
    AssistantSessionController next = null;
    try {
      assertTrue(oldHost.entered.await(2, TimeUnit.SECONDS));
      // A blocked backend request must not own the controller monitor.
      assertEquals("17", requests.submit(old::snapshot).get(1, TimeUnit.SECONDS).turnId());
      var coordinator = PhoneControlCoordinator.get(app);
      coordinator.begin(task(oldHost, "17"));
      coordinator.completeLocal(task(oldHost, "17"));
      old.detachFinishedTask();
      var nextHost = new Host(oldHost.binding, "18");
      next = new AssistantSessionController(app, nextHost.binding, nextHost, "18");
      var lease = coordinator.begin(task(nextHost, "18"));
      oldHost.release.countDown();
      assertTrue(worker(old).awaitTermination(2, TimeUnit.SECONDS));
      drain(next);
      assertTrue(coordinator.current(lease));
      assertFalse(coordinator.snapshot().paused());
      assertEquals("18", next.snapshot().turnId());
      assertTrue(nextHost.cancelledTurns.isEmpty());
      assertTrue(oldHost.cancelledTurns.isEmpty());
    } finally {
      oldHost.release.countDown();
      old.detachFinishedTask();
      if (next != null) next.detachFinishedTask();
    }
  }

  private void oldPollCannotAffectVerifiedNextTurn(boolean fail) throws Exception {
    var host = new DelayedStatus(fail ? "poll-follow-failure" : "poll-follow-success", fail);
    var selected = new AssistantSessionController(app, host.binding, host, "17");
    try {
      assertTrue(host.entered.await(2, TimeUnit.SECONDS));
      var coordinator = PhoneControlCoordinator.get(app);
      coordinator.begin(task(host, "17"));
      coordinator.completeLocal(task(host, "17"));
      assertTrue(selected.followVerifiedTask("18"));
      var lease = coordinator.begin(task(host, "18"));
      host.status = new JSONObject(host.status.toString()).put("turnId", "18");
      host.release.countDown();
      drain(selected);
      assertEquals("18", selected.snapshot().turnId());
      assertEquals(AssistantSessionState.Phase.RUNNING, selected.snapshot().phase());
      assertTrue(coordinator.current(lease));
      assertFalse(coordinator.snapshot().paused());
      assertTrue(host.cancelledTurns.isEmpty());
    } finally {
      host.release.countDown();
      selected.detachFinishedTask();
    }
  }

  @Test
  public void delayedOldPollFailureCannotPauseAVerifiedNextTurnInTheSameController()
      throws Exception {
    oldPollCannotAffectVerifiedNextTurn(true);
  }

  @Test
  public void delayedOldPollSuccessCannotOverwriteAVerifiedNextTurnInTheSameController()
      throws Exception {
    oldPollCannotAffectVerifiedNextTurn(false);
  }

  @Test
  public void currentOwnedStatusFailureStillPausesAndCancelsItsExactTurn() throws Exception {
    var host = new DelayedStatus("poll-current-failure", true);
    var selected = new AssistantSessionController(app, host.binding, host, "17");
    try {
      assertTrue(host.entered.await(2, TimeUnit.SECONDS));
      var coordinator = PhoneControlCoordinator.get(app);
      var lease = coordinator.begin(task(host, "17"));
      host.release.countDown();
      drain(selected);
      assertFalse(coordinator.current(lease));
      assertTrue(coordinator.snapshot().paused());
      assertEquals("ASSISTANT_STATUS_UNKNOWN", coordinator.snapshot().reason());
      assertEquals(List.of("17"), host.cancelledTurns);
    } finally {
      host.release.countDown();
      selected.detachFinishedTask();
    }
  }

  @Before
  public void before() {
    app = (App) RuntimeEnvironment.getApplication();
    requests = Executors.newSingleThreadExecutor();
    ShadowSettings.setCanDrawOverlays(true);
    Shadow.<ShadowApplication>extract(app)
        .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    Shadow.<ShadowNotificationManager>extract(
            app.getSystemService(android.app.NotificationManager.class))
        .setNotificationsEnabled(true);
    PhoneControlCoordinator.get(app).stop("TEST_RESET");
    PhoneControlCoordinator.get(app).resume();
  }

  @After
  public void after() {
    for (var service : services) service.destroy();
    var selected = AssistantSessionController.active();
    if (selected != null) selected.close();
    requests.shutdownNow();
  }

  private Future<AssistantService.TaskUiResult> request(Host host, String turn) {
    return requests.submit(() -> AssistantService.ensureTask(app, host.binding, turn, host));
  }

  private Intent nextService() throws Exception {
    ShadowApplication shadow = Shadow.extract(app);
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    Intent intent;
    do {
      intent = shadow.getNextStartedService();
      if (intent != null) return intent;
      Thread.sleep(2);
    } while (System.nanoTime() < until);
    throw new AssertionError("A verified physical-phone tool must request the actual service");
  }

  private ServiceController<AssistantService> dispatchService() throws Exception {
    var service = Robolectric.buildService(AssistantService.class, nextService()).create();
    services.add(service);
    service.startCommand(0, 1);
    return service;
  }

  @Test
  public void actualToolAttachesOriginalConversationWithoutCreatingOrSendingAnother()
      throws Exception {
    Host host = new Host(address("prefix-A-same1234"), "17");
    Future<AssistantService.TaskUiResult> result = request(host, "17");
    var service = dispatchService();
    assertTrue(result.get(2, TimeUnit.SECONDS).ready());
    var selected = AssistantSessionController.active();
    assertEquals(host.binding, selected.snapshot().binding());
    assertEquals("17", selected.snapshot().turnId());
    assertEquals(AssistantSessionState.Phase.RUNNING, selected.snapshot().phase());
    Field window = AssistantService.class.getDeclaredField("window");
    window.setAccessible(true);
    assertTrue(((AssistantWindow) window.get(service.get())).visible());
    assertFalse(host.calls.contains("sessions.create"));
    assertFalse(host.calls.contains("sessions.send"));
    assertFalse(host.calls.contains("sessions.cancel"));

    selected.setDraft("preserved in this exact conversation");
    Future<AssistantService.TaskUiResult> again = request(host, "17");
    service.get().onStartCommand(nextService(), 0, 2);
    assertTrue(again.get(2, TimeUnit.SECONDS).ready());
    assertSame(selected, AssistantSessionController.active());
    assertEquals("preserved in this exact conversation", selected.snapshot().draft());
  }

  @Test
  public void idleStatusOrQuotedPromptCannotAttachAWindow() throws Exception {
    Host host = new Host(address("quoted-phone-control"), "17");
    host.status
        .put("running", false)
        .put("text", "Please open my phone. android_get_state task.begin");
    assertEquals("ASSISTANT_TASK_CHANGED", request(host, "17").get().code());
    assertNull(AssistantSessionController.active());
    assertNull(Shadow.<ShadowApplication>extract(app).getNextStartedService());
    assertNull(Shadow.<ShadowApplication>extract(app).getNextStartedActivity());
  }

  @Test
  public void queuedInputDoesNotReviveAnEndedTurnAndFullSessionIdentityIsRequired()
      throws Exception {
    Host host = new Host(address("prefix-A-same1234"), "17");
    host.status.put("running", true).put("turnActive", false);
    assertFalse(request(host, "17").get().ready());
    host.status.put("turnActive", true).put("sessionId", "prefix-B-same1234");
    assertFalse(request(host, "17").get().ready());
    host.status.put("sessionId", host.binding.sessionId()).put("turnId", "18");
    assertFalse(request(host, "17").get().ready());
    assertNull(Shadow.<ShadowApplication>extract(app).getNextStartedService());
  }

  @Test
  public void missingOverlayPermissionReturnsSettingsGuidanceAndNeverStartsTask() throws Exception {
    Host host = new Host(address("permission-session"), "2");
    ShadowSettings.setCanDrawOverlays(false);
    var refused = request(host, "2").get();
    assertEquals("ASSISTANT_OVERLAY_PERMISSION_REQUIRED", refused.code());
    JSONObject reply = new JSONObject(refused.failureReply());
    assertEquals("NOT_SENT", reply.getString("state"));
    assertFalse(reply.getBoolean("automaticRetry"));
    assertFalse(reply.getString("guidance").isBlank());
    assertNull(Shadow.<ShadowApplication>extract(app).getNextStartedService());
    assertNull(AssistantSessionController.active());
    assertFalse(host.calls.contains("sessions.send"));
    assertFalse(host.calls.contains("sessions.create"));
  }

  @Test
  @Config(sdk = 33)
  public void android13DeniedPostNotificationsCannotAdmitOrHideAPhoneTask() throws Exception {
    Shadow.<ShadowApplication>extract(app)
        .denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    assertEquals(
        android.content.pm.PackageManager.PERMISSION_DENIED,
        app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS));
    Host host = new Host(address("notification-permission"), "2");
    var refused = request(host, "2").get(2, TimeUnit.SECONDS);
    assertEquals("ASSISTANT_STOP_NOTIFICATION_REQUIRED", refused.code());
    JSONObject reply = new JSONObject(refused.failureReply());
    assertEquals("NOT_SENT", reply.getString("state"));
    assertFalse(reply.getBoolean("automaticRetry"));
    assertFalse(reply.getString("guidance").isBlank());
    assertEquals("OPEN_NOTIFICATION_SETTINGS", reply.getString("nextAction"));
    Intent settings = AssistantService.notificationSettingsIntent(app);
    assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.getAction());
    assertEquals(
        app.getPackageName(), settings.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE));
    assertNull(Shadow.<ShadowApplication>extract(app).getNextStartedService());
    assertNull(AssistantSessionController.active());
    assertNull(PhoneControlCoordinator.get(app).snapshot().binding());
  }

  @Test
  @Config(sdk = 33)
  public void android13BlockedTaskChannelCannotBeBypassedByGlobalNotificationPermission()
      throws Exception {
    var manager = app.getSystemService(android.app.NotificationManager.class);
    assertEquals(
        android.content.pm.PackageManager.PERMISSION_GRANTED,
        app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS));
    assertTrue(manager.areNotificationsEnabled());
    Host host = new Host(address("blocked-task-category"), "2");
    Shadow.<ShadowNotificationManager>extract(manager).setNotificationsEnabled(false);
    assertFalse(manager.areNotificationsEnabled());
    assertEquals("ASSISTANT_STOP_NOTIFICATION_REQUIRED", request(host, "2").get().code());
    Shadow.<ShadowNotificationManager>extract(manager).setNotificationsEnabled(true);
    manager.createNotificationChannel(
        new android.app.NotificationChannel(
            AssistantService.STOP_CHANNEL,
            "Phone task status",
            android.app.NotificationManager.IMPORTANCE_NONE));
    assertEquals("ASSISTANT_STOP_NOTIFICATION_REQUIRED", request(host, "2").get().code());
    assertEquals(
        android.app.NotificationManager.IMPORTANCE_NONE,
        manager.getNotificationChannel(AssistantService.STOP_CHANNEL).getImportance());
    assertNull(Shadow.<ShadowApplication>extract(app).getNextStartedService());
    assertNull(AssistantSessionController.active());
  }

  @Test
  @Config(sdk = 33)
  public void android13PermissionRevokedWhileHiddenPausesInputAndRestoresARealStopButton()
      throws Exception {
    Host host = new Host(address("revoked-during-task"), "7");
    var admitted = request(host, "7");
    var service = dispatchService();
    assertTrue(admitted.get().ready());
    var coordinator = PhoneControlCoordinator.get(app);
    var task =
        new ControlTaskAuthority.Binding(
            ControlTaskAuthority.Owner.LOCAL_DSH,
            "native-connection",
            host.binding.sessionId(),
            "7");
    var lease = coordinator.begin(task);
    assertTrue(coordinator.observed(lease));
    assertTrue(prepare(host, true).ready());
    Field windowField = AssistantService.class.getDeclaredField("window");
    windowField.setAccessible(true);
    var window = (AssistantWindow) windowField.get(service.get());
    assertFalse(window.visible());
    Shadow.<ShadowApplication>extract(app)
        .denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    assertEquals("ASSISTANT_STOP_NOTIFICATION_REQUIRED", prepare(host, false).code());
    assertTrue(coordinator.snapshot().paused());
    assertEquals("ASSISTANT_STOP_NOTIFICATION_REQUIRED", coordinator.snapshot().reason());
    assertFalse(coordinator.current(lease));
    assertFalse(coordinator.actionAllowed(lease));
    assertTrue(window.visible());
    Field actionsField = AssistantWindow.class.getDeclaredField("actions");
    actionsField.setAccessible(true);
    ViewGroup actions = (ViewGroup) actionsField.get(window);
    assertEquals(View.VISIBLE, actions.getVisibility());
    Field stopField = AssistantWindow.class.getDeclaredField("stopButton");
    stopField.setAccessible(true);
    View stop = (View) stopField.get(window);
    assertSame(actions, stop.getParent());
    assertTrue(stop.isAttachedToWindow());
    // 恢复停止入口会改变窗口宽度：先越过防误触静置期，再两次点按确认。
    Shadow.<ShadowLooper>extract(Looper.getMainLooper())
        .idleFor(
            java.time.Duration.ofMillis(
                com.deepseekharness.app.util.OverlayTouchGuard.SETTLE_MILLIS + 50));
    assertTrue(stop.performClick());
    assertNotNull("The first tap only arms Stop", coordinator.snapshot().binding());
    assertTrue(stop.performClick());
    assertNull(coordinator.snapshot().binding());
  }

  @Test
  @Config(sdk = 33)
  public void android13ChannelRevocationInFinalInputGuardInvalidatesLeaseAndRestoresStop()
      throws Exception {
    Host host = new Host(address("revoked-at-input-guard"), "7");
    var admitted = request(host, "7");
    var service = dispatchService();
    assertTrue(admitted.get().ready());
    var coordinator = PhoneControlCoordinator.get(app);
    var task =
        new ControlTaskAuthority.Binding(
            ControlTaskAuthority.Owner.LOCAL_DSH,
            "native-connection",
            host.binding.sessionId(),
            "7");
    coordinator.begin(task);
    assertTrue(prepare(host, true).ready());
    var lease = coordinator.lease(task);
    assertTrue(coordinator.observed(lease));
    var manager = app.getSystemService(android.app.NotificationManager.class);
    manager.createNotificationChannel(
        new android.app.NotificationChannel(
            AssistantService.STOP_CHANNEL,
            "Phone task status",
            android.app.NotificationManager.IMPORTANCE_NONE));
    assertTrue(manager.areNotificationsEnabled());
    assertFalse(AssistantService.stopChannelCurrent(app, task));
    assertTrue(coordinator.snapshot().paused());
    assertFalse(coordinator.current(lease));
    Field windowField = AssistantService.class.getDeclaredField("window");
    windowField.setAccessible(true);
    assertTrue(((AssistantWindow) windowField.get(service.get())).visible());
  }

  @Test
  @Config(sdk = 33)
  public void android13RevocationBeforeServiceAdmissionDoesNotCreateAHiddenTask() throws Exception {
    Host host = new Host(address("notification-admission-race"), "7");
    var admitted = request(host, "7");
    Intent intent = nextService();
    Shadow.<ShadowApplication>extract(app)
        .denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    var service = Robolectric.buildService(AssistantService.class, intent).create();
    services.add(service);
    service.startCommand(0, 1);
    assertEquals("ASSISTANT_STOP_NOTIFICATION_REQUIRED", admitted.get(2, TimeUnit.SECONDS).code());
    assertNull(AssistantSessionController.active());
    assertNull(PhoneControlCoordinator.get(app).snapshot().binding());
  }

  @Test
  public void
      removingThePostedStopNotificationPausesAnAlreadyHiddenTaskEvenWhenPermissionAndChannelRemainEnabled()
          throws Exception {
    Host host = new Host(address("dismissed-stop-notification"), "7");
    var admitted = request(host, "7");
    var service = dispatchService();
    assertTrue(admitted.get().ready());
    var coordinator = PhoneControlCoordinator.get(app);
    var task =
        new ControlTaskAuthority.Binding(
            ControlTaskAuthority.Owner.LOCAL_DSH,
            "native-connection",
            host.binding.sessionId(),
            "7");
    coordinator.begin(task);
    assertTrue(prepare(host, true).ready());
    var lease = coordinator.lease(task);
    assertTrue(coordinator.observed(lease));
    var manager = app.getSystemService(android.app.NotificationManager.class);
    assertTrue(AssistantService.stopNotificationAvailable(app));
    assertEquals(1, manager.getActiveNotifications().length);
    manager.cancel(com.deepseekharness.app.util.NotificationIds.FLOATING_ASSISTANT);
    assertTrue(AssistantService.stopNotificationAvailable(app));
    assertEquals(0, manager.getActiveNotifications().length);
    assertFalse(AssistantService.stopChannelCurrent(app, task));
    assertTrue(coordinator.snapshot().paused());
    assertFalse(coordinator.current(lease));
    Field windowField = AssistantService.class.getDeclaredField("window");
    windowField.setAccessible(true);
    assertTrue(((AssistantWindow) windowField.get(service.get())).visible());
  }

  @Test
  public void dismissalAfterTheServiceStopGuardCannotBeSilentlyRepostedBySameConversationEnsure()
      throws Exception {
    Host host = new Host(address("dismiss-after-guard"), "7");
    var admitted = request(host, "7");
    var service = dispatchService();
    assertTrue(admitted.get().ready());
    var coordinator = PhoneControlCoordinator.get(app);
    var task =
        new ControlTaskAuthority.Binding(
            ControlTaskAuthority.Owner.LOCAL_DSH,
            "native-connection",
            host.binding.sessionId(),
            "7");
    coordinator.begin(task);
    assertTrue(prepare(host, true).ready());
    var lease = coordinator.lease(task);
    assertTrue(coordinator.observed(lease));
    var manager = app.getSystemService(android.app.NotificationManager.class);
    var repeated = request(host, "7");
    Intent intent = nextService();
    // onStartCommand checks the stop channel before backend.current(). Cancel
    // the actual posted notification at that exact boundary, while retaining
    // a live phone lease. The same-binding branch must not publish it again.
    host.nextMainCurrent =
        () -> manager.cancel(com.deepseekharness.app.util.NotificationIds.FLOATING_ASSISTANT);
    service.get().onStartCommand(intent, 0, 2);
    assertEquals("ASSISTANT_STOP_NOTIFICATION_REQUIRED", repeated.get(2, TimeUnit.SECONDS).code());
    assertEquals(0, manager.getActiveNotifications().length);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
    assertTrue(coordinator.snapshot().paused());
    assertFalse(coordinator.current(lease));
    assertEquals(0, manager.getActiveNotifications().length);
  }

  @Test
  public void nativePauseBetweenRequestAndServiceDispatchCannotBeResumedByModel() throws Exception {
    Host host = new Host(address("pause-race"), "2");
    Future<AssistantService.TaskUiResult> result = request(host, "2");
    Intent intent = nextService();
    PhoneControlCoordinator.get(app).pause("USER_PAUSED");
    var service = Robolectric.buildService(AssistantService.class, intent).create();
    services.add(service);
    service.startCommand(0, 1);
    assertFalse(result.get(2, TimeUnit.SECONDS).ready());
    assertTrue(PhoneControlCoordinator.get(app).snapshot().paused());
    assertNull(AssistantSessionController.active());
  }

  @Test
  public void runningConversationCannotBeReplacedByOneWithTheSameSuffix() throws Exception {
    Host first = new Host(address("prefix-A-same1234"), "17");
    var attached = request(first, "17");
    dispatchService();
    assertTrue(attached.get().ready());
    var selected = AssistantSessionController.active();
    Host second = new Host(address("prefix-B-same1234"), "29");
    assertEquals("ASSISTANT_CONVERSATION_BUSY", request(second, "29").get().code());
    assertSame(selected, AssistantSessionController.active());
    assertNull(Shadow.<ShadowApplication>extract(app).getNextStartedService());
  }

  private AssistantService.TaskUiResult prepare(Host host, boolean observing) throws Exception {
    var task =
        new ControlTaskAuthority.Binding(
            ControlTaskAuthority.Owner.LOCAL_DSH,
            "native-connection",
            host.binding.sessionId(),
            host.status.getString("turnId"));
    var future =
        requests.submit(() -> AssistantService.prepareScreenOperation(app, task, observing));
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!future.isDone() && System.nanoTime() < until) {
      Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
      Thread.sleep(2);
    }
    return future.get(1, TimeUnit.SECONDS);
  }

  @Test
  public void hidingActualWindowInvalidatesOldInputAndPollingCannotCoverTheNextObservation()
      throws Exception {
    Host host = new Host(address("uncovered-search"), "7");
    var attached = request(host, "7");
    var service = dispatchService();
    assertTrue(attached.get().ready());
    Field field = AssistantService.class.getDeclaredField("window");
    field.setAccessible(true);
    AssistantWindow window = (AssistantWindow) field.get(service.get());
    Field params = AssistantWindow.class.getDeclaredField("params");
    params.setAccessible(true);
    var geometry = (android.view.WindowManager.LayoutParams) params.get(window);
    assertEquals(android.view.Gravity.BOTTOM | android.view.Gravity.END, geometry.gravity);
    // 收起胶囊不超过 236dp，不覆盖大半屏幕。
    assertTrue(geometry.width <= Math.round(236 * app.getResources().getDisplayMetrics().density));

    var coordinator = PhoneControlCoordinator.get(app);
    var task =
        new ControlTaskAuthority.Binding(
            ControlTaskAuthority.Owner.LOCAL_DSH,
            "native-connection",
            host.binding.sessionId(),
            "7");
    var old = coordinator.begin(task);
    assertTrue(coordinator.observed(old));
    assertTrue(coordinator.actionAllowed(old));
    var refused = prepare(host, false);
    assertEquals("FRESH_SCREEN_OBSERVATION_REQUIRED", refused.code());
    assertEquals("NOT_SENT", new JSONObject(refused.failureReply()).getString("state"));
    assertFalse(coordinator.current(old));
    assertTrue(coordinator.snapshot().needsObservation());
    assertFalse(window.visible());
    assertTrue(window.taskUiAvailable());
    assertTrue(prepare(host, true).ready());
    var observed = coordinator.begin(task);
    assertFalse(coordinator.actionAllowed(observed));
    assertTrue(coordinator.observed(observed));
    assertTrue(prepare(host, false).ready());
    assertTrue(coordinator.actionAllowed(observed));
    assertFalse(window.visible());

    // Every actual tool may re-ensure the same view; it must not resurrect the
    // removed window between a screenshot and its corresponding input.
    var repeated = request(host, "7");
    service.get().onStartCommand(nextService(), 0, 2);
    assertTrue(repeated.get().ready());
    assertFalse(window.visible());
    assertFalse(host.calls.contains("sessions.send"));
    assertFalse(host.calls.contains("sessions.cancel"));
  }

  @Test
  public void expandedPanelStaysVisibleForObservationAndOnlyInvalidatesOldObservation()
      throws Exception {
    Host host = new Host(address("expanded-observation"), "7");
    var attached = request(host, "7");
    var service = dispatchService();
    assertTrue(attached.get(2, TimeUnit.SECONDS).ready());
    var selected = AssistantSessionController.active();
    var coordinator = PhoneControlCoordinator.get(app);
    var task = task(host, "7");
    coordinator.begin(task);
    Field windowField = AssistantService.class.getDeclaredField("window");
    windowField.setAccessible(true);
    var window = (AssistantWindow) windowField.get(service.get());
    assertTrue(window.visible());

    // 用户在面板可见时展开查看：手机操作不暂停、对话不取消。
    selected.expand();
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
    assertFalse(coordinator.snapshot().paused());
    assertTrue(window.keptVisibleForUser());
    // 读屏前不再收走展开的面板，也不报“需要本机继续”；它按自有窗口整窗排除。
    assertTrue(prepare(host, true).ready());
    assertTrue(window.visible());
    var lease = coordinator.lease(task);
    assertTrue(coordinator.observed(lease));
    assertTrue(prepare(host, false).ready());
    assertTrue(coordinator.actionAllowed(lease));

    // 收起改变几何：旧观察作废、需要重新观察，但仍不暂停。
    selected.collapse();
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
    assertFalse(coordinator.snapshot().paused());
    assertFalse(coordinator.actionAllowed(lease));
    assertFalse(host.calls.contains("sessions.cancel"));
  }

  @Test
  public void expandedPanelDefersTurnEndRetirementUntilCollapsed() throws Exception {
    Host host = new Host(address("expanded-retention"), "7");
    var attached = request(host, "7");
    var service = dispatchService();
    assertTrue(attached.get(2, TimeUnit.SECONDS).ready());
    var selected = AssistantSessionController.active();
    var coordinator = PhoneControlCoordinator.get(app);
    var task = task(host, "7");
    coordinator.begin(task);

    // 展开只改界面：不暂停手机操作，也不取消对话。
    selected.expand();
    assertTrue(selected.snapshot().expanded());
    assertFalse(selected.snapshot().paused());
    assertFalse(coordinator.snapshot().paused());

    // Same order as /app/ui/task-end. The user is reading the expanded panel, so the window stays
    // until they collapse it instead of disappearing under their finger.
    coordinator.completeLocal(task);
    AssistantService.finishLocalTask(app, task);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
    assertSame(selected, AssistantSessionController.active());
    Field windowField = AssistantService.class.getDeclaredField("window");
    windowField.setAccessible(true);
    var window = (AssistantWindow) windowField.get(service.get());
    assertTrue(window.visible());
    selected.collapse();
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
    assertFalse(window.visible());
    assertNull(AssistantSessionController.active());
    assertFalse(host.calls.contains("sessions.cancel"));
  }

  @Test
  public void explicitStopDoesNotRetainTheTaskWindowAfterTurnEndRetirement() throws Exception {
    Host host = new Host(address("stopped-retirement"), "7");
    var attached = request(host, "7");
    var service = dispatchService();
    assertTrue(attached.get(2, TimeUnit.SECONDS).ready());
    var selected = AssistantSessionController.active();
    var coordinator = PhoneControlCoordinator.get(app);
    var task = task(host, "7");
    coordinator.begin(task);
    selected.stop();
    coordinator.completeLocal(task);
    AssistantService.finishLocalTask(app, task);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
    assertNull(AssistantSessionController.active());
    assertNotNull(service.get());
  }

  @Test
  public void exactTaskFinishClosesViewWithoutCancellingTheModelOrOtherConversations()
      throws Exception {
    Host host = new Host(address("finish-original"), "7");
    var attached = request(host, "7");
    dispatchService();
    assertTrue(attached.get().ready());
    var coordinator = PhoneControlCoordinator.get(app);
    var task =
        new ControlTaskAuthority.Binding(
            ControlTaskAuthority.Owner.LOCAL_DSH,
            "native-connection",
            host.binding.sessionId(),
            "7");
    coordinator.begin(task);
    var wrong =
        new ControlTaskAuthority.Binding(task.owner(), task.connection(), "finish-other", "7");
    AssistantService.finishLocalTask(app, wrong);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
    assertNotNull(AssistantSessionController.active());
    coordinator.completeLocal(task);
    AssistantService.finishLocalTask(app, task);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
    assertNull(AssistantSessionController.active());
    assertFalse(host.calls.contains("sessions.cancel"));
    assertNull(coordinator.snapshot().binding());
    assertThrows(IllegalStateException.class, () -> coordinator.begin(task));
  }

  @Test
  public void retiringAnIdleConversationViewCannotStopAnotherConversationsPhoneLease()
      throws Exception {
    Host host = new Host(address("idle-old-conversation"), "7");
    host.status.put("running", false).put("turnActive", false);
    var selected = new AssistantSessionController(app, host.binding, host);
    Field worker = AssistantSessionController.class.getDeclaredField("worker");
    worker.setAccessible(true);
    ((ScheduledExecutorService) worker.get(selected)).submit(() -> {}).get(2, TimeUnit.SECONDS);
    assertTrue(selected.snapshot().terminalKnown());
    var coordinator = PhoneControlCoordinator.get(app);
    var next =
        coordinator.begin(
            new ControlTaskAuthority.Binding(
                ControlTaskAuthority.Owner.LOCAL_DSH,
                "native-connection",
                "another-conversation",
                "8"));
    selected.close();
    assertTrue(coordinator.current(next));
    assertFalse(host.calls.contains("sessions.cancel"));
  }

  @Test
  public void permissionActivityHasNoConversationPickerAndGrantDoesNotExecuteAnything()
      throws Exception {
    ShadowSettings.setCanDrawOverlays(false);
    var host =
        Robolectric.buildActivity(AssistantActivity.class, AssistantService.permissionIntent(app));
    host.get().setTheme(R.style.Theme_DeepseekHarness);
    try {
      host.setup().visible();
      String text = viewText(host.get().findViewById(android.R.id.content));
      assertFalse(text.contains("New conversation"));
      assertFalse(text.contains("新建会话"));
      assertNull(AssistantSessionController.active());
      assertNull(Shadow.<ShadowApplication>extract(app).getNextStartedService());
      ShadowSettings.setCanDrawOverlays(true);
      host.pause().resume();
      assertNull(AssistantSessionController.active());
      assertNull(Shadow.<ShadowApplication>extract(app).getNextStartedService());
    } finally {
      host.pause().stop().destroy();
    }
  }

  private static String viewText(View view) {
    StringBuilder text = new StringBuilder();
    if (view instanceof TextView label) text.append(label.getText()).append('\n');
    if (view instanceof ViewGroup group)
      for (int index = 0; index < group.getChildCount(); index++)
        text.append(viewText(group.getChildAt(index)));
    return text.toString();
  }
}
