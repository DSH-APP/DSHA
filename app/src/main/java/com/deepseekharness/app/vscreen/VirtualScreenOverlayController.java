package com.deepseekharness.app.vscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/**
 * 系统悬浮预览（T28 接回）：用户在虚拟识屏页开启后，离开 DSHA 也能看到虚拟屏画面。
 *
 * <ul>
 *   <li>开关意图持久化在本机偏好；虚拟屏停止只收起窗口，不清除意图，下次创建成功后自动恢复。
 *   <li>需要系统悬浮窗权限；未授权时只引导去系统设置，不显示窗口。
 *   <li>虚拟识屏页在前台时让位（suppress），避免同屏两份预览；显示期间 DSHA 页内小窗不出现。
 *   <li>只显示画面，不转发触摸：点按画面回到虚拟识屏页操作。
 * </ul>
 */
public final class VirtualScreenOverlayController {
  static final String KEY_ENABLED = "vscreen_float_preview";
  private static final int WIDTH_DP = 168;

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
  private boolean dragMoved, suppressed;

  private final VirtualScreenManager session;

  VirtualScreenOverlayController(VirtualScreenManager session) {
    this.session = java.util.Objects.requireNonNull(session);
  }

  private Context app() {
    Context context = session.applicationContext();
    return context == null ? null : context.getApplicationContext();
  }

  private android.content.SharedPreferences prefs(Context app) {
    return app.getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, 0);
  }

  /** 用户意图已开启且系统仍允许悬浮窗。 */
  public boolean enabled() {
    Context app = app();
    return app != null
        && prefs(app).getBoolean(KEY_ENABLED, false)
        && Settings.canDrawOverlays(app);
  }

  /** 正在显示系统悬浮窗（含让位隐藏期间）；页内小窗据此回避。 */
  boolean showing() {
    return panel != null;
  }

  /** 虚拟识屏页上的开关：开启时缺权限就去系统设置，授权回来后由页面恢复时补上。 */
  public void toggle(android.app.Activity from) {
    VirtualScreenPreviews.assertMain();
    Context app = app();
    if (app == null) return;
    if (enabled()) {
      prefs(app).edit().putBoolean(KEY_ENABLED, false).apply();
      hide();
      return;
    }
    prefs(app).edit().putBoolean(KEY_ENABLED, true).apply();
    if (!Settings.canDrawOverlays(app)) {
      Toast.makeText(
              from,
              UiText.choose(
                  "请允许 DSHA 显示在其他应用上层，返回后悬浮预览会自动出现。",
                  "Allow DSHA over other apps; the floating preview appears on return."),
              Toast.LENGTH_LONG)
          .show();
      try {
        from.startActivity(
            new android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + app.getPackageName())));
      } catch (RuntimeException unavailable) {
        prefs(app).edit().putBoolean(KEY_ENABLED, false).apply();
      }
      return;
    }
    restore();
  }

  /** 幂等：意图开启、权限仍在且虚拟屏正在运行时显示窗口。 */
  public void restore() {
    onMain(
        () -> {
          if (panel != null || !enabled() || session.generation().isEmpty()) return;
          attach();
        });
  }

  /** 虚拟识屏页前台时让位；离开后恢复显示（若仍开启）。 */
  public void suppress(boolean value) {
    onMain(
        () -> {
          suppressed = value;
          if (panel != null) panel.setVisibility(value ? View.GONE : View.VISIBLE);
          else if (!value) restore();
        });
  }

  private void attach() {
    VirtualScreenPreviews.assertMain();
    Context app = app();
    if (app == null) return;
    manager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
    panel = new LinearLayout(app);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setBackgroundResource(R.drawable.bg_shell_card);
    panel.setClipToOutline(true);
    panel.setElevation(dp(app, 8));
    panel.setVisibility(suppressed ? View.GONE : View.VISIBLE);

    LinearLayout bar = new LinearLayout(app);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    com.deepseekharness.app.ui.DragHandleView handle =
        new com.deepseekharness.app.ui.DragHandleView(app);
    handle.setText(UiText.choose("虚拟屏", "Virtual screen"));
    handle.setTextSize(12);
    handle.setTextColor(app.getColorStateList(R.color.shell_text));
    handle.setGravity(Gravity.CENTER_VERTICAL);
    handle.setContentDescription(
        UiText.choose("虚拟屏悬浮预览，拖动移动，点按打开", "Floating preview, drag to move, tap to open"));
    handle.setPadding(dp(app, 12), 0, 0, 0);
    bar.addView(handle, new LinearLayout.LayoutParams(0, -2, 1));
    ImageButton close = new ImageButton(app);
    close.setImageResource(R.drawable.ic_terminal_close);
    close.setImageTintList(app.getColorStateList(R.color.shell_text_secondary));
    close.setBackground(null);
    close.setPadding(dp(app, 12), dp(app, 12), dp(app, 12), dp(app, 12));
    close.setContentDescription(UiText.choose("关闭悬浮预览", "Close floating preview"));
    close.setOnClickListener(
        v -> {
          prefs(app).edit().putBoolean(KEY_ENABLED, false).apply();
          hide();
        });
    bar.addView(close, new LinearLayout.LayoutParams(dp(app, 44), dp(app, 44)));
    panel.addView(bar);

    image = new ImageView(app);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setContentDescription(UiText.choose("打开虚拟识屏页", "Open virtual screen"));
    image.setOnClickListener(v -> open(app));
    panel.addView(image, new LinearLayout.LayoutParams(-1, dp(app, WIDTH_DP * 16 / 9)));

    params =
        new WindowManager.LayoutParams(
            dp(app, WIDTH_DP),
            -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.START;
    params.x = dp(app, 12);
    params.y = dp(app, 96);
    handle.setOnTouchListener(
        (v, e) -> {
          if (params == null) return true;
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
                    >= ViewConfiguration.get(app).getScaledTouchSlop();
            params.x = Math.max(0, startX + (int) (e.getRawX() - downX));
            params.y = Math.max(0, startY + (int) (e.getRawY() - downY));
            update();
            return true;
          }
          if (e.getAction() == MotionEvent.ACTION_UP && !dragMoved) open(app);
          return true;
        });
    try {
      manager.addView(panel, params);
    } catch (RuntimeException e) {
      hide();
      Toast.makeText(
              app, UiText.choose("无法显示悬浮预览", "Unable to show floating preview"), Toast.LENGTH_SHORT)
          .show();
      return;
    }
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
                    fitAspect(app, frame.value.optInt("width"), frame.value.optInt("height"));
                    Bitmap old = shown;
                    shown = frame.bitmap;
                    image.setImageBitmap(shown);
                    if (old != null) old.recycle();
                    frame.adopted();
                  }
                  String error = frame.value.optString("error");
                  if ("VSCREEN_NOT_RUNNING".equals(error) || "VSCREEN_REVOKED".equals(error))
                    hide();
                });
    // 系统悬浮窗出现后，当前 DSHA 页里的小窗让位。
    session.foreground().refresh();
  }

  /** 缩略图高度随虚拟屏宽高比，横屏不再留大块空白。 */
  private void fitAspect(Context app, int width, int height) {
    if (image == null || width <= 0 || height <= 0) return;
    int target =
        Math.max(
            dp(app, 96),
            Math.min(dp(app, 320), Math.round(dp(app, WIDTH_DP) * (float) height / width)));
    ViewGroup.LayoutParams lp = image.getLayoutParams();
    if (lp.height == target) return;
    lp.height = target;
    image.setLayoutParams(lp);
    update();
  }

  private void update() {
    if (manager == null || panel == null || params == null) return;
    try {
      manager.updateViewLayout(panel, params);
    } catch (RuntimeException ignored) {
    }
  }

  private static void open(Context app) {
    try {
      app.startActivity(
          new android.content.Intent(app, VirtualScreenActivity.class)
              .addFlags(
                  android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                      | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    } catch (RuntimeException ignored) {
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

  /** 收起窗口；不改变用户的开启意图（关闭按钮与页面开关另行清除）。 */
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
    boolean had = panel != null;
    revision++;
    if (previewLease != null) {
      previewLease.close();
      previewLease = null;
    }
    if (image != null) image.setImageDrawable(null);
    if (panel != null && manager != null)
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
    if (had) session.foreground().refresh();
  }

  private static int dp(Context c, int v) {
    return Math.round(v * c.getResources().getDisplayMetrics().density);
  }
}
