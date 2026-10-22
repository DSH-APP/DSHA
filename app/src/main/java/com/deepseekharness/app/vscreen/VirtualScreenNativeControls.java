package com.deepseekharness.app.vscreen;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.screen.NativeScreenControlLease;
import com.deepseekharness.app.screen.ScreenReply;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** A resumed native page explicitly owns one semantic session, separate from assistant nodes. */
public final class VirtualScreenNativeControls implements AutoCloseable {
  interface Backend {
    boolean current();

    JSONObject run(
        VirtualScreenAccessibility session,
        NativeScreenControlLease lease,
        String operation,
        JSONObject args);
  }

  private final WeakReference<AppCompatActivity> activity;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final VirtualScreenAccessibility session = new VirtualScreenAccessibility();
  private final Object calls = new Object();
  private int inFlight;
  private final LifecycleEventObserver observer;
  private Backend backend;
  private NativeScreenControlLease lease;

  VirtualScreenNativeControls(AppCompatActivity owner) {
    VirtualScreenPreviews.assertMain();
    activity = new WeakReference<>(java.util.Objects.requireNonNull(owner));
    observer =
        (source, event) -> {
          if (event == Lifecycle.Event.ON_PAUSE
              || event == Lifecycle.Event.ON_STOP
              || event == Lifecycle.Event.ON_DESTROY) close();
        };
    if (!owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
        || owner.isFinishing()
        || owner.isDestroyed()) closed.set(true);
    else owner.getLifecycle().addObserver(observer);
  }

  void bind(Backend backend) {
    VirtualScreenPreviews.assertMain();
    if (this.backend != null) throw new IllegalStateException("NATIVE_CONTROL_ALREADY_BOUND");
    this.backend = java.util.Objects.requireNonNull(backend);
    lease = DshaAccessibilityService.nativeVirtualControlLease(this);
    if (!uiCurrent()) lease.close();
  }

  boolean uiCurrent() {
    AppCompatActivity owner = activity.get();
    return !closed.get() && owner != null && !owner.isFinishing() && !owner.isDestroyed();
  }

  boolean current() {
    return uiCurrent() && backend != null && backend.current() && lease != null && lease.current();
  }

  /** Opaque local subject for the screen backend; callers cannot substitute their own predicate. */
  public boolean nativeAuthorityCurrent() {
    return uiCurrent() && backend != null && backend.current();
  }

  JSONObject tree() {
    return run("tree", new JSONObject());
  }

  JSONObject node(String id, String action, String text) {
    try {
      if (id == null
          || !id.matches("[a-f0-9-]{36}")
          || !"click".equals(action) && !"set_text".equals(action)
          || text == null
          || text.length() > 16000) return ScreenReply.refused("INVALID_NATIVE_NODE_ACTION");
      return run(
          "node", new JSONObject().put("nodeId", id).put("action", action).put("text", text));
    } catch (org.json.JSONException invalid) {
      return ScreenReply.refused("INVALID_NATIVE_NODE_ACTION");
    }
  }

  private JSONObject run(String operation, JSONObject args) {
    synchronized (calls) {
      if (!uiCurrent() || backend == null || lease == null)
        return ScreenReply.refused("NATIVE_CONTROL_REVOKED");
      inFlight++;
    }
    try {
      return backend.run(session, lease, operation, args);
    } finally {
      boolean clear;
      synchronized (calls) {
        clear = --inFlight == 0 && closed.get();
      }
      if (clear) session.clear();
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    NativeScreenControlLease currentLease = lease;
    if (currentLease != null) currentLease.close();
    AppCompatActivity owner = activity.get();
    if (owner != null) {
      Runnable remove = () -> owner.getLifecycle().removeObserver(observer);
      if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) remove.run();
      else new android.os.Handler(android.os.Looper.getMainLooper()).post(remove);
    }
    boolean clear;
    synchronized (calls) {
      clear = inFlight == 0;
    }
    if (clear) session.clear();
  }
}
