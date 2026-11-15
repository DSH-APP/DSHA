package com.deepseekharness.app.vscreen;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.VirtualScreenPreviewPolicy;
import java.lang.ref.WeakReference;

/**
 * DSHA 前台内部的虚拟屏预览。
 *
 * <p>默认是宿主页顶部的蓝色提示条（只显示正在操作的目标，不转发触摸）；「完整预览」档位保留
 * 旧的页内小窗。两者都不需要系统悬浮窗权限，也不抢走当前对话；系统悬浮预览显示时让位。
 * 档位与收起意图由 {@link VirtualScreenPreviewSettings} 持久化，不按显示代次记忆。
 */
public final class VirtualScreenForeground {
  private final Handler MAIN = new Handler(Looper.getMainLooper());
  private WeakReference<Activity> host = new WeakReference<>(null);
  private LinearLayout panel;
  private VirtualScreenStrip strip;
  private ViewGroup parent;
  private ImageView image;
  private Bitmap bitmap;
  private long epoch, screenEpoch = -1;
  private VirtualScreenPreviewPolicy.Mode shownMode;
  private VirtualScreenPreviews.Lease previewLease;

  private final VirtualScreenManager session;

  VirtualScreenForeground(VirtualScreenManager session) {
    this.session = java.util.Objects.requireNonNull(session);
  }

  public void resume(Activity activity) {
    onMain(
        () -> {
          if (host.get() != activity) detach();
          host = new WeakReference<>(activity);
          activity
              .getWindow()
              .getDecorView()
              .post(
                  () -> {
                    if (host.get() == activity) sync();
                  });
        });
  }

  public void pause(Activity activity) {
    onMain(
        () -> {
          if (host.get() == activity) {
            detach();
            host.clear();
          }
        });
  }

  public void present() {
    long fence = session.epoch();
    onMain(
        () -> {
          if (fence != session.epoch()) return;
          sync();
        });
  }

  /** 系统悬浮预览显示/收起、设置里改档位或收起后重新判断：两种预览不同时出现。 */
  public void refresh() {
    onMain(this::sync);
  }

  public void stopped(long fence) {
    onMain(
        () -> {
          if (fence != session.epoch()) return;
          detach();
        });
  }

  private void onMain(Runnable action) {
    if (Looper.myLooper() == Looper.getMainLooper()) action.run();
    else MAIN.post(action);
  }

  private void sync() {
    VirtualScreenPreviews.assertMain();
    if ((panel != null || strip != null) && screenEpoch != session.epoch()) detach();
    Activity activity = host.get();
    String generation = session.generation();
    if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
      detach();
      return;
    }
    VirtualScreenPreviewPolicy.Mode mode = VirtualScreenPreviewSettings.mode(activity);
    if (!VirtualScreenPreviewPolicy.visible(
        mode,
        VirtualScreenPreviewSettings.collapsed(activity),
        !generation.isEmpty(),
        true,
        activity instanceof VirtualScreenActivity,
        session.overlay().showing())) {
      detach();
      return;
    }
    if (shownMode == mode && (panel != null || strip != null)) return;
    detach();
    if (!(activity.findViewById(android.R.id.content) instanceof FrameLayout)) return;
    parent = activity.findViewById(android.R.id.content);
    screenEpoch = session.epoch();
    if (VirtualScreenPreviewPolicy.full(mode)) attachFull(activity);
    else attachStrip(activity);
    shownMode = mode;
  }

  /** 顶部提示条：3dp 呼吸线 + 目标文字，热区 40dp；只显示，不向虚拟屏转发触摸。 */
  private void attachStrip(Activity activity) {
    strip = new VirtualScreenStrip(activity, session);
    parent.addView(strip.view(), new FrameLayout.LayoutParams(-1, dp(activity, 40), Gravity.TOP));
    strip.start();
  }

  /** 旧的页内完整预览小窗；拖动标题移动，点画面打开控制页。 */
  private void attachFull(Activity activity) {
    screenEpoch = session.epoch();
    panel = new LinearLayout(activity);
    panel.setId(R.id.vscreen_preview_panel);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setBackgroundResource(R.drawable.bg_card);
    panel.setElevation(dp(activity, 12));
    LinearLayout bar = new LinearLayout(activity);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    com.deepseekharness.app.ui.DragHandleView title =
        new com.deepseekharness.app.ui.DragHandleView(activity);
    title.setText(UiText.choose("虚拟屏 · 拖动或点按打开", "Virtual screen · drag or tap to open"));
    title.setOnClickListener(
        v ->
            activity.startActivity(
                new android.content.Intent(activity, VirtualScreenActivity.class)));
    title.setTextSize(12);
    title.setTextColor(activity.getColor(R.color.text));
    title.setPadding(dp(activity, 8), dp(activity, 8), 0, dp(activity, 8));
    bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    TextView hide = new TextView(activity);
    hide.setText("−");
    hide.setTextSize(22);
    hide.setTextColor(activity.getColor(R.color.text));
    hide.setGravity(Gravity.CENTER);
    hide.setContentDescription(UiText.choose("收起预览", "Hide preview"));
    bar.addView(hide, new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)));
    hide.setOnClickListener(
        v -> {
          // 收起意图持久化：换代次或重启后不会自己回来，只能从设置重新选择档位。
          if (activity.isFinishing() || activity.isDestroyed()) return;
          VirtualScreenPreviewSettings.collapse(activity);
          detach();
        });
    panel.addView(bar);
    image = new ImageView(activity);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setBackgroundColor(0xff101218);
    image.setContentDescription(UiText.choose("打开虚拟屏控制", "Open virtual screen controls"));
    panel.addView(image, new LinearLayout.LayoutParams(-1, dp(activity, 260)));
    image.setOnClickListener(
        v ->
            activity.startActivity(
                new android.content.Intent(activity, VirtualScreenActivity.class)));
    FrameLayout.LayoutParams layout =
        new FrameLayout.LayoutParams(dp(activity, 172), -2, Gravity.TOP | Gravity.END);
    layout.topMargin = dp(activity, 64);
    layout.rightMargin = dp(activity, 10);
    parent.addView(panel, layout);
    title.setOnTouchListener(
        new android.view.View.OnTouchListener() {
          float x, y, startX, startY;
          boolean moved;

          public boolean onTouch(android.view.View v, MotionEvent event) {
            if (panel == null) return true;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
              x = event.getRawX();
              y = event.getRawY();
              startX = panel.getTranslationX();
              startY = panel.getTranslationY();
              moved = false;
              return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
              moved |=
                  Math.hypot(event.getRawX() - x, event.getRawY() - y)
                      >= android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
              panel.setTranslationX(
                  Math.max(
                      -panel.getLeft(),
                      Math.min(
                          parent.getWidth() - panel.getRight(), startX + event.getRawX() - x)));
              panel.setTranslationY(
                  Math.max(
                      -panel.getTop(),
                      Math.min(
                          parent.getHeight() - panel.getBottom(), startY + event.getRawY() - y)));
              return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP && !moved) v.performClick();
            return true;
          }
        });
    long current = ++epoch;
    previewLease =
        session
            .previews()
            .subscribe(
                this,
                frame -> {
                  if (panel == null || epoch != current || host.get() != activity) {
                    frame.release();
                    return;
                  }
                  if (session.generation().isEmpty()) {
                    frame.release();
                    detach();
                    return;
                  }
                  if (frame.bitmap != null) {
                    Bitmap old = bitmap;
                    bitmap = frame.bitmap;
                    image.setImageBitmap(bitmap);
                    if (old != null) old.recycle();
                    frame.adopted();
                  }
                });
  }

  private void detach() {
    VirtualScreenPreviews.assertMain();
    epoch++;
    shownMode = null;
    if (strip != null) {
      strip.dismiss();
      strip = null;
    }
    if (previewLease != null) {
      previewLease.close();
      previewLease = null;
    }
    if (image != null) image.setImageDrawable(null);
    if (panel != null && parent != null) parent.removeView(panel);
    panel = null;
    parent = null;
    image = null;
    screenEpoch = -1;
    if (bitmap != null) {
      bitmap.recycle();
      bitmap = null;
    }
  }

  private static int dp(android.content.Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
