package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.deepseekharness.app.R;

/**
 * 原生内页共用页面骨架：v5 标题 / 分组卡片 / 可点行 / 页脚操作。
 *
 * <p>2026-10-27 起统一走 shell_* token：画布、卡片、文字、描边与按钮不再各页一套。
 * 业务事件仍由页面自己绑定；这里只提供容器，不伪造任何状态或数据。
 */
final class CardPage {
  final Context context;
  final LinearLayout root, content, footer;
  final ScrollView scroll;

  /** 标题与副标题（可能为 null）；CardSheet 的短屏分栏形态把它们移到左栏。 */
  TextView heading, subtitle;

  /** 由 {@link #button} 创建的主按钮；CardSheet 横排页脚时把主操作放到右侧。 */
  final java.util.Set<View> primaryButtons = new java.util.HashSet<>();

  private final boolean compact;

  CardPage(Context context, String title, String subtitle) {
    this(context, title, subtitle, false);
  }

  /** 底部弹层 / 对话框用紧凑标题，避免把页面级大标题塞进半屏容器。 */
  CardPage(Context context, String title, String subtitle, boolean compact) {
    this.context = context;
    this.compact = compact;
    root = column();
    root.setBackgroundColor(context.getColor(R.color.shell_bg));
    scroll = new ScrollView(context);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    content = column();
    int bottom =
        compact ? dp(16) : context.getResources().getDimensionPixelSize(R.dimen.shell_page_bottom);
    content.setPadding(dp(pad()), dp(compact ? 16 : 18), dp(pad()), bottom);
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (title != null && !title.isEmpty()) {
      heading = text(title, compact ? 20 : 26, R.color.shell_text);
      heading.setTypeface(null, android.graphics.Typeface.BOLD);
      heading.setLineSpacing(dp(2), 1);
      if (subtitle == null || subtitle.isEmpty())
        heading.setPadding(0, 0, 0, dp(compact ? 12 : 18));
      content.addView(heading);
    }
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView sub = text(subtitle, compact ? 12 : 13, R.color.shell_text_secondary);
      sub.setLineSpacing(dp(3), 1);
      sub.setPadding(0, dp(8), 0, dp(compact ? 14 : 20));
      content.addView(sub);
      this.subtitle = sub;
    }
    footer = column();
    footer.setPadding(dp(pad()), dp(8), dp(pad()), dp(compact ? 12 : 16));
    root.addView(footer);
  }

  private int pad() {
    return compact ? 16 : 20;
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
    view.setTextSize(size);
    view.setIncludeFontPadding(false);
    view.setTextColor(context.getColor(color));
    return view;
  }

  LinearLayout card() {
    return card(CardTokens.Tier.OUTLINED);
  }

  /** 按 H1 层级建卡：页面主卡 elevated、分组 filled、次级 outlined。 */
  LinearLayout card(CardTokens.Tier tier) {
    LinearLayout card = column();
    card.setPadding(dp(16), dp(12), dp(16), dp(12));
    CardTokens.tier(card, tier);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(14);
    content.addView(card, p);
    return card;
  }

  void label(String title) {
    TextView label = text(title, 12, R.color.shell_text_secondary);
    label.setTypeface(null, android.graphics.Typeface.BOLD);
    label.setLetterSpacing(0.03f);
    label.setPadding(0, dp(10), 0, dp(8));
    content.addView(label);
  }

  void kv(LinearLayout card, String label, String value) {
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(44));
    row.addView(
        text(label, 12, R.color.shell_text_secondary), new LinearLayout.LayoutParams(0, -2, 1));
    TextView content = text(value, 13, R.color.shell_text);
    content.setMaxWidth(dp(210));
    content.setGravity(Gravity.END);
    row.addView(content);
    card.addView(row);
  }

  /** 可点行；返回行视图，列表首次错峰等效果需要直接操作它。 */
  View entry(LinearLayout card, String title, String subtitle, int icon, Runnable action) {
    if (card.getChildCount() > 0) card.addView(divider());
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(10), 0, dp(10));
    row.setMinimumHeight(dp(56));
    row.setBackgroundResource(R.drawable.bg_action_plain);
    row.setFocusable(true);
    row.setOnClickListener(v -> action.run());
    PressFeedback.attach(row, PressFeedback.Variant.ROW);
    ImageView mark = new ImageView(context);
    mark.setImageResource(icon);
    mark.setImageTintList(
        android.content.res.ColorStateList.valueOf(context.getColor(R.color.shell_accent)));
    mark.setBackgroundResource(R.drawable.bg_logo);
    mark.setPadding(dp(7), dp(7), dp(7), dp(7));
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(mark, new LinearLayout.LayoutParams(dp(32), dp(32)));
    LinearLayout words = column();
    TextView name = text(title, 14, R.color.shell_text);
    name.setTypeface(null, android.graphics.Typeface.BOLD);
    words.addView(name);
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView detail = text(subtitle, 12, R.color.shell_text_muted);
      detail.setPadding(0, dp(4), 0, 0);
      detail.setMaxLines(2);
      detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
      words.addView(detail);
    }
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.leftMargin = dp(12);
    p.rightMargin = dp(8);
    row.addView(words, p);
    ImageView arrow = new ImageView(context);
    arrow.setImageResource(R.drawable.ic_ui2_chevron);
    arrow.setImageTintList(
        android.content.res.ColorStateList.valueOf(context.getColor(R.color.shell_text_muted)));
    arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(arrow, new LinearLayout.LayoutParams(dp(16), dp(16)));
    card.addView(row);
    return row;
  }

  private View divider() {
    View line = new View(context);
    line.setBackgroundColor(context.getColor(R.color.shell_line));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
    p.leftMargin = dp(44);
    line.setLayoutParams(p);
    return line;
  }

  Button button(LinearLayout parent, String title, boolean primary, Runnable action) {
    Button button = button(context, parent, title, primary, action);
    if (primary) primaryButtons.add(button);
    return button;
  }

  /** 对话框等不使用卡片页脚的容器复用同一套按钮样式；只有卡片页脚需要登记主按钮。 */
  static Button button(
      Context context, LinearLayout parent, String title, boolean primary, Runnable action) {
    var button = new androidx.appcompat.widget.AppCompatButton(context);
    button.setText(title);
    button.setTextSize(14);
    button.setAllCaps(false);
    button.setTypeface(null, android.graphics.Typeface.BOLD);
    button.setIncludeFontPadding(false);
    int height = dp(context, primary ? 52 : 46);
    button.setMinHeight(height);
    button.setMinimumHeight(height);
    button.setMinWidth(0);
    button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_btn);
    button.setTextColor(
        context.getColorStateList(primary ? R.color.button_primary_text : R.color.button_text));
    button.setOnClickListener(v -> action.run());
    PressFeedback.attach(button, PressFeedback.Variant.BUTTON);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(context, 10);
    parent.addView(button, p);
    return button;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
