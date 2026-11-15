package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.*;
import android.widget.*;
import com.deepseekharness.app.R;

/** 使用矢量返回图标，视觉和触摸中心一致。 */
final class UiNavigation {
  static ImageButton back(Context context, Runnable action) {
    var button = new androidx.appcompat.widget.AppCompatImageButton(context);
    button.setImageResource(R.drawable.ic_ui_back);
    android.util.TypedValue value = new android.util.TypedValue();
    context
        .getTheme()
        .resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
    button.setBackgroundResource(value.resourceId);
    int pad = dp(context, 12);
    button.setPadding(pad, pad, pad, pad);
    button.setContentDescription(com.deepseekharness.app.util.UiText.choose("返回", "Back"));
    button.setOnClickListener(v -> action.run());
    return button;
  }

  /**
   * 内页顶部只放返回键：页面标题由 CardPage 的大标题承担，避免同一标题在一屏出现两次。
   * 标题仍作为无障碍窗格名保留。
   */
  static void addHeader(Context context, LinearLayout root, String title, Runnable action) {
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(context, 6), 0, dp(context, 18), 0);
    row.addView(
        back(context, action), new LinearLayout.LayoutParams(dp(context, 48), dp(context, 52)));
    androidx.core.view.ViewCompat.setAccessibilityPaneTitle(row, title);
    root.addView(row, 0);
  }

  private static int dp(Context context, int n) {
    return Math.round(n * context.getResources().getDisplayMetrics().density);
  }
}
