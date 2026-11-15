package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/**
 * 多选底部操作栏（D2）：显示已选数量与动作；0 项时计数收起但栏位不跳，动作禁用并写原因。
 * 只用既有主题按钮与「已选中」文案，不新增固定字面量。
 */
final class SelectionActionBar {
  private SelectionActionBar() {}

  static final class Bar {
    final LinearLayout root;
    final LinearLayout header;
    final TextView count;
    final LinearLayout actions;
    private View badge;

    private Bar(Context context) {
      root = new LinearLayout(context);
      root.setOrientation(LinearLayout.VERTICAL);
      header = new LinearLayout(context);
      header.setOrientation(LinearLayout.HORIZONTAL);
      header.setGravity(Gravity.CENTER_VERTICAL);
      count = new TextView(context);
      count.setTextSize(12.5f);
      count.setTextColor(context.getColor(R.color.shell_text_secondary));
      header.addView(count, new LinearLayout.LayoutParams(0, -2, 1f));
      actions = new LinearLayout(context);
      actions.setOrientation(LinearLayout.HORIZONTAL);
      header.addView(actions, new LinearLayout.LayoutParams(-2, -2));
      root.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 主/次动作：主操作用实心按钮，其余用描边按钮；返回按钮供禁用/文案刷新。 */
    Button action(CharSequence label, boolean primary, Runnable run) {
      Context context = root.getContext();
      Button button = new androidx.appcompat.widget.AppCompatButton(context);
      button.setText(label);
      button.setAllCaps(false);
      button.setTextSize(13f);
      button.setTypeface(button.getTypeface(), android.graphics.Typeface.BOLD);
      button.setIncludeFontPadding(false);
      button.setMinHeight(Math.round(40 * density(context)));
      button.setMinimumHeight(Math.round(40 * density(context)));
      button.setMinWidth(0);
      button.setPaddingRelative(
          Math.round(12 * density(context)), 0, Math.round(12 * density(context)), 0);
      button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_btn);
      button.setTextColor(
          context.getColorStateList(primary ? R.color.button_primary_text : R.color.button_text));
      if (run != null) button.setOnClickListener(v -> run.run());
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
      params.setMarginStart(Math.round(8 * density(context)));
      actions.addView(button, params);
      return button;
    }

    /** 更新已选数量：>0 显示徽标与「已选中」，0 项收起计数但仍保留动作栏位置。 */
    void update(int selected) {
      Context context = root.getContext();
      if (selected <= 0) {
        count.setText("");
        if (badge != null) {
          header.removeView(badge);
          badge = null;
        }
        actions.setVisibility(View.GONE);
        return;
      }
      actions.setVisibility(View.VISIBLE);
      if (badge == null) {
        badge = CardTokens.badge(context, selected, CardTokens.Tone.ACCENT);
        badge.setId(View.generateViewId());
        header.addView(badge, 0, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams gap = (LinearLayout.LayoutParams) badge.getLayoutParams();
        gap.setMarginEnd(Math.round(6 * density(context)));
      } else {
        ((TextView) badge).setText(selected > 99 ? "99+" : String.valueOf(selected));
        badge.setVisibility(View.VISIBLE);
      }
      count.setText(UiText.choose("已选中", "Selected"));
    }
  }

  /** 挂到页面页脚；随后用 {@link Bar#action} 添加动作。 */
  static Bar attach(LinearLayout footer) {
    if (footer == null) return null;
    Bar bar = new Bar(footer.getContext());
    footer.addView(bar.root, 0, new LinearLayout.LayoutParams(-1, -2));
    return bar;
  }

  private static float density(Context context) {
    return context.getResources().getDisplayMetrics().density;
  }
}
