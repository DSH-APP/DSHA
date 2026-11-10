package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import com.deepseekharness.app.ForegroundActivity;
import com.deepseekharness.app.backup.BackupControl;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 界面重建只重挂浏览内核；试运行进程仍属于宿主维护任务，不由 Activity 管理。 */
public final class RuntimeBrowserProbe {
  private RuntimeBrowserProbe() {}

  public static com.deepseekharness.app.runtime.RuntimeTrial.Renderer open(
      Context context, String authUrl, String baseUrl, String cookie, BackupControl control)
      throws Exception {
    AtomicBoolean closed = new AtomicBoolean(), attached = new AtomicBoolean();
    AtomicReference<Exception> failure = new AtomicReference<>();
    AtomicReference<com.deepseekharness.app.util.TrialRendererDiagnostic> diagnostic =
        new AtomicReference<>(new com.deepseekharness.app.util.TrialRendererDiagnostic());
    Handler main = new Handler(Looper.getMainLooper());
    final class Surface implements Runnable {
      WeakReference<androidx.fragment.app.FragmentActivity> owner = new WeakReference<>(null);
      AutoCloseable view;

      void detach() {
        if (view != null)
          try {
            view.close();
          } catch (Exception ignored) {
          }
        view = null;
        owner.clear();
      }

      public void run() {
        if (owner.get() != null && (owner.get().isDestroyed() || owner.get().isFinishing()))
          detach();
        if (closed.get()) {
          detach();
          return;
        }
        var activity = ForegroundActivity.current();
        diagnostic.get().nativeFlag("ownerResumed", ForegroundActivity.isResumed(activity));
        if (!ForegroundActivity.isResumed(activity)) {
          main.postDelayed(this, 500);
          return;
        }
        try {
          if (owner.get() != activity) {
            detach();
            owner = new WeakReference<>(activity);
            var facts = new com.deepseekharness.app.util.TrialRendererDiagnostic();
            facts.nativeFlag("ownerResumed", true);
            diagnostic.set(facts); // Callbacks from an old attachment keep only its old facts.
            java.util.function.Consumer<String> failed = code -> failure.set(new IOException(code));
            if (PreviewFallback.preferred(activity)) view = gecko(activity, authUrl, failed);
            else
              try {
                view = webView(activity, baseUrl, cookie, failed, facts);
              } catch (RuntimeException | LinkageError error) {
                if (!false) throw error;
                view = gecko(activity, authUrl, failed);
              }
            attached.set(true);
          }
          main.postDelayed(this, 500);
        } catch (Exception | LinkageError error) {
          failure.set(new IOException("TRIAL_BROWSER_UNAVAILABLE", error));
          detach();
        }
      }
    }
    Surface surface = new Surface();
    main.post(surface);
    com.deepseekharness.app.runtime.RuntimeTrial.Renderer close =
        new com.deepseekharness.app.runtime.RuntimeTrial.Renderer() {
          public void close() {
            closed.set(true);
            main.removeCallbacks(surface);
            main.post(surface::detach);
          }

          public void check() throws IOException {
            if (failure.get() != null)
              throw new IOException("TRIAL_BROWSER_UNAVAILABLE", failure.get());
          }

          public java.util.Map<String, Object> diagnostics(String expectedNonce) {
            return diagnostic.get().snapshot(expectedNonce);
          }
        };
    try {
      while (!attached.get() && failure.get() == null) {
        control.check();
        Thread.sleep(100);
      }
      if (failure.get() != null) throw failure.get();
      return close;
    } catch (Exception error) {
      close.close();
      throw error;
    }
  }

  private static AutoCloseable gecko(
      androidx.fragment.app.FragmentActivity activity,
      String url,
      java.util.function.Consumer<String> failed)
      throws Exception {
    return (AutoCloseable)
        Class.forName("com.deepseekharness.app.ui.GeckoRuntimeProbe")
            .getMethod(
                "open",
                androidx.fragment.app.FragmentActivity.class,
                String.class,
                java.util.function.Consumer.class)
            .invoke(null, activity, url, failed);
  }

  private static AutoCloseable webView(
      androidx.fragment.app.FragmentActivity activity,
      String url,
      String cookie,
      java.util.function.Consumer<String> failed,
      com.deepseekharness.app.util.TrialRendererDiagnostic diagnostic) {
    FrameLayout container = new FrameLayout(activity);
    WebView view = new WebView(activity);
    AtomicBoolean alive = new AtomicBoolean(true);
    Handler main = new Handler(Looper.getMainLooper());
    Runnable sample =
        new Runnable() {
          public void run() {
            if (!alive.get()) return;
            diagnostic.nativeFlag("viewAttached", view.isAttachedToWindow());
            diagnostic.nativeFlag("viewShown", view.isShown());
            diagnostic.nativeFlag("windowVisible", view.getWindowVisibility() == View.VISIBLE);
            diagnostic.nativeFlag("sizePositive", view.getWidth() > 0 && view.getHeight() > 0);
            main.postDelayed(this, 10_000);
          }
        };
    try {
      var settings = view.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setDomStorageEnabled(true);
      settings.setAllowFileAccess(false);
      settings.setAllowContentAccess(false);
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
      try {
        boolean bridge =
            androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER);
        diagnostic.nativeFlag("bridgeSupported", bridge);
        if (bridge)
          androidx.webkit.WebViewCompat.addWebMessageListener(
              view,
              "DshaTrialDiagnostic",
              java.util.Set.of(url.substring(0, url.length() - 1)),
              (source, message, origin, isMainFrame, reply) -> {
                if (!alive.get()
                    || source != view
                    || !isMainFrame
                    || origin == null
                    || message == null
                    || !com.deepseekharness.app.util.WebPreviewPolicy.sameService(
                        url, origin.toString())) return;
                try {
                  if (message.getType() != androidx.webkit.WebMessageCompat.TYPE_STRING) return;
                  String text = message.getData();
                  if (text == null || text.length() > 2048) return;
                  org.json.JSONObject value = new org.json.JSONObject(text);
                  java.util.Map<String, Object> flags = new java.util.LinkedHashMap<>();
                  for (String key :
                      com.deepseekharness.app.util.TrialRendererDiagnostic.SCRIPT_FLAGS)
                    flags.put(key, value.opt(key));
                  flags.put("postHttpStatus", value.opt("postHttpStatus"));
                  diagnostic.accept(value.optString("nonce", ""), flags);
                } catch (org.json.JSONException | RuntimeException invalid) {
                  // Diagnosis has no authority to change acceptance or initiate another request.
                }
              });
      } catch (RuntimeException | LinkageError unavailable) {
        diagnostic.nativeFlag("bridgeSupported", false);
      }
      view.setWebViewClient(
          new WebViewClient() {
            @Override
            public void onPageStarted(WebView web, String ignored, android.graphics.Bitmap icon) {
              diagnostic.nativeFlag("pageStarted", true);
            }

            @Override
            public void onPageFinished(WebView web, String ignored) {
              diagnostic.nativeFlag("pageFinished", true);
            }

            @Override
            public void onReceivedHttpError(
                WebView web, WebResourceRequest request, WebResourceResponse response) {
              if (request.isForMainFrame()) diagnostic.mainHttpStatus(response.getStatusCode());
            }

            @Override
            public void onReceivedError(
                WebView web, WebResourceRequest request, WebResourceError error) {
              if (request.isForMainFrame()) diagnostic.mainLoadError(error.getErrorCode());
            }

            @Override
            public boolean onRenderProcessGone(WebView gone, RenderProcessGoneDetail detail) {
              diagnostic.nativeFlag("rendererGone", true);
              if (alive.getAndSet(false)) failed.accept("TRIAL_RENDERER_GONE");
              container.removeView(gone);
              gone.destroy();
              return true;
            }
          });
      container.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      container.setFocusable(false);
      container.setClickable(false);
      container.addView(view, new FrameLayout.LayoutParams(-1, -1));
      ViewGroup root = activity.findViewById(android.R.id.content);
      root.addView(
          container,
          0,
          new ViewGroup.LayoutParams(Math.max(1, root.getWidth()), Math.max(1, root.getHeight())));
      CookieManager manager = CookieManager.getInstance();
      manager.setAcceptCookie(true);
      manager.setAcceptThirdPartyCookies(view, false);
      manager.setCookie(
          url,
          cookie + "; Path=/; HttpOnly; SameSite=Strict",
          ok -> {
            diagnostic.nativeFlag("cookieCallback", true);
            diagnostic.nativeFlag("cookieAccepted", Boolean.TRUE.equals(ok));
            if (alive.get() && Boolean.TRUE.equals(ok)) view.loadUrl(url);
          });
      main.post(sample);
      return () -> {
        alive.set(false);
        main.removeCallbacks(sample);
        diagnostic.nativeFlag("closed", true);
        view.stopLoading();
        root.removeView(container);
        view.destroy();
      };
    } catch (RuntimeException | LinkageError error) {
      alive.set(false);
      main.removeCallbacks(sample);
      view.destroy();
      throw error;
    }
  }
}
