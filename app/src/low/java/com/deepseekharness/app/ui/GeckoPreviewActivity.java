package com.deepseekharness.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.R;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.WebPreviewPolicy;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/** 兼容版浏览器：鉴权、文件上传、错误恢复与系统 WebView 入口保持一致。 */
public final class GeckoPreviewActivity extends AppCompatActivity implements WebFullscreenUi.Host {
    private GeckoSession session;
    private GeckoView browser;
    private FrameLayout container;
    private ProgressBar progress;
    private View errorPanel;
    private long startupGeneration;
    private String authUrl, baseUrl;
    private boolean canGoBack;
    private GeckoSession.PromptDelegate.FilePrompt filePrompt;
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> fileResult;
    private Retained retained;
    private WebDownloads downloads;
    private WebExtension.Port pagePort;
    private int backSequence;
    private boolean backPending;
    private String savedHistory;
    private PreviewAuth previewAuth;
    public static final class Retained extends androidx.lifecycle.ViewModel {
        GeckoSession session;
        String authUrl;
        GeckoSession.SessionState history;
        boolean canGoBack;
        WebExtension.Port port;
        GeckoSession.PromptDelegate.FilePrompt prompt;
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result;
        final ArrayList<File> uploads = new ArrayList<>();
        @Override protected void onCleared() {
            if (prompt != null && result != null) result.complete(prompt.dismiss());
            if (session != null && session.isOpen()) session.close();
            session = null; WebUploads.clean(uploads);
        }
    }

    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                filePrompt = retained.prompt; fileResult = retained.result;
                if (filePrompt == null) return;
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    cancelFilePrompt();
                    return;
                }
                Uri[] selected = WebUploads.parseChooserResult(result.getResultCode(), result.getData());
                if (selected == null) { cancelFilePrompt(); return; }
                receiveFiles(new ArrayList<>(java.util.Arrays.asList(selected)));
            });

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        startupGeneration = com.deepseekharness.app.core.HarnessController.get(this).getWebGeneration();
        retained = new androidx.lifecycle.ViewModelProvider(this).get(Retained.class);
        previewAuth = new PreviewAuth(this);
        downloads = new WebDownloads(this,saved);
        savedHistory = saved == null ? null : saved.getString("gecko-state");
        setContentView(R.layout.activity_web_preview);
        WebFullscreenUi.install(this);
        container = findViewById(R.id.web_container);
        progress = findViewById(R.id.web_progress);
        errorPanel = findViewById(R.id.web_error_panel);
        authUrl = getIntent().getStringExtra("url");
        String current = com.deepseekharness.app.core.HarnessController.get(this).getWebAuthUrl();
        if (!current.isEmpty() && !current.equals(authUrl)) { authUrl = current; savedHistory = null; }
        baseUrl = WebPreviewPolicy.loopbackBaseUrl(authUrl);
        findViewById(R.id.web_error_browser).setOnClickListener(v -> external(authUrl));
        findViewById(R.id.web_error_logs).setOnClickListener(v -> startActivity(DiagnosticActivity.downloadLogs(this)));
        findViewById(R.id.web_error_recovery).setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).putExtra("open_launch", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish();
        });
        findViewById(R.id.web_retry).setOnClickListener(v -> { savedHistory = null; refreshSession(); });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        if (baseUrl == null) { showError(com.deepseekharness.app.util.UiText.text("对话地址无效"), com.deepseekharness.app.util.UiText.text("请返回启动页重新进入。")); return; }
        if (retained.session != null && authUrl.equals(retained.authUrl) && authUrl.equals(current)) load();
        else refreshSession();
    }

    private void refreshSession() {
        if (previewAuth.busy() || isFinishing() || isDestroyed()) return;
        errorPanel.setVisibility(View.GONE); progress.setVisibility(View.VISIBLE);
        previewAuth.refresh((url, cookie, error) -> {
            if (error != null) { showError(com.deepseekharness.app.util.UiText.text("暂时无法进入对话"), error); return; }
            if (!url.equals(authUrl)) savedHistory = null;
            if (session == null && retained.session != null) session = retained.session;
            closeSession();
            authUrl = url; baseUrl = WebPreviewPolicy.loopbackBaseUrl(url);
            load();
        });
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) WebFullscreenUi.applySystemBars(this);
    }

    private void load() {
        if (baseUrl == null || isFinishing()) return;
        errorPanel.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        canGoBack = retained.canGoBack;
        try {
            browser = new GeckoView(this);
            com.deepseekharness.app.core.DiagnosticLog.record(this, "WEB_ENGINE", "Gecko 143");
            boolean desktop = getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
                    .getBoolean(Constants.KEY_DESKTOP_MODE, false);
            boolean fresh = retained.session == null;
            GeckoSession current = fresh ? new GeckoSession(new GeckoSessionSettings.Builder()
                    .userAgentMode(desktop ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                            : GeckoSessionSettings.USER_AGENT_MODE_MOBILE).build()) : retained.session;
            session = current;
            retained.session = current;
            retained.authUrl = authUrl;
            current.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override public void onCanGoBack(GeckoSession s, boolean allowed) { canGoBack = allowed; retained.canGoBack = allowed; }
                @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
                    if (WebPreviewPolicy.pageDownload(baseUrl, request.uri)) return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                    if (request.hasUserGesture) external(request.uri);
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                @Override public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError error) {
                    showError(com.deepseekharness.app.util.UiText.text("对话页面加载失败"), com.deepseekharness.app.util.UiText.text("请确认服务仍在运行，点击重试。错误代码：") + error.code);
                    return null;
                }
            });
            current.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                @Override public void onSessionStateChange(GeckoSession s, GeckoSession.SessionState state) {
                    retained.history = new GeckoSession.SessionState(state);
                }
                @Override public void onPageStart(GeckoSession s, String url) {
                    progress.setProgress(0);
                    progress.setVisibility(View.VISIBLE);
                }
                @Override public void onProgressChange(GeckoSession s, int value) { progress.setProgress(value); }
                @Override public void onPageStop(GeckoSession s, boolean success) {
                    if (s != session) return;
                    progress.setVisibility(View.GONE);
                    if (!success) showError(com.deepseekharness.app.util.UiText.text("页面未完成加载"), com.deepseekharness.app.util.UiText.text("服务可能已退出，点击重试或返回启动页。"));
                    else if (savedHistory != null) {
                        String history = savedHistory; savedHistory = null;
                        try { current.restoreState(GeckoSession.SessionState.fromString(history)); }
                        catch (RuntimeException ignored) { }
                    }
                }
            });
            current.setContentDelegate(new GeckoSession.ContentDelegate() {
                @Override public void onExternalResponse(GeckoSession s, WebResponse response) {
                    if (s != session) { try { if (response.body != null) response.body.close(); } catch (Exception ignored) { } return; }
                    response.setReadTimeoutMillis(30000);
                    String disposition = header(response,"content-disposition"), mime = header(response,"content-type");
                    long size = -1;
                    try { size = Long.parseLong(header(response,"content-length")); } catch (Exception ignored) { }
                    if (header(response,"content-encoding") != null) size = -1;
                    if (response.statusCode != 200 && !(response.statusCode == 0
                            && (response.uri.startsWith("blob:") || response.uri.startsWith("data:")))) {
                        try { if (response.body != null) response.body.close(); } catch (Exception ignored) { }
                        Toast.makeText(GeckoPreviewActivity.this,com.deepseekharness.app.util.UiText.text("下载失败：HTTP ") + response.statusCode,Toast.LENGTH_LONG).show(); return;
                    }
                    downloads.start(baseUrl,response.uri,null,android.webkit.URLUtil.guessFileName(response.uri,disposition,mime),size,response.body);
                }
                @Override public void onCrash(GeckoSession s) { showError(com.deepseekharness.app.util.UiText.text("网页进程异常退出"), com.deepseekharness.app.util.UiText.text("点击重试可重新打开对话。")); }
                @Override public void onKill(GeckoSession s) { showError(com.deepseekharness.app.util.UiText.text("网页进程被系统回收"), com.deepseekharness.app.util.UiText.text("关闭其他应用后重试。")); }
            });
            current.setPromptDelegate(new GeckoSession.PromptDelegate() {
                @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt prompt) {
                    GeckoResult<PromptResponse> result = new GeckoResult<>();
                    new com.deepseekharness.app.ui.DshaDialogBuilder(GeckoPreviewActivity.this)
                            .setTitle(com.deepseekharness.app.util.UiText.text("网页提示")).setMessage(com.deepseekharness.app.util.UiText.text(prompt.message))
                            .setPositiveButton(com.deepseekharness.app.util.UiText.text("确定"), (d, w) -> result.complete(prompt.dismiss()))
                            .setOnCancelListener(d -> result.complete(prompt.dismiss())).show();
                    return result;
                }
                @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt prompt) {
                    GeckoResult<PromptResponse> result = new GeckoResult<>();
                    new com.deepseekharness.app.ui.DshaDialogBuilder(GeckoPreviewActivity.this)
                            .setTitle(com.deepseekharness.app.util.UiText.text("网页确认")).setMessage(com.deepseekharness.app.util.UiText.text(prompt.message))
                            .setPositiveButton(com.deepseekharness.app.util.UiText.text("确定"), (d, w) -> result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE)))
                            .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), (d, w) -> result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE)))
                            .setOnCancelListener(d -> result.complete(prompt.dismiss())).show();
                    return result;
                }
                @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt prompt) {
                    cancelFilePrompt();
                    if (prompt.type == FilePrompt.Type.FOLDER) {
                        Toast.makeText(GeckoPreviewActivity.this, com.deepseekharness.app.util.UiText.text("请先将文件夹压缩为文件再上传"), Toast.LENGTH_LONG).show();
                        return GeckoResult.fromValue(prompt.dismiss());
                    }
                    filePrompt = prompt;
                    fileResult = new GeckoResult<>();
                    retained.prompt = filePrompt; retained.result = fileResult;
                    GeckoResult<PromptResponse> pending = fileResult;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, prompt.type == FilePrompt.Type.MULTIPLE);
                    if (prompt.mimeTypes != null && prompt.mimeTypes.length > 0)
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, prompt.mimeTypes);
                    try { picker.launch(intent); } catch (RuntimeException error) {
                        try { picker.launch(WebUploads.fallback(intent)); }
                        catch (RuntimeException ignored) { cancelFilePrompt(); Toast.makeText(GeckoPreviewActivity.this,
                                com.deepseekharness.app.util.UiText.text("无法打开文件选择器，请启用系统文件应用"),Toast.LENGTH_LONG).show(); }
                    }
                    return pending;
                }
            });
            GeckoRuntime runtime = GeckoRuntime.getDefault(this);
            if (fresh) current.open(runtime);
            browser.setSession(current);
            container.addView(browser, new FrameLayout.LayoutParams(-1, -1));
            runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/web-integration/", "dsha-page@dsh.client")
                    .accept(extension -> runOnUiThread(() -> {
                        if (session != current) return;
                        attachPageBridge(current,extension);
                        if (fresh) loadInitial(current); else progress.setVisibility(View.GONE);
                    }), error -> runOnUiThread(() -> {
                        if (session != current) return;
                        Toast.makeText(this,com.deepseekharness.app.util.UiText.text("页面返回适配未加载，可重试打开对话"),Toast.LENGTH_LONG).show();
                        if (fresh) loadInitial(current);
                    }));
        } catch (RuntimeException | LinkageError error) {
            closeSession();
            showError(com.deepseekharness.app.util.UiText.text("兼容内核无法启动"), com.deepseekharness.app.util.UiText.text("可尝试在系统浏览器打开。错误：") + error.getClass().getSimpleName());
        }
    }

    private void receiveFiles(ArrayList<Uri> uris) {
        final Retained owner = retained;
        final GeckoSession.PromptDelegate.FilePrompt prompt = owner.prompt;
        final GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pending = owner.result;
        if (prompt == null || uris.isEmpty()) { cancelFilePrompt(); return; }
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            ArrayList<File> copied = new ArrayList<>();
            String failure = null;
            try { copied = WebUploads.copy(app,uris); }
            catch (Exception error) { failure = error.getMessage(); }
            final ArrayList<File> ready = copied;
            final String error = failure;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (owner.prompt != prompt || owner.session == null || error != null) {
                    WebUploads.clean(ready);
                    if (owner.prompt == prompt) {
                        pending.complete(prompt.dismiss()); owner.prompt = null; owner.result = null;
                    }
                    if (error != null) Toast.makeText(app,com.deepseekharness.app.util.UiText.text("上传失败：") + error,Toast.LENGTH_LONG).show();
                    return;
                }
                Uri[] files = new Uri[ready.size()];
                for (int i=0;i<files.length;i++) files[i] = Uri.fromFile(ready.get(i));
                owner.uploads.addAll(ready);
                pending.complete(prompt.confirm(app,files));
                owner.prompt = null; owner.result = null;
            });
        },"gecko-file-import").start();
    }

    private void cancelFilePrompt() {
        if (retained != null) { filePrompt = retained.prompt; fileResult = retained.result; }
        if (filePrompt != null && fileResult != null) fileResult.complete(filePrompt.dismiss());
        filePrompt = null; fileResult = null;
        if (retained != null) { retained.prompt = null; retained.result = null; }
    }
    private void back() {
        if (backPending) return;
        if (pagePort == null) { historyBack(); return; }
        backPending = true; int id = ++backSequence;
        try { pagePort.postMessage(new org.json.JSONObject().put("type","back").put("id",id)); }
        catch (Exception error) { backPending = false; historyBack(); return; }
        container.postDelayed(() -> { if (backPending && id == backSequence) { backPending = false; historyBack(); } },1200);
    }
    private void historyBack() { if (session != null && canGoBack) session.goBack(); else finish(); }
    private void loadInitial(GeckoSession current) {
        // 先由 Gecko 自己完成 token → Cookie 交换；成功后再恢复历史，避免进程重建绕过鉴权。
        current.loadUri(authUrl);
    }
    private static String header(WebResponse response, String name) {
        for (java.util.Map.Entry<String,String> h : response.headers.entrySet()) if (h.getKey().equalsIgnoreCase(name)) return h.getValue();
        return null;
    }
    private void attachPageBridge(GeckoSession current, WebExtension extension) {
        current.getWebExtensionController().setMessageDelegate(extension,new WebExtension.MessageDelegate() {
            @Override public void onConnect(WebExtension.Port port) {
                if (port.sender.session != session || !port.sender.isTopLevel()
                        || !WebPreviewPolicy.sameService(baseUrl,port.sender.url)) { port.disconnect(); return; }
                retained.port = port; pagePort = port;
                port.setDelegate(new WebExtension.PortDelegate() {
                    @Override public void onPortMessage(Object message, WebExtension.Port source) {
                        if (source != pagePort || !(message instanceof org.json.JSONObject)) return;
                        org.json.JSONObject value = (org.json.JSONObject) message;
                        if ("language-selected".equals(value.optString("type"))) {
                            LanguageController.select(GeckoPreviewActivity.this,value.optString("language"));return;
                        }
                        if ("startup".equals(value.optString("type"))) {
                            org.json.JSONObject event = value.optJSONObject("report");
                            if (event != null && event.toString().length() <= 9500
                                    && com.deepseekharness.app.core.HarnessController.get(GeckoPreviewActivity.this)
                                    .startupDiagnostics().pageEvent(startupGeneration, event))
                            {
                                com.deepseekharness.app.core.HarnessController.get(GeckoPreviewActivity.this).failedWebPage(startupGeneration,event.optString("message"));
                                startActivity(new android.content.Intent(GeckoPreviewActivity.this,StartupRecoveryActivity.class));finish();
                            }
                            return;
                        }
                        if (!backPending || !"back".equals(value.optString("type")) || value.optInt("id") != backSequence) return;
                        backPending = false;
                        if (!value.optBoolean("handled")) historyBack();
                    }
                    @Override public void onDisconnect(WebExtension.Port source) { if (pagePort == source) { pagePort = null; retained.port = null; } }
                });
                try { port.postMessage(new org.json.JSONObject().put("type","language")
                        .put("language",new com.deepseekharness.app.core.ConfigStore(GeckoPreviewActivity.this).getUiLanguage())); }
                catch(org.json.JSONException ignored) { }
            }
        },"dsha");
        if (retained.port != null) current.getWebExtensionController().getMessageDelegate(extension,"dsha").onConnect(retained.port);
    }
    private void external(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (RuntimeException error) { Toast.makeText(this, com.deepseekharness.app.util.UiText.text("没有可用的系统浏览器"), Toast.LENGTH_SHORT).show(); }
    }
    private void showError(String title, String detail) {
        com.deepseekharness.app.core.DiagnosticLog.record(this, "GECKO_PAGE", title + com.deepseekharness.app.util.UiText.text("：") + detail);
        if (isFinishing() || isDestroyed()) return;
        ((TextView) findViewById(R.id.web_error_title)).setText(title);
        ((TextView) findViewById(R.id.web_error_detail)).setText(detail + com.deepseekharness.app.util.UiText.text("\n兼容内核 Gecko 143"));
        progress.setVisibility(View.GONE); errorPanel.setVisibility(View.VISIBLE);
    }
    private void closeSession() {
        cancelFilePrompt();
        if (browser != null) { browser.releaseSession(); container.removeView(browser); browser = null; }
        if (session != null) { session.close(); session = null; }
        if (retained != null) retained.session = null;
        pagePort = null;
        if (retained != null) retained.port = null;
    }
    @Override protected void onPause() {
        PluginFragment.invalidateInstalledState();
        if (session != null) session.setActive(false); super.onPause();
    }
    @Override protected void onResume() {
        super.onResume(); if (session != null) session.setActive(true);
        if(pagePort!=null)try{pagePort.postMessage(new org.json.JSONObject().put("type","language")
                .put("language",new com.deepseekharness.app.core.ConfigStore(this).getUiLanguage()));}
        catch(org.json.JSONException ignored) { }
        String current = com.deepseekharness.app.core.HarnessController.get(this).getWebAuthUrl();
        if (previewAuth != null && !current.isEmpty() && !current.equals(authUrl)) refreshSession();
    }
    @Override protected void onDestroy() {
        if (previewAuth != null) previewAuth.cancel();
        if (downloads != null) downloads.dismiss();
        if (isChangingConfigurations() && session != null) {
            if (browser != null) { browser.releaseSession(); container.removeView(browser); browser = null; }
            session.setNavigationDelegate(null); session.setProgressDelegate(null); session.setPromptDelegate(null); session.setContentDelegate(null);
            // 保留消息端口，新的 Activity 在接管后更换代理。
            if (pagePort != null) pagePort.setDelegate(null);
            session = null;
        } else closeSession();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (downloads != null) downloads.model.saveState(out);
        if (retained.history != null) out.putString("gecko-state",retained.history.toString());
        super.onSaveInstanceState(out);
    }
}
