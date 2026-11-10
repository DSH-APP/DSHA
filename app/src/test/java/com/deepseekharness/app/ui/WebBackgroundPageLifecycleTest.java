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
import com.deepseekharness.app.util.UiText;
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
    static Boolean retained;
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
    static final List<Runnable> pageListeners = new ArrayList<>();

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
      if (retained != null) return retained;
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
    protected void addReadyWebPageListener(Runnable listener) {
      if (listener != null) pageListeners.add(listener);
    }

    @Implementation
    protected void removeReadyWebPageListener(Runnable listener) {
      pageListeners.remove(listener);
    }

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
  private BrowserPageKeeper keeper;
  private TextView title;
  private View panel;
  private org.robolectric.android.controller.ActivityController<Browser> lifecycle;
  private boolean created;
  private String previousLanguage;

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
    previousLanguage = UiText.language();
    // 固定非中文路径，避免进程全局语言状态影响本用例的精确文案断言。
    UiText.setLanguage("en");
    Controller.value = Shadow.newInstanceOf(HarnessController.class);
    Controller.ready = ORIGINAL;
    Controller.exited = false;
    Controller.protection =
        new com.deepseekharness.app.util.WebStartProtection(
            new com.deepseekharness.app.util.WebLifecycle(),
            android.os.SystemClock::elapsedRealtime);
    Controller.callbacks.clear();
    Controller.pageListeners.clear();
    Controller.retained = null;
    BrowserTasks.nativeNavigation();
    Intent intent =
        new Intent(RuntimeEnvironment.getApplication(), Browser.class)
            .putExtra(BrowserConversationRoute.REQUEST, "old-request")
            .putExtra(BrowserConversationRoute.SESSION, "old-session");
    lifecycle = Robolectric.buildActivity(Browser.class, intent);
    browser = lifecycle.get();
    page = new Page(new MutableContextWrapper(browser));
    page.loadUrl("http://127.0.0.1:41821/");
    keeper = BrowserPageKeeper.forApplication(RuntimeEnvironment.getApplication());
    retained = keeper.acquire(browser);
    retained.view = page;
    retained.ready = true;
    retained.owner = new java.lang.ref.WeakReference<>(browser);
    retained.pageSession.claim(ORIGINAL);
    // prepare() 模拟真实页面已完成首次导航：新 Activity attach 只能复用，不能再 loadUrl。
    retained.navigation.begin(false);
    assertTrue(retained.navigation.claim());
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
    try {
      if (created) lifecycle.pause().stop().destroy();
      keeper.retire("test-cleanup");
      ((org.robolectric.shadows.ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
    } finally {
      UiText.setLanguage(previousLanguage);
    }
  }

  static android.webkit.RenderProcessGoneDetail rendererExit(boolean crashed) {
    return new android.webkit.RenderProcessGoneDetail() {
      @Override
      public boolean didCrash() {
        return crashed;
      }

      @Override
      public int rendererPriorityAtExit() {
        return WebView.RENDERER_PRIORITY_IMPORTANT;
      }
    };
  }

  @Test
  public void rendererDeathDuringRotationGapIsHandledAndClearsOnlyTheRetainedPage()
      throws Exception {
    var stored = retained;
    // prepare() already loaded this page. A real retained ready page has also consumed
    // its initial-navigation ticket; leaving the gate new would authorize a first load
    // during onCreate, before the renderer-exit scenario even starts.
    stored.navigation.begin(false);
    assertTrue(stored.navigation.claim());
    browser
        .getIntent()
        .putExtra(BrowserConversationRoute.GENERATION, ORIGINAL.generation())
        .putExtra(BrowserConversationRoute.INSTANCE, ORIGINAL.instanceId());
    lifecycle.create().start().resume().visible();
    created = true;
    assertEquals("reattaching the ready page must not navigate", 1, page.navigations);
    var uploads = new WebUploads.Session(browser.getCacheDir());
    stored.uploadSession = uploads;
    lifecycle.pause().stop();
    org.robolectric.util.ReflectionHelpers.setField(browser, "mChangingConfigurations", true);
    lifecycle.destroy();
    created = false;
    assertNull(page.getParent());
    assertSame(page, stored.view);
    assertEquals(0, page.destroyed);
    assertTrue(page.getWebViewClient() instanceof WebPreviewActivity.RetainedRendererClient);
    assertTrue(page.getWebViewClient().onRenderProcessGone(page, rendererExit(true)));
    assertNull(stored.view);
    assertNull(stored.pageSession.identity());
    assertFalse(stored.ready);
    assertTrue(uploads.isClosed());
    assertEquals(1, page.destroyed);
    assertEquals("renderer loss is not Web-node exit", ORIGINAL, Controller.ready);
    assertEquals(1, page.navigations);
  }

  @Test
  public void foregroundRendererDeathCancelsOnlyItsContentReturnWithoutRestartingRuntime()
      throws Exception {
    BrowserTasks.rememberBackground(browser);
    assertTrue(BrowserTasks.returnPending());
    BrowserTasks.browserUnavailable(Robolectric.buildActivity(Browser.class).get());
    assertTrue("another host cannot cancel the pending return", BrowserTasks.returnPending());
    assertTrue(retained.rendererGone(page, rendererExit(false)));
    assertFalse(BrowserTasks.returnPending());
    assertNull(retained.view);
    assertNull(retained.pageSession.identity());
    assertEquals(1, page.destroyed);
    assertEquals(1, page.navigations);
    assertEquals(ORIGINAL, Controller.ready);
    assertFalse(Controller.exited);
    assertEquals(View.VISIBLE, panel.getVisibility());
    assertEquals("The system closed the page", title.getText().toString());
  }

  @Test
  public void lateRetainedRendererCallbackCannotDisposeAReplacementPage() {
    var stale = new WebPreviewActivity.RetainedRendererClient(retained);
    var replacement = new Page(new MutableContextWrapper(browser));
    retained.view = replacement;
    assertTrue(stale.onRenderProcessGone(page, rendererExit(true)));
    assertSame(replacement, retained.view);
    assertEquals(ORIGINAL, retained.pageSession.identity());
    assertEquals(0, replacement.destroyed);
    replacement.destroy();
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
      assertEquals("Checking the service", title.getText().toString());
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
    assertEquals("DSH service stopped", title.getText().toString());
    assertSame(page, retained.view);
    assertFalse(page.isEnabled());
    assertEquals(0, page.destroyed);
    assertEquals(1, page.navigations);
    assertFalse((Boolean) invoke("isCurrentPage", new Class<?>[] {WebView.class}, page));
  }

  @Test
  public void actualCreateWithGenerationRouteAttachesOnlyTheSameRetainedUnknownPage()
      throws Exception {
    var stored = retained;
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
    assertEquals(
        "Checking the service",
        ((TextView) browser.findViewById(R.id.web_error_title)).getText().toString());
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
    assertEquals(
        "Verifying the existing Web session · background protection retained",
        message.get(service).toString());
    Field cpu = service.getClass().getDeclaredField("wakeLock");
    cpu.setAccessible(true);
    assertNotNull(cpu.get(service));
    assertTrue(((android.os.PowerManager.WakeLock) cpu.get(service)).isHeld());
    Controller.ready = ORIGINAL;
    refresh.invoke(service);
    assertNotEquals("Web stopped", message.get(service).toString());
    assertTrue(((android.os.PowerManager.WakeLock) cpu.get(service)).isHeld());
    Controller.ready = null;
    Controller.exited = true;
    refresh.invoke(service);
    assertEquals("Web stopped", message.get(service).toString());
    assertNull(cpu.get(service));
  }

  private WebPreviewActivity.Retained createWithKeeperPage() {
    browser
        .getIntent()
        .putExtra(BrowserConversationRoute.GENERATION, ORIGINAL.generation())
        .putExtra(BrowserConversationRoute.INSTANCE, ORIGINAL.instanceId());
    var stored = retained;
    lifecycle.create().start().resume().visible();
    created = true;
    assertEquals(1, page.navigations);
    return stored;
  }

  @Test
  public void systemReclaimKeepsTheSameWebViewAndTheNextActivityAdoptsItWithoutNavigation()
      throws Exception {
    var stored = createWithKeeperPage();
    lifecycle.pause().stop();
    // 不置 mChangingConfigurations、不 finish：与系统回收 stopped Activity 一致。
    lifecycle.destroy();
    created = false;
    assertEquals("system reclaim must not destroy the page", 0, page.destroyed);
    assertSame(page, keeper.page().view);
    assertTrue(keeper.hasDetachedPage());
    assertEquals(BrowserPageKeeper.REASON_SYSTEM, keeper.lastDetachedReason());
    assertNull(page.getParent());
    assertTrue(page.getWebViewClient() instanceof WebPreviewActivity.RetainedRendererClient);

    var secondController =
        Robolectric.buildActivity(Browser.class, new Intent(browser.getIntent())).create();
    lifecycle = secondController;
    created = true;
    var second = secondController.get();
    assertSame(stored, keeper.page());
    assertSame(page, stored.view);
    secondController.start().resume();
    assertEquals("adopting the kept page must not navigate", 1, page.navigations);
    assertEquals(0, page.destroyed);
    assertNotNull(page.getParent());
    assertSame(second, stored.owner.get());
  }

  @Test
  public void configurationChangeDetachesAndTheNextActivityReusesTheSamePage() throws Exception {
    var stored = createWithKeeperPage();
    lifecycle.pause().stop();
    org.robolectric.util.ReflectionHelpers.setField(browser, "mChangingConfigurations", true);
    lifecycle.destroy();
    created = false;
    assertEquals("configuration change must not destroy the page", 0, page.destroyed);
    assertTrue(keeper.hasDetachedPage());
    assertEquals(BrowserPageKeeper.REASON_CONFIG, keeper.lastDetachedReason());

    var secondController =
        Robolectric.buildActivity(Browser.class, new Intent(browser.getIntent())).create();
    lifecycle = secondController;
    created = true;
    assertSame(stored, keeper.page());
    secondController.start().resume();
    assertEquals(1, page.navigations);
    assertEquals(0, page.destroyed);
    assertSame(page, stored.view);
  }

  @Test
  public void finishingActivityDestroysThePageAndKeeperReleasesIt() throws Exception {
    var stored = createWithKeeperPage();
    browser.finish();
    lifecycle.pause().stop().destroy();
    created = false;
    assertEquals(1, page.destroyed);
    assertNull(keeper.page());
    assertEquals(BrowserPageKeeper.REASON_FINISH, keeper.lastRetiredReason());
    assertNull(stored.view);
  }

  @Test
  public void newIntentForTheSameGenerationDoesNotNavigateOrRecreateThePage() throws Exception {
    var stored = createWithKeeperPage();
    browser.onNewIntent(
        new Intent(RuntimeEnvironment.getApplication(), Browser.class)
            .putExtra(BrowserConversationRoute.MODE, "FULL")
            .putExtra(BrowserConversationRoute.GENERATION, ORIGINAL.generation())
            .putExtra(BrowserConversationRoute.INSTANCE, ORIGINAL.instanceId())
            .putExtra(BrowserConversationRoute.REQUEST, "second-request")
            .putExtra(BrowserConversationRoute.SESSION, "second-session"));
    assertEquals(1, page.navigations);
    assertEquals(0, page.destroyed);
    assertSame(stored, keeper.page());
    assertSame(page, stored.view);
  }

  @Test
  public void changedWebSessionDestroysTheKeeperPageInsteadOfReusingIt() throws Exception {
    var stored = createWithKeeperPage();
    Controller.ready =
        new PreviewPageSession.Identity(
            8, "32345678-1234-1234-1234-123456789abc", "http://127.0.0.1:41822/?token=next");
    invoke("synchronizeWebSession", new Class<?>[0]);
    assertEquals(1, page.destroyed);
    assertNull(stored.view);
    assertNull(stored.pageSession.identity());
    assertNotSame(page, keeper.page() == null ? null : keeper.page().view);
  }

  @Test
  public void harnessServiceTaskRemovalRetiresTheKeptPage() {
    createWithKeeperPage();
    var service =
        org.robolectric.Robolectric.buildService(com.deepseekharness.app.HarnessService.class)
            .get();
    service.onTaskRemoved(null);
    assertEquals(1, page.destroyed);
    assertNull(keeper.page());
    assertEquals(BrowserPageKeeper.REASON_TASK_REMOVED, keeper.lastRetiredReason());
  }
}
