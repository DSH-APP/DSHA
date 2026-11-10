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
  private Runnable closeOriginRestorePreview;
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
  private WebCrashPanel crashPanel;
  private final Runnable endedRecheck =
      () -> {
        if (isFinishing() || isDestroyed() || !this.browserResumed) return;
        synchronizeWebSession();
        scheduleEndedRecheck();
      };
  private ProgressBar progress;

  /** 实验性：首次进入对话时的原生加载页，覆盖 WebView 首帧与 DSH 网页启动画面之间的空白。 */
  private BootScreen pageBoot;

  private WebView webView;
  private String authUrl;
  private String authCookie;
  private String baseUrl;
  private String browserInfo = com.deepseekharness.app.util.UiText.text("系统 WebView 版本未知");
  private boolean pageFailed;
  private boolean authRetried;
  private boolean geckoFallbackAttempted;
  private Retained retained;

  /** 实验性页面保活：应用作用域单页 keeper，Activity 只借阅、不拥有。 */
  private BrowserPageKeeper browserKeeper;

  private WebDownloads downloads;
  private Bundle restoreState;
  private boolean navigatingBack;
  private PreviewAuth previewAuth;
  private long startupGeneration;
  private BrowserMicrophone microphone;
  private WebViewMicrophone microphoneRequests;
  private boolean browserResumed;
  private boolean runtimeSuspended;
  private PreviewPageSession.Identity restoreIdentity;
  private PreviewPageSession.Identity obsoleteHostIdentity;
  private boolean emptyHostRetirementPending;
  // 本实例只把请求转交给已有存活宿主后即结束；生命周期回调全部跳过。
  private boolean forwardedDuplicate;
  private PreviewPageSession.Identity pendingIdentity;
  private PreviewPageSession.Identity startupIdentity;
  private final Runnable runtimeChanged =
      () ->
          runOnUiThread(
              () -> {
                synchronizeWebSession();
              });

  private static final class PendingUpload {
    final WebView view;
    final WebUploads.Session uploads;
    final ValueCallback<Uri[]> callback;
    final PreviewPageSession.Identity identity;
    final String pickerKey = "dsha-web-upload-" + java.util.UUID.randomUUID();
    final com.deepseekharness.app.backup.BackupControl copyControl =
        new com.deepseekharness.app.backup.BackupControl(null);
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
      if (value == null) copyControl.cancel();
      if (!completed.compareAndSet(false, true)) return false;
      callback.onReceiveValue(value);
      return true;
    }
  }

  public static final class Retained extends androidx.lifecycle.ViewModel {
    WebView view;
    BrowserPageKeeper keeper;
    boolean disposed;
    boolean ready;
    boolean needsStreamResume;
    boolean inAppRoundTrip;
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
    com.deepseekharness.app.util.BrowserThemeGate themeGate;
    Integer themeColor;
    String routeRequestSent;
    WebBlobDownload blobDownload;
    PendingUpload pendingUpload;
    // 页面取消后仍等待旧系统结果排空，不能把它交给新页面的上传请求。
    PendingUpload pendingPicker;
    WebUploads.Session uploadSession;
    final com.deepseekharness.app.util.BrowserUploadRequestState<WebView, WebUploads.Session>
        uploadRequests = new com.deepseekharness.app.util.BrowserUploadRequestState<>();

    void clearPageState() {
      view = null;
      authUrl = null;
      ready = false;
      needsStreamResume = false;
      inAppRoundTrip = false;
      pageSession.clear();
      pageEvents = null;
      themeGate = null;
      themeColor = null;
      routeRequestSent = null;
      pageNavigation++;
      navigation.cancel();
      pendingHistory = null;
      compatibilityScript = null;
      scriptLanguage = null;
    }

    boolean rendererGone(WebView failed, RenderProcessGoneDetail detail) {
      if (failed != view) return true; // A late callback cannot retire its replacement.
      WebPreviewActivity current = owner.get();
      if (current != null && current.webView == failed && !current.isDestroyed()) {
        current.rendererGone(failed, detail);
        return true;
      }
      retireRendererView(failed);
      return true;
    }

    void retireRendererView(WebView failed) {
      // A retained WebView remains alive between the old Activity's destruction and
      // the replacement's attachment. It still needs a renderer-exit owner in that gap.
      clearPageState();
      uploadRequests.invalidate();
      PendingUpload upload = pendingUpload;
      pendingUpload = null;
      if (upload != null) rendererCleanup("upload", () -> upload.complete(null));
      WebBlobDownload blob = blobDownload;
      blobDownload = null;
      if (blob != null) rendererCleanup("blob", blob::close);
      WebUploads.Session uploads = uploadSession;
      uploadSession = null;
      if (uploads != null) rendererCleanup("upload-session", uploads::close);
      if (failed.getParent() instanceof android.view.ViewGroup parent) parent.removeView(failed);
      rendererCleanup("destroy", failed::destroy);
      BrowserPageKeeper active = keeper;
      if (active != null) active.retireDetached(this, BrowserPageKeeper.REASON_RENDERER);
    }

    /** keeper 销毁页面时调用的唯一清理入口；重复调用不会再碰已释放资源。 */
    void dispose() {
      if (disposed) return;
      disposed = true;
      onCleared();
    }

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

  /** Does not retain the retired Activity while the same page is waiting for reattachment. */
  static final class RetainedRendererClient extends WebViewClient {
    private final Retained page;

    RetainedRendererClient(Retained page) {
      this.page = page;
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
      return page.rendererGone(view, detail);
    }
  }

  private static void rendererCleanup(String stage, Runnable cleanup) {
    try {
      cleanup.run();
    } catch (RuntimeException failure) {
      // An already-dead renderer can reject an outstanding framework callback. Continue
      // retiring the other page resources; never turn that into a second host crash.
      Log.w("DSHA", "WEB_RENDERER_CLEANUP " + stage + " " + failure.getClass().getSimpleName());
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
                              batch =
                                  pending.uploads.copy(
                                      app, java.util.Arrays.asList(chosen), pending.copyControl);
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
    return BrowserConversationRoute.conversation(ctx, url, cookie)
        .putExtra(BrowserConversationRoute.MODE, "FULL");
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // 宿主与 Main 同一任务、不再是 singleTask：已有存活宿主（例如在画中画任务里）时，
    // 把本次请求转交给它并结束这个新实例，不建第二个 WebView、不登记成新 owner。
    if (savedInstanceState == null && BrowserTasks.forwardToLiveBrowser(this)) {
      forwardedDuplicate = true;
      finish();
      return;
    }
    // 新建预览任务（有的 ROM 会为网页宿主单独建任务）同样重放「隐藏后台」；已转发给存活宿主的
    // 实例在上一分支直接结束，不重复处理。
    BrowserTasks.applyRecentsPreference(this);
    closeOriginRestorePreview = BrowserOriginRestore.registerPreview(this);
    microphone = new BrowserMicrophone(this);
    // K1：页面所有权提升到应用级 keeper。系统回收 stopped Activity 时页面只摘下不销毁，
    // 新 Activity 以同一 generation/instance 接管同一 WebView。
    browserKeeper = BrowserPageKeeper.forApplication(this);
    retained = browserKeeper.acquire(this);
    retained.owner = new java.lang.ref.WeakReference<>(this);
    emptyHostRetirementPending =
        savedInstanceState != null
            && savedInstanceState.getBoolean("browser-retire-empty-host", false)
            && retained.view == null
            && retained.pageSession.identity() == null;
    BrowserConversationRoute.register(this);
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
    com.deepseekharness.app.core.HarnessController.get(this)
        .addReadyWebPageListener(runtimeChanged);
    PreviewPageSession.Identity initial = currentWebIdentity();
    if ((!emptyHostRetirementPending || initial == null) && retireEmptyHost(null, initial, false))
      return;
    if (retained.themeColor != null) WebFullscreenUi.applyThemeColor(this, retained.themeColor);
    // 主按钮动作随错误面板结论变化；旧 ID 保留，默认仍是重新加载 / 重新核对。
    findViewById(R.id.web_retry)
        .setOnClickListener(v -> onCrashAction(crashPanel().primaryAction()));
    findViewById(R.id.web_error_browser).setOnClickListener(v -> openExternal(authUrl));
    findViewById(R.id.web_error_logs)
        .setOnClickListener(v -> startActivity(DiagnosticActivity.downloadLogs(this)));
    findViewById(R.id.web_error_recovery).setOnClickListener(v -> leavePreview());
    crashPanel();
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                navigateBack();
              }
            });
    var creationController = com.deepseekharness.app.core.HarnessController.get(this);
    if (currentWebIdentity() == null
        && (creationController.needsWebSessionRestore()
            || creationController.isRestoringWebSession()
            || creationController.hasUnresolvedWebSession())) {
      if (retained.view != null
          && creationController.isWebSessionRetained(retained.pageSession.identity())) {
        var displayIdentity = retained.pageSession.identity();
        if (displayIdentity != null) {
          authUrl = displayIdentity.authUrl();
          baseUrl = WebPreviewPolicy.loopbackBaseUrl(authUrl);
          startupIdentity = displayIdentity;
          startupGeneration = displayIdentity.generation();
        }
        webView = retained.view;
        ((android.content.MutableContextWrapper) webView.getContext()).setBaseContext(this);
        attachClients(webView);
        container.addView(webView, new FrameLayout.LayoutParams(-1, -1));
      }
      suspendRuntimePage(false);
      creationController.restoreRunningWebSession(() -> synchronizeWebSession());
      return;
    }
    if (!BrowserConversationRoute.current(getIntent(), currentWebIdentity())) {
      rejectConversationRoute();
      return;
    }
    authUrl = getIntent().getStringExtra(EXTRA_URL);
    authCookie = getIntent().getStringExtra(EXTRA_COOKIE);
    PreviewPageSession.Identity current = currentWebIdentity();
    PreviewPageSession.Identity retainedIdentity = retained.pageSession.identity();
    if (emptyHostRetirementPending && retainedIdentity == null && retained.view == null) return;
    PreviewPageSession.Identity previous =
        retainedIdentity != null
            ? retainedIdentity
            : retained.view == null ? restoreIdentity : null;
    if (previous != null && !previous.equals(current)) {
      obsoleteHostIdentity = previous;
      webView = retained.view;
      // Resume decides whether this obsolete host may hand a visible FULL request to a new task.
      return;
    }
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
          com.deepseekharness.app.util.WebCrashReport.Kind.INVALID_ADDRESS,
          com.deepseekharness.app.util.UiText.text("对话地址无效"),
          com.deepseekharness.app.util.UiText.text("请返回启动页，重新进入对话。"),
          "loopbackBaseUrl(authUrl) == null",
          null);
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
      setProgressVisible(!retained.ready);
      if (!retained.ready) showPageBoot();
      continueInitialNavigation();
    } else {
      // 旋转保留同一进程的页面；服务已换代时清理旧页面，再用当前凭据加载。
      if (retained.view != null) {
        webView = retained.view;
        destroyWebView(BrowserPageKeeper.REASON_IDENTITY);
      }
      if (!conversationHidden()) loadSession();
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
    if (isFinishing() || isDestroyed() || previewAuth == null || conversationHidden()) return;
    if (obsoleteHostIdentity != null || emptyHostRetirementPending) {
      synchronizeWebSession();
      return;
    }
    PreviewPageSession.Identity expected = currentWebIdentity();
    if (expected == null) {
      cancelPendingAuthentication();
      boolean starting = com.deepseekharness.app.core.HarnessController.get(this).isStarting();
      showError(
          starting
              ? com.deepseekharness.app.util.WebCrashReport.Kind.STARTING
              : com.deepseekharness.app.util.WebCrashReport.Kind.GENERIC,
          com.deepseekharness.app.util.UiText.text("暂时无法进入对话"),
          com.deepseekharness.app.util.UiText.text("Web 未就绪，请回启动页"),
          "loadSession: currentWebIdentity() == null",
          null);
      return;
    }
    if (!BrowserConversationRoute.current(getIntent(), expected)) {
      rejectConversationRoute();
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
    setProgressVisible(true);
    showPageBoot();
    previewAuth.refresh(
        (url, cookie, error) -> {
          if (conversationHidden()
              || !expected.equals(pendingIdentity)
              || !expected.equals(currentWebIdentity())) return;
          pendingIdentity = null;
          if (error != null) {
            showError(
                com.deepseekharness.app.util.WebCrashReport.Kind.AUTH_REFRESH,
                com.deepseekharness.app.util.UiText.text("暂时无法进入对话"),
                error,
                "previewAuth.refresh: " + error,
                null);
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

  @Override
  public Object imeBridgeIdentity() {
    return browserResumed
            && !isFinishing()
            && !isDestroyed()
            && retained != null
            && retained.ready
            && retained.themeGate != null
            && webView != null
            && isCurrentPage(webView)
            && !conversationHidden()
            && retained.themeGate.owns(webView)
            && androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)
            && WebPreviewPolicy.sameService(baseUrl, webView.getUrl())
        ? retained.themeGate
        : null;
  }

  @Override
  public boolean sendImeFrame(Object expected, org.json.JSONObject frame) {
    if (expected == null || expected != imeBridgeIdentity()) return false;
    webView.evaluateJavascript(
        "window.__dshaNativePresentation?.ime("
            + WebPageScripts.imeFrame(retained.themeGate, frame)
            + ");",
        null);
    return true;
  }

  private void cancelPendingAuthentication() {
    pendingIdentity = null;
    if (previewAuth != null) previewAuth.cancel();
  }

  @Override
  protected void cancelConversationRequests() {
    WebFullscreenUi.cancelIme(this);
    cancelPendingAuthentication();
    if (retained != null) retained.routeRequestSent = null;
    if (webView != null && retained != null && isCurrentPage(webView))
      webView.evaluateJavascript("window.__dshaNativePresentation?.cancel();", null);
    if (getIntent() != null) {
      getIntent().removeExtra(BrowserConversationRoute.REQUEST);
      getIntent().removeExtra(BrowserConversationRoute.SESSION);
    }
  }

  /** 单 task 再进入与就绪通知共用同一门禁，不按 URL 或切前台次数猜测需要刷新。 */
  private boolean synchronizeWebSession() {
    return synchronizeWebSession(false);
  }

  private boolean synchronizeWebSession(boolean explicitFullEntry) {
    if (retained == null
        || retained.owner.get() != this
        || previewAuth == null
        || isFinishing()
        || isDestroyed()) return false;
    PreviewPageSession.Identity current = currentWebIdentity();
    PreviewPageSession.Identity obsolete = retained.pageSession.identity();
    if (obsolete != null && obsolete.equals(current)) {
      obsoleteHostIdentity = null;
      emptyHostRetirementPending = false;
    }
    if (obsolete == null) obsolete = obsoleteHostIdentity;
    if (obsolete == null && webView == null && retained.view == null) obsolete = restoreIdentity;
    var controller = com.deepseekharness.app.core.HarnessController.get(this);
    if (current == null && controller.isWebSessionRetained(obsolete)) {
      suspendRuntimePage(controller.isWebSessionExited(obsolete));
      return true;
    }
    if (runtimeSuspended
        && current != null
        && (retained.pageSession.identity() == null || retained.pageSession.isCurrent(current))) {
      runtimeSuspended = false;
      errorPanel.setVisibility(View.GONE);
      findViewById(R.id.web_error_browser).setEnabled(true);
      if (webView != null) webView.setEnabled(true);
      resumePageStreams();
    }
    if (current == null
        && runtimeSuspended
        && (controller.needsWebSessionRestore()
            || controller.isRestoringWebSession()
            || controller.hasUnresolvedWebSession())) return true;
    if (emptyHostRetirementPending || obsolete != null && !obsolete.equals(current)) {
      obsoleteHostIdentity = obsolete;
      cancelPendingAuthentication();
      restoreState = null;
      restoreIdentity = null;
      if (webView == null && retained.view != null) webView = retained.view;
      destroyWebView(BrowserPageKeeper.REASON_SESSION_CHANGED);
      // 服务结束而新服务尚未就绪：停在错误面板说明原因，不留空白页；用户停止与普通维护仍照常收尾。
      if (current == null && !emptyHostRetirementPending && showEndedRuntime(obsolete)) return true;
      retireEmptyHost(obsolete, current, explicitFullEntry);
      // A refused retirement must not fall through and create a page in this old task.
      return true;
    }
    if (obsoleteHostIdentity != null && obsoleteHostIdentity.equals(current))
      obsoleteHostIdentity = null;
    if (current == null) {
      cancelPendingAuthentication();
      cancelFileSelection();
      if (microphoneRequests != null) microphoneRequests.cancel();
      retained.pendingHistory = null;
      restoreState = null;
      restoreIdentity = null;
      retireEmptyHost(null, current, false);
      return false;
    }
    if (conversationHidden()) return false;
    if (webView != null && retained.pageSession.isCurrent(current)) {
      dispatchConversationRoute();
      return false;
    }
    if (current.equals(pendingIdentity)) return true;
    loadSession();
    return true;
  }

  /** 未知只保留页面显示，所有异步权限仍只认currentWebIdentity，不认保留身份。 */
  private void suspendRuntimePage(boolean exited) {
    if (!runtimeSuspended) {
      runtimeSuspended = true;
      cancelConversationRequests();
      cancelFileSelection();
      if (microphoneRequests != null) microphoneRequests.cancel();
      retained.needsStreamResume = true;
      retained.pageNavigation++;
      retained.navigation.cancel();
      retained.pendingHistory = null;
      if (retained.blobDownload != null) {
        retained.blobDownload.close();
        retained.blobDownload = null;
      }
      downloads.model.cancel();
    }
    if (webView != null) webView.setEnabled(false);
    setProgressVisible(false);
    hidePageBoot();
    findViewById(R.id.web_error_browser).setEnabled(false);
    com.deepseekharness.app.util.WebCrashReport.Kind kind =
        exited
            ? com.deepseekharness.app.util.WebCrashReport.Kind.EXITED_KEPT
            : com.deepseekharness.app.util.WebCrashReport.Kind.VERIFY;
    crashPanel()
        .show(
            kind,
            null,
            null,
            crashFacts(null),
            technical(
                exited ? "suspendRuntimePage(exited=true)" : "suspendRuntimePage(exited=false)",
                null),
            false);
    refreshPictureInPicture();
  }

  /** An obsolete PiP task can retain its system surface scale after its page is gone. */
  private boolean retireEmptyHost(
      PreviewPageSession.Identity obsolete,
      PreviewPageSession.Identity current,
      boolean explicitFullEntry) {
    if (retained == null
        || retained.owner.get() != this
        || isFinishing()
        || isDestroyed()
        || webView != null
        || retained.view != null
        || retained.pageSession.identity() != null
        || pendingIdentity != null
        || previewAuth == null
        || previewAuth.busy()) return false;
    var controller = com.deepseekharness.app.core.HarnessController.get(this);
    boolean newReady =
        current != null
            && (emptyHostRetirementPending || obsolete != null && !obsolete.equals(current));
    if (!com.deepseekharness.app.util.BrowserTaskPolicy.endedHost(
            obsolete,
            current,
            emptyHostRetirementPending,
            controller.isStopping(),
            controller.isUserStopped(),
            controller.getWebGeneration())
        || !java.util.Objects.equals(current, controller.getReadyWebPageIdentity())) return false;
    emptyHostRetirementPending = true;
    Intent handoff = null;
    Intent original = getIntent();
    boolean fullRequest =
        explicitFullEntry
            && original != null
            && "FULL".equals(original.getStringExtra(BrowserConversationRoute.MODE))
            && BrowserConversationRoute.current(original, current);
    if (newReady && (fullRequest || browserResumed && !conversationHidden())) {
      handoff =
          new Intent(this, getClass())
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
              .putExtra(BrowserConversationRoute.MODE, "FULL")
              .putExtra(EXTRA_URL, current.authUrl())
              .putExtra(EXTRA_COOKIE, (String) null)
              .putExtra(BrowserConversationRoute.GENERATION, current.generation())
              .putExtra(BrowserConversationRoute.INSTANCE, current.instanceId());
      if (original != null
          && original.getLongExtra(BrowserConversationRoute.GENERATION, -1) == current.generation()
          && current
              .instanceId()
              .equals(original.getStringExtra(BrowserConversationRoute.INSTANCE)))
        for (String key :
            new String[] {BrowserConversationRoute.SESSION, BrowserConversationRoute.REQUEST})
          if (original.hasExtra(key)) handoff.putExtra(key, original.getStringExtra(key));
    }
    // Only retire this empty UI host; the existing runtime stop barrier owns process cleanup.
    try {
      BrowserTasks.finishEmptyHost(this);
    } catch (RuntimeException failure) {
      android.util.Log.w(
          "DSHA", "BROWSER_HOST_RETIRE_FAILED: " + failure.getClass().getSimpleName());
      return false;
    }
    if (!isFinishing()) return false;
    obsoleteHostIdentity = null;
    emptyHostRetirementPending = false;
    if (handoff != null && current.equals(currentWebIdentity())) {
      try {
        startActivity(handoff);
      } catch (RuntimeException failure) {
        android.util.Log.w(
            "DSHA", "BROWSER_HOST_HANDOFF_FAILED: " + failure.getClass().getSimpleName());
      }
    }
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
    destroyWebView(BrowserPageKeeper.REASON_RELOAD);
    // destroyWebView 把系统栏还原为宿主底色；加载页仍在时恢复为加载页底色。
    if (pageBoot != null) applyEntryBars();
    pageFailed = false;
    authRetried = !allowAuthRetry;
    errorPanel.setVisibility(View.GONE);
    setProgressVisible(true);
    try {
      WebView view = new WebView(new android.content.MutableContextWrapper(this));
      // WebView 首帧前默认画白底；改用最近一次网页背景色（无记录时宿主底色）。
      view.setBackgroundColor(entryPalette().background);
      webView = view;
      retained.view = view;
      retained.authUrl = authUrl;
      retained.pageSession.claim(identity);
      BrowserConversationRoute.register(this);
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
      WebPageDiagnostics.engine(this, identity.generation(), browserInfo);
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
      destroyWebView(BrowserPageKeeper.REASON_INIT_FAILED);
      if (identity.equals(currentWebIdentity()) && PreviewFallback.open(this, authUrl, authCookie))
        return;
      Log.w(
          "DSHA",
          com.deepseekharness.app.util.UiText.format(
              "系统 WebView 初始化失败: %s", e.getClass().getSimpleName()));
      showError(
          com.deepseekharness.app.util.WebCrashReport.Kind.WEBVIEW_INIT,
          com.deepseekharness.app.util.UiText.text("系统 WebView 无法启动"),
          com.deepseekharness.app.util.UiText.text("请更新系统 WebView 后重试"),
          "createWebView: " + e.getClass().getName(),
          null);
    }
  }

  private void continueInitialNavigation() {
    if (isFinishing()
        || conversationHidden()
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
    if (retained.themeGate == null || !retained.themeGate.owns(view))
      retained.themeGate =
          new com.deepseekharness.app.util.BrowserThemeGate(view, retained.pageSession.identity());
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
        () -> retained.pageNavigation,
        () -> retained.themeGate);
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
                || !WebPreviewPolicy.sameService(baseUrl, origin.toString())
                || !WebPreviewPolicy.sameService(baseUrl, source.getUrl())) return;
            try {
              String language = message.getData();
              // 网页只接受十种规范语言的显式选项，不接受 system：
              // 把网页当成用户显式选择，避免页面误传偏好值后行为含糊。
              if (com.deepseekharness.app.util.UiLanguagePreference.isSupportedLanguage(language))
                LanguageController.select(this, language);
            } catch (IllegalStateException ignored) {
            }
          });
      androidx.webkit.WebViewCompat.removeWebMessageListener(view, "DshaTheme");
      androidx.webkit.WebViewCompat.addWebMessageListener(
          view,
          "DshaTheme",
          java.util.Collections.singleton(baseUrl.substring(0, baseUrl.length() - 1)),
          (source, message, origin, mainFrame, reply) -> {
            if (!isCurrentPage(source)
                || !mainFrame
                || !WebPreviewPolicy.sameService(baseUrl, origin.toString())
                || !WebPreviewPolicy.sameService(baseUrl, source.getUrl())) return;
            try {
              receivePresentation(source, message.getData(), mainFrame, source.getUrl());
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
      if (request.hasGesture()
          || com.deepseekharness.app.util.DeepSeekOAuthNavigation.allowed(
              baseUrl, view.getUrl(), url)) openExternal(url);
      return true;
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
      if (!isCurrentPage(view)) return;
      WebFullscreenUi.cancelIme(WebPreviewActivity.this);
      retained.pageNavigation++;
      retained.themeGate =
          new com.deepseekharness.app.util.BrowserThemeGate(view, retained.pageSession.identity());
      retained.routeRequestSent = null;
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
      setProgressVisible(true);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
      if (!isCurrentPage(view) || pageFailed) return;
      final long navigation = retained.pageNavigation;
      setProgressVisible(false);
      hidePageBoot();
      if (!WebPreviewPolicy.sameService(baseUrl, url)) return;
      retained.ready = true;
      final var theme = retained.themeGate;
      if (theme != null)
        view.evaluateJavascript(
            WebPageScripts.presentation(WebPreviewActivity.this, theme),
            ignored -> {
              if (isCurrentPage(view) && retained.themeGate == theme) {
                dispatchConversationRoute();
                WebFullscreenUi.refreshIme(WebPreviewActivity.this);
              }
            });
      refreshPictureInPicture();
      resumePageStreams();
      // 新内核已经在文档起始执行兼容脚本；这里只做轻量能力核验，避免每次页面完成
      // 又把数十 KB 的 polyfill 注入一次。旧内核没有 DOCUMENT_START_SCRIPT 时仍保留
      // 原来的页面完成注入路径。
      String compatibility =
          (retained != null && retained.compatibilityScript != null
                  ? ""
                  : WebPageScripts.compatibility(WebPreviewActivity.this, retained.pageEvents)
                      + "\n")
              + (theme == null
                  ? ""
                  : "window.__dshaFilesBind?.("
                      + WebPageScripts.presentationBinding(theme)
                      + ");\n")
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
                    com.deepseekharness.app.util.WebCrashReport.Kind.WEBVIEW_OUTDATED,
                    com.deepseekharness.app.util.UiText.text("系统 WebView 需要更新"),
                    com.deepseekharness.app.util.UiText.format(
                        "当前内核缺少：%s。\n更新 Android System WebView / Chrome 后重试，或在浏览器中打开。", missing),
                    "capability check: missing " + missing,
                    null);
              }
            } catch (org.json.JSONException | NullPointerException invalid) {
              if (!openGeckoAfterWebViewFailure())
                showError(
                    com.deepseekharness.app.util.WebCrashReport.Kind.WEBVIEW_OUTDATED,
                    com.deepseekharness.app.util.UiText.text("系统 WebView 需要更新"),
                    com.deepseekharness.app.util.UiText.text("能力检查失败，请更新内核"),
                    "capability check: invalid result",
                    null);
            }
          });
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
      if (!isCurrentPage(view) || !request.isForMainFrame()) return;
      com.deepseekharness.app.util.WebCrashReport.Facts facts =
          new com.deepseekharness.app.util.WebCrashReport.Facts();
      facts.errorCode = error.getErrorCode();
      showError(
          com.deepseekharness.app.util.WebCrashReport.network(error.getErrorCode()),
          com.deepseekharness.app.util.UiText.text("暂时无法连接对话服务"),
          com.deepseekharness.app.util.UiText.format(
              "服务可能仍在启动或已退出。请稍后重试，持续失败时返回启动页查看日志。\n错误代码：%s", error.getErrorCode()),
          "onReceivedError " + error.getErrorCode() + " " + error.getDescription(),
          facts);
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
      com.deepseekharness.app.util.WebCrashReport.Facts facts =
          new com.deepseekharness.app.util.WebCrashReport.Facts();
      facts.httpStatus = code;
      showError(
          com.deepseekharness.app.util.WebCrashReport.http(code),
          code == 401 || code == 403
              ? com.deepseekharness.app.util.UiText.text("对话认证已失效")
              : com.deepseekharness.app.util.UiText.text("对话页面加载失败"),
          com.deepseekharness.app.util.UiText.format("HTTP %s。请返回启动页重新进入对话，或稍后重试。", code),
          "onReceivedHttpError HTTP "
              + code
              + (authRetried ? " (after one re-authentication)" : ""),
          facts);
    }

    @androidx.annotation.RequiresApi(26)
    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
      return retained == null || retained.rendererGone(view, detail);
    }
  }

  private void rendererGone(WebView failed, RenderProcessGoneDetail detail) {
    boolean currentPage = isCurrentPage(failed);
    BrowserTasks.browserUnavailable(this);
    // Revoke the native bridge before cancelIme: no JavaScript may be sent to the dead page.
    webView = null;
    WebFrameRate.clear(this, failed);
    WebFullscreenUi.cancelIme(this);
    WebFullscreenUi.applyThemeColor(this, getColor(R.color.surface));
    if (microphoneRequests != null) {
      WebViewMicrophone audio = microphoneRequests;
      microphoneRequests = null;
      rendererCleanup("microphone", audio::cancel);
    }
    boolean hadBlob = retained.blobDownload != null;
    long generation = BrowserPageKeeper.generationOf(retained);
    retained.retireRendererView(failed);
    WebPageDiagnostics.pageDestroy(
        this,
        BrowserPageKeeper.REASON_RENDERER,
        isChangingConfigurations(),
        isFinishing(),
        generation);
    if (hadBlob && downloads != null) rendererCleanup("download", downloads.model::cancel);
    navigatingBack = false;
    refreshPictureInPicture();
    if (!currentPage) return;
    showError(
        detail.didCrash()
            ? com.deepseekharness.app.util.WebCrashReport.Kind.RENDER_CRASH
            : com.deepseekharness.app.util.WebCrashReport.Kind.RENDER_RECLAIMED,
        com.deepseekharness.app.util.UiText.text("网页渲染进程已退出"),
        detail.didCrash()
            ? com.deepseekharness.app.util.UiText.text("WebView 异常，请重试")
            : com.deepseekharness.app.util.UiText.text("网页被系统回收，请重试"),
        "onRenderProcessGone(didCrash=" + detail.didCrash() + ")",
        null);
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
    destroyWebView(BrowserPageKeeper.REASON_FALLBACK);
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
      if (isCurrentPage(view) && !pageFailed) {
        progress.setProgress(value);
        // 历史恢复等路径可能不回调页面完成；进度到顶同样收起加载页。
        if (value >= 100) hidePageBoot();
      }
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
      // 未授权文件访问时，网页不能借系统文档选择器读取手机文件。
      if (!com.deepseekharness.app.StorageAccess.granted(WebPreviewActivity.this)) {
        callback.onReceiveValue(null);
        Toast.makeText(
                WebPreviewActivity.this,
                com.deepseekharness.app.util.UiText.choose(
                    "未获得文件访问授权：网页暂不能打开手机文件。请先在“设备授权”页允许文件访问。",
                    "File access is not granted, so the web page cannot open phone files. Allow file access on the device grants page first."),
                Toast.LENGTH_LONG)
            .show();
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
        RecentsPrivacyGuard.launch(pending.picker, primary);
      } catch (RuntimeException e) {
        try {
          if (primary == null)
            primary =
                WebUploadChooser.intent(
                    null,
                    params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE,
                    params.getAcceptTypes());
          RecentsPrivacyGuard.launch(pending.picker, WebUploads.fallback(primary));
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

  /** 最近一次已核验网页上报的背景色；没有记录时用宿主 shell 底色。 */
  private com.deepseekharness.app.util.BootPalette entryPalette() {
    com.deepseekharness.app.util.BootPalette shell = BootScreen.shellPalette(this);
    Integer web = new com.deepseekharness.app.core.ConfigStore(this).getEntryWebBackground();
    return web == null
        ? shell
        : com.deepseekharness.app.util.BootPalette.fromWeb(web, shell.background);
  }

  /** 网页尚未上报主题时，状态栏 / 导航栏先随加载页底色，避免与加载页出现色差。 */
  private void applyEntryBars() {
    if (retained != null && retained.themeColor == null)
      WebFullscreenUi.applyThemeColor(this, entryPalette().background);
  }

  /**
   * 实验性尽力修复：从容器进入 DSH 时，鉴权刷新、WebView 首帧与网页脚本下载解析期间页面为空，
   * 显示为长白屏。这里先盖与 DSH 网页启动画面同款的原生加载页，页面完成、出错或挂起时收起。
   */
  private void showPageBoot() {
    if (pageBoot != null
        || container == null
        || isFinishing()
        || isDestroyed()
        || conversationHidden()
        || pictureInPictureActiveOrTransitioning()
        || !(container.getParent() instanceof android.view.ViewGroup layer)) return;
    BootScreen screen = BootScreen.embedded(this, entryPalette());
    screen.setStatus(
        com.deepseekharness.app.util.UiText.choose("正在进入对话…", "Opening conversation…"));
    screen.offerMainWithDiagnostics(
        com.deepseekharness.app.util.UiText.choose("返回启动页", "Back to start page"),
        this::leavePreview);
    // 挂在网页与错误面板所在的内层容器最上层；顶部进度条仍在其上显示真实进度。
    screen.attachTo(layer);
    pageBoot = screen;
    applyEntryBars();
  }

  private void hidePageBoot() {
    BootScreen screen = pageBoot;
    pageBoot = null;
    if (screen != null) screen.dismiss();
  }

  /** 顶部进度条：出现淡入、消失 150ms 淡出，避免加载/错误切换时硬跳（审计 F-01）。 */
  private void setProgressVisible(boolean visible) {
    progress.animate().cancel();
    if (visible) {
      boolean wasHidden = progress.getVisibility() != View.VISIBLE;
      progress.setVisibility(View.VISIBLE);
      if (wasHidden) UiMotion.reveal(progress);
      return;
    }
    if (UiMotion.animationsEnabled(this)) {
      progress
          .animate()
          .alpha(0f)
          .setDuration(150L)
          .setInterpolator(UiMotion.standard())
          .withEndAction(
              () -> {
                progress.setVisibility(View.GONE);
                progress.setAlpha(1f);
              })
          .start();
    } else {
      progress.setVisibility(View.GONE);
      progress.setAlpha(1f);
    }
  }

  private void showError(String title, String detail) {
    showError(
        com.deepseekharness.app.util.WebCrashReport.Kind.GENERIC, title, detail, detail, null);
  }

  /**
   * 显示错误面板（实验性，docs/design/web-crash-20261102）。title/detail 为原有文案，仍写入诊断记录；
   * 面板标题与说明按 kind 选择，technicalDetail 为不翻译的协议原文，进入「技术详情」。
   */
  private void showError(
      com.deepseekharness.app.util.WebCrashReport.Kind kind,
      String title,
      String detail,
      String technicalDetail,
      com.deepseekharness.app.util.WebCrashReport.Facts facts) {
    if (isFinishing() || isDestroyed()) return;
    cancelFileSelection();
    if (microphoneRequests != null) microphoneRequests.cancel();
    com.deepseekharness.app.core.DiagnosticLog.record(
        this, "WEB_PAGE", title + com.deepseekharness.app.util.UiText.text("：") + detail);
    pageFailed = true;
    retained.ready = false;
    refreshPictureInPicture();
    setProgressVisible(false);
    hidePageBoot();
    crashPanel()
        .show(
            kind,
            title,
            detail,
            crashFacts(facts),
            technical(kind.name(), technicalDetail),
            authUrl != null);
  }

  /** 就绪身份消失、且旧页面未被接回保留时决定是否停在面板上；返回 false 表示照常收尾窗口。 */
  private boolean showEndedRuntime(PreviewPageSession.Identity obsolete) {
    if (isFinishing() || isDestroyed() || conversationHidden()) return false;
    var controller = com.deepseekharness.app.core.HarnessController.get(this);
    com.deepseekharness.app.util.WebCrashReport.Kind kind;
    try {
      kind =
          com.deepseekharness.app.util.WebCrashReport.ended(
              controller.isUserStopped(),
              controller.isStopping(),
              controller.isRestartBlocked(),
              controller.isStarting());
    } catch (RuntimeException unavailable) {
      return false;
    }
    if (kind == null) return false;
    // 复核时结论未变：不重复写诊断记录、不重置用户已展开的详情。
    if (kind == crashPanel().kind()) {
      scheduleEndedRecheck();
      return true;
    }
    cancelPendingAuthentication();
    String reason = "";
    try {
      reason = controller.config().getWebFailureReason();
    } catch (RuntimeException ignored) {
    }
    showError(
        kind,
        com.deepseekharness.app.util.UiText.choose("对话服务已结束", "Conversation service ended"),
        reason,
        "readyWebPageIdentity == null" + (reason.isEmpty() ? "" : "\n" + reason),
        null);
    scheduleEndedRecheck();
    return true;
  }

  /** 服务结束后面板停留期间定期复核：重启就绪即交接，用户停止后照常收尾。 */
  private void scheduleEndedRecheck() {
    View panel = errorPanel;
    if (panel == null) return;
    panel.removeCallbacks(endedRecheck);
    com.deepseekharness.app.util.WebCrashReport.Kind kind = crashPanel().kind();
    if (kind == com.deepseekharness.app.util.WebCrashReport.Kind.BLOCKED
        || kind == com.deepseekharness.app.util.WebCrashReport.Kind.RESTARTING
        || kind == com.deepseekharness.app.util.WebCrashReport.Kind.EXITED)
      panel.postDelayed(endedRecheck, 1500);
  }

  private WebCrashPanel crashPanel() {
    if (crashPanel == null || crashPanel.root != errorPanel)
      crashPanel =
          new WebCrashPanel(
              errorPanel, errorTitle, errorDetail, this::findViewById, this::onCrashAction);
    return crashPanel;
  }

  /** 错误面板动作；null 表示顶栏返回，与系统返回键一致。 */
  private void onCrashAction(com.deepseekharness.app.util.WebCrashReport.Action action) {
    if (action == null) {
      getOnBackPressedDispatcher().onBackPressed();
      return;
    }
    switch (action) {
      case HOME:
        leavePreview();
        break;
      case RECHECK:
        com.deepseekharness.app.core.HarnessController.get(this)
            .requestWebSessionRecheck(() -> runOnUiThread(this::synchronizeWebSession));
        break;
      case RELOAD:
        if (runtimeSuspended)
          com.deepseekharness.app.core.HarnessController.get(this)
              .requestWebSessionRecheck(() -> runOnUiThread(this::synchronizeWebSession));
        else loadSession();
        break;
      case STORE:
        openWebViewStore();
        break;
      case BROWSER:
        openExternal(authUrl);
        break;
      case RECOVERY:
        startActivity(new Intent(this, StartupRecoveryActivity.class));
        leavePreview();
        break;
      case LOGS:
        startActivity(DiagnosticActivity.downloadLogs(this));
        break;
      case WAIT:
      default:
        break;
    }
  }

  /** 打开当前 WebView 提供方的商店页；没有商店时退回网页版。 */
  private void openWebViewStore() {
    String provider = "com.google.android.webview";
    try {
      PackageInfo current = WebView.getCurrentWebViewPackage();
      if (current != null) provider = current.packageName;
    } catch (RuntimeException ignored) {
    }
    try {
      startActivity(
          new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + provider))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (RuntimeException noStore) {
      openExternal("https://play.google.com/store/apps/details?id=" + provider);
    }
  }

  /** 面板事实：只读控制器已有状态，读不到时保留未知，不推测。 */
  private com.deepseekharness.app.util.WebCrashReport.Facts crashFacts(
      com.deepseekharness.app.util.WebCrashReport.Facts base) {
    com.deepseekharness.app.util.WebCrashReport.Facts facts =
        base == null ? new com.deepseekharness.app.util.WebCrashReport.Facts() : base;
    try {
      var controller = com.deepseekharness.app.core.HarnessController.get(this);
      facts.autoRestart = controller.canAutoRestart();
      facts.failures = controller.config().getWebFailures();
      // 退出码只存在于失败原因原文里（受运行时启动器字节钉保护，不在此改 Controller）；
      // 状态行不解析本地化文案，原文进入「技术详情」。
    } catch (RuntimeException unavailable) {
      // 测试替身或进程收尾时读不到：保持默认（未知）。
    }
    return facts;
  }

  /** 技术详情原文：入口、协议细节、失败次数、时间与内核信息；不翻译。 */
  private String technical(String entry, String detail) {
    StringBuilder text = new StringBuilder(entry);
    if (detail != null && !detail.isEmpty()) text.append('\n').append(detail);
    try {
      text.append("\nfailures=")
          .append(new com.deepseekharness.app.core.ConfigStore(this).getWebFailures())
          .append('/')
          .append(com.deepseekharness.app.util.RestartBudget.LIMIT);
    } catch (RuntimeException ignored) {
    }
    text.append('\n')
        .append(
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date()))
        .append("\n\n")
        .append(browserInfo);
    return text.toString();
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
      hideConversation();
      return;
    }
    navigatingBack = true;
    Runnable fallback =
        () -> {
          if (!navigatingBack || !isCurrentPage(current)) return;
          navigatingBack = false;
          if (current.canGoBack()) current.goBack();
          else if (com.deepseekharness.app.util.EntryTarget.WEB.equals(
              new com.deepseekharness.app.core.ConfigStore(this).getEntryTarget()))
            // 启动目标为 Web：网页已无可返回的历史时回到桌面，页面与会话保留。
            leaveToLauncher();
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

  /** 销毁页面并记录 K0 原因；keeper 已退役（view 已为空）时不会二次 destroy。 */
  private void destroyWebView(String reason) {
    WebFullscreenUi.cancelIme(this);
    WebFullscreenUi.applyThemeColor(this, getColor(R.color.surface));
    if (microphoneRequests != null) {
      microphoneRequests.cancel();
      microphoneRequests = null;
    }
    cancelFileSelection();
    WebView previous = webView;
    WebView pageView = retained == null ? null : retained.view;
    WebView destroyed = previous != null ? previous : pageView;
    if (destroyed != null)
      WebPageDiagnostics.pageDestroy(
          this,
          reason,
          isChangingConfigurations(),
          isFinishing(),
          BrowserPageKeeper.generationOf(retained));
    WebFrameRate.clear(this, previous);
    webView = null;
    if (retained != null) retained.clearPageState();
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
    if (destroyed != null) {
      if (container != null) container.removeView(destroyed);
      destroyed.destroy();
    }
    if (retained != null && retained.uploadSession != null) {
      retained.uploadSession.close();
      retained.uploadSession = null;
    }
  }

  /**
   * 把页面从 Activity 摘下但保留 WebView：清掉 Activity 级客户端与上下文，只留 renderer 退出桩。
   * 视图摘除由本方法完成，keeper.detach 只负责所有权与诊断。
   */
  private void detachPageView() {
    WebView detached = webView;
    if (detached == null) return;
    webView = null;
    WebFullscreenUi.cancelIme(this);
    WebFrameRate.clear(this, detached);
    if (container != null) container.removeView(detached);
    if (retained == null || retained.view != detached) return;
    detached.setWebViewClient(new RetainedRendererClient(retained));
    detached.setWebChromeClient(null);
    detached.setDownloadListener(null);
    if (detached.getContext() instanceof android.content.MutableContextWrapper context)
      context.setBaseContext(getApplicationContext());
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
    WebFullscreenUi.cancelIme(this);
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
    if (retained != null) retained.routeRequestSent = null;
    if (!pictureInPictureActiveOrTransitioning() && webView != null) {
      retained.needsStreamResume = true;
      // 应用内切换（Web → 原生页）与整体退到后台的 carrier 风险不同；诊断行据此区分。
      retained.inAppRoundTrip =
          com.deepseekharness.app.ForegroundActivity.isAnotherActivityVisible(this);
      webView.onPause();
    }
    super.onStop();
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (!pictureInPictureActiveOrTransitioning() && webView != null) webView.onResume();
  }

  @Override
  protected void onResume() {
    super.onResume();
    browserResumed = true;
    // 预览宿主回到前台时再重放一次：新任务可能晚于上次策略才进入系统任务列表
    // （ColorOS 16 会把网页宿主作为独立卡片留下），这里补上 exclude 标记。
    BrowserTasks.applyRecentsPreference(this);
    if (synchronizeWebSession()) return;
    continueInitialNavigation();
    if (!pictureInPictureActiveOrTransitioning() && webView != null)
      WebFrameRate.apply(this, webView);
    if (webView != null
        && isCurrentPage(webView)
        && WebPreviewPolicy.sameService(baseUrl, webView.getUrl())) {
      updateDocumentLanguage(webView);
      webView.evaluateJavascript(WebPageScripts.language(this), null);
      // 保留的 WebView 不会重跑文档起始脚本：回到前台时重推原生主题，插件只在值变化时写入。
      webView.evaluateJavascript(WebPageScripts.theme(this), null);
    }
    resumePageStreams();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    boolean explicitFullEntry = "FULL".equals(intent.getStringExtra(BrowserConversationRoute.MODE));
    super.onNewIntent(intent);
    setIntent(intent);
    var controller = com.deepseekharness.app.core.HarnessController.get(this);
    var displayIdentity = retained == null ? null : retained.pageSession.identity();
    if (currentWebIdentity() == null
        && controller.isWebSessionRetained(displayIdentity)
        && BrowserConversationRoute.current(intent, displayIdentity)) {
      cancelConversationRequests();
      receivePresentationIntent();
      synchronizeWebSession(explicitFullEntry);
      return;
    }
    if (!BrowserConversationRoute.current(intent, currentWebIdentity())) {
      rejectConversationRoute();
      return;
    }
    if (retained != null) retained.routeRequestSent = null;
    synchronizeWebSession(explicitFullEntry);
  }

  private void rejectConversationRoute() {
    Toast.makeText(
            this,
            com.deepseekharness.app.util.UiText.choose(
                "这条任务所属的聊天实例已结束，请从启动页重新进入。",
                "This task's chat instance has ended. Open the workspace from the start page."),
            Toast.LENGTH_LONG)
        .show();
    hideConversation();
  }

  private void dispatchConversationRoute() {
    if (retained == null
        || !retained.ready
        || retained.themeGate == null
        || webView == null
        || !isCurrentPage(webView)
        || conversationHidden()
        || !BrowserConversationRoute.current(getIntent(), currentWebIdentity())) return;
    String request = getIntent().getStringExtra(BrowserConversationRoute.REQUEST);
    String id = getIntent().getStringExtra(BrowserConversationRoute.SESSION);
    if (request == null
        || request.equals(retained.routeRequestSent)
        || id == null
        || id.isEmpty()
        || id.length() > 512) return;
    if (!androidx.webkit.WebViewFeature.isFeatureSupported(
        androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) {
      Toast.makeText(
              this,
              com.deepseekharness.app.util.UiText.choose(
                  "无法打开该任务的会话，当前聊天已保留。",
                  "The task's session could not be opened. Your current chat was kept."),
              Toast.LENGTH_LONG)
          .show();
      for (String key :
          new String[] {
            BrowserConversationRoute.REQUEST,
            BrowserConversationRoute.SESSION,
            BrowserConversationRoute.GENERATION,
            BrowserConversationRoute.INSTANCE,
            BrowserConversationRoute.QUESTION
          }) getIntent().removeExtra(key);
      return;
    }
    retained.routeRequestSent = request;
    webView.evaluateJavascript(
        "window.__dshaNativePresentation&&window.__dshaNativePresentation.route("
            + WebPageScripts.sessionRoute(getIntent(), retained.themeGate)
            + ");",
        null);
  }

  private void receivePresentation(WebView source, String text, boolean mainFrame, String url) {
    if (text == null
        || text.length() > 2048
        || retained.themeGate == null
        || conversationHidden()
        || !isCurrentPage(source)) return;
    try {
      var value = new org.json.JSONObject(text);
      if (!retained.themeGate.accept(
          source,
          currentWebIdentity(),
          value.optString("nonce"),
          value.optLong("generation", -1),
          value.optString("instance"),
          value.optLong("sequence", -1),
          mainFrame,
          url)) return;
      if ("ime-ack".equals(value.optString("type"))) {
        WebFullscreenUi.acknowledgeIme(
            this, retained.themeGate, value.optLong("frameId", -1), value.optBoolean("supported"));
      } else if ("theme-color".equals(value.optString("type"))) {
        Integer color =
            com.deepseekharness.app.util.BrowserThemeColor.parse(
                value.optString("color"), getColor(R.color.surface));
        if (color != null) {
          retained.themeColor = color;
          // 已核验页面在上报主题：网页自身已接手显示，收起原生加载页。
          hidePageBoot();
          WebFullscreenUi.applyThemeColor(this, color);
          // 已核验页面的主题色留给下一次直启的加载页，衔接 DSH 主题与外部主题壳。
          new com.deepseekharness.app.core.ConfigStore(this).setEntryWebBackground(color);
        }
      } else if ("session-route".equals(value.optString("type"))
          && value.optString("requestToken").equals(retained.routeRequestSent)
          && value
              .optString("sessionId")
              .equals(getIntent().getStringExtra(BrowserConversationRoute.SESSION))) {
        if (!"opened".equals(value.optString("status")))
          Toast.makeText(
                  this,
                  com.deepseekharness.app.util.UiText.choose(
                      "无法打开该任务的会话，当前聊天已保留。",
                      "The task's session could not be opened. Your current chat was kept."),
                  Toast.LENGTH_LONG)
              .show();
        else if (getIntent().getBooleanExtra(BrowserConversationRoute.QUESTION, false))
          source.evaluateJavascript(WebPageScripts.focusQuestion(), null);
        for (String key :
            new String[] {
              BrowserConversationRoute.REQUEST,
              BrowserConversationRoute.SESSION,
              BrowserConversationRoute.GENERATION,
              BrowserConversationRoute.INSTANCE,
              BrowserConversationRoute.QUESTION
            }) getIntent().removeExtra(key);
      }
    } catch (org.json.JSONException ignored) {
    }
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
    // foreground 只作为证据带给客户端/诊断：应用内切换时 carrier 通常仍可用，
    // 客户端不再因为探针超时直接重建整份 Remote 基线。
    webView.evaluateJavascript(
        "window.dispatchEvent(new CustomEvent('dsha-browser-resume',{detail:{foreground:"
            + retained.inAppRoundTrip
            + "}}));",
        null);
    webView.removeCallbacks(resumeDiagnostic);
    webView.postDelayed(resumeDiagnostic, 2600);
  }

  private final Runnable resumeDiagnostic = this::readResumeDiagnostic;

  /** resume 后读取客户端脱敏探测记录；旧客户端没有该全局时记录 probe=absent。 */
  private void readResumeDiagnostic() {
    WebView current = webView;
    if (current == null
        || retained == null
        || !retained.ready
        || !browserResumed
        || !isCurrentPage(current)) return;
    current.evaluateJavascript(
        "(window.__DSHA_RESUME_DIAG__||null)",
        value -> {
          if (retained == null || !isCurrentPage(current)) return;
          WebPageDiagnostics.resume(
              WebPreviewActivity.this,
              value,
              retained.inAppRoundTrip,
              retained.ready,
              BrowserPageKeeper.generationOf(retained));
        });
  }

  @Override
  protected void onDestroy() {
    if (forwardedDuplicate) {
      // 只走过 super.onCreate：没有页面、监听或 owner 登记需要撤销。
      super.onDestroy();
      return;
    }
    hidePageBoot();
    BrowserConversationRoute.unregister(this);
    com.deepseekharness.app.core.HarnessController.get(this)
        .removeReadyWebPageListener(runtimeChanged);
    if (microphoneRequests != null) microphoneRequests.cancel();
    if (microphone != null) microphone.close();
    if (retained != null) unregisterFilePicker(retained.pendingPicker);
    if (retained != null && retained.owner.get() == this) retained.owner.clear();
    cancelPendingAuthentication();
    if (downloads != null) downloads.dismiss();
    boolean changingConfigurations = isChangingConfigurations();
    boolean finishing = isFinishing();
    if (!browserKeeper.owns(this)) {
      // 新 Activity 已接管同一页面，或 keeper 已退役：旧实例只放手，不摘视图、不改客户端。
      if (webView != null && container != null) container.removeView(webView);
      webView = null;
    } else if (changingConfigurations) {
      // 配置变更：只摘挂，页面留在 keeper，等新 Activity 以同一身份接管。
      detachPageView();
      browserKeeper.detach(this, BrowserPageKeeper.REASON_CONFIG);
    } else if (finishing) {
      WebPageDiagnostics.pageDestroy(
          this,
          BrowserPageKeeper.REASON_FINISH,
          false,
          true,
          BrowserPageKeeper.generationOf(retained));
      detachPageView();
      browserKeeper.retireFrom(this, BrowserPageKeeper.REASON_FINISH);
    } else if (webView != null || retained != null && retained.view != null && !retained.disposed) {
      // 两者皆否 = 系统回收 stopped Activity：保留页面，不销毁。
      detachPageView();
      browserKeeper.detach(this, BrowserPageKeeper.REASON_SYSTEM);
    } else {
      browserKeeper.retireFrom(this, BrowserPageKeeper.REASON_EMPTY);
    }
    super.onDestroy();
    if (closeOriginRestorePreview != null) closeOriginRestorePreview.run();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (downloads != null) downloads.model.saveState(out);
    if (webView != null) {
      Bundle state = new Bundle();
      webView.saveState(state);
      out.putBundle("browser-state", state);
    }
    PreviewPageSession.Identity identity =
        webView != null ? retained.pageSession.identity() : obsoleteHostIdentity;
    if (identity != null) {
      out.putLong(STATE_PAGE_GENERATION, identity.generation());
      out.putString(STATE_PAGE_INSTANCE, identity.instanceId());
      out.putString(STATE_PAGE_URL, identity.authUrl());
    }
    if (emptyHostRetirementPending && webView == null && retained.view == null)
      out.putBoolean("browser-retire-empty-host", true);
    super.onSaveInstanceState(out);
  }
}
