package com.deepseekharness.app.vscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** 独立可拖动悬浮预览；复用相同帧解码器，不占用文本悬浮条。 */
public final class VirtualScreenOverlayController {
  private final Handler MAIN = new Handler(Looper.getMainLooper());
  private WindowManager manager;
  private LinearLayout panel;
  private ImageView image;
  private WindowManager.LayoutParams params;
  private Bitmap shown;
  private VirtualScreenPreviews.Lease previewLease;
  private volatile long revision;
  private float downX, downY;
  private int startX, startY;
  private boolean dragMoved;

  private final VirtualScreenManager session;

  VirtualScreenOverlayController(VirtualScreenManager session) {
    this.session = java.util.Objects.requireNonNull(session);
  }

  public void show() {
    Context context = session.applicationContext();
    if (Looper.myLooper() != Looper.getMainLooper()) {
      Context app = context.getApplicationContext();
      long fence = session.epoch(), window = revision;
      MAIN.post(
          () -> {
            if (fence == session.epoch() && window == revision) show();
          });
      return;
    }
    VirtualScreenPreviews.assertMain();
    if (android.os.Build.VERSION.SDK_INT < 30) return;
    if (!Settings.canDrawOverlays(context)) {
      Toast.makeText(
              context,
              com.deepseekharness.app.util.UiText.choose(
                  "允许悬浮窗后，可再次点悬浮预览。", "Allow overlays, then tap Float preview again."),
              Toast.LENGTH_LONG)
          .show();
      context.startActivity(
          new android.content.Intent(
                  Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                  android.net.Uri.parse("package:" + context.getPackageName()))
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
      return;
    }
    if (panel != null) {
      hide();
      return;
    }
    Context app = context.getApplicationContext();
    manager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
    panel = new LinearLayout(app);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setBackgroundColor(0xee101218);
    com.deepseekharness.app.ui.DragHandleView handle =
        new com.deepseekharness.app.ui.DragHandleView(app);
    handle.setText(
        com.deepseekharness.app.util.UiText.choose(
            "虚拟屏 · 拖动此处 · 点此隐藏", "Virtual screen · drag / tap to hide"));
    handle.setTextColor(0xffffffff);
    handle.setTextSize(12);
    handle.setPadding(dp(app, 8), dp(app, 8), dp(app, 8), dp(app, 8));
    handle.setOnClickListener(v -> hide());
    panel.addView(handle);
    image = new ImageView(app);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    panel.addView(image, new LinearLayout.LayoutParams(-1, dp(app, 300)));
    params =
        new WindowManager.LayoutParams(
            dp(app, 200),
            -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.LEFT;
    params.x = dp(app, 8);
    params.y = dp(app, 80);
    handle.setOnTouchListener(
        (v, e) -> {
          if (e.getAction() == MotionEvent.ACTION_DOWN) {
            downX = e.getRawX();
            downY = e.getRawY();
            startX = params.x;
            startY = params.y;
            dragMoved = false;
            return true;
          }
          if (e.getAction() == MotionEvent.ACTION_MOVE) {
            dragMoved |=
                Math.hypot(e.getRawX() - downX, e.getRawY() - downY)
                    >= android.view.ViewConfiguration.get(app).getScaledTouchSlop();
            params.x = Math.max(0, startX + (int) (e.getRawX() - downX));
            params.y = Math.max(0, startY + (int) (e.getRawY() - downY));
            try {
              manager.updateViewLayout(panel, params);
            } catch (RuntimeException ignored) {
            }
            return true;
          }
          if (e.getAction() == MotionEvent.ACTION_UP && !dragMoved) {
            v.performClick();
          }
          return true;
        });
    try {
      manager.addView(panel, params);
      long window = ++revision;
      previewLease =
          session
              .previews()
              .subscribe(
                  this,
                  frame -> {
                    if (panel == null || revision != window) {
                      frame.release();
                      return;
                    }
                    if (frame.bitmap != null) {
                      Bitmap old = shown;
                      shown = frame.bitmap;
                      image.setImageBitmap(shown);
                      if (old != null) old.recycle();
                    }
                    if ("VSCREEN_NOT_RUNNING".equals(frame.value.optString("error"))
                        || "VSCREEN_REVOKED".equals(frame.value.optString("error"))) hide();
                  });
    } catch (RuntimeException e) {
      hide();
      Toast.makeText(
              context,
              com.deepseekharness.app.util.UiText.choose("无法显示悬浮预览", "Unable to show overlay"),
              Toast.LENGTH_SHORT)
          .show();
    }
  }

  public void hideStopped(long fence) {
    onMain(
        () -> {
          if (fence == session.epoch()) hide();
        });
  }

  private void onMain(Runnable action) {
    if (Looper.myLooper() == Looper.getMainLooper()) action.run();
    else MAIN.post(action);
  }

  public void hide() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      long current = revision;
      MAIN.post(
          () -> {
            if (current == revision) hide();
          });
      return;
    }
    VirtualScreenPreviews.assertMain();
    revision++;
    if (previewLease != null) {
      previewLease.close();
      previewLease = null;
    }
    if (image != null) image.setImageDrawable(null);
    if (panel != null)
      try {
        manager.removeView(panel);
      } catch (RuntimeException ignored) {
      }
    panel = null;
    image = null;
    manager = null;
    params = null;
    if (shown != null) {
      shown.recycle();
      shown = null;
    }
  }

  private static int dp(Context c, int v) {
    return Math.round(v * c.getResources().getDisplayMetrics().density);
  }
}
