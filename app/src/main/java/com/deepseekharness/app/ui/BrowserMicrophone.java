package com.deepseekharness.app.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.deepseekharness.app.util.MicrophonePolicy;
import com.deepseekharness.app.util.MicrophoneRequestGate;
import com.deepseekharness.app.util.UiText;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 标准版与 Gecko 共用的按需系统授权；不在启动时申请，也不提供后台录音授权。 */
public final class BrowserMicrophone implements AutoCloseable, DefaultLifecycleObserver {
  private final ComponentActivity activity;
  private final MicrophoneRequestGate gate = new MicrophoneRequestGate();
  private Pending pending;
  private boolean closed;

  private static final class Pending {
    final long ticket;
    final String origin, base;
    final BooleanSupplier current;
    Consumer<Boolean> result;
    ActivityResultLauncher<String> launcher;

    Pending(
        long ticket,
        String origin,
        String base,
        BooleanSupplier current,
        Consumer<Boolean> result) {
      this.ticket = ticket;
      this.origin = origin;
      this.base = base;
      this.current = current;
      this.result = result;
    }

    void finish(boolean allowed) {
      Consumer<Boolean> callback = result;
      result = null;
      if (callback != null)
        try {
          callback.accept(allowed);
        } catch (RuntimeException ignored) {
        }
    }
  }

  public BrowserMicrophone(ComponentActivity activity) {
    this.activity = activity;
    activity.getLifecycle().addObserver(this);
  }

  private boolean visible() {
    return !closed
        && !activity.isFinishing()
        && !activity.isDestroyed()
        && activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
  }

  private boolean current(Pending request) {
    try {
      return visible()
          && MicrophonePolicy.trustedOrigin(request.origin, request.base)
          && request.current.getAsBoolean();
    } catch (RuntimeException stale) {
      return false;
    }
  }

  public void request(
      String requestingOrigin,
      String baseUrl,
      BooleanSupplier stillCurrent,
      Consumer<Boolean> result) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      activity.runOnUiThread(() -> request(requestingOrigin, baseUrl, stillCurrent, result));
      return;
    }
    Pending request = new Pending(-1, requestingOrigin, baseUrl, stillCurrent, result);
    // 系统授权弹窗关闭时，Gecko 的媒体回调可能早于窗口重新获得焦点；
    // 以可见生命周期及本页身份核验，不能把这一正常时序误判为拒绝。
    if (!current(request)) {
      request.finish(false);
      return;
    }
    long ticket = gate.begin();
    if (ticket < 0) {
      request.finish(false);
      return;
    }
    request = new Pending(ticket, requestingOrigin, baseUrl, stillCurrent, result);
    pending = request;
    if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED) {
      resolve(request, true);
      return;
    }
    final Pending owned = request;
    try {
      // 每次系统请求使用独立注册身份，旧 Activity 或旧请求的结果不能交给新网页请求。
      owned.launcher =
          activity
              .getActivityResultRegistry()
              .register(
                  "dsha-microphone-" + UUID.randomUUID(),
                  new ActivityResultContracts.RequestPermission(),
                  allowed -> {
                    boolean showDenied =
                        owned.result != null && current(owned) && !Boolean.TRUE.equals(allowed);
                    resolve(owned, Boolean.TRUE.equals(allowed));
                    if (showDenied)
                      Toast.makeText(
                              activity,
                              UiText.choose(
                                  "麦克风未获授权，可在系统应用权限中允许后重试。",
                                  "Microphone access was denied. Allow it in system app permissions and retry."),
                              Toast.LENGTH_LONG)
                          .show();
                  });
      owned.launcher.launch(Manifest.permission.RECORD_AUDIO);
    } catch (RuntimeException unavailable) {
      resolve(owned, false);
    }
  }

  private void resolve(Pending request, boolean granted) {
    boolean allowed =
        gate.resolve(
            request.ticket,
            granted,
            pending == request
                && current(request)
                && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED);
    if (pending == request) pending = null;
    request.finish(allowed);
    if (request.launcher != null) {
      request.launcher.unregister();
      request.launcher = null;
    }
  }

  public void cancel() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      activity.runOnUiThread(this::cancel);
      return;
    }
    gate.cancel();
    if (pending != null) pending.finish(false);
  }

  @Override
  public void onStop(LifecycleOwner owner) {
    cancel();
  }

  @Override
  public void onDestroy(LifecycleOwner owner) {
    close();
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    cancel();
    if (pending != null && pending.launcher != null) {
      pending.launcher.unregister();
      pending.launcher = null;
    }
    activity.getLifecycle().removeObserver(this);
  }
}
