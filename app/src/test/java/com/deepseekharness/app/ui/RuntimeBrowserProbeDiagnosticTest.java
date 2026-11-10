package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.app.Application;
import android.net.Uri;
import android.webkit.WebView;
import androidx.fragment.app.FragmentActivity;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.deepseekharness.app.util.TrialRendererDiagnostic;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

@RunWith(WebFullscreenUiImeTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {
      RuntimeBrowserProbeDiagnosticTest.Features.class,
      RuntimeBrowserProbeDiagnosticTest.Bridge.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class RuntimeBrowserProbeDiagnosticTest {
  @Implements(value = WebViewFeature.class, isInAndroidSdk = false)
  public static final class Features {
    static boolean reject;

    @Implementation
    protected static boolean isFeatureSupported(String feature) {
      if (reject) throw new UnsupportedOperationException("fixture");
      return WebViewFeature.WEB_MESSAGE_LISTENER.equals(feature);
    }
  }

  @Implements(value = WebViewCompat.class, isInAndroidSdk = false)
  public static final class Bridge {
    static WebView view;
    static Set<String> origins;
    static WebViewCompat.WebMessageListener listener;
    static boolean reject;

    @Implementation
    protected static void addWebMessageListener(
        WebView web, String name, Set<String> rules, WebViewCompat.WebMessageListener callback) {
      if (reject) throw new UnsupportedOperationException("fixture");
      assertEquals("DshaTrialDiagnostic", name);
      view = web;
      origins = rules;
      listener = callback;
    }
  }

  private AutoCloseable open(FragmentActivity activity, TrialRendererDiagnostic diagnostic)
      throws Exception {
    var method =
        RuntimeBrowserProbe.class.getDeclaredMethod(
            "webView",
            FragmentActivity.class,
            String.class,
            String.class,
            Consumer.class,
            TrialRendererDiagnostic.class);
    method.setAccessible(true);
    return (AutoCloseable)
        method.invoke(
            null,
            activity,
            "http://127.0.0.1:41821/",
            "fixture-cookie",
            (Consumer<String>) ignored -> fail("Diagnostic transport must not fail the trial"),
            diagnostic);
  }

  private String report() throws Exception {
    var value = new org.json.JSONObject().put("nonce", "a".repeat(32)).put("postHttpStatus", 200);
    for (String key : TrialRendererDiagnostic.SCRIPT_FLAGS) value.put(key, true);
    return value.toString();
  }

  @Test
  public void onlyLiveTopLevelSameOriginReportsReachTheNonceBoundProjection() throws Exception {
    Bridge.reject = false;
    var controller = Robolectric.buildActivity(FragmentActivity.class).setup();
    var diagnostic = new TrialRendererDiagnostic();
    try (var handle = open(controller.get(), diagnostic)) {
      assertEquals(Set.of("http://127.0.0.1:41821"), Bridge.origins);
      var payload = new WebMessageCompat(report());
      Bridge.listener.onPostMessage(
          Bridge.view,
          new WebMessageCompat(new byte[] {1, 2}),
          Uri.parse("http://127.0.0.1:41821"),
          true,
          null);
      assertEquals(false, diagnostic.snapshot("a".repeat(32)).get("scriptReportReceived"));
      Bridge.listener.onPostMessage(
          Bridge.view, payload, Uri.parse("http://127.0.0.1:41821"), false, null);
      Bridge.listener.onPostMessage(
          Bridge.view, payload, Uri.parse("http://127.0.0.1:41822"), true, null);
      assertEquals(false, diagnostic.snapshot("a".repeat(32)).get("scriptReportReceived"));
      Bridge.listener.onPostMessage(
          Bridge.view, payload, Uri.parse("http://127.0.0.1:41821"), true, null);
      assertEquals(true, diagnostic.snapshot("a".repeat(32)).get("nonceMatched"));
      assertEquals(false, diagnostic.snapshot("b".repeat(32)).get("nonceMatched"));
    } finally {
      controller.pause().stop().destroy();
    }
  }

  @Test
  public void unsupportedDiagnosticBridgeDoesNotChangeBrowserConstructionOrReadyGate()
      throws Exception {
    Bridge.reject = true;
    var controller = Robolectric.buildActivity(FragmentActivity.class).setup();
    var diagnostic = new TrialRendererDiagnostic();
    try (var handle = open(controller.get(), diagnostic)) {
      assertEquals(false, diagnostic.snapshot("a".repeat(32)).get("bridgeSupported"));
      assertEquals(false, diagnostic.snapshot("a".repeat(32)).get("scriptReportReceived"));
    } finally {
      Bridge.reject = false;
      controller.pause().stop().destroy();
    }
  }

  @Test
  public void capabilityProbeFailureIsAlsoBestEffort() throws Exception {
    Features.reject = true;
    var controller = Robolectric.buildActivity(FragmentActivity.class).setup();
    var diagnostic = new TrialRendererDiagnostic();
    try (var handle = open(controller.get(), diagnostic)) {
      assertEquals(false, diagnostic.snapshot("a".repeat(32)).get("bridgeSupported"));
    } finally {
      Features.reject = false;
      controller.pause().stop().destroy();
    }
  }
}
