package com.deepseekharness.app.screen;

import static org.junit.Assert.*;

import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.util.ScreenConnectionEpoch;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(AccessibilityPairingReadThreadTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class AccessibilityPairingReadThreadTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  public static final class BlockedService extends DshaAccessibilityService {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicReference<Thread> readThread = new AtomicReference<>();

    @Override
    public AccessibilityNodeInfo getRootInActiveWindow() {
      readThread.set(Thread.currentThread());
      entered.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }

  @Test
  public void cancellationAfterPermitClaimRemainsUnknownAndCannotDispatchLater() throws Exception {
    ScreenConnectionEpoch<DshaAccessibilityService> connection = new ScreenConnectionEpoch<>();
    connection.connect(new BlockedService());
    var broker = new com.deepseekharness.app.capabilities.CapabilityBroker(() -> 0);
    var target =
        new com.deepseekharness.app.capabilities.WindowTargetEvidence() {
          public String kind() {
            return "window";
          }

          public int displayId() {
            return 0;
          }

          public int windowId() {
            return 7;
          }

          public String packageName() {
            return "example.app";
          }
        };
    var request =
        com.deepseekharness.app.capabilities.CapabilityRequest.create(
            "screen",
            "key",
            new com.deepseekharness.app.core.RuntimeSnapshot.Epoch("install", 1, "run"),
            "install",
            "bridge",
            target,
            0,
            broker.epoch("screen"),
            10_000);
    assertTrue(broker.authorize(request));
    CountDownLatch permitClaimed = new CountDownLatch(1), release = new CountDownLatch(1);
    ScreenRequestContext context =
        new ScreenRequestContext(
            broker,
            request,
            () -> {
              // Authorization has already been consumed, so a permit exists and its final live
              // check waits.
              if (!broker.authorized(request)) {
                permitClaimed.countDown();
                try {
                  release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                }
              }
              return true;
            },
            connection,
            true);
    var worker = Executors.newSingleThreadExecutor();
    AtomicInteger dispatched = new AtomicInteger();
    try {
      var claim =
          worker.submit(
              () -> {
                if (context.claimDispatch()) dispatched.incrementAndGet();
              });
      assertTrue(permitClaimed.await(2, TimeUnit.SECONDS));
      assertTrue(context.cancelAndMayHaveSubmitted());
      release.countDown();
      claim.get(2, TimeUnit.SECONDS);
      assertEquals(0, dispatched.get());
      assertFalse(context.current());
      assertFalse(broker.authorize(request));
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  public void actualSettingsEventReturnsWhilePlatformRootReadIsStillBlocked() throws Exception {
    var connectionField = DshaAccessibilityService.class.getDeclaredField("CONNECTION");
    connectionField.setAccessible(true);
    @SuppressWarnings("unchecked")
    ScreenConnectionEpoch<DshaAccessibilityService> connection =
        (ScreenConnectionEpoch<DshaAccessibilityService>) connectionField.get(null);
    var instanceField = DshaAccessibilityService.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    BlockedService service = new BlockedService();
    var caller = Executors.newSingleThreadExecutor();
    AtomicReference<Thread> eventThread = new AtomicReference<>();
    try {
      connection.connect(service);
      instanceField.set(null, service);
      DshaAccessibilityService.startWatch(
          (code, ip, port, connect) -> fail("Unexpected pairing callback"));
      AccessibilityEvent event =
          AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
      event.setPackageName("com.android.settings");
      var returned =
          caller.submit(
              () -> {
                eventThread.set(Thread.currentThread());
                service.onAccessibilityEvent(event);
              });
      returned.get(1, TimeUnit.SECONDS);
      assertTrue(service.entered.await(2, TimeUnit.SECONDS));
      assertNotSame(eventThread.get(), service.readThread.get());
    } finally {
      DshaAccessibilityService.stopWatch();
      service.release.countDown();
      connection.disconnect(service);
      instanceField.set(null, null);
      caller.shutdownNow();
    }
  }
}
