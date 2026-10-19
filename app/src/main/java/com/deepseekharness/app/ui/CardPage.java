package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.deepseekharness.app.R;

/** 原生内页共用卡片、分组和随正文滚动的操作区，业务事件由页面自己绑定。 */
final class CardPage {
  final Context context;
  final LinearLayout root, content, footer;
  final ScrollView scroll;

  CardPage(Context context, String title, String subtitle) {
    this.context = context;
    root = column();
    root.setBackgroundColor(context.getColor(R.color.surface));
    scroll = new ScrollView(context);
    scroll.setFillViewport(true);
    content = new PageColumn(context);
    content.setPadding(dp(18), dp(16), dp(18), dp(24));
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView heading = text(title, 24, R.color.text);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
    content.addView(heading);
    if (!subtitle.isEmpty()) {
      TextView sub = text(subtitle, 14, R.color.text_secondary);
      sub.setPadding(0, dp(7), 0, dp(18));
      content.addView(sub);
    }
    footer = new PageColumn(context);
    footer.setPadding(dp(18), dp(8), dp(18), dp(12));
    // 多个长操作与正文一起滚动，短屏和大字体不让页脚挤掉表单。
    LinearLayout sections = column();
    sections.addView(content);
    sections.addView(footer);
    scroll.addView(sections);
  }

  int dp(int n) {
    return Math.round(n * context.getResources().getDisplayMetrics().density);
  }

  LinearLayout column() {
    LinearLayout view = new LinearLayout(context);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  TextView text(String value, int size, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(Math.max(13, size));
    view.setLineSpacing(dp(3), 1);
    view.setIncludeFontPadding(false);
    view.setTextColor(context.getColor(color));
    return view;
  }

  LinearLayout card() {
    return card(content);
  }

  LinearLayout card(LinearLayout parent) {
    LinearLayout card = column();
    card.setPadding(dp(16), dp(14), dp(16), dp(14));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(14);
    parent.addView(com.deepseekharness.app.ui.miuix.MiuixUi.card(context, card), p);
    return card;
  }

  void label(String title) {
    TextView label = text(title, 14, R.color.text_secondary);
    label.setTypeface(null, android.graphics.Typeface.BOLD);
    androidx.core.view.ViewCompat.setAccessibilityHeading(label, true);
    label.setPadding(0, dp(12), 0, dp(8));
    content.addView(label);
  }

  void kv(LinearLayout card, String label, String value) {
    LinearLayout row = column();
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(8), 0, dp(8));
    row.addView(text(label, 13, R.color.text_secondary));
    TextView content = text(value, 15, R.color.text);
    content.setTextIsSelectable(true);
    content.setPadding(0, dp(4), 0, 0);
    row.addView(content, new LinearLayout.LayoutParams(-1, -2));
    card.addView(row);
  }

  void entry(LinearLayout card, String title, String subtitle, int icon, Runnable action) {
    card.addView(
        com.deepseekharness.app.ui.miuix.MiuixUi.entry(context, title, subtitle, icon, action));
  }

  Button button(LinearLayout parent, String title, boolean primary, Runnable action) {
    var button = new androidx.appcompat.widget.AppCompatButton(context);
    button.setText(title);
    button.setTextSize(15);
    button.setPadding(dp(16), dp(12), dp(16), dp(12));
    button.setAllCaps(false);
    button.setMinHeight(dp(48));
    button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_btn);
    button.setTextColor(
        context.getColorStateList(primary ? R.color.button_primary_text : R.color.button_text));
    button.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(8);
    parent.addView(button, p);
    return button;
  }
}
