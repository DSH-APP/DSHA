package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.*;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.BootPalette;
import com.deepseekharness.app.util.PreviewPageSession;
import com.deepseekharness.app.util.UiText;
import java.lang.reflect.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;

/** 实验性：对话页首次加载的原生加载页只盖在网页层上，页面出错或挂起时收起且不重复叠加。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = WebBackgroundPageLifecycleTest.App.class,
    shadows = WebBackgroundPageLifecycleTest.Controller.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WebPageBootOverlayTest {
  private static final PreviewPageSession.Identity ORIGINAL =
      new PreviewPageSession.Identity(
          7, "22345678-1234-1234-1234-123456789abc", "http://127.0.0.1:41821/?token=fixed");

  private WebBackgroundPageLifecycleTest.Browser browser;
  private WebView page;
  private FrameLayout layer;
  private FrameLayout container;
  private View panel;
  private String previousLanguage;

  private void set(String name, Object value) throws Exception {
    Field field = WebPreviewActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(browser, value);
  }

  private Object get(String name) throws Exception {
    Field field = WebPreviewActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(browser);
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

  private static void idle() {
    ((org.robolectric.shadows.ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
  }

  @Before
  public void prepare() throws Exception {
    previousLanguage = UiText.language();
    UiText.setLanguage("en");
    WebBackgroundPageLifecycleTest.Controller.value = Shadow.newInstanceOf(HarnessController.class);
    WebBackgroundPageLifecycleTest.Controller.ready = ORIGINAL;
    WebBackgroundPageLifecycleTest.Controller.exited = false;
    WebBackgroundPageLifecycleTest.Controller.protection =
        new com.deepseekharness.app.util.WebStartProtection(
            new com.deepseekharness.app.util.WebLifecycle(),
            android.os.SystemClock::elapsedRealtime);
    WebBackgroundPageLifecycleTest.Controller.callbacks.clear();
    browser =
        Robolectric.buildActivity(
                WebBackgroundPageLifecycleTest.Browser.class,
                new Intent(
                    RuntimeEnvironment.getApplication(),
                    WebBackgroundPageLifecycleTest.Browser.class))
            .get();
    page = new WebView(new MutableContextWrapper(browser));
    WebPreviewActivity.Retained retained = new WebPreviewActivity.Retained();
    retained.view = page;
    retained.owner = new java.lang.ref.WeakReference<>(browser);
    retained.pageSession.claim(ORIGINAL);
    // 与 activity_web_preview 相同：网页容器与错误面板同在一个内层容器里。
    layer = new FrameLayout(browser);
    container = new FrameLayout(browser);
    panel = new FrameLayout(browser);
    panel.setVisibility(View.GONE);
    layer.addView(container);
    layer.addView(panel);
    browser.views.put(R.id.web_error_browser, new Button(browser));
    set("retained", retained);
    set("webView", page);
    set("previewAuth", new PreviewAuth(browser));
    set("errorPanel", panel);
    set("errorTitle", new TextView(browser));
    set("errorDetail", new TextView(browser));
    set("progress", new ProgressBar(browser));
    set("container", container);
    set("baseUrl", "http://127.0.0.1:41821/");
    set("downloads", new WebDownloads(browser, null));
  }

  @After
  public void finish() {
    try {
      page.destroy();
      idle();
    } finally {
      UiText.setLanguage(previousLanguage);
    }
  }

  private View overlay() {
    return layer.getChildCount() > 2 ? layer.getChildAt(layer.getChildCount() - 1) : null;
  }

  private static boolean hasText(View view, String text) {
    if (view instanceof TextView label && text.contentEquals(label.getText())) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasText(group.getChildAt(i), text)) return true;
    return false;
  }

  @Test
  public void bootCoversThePageLayerOnceWithTheLastVerifiedWebBackground() throws Exception {
    new ConfigStore(browser).setEntryWebBackground(0xFF141414);
    invoke("showPageBoot", new Class<?>[0]);
    invoke("showPageBoot", new Class<?>[0]);
    View boot = overlay();
    assertNotNull(boot);
    assertEquals("only one overlay above the page and error panel", 3, layer.getChildCount());
    assertEquals(
        BootPalette.fromWeb(0xFF141414, 0).background,
        ((ColorDrawable) boot.getBackground()).getColor());
    assertTrue(hasText(boot, "Opening conversation…"));
    assertTrue(hasText(boot, "HARNESS"));
  }

  @Test
  public void withoutAWebRecordTheBootUsesTheHostShellBackground() throws Exception {
    invoke("showPageBoot", new Class<?>[0]);
    assertEquals(
        BootScreen.shellPalette(browser).background,
        ((ColorDrawable) overlay().getBackground()).getColor());
  }

  @Test
  public void errorPanelIsNeverHiddenBehindTheBoot() throws Exception {
    invoke("showPageBoot", new Class<?>[0]);
    invoke("showError", new Class<?>[] {String.class, String.class}, "Page failed", "HTTP 500");
    idle();
    assertNull(get("pageBoot"));
    assertNull(overlay());
    assertEquals(View.VISIBLE, panel.getVisibility());
  }

  @Test
  public void suspendedRuntimePageDismissesTheBoot() throws Exception {
    invoke("showPageBoot", new Class<?>[0]);
    invoke("suspendRuntimePage", new Class<?>[] {boolean.class}, false);
    idle();
    assertNull(get("pageBoot"));
    assertNull(overlay());
  }

  @Test
  public void aContainerWithoutLayerSkipsTheBootWithoutFailing() throws Exception {
    layer.removeView(container);
    invoke("showPageBoot", new Class<?>[0]);
    assertNull(get("pageBoot"));
  }
}
