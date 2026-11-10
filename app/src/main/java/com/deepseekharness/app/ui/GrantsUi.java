package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import com.deepseekharness.app.R;

/** 设备能力授权三个页面共用的轻量界面构件；不持有页面状态，也不读取授权。 */
final class GrantsUi {
  private GrantsUi() {}

  static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  /**
   * 分段选择：放在 bg_plugin_segment_track 轨道里的等宽 RadioButton；选中段用对应风险色的文字。
   * 点击由 onPick 处理，调用方负责按已保存值重画（RadioButton 自带的选中切换会被覆盖）。
   */
  static RadioButton[] buildSegment(
      Context ctx,
      RadioGroup group,
      String[] labels,
      String[] descriptions,
      int[] tones,
      java.util.function.IntConsumer onPick) {
    RadioButton[] buttons = new RadioButton[labels.length];
    int muted = ctx.getColor(R.color.shell_text_secondary);
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      RadioButton button = new RadioButton(ctx);
      button.setId(View.generateViewId());
      button.setButtonDrawable(null);
      button.setBackgroundResource(R.drawable.bg_update_channel_tab);
      button.setGravity(android.view.Gravity.CENTER);
      button.setIncludeFontPadding(false);
      button.setSingleLine(true);
      button.setEllipsize(android.text.TextUtils.TruncateAt.END);
      button.setTextSize(13);
      button.setTypeface(button.getTypeface(), android.graphics.Typeface.BOLD);
      button.setText(labels[i]);
      button.setContentDescription(descriptions[i]);
      button.setTextColor(
          new android.content.res.ColorStateList(
              new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
              new int[] {ctx.getColor(tones[i]), muted}));
      button.setMinHeight(dp(ctx, 36));
      button.setMinimumHeight(dp(ctx, 36));
      button.setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0);
      button.setOnClickListener(v -> onPick.accept(index));
      RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(0, -2, 1f);
      if (i > 0) params.setMarginStart(dp(ctx, 2));
      group.addView(button, params);
      buttons[i] = button;
    }
    return buttons;
  }

  /**
   * 标签旁的折叠说明（替代 ⓘ）：保留标签原有起始图标，收起态只有标签与展开箭头。
   *
   * <p>标签可能嵌在水平行里（例如「屏幕操作」标签 + 撤销按钮）：详情容器插到整行之后，
   * 不能插进标签与行内按钮之间。
   */
  static void bindLabelInfo(
      TextView label, String key, CharSequence full, boolean defaultExpanded, Accordion accordion) {
    if (label == null || accordion == null) return;
    DisclosureCard.Model model = new DisclosureCard.Model();
    model.key = key;
    model.detail = full;
    model.defaultExpanded = defaultExpanded;
    View host =
        label.getParent() instanceof LinearLayout line
                && line.getOrientation() == LinearLayout.HORIZONTAL
            ? (View) line
            : null;
    DisclosureCard.bind(label, model, accordion.state(), () -> accordion.onToggle(key), host);
    accordion.register(key, () -> bindLabelInfo(label, key, full, defaultExpanded, accordion));
  }
}
