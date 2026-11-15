package com.deepseekharness.app.screen;

import static org.junit.Assert.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(ScreenCaptureCallbackQueueTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class ScreenCaptureCallbackQueueTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  @Test
  public void saturatedPixelQueueNeverRunsHeavyWorkOnTheFrameworkCaller() throws Exception {
    var field = ScreenScreenshotCapture.class.getDeclaredField("CALLBACKS");
    field.setAccessible(true);
    ThreadPoolExecutor callbacks = (ThreadPoolExecutor) field.get(null);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicBoolean ranOnCaller = new AtomicBoolean();
    Thread caller = Thread.currentThread();
    try {
      callbacks.execute(
          () -> {
            entered.countDown();
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }
          });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      while (callbacks.getQueue().remainingCapacity() > 0) callbacks.execute(() -> {});
      assertThrows(
          RejectedExecutionException.class,
          () -> callbacks.execute(() -> ranOnCaller.set(Thread.currentThread() == caller)));
      assertFalse(ranOnCaller.get());
    } finally {
      release.countDown();
    }
  }
}
