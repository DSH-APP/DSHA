package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.RecoveryLocalePolicy;
import org.json.JSONObject;
import org.mozilla.geckoview.*;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/** Gecko emergency page with a localhost-only locale extension and isolated session. */
final class RecoveryGeckoSurface implements RecoveryBrowserSurface {
  private static final String EXTENSION_ID = "dsha-recovery-locale@dsh.client";
  private static final String NATIVE_PORT = "dsha_recovery_locale";
  private static final String EXTENSION_ASSETS =
      "resource://android/assets/recovery-web-integration/";
  private final Context app;
  private final Handler main = new Handler(Looper.getMainLooper());
  private GeckoSession session;
  private GeckoView view;
  private Consumer<String> error;
  private WeakReference<RecoveryWebActivity> owner = new WeakReference<>(null);
  private String origin = "";
  private String documentUrl = "";
  private String pendingAuthUrl = "";
  private String language = "zh";
  private GeckoMicrophoneDelegate microphoneDelegate;
  private WebExtension extension;
  private WebExtension.Port pagePort;
  private boolean canBack, loaded, loadingExtension;

  RecoveryGeckoSurface(Context context) {
    app = context.getApplicationContext();
  }

  @Override
  public View attach(Activity activity, Consumer<String> failed) {
    if (!(activity instanceof RecoveryWebActivity))
      throw new IllegalArgumentException("RECOVERY_ACTIVITY_REQUIRED");
    RecoveryWebActivity currentOwner = (RecoveryWebActivity) activity;
    owner = new WeakReference<>(currentOwner);
    error = failed;
    if (microphoneDelegate != null) microphoneDelegate.close();
    view = new GeckoView(activity);
    boolean fresh = session == null;
    if (fresh) createSession();
    GeckoSession current = session;
    GeckoView attachedView = view;
    microphoneDelegate =
        new GeckoMicrophoneDelegate(
            currentOwner.microphone(),
            current,
            () -> origin,
            () -> documentUrl,
            () ->
                session == current
                    && view == attachedView
                    && !currentOwner.isFinishing()
                    && !currentOwner.isDestroyed()
                    && currentOwner.microphoneSessionCurrent());
    current.setPermissionDelegate(microphoneDelegate);
    if (fresh) current.open(GeckoRuntime.getDefault(app));
    attachedView.setSession(current);
    if (extension != null) attachLocaleBridge(current, extension);
    return attachedView;
  }

  private void createSession() {
    session =
        new GeckoSession(
            new GeckoSessionSettings.Builder()
                .contextId("dsha-emergency-" + java.util.UUID.randomUUID())
                .build());
    session.setNavigationDelegate(
        new GeckoSession.NavigationDelegate() {
          @Override
          public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession source, LoadRequest request) {
            return GeckoResult.fromValue(
                source == session && RecoveryLocalePolicy.sameOrigin(origin, request.uri)
                    ? AllowOrDeny.ALLOW
                    : AllowOrDeny.DENY);
          }

          @Override
          public void onLocationChange(
              GeckoSession source,
              String url,
              java.util.List<GeckoSession.PermissionDelegate.ContentPermission> permissions,
              Boolean hasUserGesture) {
            if (source != session) return;
            if (!java.util.Objects.equals(documentUrl, url) && microphoneDelegate != null)
              microphoneDelegate.cancelPending();
            documentUrl = url;
            syncLanguage(language);
          }

          @Override
          public void onCanGoBack(GeckoSession source, boolean back) {
            if (source == session) canBack = back;
          }

          @Override
          public GeckoResult<String> onLoadError(
              GeckoSession source, String uri, WebRequestError failure) {
            if (source == session) {
              cancelMicrophone();
              documentUrl = "";
              fail("RECOVERY_GECKO_" + failure.code);
            }
            return null;
          }
        });
    session.setProgressDelegate(
        new GeckoSession.ProgressDelegate() {
          @Override
          public void onPageStart(GeckoSession source, String url) {
            if (source != session) return;
            cancelMicrophone();
            documentUrl = url;
            syncLanguage(language);
          }
        });
    session.setContentDelegate(
        new GeckoSession.ContentDelegate() {
          @Override
          public void onCrash(GeckoSession source) {
            lost(source);
          }

          @Override
          public void onKill(GeckoSession source) {
            lost(source);
          }
        });
  }

  private void attachLocaleBridge(GeckoSession current, WebExtension currentExtension) {
    if (current != session || currentExtension == null) return;
    current
        .getWebExtensionController()
        .setMessageDelegate(
            currentExtension,
            new WebExtension.MessageDelegate() {
              @Override
              public void onConnect(WebExtension.Port port) {
                if (port.sender.session != session
                    || !port.sender.isTopLevel()
                    || !RecoveryLocalePolicy.sameOrigin(origin, port.sender.url)) {
                  port.disconnect();
                  return;
                }
                pagePort = port;
                port.setDelegate(
                    new WebExtension.PortDelegate() {
                      @Override
                      public void onPortMessage(Object message, WebExtension.Port source) {
                        if (source != pagePort
                            || source.sender.session != session
                            || !RecoveryLocalePolicy.sameOrigin(origin, source.sender.url)
                            || !(message instanceof JSONObject)
                            || ((JSONObject) message).toString().length() > 256) return;
                        JSONObject value = (JSONObject) message;
                        if (!"language-selected".equals(value.optString("type"))) return;
                        String selected = value.optString("language");
                        RecoveryWebActivity activity = owner.get();
                        if (RecoveryLocalePolicy.supportedLanguage(selected)
                            && activity != null
                            && !activity.isFinishing()
                            && !activity.isDestroyed()
                            && activity.microphoneSessionCurrent())
                          LanguageController.select(activity, selected);
                      }

                      @Override
                      public void onDisconnect(WebExtension.Port source) {
                        if (pagePort == source) pagePort = null;
                      }
                    });
                sendLanguage();
              }
            },
            NATIVE_PORT);
    if (pagePort != null) {
      try {
        current
            .getWebExtensionController()
            .getMessageDelegate(currentExtension, NATIVE_PORT)
            .onConnect(pagePort);
      } catch (RuntimeException disconnected) {
        pagePort = null;
      }
    }
  }

  @Override
  public void load(String auth, String base, String cookie) {
    if (loaded || loadingExtension) return;
    var launch = RecoveryLocalePolicy.launch(base, auth);
    if (launch == null) {
      fail("RECOVERY_ORIGIN_INVALID");
      return;
    }
    origin = launch.origin;
    String localAuth = launch.authUrl;
    if (session == null) createSession();
    pendingAuthUrl = localAuth;
    language = new ConfigStore(app).getUiLanguage();
    loadingExtension = true;
    GeckoSession current = session;
    GeckoRuntime runtime = GeckoRuntime.getDefault(app);
    runtime
        .getWebExtensionController()
        .ensureBuiltIn(EXTENSION_ASSETS, EXTENSION_ID)
        .accept(
            builtIn ->
                main.post(
                    () -> {
                      if (session != current) return;
                      extension = builtIn;
                      loadingExtension = false;
                      attachLocaleBridge(current, builtIn);
                      startPage(current);
                    }),
            failure ->
                main.post(
                    () -> {
                      if (session != current) return;
                      loadingExtension = false;
                      fail("RECOVERY_LOCALE_BRIDGE_UNAVAILABLE");
                      startPage(current);
                    }));
  }

  private void startPage(GeckoSession current) {
    if (session != current || loaded || pendingAuthUrl.isEmpty()) return;
    loaded = true;
    current.loadUri(pendingAuthUrl);
  }

  @Override
  public void syncLanguage(String value) {
    if (!RecoveryLocalePolicy.supportedLanguage(value)) return;
    language = value;
    sendLanguage();
  }

  private void sendLanguage() {
    WebExtension.Port current = pagePort;
    if (current == null
        || !RecoveryLocalePolicy.supportedLanguage(language)
        || !RecoveryLocalePolicy.sameOrigin(origin, current.sender.url)
        || current.sender.session != session) return;
    try {
      current.postMessage(new JSONObject().put("type", "language").put("language", language));
    } catch (Exception disconnected) {
      if (pagePort == current) pagePort = null;
    }
  }

  private void cancelMicrophone() {
    if (microphoneDelegate != null) microphoneDelegate.cancelPending();
  }

  private void fail(String value) {
    if (error != null) error.accept(value);
  }

  private void lost(GeckoSession failed) {
    if (failed != session) return;
    if (microphoneDelegate != null) {
      microphoneDelegate.close();
      microphoneDelegate = null;
    }
    failed.setPermissionDelegate(null);
    session = null;
    loaded = false;
    loadingExtension = false;
    canBack = false;
    documentUrl = "";
    pagePort = null;
    if (view != null) view.releaseSession();
    fail("RECOVERY_RENDERER_GONE");
    if (failed.isOpen()) failed.close();
  }

  @Override
  public boolean back() {
    if (canBack && session != null) {
      session.goBack();
      return true;
    }
    return false;
  }

  @Override
  public void detach() {
    if (microphoneDelegate != null) {
      microphoneDelegate.close();
      microphoneDelegate = null;
    }
    if (session != null) session.setPermissionDelegate(null);
    if (pagePort != null) pagePort.setDelegate(null);
    error = null;
    owner.clear();
    if (view != null) {
      view.releaseSession();
      if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }
    view = null;
  }

  @Override
  public void close() {
    detach();
    if (session != null) {
      session.setPermissionDelegate(null);
      if (session.isOpen()) session.close();
    }
    session = null;
    pagePort = null;
    extension = null;
    loaded = false;
    loadingExtension = false;
    documentUrl = "";
    origin = "";
    pendingAuthUrl = "";
    canBack = false;
  }
}
