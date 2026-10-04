package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.*;
import android.view.*;
import android.webkit.*;
import androidx.webkit.*;
import java.util.function.Consumer;

/** localhost 与正式页面的 127.0.0.1 分开 Cookie；不提供任意本地文件或原生 JS 接口。 */
final class RecoveryWebSurface implements RecoveryBrowserSurface {
  private final Context app;
  private final MutableContextWrapper context;
  private WebView view;
  private Consumer<String> error;
  private String origin = "";
  private String language = "zh";
  private ScriptHandler script;
  private boolean loaded;
  private WebViewMicrophone microphoneRequests;

  RecoveryWebSurface(Context app) {
    this.app = app.getApplicationContext();
    context = new MutableContextWrapper(this.app);
  }

  public View attach(Activity activity, Consumer<String> failed) {
    context.setBaseContext(activity);
    error = failed;
    if (view == null) {
      view = new WebView(context);
      WebSettings settings = view.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setDomStorageEnabled(true);
      settings.setAllowFileAccess(false);
      settings.setAllowContentAccess(false);
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
      view.setWebViewClient(
          new WebViewClient() {
            @Override
            public void onPageStarted(WebView web, String url, android.graphics.Bitmap icon) {
              if (microphoneRequests != null) microphoneRequests.cancel();
            }

            @Override
            public void onPageFinished(WebView web, String url) {
              if (web != view
                  || !com.deepseekharness.app.util.RecoveryLocalePolicy.sameOrigin(origin, url))
                return;
              // 前置 ES 兼容已由受管 HTML 安装。旧 WebView 缺文档起始注入时，
              // 这里只补语言选择监听；不能把页面完成回调当作早期 API 补丁。
              if (com.deepseekharness.app.util.RecoveryLocalePolicy.needsFinishedPageLanguageBridge(
                  origin,
                  url,
                  WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)))
                web.evaluateJavascript(WebPageScripts.language(app), null);
              syncLanguage(language);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
              return !allowed(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView web, String url) {
              return !allowed(url);
            }

            @Override
            public void onReceivedError(
                WebView web, WebResourceRequest request, WebResourceError failure) {
              if (request.isForMainFrame()) fail(failure.getDescription().toString());
            }

            @Override
            public void onReceivedHttpError(
                WebView web, WebResourceRequest request, WebResourceResponse response) {
              if (request.isForMainFrame()) fail("HTTP " + response.getStatusCode());
            }

            @Override
            public boolean onRenderProcessGone(WebView web, RenderProcessGoneDetail detail) {
              if (microphoneRequests != null) microphoneRequests.cancel();
              fail("RECOVERY_RENDERER_GONE");
              if (web.getParent() instanceof ViewGroup)
                ((ViewGroup) web.getParent()).removeView(web);
              web.destroy();
              view = null;
              loaded = false;
              return true;
            }
          });
    }
    if (microphoneRequests != null) microphoneRequests.cancel();
    if (activity instanceof RecoveryWebActivity) {
      RecoveryWebActivity owner = (RecoveryWebActivity) activity;
      WebView attached = view;
      WebViewMicrophone audio =
          new WebViewMicrophone(
              owner.microphone(),
              attached,
              () -> origin,
              () ->
                  view == attached
                      && context.getBaseContext() == owner
                      && owner.microphoneSessionCurrent());
      microphoneRequests = audio;
      view.setWebChromeClient(
          new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
              audio.request(request);
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
              audio.cancelled(request);
            }
          });
    }
    if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    if (!origin.isEmpty()) installLanguageListener();
    return view;
  }

  private boolean allowed(String url) {
    return !origin.isEmpty()
        && (url.equals(origin.substring(0, origin.length() - 1)) || url.startsWith(origin));
  }

  private void fail(String detail) {
    if (microphoneRequests != null) microphoneRequests.cancel();
    if (error != null) error.accept(detail);
  }

  public void load(String auth, String base, String cookie) {
    if (view == null || loaded) return;
    var launch = com.deepseekharness.app.util.RecoveryLocalePolicy.launch(base, auth);
    if (launch == null) {
      fail("RECOVERY_ORIGIN_INVALID");
      return;
    }
    origin = launch.origin;
    String localAuth = launch.authUrl;
    language = new com.deepseekharness.app.core.ConfigStore(app).getUiLanguage();
    installLanguageListener();
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
      script =
          WebViewCompat.addDocumentStartJavaScript(
              view,
              WebPageScripts.emergencyCompatibility(app),
              java.util.Set.of(origin.substring(0, origin.length() - 1)));
    CookieManager cookies = CookieManager.getInstance();
    cookies.setAcceptCookie(true);
    cookies.setAcceptThirdPartyCookies(view, false);
    // rc2 的 Cookie 名与签名都绑定 Host authority。使用 localhost 的真实鉴权流程，
    // 不能将原生在 127.0.0.1 得到的 Cookie 直接搬到另一主机名。
    loaded = true;
    view.loadUrl(localAuth);
  }

  private void installLanguageListener() {
    if (view == null
        || origin.isEmpty()
        || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
    WebViewCompat.removeWebMessageListener(view, "DshaLanguage");
    WebViewCompat.addWebMessageListener(
        view,
        "DshaLanguage",
        java.util.Set.of(origin.substring(0, origin.length() - 1)),
        (web, message, sourceOrigin, isMainFrame, reply) -> {
          Activity current =
              context.getBaseContext() instanceof Activity
                  ? (Activity) context.getBaseContext()
                  : null;
          if (web != view
              || current == null
              || current.isFinishing()
              || current.isDestroyed()
              || !isMainFrame
              || !com.deepseekharness.app.util.RecoveryLocalePolicy.sameOrigin(
                  origin, sourceOrigin.toString())) return;
          String selected = message.getData();
          if (com.deepseekharness.app.util.RecoveryLocalePolicy.supportedLanguage(selected))
            LanguageController.select(current, selected);
        });
  }

  public void syncLanguage(String value) {
    if (!com.deepseekharness.app.util.RecoveryLocalePolicy.supportedLanguage(value)) return;
    language = value;
    WebView current = view;
    if (current == null
        || !loaded
        || !com.deepseekharness.app.util.RecoveryLocalePolicy.sameOrigin(origin, current.getUrl()))
      return;
    current.evaluateJavascript(
        com.deepseekharness.app.util.RecoveryLocalePolicy.pageScript(value), null);
  }

  public boolean back() {
    if (view != null && view.canGoBack()) {
      view.goBack();
      return true;
    }
    return false;
  }

  public void detach() {
    error = null;
    if (microphoneRequests != null) {
      microphoneRequests.cancel();
      microphoneRequests = null;
    }
    if (view != null) {
      if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        WebViewCompat.removeWebMessageListener(view, "DshaLanguage");
      view.setWebChromeClient(null);
      if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }
    context.setBaseContext(app);
  }

  public void close() {
    detach();
    if (script != null) script.remove();
    if (view != null) view.destroy();
    view = null;
    loaded = false;
  }
}
