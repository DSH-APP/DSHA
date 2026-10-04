package com.deepseekharness.app.ui;

import android.webkit.PermissionRequest;
import android.webkit.WebView;
import com.deepseekharness.app.util.MicrophonePolicy;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 每个 WebView 文档独立持有媒体请求，绝不把整组网页资源一起授权。 */
final class WebViewMicrophone {
  private final BrowserMicrophone microphone;
  private final WebView view;
  private final Supplier<String> base;
  private final BooleanSupplier ownerCurrent;
  private PermissionRequest pending;
  private long revision;

  WebViewMicrophone(
      BrowserMicrophone microphone, WebView view, Supplier<String> base, BooleanSupplier current) {
    this.microphone = microphone;
    this.view = view;
    this.base = base;
    this.ownerCurrent = current;
  }

  private static void deny(PermissionRequest request) {
    try {
      request.deny();
    } catch (RuntimeException alreadyCancelled) {
    }
  }

  private boolean current(long expected) {
    return revision == expected
        && ownerCurrent.getAsBoolean()
        && MicrophonePolicy.trustedOrigin(view.getUrl(), base.get());
  }

  void request(PermissionRequest request) {
    final long expected = revision;
    if (pending != null
        || request.getOrigin() == null
        || !MicrophonePolicy.audioOnly(request.getResources())
        || !current(expected)) {
      deny(request);
      return;
    }
    pending = request;
    microphone.request(
        request.getOrigin().toString(),
        base.get(),
        () -> pending == request && current(expected),
        allowed -> {
          boolean grant = allowed && pending == request && current(expected);
          if (pending == request) pending = null;
          if (grant)
            try {
              request.grant(new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } catch (RuntimeException cancelled) {
              deny(request);
            }
          else deny(request);
        });
  }

  void cancelled(PermissionRequest request) {
    if (pending == request) cancel();
  }

  void cancel() {
    revision++;
    microphone.cancel();
    pending = null;
  }
}
