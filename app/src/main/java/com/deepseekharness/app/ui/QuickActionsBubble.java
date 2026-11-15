package com.deepseekharness.app.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.deepseekharness.app.R;
import com.deepseekharness.app.util.QuickActions;
import com.deepseekharness.app.util.UiText;

import java.util.function.Consumer;

/**
 * 顶栏「+」气泡弹窗：把下载记录、关于 DSHA 与启动页的运行环境 / 访问地址收拢到一个轻量气泡，
 * 点按「+」后从右上角锚点带缩放淡入动画展开；点按外部、返回键或再次点按「+」都会关闭。
 */
final class QuickActionsBubble {

  interface Listener {
    void onAction(QuickActions.Action action);

    void onDismissed();
  }

  private final PopupWindow window;

  private QuickActionsBubble(PopupWindow window) {
    this.window = window;
  }

  /** 在锚点（顶栏「+」）下方弹出气泡；launchPage 决定是否出现运行环境 / 访问地址两项。 */
  static QuickActionsBubble show(
      Activity activity, View anchor, boolean launchPage, Listener listener) {
    View root = LayoutInflater.from(activity).inflate(R.layout.popup_quick_actions, null, false);
    PopupWindow window = new PopupWindow(activity);
    window.setContentView(root);
    window.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
    window.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
    window.setFocusable(true);
    window.setOutsideTouchable(true);
    window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    QuickActionsBubble bubble = new QuickActionsBubble(window);
    bind(
        root,
        launchPage,
        action -> {
          if (window.isShowing()) window.dismiss();
          listener.onAction(action);
        },
        window::dismiss);
    window.setOnDismissListener(listener::onDismissed);
    bubble.showBelow(anchor);
    return bubble;
  }

  /**
   * 绑定文案、可见性与点击；拆出来便于单测直接驱动气泡内容，不依赖真实 PopupWindow 窗口。
   *
   * @param onAction 点按菜单项后的业务动作
   * @param dismiss 关闭气泡
   */
  static void bind(
      View root, boolean launchPage, Consumer<QuickActions.Action> onAction, Runnable dismiss) {
    bindRow(
        root,
        R.id.quick_action_downloads,
        R.id.quick_action_downloads_label,
        0,
        UiText.choose("下载记录", "Downloads"),
        null,
        QuickActions.Action.DOWNLOADS,
        onAction,
        dismiss);
    bindRow(
        root,
        R.id.quick_action_about,
        R.id.quick_action_about_label,
        0,
        UiText.choose("关于 DSHA", "About DSHA"),
        null,
        QuickActions.Action.ABOUT,
        onAction,
        dismiss);
    bindRow(
        root,
        R.id.quick_action_environment,
        R.id.quick_action_environment_label,
        0,
        UiText.choose("运行环境 · 检查与维护", "Runtime environment · inspect and repair"),
        null,
        QuickActions.Action.ENVIRONMENT,
        onAction,
        dismiss);
    bindRow(
        root,
        R.id.quick_action_address,
        R.id.quick_action_address_label,
        R.id.quick_action_address_sub,
        UiText.choose("访问地址", "Access addresses"),
        UiText.choose("查看本机与局域网地址", "Local and LAN addresses"),
        QuickActions.Action.ADDRESS,
        onAction,
        dismiss);

    int launchOnly = launchPage ? View.VISIBLE : View.GONE;
    root.findViewById(R.id.quick_action_environment).setVisibility(launchOnly);
    root.findViewById(R.id.quick_action_address).setVisibility(launchOnly);
    // 每两项之间都画一条分隔线；非启动页只有两项，只保留第一根。
    root.findViewById(R.id.quick_action_divider_downloads).setVisibility(View.VISIBLE);
    root.findViewById(R.id.quick_action_divider_about).setVisibility(launchOnly);
    root.findViewById(R.id.quick_action_divider_environment).setVisibility(launchOnly);
  }

  private static void bindRow(
      View root,
      int rowId,
      int labelId,
      int subtitleId,
      CharSequence label,
      CharSequence subtitle,
      QuickActions.Action action,
      Consumer<QuickActions.Action> onAction,
      Runnable dismiss) {
    View row = root.findViewById(rowId);
    ((TextView) root.findViewById(labelId)).setText(label);
    if (subtitleId != 0 && subtitle != null) {
      ((TextView) root.findViewById(subtitleId)).setText(subtitle);
    }
    row.setContentDescription(label);
    row.setOnClickListener(
        ignored -> {
          dismiss.run();
          onAction.accept(action);
        });
  }

  private void showBelow(View anchor) {
    View card = window.getContentView().findViewById(R.id.quick_actions_card);
    boolean rtl =
        anchor.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    float density = anchor.getResources().getDisplayMetrics().density;
    if (card != null) {
      card.setAlpha(0f);
      card.setScaleX(0.92f);
      card.setScaleY(0.92f);
      card.setTranslationY(-6f * density);
    }
    int width =
        anchor
            .getResources()
            .getDimensionPixelSize(com.deepseekharness.app.R.dimen.shell_quick_action_width);
    // 右对齐锚点右缘；RTL 下锚点在左侧，改为左对齐。
    window.showAsDropDown(anchor, rtl ? 0 : anchor.getWidth() - width, Math.round(6f * density));
    if (card != null) {
      card.post(
          () -> {
            card.setPivotX(rtl ? 0f : card.getWidth());
            card.setPivotY(0f);
            card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(new OvershootInterpolator(1.15f))
                .start();
          });
    }
  }

  void dismiss() {
    if (window.isShowing()) window.dismiss();
  }
}
