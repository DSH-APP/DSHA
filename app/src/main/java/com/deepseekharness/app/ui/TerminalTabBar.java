package com.deepseekharness.app.ui;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.deepseekharness.app.PtySession;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.TerminalTabs;

/** PTY 和简易终端共用的标签行；新建和关闭始终是独立的 48dp 触摸目标。 */
final class TerminalTabBar {
  interface Actions {
    void select(long id);

    void close(long id);
  }

  static <T> void render(View root, TerminalTabs.ReadOnly<T> tabs, Actions actions) {
    LinearLayout row = root.findViewById(R.id.terminal_tabs);
    row.removeAllViews();
    var current = tabs.current();
    for (var tab : tabs.snapshot()) {
      boolean selected = current != null && current.id == tab.id;
      boolean running = isRunning(tab.value);
      LinearLayout chip = new LinearLayout(root.getContext());
      chip.setGravity(Gravity.CENTER_VERTICAL);
      chip.setMinimumHeight(dp(root, 48));
      chip.setBackgroundResource(selected ? R.drawable.bg_card : 0);
      View dot = new View(root.getContext());
      GradientDrawable circle = new GradientDrawable();
      circle.setShape(GradientDrawable.OVAL);
      circle.setColor(
          root.getContext()
              .getColor(running && !tab.isClosing() ? R.color.ok : R.color.text_muted));
      dot.setBackground(circle);
      dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(root, 8), dp(root, 8));
      dotLp.setMarginStart(dp(root, 12));
      chip.addView(dot, dotLp);
      TextView name = new TextView(root.getContext());
      name.setText(
          com.deepseekharness.app.util.UiText.format(
              tab.isClosing() ? "终端 %s · 关闭中…" : "终端 %s", tab.number));
      name.setTextColor(
          root.getContext().getColor(selected ? R.color.text : R.color.text_secondary));
      name.setTextSize(14);
      name.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
      name.setIncludeFontPadding(false);
      name.setGravity(Gravity.CENTER);
      name.setSingleLine();
      name.setMinWidth(dp(root, 48));
      name.setMinHeight(dp(root, 48));
      name.setPadding(dp(root, 8), 0, dp(root, 8), 0);
      name.setContentDescription(
          com.deepseekharness.app.util.UiText.format("切换到终端 %s", tab.number));
      name.setOnClickListener(v -> actions.select(tab.id));
      chip.addView(name, new LinearLayout.LayoutParams(-2, -2));
      if (!running && !tab.isClosing() && selected) {
        TextView ended = new TextView(root.getContext());
        ended.setText(root.getContext().getString(R.string.ui2_ended));
        ended.setTextColor(root.getContext().getColor(R.color.text_muted));
        ended.setTextSize(12);
        ended.setIncludeFontPadding(false);
        ended.setGravity(Gravity.CENTER);
        ended.setMinHeight(dp(root, 48));
        ended.setPadding(0, 0, dp(root, 4), 0);
        chip.addView(ended, new LinearLayout.LayoutParams(-2, -2));
      }
      TextView close = new TextView(root.getContext());
      close.setText(com.deepseekharness.app.util.UiText.text("×"));
      close.setTextSize(20);
      close.setGravity(Gravity.CENTER);
      close.setTextColor(root.getContext().getColor(R.color.text_secondary));
      close.setMinHeight(dp(root, 48));
      close.setContentDescription(
          com.deepseekharness.app.util.UiText.format("关闭终端 %s", tab.number));
      close.setEnabled(!tab.isClosing());
      close.setAlpha(tab.isClosing() ? 0.4f : 1f);
      close.setOnClickListener(v -> actions.close(tab.id));
      chip.addView(close, new LinearLayout.LayoutParams(dp(root, 48), dp(root, 48)));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginEnd(dp(root, 6));
      row.addView(chip, lp);
      if (selected)
        chip.post(
            () -> {
              if (root.isAttachedToWindow())
                ((HorizontalScrollView) root.findViewById(R.id.terminal_tabs_scroll))
                    .smoothScrollTo(chip.getLeft(), 0);
            });
    }
  }

  private static boolean isRunning(Object value) {
    if (value instanceof PtySession) return ((PtySession) value).isRunning();
    return true;
  }

  private static int dp(View root, int value) {
    return Math.round(value * root.getResources().getDisplayMetrics().density);
  }
}
