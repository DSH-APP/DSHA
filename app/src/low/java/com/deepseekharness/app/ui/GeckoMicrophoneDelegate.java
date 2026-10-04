package com.deepseekharness.app.ui;

import android.Manifest;

import com.deepseekharness.app.util.MicrophonePolicy;

import org.mozilla.geckoview.GeckoSession;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Gecko 的系统权限与网页媒体权限共用一次性、绑定页面的麦克风检查。 */
final class GeckoMicrophoneDelegate implements GeckoSession.PermissionDelegate {
  private final BrowserMicrophone microphone;
  private final GeckoSession session;
  private final Supplier<String> baseUrl, documentUrl;
  private final BooleanSupplier current;
  private long revision;
  private boolean closed;

  GeckoMicrophoneDelegate(
      BrowserMicrophone microphone,
      GeckoSession session,
      Supplier<String> baseUrl,
      Supplier<String> documentUrl,
      BooleanSupplier current) {
    this.microphone = microphone;
    this.session = session;
    this.baseUrl = baseUrl;
    this.documentUrl = documentUrl;
    this.current = current;
  }

  private boolean valid(GeckoSession source, long expectedRevision) {
    return !closed
        && source == session
        && session.isOpen()
        && revision == expectedRevision
        && current.getAsBoolean()
        && MicrophonePolicy.trustedOrigin(documentUrl.get(), baseUrl.get());
  }

  @Override
  public void onAndroidPermissionsRequest(
      GeckoSession source, String[] permissions, Callback callback) {
    // 此回调不携带请求源：仅申请当前可信顶层页所需的录音权限，媒体源仍由下一阶段复核。
    long expected = revision;
    if (permissions == null
        || permissions.length != 1
        || !Manifest.permission.RECORD_AUDIO.equals(permissions[0])
        || !valid(source, expected)) {
      callback.reject();
      return;
    }
    microphone.request(
        documentUrl.get(),
        baseUrl.get(),
        () -> valid(source, expected),
        allowed -> {
          if (allowed && valid(source, expected)) callback.grant();
          else callback.reject();
        });
  }

  @Override
  public void onMediaPermissionRequest(
      GeckoSession source,
      String uri,
      MediaSource[] video,
      MediaSource[] audio,
      MediaCallback callback) {
    long expected = revision;
    if (!valid(source, expected) || video != null && video.length != 0 || audio == null) {
      callback.reject();
      return;
    }
    MediaSource microphoneSource = null;
    for (MediaSource candidate : audio) {
      if (candidate != null
          && candidate.type == MediaSource.TYPE_AUDIO
          && candidate.source == MediaSource.SOURCE_MICROPHONE
          && candidate.id != null
          && !candidate.id.isEmpty()) {
        microphoneSource = candidate;
        break;
      }
    }
    if (microphoneSource == null) {
      callback.reject();
      return;
    }
    final MediaSource selected = microphoneSource;
    microphone.request(
        uri,
        baseUrl.get(),
        () -> valid(source, expected),
        allowed -> {
          // 不授予摄像头、屏幕或桌面音频；选源属于本次回调，不复用上一请求的设备。
          if (allowed && valid(source, expected)) callback.grant((MediaSource) null, selected);
          else callback.reject();
        });
  }

  void cancelPending() {
    revision++;
    microphone.cancel();
  }

  void close() {
    closed = true;
    cancelPending();
  }
}
