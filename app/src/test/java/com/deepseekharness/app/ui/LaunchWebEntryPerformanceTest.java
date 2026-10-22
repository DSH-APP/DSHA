package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.FragmentActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.DshAuthSession;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;

/** Real Launch control flow and PreviewAuth, with synthetic loopback HTTP and host UI. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = LaunchWebEntryPerformanceTest.App.class,
    shadows = LaunchWebEntryPerformanceTest.Controller.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class LaunchWebEntryPerformanceTest {
  private static final String COOKIE =
      "dsh-auth-" + "B".repeat(43) + "=v1.cGF5bG9hZA." + "C".repeat(43);

  public static class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static class Controller {
    static HarnessController controller;
    static Server server;
    static final AtomicInteger exchanges = new AtomicInteger();
    static long generation;
    static String replacementUrl;

    @Implementation
    protected static HarnessController get(Context c) {
      return controller;
    }

    @Implementation
    protected String getWebAuthUrl() {
      return replacementUrl == null ? server.url() : replacementUrl;
    }

    @Implementation
    protected long getWebGeneration() {
      return generation;
    }

    @Implementation
    protected String exchangeDshAuthCookie() {
      exchanges.incrementAndGet();
      var result =
          DshAuthSession.exchange(server.url(), server.socket.getLocalPort(), () -> true, 1200);
      return result.cookie;
    }

    @Implementation
    protected String getWebAuthFailure() {
      return "SYNTHETIC_AUTH_REFUSED";
    }
  }

  public static class Host extends FragmentActivity {
    @Override
    protected void onCreate(Bundle saved) {
      super.onCreate(saved);
      FrameLayout root = new FrameLayout(this);
      root.setId(10001);
      setContentView(root);
    }
  }

  public static class Entry extends LaunchFragment {
    int navigations, callbacks;
    String cookie, error;
    boolean changedGeneration, changedUrl, navigationFailure;
    PreviewAuth preview;
    int generationReads, urlReads;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle saved) {
      try {
        Field f = LaunchFragment.class.getDeclaredField("controller");
        f.setAccessible(true);
        f.set(this, Controller.controller);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
      FrameLayout root = new FrameLayout(requireContext());
      TextView status = new TextView(requireContext());
      status.setId(R.id.launch_status);
      root.addView(status);
      return root;
    }

    @Override
    String webEntryUrl() {
      return changedUrl && urlReads++ > 0
          ? "http://127.0.0.1:5555/?token=changed"
          : Controller.server.url();
    }

    @Override
    long webEntryGeneration() {
      return changedGeneration && generationReads++ > 0 ? 8 : 7;
    }

    @Override
    void openWebEntry(Activity activity, String url, String cookie) {
      if (navigationFailure) throw new IllegalStateException("SYNTHETIC_ACTIVITY_REFUSED");
      navigations++;
      this.cookie = cookie;
      preview = new PreviewAuth(activity);
      preview.refresh(
          (next, verified, error) -> {
            callbacks++;
            this.error = error;
          });
    }

    void enter() throws Exception {
      Method m = LaunchFragment.class.getDeclaredMethod("enterWeb");
      m.setAccessible(true);
      m.invoke(this);
    }
  }

  static class Server implements AutoCloseable {
    final ServerSocket socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    final AtomicInteger requests = new AtomicInteger(), completed = new AtomicInteger();
    final Thread worker;
    volatile boolean reject;
    volatile Throwable failure;

    Server() throws Exception {
      worker =
          new Thread(
              () -> {
                while (!socket.isClosed())
                  try (Socket c = socket.accept()) {
                    c.setSoTimeout(2000);
                    BufferedReader in =
                        new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
                    String first = in.readLine();
                    while (true) {
                      String line = in.readLine();
                      if (line == null || line.isEmpty()) break;
                    }
                    requests.incrementAndGet();
                    Thread.sleep(60);
                    String response =
                        reject
                            ? "401 Unauthorized\r\n"
                            : first.startsWith("GET /?token=")
                                ? "303 See Other\r\nLocation: /\r\nSet-Cookie: "
                                    + COOKIE
                                    + "; Path=/; HttpOnly\r\n"
                                : "200 OK\r\n";
                    c.getOutputStream()
                        .write(
                            ("HTTP/1.1 "
                                    + response
                                    + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                    completed.incrementAndGet();
                  } catch (Throwable e) {
                    if (!socket.isClosed()) failure = e;
                  }
              });
      worker.setDaemon(true);
      worker.start();
    }

    String url() {
      return "http://127.0.0.1:" + socket.getLocalPort() + "/?token=" + "A".repeat(43);
    }

    public void close() throws Exception {
      socket.close();
      worker.join(3000);
      if (failure != null) throw new AssertionError(failure);
    }
  }

  org.robolectric.android.controller.ActivityController<Host> host;
  Entry entry;
  Server server;

  @Before
  public void setup() throws Exception {
    server = new Server();
    Controller.server = server;
    Controller.controller = Shadow.newInstanceOf(HarnessController.class);
    Controller.exchanges.set(0);
    Controller.generation = 7;
    Controller.replacementUrl = null;
    host = Robolectric.buildActivity(Host.class).setup();
    entry = new Entry();
    host.get().getSupportFragmentManager().beginTransaction().add(10001, entry).commitNow();
  }

  @After
  public void close() throws Exception {
    host.pause().stop().destroy();
    server.close();
  }

  static void pump(Callable<Boolean> done) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!done.call() && System.nanoTime() < until) {
      ((org.robolectric.shadows.ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
      Thread.sleep(5);
    }
    ((org.robolectric.shadows.ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
    assertTrue("callback not delivered", done.call());
  }

  @Test
  public void firstEntryHasOneAuthenticationOwnerAndFewerRealHttpRequests() throws Exception {
    int expected = Integer.getInteger("dsha.expectedEntryExchanges", 1);
    long begin = System.nanoTime();
    entry.enter();
    entry.enter();
    pump(() -> entry.callbacks == 1);
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
    assertEquals(1, entry.navigations);
    assertNull(entry.error);
    assertEquals(expected, Controller.exchanges.get());
    assertEquals(expected * 2, server.requests.get());
    System.out.println(
        "ENTRY_LOOPBACK_METRIC exchanges="
            + Controller.exchanges
            + " requests="
            + server.requests
            + " elapsedMs="
            + elapsed
            + " artificialDelayPerRequestMs=60");
  }

  @Test
  public void pausedEntryDoesNotAuthenticateOrNavigate() throws Exception {
    host.pause();
    entry.enter();
    assertEquals(0, entry.navigations);
    assertEquals(0, Controller.exchanges.get());
    host.resume();
  }

  @Test
  public void destroyedViewDoesNotAuthenticateOrNavigate() throws Exception {
    host.get().getSupportFragmentManager().beginTransaction().remove(entry).commitNow();
    entry.enter();
    assertEquals(0, entry.navigations);
    assertEquals(0, Controller.exchanges.get());
  }

  @Test
  public void generationChangesBeforeDispatchDoNotNavigate() throws Exception {
    entry.changedGeneration = true;
    entry.enter();
    Thread.sleep(160);
    ((org.robolectric.shadows.ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
    assertEquals(0, entry.navigations);
  }

  @Test
  public void urlChangesBeforeDispatchDoNotNavigate() throws Exception {
    entry.changedUrl = true;
    entry.enter();
    Thread.sleep(160);
    ((org.robolectric.shadows.ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
    assertEquals(0, entry.navigations);
  }

  @Test
  public void failedBrowserAuthenticationStillReturnsAnExplicitError() throws Exception {
    server.reject = true;
    entry.enter();
    int expected = Integer.getInteger("dsha.expectedEntryExchanges", 1);
    if (expected == 1) {
      pump(() -> entry.callbacks == 1);
      assertEquals(1, entry.navigations);
      assertNotNull(entry.error);
      assertEquals(1, Controller.exchanges.get());
    } else {
      Thread.sleep(160);
      ((org.robolectric.shadows.ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
      assertEquals(0, entry.navigations);
    }
  }

  @Test
  public void failedNavigationReleasesSingleFlightForAnExplicitRetry() throws Exception {
    entry.navigationFailure = true;
    entry.enter();
    assertEquals(0, entry.navigations);
    assertEquals(0, Controller.exchanges.get());
    entry.navigationFailure = false;
    entry.enter();
    pump(() -> entry.callbacks == 1);
    assertEquals(1, entry.navigations);
    assertNull(entry.error);
    assertEquals(1, Controller.exchanges.get());
  }

  @Test
  public void generationChangedWhileBrowserAuthenticatesCannotAcceptTheOldCookie()
      throws Exception {
    entry.enter();
    Controller.generation = 8;
    pump(() -> entry.callbacks == 1);
    assertNotNull(entry.error);
    assertEquals(1, entry.navigations);
    assertEquals(1, Controller.exchanges.get());
  }

  @Test
  public void urlChangedWhileBrowserAuthenticatesCannotAcceptTheOldCookie() throws Exception {
    entry.enter();
    Controller.replacementUrl = "http://127.0.0.1:5555/?token=changed";
    pump(() -> entry.callbacks == 1);
    assertNotNull(entry.error);
    assertEquals(1, Controller.exchanges.get());
  }

  @Test
  public void cancelledBrowserAuthenticationDoesNotDeliverItsLateCallback() throws Exception {
    entry.enter();
    assertNotNull(entry.preview);
    entry.preview.cancel();
    pump(() -> server.completed.get() == 2);
    Thread.sleep(20);
    ((org.robolectric.shadows.ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
    assertEquals(0, entry.callbacks);
    assertEquals(1, Controller.exchanges.get());
  }
}
