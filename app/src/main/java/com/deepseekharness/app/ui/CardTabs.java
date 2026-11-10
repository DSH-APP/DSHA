package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.CardTabState;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;

/**
 * 卡内标签页（F3）：同一张卡片里的少量并列内容，切换不离开卡片。
 *
 * <p>页签行可以横向滚动，所以 1.3 倍字体与阿拉伯语（RTL）下不会被截断或挤压；未选中的面板
 * 设为 GONE，读屏不会读到隐藏内容。同一时刻只有一个页签选中，选中状态写进
 * {@link CardTabState}，旋转 / 语言切换后按稳定键恢复。
 */
final class CardTabs {
  static final class Tab {
    final String key;
    final CharSequence title;
    final View pane;

    Tab(String key, CharSequence title, View pane) {
      this.key = key;
      this.title = title;
      this.pane = pane;
    }
  }

  private final LinearLayout card;
  private final List<Tab> tabs = new ArrayList<>();
  private final LinearLayout row;
  private final CardTabState state;
  private final List<TextView> labels = new ArrayList<>();
  private final List<View> indicators = new ArrayList<>();

  private CardTabs(LinearLayout card, List<Tab> tabs, String savedKey) {
    this.card = card;
    List<String> keys = new ArrayList<>();
    for (Tab tab : tabs) {
      if (tab == null || tab.key == null || tab.title == null || tab.pane == null) continue;
      keys.add(tab.key);
      this.tabs.add(tab);
    }
    state = new CardTabState(keys, savedKey);
    row = new LinearLayout(card.getContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    HorizontalScrollView scroller = new HorizontalScrollView(card.getContext());
    scroller.setHorizontalScrollBarEnabled(false);
    scroller.setFillViewport(false);
    scroller.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
    LinearLayout.LayoutParams scrollerParams = new LinearLayout.LayoutParams(-1, -2);
    scrollerParams.bottomMargin = dp(8);
    card.addView(scroller, 0, scrollerParams);
    for (Tab tab : this.tabs) labels.add(tab(card.getContext(), tab));
    applySelection(null);
  }

  /** 在卡片顶部插入页签行；{@code savedKey} 为上次保存的选中键。 */
  static CardTabs bind(LinearLayout card, List<Tab> tabs, String savedKey) {
    if (card == null || tabs == null || tabs.isEmpty()) return null;
    return new CardTabs(card, tabs, savedKey);
  }

  String selectedKey() {
    return state.selectedKey();
  }

  int selectedIndex() {
    return state.selectedIndex();
  }

  boolean select(String key) {
    if (!state.select(key)) return false;
    applySelection(key);
    return true;
  }

  /** 页签行本身（测试与宿主做短屏调整时使用）。 */
  LinearLayout row() {
    return row;
  }

  private TextView tab(Context context, Tab tab) {
    LinearLayout cell = new LinearLayout(context);
    cell.setOrientation(LinearLayout.VERTICAL);
    cell.setGravity(Gravity.CENTER_HORIZONTAL);
    TextView text = new TextView(context);
    text.setText(tab.title);
    text.setTextSize(13.5f);
    text.setIncludeFontPadding(false);
    text.setGravity(Gravity.CENTER);
    text.setMinHeight(dp(40));
    text.setPaddingRelative(dp(12), dp(8), dp(12), dp(6));
    text.setOnClickListener(view -> select(tab.key));
    text.setFocusable(true);
    cell.addView(text, new LinearLayout.LayoutParams(-2, -2));
    View indicator = new View(context);
    LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(-1, dp(2));
    indicatorParams.leftMargin = dp(8);
    indicatorParams.rightMargin = dp(8);
    indicatorParams.bottomMargin = dp(4);
    indicator.setLayoutParams(indicatorParams);
    cell.addView(indicator);
    row.addView(cell, new LinearLayout.LayoutParams(-2, -2));
    indicators.add(indicator);
    return text;
  }

  private void applySelection(String announced) {
    int selected = state.selectedIndex();
    for (int index = 0; index < tabs.size(); index++) {
      boolean active = index == selected;
      Tab tab = tabs.get(index);
      tab.pane.setVisibility(active ? View.VISIBLE : View.GONE);
      TextView text = labels.get(index);
      text.setTextColor(
          card.getContext().getColor(active ? R.color.shell_accent : R.color.shell_text_secondary));
      text.setTypeface(
          null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
      text.setSelected(active);
      text.setStateDescription(active ? UiText.text("已选中") : UiText.choose("未选中", "Not selected"));
      text.setContentDescription(tab.title);
      View indicator = indicators.get(index);
      indicator.setBackgroundColor(
          card.getContext().getColor(active ? R.color.shell_accent : android.R.color.transparent));
      indicator.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
    }
    if (announced != null) {
      int index = state.selectedIndex();
      if (index >= 0 && index < labels.size() && labels.get(index).isAttachedToWindow())
        labels.get(index).announceForAccessibility(labels.get(index).getText());
    }
  }

  private int dp(int value) {
    return Math.round(value * card.getResources().getDisplayMetrics().density);
  }
}
