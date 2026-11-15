package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RotateDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 折叠披露（替代 ⓘ 弹层）：摘要保留在原 TextView 上，行尾加展开箭头；展开后才创建
 * 完整说明与动作，折叠时不创建隐藏子视图（读屏不会读到）。
 *
 * <p>用法：宿主先把摘要写进现有 TextView（保留 id、样式与淡入逻辑），再调用 {@link #bind}。
 * 展开状态由 {@link State} 持有——旋转、语言切换、每秒刷新都保留；用户折叠后不再被
 * {@link Model#defaultExpanded} 覆盖。异常或放宽档位由宿主传 {@code defaultExpanded=true} 默认展开。
 *
 * <p>一屏只用这一种披露方式：迁移过的页面不再调用 InfoHint。
 */
final class DisclosureCard {
  private DisclosureCard() {}

  /** 一次披露的数据；摘要由宿主的 TextView 承载，这里只描述展开区。 */
  static final class Model {
    /** 屏内唯一状态键；旋转/刷新后按它恢复。 */
    String key;

    /** 展开区完整说明；与 {@link #actions} 都为空时视为无需披露。 */
    CharSequence detail;

    /** 收起态也始终可见的一行安全后果。 */
    CharSequence consequence;

    /** 后果文字颜色（默认次要文字）。 */
    int consequenceTone = R.color.shell_text_secondary;

    /** 展开区动作（由宿主创建，绑定后由本组件持有并显示）。 */
    View[] actions = new View[0];

    /** 异常 / 放宽状态默认展开。 */
    boolean defaultExpanded;
  }

  /** 展开状态的外部持有者；返回 {@code null} 表示尚未记录。 */
  interface State {
    Boolean expanded(String key);

    void setExpanded(String key, boolean value);
  }

  /** 屏内展开状态：Map + Bundle 存取，不缓存语言。 */
  static final class Memory implements State {
    private final Map<String, Boolean> values = new HashMap<>();

    @Override
    public Boolean expanded(String key) {
      return values.get(key);
    }

    @Override
    public void setExpanded(String key, boolean value) {
      values.put(key, value);
    }

    /** 写入 Fragment/Activity 的 onSaveInstanceState。 */
    void save(Bundle out, String name) {
      Bundle bundle = new Bundle();
      for (Map.Entry<String, Boolean> entry : values.entrySet())
        bundle.putBoolean(entry.getKey(), Boolean.TRUE.equals(entry.getValue()));
      out.putBundle(name, bundle);
    }

    /** 从 onSaveInstanceState 恢复；没有记录时返回空状态。 */
    static Memory restore(Bundle in, String name) {
      Memory memory = new Memory();
      Bundle bundle = in == null ? null : in.getBundle(name);
      if (bundle == null) return memory;
      for (String key : bundle.keySet()) memory.values.put(key, bundle.getBoolean(key));
      return memory;
    }
  }

  private static final Map<TextView, Binding> BINDINGS = new WeakHashMap<>();

  /** 详情插入摘要之后（默认位置）。 */
  static void bind(TextView summary, Model model, State state, Runnable onToggle) {
    bind(summary, model, state, onToggle, null);
  }

  /**
   * @param insertAfter 详情容器插入位置；摘要与目标之间有必须保持顺序的兄弟视图时传入
   *     （例如启动页的活动轨道必须紧跟副标题）。为 null 时紧跟摘要。
   */
  static void bind(
      TextView summary, Model model, State state, Runnable onToggle, View insertAfter) {
    if (summary == null) return;
    boolean disclose =
        model != null && (!TextUtils.isEmpty(model.detail) || model.actions.length > 0);
    Binding binding = BINDINGS.get(summary);
    if (!disclose) {
      if (binding != null) binding.release();
      return;
    }
    if (binding == null) binding = Binding.create(summary, insertAfter);
    if (binding == null) return;
    Boolean saved = state == null ? null : state.expanded(model.key);
    boolean expanded = saved == null ? model.defaultExpanded : saved;
    if (state != null && saved == null) state.setExpanded(model.key, expanded);
    binding.apply(model, state, onToggle, expanded);
  }

  /** 供测试与宿主查询当前是否展开。 */
  static boolean expanded(TextView summary) {
    Binding binding = summary == null ? null : BINDINGS.get(summary);
    return binding != null && binding.expanded;
  }

  private static final class Binding {
    final TextView summary;
    final LinearLayout holder;
    final LinearLayout details;
    final TextView consequence;
    final Drawable[] original;
    Model model;
    State state;
    Runnable onToggle;
    boolean expanded;
    CharSequence renderedDetail;
    View[] renderedActions = new View[0];

    private Binding(TextView summary, LinearLayout holder) {
      this.summary = summary;
      this.holder = holder;
      this.details = new LinearLayout(summary.getContext());
      this.details.setOrientation(LinearLayout.VERTICAL);
      this.consequence = new TextView(summary.getContext());
      this.original = summary.getCompoundDrawablesRelative();
    }

    static Binding create(TextView summary, View insertAfter) {
      ViewGroup parent;
      int index;
      if (summary.getParent() instanceof LinearLayout direct
          && direct.getOrientation() == LinearLayout.VERTICAL) {
        parent = direct;
        index =
            insertAfter != null && insertAfter.getParent() == direct
                ? direct.indexOfChild(insertAfter) + 1
                : direct.indexOfChild(summary) + 1;
      } else if (insertAfter != null && insertAfter.getParent() instanceof ViewGroup host) {
        // 摘要在水平行里（例如「分组标签 + 说明」行）：详情容器插到该行之后的竖向容器。
        parent = host;
        index = host.indexOfChild(insertAfter) + 1;
      } else {
        return null;
      }
      Context context = summary.getContext();
      LinearLayout holder = new LinearLayout(context);
      holder.setOrientation(LinearLayout.VERTICAL);
      Binding binding = new Binding(summary, holder);
      binding.consequence.setTextSize(12.5f);
      binding.consequence.setIncludeFontPadding(false);
      binding.consequence.setLineSpacing(dp(context, 2), 1f);
      binding.consequence.setMaxLines(3);
      binding.consequence.setEllipsize(android.text.TextUtils.TruncateAt.END);
      binding.consequence.setPadding(0, dp(context, 5), 0, 0);
      binding.consequence.setVisibility(View.GONE);
      holder.addView(binding.consequence, new LinearLayout.LayoutParams(-1, -2));
      binding.details.setVisibility(View.GONE);
      holder.addView(binding.details, new LinearLayout.LayoutParams(-1, -2));
      parent.addView(holder, index, new LinearLayout.LayoutParams(-1, -2));
      BINDINGS.put(summary, binding);
      return binding;
    }

    void apply(Model model, State state, Runnable onToggle, boolean expanded) {
      this.model = model;
      this.state = state;
      this.onToggle = onToggle;
      this.expanded = expanded;
      Context context = summary.getContext();
      CharSequence consequenceText =
          TextUtils.isEmpty(model.consequence) ? "" : model.consequence.toString().trim();
      if (TextUtils.isEmpty(consequenceText)) {
        consequence.setVisibility(View.GONE);
      } else {
        consequence.setText(consequenceText);
        consequence.setTextColor(context.getColor(model.consequenceTone));
        consequence.setVisibility(View.VISIBLE);
      }
      summary.setClickable(true);
      summary.setFocusable(true);
      summary.setOnClickListener(view -> toggle());
      Drawable[] current = summary.getCompoundDrawablesRelative();
      summary.setCompoundDrawablesRelative(
          current[0], current[1], chevron(context, expanded), current[3]);
      summary.setCompoundDrawablePadding(dp(context, 6));
      summary.setStateDescription(
          expanded ? UiText.choose("已展开", "Expanded") : UiText.choose("已收起", "Collapsed"));
      details.animate().cancel();
      if (expanded) {
        details.setVisibility(View.VISIBLE);
        renderDetails();
      } else {
        clearDetails();
        details.setVisibility(View.GONE);
      }
      holder.setVisibility(
          expanded || !TextUtils.isEmpty(consequenceText) ? View.VISIBLE : View.GONE);
    }

    void toggle() {
      if (model == null) return;
      expanded = !expanded;
      if (state != null) state.setExpanded(model.key, expanded);
      Context context = summary.getContext();
      Drawable[] current = summary.getCompoundDrawablesRelative();
      int fromLevel = current[2] instanceof RotateDrawable rotating ? rotating.getLevel() : 0;
      RotateDrawable next = chevron(context, false);
      if (next != null) next.setLevel(fromLevel);
      summary.setCompoundDrawablesRelative(current[0], current[1], next, current[3]);
      summary.setCompoundDrawablePadding(dp(context, 6));
      UiMotion.rotateChevron(context, next, expanded);
      summary.setStateDescription(
          expanded ? UiText.choose("已展开", "Expanded") : UiText.choose("已收起", "Collapsed"));
      if (expanded) {
        details.animate().cancel();
        details.setVisibility(View.VISIBLE);
        renderDetails();
        holder.setVisibility(View.VISIBLE);
        UiMotion.reveal(details);
      } else {
        UiMotion.conceal(
            details,
            () -> {
              // 收起动画期间又点开：以新状态为准，不能把刚渲染的详情清掉。
              if (expanded) return;
              clearDetails();
              details.setVisibility(View.GONE);
            });
        holder.setVisibility(TextUtils.isEmpty(model.consequence) ? View.GONE : View.VISIBLE);
      }
      if (onToggle != null) onToggle.run();
    }

    private void renderDetails() {
      if (model == null) return;
      if (TextUtils.equals(renderedDetail, model.detail) && renderedActions == model.actions)
        return;
      renderedDetail = model.detail;
      renderedActions = model.actions;
      details.removeAllViews();
      Context context = summary.getContext();
      if (!TextUtils.isEmpty(model.detail)) {
        TextView body = new TextView(context);
        body.setTextSize(13f);
        body.setTextColor(context.getColor(R.color.shell_text_secondary));
        body.setIncludeFontPadding(false);
        body.setLineSpacing(dp(context, 3), 1f);
        body.setText(model.detail);
        body.setTextIsSelectable(true);
        body.setPadding(0, dp(context, 6), 0, 0);
        details.addView(body, new LinearLayout.LayoutParams(-1, -2));
      }
      if (model.actions.length > 0) {
        OnboardingKit.FlowRow actions =
            new OnboardingKit.FlowRow(context, dp(context, 8), dp(context, 8), Gravity.START);
        actions.setPadding(0, dp(context, 6), 0, 0);
        for (View action : model.actions) {
          if (action == null) continue;
          // 动作视图由宿主创建、跨多次渲染复用：先脱离旧容器，否则 addView 抛
          // IllegalStateException「already has a parent」（真机点徽章崩溃的根因）。
          detach(action);
          actions.addView(
              action, new ViewGroup.LayoutParams(-2, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        details.addView(actions, new LinearLayout.LayoutParams(-1, -2));
      }
    }

    private void clearDetails() {
      details.animate().cancel();
      detachAllActions();
      details.removeAllViews();
      renderedDetail = null;
      renderedActions = new View[0];
    }

    /** 收起后把动作视图从旧 FlowRow 摘下，下一次展开才能重新 addView。 */
    private void detachAllActions() {
      if (model == null) return;
      for (View action : model.actions) detach(action);
    }

    private static void detach(View action) {
      if (action != null && action.getParent() instanceof ViewGroup parent)
        parent.removeView(action);
    }

    void release() {
      detachAllActions();
      ViewGroup parent = (ViewGroup) holder.getParent();
      if (parent != null) parent.removeView(holder);
      summary.setOnClickListener(null);
      summary.setClickable(false);
      summary.setFocusable(false);
      summary.setCompoundDrawablesRelative(original[0], original[1], original[2], original[3]);
      summary.setStateDescription(null);
      BINDINGS.remove(summary);
    }

    private static RotateDrawable chevron(Context context, boolean expanded) {
      Drawable arrow = context.getDrawable(R.drawable.ic_ui2_chevron);
      if (arrow == null) return null;
      arrow = arrow.mutate();
      arrow.setTintList(ColorStateList.valueOf(context.getColor(R.color.shell_text_muted)));
      RotateDrawable rotate = new RotateDrawable();
      rotate.setDrawable(arrow);
      rotate.setPivotX(0.5f);
      rotate.setPivotY(0.5f);
      rotate.setFromDegrees(0f);
      rotate.setToDegrees(90f);
      rotate.setLevel(expanded ? 10000 : 0);
      return rotate;
    }
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
