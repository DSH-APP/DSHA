package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.deepseekharness.app.R;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.PreviewPageSession;
import com.deepseekharness.app.util.WebPreviewPolicy;

/** 标准版预览：系统 WebView、异步鉴权、文件选择与可恢复的加载错误。 */
public class WebPreviewActivity extends PictureInPictureActivity implements WebFullscreenUi.Host {
  private static final String EXTRA_URL = "url";
  private static final String EXTRA_COOKIE = "cookie";
  private static final String STATE_PAGE_GENERATION = "browser-generation";
  private static final String STATE_PAGE_INSTANCE = "browser-instance";
  private static final String STATE_PAGE_URL = "browser-auth-url";
  // 检查真实页面能力，包括上游 polyfill 的结果，不凭伪装 UA 判断。
  private static final String CAPABILITY_CHECK =
      "(function(){var m=[];"
          + "if(!('noModule' in document.createElement('script')))m.push('JavaScript modules');"
          + "['Promise','fetch','WebSocket','TextEncoder','ReadableStream','AbortController']"
          + ".forEach(function(k){if(typeof window[k]==='undefined')m.push(k);});"
          + "if(typeof AbortSignal==='undefined'||typeof AbortSignal.any!=='function')m.push('AbortSignal.any');"
          + "if(typeof AbortSignal==='undefined'||typeof AbortSignal.timeout!=='function')m.push('AbortSignal.timeout');"
          + "if(typeof Promise==='undefined'||typeof Promise.withResolvers!=='function')m.push('Promise.withResolvers');"
          + "if(typeof Iterator==='undefined'||typeof Iterator.from!=='function'||typeof Iterator.prototype.filter!=='function')m.push('Iterator');"
          + "return m.join(', ');})()";

  private FrameLayout container;
  private View errorPanel;
  private TextView errorTitle;
  private TextView errorDetail;
  private ProgressBar progress;
  private WebView webView;
  private String authUrl;
  private String authCookie;
  private String baseUrl;
  private String browserInfo = com.deepseekharness.app.util.UiText.text("系统 WebView 版本未知");
  private boolean pageFailed;
  private boolean authRetried;
  private boolean geckoFallbackAttempted;
  private Retained retained;
  private WebDownloads downloads;
  private Bundle restoreState;
  private boolean navigatingBack;
  private PreviewAuth previewAuth;
  private long startupGeneration;
  private BrowserMicrophone microphone;
  private WebViewMicrophone microphoneRequests;
  private boolean browserResumed;
  private PreviewPageSession.Identity restoreIdentity;
  private PreviewPageSession.Identity pendingIdentity;
  private PreviewPageSession.Identity startupIdentity;
  private final Runnable runtimeChanged =
      () ->
          runOnUiThread(
              () -> {
                if (browserResumed || pictureInPictureActiveOrTransitioning())
                  synchronizeWebSession();
              });

  private static final class PendingUpload {
    final WebView view;
    final WebUploads.Session uploads;
    final ValueCallback<Uri[]> callback;
    final PreviewPageSession.Identity identity;
    final String pickerKey = "dsha-web-upload-" + java.util.UUID.randomUUID();
    ActivityResultLauncher<Intent> picker;
    WebPreviewActivity pickerOwner;
    final com.deepseekharness.app.util.BrowserUploadRequestState.Ticket<WebView, WebUploads.Session>
        ticket;
    final java.util.concurrent.atomic.AtomicBoolean completed =
        new java.util.concurrent.atomic.AtomicBoolean();

    PendingUpload(
        WebView view,
        WebUploads.Session uploads,
        ValueCallback<Uri[]> callback,
        PreviewPageSession.Identity identity,
        com.deepseekharness.app.util.BrowserUploadRequestState.Ticket<WebView, WebUploads.Session>
            ticket) {
      this.view = view;
      this.uploads = uploads;
      this.callback = callback;
      this.identity = identity;
      this.ticket = ticket;
    }

    boolean complete(Uri[] value) {
      if (!completed.compareAndSet(false, true)) return false;
      callback.onReceiveValue(value);
      return true;
    }
  }

  public static final class Retained extends androidx.lifecycle.ViewModel {
    WebView view;
    boolean ready;
    boolean needsStreamResume;
    String authUrl;
    final PreviewPageSession pageSession = new PreviewPageSession();
    final com.deepseekharness.app.util.PreviewNavigation navigation =
        new com.deepseekharness.app.util.PreviewNavigation();
    java.lang.ref.WeakReference<WebPreviewActivity> owner = new java.lang.ref.WeakReference<>(null);
    Bundle pendingHistory;
    androidx.webkit.ScriptHandler compatibilityScript;
    String scriptLanguage;
    com.deepseekharness.app.util.StartupPageGate pageEvents;
    long pageNavigation;
    WebBlobDownload blobDownload;
    PendingUpload pendingUpload;
    // 页面取消后仍等待旧系统结果排空，不能把它交给新页面的上传请求。
    PendingUpload pendingPicker;
    WebUploads.Session uploadSession;
    final com.deepseekharness.app.util.BrowserUploadRequestState<WebView, WebUploads.Session>
        uploadRequests = new com.deepseekharness.app.util.BrowserUploadRequestState<>();

    @Override
    protected void onCleared() {
      if (pendingUpload != null) {
        pendingUpload.complete(null);
        pendingUpload = null;
      }
      if (pendingPicker != null) {
        if (pendingPicker.picker != null) pendingPicker.picker.unregister();
        pendingPicker.picker = null;
        pendingPicker.pickerOwner = null;
        pendingPicker.complete(null);
        pendingPicker = null;
      }
      uploadRequests.close();
      if (view != null) view.destroy();
      view = null;
      if (blobDownload != null) blobDownload.close();
      if (uploadSession != null) {
        uploadSession.close();
        uploadSession = null;
      }
    }
  }

  private void registerFilePicker(PendingUpload request) {
    if (request.picker != null) return;
    request.pickerOwner = this;
    request.picker =
        getActivityResultRegistry()
            .register(
                request.pickerKey,
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                  PendingUpload pending = request;
                  unregisterFilePicker(pending);
                  if (retained.pendingPicker != pending) {
                    pending.complete(null);
                    return;
                  }
                  retained.pendingPicker = null;
                  if (retained.pendingUpload != pending
                      || !retained.uploadRequests.owns(
                          pending.ticket, retained.view, retained.uploadSession)
                      || !isCurrentPage(pending.view)) {
                    if (retained.pendingUpload == pending) {
                      retained.pendingUpload = null;
                      retained.uploadRequests.finish(pending.ticket);
                    }
                    pending.complete(null);
                    return;
                  }
                  Uri[] selected =
                      WebUploads.parseChooserResult(result.getResultCode(), result.getData());
                  if (selected != null) {
                    for (Uri uri : selected) {
                      // 只接收内容 URI，不向网页开放任意本地文件路径。
                      if (uri == null || !"content".equals(uri.getScheme())) {
                        selected = null;
                        break;
                      }
                    }
                  }
                  if (selected == null) {
                    retained.pendingUpload = null;
                    retained.uploadRequests.finish(pending.ticket);
                    pending.complete(null);
                    return;
                  }
                  final Uri[] chosen = selected;
                  final Retained owner = retained;
                  final Context app = getApplicationContext();
                  new Thread(
                          () -> {
                            WebUploads.Batch batch = null;
                            try {
                              batch = pending.uploads.copy(app, java.util.Arrays.asList(chosen));
                              Uri[] local = new Uri[batch.files().size()];
                              for (int i = 0; i < local.length; i++)
                                local[i] =
                                    androidx.core.content.FileProvider.getUriForFile(
                                        app,
                                        app.getPackageName() + ".updates",
                                        batch.files().get(i));
                              final WebUploads.Batch ready = batch;
                              new android.os.Handler(android.os.Looper.getMainLooper())
                                  .post(
                                      () -> {
                                        if (owner.pendingUpload != pending
                                            || !owner.uploadRequests.owns(
                                                pending.ticket, owner.view, owner.uploadSession)
                                            || !owner.pageSession.isCurrent(pending.identity)
                                            || !pending.identity.equals(
                                                com.deepseekharness.app.core.HarnessController.get(
                                                        app)
                                                    .getReadyWebPageIdentity())
                                            || pending.uploads.isClosed()
                                            || !ready.commit()) {
                                          ready.close();
                                          if (owner.pendingUpload == pending) {
                                            owner.pendingUpload = null;
                                            owner.uploadRequests.finish(pending.ticket);
                                          }
                                          pending.complete(null);
                                          return;
                                        }
                                        owner.pendingUpload = null;
                                        owner.uploadRequests.finish(pending.ticket);
                                        pending.complete(local);
                                      });
                            } catch (Exception error) {
                              if (batch != null) batch.close();
                              new android.os.Handler(android.os.Looper.getMainLooper())
                                  .post(
                                      () -> {
                                        if (owner.pendingUpload == pending) {
                                          owner.pendingUpload = null;
                                          owner.uploadRequests.finish(pending.ticket);
                                        }
                                        if (pending.complete(null) && !pending.uploads.isClosed())
                                          Toast.makeText(
                                                  app,
                                                  com.deepseekharness.app.util.UiText.format(
                                                      "上传失败：%s", error.getMessage()),
                                                  Toast.LENGTH_LONG)
                                              .show();
                                      });
                            }
                          },
                          "web-file-import")
                      .start();
                });
    // 无生命周期的注册可能同步交付旋转前暂存的结果，注册返回后再补一次释放。
    if (retained.pendingPicker != request) unregisterFilePicker(request);
  }

  private void unregisterFilePicker(PendingUpload pending) {
    if (pending != null && pending.pickerOwner == this && pending.picker != null) {
      pending.picker.unregister();
      pending.picker = null;
      pending.pickerOwner = null;
    }
  }

  public static Intent intent(Context ctx, String url, String cookie) {
    return new Intent(ctx, WebPreviewActivity.class)
        .putExtra(EXTRA_URL, url)
        .putExtra(EXTRA_COOKIE, cookie);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    microphone = new BrowserMicrophone(this);
    retained = new androidx.lifecycle.ViewModelProvider(this).get(Retained.class);
    retained.owner = new java.lang.ref.WeakReference<>(this);
    previewAuth = new PreviewAuth(this);
    downloads = new WebDownloads(this, savedInstanceState);
    restoreState =
        savedInstanceState == null ? null : savedInstanceState.getBundle("browser-state");
    restoreIdentity = savedPageIdentity(savedInstanceState);
    if (restoreIdentity == null) restoreState = null;
    setContentView(R.layout.activity_web_preview);
    WebFullscreenUi.install(this);
    container = findViewById(R.id.web_container);
    errorPanel = findViewById(R.id.web_error_panel);
    errorTitle = findViewById(R.id.web_error_title);
    errorDetail = findViewById(R.id.web_error_detail);
    progress = findViewById(R.id.web_progress);
    findViewById(R.id.web_retry).setOnClickListener(v -> loadSession());
    findViewById(R.id.web_error_browser).setOnClickListener(v -> openExternal(authUrl));
    findViewById(R.id.web_error_logs)
        .setOnClickListener(v -> startActivity(DiagnosticActivity.downloadLogs(this)));
    findViewById(R.id.web_error_recovery)
        .setOnClickListener(
            v -> {
              startActivity(
                  new Intent(this, MainActivity.class)
                      .putExtra("open_launch", true)
                      .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
              finish();
            });
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                navigateBack();
              }
            });
    authUrl = getIntent().getStringExtra(EXTRA_URL);
    authCookie = getIntent().getStringExtra(EXTRA_COOKIE);
    PreviewPageSession.Identity current = currentWebIdentity();
    PreviewPageSession.Identity retainedIdentity = retained.pageSession.identity();
    if (current != null) {
      if (!current.authUrl().equals(authUrl)) authCookie = null;
      authUrl = current.authUrl();
    } else if (retainedIdentity != null) {
      authUrl = retainedIdentity.authUrl();
      authCookie = null;
    }
    if (current == null || !current.equals(restoreIdentity)) restoreState = null;
    baseUrl = WebPreviewPolicy.loopbackBaseUrl(authUrl);
    if (baseUrl == null) {
      authUrl = null;
      showError(
          com.deepseekharness.app.util.UiText.text("对话地址无效"),
          com.deepseekharness.app.util.UiText.text("请返回启动页，重新进入对话。"));
      return;
    }
    if (current != null
        && PreviewFallback.preferred(this)
        && PreviewFallback.open(this, authUrl, authCookie)) return;
    if (retained.view != null
        && retainedIdentity != null
        && (current == null || retained.pageSession.isCurrent(current))) {
      startupGeneration = retainedIdentity.generation();
      startupIdentity = retainedIdentity;
      webView = retained.view;
      ((android.content.MutableContextWrapper) webView.getContext()).setBaseContext(this);
      attachClients(webView);
      container.addView(webView, new FrameLayout.LayoutParams(-1, -1));
      WebFrameRate.apply(this, webView);
      progress.setVisibility(retained.ready ? View.GONE : View.VISIBLE);
      continueInitialNavigation();
    } else {
      // 旋转保留同一进程的页面；服务已换代时清理旧页面，再用当前凭据加载。
      if (retained.view != null) {
        webView = retained.view;
        destroyWebView();
      }
      loadSession();
    }
    if (retained.pendingPicker != null) {
      try {
        registerFilePicker(retained.pendingPicker);
      } catch (RuntimeException unavailable) {
        unregisterFilePicker(retained.pendingPicker);
        retained.pendingPicker = null;
        cancelFileSelection();
      }
    }
  }

  private void loadSession() {
    loadSession(true);
  }

  private void loadSession(boolean allowAuthRetry) {
    if (isFinishing() || isDestroyed() || previewAuth == null) return;
    PreviewPageSession.Identity expected = currentWebIdentity();
    if (expected == null) {
      cancelPendingAuthentication();
      showError(
          com.deepseekharness.app.util.UiText.text("暂时无法进入对话"),
          com.deepseekharness.app.util.UiText.text("Web 尚未就绪，请返回启动页查看服务状态"));
      return;
    }
    if (previewAuth.busy() && expected.equals(pendingIdentity)) return;
    cancelPendingAuthentication();
    cancelFileSelection();
    if (microphoneRequests != null) microphoneRequests.cancel();
    if (!expected.equals(restoreIdentity)) restoreState = null;
    if (!retained.pageSession.isCurrent(expected)) {
      retained.pendingHistory = null;
      navigatingBack = false;
      geckoFallbackAttempted = false;
    }
    pendingIdentity = expected;
    errorPanel.setVisibility(View.GONE);
    progress.setVisibility(View.VISIBLE);
    previewAuth.refresh(
        (url, cookie, error) -> {
          if (!expected.equals(pendingIdentity) || !expected.equals(currentWebIdentity())) return;
          pendingIdentity = null;
          if (error != null) {
            showError(com.deepseekharness.app.util.UiText.text("暂时无法进入对话"), error);
            return;
          }
          if (!url.equals(authUrl)) restoreState = null;
          authUrl = url;
          authCookie = cookie;
          baseUrl = WebPreviewPolicy.loopbackBaseUrl(url);
          startupGeneration = expected.generation();
          startupIdentity = expected;
          createWebView(allowAuthRetry, expected);
        });
  }

  private PreviewPageSession.Identity currentWebIdentity() {
    return com.deepseekharness.app.core.HarnessController.get(this).getReadyWebPageIdentity();
  }

  private void cancelPendingAuthentication() {
    pendingIdentity = null;
    if (previewAuth != null) previewAuth.cancel();
  }

  /** 单 task 再进入与就绪通知共用同一门禁，不按 URL 或切前台次数猜测需要刷新。 */
  private boolean synchronizeWebSession() {
    if (retained == null || previewAuth == null || isFinishing() || isDestroyed()) return false;
    PreviewPageSession.Identity current = currentWebIdentity();
    if (current == null) {
      cancelPendingAuthentication();
      cancelFileSelection();
      if (microphoneRequests != null) microphoneRequests.cancel();
      retained.pendingHistory = null;
      restoreState = null;
      restoreIdentity = null;
      return false;
    }
    if (webView != null && retained.pageSession.isCurrent(current)) return false;
    if (current.equals(pendingIdentity)) return true;
    loadSession();
    return true;
  }

  private boolean isCurrentPage(WebView view) {
    return retained != null
        && view != null
        && webView == view
        && retained.view == view
        && retained.owner.get() == this
        && !isFinishing()
        && !isDestroyed()
        && pendingIdentity == null
        && retained.pageSession.isCurrent(currentWebIdentity());
  }

  private static PreviewPageSession.Identity savedPageIdentity(Bundle state) {
    if (state == null) return null;
    try {
      return new PreviewPageSession.Identity(
          state.getLong(STATE_PAGE_GENERATION, 0),
          state.getString(STATE_PAGE_INSTANCE),
          state.getString(STATE_PAGE_URL));
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private void createWebView(boolean allowAuthRetry, PreviewPageSession.Identity identity) {
    destroyWebView();
    pageFailed = false;
    authRetried = !allowAuthRetry;
    errorPanel.setVisibility(View.GONE);
    progress.setVisibility(View.VISIBLE);
    try {
      WebView view = new WebView(new android.content.MutableContextWrapper(this));
      webView = view;
      retained.view = view;
      retained.authUrl = authUrl;
      retained.pageSession.claim(identity);
      retained.needsStreamResume = false;
      retained.pendingHistory = restoreState;
      restoreState = null;
      final Retained page = retained;
      final boolean hasCookie = authCookie != null && !authCookie.isEmpty();
      final long navigationTicket = page.navigation.begin(hasCookie);
      PackageInfo provider =
          android.os.Build.VERSION.SDK_INT >= 26 ? WebView.getCurrentWebViewPackage() : null;
      browserInfo =
          provider == null
              ? com.deepseekharness.app.util.UiText.text("系统 WebView 版本未知")
              : provider.packageName + " " + provider.versionName;
      Log.i("DSHA", com.deepseekharness.app.util.UiText.format("标准版预览内核: %s", browserInfo));
      com.deepseekharness.app.core.DiagnosticLog.record(this, "WEB_ENGINE", browserInfo);
      WebSettings settings = view.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setDomStorageEnabled(true);
      settings.setAllowFileAccess(false);
      // 网页只能获取用户选择后复制到专属 FileProvider 的 URI。
      settings.setAllowContentAccess(true);
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
      settings.setSupportMultipleWindows(false);
      settings.setLoadWithOverviewMode(true);
      settings.setUseWideViewPort(true);
      if (getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
          .getBoolean(Constants.KEY_DESKTOP_MODE, false)) {
        settings.setUserAgentString(
            WebPreviewPolicy.desktopUserAgent(settings.getUserAgentString()));
      }
      attachClients(view);
      container.addView(view, new FrameLayout.LayoutParams(-1, -1));
      WebFrameRate.apply(this, view);
      CookieManager cookies = CookieManager.getInstance();
      cookies.setAcceptCookie(true);
      cookies.setAcceptThirdPartyCookies(view, false);
      if (hasCookie) {
        // setCookie 是异步的：完成后才加载，避免首次进入偶发未认证。
        cookies.setCookie(
            baseUrl,
            authCookie + "; Path=/; HttpOnly; SameSite=Strict",
            ok -> {
              if (page.view != view
                  || !page.pageSession.isCurrent(
                      com.deepseekharness.app.core.HarnessController.get(getApplicationContext())
                          .getReadyWebPageIdentity())
                  || !page.navigation.cookieCompleted(navigationTicket, Boolean.TRUE.equals(ok)))
                return;
              WebPreviewActivity owner = page.owner.get();
              if (owner != null) owner.continueInitialNavigation();
            });
      } else {
        continueInitialNavigation();
      }
    } catch (RuntimeException | LinkageError e) {
      destroyWebView();
      if (identity.equals(currentWebIdentity()) && PreviewFallback.open(this, authUrl, authCookie))
        return;
      Log.w(
          "DSHA",
          com.deepseekharness.app.util.UiText.format(
              "系统 WebView 初始化失败: %s", e.getClass().getSimpleName()));
      showError(
          com.deepseekharness.app.util.UiText.text("系统 WebView 无法启动"),
          com.deepseekharness.app.util.UiText.text(
              "请更新或启用 Android System WebView / Chrome，也可以使用系统浏览器进入对话。"));
    }
  }

  private void continueInitialNavigation() {
    if (isFinishing()
        || isDestroyed()
        || retained.owner.get() != this
        || webView == null
        || retained.view != webView
        || !isCurrentPage(webView)
        || !retained.navigation.claim()) return;
    Bundle history = retained.pendingHistory;
    retained.pendingHistory = null;
    if (retained.navigation.cookieAccepted()
        && history != null
        && webView.restoreState(history) != null) return;
    webView.loadUrl(retained.navigation.cookieAccepted() ? baseUrl : authUrl);
  }

  private void attachClients(WebView view) {
    if (retained.pageEvents == null || !retained.pageEvents.owns(view))
      retained.pageEvents =
          new com.deepseekharness.app.util.StartupPageGate(view, startupGeneration, baseUrl);
    if (microphoneRequests != null) microphoneRequests.cancel();
    microphoneRequests =
        new WebViewMicrophone(
            microphone,
            view,
            () -> baseUrl,
            () ->
                isCurrentPage(view)
                    && !pageFailed
                    && WebPreviewPolicy.sameService(baseUrl, view.getUrl()));
    WebFileActions.attach(
        view,
        baseUrl,
        () -> {
          WebPreviewActivity host = retained.owner.get();
          return host != null && host.isCurrentPage(view) && !host.pageFailed;
        },
        () -> {
          if (retained.blobDownload == null)
            retained.blobDownload = new WebBlobDownload(view, downloads.model);
          return retained.blobDownload;
        },
        () -> retained.pageNavigation);
    updateDocumentLanguage(view);
    if (androidx.webkit.WebViewFeature.isFeatureSupported(
        androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) {
      androidx.webkit.WebViewCompat.removeWebMessageListener(view, "DshaLanguage");
      androidx.webkit.WebViewCompat.addWebMessageListener(
          view,
          "DshaLanguage",
          java.util.Collections.singleton(baseUrl.substring(0, baseUrl.length() - 1)),
          (source, message, origin, mainFrame, reply) -> {
            if (!isCurrentPage(source)
                || !mainFrame
                || !WebPreviewPolicy.sameService(baseUrl, source.getUrl())) return;
            try {
              String language = message.getData();
              // 网页只有「中文 / English」两个显式选项，不接受 system：
              // 把网页当成用户显式选择，避免页面误传偏好值后行为含糊。
              if (com.deepseekharness.app.util.UiLanguagePreference.EN.equals(language)
                  || com.deepseekharness.app.util.UiLanguagePreference.ZH.equals(language))
                LanguageController.select(this, language);
            } catch (IllegalStateException ignored) {
            }
          });
    }
    view.setWebViewClient(new PreviewClient());
    view.setWebChromeClient(new PreviewChromeClient(view, microphoneRequests));
    view.setDownloadListener(
        (url, agent, disposition, mime, length) -> {
          if (!isCurrentPage(view) || !WebPreviewPolicy.sameService(baseUrl, view.getUrl())) return;
          String name = android.webkit.URLUtil.guessFileName(url, disposition, mime);
          if (url.startsWith("blob:") || url.startsWith("data:")) {
            if (retained.blobDownload == null)
              retained.blobDownload = new WebBlobDownload(view, downloads.model);
            retained.blobDownload.start(baseUrl, url, name);
            return;
          }
          downloads.start(
              baseUrl, url, CookieManager.getInstance().getCookie(url), name, length, null);
        });
  }

  /** 保留网页实例时同步后续文档的启动脚本，避免重载时短暂回到旧语言。 */
  private void updateDocumentLanguage(WebView view) {
    if (!androidx.webkit.WebViewFeature.isFeatureSupported(
        androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return;
    String language = new com.deepseekharness.app.core.ConfigStore(this).getUiLanguage();
    if (retained.compatibilityScript != null && language.equals(retained.scriptLanguage)) return;
    if (retained.compatibilityScript != null) retained.compatibilityScript.remove();
    retained.compatibilityScript =
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
            view,
            WebPageScripts.compatibility(this, retained.pageEvents),
            java.util.Collections.singleton(baseUrl.substring(0, baseUrl.length() - 1)));
    retained.scriptLanguage = language;
  }

  private class PreviewClient extends WebViewClient {
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
      if (!isCurrentPage(view)) return true;
      if (WebPreviewPolicy.pageDownload(baseUrl, url)) return false;
      openExternal(url);
      return true;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
      if (!isCurrentPage(view)) return true;
      // 插件的 iframe / 内嵌预览保持 WebView 原有行为，只接管顶层导航。
      if (!request.isForMainFrame()) return false;
      String url = request.getUrl().toString();
      if (WebPreviewPolicy.pageDownload(baseUrl, url)) return false;
      if (request.hasGesture()) openExternal(url);
      return true;
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
      if (!isCurrentPage(view)) return;
      retained.pageNavigation++;
      if (retained.blobDownload != null) {
        retained.blobDownload.close();
        retained.blobDownload = null;
        downloads.model.cancel();
      }
      cancelFileSelection();
      if (microphoneRequests != null) microphoneRequests.cancel();
      retained.ready = false;
      refreshPictureInPicture();
      pageFailed = false;
      errorPanel.setVisibility(View.GONE);
      progress.setProgress(0);
      progress.setVisibility(View.VISIBLE);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
      if (!isCurrentPage(view) || pageFailed) return;
      final long navigation = retained.pageNavigation;
      progress.setVisibility(View.GONE);
      if (!WebPreviewPolicy.sameService(baseUrl, url)) return;
      retained.ready = true;
      refreshPictureInPicture();
      resumePageStreams();
      // 新内核已经在文档起始执行兼容脚本；这里只做轻量能力核验，避免每次页面完成
      // 又把数十 KB 的 polyfill 注入一次。旧内核没有 DOCUMENT_START_SCRIPT 时仍保留
      // 原来的页面完成注入路径。
      String compatibility =
          retained != null && retained.compatibilityScript != null
              ? CAPABILITY_CHECK
              : WebPageScripts.compatibility(WebPreviewActivity.this, retained.pageEvents)
                  + "\n"
                  + CAPABILITY_CHECK;
      view.evaluateJavascript(
          compatibility,
          result -> {
            if (!isCurrentPage(view) || retained.pageNavigation != navigation || pageFailed) return;
            try {
              Object missing = new org.json.JSONTokener(result).nextValue();
              if (!(missing instanceof String) || !((String) missing).isEmpty()) {
                if (openGeckoAfterWebViewFailure()) return;
                showError(
                    com.deepseekharness.app.util.UiText.text("系统 WebView 需要更新"),
                    com.deepseekharness.app.util.UiText.format(
                        "当前内核缺少：%s。\n更新 Android System WebView / Chrome 后重试，或在浏览器中打开。", missing));
              }
            } catch (org.json.JSONException | NullPointerException invalid) {
              if (!openGeckoAfterWebViewFailure())
                showError(
                    com.deepseekharness.app.util.UiText.text("系统 WebView 需要更新"),
                    com.deepseekharness.app.util.UiText.text("网页能力检查未返回有效结果，请更新内核后重试。"));
            }
          });
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
      if (!isCurrentPage(view) || !request.isForMainFrame()) return;
      showError(
          com.deepseekharness.app.util.UiText.text("暂时无法连接对话服务"),
          com.deepseekharness.app.util.UiText.format(
              "服务可能仍在启动或已退出。请稍后重试，持续失败时返回启动页查看日志。\n错误代码：%s", error.getErrorCode()));
    }

    @Override
    public void onReceivedHttpError(
        WebView view, WebResourceRequest request, WebResourceResponse response) {
      if (!isCurrentPage(view) || !request.isForMainFrame()) return;
      int code = response.getStatusCode();
      if ((code == 401 || code == 403) && !authRetried) {
        authRetried = true;
        authCookie = null;
        restoreState = null;
        loadSession(false);
        return;
      }
      showError(
          code == 401 || code == 403
              ? com.deepseekharness.app.util.UiText.text("对话认证已失效")
              : com.deepseekharness.app.util.UiText.text("对话页面加载失败"),
          com.deepseekharness.app.util.UiText.format("HTTP %s。请返回启动页重新进入对话，或稍后重试。", code));
    }

    @androidx.annotation.RequiresApi(26)
    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
      if (webView == view) {
        boolean currentPage = isCurrentPage(view);
        destroyWebView();
        if (!currentPage) return true;
        // A crashed renderer is an engine failure. Low may switch once
        // to Gecko after the dead WebView and its page resources close.
        if (detail.didCrash() && openGeckoAfterWebViewFailure()) return true;
        showError(
            com.deepseekharness.app.util.UiText.text("网页渲染进程已退出"),
            detail.didCrash()
                ? com.deepseekharness.app.util.UiText.text("系统 WebView 发生异常，点击重试可重新打开；持续出现时请更新内核。")
                : com.deepseekharness.app.util.UiText.text("系统可能因内存不足回收了网页，点击重试可重新打开。"));
      }
      return true;
    }
  }

  private boolean openGeckoAfterWebViewFailure() {
    if (geckoFallbackAttempted
        || isFinishing()
        || isDestroyed()
        || authUrl == null
        || !WebPreviewPolicy.sameService(baseUrl, authUrl)) return false;
    if (startupIdentity == null || !startupIdentity.equals(currentWebIdentity())) return false;
    geckoFallbackAttempted = true;
    cancelFileSelection();
    if (microphoneRequests != null) microphoneRequests.cancel();
    destroyWebView();
    return PreviewFallback.open(this, authUrl, authCookie);
  }

  private class PreviewChromeClient extends WebChromeClient {
    private final WebViewMicrophone audio;
    private final WebView sourceView;

    PreviewChromeClient(WebView view, WebViewMicrophone audio) {
      sourceView = view;
      this.audio = audio;
    }

    @Override
    public void onPermissionRequest(android.webkit.PermissionRequest request) {
      audio.request(request);
    }

    @Override
    public void onPermissionRequestCanceled(android.webkit.PermissionRequest request) {
      audio.cancelled(request);
    }

    @Override
    public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
      if (!isCurrentPage(sourceView)) return true;
      com.deepseekharness.app.core.StartupDiagnostics diagnostics =
          com.deepseekharness.app.core.HarnessController.get(WebPreviewActivity.this)
              .startupDiagnostics();
      if (message.message().startsWith("[DSHA_PAGE] ")
          && message.message().length() <= 9500
          && webView != null
          && WebPreviewPolicy.sameService(baseUrl, webView.getUrl())) {
        try {
          org.json.JSONObject event = new org.json.JSONObject(message.message().substring(12));
          final long navigation = retained.pageNavigation;
          final var gate = retained.pageEvents;
          String documentId = event.optString("documentId"), source = message.sourceId();
          if (gate == null || !gate.source().equals(source)) return true;
          sourceView.evaluateJavascript(
              "Boolean(window.__dshaStartupDocumentId==="
                  + org.json.JSONObject.quote(documentId)
                  + ")",
              proof -> {
                if (!isCurrentPage(sourceView) || retained.pageNavigation != navigation) return;
                long current =
                    com.deepseekharness.app.core.HarnessController.get(WebPreviewActivity.this)
                        .getWebGeneration();
                var controller =
                    com.deepseekharness.app.core.HarnessController.get(WebPreviewActivity.this);
                if (controller.isStopping() || controller.isUserStopped()) return;
                if (!gate.accept(
                    sourceView,
                    current,
                    sourceView.getUrl(),
                    source,
                    event.optString("nonce"),
                    event.optString("page"),
                    documentId,
                    event.optLong("sequence", -1),
                    "true".equals(proof),
                    event.optBoolean("fatal"),
                    "ready".equals(event.optString("type")))) return;
                if (diagnostics.pageEvent(startupGeneration, event)) {
                  com.deepseekharness.app.core.HarnessController.get(WebPreviewActivity.this)
                      .failedWebPage(startupGeneration, event.optString("message"));
                  startActivity(new Intent(WebPreviewActivity.this, StartupRecoveryActivity.class));
                  finish();
                }
              });
        } catch (Exception ignored) {
        }
        return true;
      }
      if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
        String source =
            android.net.Uri.parse(message.sourceId() == null ? "" : message.sourceId()).getPath();
        com.deepseekharness.app.core.DiagnosticLog.record(
            WebPreviewActivity.this,
            "WEB_JS",
            String.valueOf(source) + ":" + message.lineNumber() + " " + message.message());
        if (webView != null && WebPreviewPolicy.sameService(baseUrl, webView.getUrl()))
          diagnostics.browser(
              startupGeneration,
              String.valueOf(source) + ":" + message.lineNumber() + " " + message.message());
      }
      return true;
    }

    @Override
    public void onProgressChanged(WebView view, int value) {
      if (isCurrentPage(view) && !pageFailed) progress.setProgress(value);
    }

    @Override
    public boolean onShowFileChooser(
        WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
      if (retained.pendingPicker != null
          || pageFailed
          || !isCurrentPage(view)
          || !WebPreviewPolicy.sameService(baseUrl, view.getUrl())) {
        callback.onReceiveValue(null);
        return true;
      }
      cancelFileSelection();
      WebUploads.Session session = retained.uploadSession;
      if (session == null || session.isClosed())
        retained.uploadSession = session = new WebUploads.Session(getCacheDir());
      retained.pendingUpload =
          new PendingUpload(
              view,
              session,
              callback,
              retained.pageSession.identity(),
              retained.uploadRequests.begin(view, session));
      retained.pendingPicker = retained.pendingUpload;
      PendingUpload pending = retained.pendingUpload;
      Intent primary = null;
      try {
        registerFilePicker(pending);
        primary =
            WebUploadChooser.intent(
                WebUploadChooser.onlyImages(params.getAcceptTypes()) ? params.createIntent() : null,
                params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE,
                params.getAcceptTypes());
        pending.picker.launch(primary);
      } catch (RuntimeException e) {
        try {
          if (primary == null)
            primary =
                WebUploadChooser.intent(
                    null,
                    params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE,
                    params.getAcceptTypes());
          pending.picker.launch(WebUploads.fallback(primary));
        } catch (RuntimeException ignored) {
          unregisterFilePicker(pending);
          retained.pendingPicker = null;
          cancelFileSelection();
          Toast.makeText(
                  WebPreviewActivity.this,
                  com.deepseekharness.app.util.UiText.text("无法打开文件选择器，请启用系统文件应用"),
                  Toast.LENGTH_LONG)
              .show();
        }
      }
      return true;
    }
  }

  private void showError(String title, String detail) {
    if (isFinishing() || isDestroyed()) return;
    cancelFileSelection();
    if (microphoneRequests != null) microphoneRequests.cancel();
    com.deepseekharness.app.core.DiagnosticLog.record(
        this, "WEB_PAGE", title + com.deepseekharness.app.util.UiText.text("：") + detail);
    pageFailed = true;
    retained.ready = false;
    refreshPictureInPicture();
    progress.setVisibility(View.GONE);
    errorTitle.setText(title);
    errorDetail.setText(com.deepseekharness.app.util.UiText.raw(detail + "\n\n" + browserInfo));
    errorPanel.setVisibility(View.VISIBLE);
  }

  private void openExternal(String url) {
    if (url == null) return;
    if (PluginNavigation.open(this, url)) return;
    Uri uri = Uri.parse(url);
    if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) return;
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
    } catch (RuntimeException e) {
      Toast.makeText(
              this, com.deepseekharness.app.util.UiText.text("未找到可用的系统浏览器"), Toast.LENGTH_SHORT)
          .show();
    }
  }

  private void navigateBack() {
    if (navigatingBack) return;
    WebView current = webView;
    if (pageFailed
        || current == null
        || !isCurrentPage(current)
        || !WebPreviewPolicy.sameService(baseUrl, current.getUrl())) {
      finish();
      return;
    }
    navigatingBack = true;
    Runnable fallback =
        () -> {
          if (!navigatingBack || !isCurrentPage(current)) return;
          navigatingBack = false;
          if (current.canGoBack()) current.goBack();
          else leavePreview();
        };
    current.postDelayed(fallback, 1200);
    current.evaluateJavascript(
        WebPageScripts.back(this),
        result -> {
          if (!navigatingBack || !isCurrentPage(current)) return;
          if ("true".equals(result)) {
            navigatingBack = false;
            current.removeCallbacks(fallback);
          } else fallback.run();
        });
  }

  private void cancelFileSelection() {
    if (retained == null) return;
    retained.uploadRequests.invalidate();
    if (retained.pendingUpload == null) return;
    PendingUpload pending = retained.pendingUpload;
    retained.pendingUpload = null;
    pending.complete(null);
  }

  private void destroyWebView() {
    if (microphoneRequests != null) {
      microphoneRequests.cancel();
      microphoneRequests = null;
    }
    cancelFileSelection();
    WebView previous = webView;
    WebFrameRate.clear(this, previous);
    webView = null;
    if (retained != null) {
      retained.view = null;
      retained.authUrl = null;
      retained.ready = false;
      retained.needsStreamResume = false;
      retained.pageSession.clear();
      retained.pageEvents = null;
      retained.pageNavigation++;
      retained.navigation.cancel();
      retained.pendingHistory = null;
    }
    refreshPictureInPicture();
    if (retained != null) {
      retained.compatibilityScript = null;
      retained.scriptLanguage = null;
    }
    if (retained != null && retained.blobDownload != null) {
      retained.blobDownload.close();
      retained.blobDownload = null;
      downloads.model.cancel();
    }
    if (previous != null) {
      container.removeView(previous);
      previous.destroy();
    }
    if (retained != null && retained.uploadSession != null) {
      retained.uploadSession.close();
      retained.uploadSession = null;
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) {
      WebFullscreenUi.applySystemBars(this);
      if (webView != null) WebFrameRate.apply(this, webView);
    }
  }

  @Override
  protected void onPause() {
    browserResumed = false;
    com.deepseekharness.app.core.PluginRepository.get(this).invalidateInstalledState();
    if (!pictureInPictureActiveOrTransitioning()) WebFrameRate.clear(this, webView);
    super.onPause();
  }

  @Override
  protected boolean pictureInPictureContentReady() {
    return webView != null
        && retained != null
        && retained.ready
        && !pageFailed
        && isCurrentPage(webView)
        && WebPreviewPolicy.sameService(baseUrl, webView.getUrl());
  }

  // 画中画仍可见但 Activity 已暂停；完全不可见时才暂停网页绘制。
  @Override
  protected void onStop() {
    com.deepseekharness.app.core.HarnessController.get(this)
        .removeReadyWebPageListener(runtimeChanged);
    if (!pictureInPictureActiveOrTransitioning() && webView != null) {
      retained.needsStreamResume = true;
      webView.onPause();
    }
    super.onStop();
  }

  @Override
  protected void onStart() {
    super.onStart();
    com.deepseekharness.app.core.HarnessController.get(this)
        .addReadyWebPageListener(runtimeChanged);
    if (!pictureInPictureActiveOrTransitioning() && webView != null) webView.onResume();
  }

  @Override
  protected void onResume() {
    super.onResume();
    browserResumed = true;
    if (synchronizeWebSession()) return;
    if (!pictureInPictureActiveOrTransitioning() && webView != null)
      WebFrameRate.apply(this, webView);
    if (webView != null
        && isCurrentPage(webView)
        && WebPreviewPolicy.sameService(baseUrl, webView.getUrl())) {
      updateDocumentLanguage(webView);
      webView.evaluateJavascript(WebPageScripts.language(this), null);
    }
    resumePageStreams();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    synchronizeWebSession();
  }

  private void resumePageStreams() {
    if (retained == null
        || !retained.needsStreamResume
        || !retained.ready
        || pageFailed
        || webView == null
        || retained.view != webView
        || retained.owner.get() != this
        || !browserResumed
        || !isCurrentPage(webView)
        || !WebPreviewPolicy.sameService(baseUrl, webView.getUrl())) return;
    retained.needsStreamResume = false;
    webView.evaluateJavascript("window.dispatchEvent(new Event('dsha-browser-resume'));", null);
  }

  @Override
  protected void onDestroy() {
    com.deepseekharness.app.core.HarnessController.get(this)
        .removeReadyWebPageListener(runtimeChanged);
    if (microphoneRequests != null) microphoneRequests.cancel();
    if (microphone != null) microphone.close();
    if (retained != null) unregisterFilePicker(retained.pendingPicker);
    if (retained != null && retained.owner.get() == this) retained.owner.clear();
    cancelPendingAuthentication();
    if (downloads != null) downloads.dismiss();
    if (isChangingConfigurations() && webView != null) {
      container.removeView(webView);
      webView.setWebViewClient(new WebViewClient());
      webView.setWebChromeClient(null);
      webView.setDownloadListener(null);
      ((android.content.MutableContextWrapper) webView.getContext())
          .setBaseContext(getApplicationContext());
      webView = null;
    } else destroyWebView();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (downloads != null) downloads.model.saveState(out);
    if (webView != null) {
      Bundle state = new Bundle();
      webView.saveState(state);
      out.putBundle("browser-state", state);
      PreviewPageSession.Identity identity = retained.pageSession.identity();
      if (identity != null) {
        out.putLong(STATE_PAGE_GENERATION, identity.generation());
        out.putString(STATE_PAGE_INSTANCE, identity.instanceId());
        out.putString(STATE_PAGE_URL, identity.authUrl());
      }
    }
    super.onSaveInstanceState(out);
  }
}
