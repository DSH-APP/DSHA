package com.deepseekharness.app.assistant;

import com.deepseekharness.app.util.UiText;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.control.OwnedScreenOverlay;
import com.deepseekharness.app.control.ScreenControlConfirmation;
import com.deepseekharness.app.util.ScreenTarget;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Separate permission prompt; closing it precedes the broker's exact target revalidation. */
final class AssistantConfirmationWindow implements AutoCloseable {
  private final Context context;
  private final WindowManager manager;
  private final Handler main = new Handler(Looper.getMainLooper());
  private LinearLayout view;
  private Pending active;
  private long sequence;
  private volatile boolean closed;

  AssistantConfirmationWindow(Context context) {
    this.context = context;
    manager = context.getSystemService(WindowManager.class);
  }

  private final class Pending implements ScreenControlConfirmation.Prompt {
    final CapabilityRequest request;
    final BooleanSupplier current;
    final AtomicBoolean canceled = new AtomicBoolean();
    Consumer<ScreenControlConfirmation.Decision> answer;

    Pending(
        CapabilityRequest request,
        BooleanSupplier current,
        Consumer<ScreenControlConfirmation.Decision> answer) {
      this.request = request;
      this.current = current;
      this.answer = answer;
    }

    boolean valid() {
      try {
        return !closed
            && !canceled.get()
            && Settings.canDrawOverlays(context)
            && request.deadlineElapsedRealtimeMillis > SystemClock.elapsedRealtime()
            && current.getAsBoolean();
      } catch (RuntimeException unavailable) {
        return false;
      }
    }

    void answer(Boolean approved, int windowId) {
      answer(approved, windowId, false);
    }

    void answer(Boolean approved, int windowId, boolean rememberTurn) {
      var callback = answer;
      answer = null;
      if (callback != null)
        callback.accept(
            new ScreenControlConfirmation.Decision(
                approved, windowId, rememberTurn && Boolean.TRUE.equals(approved)));
    }

    @Override
    public void close() {
      canceled.set(true);
      main.post(
          () -> {
            if (active == this) finish(null);
            else answer(null, -1);
          });
    }
  }

  ScreenControlConfirmation.Prompt show(
      CapabilityRequest request,
      ScreenTarget target,
      BooleanSupplier current,
      Consumer<ScreenControlConfirmation.Decision> answer) {
    Pending pending = new Pending(request, current, answer);
    main.post(
        () -> {
          // A canceled queued request must not retire a newer visible prompt.
          if (!pending.valid()) {
            pending.answer(null, -1);
            return;
          }
          finish(null);
          if (!pending.valid()) {
            pending.answer(null, -1);
            return;
          }
          active = pending;
          long stamp = ++sequence;
          view = new LinearLayout(context);
          view.setOrientation(LinearLayout.VERTICAL);
          view.setPadding(22, 18, 22, 18);
          view.setBackgroundColor(0xFF192435);
          TextView text = new TextView(context);
          text.setTextColor(Color.WHITE);
          text.setMaxLines(8);
          text.setText(
              String.format(
                  UiText.choose("允许本次手机操作？\n%s\n%s", "Allow this phone action?\n%s\n%s"),
                  target == null ? "" : target.packageName,
                  com.deepseekharness.app.util.SensitiveData.redact(request.action)));
          view.addView(text);
          Button allow = new Button(context);
          allow.setText(UiText.choose("允许本次", "Allow once"));
          view.addView(allow);
          allow.setOnClickListener(
              v -> {
                if (active == pending && sequence == stamp) finish(true);
              });
          Button deny = new Button(context);
          deny.setText(UiText.choose("拒绝", "Deny"));
          view.addView(deny);
          deny.setOnClickListener(
              v -> {
                if (active == pending && sequence == stamp) finish(false);
              });
          boolean turnAllowed = target != null || "external.open".equals(request.capability);
          Button allowTurn = new Button(context);
          allowTurn.setVisibility(turnAllowed ? View.VISIBLE : View.GONE);
          allowTurn.setText(
              target != null
                      && com.deepseekharness.app.util.SensitiveAppPolicy.sensitive(
                          target.packageName)
                  ? UiText.choose(
                      "本轮允许此应用（敏感：每 5 次再确认）",
                      "Allow this app this turn (sensitive: re-ask every 5)")
                  : UiText.choose("本轮允许此应用", "Allow this app for this turn"));
          // 放在「拒绝」下方并隔开，避免和「允许本次」连按误触。
          LinearLayout.LayoutParams turnParams = new LinearLayout.LayoutParams(-1, -2);
          turnParams.topMargin = 18;
          view.addView(allowTurn, turnParams);
          allowTurn.setOnClickListener(
              v -> {
                if (active == pending && sequence == stamp) finish(true, true);
              });
          WindowManager.LayoutParams params =
              new WindowManager.LayoutParams(
                  -1,
                  -2,
                  Build.VERSION.SDK_INT >= 26
                      ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                      : WindowManager.LayoutParams.TYPE_PHONE,
                  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                      | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                  PixelFormat.TRANSLUCENT);
          params.gravity = Gravity.TOP;
          params.y = 90;
          view.addOnLayoutChangeListener(
              (v, l, t, r, b, ol, ot, or, ob) ->
                  AssistantWindow.publish(view, "assistant-confirm", false));
          try {
            manager.addView(view, params);
            view.post(() -> AssistantWindow.publish(view, "assistant-confirm", false));
          } catch (RuntimeException denied) {
            finish(null);
            return;
          }
          watch(pending, stamp, SystemClock.elapsedRealtime() + 60000);
        });
    return pending;
  }

  private void watch(Pending pending, long stamp, long deadline) {
    main.postDelayed(
        () -> {
          if (active != pending || sequence != stamp) return;
          if (!pending.valid() || SystemClock.elapsedRealtime() >= deadline) finish(null);
          else watch(pending, stamp, deadline);
        },
        Math.min(100, Math.max(1, deadline - SystemClock.elapsedRealtime())));
  }

  private int windowId() {
    android.view.accessibility.AccessibilityNodeInfo node = null;
    try {
      node = view == null ? null : view.createAccessibilityNodeInfo();
      return node == null ? -1 : node.getWindowId();
    } catch (RuntimeException unavailable) {
      return -1;
    } finally {
      if (node != null) node.recycle();
    }
  }

  private void finish(Boolean approved) {
    finish(approved, false);
  }

  private void finish(Boolean approved, boolean rememberTurn) {
    ++sequence;
    Pending pending = active;
    active = null;
    int promptWindow = windowId();
    Boolean allowed = pending != null && pending.valid() ? approved : null;
    OwnedScreenOverlay.clear("assistant-confirm");
    if (view != null)
      try {
        manager.removeViewImmediate(view);
      } catch (RuntimeException ignored) {
      }
    view = null;
    if (pending != null) pending.answer(allowed, promptWindow, rememberTurn);
  }

  @Override
  public void close() {
    closed = true;
    main.post(() -> finish(null));
  }
}
