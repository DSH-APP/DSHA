package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import com.deepseekharness.app.DshaApp;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

/** Real WebView clients and native lifecycle; does not crash a renderer or user data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = RendererSurfaceLifecycleTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RendererSurfaceLifecycleTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Test
  public void deadRendererPortCannotLeaveTheBlobWriterRunning() {
    WebView view = new WebView(RuntimeEnvironment.getApplication());
    WebBlobDownload blob =
        new WebBlobDownload(view, new WebDownloadModel(RuntimeEnvironment.getApplication()));
    ReflectionHelpers.setField(
        blob,
        "port",
        // The framework's constructor is hidden by newer compile SDKs; use Robolectric's
        // public port implementation and fail only the provider close operation.
        new org.robolectric.fakes.RoboWebMessagePort() {
          @Override
          public void close() {
            throw new IllegalStateException("renderer exited");
          }
        });
    try {
      blob.close();
      fail("The simulated provider must throw");
    } catch (IllegalStateException expected) {
      java.util.concurrent.ExecutorService writer = ReflectionHelpers.getField(blob, "writer");
      assertTrue(writer.isShutdown());
      assertNull(ReflectionHelpers.getField(blob, "port"));
    } finally {
      blob.close();
      view.destroy();
    }
  }

  @Test
  public void originRestoreRendererDeathDetachesAndClosesWithoutReplayingMigration() {
    var surface = new BrowserOriginRestoreWebView(RuntimeEnvironment.getApplication());
    WebView original = (WebView) surface.view();
    FrameLayout parent = new FrameLayout(RuntimeEnvironment.getApplication());
    parent.addView(original);
    surface.load("http://127.0.0.1:41821/__dsha/browser-origin-restore", "{}");
    var client = original.getWebViewClient();
    assertTrue(
        client.onRenderProcessGone(original, WebBackgroundPageLifecycleTest.rendererExit(true)));
    assertNull(surface.view());
    assertNull(original.getParent());
    surface.close();
    assertTrue(
        client.onRenderProcessGone(original, WebBackgroundPageLifecycleTest.rendererExit(true)));
    try {
      surface.load("http://127.0.0.1:41821/", "{}");
      fail("A retired migration document must not be replayed");
    } catch (IllegalStateException expected) {
      assertEquals("RESTORE_RENDERER_CLOSED", expected.getMessage());
    }
  }

  @Test
  public void communityRendererDeathWaitsForExplicitRetryAndNeverReusesDeadWebView() {
    var lifecycle = Robolectric.buildActivity(CommunityActivity.class).setup();
    try {
      CommunityActivity owner = lifecycle.get();
      WebView original = ReflectionHelpers.getField(owner, "browser");
      var client = original.getWebViewClient();
      assertTrue(
          client.onRenderProcessGone(original, WebBackgroundPageLifecycleTest.rendererExit(false)));
      assertNull(ReflectionHelpers.getField(owner, "browser"));
      assertNull(original.getParent());
      ViewGroup root = ReflectionHelpers.getField(owner, "root");
      ViewGroup bar = (ViewGroup) root.getChildAt(0);
      ((Button) bar.getChildAt(2)).performClick();
      WebView replacement = ReflectionHelpers.getField(owner, "browser");
      assertNotNull(replacement);
      assertNotSame(original, replacement);
      assertSame(root, replacement.getParent());
      assertTrue(
          client.onRenderProcessGone(original, WebBackgroundPageLifecycleTest.rendererExit(true)));
      assertSame(replacement, ReflectionHelpers.getField(owner, "browser"));
    } finally {
      lifecycle.pause().stop().destroy();
    }
  }

  @Test
  public void communityBackAndStateSaveRemainAvailableAfterRendererDeath() {
    var lifecycle = Robolectric.buildActivity(CommunityActivity.class).setup();
    try {
      CommunityActivity owner = lifecycle.get();
      WebView original = ReflectionHelpers.getField(owner, "browser");
      original
          .getWebViewClient()
          .onRenderProcessGone(original, WebBackgroundPageLifecycleTest.rendererExit(true));
      lifecycle.saveInstanceState(new android.os.Bundle());
      owner.getOnBackPressedDispatcher().onBackPressed();
      assertTrue(owner.isFinishing());
    } finally {
      lifecycle.pause().stop().destroy();
    }
  }
}
