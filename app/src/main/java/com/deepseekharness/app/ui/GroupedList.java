package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RotateDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.StickyHeaderMath;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分组列表（F4）：分组标题吸顶 + 可折叠。
 *
 * <p>用于设置这类长页：滚动时当前分组的标题贴在视口顶部，到组底被顶走；点标题折叠该组，
 * 收起后正文从布局与读屏里一起消失，组标题仍是可点按钮（读屏给出“已展开/已收起”）。
 *
 * <p>吸顶只改 translationY，不改真实布局：滚动位置、焦点与无障碍顺序都不变；折叠状态保存在
 * {@link Memory} 里，旋转与语言切换后保持用户选择。
 */
final class GroupedList {
  static final class Group {
    final String key;
    final TextView header;
    final View body;
    Drawable originalBackground;
    boolean collapsed;

    Group(String key, TextView header, View body) {
      this.key = key;
      this.header = header;
      this.body = body;
    }
  }

  /** 折叠状态：Map + Bundle 存取，不缓存语言。 */
  static final class Memory {
    private final Map<String, Boolean> values = new HashMap<>();

    Boolean collapsed(String key) {
      return values.get(key);
    }

    void setCollapsed(String key, boolean value) {
      values.put(key, value);
    }

    void save(Bundle out, String name) {
      Bundle bundle = new Bundle();
      for (Map.Entry<String, Boolean> entry : values.entrySet())
        bundle.putBoolean(entry.getKey(), Boolean.TRUE.equals(entry.getValue()));
      out.putBundle(name, bundle);
    }

    static Memory restore(Bundle in, String name) {
      Memory memory = new Memory();
      Bundle bundle = in == null ? null : in.getBundle(name);
      if (bundle == null) return memory;
      for (String key : bundle.keySet()) memory.values.put(key, bundle.getBoolean(key));
      return memory;
    }
  }

  private final ScrollView scroll;
  private final List<Group> groups = new ArrayList<>();
  private final Memory memory;
  private final Runnable onToggle;

  private GroupedList(ScrollView scroll, Memory memory, Runnable onToggle) {
    this.scroll = scroll;
    this.memory = memory == null ? new Memory() : memory;
    this.onToggle = onToggle;
    scroll.getViewTreeObserver().addOnScrollChangedListener(this::refresh);
  }

  /**
   * 安装分组行为；分组头部必须是滚动内容里的普通视图（不是 RecyclerView 表头）。
   *
   * @param onToggle 折叠 / 展开后回调（宿主可据此调整可见性）
   */
  static GroupedList install(
      ScrollView scroll, List<Group> groups, Memory memory, Runnable onToggle) {
    if (scroll == null || groups == null || groups.isEmpty()) return null;
    GroupedList list = new GroupedList(scroll, memory, onToggle);
    for (Group group : groups) {
      if (group == null || group.key == null || group.header == null || group.body == null)
        continue;
      Boolean saved = list.memory.collapsed(group.key);
      group.collapsed = saved != null && saved;
      group.originalBackground = group.header.getBackground();
      list.groups.add(group);
      list.bind(group);
    }
    if (list.groups.isEmpty()) return null;
    list.refresh();
    return list;
  }

  List<String> keys() {
    List<String> keys = new ArrayList<>();
    for (Group group : groups) keys.add(group.key);
    return keys;
  }

  boolean collapsed(String key) {
    Group group = find(key);
    return group != null && group.collapsed;
  }

  /** 折叠 / 展开（与点标题等价）；未知键忽略。 */
  void toggle(String key) {
    Group group = find(key);
    if (group == null) return;
    group.collapsed = !group.collapsed;
    memory.setCollapsed(key, group.collapsed);
    apply(group);
    refresh();
    if (onToggle != null) onToggle.run();
  }

  /** 重新计算吸顶位移；宿主在布局变化后也可主动调用。 */
  void refresh() {
    if (groups.isEmpty() || scroll.getChildCount() == 0) return;
    View content = scroll.getChildAt(0);
    int scrollY = scroll.getScrollY();
    for (Group group : groups) {
      int headerTop = offsetInContent(content, group.header);
      if (headerTop == Integer.MIN_VALUE) continue;
      int headerHeight = group.header.getHeight();
      int groupBottom =
          group.body.getVisibility() == View.VISIBLE
              ? offsetInContent(content, group.body) + group.body.getHeight()
              : headerTop + headerHeight;
      int offset = StickyHeaderMath.offset(scrollY, headerTop, headerHeight, groupBottom);
      group.header.setTranslationY(offset);
      applyStickySkin(group, offset > 0);
    }
  }

  private void bind(Group group) {
    group.header.setClickable(true);
    group.header.setFocusable(true);
    if (group.originalBackground == null)
      group.header.setBackgroundResource(R.drawable.bg_action_plain);
    Drawable[] current = group.header.getCompoundDrawablesRelative();
    group.header.setCompoundDrawablesRelative(
        current[0], current[1], chevron(group.header, false), current[3]);
    group.header.setCompoundDrawablePadding(dp(group.header, 6));
    group.header.setOnClickListener(view -> toggle(group.key));
    apply(group);
  }

  private void apply(Group group) {
    group.body.setVisibility(group.collapsed ? View.GONE : View.VISIBLE);
    Drawable[] current = group.header.getCompoundDrawablesRelative();
    RotateDrawable next = chevron(group.header, group.collapsed);
    if (next != null) next.setLevel(group.collapsed ? 0 : 10000);
    group.header.setCompoundDrawablesRelative(current[0], current[1], next, current[3]);
    group.header.setCompoundDrawablePadding(dp(group.header, 6));
    group.header.setStateDescription(group.collapsed ? UiText.text("已收起") : UiText.text("已展开"));
  }

  /** 吸顶时补不透明底色，避免正文从标题后面透出；不吸顶时恢复原背景。 */
  private void applyStickySkin(Group group, boolean sticky) {
    TextView header = group.header;
    if (sticky) {
      if (!(header.getBackground() instanceof ColorDrawable))
        header.setBackground(new ColorDrawable(header.getContext().getColor(R.color.shell_bg)));
    } else if (header.getBackground() instanceof ColorDrawable) {
      if (group.originalBackground != null) header.setBackground(group.originalBackground);
      else header.setBackgroundResource(R.drawable.bg_action_plain);
    }
  }

  private Group find(String key) {
    for (Group group : groups) if (group.key.equals(key)) return group;
    return null;
  }

  /** 表头 / 正文到滚动内容顶部的偏移；不属于同一内容树时返回 MIN_VALUE。 */
  private static int offsetInContent(View content, View target) {
    int offset = 0;
    View current = target;
    while (current != null) {
      if (current == content) return offset;
      offset += current.getTop();
      View parent = current.getParent() instanceof View view ? view : null;
      if (parent == null) return Integer.MIN_VALUE;
      current = parent;
    }
    return Integer.MIN_VALUE;
  }

  private static RotateDrawable chevron(TextView header, boolean collapsed) {
    Context context = header.getContext();
    Drawable arrow = context.getDrawable(R.drawable.ic_ui2_chevron);
    if (arrow == null) return null;
    arrow = arrow.mutate();
    arrow.setTintList(ColorStateList.valueOf(context.getColor(R.color.shell_text_muted)));
    RotateDrawable rotate = new RotateDrawable();
    rotate.setDrawable(arrow);
    rotate.setPivotX(0.5f);
    rotate.setPivotY(0.5f);
    rotate.setFromDegrees(0f);
    rotate.setToDegrees(-90f);
    rotate.setLevel(collapsed ? 0 : 10000);
    return rotate;
  }

  private static int dp(View view, int value) {
    return Math.round(value * view.getResources().getDisplayMetrics().density);
  }

  /** 测试与非视图宿主使用：可按当前滚动位置主动重算。 */
  ViewTreeObserver.OnScrollChangedListener listener() {
    return this::refresh;
  }
}
