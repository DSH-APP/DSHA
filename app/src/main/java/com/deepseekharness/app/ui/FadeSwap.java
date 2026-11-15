package com.deepseekharness.app.ui;

import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 同区域内容切换（G4）：文字先写新值再淡入；列表/卡片整体替换时淡出旧内容、重建后淡入。
 * 不叠加第三种时长——全部走 {@link UiMotion} 的 150ms / 90ms 既有节奏，系统关闭动画时直接替换。
 */
final class FadeSwap {
  private FadeSwap() {}

  /** 状态文字切换：任何时刻显示的都是最新真实值，不出现「旧值停留」。 */
  static void text(TextView view, CharSequence text) {
    UiMotion.crossFadeText(view, text);
  }

  /** 区域内容替换：rebuild 里重新填充子视图；支持连续刷新（只保留最后一次）。 */
  static void content(ViewGroup host, Runnable rebuild) {
    UiMotion.swapContent(host, rebuild);
  }
}
