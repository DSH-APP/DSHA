package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.content.*;
import android.os.Looper;
import android.view.View;
import android.webkit.WebView;
import android.widget.*;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.PreviewPageSession;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;

/** 实际Preview创建/同步与WebView/控件；隔离后端身份口，不运行guest或真实网页。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = WebBackgroundPageLifecycleTest.App.class,
    shadows = WebBackgroundPageLifecycleTest.Controller.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WebBackgroundPageLifecycleTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {
      RuntimeTasks.initialize(this);
    }
  }

  private static final PreviewPageSession.Identity ORIGINAL =
      new PreviewPageSession.Identity(
          7, "22345678-1234-1234-1234-123456789abc", "http://127.0.0.1:41821/?token=fixed");

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Controller {
    static HarnessController value;
    static PreviewPageSession.Identity ready;
    static boolean exited;
    static com.deepseekharness.app.util.WebStartProtection protection;

    @Implementation
    protected com.deepseekharness.app.util.WebStartProtection webStartProtection() {
      return protection;
    }

    @Implementation
    protected boolean requiresRuntimeProtection() {
      return !exited;
    }

    static final List<Runnable> callbacks = new ArrayList<>();

    @Implementation
    protected static HarnessController get(Context context) {
      return value;
    }

    @Implementation
    protected PreviewPageSession.Identity getReadyWebPageIdentity() {
      return ready;
    }

    @Implementation
    protected boolean isWebSessionRetained(PreviewPageSession.Identity old) {
      return ready == null && ORIGINAL.equals(old);
    }

    @Implementation
    protected boolean isWebSessionExited(PreviewPageSession.Identity old) {
      return exited;
    }

    @Implementation
    protected boolean needsWebSessionRestore() {
      return false;
    }

    @Implementation
    protected boolean isRestoringWebSession() {
      return false;
    }

    @Implementation
    protected boolean hasUnresolvedWebSession() {
      return ready == null && !exited;
    }

    @Implementation
    protected boolean isStopping() {
      return false;
    }

    @Implementation
    protected boolean isUserStopped() {
      return false;
    }

    @Implementation
    protected long getWebGeneration() {
      return 7;
    }

    @Implementation
    protected ConfigStore config() {
      return new ConfigStore(RuntimeEnvironment.getApplication());
    }

    @Implementation
    protected boolean isStarting() {
      return false;
    }

    @Implementation
    protected boolean isRestartBlocked() {
      return false;
    }

    @Implementation
    protected boolean canAutoRestart() {
      return ready != null;
    }

    @Implementation
    protected String getWebAuthUrl() {
      return ready == null ? "" : ready.authUrl();
    }

    @Implementation
    protected void addReadyWebPageListener(Runnable ignored) {}

    @Implementation
    protected void removeReadyWebPageListener(Runnable ignored) {}

    @Implementation
    protected void restoreRunningWebSession(Runnable completed) {
      if (completed != null) callbacks.add(completed);
    }
  }

  public static final class Browser extends WebPreviewActivity {
    final Map<Integer, View> views = new HashMap<>();

    @Override
    public <T extends View> T findViewById(int id) {
      return (T) (views.containsKey(id) ? views.get(id) : getWindow().findViewById(id));
    }

    @Override
    public void setContentView(int layout) {
      FrameLayout content = new FrameLayout(this), container = new FrameLayout(this);
      content.addView(container);
      views.put(R.id.web_container, container);
      FrameLayout errors = new FrameLayout(this);
      errors.setVisibility(View.GONE);
      views.put(R.id.web_error_panel, errors);
      content.addView(errors);
      for (int id :
          new int[] {
            R.id.web_retry, R.id.web_error_browser, R.id.web_error_logs, R.id.web_error_recovery
          }) views.put(id, new Button(this));
      views.put(R.id.web_error_title, new TextView(this));
      views.put(R.id.web_error_detail, new TextView(this));
      views.put(R.id.web_progress, new ProgressBar(this));
      getWindow().setContentView(content);
    }
  }

  public static final class Page extends WebView {
    int destroyed, navigations;

    Page(Context context) {
      super(context);
    }

    @Override
    public void destroy() {
      destroyed++;
      super.destroy();
    }

    @Override
    public void loadUrl(String url) {
      navigations++;
      super.loadUrl(url);
    }
  }

  private Browser browser;
  private Page page;
  private WebPreviewActivity.Retained retained;
  private TextView title;
  private View panel;
  private org.robolectric.android.controller.ActivityController<Browser> lifecycle;
  private boolean created;

  private static void set(Object target, String name, Object value) throws Exception {
    Field field =
        target.getClass() == Browser.class
            ? WebPreviewActivity.class.getDeclaredField(name)
            : target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
    Method method = WebPreviewActivity.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    try {
      return method.invoke(browser, args);
    } catch (InvocationTargetException failure) {
      throw new AssertionError(failure.getCause());
    }
  }

  @Before
  public void prepare() throws Exception {
    Controller.value = Shadow.newInstanceOf(HarnessController.class);
    Controller.ready = ORIGINAL;
    Controller.exited = false;
    Controller.protection =
        new com.deepseekharness.app.util.WebStartProtection(
            new com.deepseekharness.app.util.WebLifecycle(),
            android.os.SystemClock::elapsedRealtime);
    Controller.callbacks.clear();
    BrowserTasks.nativeNavigation();
    Intent intent =
        new Intent(RuntimeEnvironment.getApplication(), Browser.class)
            .putExtra(BrowserConversationRoute.REQUEST, "old-request")
            .putExtra(BrowserConversationRoute.SESSION, "old-session");
    lifecycle = Robolectric.buildActivity(Browser.class, intent);
    browser = lifecycle.get();
    page = new Page(new MutableContextWrapper(browser));
    page.loadUrl("http://127.0.0.1:41821/");
    retained = new WebPreviewActivity.Retained();
    retained.view = page;
    retained.ready = true;
    retained.owner = new java.lang.ref.WeakReference<>(browser);
    retained.pageSession.claim(ORIGINAL);
    title = new TextView(browser);
    panel = new FrameLayout(browser);
    panel.setVisibility(View.GONE);
    browser.views.put(R.id.web_error_browser, new Button(browser));
    set(browser, "retained", retained);
    set(browser, "webView", page);
    set(browser, "previewAuth", new PreviewAuth(browser));
    set(browser, "errorPanel", panel);
    set(browser, "errorTitle", title);
    set(browser, "errorDetail", new TextView(browser));
    set(browser, "progress", new ProgressBar(browser));
    set(browser, "container", new FrameLayout(browser));
    set(browser, "baseUrl", "http://127.0.0.1:41821/");
    set(browser, "browserResumed", true);
    set(browser, "downloads", new WebDownloads(browser, null));
  }

  @After
  public void finish() {
    if (created) lifecycle.pause().stop().destroy();
    else page.destroy();
    ((org.robolectric.shadows.ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
  }

  @Test
  public void threeUnknownRestoreCyclesKeepTheSameDomAndBlockOldNativeCallbacksWithoutNavigation()
      throws Exception {
    for (int cycle = 0; cycle < 3; cycle++) {
      Controller.ready = null;
      assertTrue((Boolean) invoke("synchronizeWebSession", new Class<?>[0]));
      assertSame(page, retained.view);
      assertEquals(0, page.destroyed);
      assertFalse(page.isEnabled());
      assertEquals(View.VISIBLE, panel.getVisibility());
      assertTrue(title.getText().toString().contains("验证"));
      assertFalse((Boolean) invoke("isCurrentPage", new Class<?>[] {WebView.class}, page));
      assertFalse(browser.getIntent().hasExtra(BrowserConversationRoute.REQUEST));
      Controller.ready = ORIGINAL;
      invoke("synchronizeWebSession", new Class<?>[0]);
      assertSame(page, retained.view);
      assertEquals(0, page.destroyed);
      assertTrue(page.isEnabled());
      assertEquals(View.GONE, panel.getVisibility());
      assertTrue(retained.ready);
      assertTrue((Boolean) invoke("isCurrentPage", new Class<?>[] {WebView.class}, page));
    }
    assertEquals(1, page.navigations);
  }

  @Test
  public void confirmedExitShowsAnAccurateRetainedReadOnlyPageAndDoesNotStartOrReload()
      throws Exception {
    Controller.ready = null;
    Controller.exited = true;
    invoke("synchronizeWebSession", new Class<?>[0]);
    assertTrue(title.getText().toString().contains("已退出"));
    assertSame(page, retained.view);
    assertFalse(page.isEnabled());
    assertEquals(0, page.destroyed);
    assertEquals(1, page.navigations);
    assertFalse((Boolean) invoke("isCurrentPage", new Class<?>[] {WebView.class}, page));
  }

  @Test
  public void actualCreateWithGenerationRouteAttachesOnlyTheSameRetainedUnknownPage()
      throws Exception {
    var stored =
        new androidx.lifecycle.ViewModelProvider(browser).get(WebPreviewActivity.Retained.class);
    stored.view = page;
    stored.ready = true;
    stored.pageSession.claim(ORIGINAL);
    browser
        .getIntent()
        .putExtra(BrowserConversationRoute.GENERATION, ORIGINAL.generation())
        .putExtra(BrowserConversationRoute.INSTANCE, ORIGINAL.instanceId());
    Controller.ready = null;
    lifecycle.create();
    created = true;
    lifecycle.start().resume();
    assertFalse(browser.conversationHidden());
    assertSame(page, stored.view);
    assertNotNull(page.getParent());
    assertEquals(0, page.destroyed);
    assertFalse(page.isEnabled());
    assertFalse(browser.getIntent().hasExtra(BrowserConversationRoute.REQUEST));
    assertTrue(
        ((TextView) browser.findViewById(R.id.web_error_title))
            .getText()
            .toString()
            .contains("验证"));
    browser.onNewIntent(
        new Intent(RuntimeEnvironment.getApplication(), Browser.class)
            .putExtra(BrowserConversationRoute.MODE, "FULL")
            .putExtra(BrowserConversationRoute.GENERATION, ORIGINAL.generation())
            .putExtra(BrowserConversationRoute.INSTANCE, ORIGINAL.instanceId())
            .putExtra(BrowserConversationRoute.REQUEST, "late-task-route")
            .putExtra(BrowserConversationRoute.SESSION, "old-session"));
    assertFalse(browser.conversationHidden());
    assertFalse(browser.getIntent().hasExtra(BrowserConversationRoute.REQUEST));
    assertSame(page, stored.view);
    assertEquals(0, page.destroyed);
    Controller.ready = ORIGINAL;
    for (Runnable callback : List.copyOf(Controller.callbacks)) callback.run();
    assertSame(page, stored.view);
    assertTrue(page.isEnabled());
    assertEquals(1, page.navigations);
  }

  @Test
  public void retiredBlobBridgeIsClearedAndTheSameDomCanCreateANewWriter() throws Exception {
    Field downloads = WebPreviewActivity.class.getDeclaredField("downloads");
    downloads.setAccessible(true);
    WebDownloadModel model = ((WebDownloads) downloads.get(browser)).model;
    var old = new WebBlobDownload(page, model);
    retained.blobDownload = old;
    Controller.ready = null;
    invoke("synchronizeWebSession", new Class<?>[0]);
    assertNull(retained.blobDownload);
    Controller.ready = ORIGINAL;
    invoke("synchronizeWebSession", new Class<?>[0]);
    invoke("attachClients", new Class<?>[] {WebView.class}, page);
    var shadow = (org.robolectric.shadows.ShadowWebView) Shadow.extract(page);
    shadow
        .getDownloadListener()
        .onDownloadStart("data:text/plain,fixture", "", "", "text/plain", 7);
    assertNotNull(retained.blobDownload);
    assertNotSame(old, retained.blobDownload);
    Field writer = WebBlobDownload.class.getDeclaredField("writer");
    writer.setAccessible(true);
    assertTrue(((java.util.concurrent.ExecutorService) writer.get(old)).isShutdown());
    assertFalse(
        ((java.util.concurrent.ExecutorService) writer.get(retained.blobDownload)).isShutdown());
    retained.blobDownload.close();
    retained.blobDownload = null;
  }

  @Test
  public void actualServicePowerRefreshKeepsUnknownProtectedAndOnlyConfirmedExitReportsStopped()
      throws Exception {
    var service = Robolectric.buildService(com.deepseekharness.app.HarnessService.class).get();
    Field controller = service.getClass().getDeclaredField("c");
    controller.setAccessible(true);
    controller.set(service, Controller.value);
    Field keep = service.getClass().getDeclaredField("keepAliveRunning");
    keep.setAccessible(true);
    keep.set(service, true);
    Field message = service.getClass().getDeclaredField("notificationState");
    message.setAccessible(true);
    Method refresh = service.getClass().getDeclaredMethod("refreshLocks");
    refresh.setAccessible(true);
    Controller.ready = null;
    refresh.invoke(service);
    assertTrue(message.get(service).toString().contains("验证"));
    Field cpu = service.getClass().getDeclaredField("wakeLock");
    cpu.setAccessible(true);
    assertNotNull(cpu.get(service));
    assertTrue(((android.os.PowerManager.WakeLock) cpu.get(service)).isHeld());
    Controller.ready = ORIGINAL;
    refresh.invoke(service);
    assertFalse(message.get(service).toString().contains("已停止"));
    assertTrue(((android.os.PowerManager.WakeLock) cpu.get(service)).isHeld());
    Controller.ready = null;
    Controller.exited = true;
    refresh.invoke(service);
    assertTrue(message.get(service).toString().contains("已停止"));
    assertNull(cpu.get(service));
  }
}
