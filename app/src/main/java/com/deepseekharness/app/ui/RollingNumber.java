package com.deepseekharness.app.ui;

import android.widget.TextView;
import com.deepseekharness.app.util.Fmt;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.LongFunction;

/**
 * 统计数字滚动（E5）：字节/计数从上一值滚到新值（200ms），单位在滚动中保持不变；
 * 系统关闭动画、首次渲染或值未变时直接落值。上一值按 TextView 记住，页面刷新不用自己保存。
 */
final class RollingNumber {
  private RollingNumber() {}

  private static final Map<TextView, Long> LAST = new WeakHashMap<>();

  static void bytes(TextView view, long bytes) {
    value(view, bytes, Fmt::bytes);
  }

  static void value(TextView view, long target, LongFunction<String> render) {
    if (view == null || render == null) return;
    Long previous = LAST.get(view);
    LAST.put(view, target);
    if (previous == null || previous == target || !UiMotion.animationsEnabled(view.getContext())) {
      view.setText(render.apply(target));
      return;
    }
    long from = previous;
    UiMotion.animateValue(view, from, target, value -> render.apply(Math.round(value)));
  }
}
