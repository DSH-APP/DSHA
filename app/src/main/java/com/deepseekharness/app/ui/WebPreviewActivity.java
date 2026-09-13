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
import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.R;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.WebPreviewPolicy;

/** 标准版预览：系统 WebView、异步鉴权、文件选择与可恢复的加载错误。 */
public class WebPreviewActivity extends AppCompatActivity implements WebFullscreenUi.Host {
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_COOKIE = "cookie";
    // 检查真实页面能力，包括上游 polyfill 的结果，不凭伪装 UA 判断。
    private static final String CAPABILITY_CHECK = "(function(){var m=[];"
            + "if(!('noModule' in document.createElement('script')))m.push('JavaScript modules');"
            + "['Promise','fetch','WebSocket','TextEncoder','ReadableStream','AbortController']"
            + ".forEach(function(k){if(typeof window[k]==='undefined')m.push(k);});"
            + "if(typeof AbortSignal==='undefined'||typeof AbortSignal.any!=='function')m.push('AbortSignal.any');"
            + "if(typeof AbortSignal==='undefined'||typeof AbortSignal.timeout!=='function')m.push('AbortSignal.timeout');"
            + "if(typeof Promise.withResolvers!=='function')m.push('Promise.withResolvers');"
            + "if(typeof Iterator==='undefined'||typeof Iterator.from!=='function'||typeof Iterator.prototype.filter!=='function')m.push('Iterator');"
            + "return m.join(', ');})()";

    private FrameLayout container;
    private View errorPanel;
    private TextView errorTitle;
    private TextView errorDetail;
    private ProgressBar progress;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private String authUrl;
    private String authCookie;
    private String baseUrl;
    private String browserInfo = com.deepseekharness.app.util.UiText.text("系统 WebView 版本未知");
    private boolean pageFailed;
    private boolean authRetried;
    private Retained retained;
    private WebDownloads downloads;
    private Bundle restoreState;
    private boolean navigatingBack;
    private PreviewAuth previewAuth;
    private long startupGeneration;

    public static final class Retained extends androidx.lifecycle.ViewModel {
        WebView view;
        String authUrl;
        androidx.webkit.ScriptHandler compatibilityScript;
        String scriptLanguage;
        WebBlobDownload blobDownload;
        ValueCallback<Uri[]> pickerCallback;
        final java.util.ArrayList<java.io.File> uploads = new java.util.ArrayList<>();
        @Override protected void onCleared() {
            if (pickerCallback != null) pickerCallback.onReceiveValue(null);
            if (view != null) view.destroy();
            view = null;
            if (blobDownload != null) blobDownload.close();
            WebUploads.clean(uploads);
        }
    }

    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                ValueCallback<Uri[]> callback = retained.pickerCallback;
                retained.pickerCallback = null;
                fileCallback = null;
                if (callback == null) return;
                Uri[] selected = WebUploads.parseChooserResult(
                        result.getResultCode(), result.getData());
                if (selected != null) {
                    for (Uri uri : selected) {
                        // 只接收内容 URI，不向网页开放任意本地文件路径。
                        if (uri == null || !"content".equals(uri.getScheme())) {
                            selected = null;
                            break;
                        }
                    }
                }
                if (selected == null) { callback.onReceiveValue(null); return; }
                final Uri[] chosen = selected;
                final Retained owner = retained;
                final Context app = getApplicationContext();
                new Thread(() -> {
                    java.util.ArrayList<java.io.File> copied = new java.util.ArrayList<>();
                    try {
                        copied = WebUploads.copy(app, java.util.Arrays.asList(chosen));
                        Uri[] local = new Uri[copied.size()];
                        for (int i=0;i<local.length;i++) local[i] = androidx.core.content.FileProvider.getUriForFile(app,app.getPackageName()+".updates",copied.get(i));
                        final java.util.ArrayList<java.io.File> ready = copied;
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            if (owner.view == null) { WebUploads.clean(ready); callback.onReceiveValue(null); }
                            else { owner.uploads.addAll(ready); callback.onReceiveValue(local); }
                        });
                    } catch (Exception error) {
                        WebUploads.clean(copied);
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            callback.onReceiveValue(null);
                            Toast.makeText(app,com.deepseekharness.app.util.UiText.text("上传失败：")+error.getMessage(),Toast.LENGTH_LONG).show();
                        });
                    }
                },"web-file-import").start();
            });

    public static Intent intent(Context ctx, String url, String cookie) {
        return new Intent(ctx, WebPreviewActivity.class)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_COOKIE, cookie);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startupGeneration = com.deepseekharness.app.core.HarnessController.get(this).getWebGeneration();
        retained = new androidx.lifecycle.ViewModelProvider(this).get(Retained.class);
        previewAuth = new PreviewAuth(this);
        downloads = new WebDownloads(this,savedInstanceState);
        restoreState = savedInstanceState == null ? null : savedInstanceState.getBundle("browser-state");
        setContentView(R.layout.activity_web_preview);
        WebFullscreenUi.install(this);
        container = findViewById(R.id.web_container);
        errorPanel = findViewById(R.id.web_error_panel);
        errorTitle = findViewById(R.id.web_error_title);
        errorDetail = findViewById(R.id.web_error_detail);
        progress = findViewById(R.id.web_progress);
        findViewById(R.id.web_retry).setOnClickListener(v -> loadSession());
        findViewById(R.id.web_error_browser).setOnClickListener(v -> openExternal(authUrl));
        findViewById(R.id.web_error_logs).setOnClickListener(v -> startActivity(DiagnosticActivity.downloadLogs(this)));
        findViewById(R.id.web_error_recovery).setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).putExtra("open_launch", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish();
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { navigateBack(); }
        });
        authUrl = getIntent().getStringExtra(EXTRA_URL);
        authCookie = getIntent().getStringExtra(EXTRA_COOKIE);
        String current = com.deepseekharness.app.core.HarnessController.get(this).getWebAuthUrl();
        if (!current.isEmpty() && !current.equals(authUrl)) { authUrl = current; authCookie = null; restoreState = null; }
        baseUrl = WebPreviewPolicy.loopbackBaseUrl(authUrl);
        if (baseUrl == null) {
            authUrl = null;
            showError(com.deepseekharness.app.util.UiText.text("对话地址无效"), com.deepseekharness.app.util.UiText.text("请返回启动页，重新进入对话。"));
            return;
        }
        if (PreviewFallback.preferred(this) && PreviewFallback.open(this, authUrl, authCookie)) return;
        if (retained.view != null && authUrl.equals(retained.authUrl) && authUrl.equals(current)) {
            webView = retained.view;
            ((android.content.MutableContextWrapper) webView.getContext()).setBaseContext(this);
            attachClients(webView);
            container.addView(webView,new FrameLayout.LayoutParams(-1,-1));
            progress.setVisibility(View.GONE);
        } else {
            // 旋转保留同一进程的页面；服务已换代时清理旧页面，再用当前凭据加载。
            if (retained.view != null) { webView = retained.view; destroyWebView(); }
            loadSession();
        }
    }

    private void loadSession() {
        loadSession(true);
    }

    private void loadSession(boolean allowAuthRetry) {
        if (isFinishing() || isDestroyed() || previewAuth.busy()) return;
        errorPanel.setVisibility(View.GONE); progress.setVisibility(View.VISIBLE);
        previewAuth.refresh((url, cookie, error) -> {
            if (error != null) { showError(com.deepseekharness.app.util.UiText.text("暂时无法进入对话"), error); return; }
            if (!url.equals(authUrl)) restoreState = null;
            authUrl = url; authCookie = cookie; baseUrl = WebPreviewPolicy.loopbackBaseUrl(url);
            createWebView(allowAuthRetry);
        });
    }

    private void createWebView(boolean allowAuthRetry) {
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
            PackageInfo provider = android.os.Build.VERSION.SDK_INT >= 26 ? WebView.getCurrentWebViewPackage() : null;
            browserInfo = provider == null ? com.deepseekharness.app.util.UiText.text("系统 WebView 版本未知")
                    : provider.packageName + " " + provider.versionName;
            Log.i("DSHA", com.deepseekharness.app.util.UiText.text("标准版预览内核: ") + browserInfo);
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
                settings.setUserAgentString(WebPreviewPolicy.desktopUserAgent(settings.getUserAgentString()));
            }
            attachClients(view);
            container.addView(view, new FrameLayout.LayoutParams(-1, -1));
            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setAcceptThirdPartyCookies(view, false);
            if (authCookie != null && !authCookie.isEmpty()) {
                // setCookie 是异步的：完成后才加载，避免首次进入偶发未认证。
                cookies.setCookie(baseUrl, authCookie + "; Path=/; HttpOnly; SameSite=Strict", ok -> {
                    if (webView != view || isFinishing() || isDestroyed()) return;
                    if (Boolean.TRUE.equals(ok) && restoreState != null) {
                        Bundle history = restoreState; restoreState = null;
                        if (view.restoreState(history) != null) return;
                    }
                    view.loadUrl(Boolean.TRUE.equals(ok) ? baseUrl : authUrl);
                });
            } else {
                view.loadUrl(authUrl);
            }
        } catch (RuntimeException | LinkageError e) {
            destroyWebView();
            if (PreviewFallback.open(this, authUrl, authCookie)) return;
            Log.w("DSHA", com.deepseekharness.app.util.UiText.text("系统 WebView 初始化失败: ") + e.getClass().getSimpleName());
            showError(com.deepseekharness.app.util.UiText.text("系统 WebView 无法启动"), com.deepseekharness.app.util.UiText.text("请更新或启用 Android System WebView / Chrome，")
                    + com.deepseekharness.app.util.UiText.text("也可以使用系统浏览器进入对话。"));
        }
    }

    private void attachClients(WebView view) {
        updateDocumentLanguage(view);
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) {
            androidx.webkit.WebViewCompat.removeWebMessageListener(view,"DshaLanguage");
            androidx.webkit.WebViewCompat.addWebMessageListener(view,"DshaLanguage",
                java.util.Collections.singleton(baseUrl.substring(0,baseUrl.length()-1)),
                (source,message,origin,mainFrame,reply)->{
                    if(source!=webView||!mainFrame||!WebPreviewPolicy.sameService(baseUrl,source.getUrl()))return;
                    try {
                        String language=message.getData();
                        // 网页只有「中文 / English」两个显式选项，不接受 system：
                        // 把网页当成用户显式选择，避免页面误传偏好值后行为含糊。
                        if(com.deepseekharness.app.util.UiLanguagePreference.EN.equals(language)
                                ||com.deepseekharness.app.util.UiLanguagePreference.ZH.equals(language))
                            LanguageController.select(this,language);
                    } catch(IllegalStateException ignored) { }
                });
        }
        view.setWebViewClient(new PreviewClient());
        view.setWebChromeClient(new PreviewChromeClient());
        view.setDownloadListener((url, agent, disposition, mime, length) -> {
            if (!WebPreviewPolicy.sameService(baseUrl, view.getUrl())) return;
            String name = android.webkit.URLUtil.guessFileName(url,disposition,mime);
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                if (retained.blobDownload == null) retained.blobDownload = new WebBlobDownload(view,downloads.model);
                retained.blobDownload.start(baseUrl,url,name); return;
            }
            downloads.start(baseUrl,url,CookieManager.getInstance().getCookie(url),
                    name,length,null);
        });
    }

    /** 保留网页实例时同步后续文档的启动脚本，避免重载时短暂回到旧语言。 */
    private void updateDocumentLanguage(WebView view) {
        if(!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))return;
        String language=new com.deepseekharness.app.core.ConfigStore(this).getUiLanguage();
        if(retained.compatibilityScript!=null&&language.equals(retained.scriptLanguage))return;
        if(retained.compatibilityScript!=null)retained.compatibilityScript.remove();
        retained.compatibilityScript=androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view,WebPageScripts.compatibility(this),
            java.util.Collections.singleton(baseUrl.substring(0,baseUrl.length()-1)));
        retained.scriptLanguage=language;
    }

    private class PreviewClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (WebPreviewPolicy.pageDownload(baseUrl, url)) return false;
            openExternal(url);
            return true;
        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // 插件的 iframe / 内嵌预览保持 WebView 原有行为，只接管顶层导航。
            if (!request.isForMainFrame()) return false;
            String url = request.getUrl().toString();
            if (WebPreviewPolicy.pageDownload(baseUrl, url)) return false;
            if (request.hasGesture()) openExternal(url);
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (webView != view) return;
            pageFailed = false;
            errorPanel.setVisibility(View.GONE);
            progress.setProgress(0);
            progress.setVisibility(View.VISIBLE);
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (webView != view || pageFailed) return;
            progress.setVisibility(View.GONE);
            if (!WebPreviewPolicy.sameService(baseUrl, url)) return;
            // 厂商内核可能不提供 DOCUMENT_START_SCRIPT；页面入口已提前补齐，这里再幂等核验。
            view.evaluateJavascript(WebPageScripts.compatibility(WebPreviewActivity.this)+"\n"+CAPABILITY_CHECK, result -> {
                if (webView != view || pageFailed || isFinishing() || isDestroyed()) return;
                try {
                    Object missing = new org.json.JSONTokener(result).nextValue();
                    if (missing instanceof String && !((String) missing).isEmpty()) {
                        if (PreviewFallback.open(WebPreviewActivity.this, authUrl, authCookie)) return;
                        showError(com.deepseekharness.app.util.UiText.text("系统 WebView 需要更新"), com.deepseekharness.app.util.UiText.text("当前内核缺少：") + missing
                                + com.deepseekharness.app.util.UiText.text("。\n更新 Android System WebView / Chrome 后重试，或在浏览器中打开。"));
                    }
                } catch (org.json.JSONException ignored) { }
            });
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (webView != view || !request.isForMainFrame()) return;
            showError(com.deepseekharness.app.util.UiText.text("暂时无法连接对话服务"), com.deepseekharness.app.util.UiText.text("服务可能仍在启动或已退出。请稍后重试，")
                    + com.deepseekharness.app.util.UiText.text("持续失败时返回启动页查看日志。\n错误代码：") + error.getErrorCode());
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (webView != view || !request.isForMainFrame()) return;
            int code = response.getStatusCode();
            if ((code == 401 || code == 403) && !authRetried) {
                authRetried = true;
                authCookie = null;
                restoreState = null;
                loadSession(false);
                return;
            }
            showError(code == 401 || code == 403 ? com.deepseekharness.app.util.UiText.text("对话认证已失效") : com.deepseekharness.app.util.UiText.text("对话页面加载失败"),
                    "HTTP " + code + com.deepseekharness.app.util.UiText.text("。请返回启动页重新进入对话，或稍后重试。"));
        }

        @androidx.annotation.RequiresApi(26)
        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (webView == view) {
                destroyWebView();
                showError(com.deepseekharness.app.util.UiText.text("网页渲染进程已退出"), detail.didCrash()
                        ? com.deepseekharness.app.util.UiText.text("系统 WebView 发生异常，点击重试可重新打开；持续出现时请更新内核。")
                        : com.deepseekharness.app.util.UiText.text("系统可能因内存不足回收了网页，点击重试可重新打开。"));
            }
            return true;
        }
    }

    private class PreviewChromeClient extends WebChromeClient {
        @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
            com.deepseekharness.app.core.StartupDiagnostics diagnostics = com.deepseekharness.app.core.HarnessController.get(WebPreviewActivity.this).startupDiagnostics();
            if (message.message().startsWith("[DSHA_PAGE] ") && message.message().length() <= 9500
                    && webView != null && WebPreviewPolicy.sameService(baseUrl, webView.getUrl())) {
                try {
                    org.json.JSONObject event = new org.json.JSONObject(message.message().substring(12));
                    if (diagnostics.pageEvent(startupGeneration, event)) {
                        com.deepseekharness.app.core.HarnessController.get(WebPreviewActivity.this).failedWebPage(startupGeneration,event.optString("message"));
                        startActivity(new Intent(WebPreviewActivity.this,StartupRecoveryActivity.class));finish();
                    }
                } catch (Exception ignored) { }
                return true;
            }
            if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                String source = android.net.Uri.parse(message.sourceId() == null ? "" : message.sourceId()).getPath();
                com.deepseekharness.app.core.DiagnosticLog.record(WebPreviewActivity.this, "WEB_JS",
                        String.valueOf(source) + ":" + message.lineNumber() + " " + message.message());
                if (webView != null && WebPreviewPolicy.sameService(baseUrl, webView.getUrl()))
                    diagnostics.browser(startupGeneration, String.valueOf(source) + ":" + message.lineNumber() + " " + message.message());
            }
            return true;
        }
        @Override public void onProgressChanged(WebView view, int value) {
            if (webView == view && !pageFailed) progress.setProgress(value);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            cancelFileSelection();
            if (webView != view || !WebPreviewPolicy.sameService(baseUrl, view.getUrl())) {
                callback.onReceiveValue(null);
                return true;
            }
            fileCallback = callback;
            retained.pickerCallback = callback;
            Intent primary = null;
            try {
                primary = params.createIntent();
                filePicker.launch(primary);
            } catch (RuntimeException e) {
                try {
                    if (primary == null) primary = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                            .putExtra(Intent.EXTRA_MIME_TYPES,params.getAcceptTypes());
                    filePicker.launch(WebUploads.fallback(primary));
                } catch (RuntimeException ignored) {
                    cancelFileSelection();
                    Toast.makeText(WebPreviewActivity.this, com.deepseekharness.app.util.UiText.text("无法打开文件选择器，请启用系统文件应用"), Toast.LENGTH_LONG).show();
                }
            }
            return true;
        }
    }

    private void showError(String title, String detail) {
        if (isFinishing() || isDestroyed()) return;
        com.deepseekharness.app.core.DiagnosticLog.record(this, "WEB_PAGE", title + com.deepseekharness.app.util.UiText.text("：") + detail);
        pageFailed = true;
        progress.setVisibility(View.GONE);
        errorTitle.setText(title);
        errorDetail.setText(com.deepseekharness.app.util.UiText.text(detail + "\n\n" + browserInfo));
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void openExternal(String url) {
        if (url == null) return;
        Uri uri = Uri.parse(url);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (RuntimeException e) {
            Toast.makeText(this, com.deepseekharness.app.util.UiText.text("未找到可用的系统浏览器"), Toast.LENGTH_SHORT).show();
        }
    }

    private void navigateBack() {
        if (navigatingBack) return;
        WebView current = webView;
        if (pageFailed || current == null || !WebPreviewPolicy.sameService(baseUrl,current.getUrl())) { finish(); return; }
        navigatingBack = true;
        Runnable fallback = () -> {
            if (!navigatingBack || webView != current || isDestroyed()) return;
            navigatingBack = false;
            if (current.canGoBack()) current.goBack(); else finish();
        };
        current.postDelayed(fallback,1200);
        current.evaluateJavascript(WebPageScripts.back(this), result -> {
            if (!navigatingBack || webView != current) return;
            if ("true".equals(result)) { navigatingBack = false; current.removeCallbacks(fallback); }
            else fallback.run();
        });
    }

    private void cancelFileSelection() {
        if (retained == null || retained.pickerCallback == null) return;
        ValueCallback<Uri[]> callback = retained.pickerCallback;
        retained.pickerCallback = null;
        fileCallback = null;
        callback.onReceiveValue(null);
    }

    private void destroyWebView() {
        cancelFileSelection();
        WebView previous = webView;
        webView = null;
        if (retained != null) retained.view = null;
        if(retained!=null){retained.compatibilityScript=null;retained.scriptLanguage=null;}
        if (retained != null && retained.blobDownload != null) { retained.blobDownload.close(); retained.blobDownload = null; }
        if (previous != null) {
            container.removeView(previous);
            previous.destroy();
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) WebFullscreenUi.applySystemBars(this);
    }

    @Override protected void onPause() {
        PluginFragment.invalidateInstalledState();
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if(webView!=null&&WebPreviewPolicy.sameService(baseUrl,webView.getUrl())) {
            updateDocumentLanguage(webView);webView.evaluateJavascript(WebPageScripts.language(this),null);
        }
        String current = com.deepseekharness.app.core.HarnessController.get(this).getWebAuthUrl();
        if (previewAuth != null && !current.isEmpty() && !current.equals(authUrl)) loadSession();
    }

    @Override protected void onDestroy() {
        if (previewAuth != null) previewAuth.cancel();
        if (downloads != null) downloads.dismiss();
        if (isChangingConfigurations() && webView != null) {
            container.removeView(webView);
            webView.setWebViewClient(new WebViewClient()); webView.setWebChromeClient(null); webView.setDownloadListener(null);
            ((android.content.MutableContextWrapper) webView.getContext()).setBaseContext(getApplicationContext());
            webView = null;
        } else destroyWebView();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (downloads != null) downloads.model.saveState(out);
        if (webView != null) { Bundle state = new Bundle(); webView.saveState(state); out.putBundle("browser-state",state); }
        super.onSaveInstanceState(out);
    }
}
