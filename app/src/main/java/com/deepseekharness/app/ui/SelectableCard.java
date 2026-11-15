package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 选中卡（D1/H3）：单选=描边+色调+勾选，多选=勾选框；选中语义与按压态分开。
 *
 * <p>单选用于显示规格、语言、导出范围等互斥选项：组内由宿主保证互斥，点选即保存；
 * 紧凑格（窄屏网格）只靠描边+底色表示选中，避免勾选把母语名挤成省略号，读屏仍报 checked。
 */
final class SelectableCard {
  private SelectableCard() {}

  static final class Model {
    CharSequence title;
    CharSequence subtitle;
    int icon;
    boolean selected;
    boolean multi;
  }

  private static final Map<View, Holder> HOLDERS = new WeakHashMap<>();

  static View single(Context context, Model model, Runnable onClick) {
    return cell(context, model, false, false, onClick);
  }

  static View multi(Context context, Model model, Runnable onClick) {
    return cell(context, model, true, false, onClick);
  }

  /** 窄屏网格格：隐藏勾选，只保留描边+色调（与语言选择一致的紧凑规则）。 */
  static View grid(Context context, Model model, Runnable onClick) {
    return cell(context, model, false, true, onClick);
  }

  static void setSelected(View view, boolean selected) {
    Holder holder = view == null ? null : HOLDERS.get(view);
    if (holder == null) return;
    holder.model.selected = selected;
    holder.apply();
  }

  static boolean isSelected(View view) {
    Holder holder = view == null ? null : HOLDERS.get(view);
    return holder != null && holder.model.selected;
  }

  private static View cell(
      Context context, Model model, boolean multi, boolean compact, Runnable onClick) {
    LinearLayout cell = new LinearLayout(context);
    cell.setOrientation(LinearLayout.HORIZONTAL);
    cell.setGravity(Gravity.CENTER_VERTICAL);
    int padH = dp(context, compact ? 9 : 12), padV = dp(context, compact ? 7 : 10);
    cell.setPadding(padH, padV, padH, padV);
    cell.setMinimumHeight(dp(context, compact ? 50 : 56));
    cell.setClickable(true);
    cell.setFocusable(true);
    cell.setForeground(context.getDrawable(R.drawable.bg_action_plain));

    if (model.icon != 0) {
      ImageView icon = new ImageView(context);
      icon.setImageResource(model.icon);
      icon.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.shell_text_muted)));
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams iconParams =
          new LinearLayout.LayoutParams(dp(context, 20), dp(context, 20));
      iconParams.setMarginEnd(dp(context, 10));
      cell.addView(icon, iconParams);
    }

    LinearLayout words = new LinearLayout(context);
    words.setOrientation(LinearLayout.VERTICAL);
    TextView title = new TextView(context);
    title.setText(model.title);
    title.setTextSize(compact ? 14f : 15f);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setIncludeFontPadding(false);
    title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
    title.setSingleLine(compact);
    title.setEllipsize(compact ? android.text.TextUtils.TruncateAt.END : null);
    words.addView(title, new LinearLayout.LayoutParams(-1, -2));
    TextView subtitle = null;
    if (!android.text.TextUtils.isEmpty(model.subtitle)) {
      subtitle = new TextView(context);
      subtitle.setText(model.subtitle);
      subtitle.setTextSize(11.5f);
      subtitle.setIncludeFontPadding(false);
      subtitle.setTextColor(context.getColor(R.color.shell_text_muted));
      subtitle.setMaxLines(2);
      subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
      subParams.topMargin = dp(context, 3);
      words.addView(subtitle, subParams);
    } else {
      words.setGravity(Gravity.CENTER_VERTICAL);
    }
    LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1f);
    cell.addView(words, wordsParams);

    CheckBox multiBox = null;
    ImageView check = null;
    if (multi) {
      multiBox = new CheckBox(context);
      multiBox.setClickable(false);
      multiBox.setFocusable(false);
      multiBox.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      cell.addView(multiBox, new LinearLayout.LayoutParams(-2, -2));
    } else if (!compact) {
      check = new ImageView(context);
      check.setImageResource(R.drawable.ic_onb_check);
      check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams checkParams =
          new LinearLayout.LayoutParams(dp(context, 16), dp(context, 16));
      checkParams.setMarginStart(dp(context, 6));
      cell.addView(check, checkParams);
    }

    Holder holder = new Holder(cell, model, title, subtitle, check, multiBox, compact);
    HOLDERS.put(cell, holder);
    holder.apply();
    String role = multi ? "android.widget.CheckBox" : "android.widget.RadioButton";
    cell.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(role);
            info.setCheckable(true);
            info.setChecked(holder.model.selected);
            if (android.text.TextUtils.isEmpty(holder.model.subtitle) == false)
              info.setText(holder.model.title + "，" + holder.model.subtitle);
          }
        });
    cell.setContentDescription(
        android.text.TextUtils.isEmpty(model.subtitle)
            ? model.title
            : model.title + "，" + model.subtitle);
    if (onClick != null) cell.setOnClickListener(v -> onClick.run());
    return cell;
  }

  private static final class Holder {
    final View root;
    final Model model;
    final TextView title;
    final TextView subtitle;
    final ImageView check;
    final CheckBox multiBox;
    final boolean compact;
    final GradientDrawable shape = new GradientDrawable();

    Holder(
        View root,
        Model model,
        TextView title,
        TextView subtitle,
        ImageView check,
        CheckBox multiBox,
        boolean compact) {
      this.root = root;
      this.model = model;
      this.title = title;
      this.subtitle = subtitle;
      this.check = check;
      this.multiBox = multiBox;
      this.compact = compact;
      shape.setCornerRadius(dp(root.getContext(), 14));
    }

    void apply() {
      Context context = root.getContext();
      int accent = context.getColor(R.color.shell_accent);
      boolean selected = model.selected;
      shape.setColor(
          selected
              ? androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x1A)
              : context.getColor(R.color.shell_surface));
      shape.setStroke(
          dp(context, selected ? 2 : 1),
          selected ? accent : context.getColor(R.color.shell_line_strong));
      root.setBackground(shape);
      title.setTextColor(context.getColor(selected ? R.color.shell_accent : R.color.shell_text));
      if (subtitle != null) subtitle.setVisibility(View.VISIBLE);
      if (check != null) {
        check.setImageTintList(ColorStateList.valueOf(accent));
        check.setVisibility(selected ? View.VISIBLE : View.GONE);
      }
      if (multiBox != null) multiBox.setChecked(selected);
      root.setSelected(selected);
      root.setStateDescription(selected ? UiText.choose("已选中", "Selected") : null);
    }
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
