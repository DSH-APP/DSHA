package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ComponentCallbacks2;
import android.webkit.WebView;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.PreviewPageSession;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;

/** K1+K2：应用级 keeper 的单页所有权、摘挂、trim、任务移除与运行时身份变化语义。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = WebBackgroundPageLifecycleTest.App.class,
    shadows = WebBackgroundPageLifecycleTest.Controller.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserPageKeeperTest {
  private static final PreviewPageSession.Identity IDENTITY =
      new PreviewPageSession.Identity(
          7, "22345678-1234-1234-1234-123456789abc", "http://127.0.0.1:41821/?token=fixed");

  private BrowserPageKeeper keeper;
  private WebPreviewActivity.Retained retained;
  private WebBackgroundPageLifecycleTest.Page page;
  private WebBackgroundPageLifecycleTest.Browser first;
  private WebBackgroundPageLifecycleTest.Browser second;

  @Before
  public void setUp() {
    WebBackgroundPageLifecycleTest.Controller.value = Shadow.newInstanceOf(HarnessController.class);
    WebBackgroundPageLifecycleTest.Controller.ready = IDENTITY;
    WebBackgroundPageLifecycleTest.Controller.retained = null;
    WebBackgroundPageLifecycleTest.Controller.pageListeners.clear();
    keeper = BrowserPageKeeper.forApplication(RuntimeEnvironment.getApplication());
    first = Robolectric.buildActivity(WebBackgroundPageLifecycleTest.Browser.class).get();
    second = Robolectric.buildActivity(WebBackgroundPageLifecycleTest.Browser.class).get();
    retained = keeper.acquire(first);
    page =
        new WebBackgroundPageLifecycleTest.Page(new android.content.MutableContextWrapper(first));
    retained.view = page;
    retained.ready = true;
    retained.pageSession.claim(IDENTITY);
    page.loadUrl("http://127.0.0.1:41821/");
  }

  @After
  public void tearDown() {
    keeper.retire("test-cleanup");
  }

  @Test
  public void newKeeperHasNoPage() {
    var fresh = BrowserPageKeeper.forApplication(RuntimeEnvironment.getApplication());
    assertSame(keeper, fresh);
    keeper.retire("test-cleanup");
    assertNull(keeper.page());
  }

  @Test
  public void detachKeepsTheSamePageAndTheNextOwnerAdoptsItWithoutNavigation() {
    assertTrue(keeper.detach(first, BrowserPageKeeper.REASON_SYSTEM));
    assertTrue(keeper.hasDetachedPage());
    assertTrue(keeper.detachedAt() > 0);
    assertEquals(BrowserPageKeeper.REASON_SYSTEM, keeper.lastDetachedReason());
    assertEquals(1, keeper.detaches());
    assertEquals(1, page.navigations);

    var adopted = keeper.acquire(second);
    assertSame(retained, adopted);
    assertSame(page, adopted.view);
    assertEquals(0, page.destroyed);
    assertTrue(keeper.isAttached());
    assertEquals(1, keeper.attaches());
    assertEquals(1, page.navigations);
  }

  @Test
  public void configChangeDetachAlsoKeepsThePageButDoesNotRetire() {
    assertTrue(keeper.detach(first, BrowserPageKeeper.REASON_CONFIG));
    assertSame(retained, keeper.acquire(second));
    assertEquals(0, page.destroyed);
    assertEquals(0, keeper.retires());
  }

  @Test
  public void retireFromOwnerDestroysThePageAndReleasesTheKeeper() {
    assertTrue(keeper.retireFrom(first, BrowserPageKeeper.REASON_FINISH));
    assertEquals(1, page.destroyed);
    assertNull(keeper.page());
    assertEquals(BrowserPageKeeper.REASON_FINISH, keeper.lastRetiredReason());
    assertEquals(1, keeper.retires());
    assertFalse(keeper.retireFrom(first, BrowserPageKeeper.REASON_FINISH));
  }

  @Test
  public void oldActivityCannotRetireAPageTheNewActivityNowOwns() {
    assertTrue(keeper.detach(first, BrowserPageKeeper.REASON_SYSTEM));
    assertSame(retained, keeper.acquire(second));
    assertFalse(keeper.retireFrom(first, BrowserPageKeeper.REASON_FINISH));
    assertSame(page, keeper.page().view);
    assertEquals(0, page.destroyed);
  }

  @Test
  public void detachWithoutAPageRetiresTheEmptyShell() {
    keeper.retire("reset");
    var empty = keeper.acquire(first);
    assertNull(empty.view);
    assertFalse(keeper.detach(first, BrowserPageKeeper.REASON_SYSTEM));
    assertNull(keeper.page());
    assertEquals(BrowserPageKeeper.REASON_EMPTY, keeper.lastRetiredReason());
  }

  @Test
  public void trimMemoryRetiresOnlyDetachedPages() {
    assertFalse(
        "visible page must not be silently destroyed",
        keeper.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL));
    assertEquals(0, page.destroyed);
    assertFalse(
        "UI hidden alone is not a destroy signal",
        keeper.trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN));

    assertTrue(keeper.detach(first, BrowserPageKeeper.REASON_SYSTEM));
    assertTrue(keeper.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW));
    assertEquals(1, page.destroyed);
    assertNull(keeper.page());
    assertEquals(BrowserPageKeeper.REASON_TRIM, keeper.lastRetiredReason());
  }

  @Test
  public void completeTrimRetiresADetachedPage() {
    assertTrue(keeper.detach(first, BrowserPageKeeper.REASON_SYSTEM));
    assertTrue(keeper.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE));
    assertEquals(1, page.destroyed);
    assertNull(keeper.page());
  }

  @Test
  public void taskRemovalRetiresThePage() {
    assertTrue(keeper.detach(first, BrowserPageKeeper.REASON_SYSTEM));
    assertTrue(keeper.onTaskRemoved());
    assertEquals(1, page.destroyed);
    assertNull(keeper.page());
    assertEquals(BrowserPageKeeper.REASON_TASK_REMOVED, keeper.lastRetiredReason());
    assertFalse(keeper.onTaskRemoved());
  }

  @Test
  public void rendererGoneInTheDetachGapRetiresThePage() {
    assertTrue(keeper.detach(first, BrowserPageKeeper.REASON_SYSTEM));
    WebView failed = retained.view;
    retained.retireRendererView(failed);
    assertEquals(1, page.destroyed);
    assertNull(keeper.page());
    assertEquals(BrowserPageKeeper.REASON_RENDERER, keeper.lastRetiredReason());
  }

  @Test
  public void runtimeListenerRetiresDetachedPageWhenTheSessionChanges() {
    assertTrue(keeper.detach(first, BrowserPageKeeper.REASON_SYSTEM));
    WebBackgroundPageLifecycleTest.Controller.ready =
        new PreviewPageSession.Identity(
            8, "32345678-1234-1234-1234-123456789abc", "http://127.0.0.1:41822/?token=next");
    runRuntimeListeners();
    assertEquals(1, page.destroyed);
    assertNull(keeper.page());
    assertEquals(BrowserPageKeeper.REASON_WEB_SESSION, keeper.lastRetiredReason());
  }

  @Test
  public void runtimeListenerKeepsDetachedPageWhileTheSessionIsTransientlyRetained() {
    assertTrue(keeper.detach(first, BrowserPageKeeper.REASON_SYSTEM));
    WebBackgroundPageLifecycleTest.Controller.ready = null;
    WebBackgroundPageLifecycleTest.Controller.retained = true;
    runRuntimeListeners();
    assertEquals(0, page.destroyed);
    assertSame(page, keeper.page().view);

    WebBackgroundPageLifecycleTest.Controller.retained = false;
    runRuntimeListeners();
    assertEquals(1, page.destroyed);
    assertNull(keeper.page());
    assertEquals(BrowserPageKeeper.REASON_WEB_SESSION, keeper.lastRetiredReason());
  }

  @Test
  public void runtimeListenerNeverDestroysAPageAnActivityStillOwns() {
    WebBackgroundPageLifecycleTest.Controller.ready =
        new PreviewPageSession.Identity(
            8, "32345678-1234-1234-1234-123456789abc", "http://127.0.0.1:41822/?token=next");
    runRuntimeListeners();
    assertEquals(0, page.destroyed);
    assertSame(page, keeper.page().view);
  }

  @Test
  public void retireDetachedPageLeavesAnAttachedPageAlone() {
    assertFalse(keeper.retireDetachedPage(BrowserPageKeeper.REASON_ORIGIN_RESTORE));
    assertEquals(0, page.destroyed);
    assertTrue(keeper.detach(first, BrowserPageKeeper.REASON_SYSTEM));
    assertTrue(keeper.retireDetachedPage(BrowserPageKeeper.REASON_ORIGIN_RESTORE));
    assertEquals(1, page.destroyed);
    assertNull(keeper.page());
    assertEquals(BrowserPageKeeper.REASON_ORIGIN_RESTORE, keeper.lastRetiredReason());
  }

  @Test
  public void hasKeptPageTracksTheKeeperLifecycle() {
    assertTrue(BrowserPageKeeper.hasKeptPage());
    keeper.retire("test-cleanup");
    assertFalse(BrowserPageKeeper.hasKeptPage());
  }

  private static void runRuntimeListeners() {
    for (Runnable listener : List.copyOf(WebBackgroundPageLifecycleTest.Controller.pageListeners))
      listener.run();
  }
}
