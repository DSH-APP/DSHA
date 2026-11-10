package com.deepseekharness.app;

import static org.junit.Assert.*;

import android.content.Context;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.LanNetworkState;
import com.deepseekharness.app.util.LanPassword;
import com.deepseekharness.app.util.LanPortPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Exercises the actual listener thread without exposing a LAN socket. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = LanListenerFailureTest.App.class,
    shadows = {LanListenerFailureTest.Port.class, LanListenerFailureTest.Network.class})
public final class LanListenerFailureTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private static final AtomicReference<Thread> worker = new AtomicReference<>();
  private static final AtomicReference<Throwable> uncaught = new AtomicReference<>();
  private static CountDownLatch entered;
  private static boolean failBind;
  private static ServerSocket bound;

  private static void markWorker() {
    Thread thread = Thread.currentThread();
    thread.setUncaughtExceptionHandler((ignored, failure) -> uncaught.set(failure));
    worker.set(thread);
    entered.countDown();
  }

  @Implements(value = LanPortPolicy.class, isInAndroidSdk = false)
  public static final class Port {
    @Implementation
    protected static ServerSocket bind(int port) throws IOException {
      if (failBind) {
        markWorker();
        throw new SecurityException("private address must not appear in diagnostics");
      }
      bound = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      return bound;
    }
  }

  @Implements(value = LanNetworkState.class, isInAndroidSdk = false)
  public static final class Network {
    @Implementation
    protected boolean poll() {
      markWorker();
      throw new IllegalStateException("network state unavailable");
    }
  }

  @Before
  public void setUp() {
    worker.set(null);
    uncaught.set(null);
    entered = new CountDownLatch(1);
    bound = null;
    Context ctx = RuntimeEnvironment.getApplication();
    ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(LanPassword.PREF_KEY, "test-verifier")
        .commit();
    assertTrue(LanProxyService.setDshAuthCookie("dsh-auth-test=valid", 123));
  }

  @After
  public void tearDown() {
    LanProxyService.stop();
  }

  @Test
  public void deniedBindRetiresListenerWithoutUncaughtException() throws Exception {
    failBind = true;
    runFailure();
  }

  @Test
  public void failedNetworkPollClosesBoundListenerWithoutUncaughtException() throws Exception {
    failBind = false;
    runFailure();
    assertNotNull(bound);
    assertTrue(bound.isClosed());
  }

  private void runFailure() throws Exception {
    LanProxyService.start(null, RuntimeEnvironment.getApplication(), 49123, 123);
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    Thread thread = worker.get();
    assertNotNull(thread);
    thread.join(5000);
    assertFalse(thread.isAlive());
    assertNull(uncaught.get());
    assertEquals(0, LanProxyService.port(123));
    assertTrue(LanProxyService.hasDshAuth(123));
  }
}
