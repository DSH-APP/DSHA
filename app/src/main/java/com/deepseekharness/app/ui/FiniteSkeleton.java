package com.deepseekharness.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import com.deepseekharness.app.util.SkeletonTiming;

/**
 * 有限骨架（E4）：首次加载时用 1–2 秒为上限的骨架行占位，数据到达立即停。
 *
 * <p>骨架只表达“内容还没到”，不伪造条目内容；到上限（默认 1.5 秒）必须停：呼吸动画结束、
 * 骨架自己隐藏，由页面切换到静态「仍在读取」或真实空状态，不存在无限 shimmer。
 * 系统关闭动画时骨架保持静态色块，仍然按时收尾。
 */
final class FiniteSkeleton {
  interface Listener {
    /** 到达上限仍未拿到数据；调用方应改为静态提示。 */
    void onTimeout();
  }

  private final LinearLayout host;
  private final long durationMs;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable timeoutTask = this::timeout;
  private ValueAnimator pulse;
  private Listener listener;
  private boolean running;
  private boolean timedOut;

  private FiniteSkeleton(LinearLayout host, int rows, long durationMs) {
    this.host = host;
    this.durationMs = SkeletonTiming.clampDuration(durationMs);
    host.setOrientation(LinearLayout.VERTICAL);
    host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    host.setVisibility(View.GONE);
    for (int index = 0; index < Math.max(1, rows); index++) host.addView(row(host.getContext()));
  }

  static FiniteSkeleton attach(LinearLayout host) {
    return attach(host, 3, SkeletonTiming.DEFAULT_MS);
  }

  static FiniteSkeleton attach(LinearLayout host, int rows, long durationMs) {
    return new FiniteSkeleton(host, rows, durationMs);
  }

  void setListener(Listener value) {
    listener = value;
    if (timedOut) notifyTimeout();
  }

  long durationMs() {
    return durationMs;
  }

  boolean visible() {
    return running;
  }

  boolean timedOut() {
    return timedOut;
  }

  void start() {
    if (running) return;
    running = true;
    timedOut = false;
    host.setVisibility(View.VISIBLE);
    host.setAlpha(1f);
    if (UiMotion.animationsEnabled(host.getContext())) {
      pulse = ValueAnimator.ofFloat(0.55f, 0.95f);
      pulse.setDuration(900L);
      pulse.setRepeatMode(ValueAnimator.REVERSE);
      pulse.setRepeatCount(ValueAnimator.INFINITE);
      pulse.addUpdateListener(running -> host.setAlpha((Float) running.getAnimatedValue()));
      pulse.start();
    } else {
      host.setAlpha(0.7f);
    }
    main.removeCallbacks(timeoutTask);
    main.postDelayed(timeoutTask, durationMs);
  }

  /** 数据到达：立即停止并隐藏，不播放收尾动画（避免真实内容被盖住）。 */
  void stop() {
    if (!running) return;
    running = false;
    main.removeCallbacks(timeoutTask);
    stopPulse();
    host.setVisibility(View.GONE);
    host.setAlpha(1f);
  }

  private void timeout() {
    if (!running) return;
    running = false;
    timedOut = true;
    stopPulse();
    host.setVisibility(View.GONE);
    host.setAlpha(1f);
    notifyTimeout();
  }

  private void notifyTimeout() {
    Listener current = listener;
    if (current != null) current.onTimeout();
  }

  private void stopPulse() {
    if (pulse != null) {
      pulse.cancel();
      pulse = null;
    }
  }

  private View row(Context context) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    int pad = dp(context, 12);
    row.setPadding(pad / 2, pad, pad / 2, pad);
    row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    View icon = block(context, 30, 30, 15);
    row.addView(icon);
    LinearLayout words = new LinearLayout(context);
    words.setOrientation(LinearLayout.VERTICAL);
    words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1f);
    wordsParams.leftMargin = dp(context, 12);
    row.addView(words, wordsParams);
    words.addView(block(context, -1, 11, 5), new LinearLayout.LayoutParams(-1, dp(context, 11)));
    LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-2, dp(context, 9));
    second.topMargin = dp(context, 7);
    View shortBar = block(context, dp(context, 96), 9, 4);
    words.addView(shortBar, second);
    return row;
  }

  private View block(Context context, int width, int heightDp, int radiusDp) {
    View view = new View(context);
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(context.getColor(com.deepseekharness.app.R.color.shell_raised));
    shape.setCornerRadius(dp(context, radiusDp));
    view.setBackground(shape);
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    view.setLayoutParams(
        new LinearLayout.LayoutParams(
            width < 0 ? LinearLayout.LayoutParams.MATCH_PARENT : dp(context, width),
            dp(context, heightDp)));
    return view;
  }

  private int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
