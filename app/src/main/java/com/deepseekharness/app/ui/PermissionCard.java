package com.deepseekharness.app.ui;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/**
 * 单项授权的紧凑版式：一行（图标 + 名称 + 副标题 + 状态标签或行内主操作 + 展开箭头），展开后才显示
 * 用途说明、可解锁能力、状态原文、离开 DSHA 的提示与其余按钮。
 *
 * <p>已开启的项只显示一行绿色状态；未开启的项副标题只放“开了能做什么”，异常（被拒绝、后台受限等）才换成警示。
 * 折叠时不创建详情视图，读屏不会读到隐藏内容。状态由 PermissionPanel 每次重读后重绘，展开选择由面板持有。
 */
final class PermissionCard {
  /** 一次渲染所需的数据。 */
  static final class Model {
    int icon;
    String title, why, status, outside, note;
    String[] unlocks = new String[0];
    String pill;
    OnboardingKit.Tone tone = OnboardingKit.Tone.NEUTRAL;
    boolean ok, recommended;

    /** 展开区里的按钮。未开启且设置了 inline 时，actions[0] 视为与 inline 同一操作，展开区不再重复它。 */
    View[] actions = new View[0];

    /** 未开启时放在行内的主操作（短文案小按钮）；为 null 时行内显示 pill。 */
    View inline;

    /** 未开启时的异常提示，替代副标题（例如“系统不再弹框”）。 */
    String alert;

    /** 展开区追加的自定义视图（例如通道状态与管理器按钮）。 */
    View extra;
  }

  private PermissionCard() {}

  /** 行内主操作：小号实心按钮。完整文案放进读屏描述，避免短文案在读屏时失去上下文。 */
  static View inlineAction(
      OnboardingKit kit, String label, String description, View.OnClickListener action) {
    android.widget.Button b = compact(kit, kit.small(label, true, action));
    if (description != null && !description.isEmpty()) b.setContentDescription(description);
    return b;
  }

  /** 把已有的小按钮压成行内尺寸（34dp 高、单行），保留原有点击逻辑。 */
  static android.widget.Button compact(OnboardingKit kit, android.widget.Button b) {
    b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
    b.setMinHeight(kit.dp(34));
    b.setMinimumHeight(kit.dp(34));
    b.setPadding(kit.dp(12), 0, kit.dp(12), 0);
    b.setSingleLine(true);
    b.setMaxWidth(kit.dp(150));
    b.setEllipsize(android.text.TextUtils.TruncateAt.END);
    return b;
  }

  /** 构建一项授权。expanded 决定是否创建详情；onToggle 在点击该行时调用（面板据此切换并重绘）。 */
  static LinearLayout build(OnboardingKit kit, Model m, boolean expanded, Runnable onToggle) {
    LinearLayout item = kit.column();

    LinearLayout row = kit.row();
    row.setMinimumHeight(kit.dp(56));
    row.setPaddingRelative(kit.dp(12), kit.dp(8), kit.dp(10), kit.dp(8));
    row.setBackgroundResource(R.drawable.bg_onb_row_ripple);
    row.addView(kit.iconBox(m.icon, m.ok, 32, 17));

    LinearLayout words = kit.column();
    TextView title = kit.bold(m.title, 14.5f, R.color.shell_text);
    title.setMaxLines(2);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    words.addView(title);
    String subtitle = subtitle(m);
    if (!subtitle.isEmpty()) {
      boolean warn = !m.ok && m.alert != null && !m.alert.isEmpty();
      TextView sub = kit.text(subtitle, 12, warn ? R.color.shell_warn : R.color.shell_text_muted);
      sub.setMaxLines(expanded ? 3 : 1);
      sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
      words.addView(sub, kit.topMargin(2));
    }
    LinearLayout.LayoutParams wp =
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    wp.setMarginStart(kit.dp(10));
    wp.setMarginEnd(kit.dp(8));
    row.addView(words, wp);

    View trailing = trailing(kit, m);
    if (trailing != null) row.addView(trailing, wrap());

    // ic_ui2_chevron 朝右；展开时朝下，RTL 下折叠态朝左。
    ImageView chevron = kit.icon(R.drawable.ic_ui2_chevron, R.color.shell_text_muted, 16);
    chevron.setRotation(expanded ? 90 : (UiText.isRtl() ? 180 : 0));
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(kit.dp(16), kit.dp(16));
    cp.setMarginStart(kit.dp(6));
    row.addView(chevron, cp);

    row.setClickable(true);
    row.setFocusable(true);
    row.setOnClickListener(v -> onToggle.run());
    row.setContentDescription(rowDescription(m, subtitle));
    row.setStateDescription(
        expanded ? UiText.choose("已展开", "Expanded") : UiText.choose("已收起", "Collapsed"));
    row.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setHeading(true);
            info.addAction(
                new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK,
                    expanded
                        ? UiText.choose("收起说明", "Hide details")
                        : UiText.choose("展开说明", "Show details")));
          }
        });
    item.addView(row, OnboardingKit.matchWrap());

    if (expanded) item.addView(details(kit, m), OnboardingKit.matchWrap());
    return item;
  }

  /** 已开启：不需要副标题（状态在标签里）；未开启：异常提示优先，否则“开了能做什么”。 */
  static String subtitle(Model m) {
    if (m.ok) return "";
    if (m.alert != null && !m.alert.isEmpty()) return m.alert;
    if (m.unlocks.length > 0) return String.join(" · ", m.unlocks);
    // 没有可解锁能力列表（设备命令通道）时，用状态短语代替长说明；行内已是按钮，标签不显示。
    if (m.pill != null && !m.pill.isEmpty()) return m.pill;
    return m.why == null ? "" : m.why;
  }

  private static View trailing(OnboardingKit kit, Model m) {
    if (!m.ok && m.inline != null) {
      detach(m.inline);
      return m.inline;
    }
    if (m.pill == null || m.pill.isEmpty()) return null;
    TextView pill = kit.pill(m.pill, m.tone);
    pill.setMaxWidth(kit.dp(128));
    pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return pill;
  }

  private static String rowDescription(Model m, String subtitle) {
    String sep = UiText.choose("，", ", ");
    StringBuilder out = new StringBuilder(m.title);
    if (m.pill != null && !m.pill.isEmpty()) out.append(sep).append(m.pill);
    if (!subtitle.isEmpty() && !subtitle.equals(m.pill)) out.append(sep).append(subtitle);
    return out.toString();
  }

  private static View details(OnboardingKit kit, Model m) {
    LinearLayout box = kit.column();
    // 与行内文字左缘对齐：12dp 内边距 + 32dp 图标 + 10dp 间距。
    box.setPaddingRelative(kit.dp(54), 0, kit.dp(14), kit.dp(12));
    if (m.why != null && !m.why.isEmpty())
      box.addView(kit.text(m.why, 12.5f, R.color.shell_text_secondary));
    // 未开启时可解锁能力已在副标题里，展开区不再重复成标签。
    if (m.ok && m.unlocks.length > 0) {
      OnboardingKit.FlowRow tags =
          new OnboardingKit.FlowRow(kit.context, kit.dp(5), kit.dp(5), Gravity.START);
      for (String u : m.unlocks) {
        TextView tag = kit.text(u, 11.5f, R.color.shell_text_secondary);
        tag.setBackgroundResource(R.drawable.bg_onb_pill_neutral);
        tag.setPadding(kit.dp(8), kit.dp(2), kit.dp(8), kit.dp(2));
        tags.addView(tag, wrap());
      }
      kit.add(box, tags, 8);
    }
    if (m.status != null && !m.status.isEmpty()) {
      TextView status =
          kit.text(m.status, 12, m.ok ? R.color.shell_good : R.color.shell_text_secondary);
      status.setBackgroundResource(R.drawable.bg_onb_status_box);
      status.setPadding(kit.dp(10), kit.dp(7), kit.dp(10), kit.dp(7));
      kit.add(box, status, 8);
    }
    if (m.extra != null) {
      detach(m.extra);
      kit.add(box, m.extra, 8);
    }
    if (!m.ok && m.outside != null && !m.outside.isEmpty()) {
      LinearLayout out = kit.row();
      out.setGravity(Gravity.TOP);
      LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(kit.dp(13), kit.dp(13));
      ip.topMargin = kit.dp(2);
      out.addView(kit.icon(R.drawable.ic_onb_external, R.color.shell_text_muted, 13), ip);
      LinearLayout.LayoutParams tp =
          new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      tp.setMarginStart(kit.dp(6));
      out.addView(kit.text(m.outside, 12, R.color.shell_text_muted), tp);
      kit.add(box, out, 8);
    }
    if (m.note != null && !m.note.isEmpty())
      kit.add(box, kit.text(m.note, 12, R.color.shell_text_muted), 6);
    View[] actions = detailActions(m);
    if (actions.length > 0) kit.add(box, kit.actionRow(actions), 6);
    return box;
  }

  /** 未开启且主操作已在行内时，展开区去掉与之重复的 actions[0]。 */
  static View[] detailActions(Model m) {
    if (m.ok || m.inline == null || m.actions.length == 0) return m.actions;
    View[] rest = new View[m.actions.length - 1];
    System.arraycopy(m.actions, 1, rest, 0, rest.length);
    return rest;
  }

  private static void detach(View view) {
    if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
  }

  private static LinearLayout.LayoutParams wrap() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }
}
