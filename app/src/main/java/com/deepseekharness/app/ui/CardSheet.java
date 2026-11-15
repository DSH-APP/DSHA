package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.SheetGeometry;

/**
 * 应用卡片弹层。形态由 {@link SheetGeometry} 按窗口宽、高与内容档决定（规则见
 * docs/design/ui-template-baseline-20261103/README.md §9）：手机竖屏贴底，平板按内容定宽居中，
 * 短屏（手机横屏）收窄居中；短屏的长表单分两栏，左栏标题与操作，右栏正文滚动。 内容短时收紧，超过上限时只滚正文，页脚常驻。
 */
final class CardSheet {
  /** show() 时恢复 create() 算出的窗口宽度（px）；居中形态才有值。 */
  private static final java.util.Map<androidx.appcompat.app.AlertDialog, Integer> widths =
      new java.util.WeakHashMap<>();

  static androidx.appcompat.app.AlertDialog create(Context context, CardPage page) {
    return create(context, page, SheetGeometry.Size.M);
  }

  static androidx.appcompat.app.AlertDialog create(
      Context context, CardPage page, SheetGeometry.Size size) {
    android.content.res.Configuration c = context.getResources().getConfiguration();
    SheetGeometry.Mode mode = SheetGeometry.mode(c.screenWidthDp, c.screenHeightDp, size);
    boolean bottom = mode == SheetGeometry.Mode.BOTTOM;
    // 贴底弹层只在主界面让出悬浮胶囊（shell_nav_clearance：手机 74dp，sw600 为 0）；终端与独立页面没有胶囊。
    int lift =
        bottom && hostsFloatingNav(context)
            ? context.getResources().getDimensionPixelSize(R.dimen.shell_nav_clearance)
            : 0;
    float density = context.getResources().getDisplayMetrics().density;
    final int maxWidth = dp(context, SheetGeometry.width(mode, size, c.screenWidthDp));
    final int maxHeight =
        dp(context, SheetGeometry.maxHeight(mode, c.screenHeightDp, Math.round(lift / density)));
    page.root.setBackgroundResource(bottom ? R.drawable.bg_shell_sheet : R.drawable.bg_shell_card);
    page.scroll.setFillViewport(false);
    LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, -2);
    page.scroll.setLayoutParams(scrollParams);
    if (mode == SheetGeometry.Mode.SIDE) splitColumns(context, page, maxWidth);
    final boolean side = mode == SheetGeometry.Mode.SIDE;
    FrameLayout container =
        new FrameLayout(context) {
          @Override
          protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            // 页脚按钮在 create() 之后才由调用方添加；首次测量前再决定横排。
            arrangeFooter(page, mode);
          }

          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int limit = maxHeight;
            if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
              limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
            if (!bottom)
              widthSpec =
                  View.MeasureSpec.makeMeasureSpec(
                      Math.min(maxWidth, View.MeasureSpec.getSize(widthSpec)),
                      View.MeasureSpec.EXACTLY);
            if (side) {
              // 两栏等高：行按内容收紧到上限，左右栏各自滚动。
              super.onMeasure(
                  widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
              return;
            }
            // 短列表按内容收紧；只有超过上限才把滚动区改成权重，避免「取消」被顶到屏幕底。
            scrollParams.height = -2;
            scrollParams.weight = 0;
            super.onMeasure(
                widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            if (getMeasuredHeight() >= limit) {
              scrollParams.height = 0;
              scrollParams.weight = 1;
              super.onMeasure(
                  widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.EXACTLY));
            }
          }
        };
    container.addView(page.root, new FrameLayout.LayoutParams(-1, -2));
    View content = container;
    if (!bottom) {
      FrameLayout host = new FrameLayout(context);
      host.addView(container, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
      content = host;
    }
    // 尺寸完全由这里决定，不套用 DshaDialogBuilder 的默认对话框宽度。
    var dialog = new DshaDialogBuilder(context).keepWindowSize().setView(content).create();
    // 贴底与半屏动效必须在第一次 show() 前设置，否则首帧会先出现在屏幕中央再跳到底部。
    if (dialog.getWindow() != null) {
      if (!bottom) {
        int width =
            Math.min(
                maxWidth, context.getResources().getDisplayMetrics().widthPixels - dp(context, 24));
        widths.put(dialog, width);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setGravity(Gravity.CENTER);
        dialog.getWindow().setLayout(width, -2);
      } else {
        dialog.getWindow().setGravity(Gravity.BOTTOM);
        dialog.getWindow().setLayout(-1, -2);
        dialog.getWindow().setWindowAnimations(R.style.Animation_DSHA_Sheet);
        if (lift > 0) {
          android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
          lp.y = lift;
          dialog.getWindow().setAttributes(lp);
        }
      }
    }
    return dialog;
  }

  static void show(androidx.appcompat.app.AlertDialog dialog, Context context) {
    dialog.show();
    if (dialog.getWindow() != null) {
      dialog
          .getWindow()
          .setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      Integer width = widths.get(dialog);
      if (width != null) dialog.getWindow().setLayout(width, -2);
    }
  }

  /**
   * 短屏长表单：root 改为横排两栏。左栏（≈36%，raised 底）放标题、副标题与竖排页脚，标题区可滚动；
   * 右栏是原来的正文滚动区。调用方之后往 page.footer / page.content 添加的视图照常生效。
   */
  private static void splitColumns(Context context, CardPage page, int cardWidthPx) {
    float density = context.getResources().getDisplayMetrics().density;
    int sideWidth = dp(context, SheetGeometry.sideColumnWidth(Math.round(cardWidthPx / density)));
    page.root.removeView(page.scroll);
    page.root.removeView(page.footer);
    page.root.setOrientation(LinearLayout.HORIZONTAL);
    // 左栏 raised 底色不能盖过卡片圆角。
    page.root.setClipToOutline(true);
    LinearLayout left = page.column();
    left.setBackgroundResource(R.color.shell_raised);
    ScrollView headScroll = new ScrollView(context);
    LinearLayout head = page.column();
    head.setPadding(page.dp(16), page.dp(16), page.dp(16), 0);
    for (TextView view : new TextView[] {page.heading, page.subtitle}) {
      if (view == null) continue;
      page.content.removeView(view);
      head.addView(view);
    }
    headScroll.addView(head);
    left.addView(headScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    page.footer.setPadding(page.dp(16), page.dp(4), page.dp(16), page.dp(12));
    left.addView(page.footer, new LinearLayout.LayoutParams(-1, -2));
    page.root.addView(left, new LinearLayout.LayoutParams(sideWidth, -1));
    View rule = new View(context);
    rule.setBackgroundColor(context.getColor(R.color.shell_line));
    page.root.addView(rule, new LinearLayout.LayoutParams(page.dp(1), -1));
    page.content.setPadding(page.dp(16), page.dp(16), page.dp(16), page.dp(16));
    page.root.addView(page.scroll, new LinearLayout.LayoutParams(0, -2, 1));
  }

  /**
   * 页脚恰好两个可见按钮时横排、等宽，主操作在右（Android 习惯）。自定义按钮行、单个或三个以上按钮保持原样。
   */
  static void arrangeFooter(CardPage page, SheetGeometry.Mode mode) {
    LinearLayout footer = page.footer;
    java.util.List<View> buttons = new java.util.ArrayList<>();
    for (int i = 0; i < footer.getChildCount(); i++) {
      View child = footer.getChildAt(i);
      if (child.getVisibility() == View.GONE) continue;
      if (!(child instanceof Button)) return;
      buttons.add(child);
    }
    if (!SheetGeometry.footerInRow(mode, buttons.size())) return;
    footer.setOrientation(LinearLayout.HORIZONTAL);
    View first = buttons.get(0), second = buttons.get(1);
    if (page.primaryButtons.contains(first) && !page.primaryButtons.contains(second)) {
      footer.removeView(first);
      footer.addView(first, footer.indexOfChild(second) + 1);
      View swap = first;
      first = second;
      second = swap;
    }
    int height = page.dp(48);
    for (View button : new View[] {first, second}) {
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, height, 1);
      p.topMargin = page.dp(10);
      if (button == second) p.leftMargin = page.dp(10);
      button.setLayoutParams(p);
      button.setMinimumHeight(0);
      if (button instanceof TextView) {
        ((TextView) button).setMinHeight(0);
        ((TextView) button).setSingleLine(true);
        ((TextView) button).setEllipsize(android.text.TextUtils.TruncateAt.END);
      }
    }
  }

  /** 只有 MainActivity 的手机外壳有叠在内容上的悬浮胶囊。 */
  private static boolean hostsFloatingNav(Context context) {
    while (context instanceof android.content.ContextWrapper) {
      if (context instanceof MainActivity) return true;
      if (context instanceof android.app.Activity) return false;
      context = ((android.content.ContextWrapper) context).getBaseContext();
    }
    return false;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  static void choices(
      Context context,
      String title,
      java.util.function.Supplier<java.util.List<String>> source,
      java.util.function.IntConsumer selected) {
    CardPage page = new CardPage(context, title, "", true);
    var dialog = create(context, page, SheetGeometry.Size.S);
    java.util.List<String> values = source.get();
    for (int i = 0; i < values.size(); i++) {
      final int position = i;
      page.entry(
          page.content,
          values.get(i),
          "",
          R.drawable.ic_ui2_box,
          () -> {
            dialog.dismiss();
            selected.accept(position);
          });
    }
    page.button(
        page.footer,
        com.deepseekharness.app.util.UiText.choose("取消", "Cancel"),
        false,
        dialog::dismiss);
    show(dialog, context);
  }

  static void show(Context context, String title, String body) {
    CardPage page = new CardPage(context, title, "", true);
    TextView text = page.text(body, 13, R.color.text_secondary);
    text.setPadding(0, page.dp(14), 0, 0);
    text.setTextIsSelectable(true);
    text.setLineSpacing(page.dp(5), 1);
    page.content.addView(text);
    var dialog = create(context, page);
    page.button(
        page.footer,
        com.deepseekharness.app.util.UiText.choose("关闭", "Close"),
        false,
        dialog::dismiss);
    show(dialog, context);
  }
}
