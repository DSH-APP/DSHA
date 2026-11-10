package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.Looper;
import com.deepseekharness.app.PtySession;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** 只执行主线程派发，不 fork/JNI；验证固定 Termux 0.118.0 的 Handler 构造合同。 */
@RunWith(TerminalPtyThreadContractTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalPtyThreadContractTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  private static void initialize(Runnable action) {
    try {
      Method method = PtySession.class.getDeclaredMethod("initializeOnMain", Runnable.class);
      method.setAccessible(true);
      method.invoke(null, action);
    } catch (InvocationTargetException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new AssertionError(cause);
    } catch (ReflectiveOperationException failed) {
      throw new AssertionError(failed);
    }
  }

  @Test
  public void mainCallerInitializesInlineOnMainLooper() {
    AtomicBoolean ran = new AtomicBoolean();
    initialize(
        () -> {
          assertSame(Looper.getMainLooper(), Looper.myLooper());
          ran.set(true);
        });
    assertTrue(ran.get());
  }

  @Test
  public void workerIsInterruptedButDoesNotAbandonQueuedForkCallback() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();
    AtomicBoolean ran = new AtomicBoolean(), restored = new AtomicBoolean();
    Thread worker =
        new Thread(
            () -> {
              entered.countDown();
              try {
                initialize(
                    () -> {
                      assertSame(Looper.getMainLooper(), Looper.myLooper());
                      assertFalse(ran.getAndSet(true));
                    });
                restored.set(Thread.currentThread().isInterrupted());
              } catch (Throwable failed) {
                error.set(failed);
              }
            });
    worker.start();
    assertTrue(entered.await(1, TimeUnit.SECONDS));
    worker.interrupt();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (worker.isAlive() && System.nanoTime() < deadline) {
      org.robolectric.shadows.ShadowLooper main =
          org.robolectric.shadow.api.Shadow.extract(Looper.getMainLooper());
      main.idle();
      worker.join(10);
    }
    assertFalse("worker must receive the exact initialization result", worker.isAlive());
    assertNull(error.get());
    assertTrue(ran.get());
    assertTrue(restored.get());
  }

  @Test
  public void mainInitializationFailurePropagatesWithoutReplay() {
    AtomicBoolean ran = new AtomicBoolean();
    IllegalStateException expected = new IllegalStateException("failed before fork");
    assertSame(
        expected,
        assertThrows(
            IllegalStateException.class,
            () ->
                initialize(
                    () -> {
                      assertFalse(ran.getAndSet(true));
                      throw expected;
                    })));
    assertTrue(ran.get());
  }
}
