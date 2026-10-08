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

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.GeckoFileChooserState;
import com.deepseekharness.app.util.PreviewNavigation;
import com.deepseekharness.app.util.PreviewPageSession;
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

import java.util.ArrayList;

/** 兼容版浏览器：鉴权、文件上传、错误恢复与系统 WebView 入口保持一致。 */
public final class GeckoPreviewActivity extends PictureInPictureActivity
    implements WebFullscreenUi.Host {
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
  private ActivityResultLauncher<Intent> picker;
  private PendingFileUpload pickerOwner;
  private Retained retained;
  private WebDownloads downloads;
  private WebExtension.Port pagePort;
  private int backSequence;
  private boolean backPending;
  private String savedHistory;
  private PreviewPageSession.Identity savedIdentity;
  private PreviewPageSession.Identity pendingIdentity;
  private HarnessController controller;
  private PreviewAuth previewAuth;
  private BrowserMicrophone microphone;
  private GeckoMicrophoneDelegate microphoneDelegate;
  private boolean browserResumed;
  private final Runnable readyWebListener =
      () ->
          runOnUiThread(
              () -> {
                if (!isFinishing()
                    && !isDestroyed()
                    && getLifecycle()
                        .getCurrentState()
                        .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) syncSession(false);
              });

  private static final class PendingFileUpload {
    final GeckoSession session;
    final GeckoSession.PromptDelegate.FilePrompt prompt;
    final GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result;
    final WebUploads.Session uploads;
    final PreviewPageSession.Identity identity;
    final String pickerKey = "gecko-upload-" + java.util.UUID.randomUUID();
    final com.deepseekharness.app.util.BrowserUploadRequestState.Ticket<
            GeckoSession, WebUploads.Session>
        ticket;
    final java.util.concurrent.atomic.AtomicBoolean completed =
        new java.util.concurrent.atomic.AtomicBoolean();

    PendingFileUpload(
        GeckoSession session,
        GeckoSession.PromptDelegate.FilePrompt prompt,
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result,
        WebUploads.Session uploads,
        PreviewPageSession.Identity identity,
        com.deepseekharness.app.util.BrowserUploadRequestState.Ticket<
                GeckoSession, WebUploads.Session>
            ticket) {
      this.session = session;
      this.prompt = prompt;
      this.result = result;
      this.uploads = uploads;
      this.identity = identity;
      this.ticket = ticket;
    }

    boolean complete(GeckoSession.PromptDelegate.PromptResponse value) {
      if (!completed.compareAndSet(false, true)) return false;
      result.complete(value);
      return true;
    }

    boolean dismiss() {
      return complete(prompt.dismiss());
    }
  }

  public static final class Retained extends androidx.lifecycle.ViewModel {
    GeckoSession session;
    boolean ready;
    boolean needsStreamResume;
    String authUrl;
    String documentUrl;
    GeckoSession.SessionState history;
    boolean canGoBack;
    WebExtension.Port port;
    com.deepseekharness.app.util.StartupPageGate pageEvents;
    PendingFileUpload pendingUpload;
    WebUploads.Session uploadSession;
    final PreviewPageSession page = new PreviewPageSession();
    final PreviewNavigation navigation = new PreviewNavigation();
    final GeckoFileChooserState<PendingFileUpload> chooser = new GeckoFileChooserState<>();
    final com.deepseekharness.app.util.BrowserUploadRequestState<GeckoSession, WebUploads.Session>
        uploadRequests = new com.deepseekharness.app.util.BrowserUploadRequestState<>();

    @Override
    protected void onCleared() {
      if (pendingUpload != null) {
        pendingUpload.dismiss();
        pendingUpload = null;
      }
      uploadRequests.close();
      chooser.takeResult();
      navigation.cancel();
      page.clear();
      if (session != null && session.isOpen()) session.close();
      session = null;
      if (uploadSession != null) {
        uploadSession.close();
        uploadSession = null;
      }
    }
  }

  private void registerPicker(PendingFileUpload pending) {
    if (pending == null || pickerOwner == pending && picker != null) return;
    if (picker != null) picker.unregister();
    picker = null;
    pickerOwner = pending;
    // 旋转接管原请求的 key；进程重建后不再注册失去请求对象的旧 key。
    ActivityResultLauncher<Intent> registered =
        getActivityResultRegistry()
            .register(
                pending.pickerKey,
                new ActivityResultContracts.StartActivityForResult(),
                result -> receiveChooserResult(pending, result));
    if (retained.chooser.pending() == pending) picker = registered;
    else {
      registered.unregister();
      if (pickerOwner == pending) pickerOwner = null;
    }
  }

  private void unregisterPicker(PendingFileUpload pending) {
    if (pickerOwner != pending) return;
    if (picker != null) picker.unregister();
    picker = null;
    pickerOwner = null;
  }

  private void receiveChooserResult(
      PendingFileUpload pending, androidx.activity.result.ActivityResult result) {
    if (retained.chooser.takeResult(pending) == null) return;
    unregisterPicker(pending);
    if (!uploadCurrent(retained, pending)) {
      pending.dismiss();
      if (retained.pendingUpload == pending) cancelFilePrompt();
      return;
    }
    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
      cancelFilePrompt();
      return;
    }
    Uri[] selected = WebUploads.parseChooserResult(result.getResultCode(), result.getData());
    if (selected == null) {
      cancelFilePrompt();
      return;
    }
    receiveFiles(pending, new ArrayList<>(java.util.Arrays.asList(selected)));
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    controller = HarnessController.get(this);
    retained = new androidx.lifecycle.ViewModelProvider(this).get(Retained.class);
    previewAuth = new PreviewAuth(this);
    microphone = new BrowserMicrophone(this);
    downloads = new WebDownloads(this, saved);
    savedHistory = saved == null ? null : saved.getString("gecko-state");
    savedIdentity = restoreIdentity(saved);
    setContentView(R.layout.activity_web_preview);
    WebFullscreenUi.install(this);
    container = findViewById(R.id.web_container);
    progress = findViewById(R.id.web_progress);
    errorPanel = findViewById(R.id.web_error_panel);
    authUrl = getIntent().getStringExtra("url");
    PreviewPageSession.Identity current = controller.getReadyWebPageIdentity();
    if (current != null) authUrl = current.authUrl();
    else if (retained.page.identity() != null) authUrl = retained.page.identity().authUrl();
    baseUrl = WebPreviewPolicy.loopbackBaseUrl(authUrl);
    findViewById(R.id.web_error_browser).setOnClickListener(v -> external(authUrl));
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
    findViewById(R.id.web_retry)
        .setOnClickListener(
            v -> {
              savedHistory = null;
              refreshSession();
            });
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                back();
              }
            });
    registerPicker(retained.chooser.pending());
    if (baseUrl == null) {
      showError(
          com.deepseekharness.app.util.UiText.text("对话地址无效"),
          com.deepseekharness.app.util.UiText.text("请返回启动页重新进入。"));
      return;
    }
    syncSession(false);
  }

  private void refreshSession() {
    syncSession(true);
  }

  /** 新实例真正就绪后替换页面；切回、旋转和语言重建继续接管同一 Gecko 会话。 */
  private void syncSession(boolean retry) {
    if (retained == null || previewAuth == null || isFinishing() || isDestroyed()) return;
    PreviewPageSession.Identity current = controller.getReadyWebPageIdentity();
    if (current == null) {
      previewAuth.cancel();
      pendingIdentity = null;
      cancelFilePrompt();
      if (microphoneDelegate != null) microphoneDelegate.cancelPending();
      backPending = false;
      backSequence++;
      if (session == null && retained.session != null && retained.page.identity() != null) {
        savedHistory = null;
        authUrl = retained.page.identity().authUrl();
        startupGeneration = retained.page.identity().generation();
        baseUrl = WebPreviewPolicy.loopbackBaseUrl(authUrl);
        load();
      }
      progress.setVisibility(View.VISIBLE);
      refreshPictureInPicture();
      return;
    }
    if (!retry && retained.session != null && retained.page.isCurrent(current)) {
      authUrl = current.authUrl();
      baseUrl = WebPreviewPolicy.loopbackBaseUrl(authUrl);
      startupGeneration = current.generation();
      if (session == null) {
        savedHistory = null;
        load();
      } else resumePageStreams();
      return;
    }
    if (previewAuth.busy() && current.equals(pendingIdentity)) return;
    previewAuth.cancel();
    pendingIdentity = current;
    cancelFilePrompt();
    if (microphoneDelegate != null) microphoneDelegate.cancelPending();
    backPending = false;
    backSequence++;
    errorPanel.setVisibility(View.GONE);
    progress.setVisibility(View.VISIBLE);
    refreshPictureInPicture();
    previewAuth.refresh(
        (url, cookie, error) -> {
          if (!current.equals(pendingIdentity)) return;
          pendingIdentity = null;
          if (!current.equals(controller.getReadyWebPageIdentity())) {
            syncSession(false);
            return;
          }
          if (error != null) {
            showError(com.deepseekharness.app.util.UiText.text("暂时无法进入对话"), error);
            return;
          }
          if (!current.equals(savedIdentity)) savedHistory = null;
          if (session == null && retained.session != null) session = retained.session;
          closeSession();
          retained.page.claim(current);
          authUrl = url;
          baseUrl = WebPreviewPolicy.loopbackBaseUrl(url);
          startupGeneration = current.generation();
          load();
        });
  }

  private static PreviewPageSession.Identity restoreIdentity(Bundle saved) {
    if (saved == null) return null;
    try {
      return new PreviewPageSession.Identity(
          saved.getLong("gecko-generation", -1),
          saved.getString("gecko-instance"),
          saved.getString("gecko-auth-url"));
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private boolean pageCurrent(GeckoSession source, PreviewPageSession.Identity identity) {
    return source == session
        && retained.session == source
        && identity != null
        && identity.equals(retained.page.identity())
        && identity.equals(controller.getReadyWebPageIdentity())
        && !isFinishing()
        && !isDestroyed();
  }

  private boolean uploadCurrent(Retained owner, PendingFileUpload request) {
    return request != null
        && !request.completed.get()
        && owner.pendingUpload == request
        && owner.page.isCurrent(request.identity)
        && request.identity.equals(controller.getReadyWebPageIdentity())
        && owner.uploadRequests.owns(request.ticket, owner.session, owner.uploadSession);
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) WebFullscreenUi.applySystemBars(this);
  }

  private void load() {
    final PreviewPageSession.Identity pageIdentity = retained.page.identity();
    if (baseUrl == null || pageIdentity == null || isFinishing()) return;
    errorPanel.setVisibility(View.GONE);
    progress.setVisibility(View.VISIBLE);
    canGoBack = retained.canGoBack;
    try {
      browser = new GeckoView(this);
      com.deepseekharness.app.core.DiagnosticLog.record(this, "WEB_ENGINE", "Gecko 143");
      boolean desktop =
          getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
              .getBoolean(Constants.KEY_DESKTOP_MODE, false);
      boolean fresh = retained.session == null;
      GeckoSession current =
          fresh
              ? new GeckoSession(
                  new GeckoSessionSettings.Builder()
                      .userAgentMode(
                          desktop
                              ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                              : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                      .build())
              : retained.session;
      session = current;
      retained.session = current;
      retained.authUrl = authUrl;
      if (fresh) {
        retained.documentUrl = null;
        retained.needsStreamResume = false;
        retained.navigation.begin(false);
      }
      if (microphoneDelegate != null) microphoneDelegate.close();
      microphoneDelegate =
          new GeckoMicrophoneDelegate(
              microphone,
              current,
              () -> baseUrl,
              () -> retained.documentUrl,
              () ->
                  pageCurrent(current, pageIdentity)
                      && getLifecycle()
                          .getCurrentState()
                          .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED));
      current.setPermissionDelegate(microphoneDelegate);
      current.setNavigationDelegate(
          new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(
                GeckoSession s,
                String url,
                java.util.List<GeckoSession.PermissionDelegate.ContentPermission> permissions,
                Boolean hasUserGesture) {
              if (!pageCurrent(s, pageIdentity)) return;
              if (!java.util.Objects.equals(retained.documentUrl, url)) {
                cancelFilePrompt();
                if (microphoneDelegate != null) microphoneDelegate.cancelPending();
              }
              retained.documentUrl = url;
            }

            @Override
            public void onCanGoBack(GeckoSession s, boolean allowed) {
              if (!pageCurrent(s, pageIdentity)) return;
              canGoBack = allowed;
              retained.canGoBack = allowed;
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
              if (!pageCurrent(s, pageIdentity)) return GeckoResult.fromValue(AllowOrDeny.DENY);
              if (WebPreviewPolicy.pageDownload(baseUrl, request.uri))
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
              if (request.hasUserGesture) external(request.uri);
              return GeckoResult.fromValue(AllowOrDeny.DENY);
            }

            @Override
            public GeckoResult<String> onLoadError(
                GeckoSession s, String uri, WebRequestError error) {
              if (!pageCurrent(s, pageIdentity)) return null;
              showError(
                  com.deepseekharness.app.util.UiText.text("对话页面加载失败"),
                  com.deepseekharness.app.util.UiText.format("请确认服务仍在运行，点击重试。错误代码：%s", error.code));
              return null;
            }
          });
      current.setProgressDelegate(
          new GeckoSession.ProgressDelegate() {
            @Override
            public void onSessionStateChange(GeckoSession s, GeckoSession.SessionState state) {
              if (!pageCurrent(s, pageIdentity)) return;
              retained.history = new GeckoSession.SessionState(state);
            }

            @Override
            public void onPageStart(GeckoSession s, String url) {
              if (!pageCurrent(s, pageIdentity)) return;
              cancelFilePrompt();
              if (microphoneDelegate != null) microphoneDelegate.cancelPending();
              retained.documentUrl = url;
              retained.ready = false;
              refreshPictureInPicture();
              progress.setProgress(0);
              progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onProgressChange(GeckoSession s, int value) {
              if (!pageCurrent(s, pageIdentity)) return;
              progress.setProgress(value);
            }

            @Override
            public void onPageStop(GeckoSession s, boolean success) {
              if (!pageCurrent(s, pageIdentity)) return;
              retained.ready = success;
              refreshPictureInPicture();
              if (success) resumePageStreams();
              progress.setVisibility(View.GONE);
              if (!success)
                showError(
                    com.deepseekharness.app.util.UiText.text("页面未完成加载"),
                    com.deepseekharness.app.util.UiText.text("服务可能已退出，点击重试或返回启动页。"));
              else if (savedHistory != null) {
                String history = savedHistory;
                savedHistory = null;
                try {
                  current.restoreState(GeckoSession.SessionState.fromString(history));
                } catch (RuntimeException ignored) {
                }
              }
            }
          });
      current.setContentDelegate(
          new GeckoSession.ContentDelegate() {
            @Override
            public void onExternalResponse(GeckoSession s, WebResponse response) {
              if (!pageCurrent(s, pageIdentity)) {
                try {
                  if (response.body != null) response.body.close();
                } catch (Exception ignored) {
                }
                return;
              }
              response.setReadTimeoutMillis(30000);
              String disposition = header(response, "content-disposition"),
                  mime = header(response, "content-type");
              long size = -1;
              try {
                size = Long.parseLong(header(response, "content-length"));
              } catch (Exception ignored) {
              }
              if (header(response, "content-encoding") != null) size = -1;
              if (response.statusCode != 200
                  && !(response.statusCode == 0
                      && (response.uri.startsWith("blob:") || response.uri.startsWith("data:")))) {
                try {
                  if (response.body != null) response.body.close();
                } catch (Exception ignored) {
                }
                Toast.makeText(
                        GeckoPreviewActivity.this,
                        com.deepseekharness.app.util.UiText.format(
                            "下载失败：HTTP %s", response.statusCode),
                        Toast.LENGTH_LONG)
                    .show();
                return;
              }
              downloads.start(
                  baseUrl,
                  response.uri,
                  null,
                  android.webkit.URLUtil.guessFileName(response.uri, disposition, mime),
                  size,
                  response.body);
            }

            @Override
            public void onCrash(GeckoSession s) {
              if (!pageCurrent(s, pageIdentity)) return;
              showError(
                  com.deepseekharness.app.util.UiText.text("网页进程异常退出"),
                  com.deepseekharness.app.util.UiText.text("点击重试可重新打开对话。"));
            }

            @Override
            public void onKill(GeckoSession s) {
              if (!pageCurrent(s, pageIdentity)) return;
              showError(
                  com.deepseekharness.app.util.UiText.text("网页进程被系统回收"),
                  com.deepseekharness.app.util.UiText.text("关闭其他应用后重试。"));
            }
          });
      current.setPromptDelegate(
          new GeckoSession.PromptDelegate() {
            @Override
            public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt prompt) {
              if (!pageCurrent(s, pageIdentity)) return GeckoResult.fromValue(prompt.dismiss());
              GeckoResult<PromptResponse> result = new GeckoResult<>();
              new com.deepseekharness.app.ui.DshaDialogBuilder(GeckoPreviewActivity.this)
                  .setTitle(com.deepseekharness.app.util.UiText.format("网页提示（%s）", baseUrl))
                  .setMessage(com.deepseekharness.app.util.UiText.raw(prompt.message))
                  .setPositiveButton(
                      com.deepseekharness.app.util.UiText.text("确定"),
                      (d, w) -> result.complete(prompt.dismiss()))
                  .setOnCancelListener(d -> result.complete(prompt.dismiss()))
                  .show();
              return result;
            }

            @Override
            public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt prompt) {
              if (!pageCurrent(s, pageIdentity)) return GeckoResult.fromValue(prompt.dismiss());
              GeckoResult<PromptResponse> result = new GeckoResult<>();
              new com.deepseekharness.app.ui.DshaDialogBuilder(GeckoPreviewActivity.this)
                  .setTitle(com.deepseekharness.app.util.UiText.format("网页确认（%s）", baseUrl))
                  .setMessage(com.deepseekharness.app.util.UiText.raw(prompt.message))
                  .setPositiveButton(
                      com.deepseekharness.app.util.UiText.text("确定"),
                      (d, w) ->
                          result.complete(
                              pageCurrent(s, pageIdentity)
                                  ? prompt.confirm(ButtonPrompt.Type.POSITIVE)
                                  : prompt.dismiss()))
                  .setNegativeButton(
                      com.deepseekharness.app.util.UiText.text("取消"),
                      (d, w) ->
                          result.complete(
                              pageCurrent(s, pageIdentity)
                                  ? prompt.confirm(ButtonPrompt.Type.NEGATIVE)
                                  : prompt.dismiss()))
                  .setOnCancelListener(d -> result.complete(prompt.dismiss()))
                  .show();
              return result;
            }

            @Override
            public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt prompt) {
              if (!pageCurrent(s, pageIdentity) || retained.chooser.waiting())
                return GeckoResult.fromValue(prompt.dismiss());
              cancelFilePrompt();
              if (prompt.type == FilePrompt.Type.FOLDER) {
                Toast.makeText(
                        GeckoPreviewActivity.this,
                        com.deepseekharness.app.util.UiText.text("请先将文件夹压缩为文件再上传"),
                        Toast.LENGTH_LONG)
                    .show();
                return GeckoResult.fromValue(prompt.dismiss());
              }
              filePrompt = prompt;
              fileResult = new GeckoResult<>();
              WebUploads.Session uploadSession = retained.uploadSession;
              if (uploadSession == null || uploadSession.isClosed())
                retained.uploadSession = uploadSession = new WebUploads.Session(getCacheDir());
              PendingFileUpload upload =
                  new PendingFileUpload(
                      s,
                      prompt,
                      fileResult,
                      uploadSession,
                      pageIdentity,
                      retained.uploadRequests.begin(s, uploadSession));
              if (!retained.chooser.begin(upload)) {
                retained.uploadRequests.finish(upload.ticket);
                upload.dismiss();
                filePrompt = null;
                fileResult = null;
                return upload.result;
              }
              retained.pendingUpload = upload;
              GeckoResult<PromptResponse> pending = fileResult;
              Intent intent =
                  WebUploadChooser.intent(
                      null, prompt.type == FilePrompt.Type.MULTIPLE, prompt.mimeTypes);
              try {
                registerPicker(upload);
                picker.launch(intent);
              } catch (RuntimeException error) {
                try {
                  if (picker == null) throw error;
                  picker.launch(WebUploads.fallback(intent));
                } catch (RuntimeException ignored) {
                  retained.chooser.takeResult();
                  unregisterPicker(upload);
                  cancelFilePrompt();
                  Toast.makeText(
                          GeckoPreviewActivity.this,
                          com.deepseekharness.app.util.UiText.text("无法打开文件选择器，请启用系统文件应用"),
                          Toast.LENGTH_LONG)
                      .show();
                }
              }
              return pending;
            }
          });
      GeckoRuntime runtime = GeckoRuntime.getDefault(this);
      if (fresh) current.open(runtime);
      browser.setSession(current);
      container.addView(browser, new FrameLayout.LayoutParams(-1, -1));
      runtime
          .getWebExtensionController()
          .ensureBuiltIn("resource://android/assets/web-integration/", "dsha-page@dsh.client")
          .accept(
              extension ->
                  runOnUiThread(
                      () -> {
                        if (!pageCurrent(current, pageIdentity)) return;
                        attachPageBridge(current, extension);
                        loadInitial(current);
                        if (retained.ready) progress.setVisibility(View.GONE);
                      }),
              error ->
                  runOnUiThread(
                      () -> {
                        if (!pageCurrent(current, pageIdentity)) return;
                        Toast.makeText(
                                this,
                                com.deepseekharness.app.util.UiText.text("页面返回适配未加载，可重试打开对话"),
                                Toast.LENGTH_LONG)
                            .show();
                        loadInitial(current);
                      }));
    } catch (RuntimeException | LinkageError error) {
      closeSession();
      showError(
          com.deepseekharness.app.util.UiText.text("兼容内核无法启动"),
          com.deepseekharness.app.util.UiText.format(
              "可尝试在系统浏览器打开。错误：%s", error.getClass().getSimpleName()));
    }
  }

  private void receiveFiles(PendingFileUpload request, ArrayList<Uri> uris) {
    final Retained owner = retained;
    if (!uploadCurrent(owner, request) || uris.isEmpty()) {
      cancelFilePrompt();
      return;
    }
    final android.content.Context app = getApplicationContext();
    new Thread(
            () -> {
              WebUploads.Batch batch = null;
              String failure = null;
              try {
                batch = request.uploads.copy(app, uris);
              } catch (Exception error) {
                failure = error.getMessage();
              }
              final WebUploads.Batch ready = batch;
              final String error = failure;
              new android.os.Handler(android.os.Looper.getMainLooper())
                  .post(
                      () -> {
                        boolean active = uploadCurrent(owner, request);
                        if (!active || error != null || ready == null) {
                          if (ready != null) ready.close();
                          if (owner.pendingUpload == request) {
                            owner.pendingUpload = null;
                            owner.uploadRequests.finish(request.ticket);
                            request.dismiss();
                            filePrompt = null;
                            fileResult = null;
                          }
                          if (active && error != null && !request.uploads.isClosed())
                            Toast.makeText(
                                    app,
                                    com.deepseekharness.app.util.UiText.format("上传失败：%s", error),
                                    Toast.LENGTH_LONG)
                                .show();
                          return;
                        }
                        Uri[] files = new Uri[ready.files().size()];
                        for (int i = 0; i < files.length; i++)
                          files[i] = Uri.fromFile(ready.files().get(i));
                        if (!ready.commit()) {
                          ready.close();
                          owner.pendingUpload = null;
                          owner.uploadRequests.finish(request.ticket);
                          request.dismiss();
                          filePrompt = null;
                          fileResult = null;
                          return;
                        }
                        if (uploadCurrent(owner, request))
                          request.complete(request.prompt.confirm(app, files));
                        else request.dismiss();
                        owner.pendingUpload = null;
                        owner.uploadRequests.finish(request.ticket);
                        filePrompt = null;
                        fileResult = null;
                      });
            },
            "gecko-file-import")
        .start();
  }

  private void cancelFilePrompt() {
    if (retained != null) retained.uploadRequests.invalidate();
    if (retained != null && retained.pendingUpload != null) {
      PendingFileUpload pending = retained.pendingUpload;
      retained.pendingUpload = null;
      pending.dismiss();
    } else if (filePrompt != null && fileResult != null) fileResult.complete(filePrompt.dismiss());
    filePrompt = null;
    fileResult = null;
  }

  private void back() {
    if (backPending) return;
    if (session == null || !retained.page.isCurrent(controller.getReadyWebPageIdentity())) {
      leavePreview();
      return;
    }
    if (pagePort == null) {
      historyBack();
      return;
    }
    backPending = true;
    int id = ++backSequence;
    try {
      pagePort.postMessage(new org.json.JSONObject().put("type", "back").put("id", id));
    } catch (Exception error) {
      backPending = false;
      historyBack();
      return;
    }
    container.postDelayed(
        () -> {
          if (backPending && id == backSequence) {
            backPending = false;
            historyBack();
          }
        },
        1200);
  }

  private void historyBack() {
    if (session != null && canGoBack) session.goBack();
    else leavePreview();
  }

  private void loadInitial(GeckoSession current) {
    // 先由 Gecko 自己完成 token → Cookie 交换；成功后再恢复历史，避免进程重建绕过鉴权。
    if (!pageCurrent(current, retained.page.identity()) || !retained.navigation.claim()) return;
    current.load(
        new GeckoSession.Loader().uri(authUrl).flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE));
  }

  private static String header(WebResponse response, String name) {
    for (java.util.Map.Entry<String, String> h : response.headers.entrySet())
      if (h.getKey().equalsIgnoreCase(name)) return h.getValue();
    return null;
  }

  private void attachPageBridge(GeckoSession current, WebExtension extension) {
    final PreviewPageSession.Identity pageIdentity = retained.page.identity();
    current
        .getWebExtensionController()
        .setMessageDelegate(
            extension,
            new WebExtension.MessageDelegate() {
              @Override
              public void onConnect(WebExtension.Port port) {
                if (!pageCurrent(current, pageIdentity)
                    || port.sender.session != session
                    || !port.sender.isTopLevel()
                    || !WebPreviewPolicy.sameService(baseUrl, port.sender.url)) {
                  port.disconnect();
                  return;
                }
                retained.port = port;
                pagePort = port;
                if (retained.pageEvents == null || !retained.pageEvents.owns(port))
                  retained.pageEvents =
                      new com.deepseekharness.app.util.StartupPageGate(
                          port, startupGeneration, baseUrl);
                final var observation = retained.pageEvents;
                port.setDelegate(
                    new WebExtension.PortDelegate() {
                      @Override
                      public void onPortMessage(Object message, WebExtension.Port source) {
                        if (source != pagePort
                            || !pageCurrent(current, pageIdentity)
                            || source.sender.session != current
                            || !source.sender.isTopLevel()
                            || !WebPreviewPolicy.sameService(baseUrl, source.sender.url)
                            || !WebPreviewPolicy.sameService(baseUrl, retained.documentUrl)
                            || !(message instanceof org.json.JSONObject)) return;
                        org.json.JSONObject value = (org.json.JSONObject) message;
                        if ("language-selected".equals(value.optString("type"))) {
                          LanguageController.select(
                              GeckoPreviewActivity.this, value.optString("language"));
                          return;
                        }
                        if ("startup".equals(value.optString("type"))) {
                          org.json.JSONObject event = value.optJSONObject("report");
                          long currentGeneration =
                              com.deepseekharness.app.core.HarnessController.get(
                                      GeckoPreviewActivity.this)
                                  .getWebGeneration();
                          var controller =
                              com.deepseekharness.app.core.HarnessController.get(
                                  GeckoPreviewActivity.this);
                          if (event != null
                              && event.toString().length() <= 9500
                              && !isFinishing()
                              && !isDestroyed()
                              && source.sender.session == session
                              && source.sender.isTopLevel()
                              && !controller.isStopping()
                              && !controller.isUserStopped()
                              && observation.accept(
                                  source,
                                  currentGeneration,
                                  retained.documentUrl == null
                                      ? source.sender.url
                                      : retained.documentUrl,
                                  null,
                                  event.optString("nonce"),
                                  event.optString("page"),
                                  event.optString("documentId"),
                                  event.optLong("sequence", -1),
                                  true,
                                  event.optBoolean("fatal"),
                                  "ready".equals(event.optString("type")))
                              && com.deepseekharness.app.core.HarnessController.get(
                                      GeckoPreviewActivity.this)
                                  .startupDiagnostics()
                                  .pageEvent(startupGeneration, event)) {
                            com.deepseekharness.app.core.HarnessController.get(
                                    GeckoPreviewActivity.this)
                                .failedWebPage(startupGeneration, event.optString("message"));
                            startActivity(
                                new android.content.Intent(
                                    GeckoPreviewActivity.this, StartupRecoveryActivity.class));
                            finish();
                          }
                          return;
                        }
                        if (!backPending
                            || !"back".equals(value.optString("type"))
                            || value.optInt("id") != backSequence) return;
                        backPending = false;
                        if (!value.optBoolean("handled")) historyBack();
                      }

                      @Override
                      public void onDisconnect(WebExtension.Port source) {
                        if (pagePort == source) {
                          pagePort = null;
                          if (retained.port == source) retained.port = null;
                        }
                      }
                    });
                try {
                  port.postMessage(
                      new org.json.JSONObject()
                          .put("type", "language")
                          .put("observationNonce", observation.nonce())
                          .put(
                              "language",
                              new com.deepseekharness.app.core.ConfigStore(
                                      GeckoPreviewActivity.this)
                                  .getUiLanguage()));
                } catch (org.json.JSONException ignored) {
                }
                resumePageStreams();
              }
            },
            "dsha");
    if (retained.port != null)
      current
          .getWebExtensionController()
          .getMessageDelegate(extension, "dsha")
          .onConnect(retained.port);
  }

  private void external(String url) {
    if (url != null && PluginNavigation.open(this, url)) return;
    if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
    try {
      startActivity(
          new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
    } catch (RuntimeException error) {
      Toast.makeText(
              this, com.deepseekharness.app.util.UiText.text("没有可用的系统浏览器"), Toast.LENGTH_SHORT)
          .show();
    }
  }

  private void showError(String title, String detail) {
    cancelFilePrompt();
    if (microphoneDelegate != null) microphoneDelegate.cancelPending();
    if (retained != null) retained.documentUrl = null;
    if (retained != null) retained.ready = false;
    refreshPictureInPicture();
    com.deepseekharness.app.core.DiagnosticLog.record(
        this, "GECKO_PAGE", title + com.deepseekharness.app.util.UiText.text("：") + detail);
    if (isFinishing() || isDestroyed()) return;
    ((TextView) findViewById(R.id.web_error_title)).setText(title);
    ((TextView) findViewById(R.id.web_error_detail))
        .setText(com.deepseekharness.app.util.UiText.format("%s\n兼容内核 Gecko 143", detail));
    progress.setVisibility(View.GONE);
    errorPanel.setVisibility(View.VISIBLE);
  }

  private void closeSession() {
    if (microphoneDelegate != null) {
      microphoneDelegate.close();
      microphoneDelegate = null;
    }
    cancelFilePrompt();
    backPending = false;
    backSequence++;
    canGoBack = false;
    if (browser != null) {
      browser.releaseSession();
      container.removeView(browser);
      browser = null;
    }
    if (session != null) {
      session.setPermissionDelegate(null);
      session.close();
      session = null;
    }
    if (retained != null) {
      retained.session = null;
      retained.ready = false;
      retained.documentUrl = null;
      retained.authUrl = null;
      retained.history = null;
      retained.canGoBack = false;
      retained.needsStreamResume = false;
      retained.pageEvents = null;
      retained.navigation.cancel();
      retained.page.clear();
    }
    if (retained != null && retained.uploadSession != null) {
      retained.uploadSession.close();
      retained.uploadSession = null;
    }
    refreshPictureInPicture();
    pagePort = null;
    if (retained != null) retained.port = null;
  }

  @Override
  protected void onPause() {
    browserResumed = false;
    com.deepseekharness.app.core.PluginRepository.get(this).invalidateInstalledState();
    super.onPause();
  }

  @Override
  protected boolean pictureInPictureContentReady() {
    return session != null
        && retained != null
        && retained.ready
        && retained.page.isCurrent(controller.getReadyWebPageIdentity())
        && baseUrl != null
        && errorPanel != null
        && errorPanel.getVisibility() != View.VISIBLE;
  }

  @Override
  protected void onStop() {
    if (controller != null) controller.removeReadyWebPageListener(readyWebListener);
    if (microphoneDelegate != null) microphoneDelegate.cancelPending();
    if (!pictureInPictureActiveOrTransitioning() && session != null) {
      retained.needsStreamResume = true;
      session.setActive(false);
    }
    super.onStop();
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (controller != null) controller.addReadyWebPageListener(readyWebListener);
    if (!pictureInPictureActiveOrTransitioning() && session != null) session.setActive(true);
    syncSession(false);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    syncSession(false);
  }

  @Override
  protected void onResume() {
    super.onResume();
    browserResumed = true;
    syncSession(false);
    if (pagePort != null && retained.page.isCurrent(controller.getReadyWebPageIdentity()))
      try {
        pagePort.postMessage(
            new org.json.JSONObject()
                .put("type", "language")
                .put(
                    "language",
                    new com.deepseekharness.app.core.ConfigStore(this).getUiLanguage()));
      } catch (org.json.JSONException ignored) {
      }
    resumePageStreams();
  }

  private void resumePageStreams() {
    if (retained == null
        || !retained.needsStreamResume
        || !retained.ready
        || pagePort == null
        || session == null
        || retained.session != session
        || !browserResumed
        || !WebPreviewPolicy.sameService(baseUrl, retained.documentUrl)) return;
    if (!retained.page.isCurrent(controller.getReadyWebPageIdentity())) return;
    try {
      pagePort.postMessage(new org.json.JSONObject().put("type", "browser-resume"));
      retained.needsStreamResume = false;
    } catch (org.json.JSONException ignored) {
    }
  }

  @Override
  protected void onDestroy() {
    backPending = false;
    backSequence++;
    if (controller != null) controller.removeReadyWebPageListener(readyWebListener);
    if (picker != null) picker.unregister();
    picker = null;
    pickerOwner = null;
    if (microphoneDelegate != null) {
      microphoneDelegate.close();
      microphoneDelegate = null;
    }
    if (microphone != null) microphone.close();
    if (previewAuth != null) previewAuth.cancel();
    pendingIdentity = null;
    if (downloads != null) downloads.dismiss();
    if (isChangingConfigurations() && session != null) {
      if (browser != null) {
        browser.releaseSession();
        container.removeView(browser);
        browser = null;
      }
      session.setPermissionDelegate(null);
      session.setNavigationDelegate(null);
      session.setProgressDelegate(null);
      session.setPromptDelegate(null);
      session.setContentDelegate(null);
      // 保留消息端口，新的 Activity 在接管后更换代理。
      if (pagePort != null) pagePort.setDelegate(null);
      session = null;
    } else closeSession();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (downloads != null) downloads.model.saveState(out);
    PreviewPageSession.Identity identity = retained.page.identity();
    if (identity != null && retained.history != null) {
      out.putString("gecko-state", retained.history.toString());
      out.putLong("gecko-generation", identity.generation());
      out.putString("gecko-instance", identity.instanceId());
      out.putString("gecko-auth-url", identity.authUrl());
    }
    super.onSaveInstanceState(out);
  }
}
