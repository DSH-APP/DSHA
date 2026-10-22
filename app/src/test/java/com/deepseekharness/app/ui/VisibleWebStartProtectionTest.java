package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import androidx.fragment.app.FragmentActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.ForegroundActivity;
import com.deepseekharness.app.HarnessService;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.core.WebStopCoordinator;
import com.deepseekharness.app.util.WebLifecycle;
import com.deepseekharness.app.util.WebStartProtection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;

/** Actual UI dispatcher, FGS promotion and CPU lock; controller/HTTP are explicit test ports. */
@RunWith(VisibleWebStartProtectionTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = VisibleWebStartProtectionTest.App.class,
    shadows = {
      VisibleWebStartProtectionTest.Controller.class,
      VisibleWebStartProtectionTest.Foreground.class,
      VisibleWebStartProtectionTest.Bridge.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class VisibleWebStartProtectionTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  public static final class App extends DshaApp {
    @Override
    public void onCreate() {
      RuntimeTasks.initialize(this);
    }
  }

  @Implements(value = ForegroundActivity.class, isInAndroidSdk = false)
  public static final class Foreground {
    static FragmentActivity visible;

    @Implementation
    protected static FragmentActivity current() {
      return visible;
    }

    @Implementation
    protected static boolean isResumed(FragmentActivity activity) {
      return activity != null
          && activity == visible
          && activity
              .getLifecycle()
              .getCurrentState()
              .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
    }
  }

  @Implements(value = HttpShellService.class, isInAndroidSdk = false)
  public static final class Bridge {
    static int acquired;

    @Implementation
    protected static HttpShellService.Lease acquire(Context context) {
      acquired++;
      return null;
    }
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Controller {
    static HarnessController value;
    static WebLifecycle lifecycle;
    static WebStartProtection protection;
    static int starts;
    static boolean demand, unresolved;
    static WebStartProtection.Ticket recoveryTicket;
    static int fencedStops;

    @Implementation
    protected static HarnessController get(Context context) {
      return value;
    }

    @Implementation
    protected WebStartProtection webStartProtection() {
      return protection;
    }

    @Implementation
    protected boolean requiresRuntimeProtection() {
      return demand || unresolved || lifecycle.isStarting() || lifecycle.isStopping();
    }

    @Implementation
    protected boolean isUserStopped() {
      return lifecycle.isUserStopped();
    }

    @Implementation
    protected boolean isStopping() {
      return lifecycle.isStopping();
    }

    @Implementation
    protected boolean isStarting() {
      return lifecycle.isStarting();
    }

    @Implementation
    protected boolean isRestartBlocked() {
      return false;
    }

    @Implementation
    protected boolean isRestoringWebSession() {
      return false;
    }

    @Implementation
    protected boolean needsWebSessionRestore() {
      return false;
    }

    @Implementation
    protected boolean hasUnresolvedWebSession() {
      return unresolved;
    }

    @Implementation
    protected boolean canAutoRestart() {
      return false;
    }

    @Implementation
    protected long getWebGeneration() {
      return lifecycle.generation();
    }

    @Implementation
    protected String getWebAuthUrl() {
      return "";
    }

    @Implementation
    protected String lastStopError() {
      return "";
    }

    @Implementation
    protected ConfigStore config() {
      return new ConfigStore(RuntimeEnvironment.getApplication());
    }

    @Implementation
    protected WebStopCoordinator.Result lastStopResult() {
      return new WebStopCoordinator.Result(WebStopCoordinator.Status.STOPPED, "fixture", "");
    }

    @Implementation
    protected void restoreRunningWebSession(Runnable completed) {
      if (completed != null) completed.run();
    }

    @Implementation
    protected void requestWebSessionRecheck(Runnable completed) {
      if (completed != null) completed.run();
    }

    @Implementation
    protected void stopWebIfUnchanged(long generation) {
      if (lifecycle.isCurrent(generation)) {
        protection.cancelAll();
        lifecycle.beginStop();
        fencedStops++;
      }
    }

    @Implementation
    protected boolean startProtectedWeb(
        WebStartProtection.Ticket ticket,
        boolean safe,
        boolean recover,
        java.util.function.Consumer<String> status) {
      if (!protection.canAdmit(ticket)) return false;
      starts++;
      if (recover) {
        recoveryTicket = ticket;
        return true;
      }
      if (protection.beginStart(ticket, false) < 0) return false;
      demand = true;
      return true;
    }
  }

  private org.robolectric.android.controller.ActivityController<FragmentActivity> activity;
  private org.robolectric.android.controller.ServiceController<HarnessService> service;
  private App app;
  private final List<Runnable> queue = new ArrayList<>();

  @Before
  public void prepare() {
    app = (App) RuntimeEnvironment.getApplication();
    Controller.value = Shadow.newInstanceOf(HarnessController.class);
    Controller.lifecycle = new WebLifecycle();
    long stopped = Controller.lifecycle.beginStop();
    Controller.lifecycle.finishStop(stopped);
    Controller.protection =
        new WebStartProtection(Controller.lifecycle, android.os.SystemClock::elapsedRealtime);
    Controller.starts = 0;
    Controller.demand = false;
    Controller.unresolved = false;
    Controller.recoveryTicket = null;
    Controller.fencedStops = 0;
    Bridge.acquired = 0;
    activity = Robolectric.buildActivity(FragmentActivity.class).setup();
    Foreground.visible = activity.get();
  }

  @After
  public void close() {
    Controller.protection.cancelAll();
    if (service != null) service.destroy();
    Foreground.visible = null;
    if (activity != null) activity.pause().stop().destroy();
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
  }

  private Intent queuedPrepare(boolean recover) {
    assertTrue(
        FirstRunWebStart.enqueueVisible(activity.get(), queue::add, recover, recover, null, null));
    Intent intent =
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowApplication>extract(app)
            .getNextStartedService();
    assertNotNull("FGS request must precede any queued worker", intent);
    assertEquals("com.deepseekharness.app.PREPARE_WEB_START", intent.getAction());
    assertEquals(1, queue.size());
    assertEquals(0, Controller.starts);
    return intent;
  }

  private void promote(Intent intent) {
    service = Robolectric.buildService(HarnessService.class, intent).create();
    assertEquals(android.app.Service.START_STICKY, service.get().onStartCommand(intent, 0, 1));
    assertNotNull(
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowService>extract(
                service.get())
            .getLastForegroundNotification());
  }

  @Test
  public void visibleClickRequestsFgsBeforeDispatchAndAcceptedTaskSurvivesScreenOff() {
    Intent intent = queuedPrepare(false);
    Foreground.visible = null;
    activity.pause();
    promote(intent);
    queue.remove(0).run();
    assertEquals(1, Controller.starts);
    assertTrue(Controller.lifecycle.isStarting());
    assertFalse(Controller.protection.hasPreparing());
  }

  @Test
  public void backgroundCallerCannotIssueAPreparationIntent() {
    Foreground.visible = null;
    assertFalse(
        FirstRunWebStart.enqueueVisible(activity.get(), queue::add, false, false, null, null));
    assertTrue(queue.isEmpty());
    assertNull(
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowApplication>extract(app)
            .getNextStartedService());
  }

  @Test
  public void cancelledIntentCannotAcquireBridgeOrAdmitAfterLateServiceDelivery() {
    Intent intent = queuedPrepare(false);
    Controller.protection.cancelAll();
    service = Robolectric.buildService(HarnessService.class, intent).create();
    assertEquals(android.app.Service.START_NOT_STICKY, service.get().onStartCommand(intent, 0, 1));
    queue.remove(0).run();
    assertEquals(0, Controller.starts);
    assertEquals(0, Bridge.acquired);
    assertTrue(
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowService>extract(
                service.get())
            .isStoppedBySelf());
  }

  @Test
  public void serviceDestroyedAfterPromotionCannotAuthorizeTheQueuedWorker() {
    Intent intent = queuedPrepare(false);
    promote(intent);
    service.destroy();
    service = null;
    queue.remove(0).run();
    assertEquals(0, Controller.starts);
    assertFalse(Controller.protection.hasPreparing());
  }

  @Test
  public void deniedActualForegroundPromotionNeverRunsStartup() {
    Intent intent = queuedPrepare(false);
    service = Robolectric.buildService(HarnessService.class, intent);
    org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowService>extract(service.get())
        .setThrowInStartForeground(new SecurityException("fixture"));
    service.create();
    service.get().onStartCommand(intent, 0, 1);
    queue.remove(0).run();
    assertEquals(0, Controller.starts);
    assertEquals(0, Bridge.acquired);
  }

  @Test
  public void synchronousExecutorRejectionClosesOnlyThePreparation() {
    assertFalse(
        FirstRunWebStart.enqueueVisible(
            activity.get(),
            work -> {
              throw new java.util.concurrent.RejectedExecutionException("fixture");
            },
            false,
            false,
            null,
            null));
    assertFalse(Controller.protection.hasPreparing());
    assertEquals(0, Controller.starts);
  }

  @Test
  public void recoveryWorkerTransfersTicketToControllerContinuationInsteadOfClosingIt() {
    Intent intent = queuedPrepare(true);
    promote(intent);
    queue.remove(0).run();
    assertEquals(1, Controller.starts);
    assertNotNull(Controller.recoveryTicket);
    assertTrue(Controller.protection.canAdmit(Controller.recoveryTicket));
    assertTrue(Controller.protection.hasPreparing());
  }

  @Test
  public void runningPromotionIsNotRepeatedAfterTheActivityBackgrounds() {
    Intent intent = queuedPrepare(false);
    promote(intent);
    Foreground.visible = null;
    activity.pause();
    org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowService>extract(service.get())
        .setThrowInStartForeground(new SecurityException("no repeated promotion"));
    assertEquals(android.app.Service.START_STICKY, service.get().onStartCommand(intent, 0, 2));
    queue.remove(0).run();
    assertEquals(1, Controller.starts);
  }

  @Test
  public void admittedRuntimeIsFencedWhenItsActualServiceIsDestroyed() {
    Intent intent = queuedPrepare(false);
    promote(intent);
    queue.remove(0).run();
    long generation = Controller.lifecycle.generation();
    service.destroy();
    service = null;
    assertEquals(1, Controller.fencedStops);
    assertTrue(Controller.lifecycle.isStopping());
    assertFalse(Controller.lifecycle.isCurrent(generation));
  }

  @Test
  public void foregroundFailureRetainsAdmittedOwnerUntilItsSafeStopIsFenced() {
    Intent intent = queuedPrepare(false);
    promote(intent);
    queue.remove(0).run();
    long generation = Controller.lifecycle.generation();
    new ConfigStore(app).setLanMode(true);
    org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowService>extract(service.get())
        .setThrowInStartForeground(new SecurityException("type update rejected"));
    assertEquals(
        android.app.Service.START_NOT_STICKY,
        service
            .get()
            .onStartCommand(
                new Intent(app, HarnessService.class).setAction(HarnessService.ACTION_START),
                0,
                2));
    assertEquals(1, Controller.fencedStops);
    assertTrue(Controller.lifecycle.isStopping());
    assertFalse(Controller.lifecycle.isCurrent(generation));
    service.destroy();
    service = null;
    assertEquals("late destroy must not stop a second generation", 1, Controller.fencedStops);
  }

  @Test
  public void preparationNotificationReportsStartupRatherThanReadyOrStopped() {
    Intent intent = queuedPrepare(false);
    promote(intent);
    var notification =
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowService>extract(
                service.get())
            .getLastForegroundNotification();
    String title =
        String.valueOf(notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE));
    assertTrue(
        title,
        title.contains("启动") || title.toLowerCase(java.util.Locale.ROOT).contains("starting"));
  }

  @Test
  public void firstRunRequestRemainsPendingUntilAVisibleResumedWindow() {
    var intent = FirstRunWebStart.mainIntent(app, true);
    activity.pause();
    FirstRunWebStart.schedule(app, intent);
    assertTrue(intent.getBooleanExtra(FirstRunWebStart.EXTRA, false));
    assertNull(
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowApplication>extract(app)
            .getNextStartedService());
    assertFalse(Controller.protection.hasPreparing());
  }

  @Test
  public void stickyRecreationDoesNotReplayAnUnadmittedPreparation() {
    service = Robolectric.buildService(HarnessService.class).create();
    assertEquals(android.app.Service.START_NOT_STICKY, service.get().onStartCommand(null, 0, 1));
    assertEquals(0, Controller.starts);
    assertEquals(0, Bridge.acquired);
    assertTrue(
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowService>extract(
                service.get())
            .isStoppedBySelf());
  }

  @Test
  public void stalePreparationDoesNotChangeAnExistingRuntimeStickyLifetime() {
    Controller.demand = true;
    Intent stale =
        new Intent(app, HarnessService.class)
            .setAction("com.deepseekharness.app.PREPARE_WEB_START")
            .putExtra("web_start_preparation", "old-process-ticket");
    service = Robolectric.buildService(HarnessService.class, stale).create();
    assertEquals(android.app.Service.START_STICKY, service.get().onStartCommand(stale, 0, 1));
    assertEquals(0, Controller.starts);
    assertEquals(0, Bridge.acquired);
    assertFalse(
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowService>extract(
                service.get())
            .isStoppedBySelf());
  }

  @Test
  public void stalePreparationDoesNotCancelOrReplayAnotherValidPreparation() {
    Intent current = queuedPrepare(false);
    Intent stale = new Intent(current).putExtra("web_start_preparation", "old-process-ticket");
    service = Robolectric.buildService(HarnessService.class, stale).create();
    assertEquals(android.app.Service.START_STICKY, service.get().onStartCommand(stale, 0, 1));
    assertTrue(Controller.protection.hasPreparing());
    assertEquals(0, Controller.starts);
    assertEquals(0, Bridge.acquired);
    assertEquals(android.app.Service.START_STICKY, service.get().onStartCommand(current, 0, 2));
    queue.remove(0).run();
    assertEquals(1, Controller.starts);
  }
}
