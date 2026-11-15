package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.SheetGeometry;
import com.deepseekharness.app.util.UiText;

/**
 * 底部抽屉（G1）：拖拽手柄 + 返回键先收抽屉 + 短屏全高可滚动。
 *
 * <p>与 {@link CardSheet}（说明 / 选择列表）分工：抽屉用于「详情 / 筛选 / 动作入口」这类
 * 结构化内容，始终贴底、整宽，内容区可滚动，页脚常驻。规则：
 *
 * <ul>
 *   <li>返回键先关闭抽屉，不结束宿主页面；抽屉内部没有二级返回栈。
 *   <li>点遮罩只关闭抽屉，不执行任何动作；破坏性动作仍走各自的确认弹窗，且只能由用户
 *       显式点按按钮触发（遮罩 / 拖动 / 返回都只是取消）。
 *   <li>短屏（高度 &lt; 480dp，例如手机横屏）抽屉占满可用高度，正文滚动、页脚常驻；
 *       普通屏幕按内容收紧，但首屏不超过可用高度的约 72%，超出才滚动、页脚常驻。
 *   <li>动效统一走 {@link UiMotion}；系统关闭动画时直接落到终态。
 * </ul>
 */
final class BottomSheetCard {
  private BottomSheetCard() {}

  /** 已显示的抽屉；调用方据此关闭、复用页面数据。 */
  static final class Sheet {
    private final AlertDialog dialog;
    private final CardPage page;
    private final View handle;
    private final View container;

    private Sheet(AlertDialog dialog, CardPage page, View handle, View container) {
      this.dialog = dialog;
      this.page = page;
      this.handle = handle;
      this.container = container;
    }

    /** 返回键钩子：抽屉开着就只关抽屉并返回 true。 */
    boolean handleBack() {
      if (dialog.isShowing()) {
        dismiss();
        return true;
      }
      return false;
    }

    void dismiss() {
      if (dialog.isShowing()) dialog.dismiss();
    }

    boolean isShowing() {
      return dialog.isShowing();
    }

    CardPage page() {
      return page;
    }

    View handle() {
      return handle;
    }

    View container() {
      return container;
    }

    AlertDialog dialog() {
      return dialog;
    }
  }

  /** 贴底显示一个 {@link CardPage}；页面根视图的滚动 / 页脚语义保持不变。 */
  static Sheet show(Context context, CardPage page) {
    if (context == null || page == null) return null;
    float density = context.getResources().getDisplayMetrics().density;
    int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
    boolean shortScreen = screenHeight / density < SheetGeometry.TALL_MIN_HEIGHT;
    int lift =
        hostsFloatingNav(context)
            ? context.getResources().getDimensionPixelSize(R.dimen.shell_nav_clearance)
            : 0;
    int maxHeight =
        shortScreen
            ? Math.max(dp(context, 240), screenHeight - lift - dp(context, 12))
            : Math.max(
                dp(context, 240),
                dp(
                    context,
                    SheetGeometry.bottomDrawerMaxHeight(
                        Math.round(screenHeight / density), Math.round(lift / density))));

    page.root.setBackgroundResource(R.drawable.bg_shell_sheet);
    page.root.setClipToOutline(true);
    page.scroll.setFillViewport(false);
    final LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, -2);
    page.scroll.setLayoutParams(scrollParams);

    View handle = handle(context);
    page.root.addView(handle, 0, new LinearLayout.LayoutParams(-1, dp(context, 40)));

    final FrameLayout.LayoutParams rootParams =
        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2);
    FrameLayout container =
        new FrameLayout(context) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int limit = maxHeight;
            if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
              limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
            // 先按内容收紧；超过上限或短屏时，把正文改成权重并让整页占满可用高度。
            scrollParams.height = -2;
            scrollParams.weight = 0;
            rootParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            super.onMeasure(
                widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            boolean fill = shortScreen || getMeasuredHeight() >= limit;
            if (!fill) return;
            scrollParams.height = 0;
            scrollParams.weight = 1;
            rootParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            super.onMeasure(
                widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.EXACTLY));
          }
        };
    container.addView(page.root, rootParams);

    AlertDialog dialog =
        new DshaDialogBuilder(context).keepWindowSize().setView(container).create();
    if (dialog.getWindow() != null) {
      dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      dialog.getWindow().setGravity(Gravity.BOTTOM);
      dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, -2);
      dialog.getWindow().setWindowAnimations(R.style.Animation_DSHA_Sheet);
      if (lift > 0) {
        WindowManager.LayoutParams attributes = dialog.getWindow().getAttributes();
        attributes.y = lift;
        dialog.getWindow().setAttributes(attributes);
      }
    }
    // 遮罩点击只取消抽屉，不执行任何动作；动作只能由用户点抽屉里的按钮触发。
    dialog.setCanceledOnTouchOutside(true);
    dialog.setCancelable(true);
    final Sheet sheet = new Sheet(dialog, page, handle, container);
    dialog.setOnKeyListener(
        (ignored, keyCode, event) -> {
          if (keyCode != android.view.KeyEvent.KEYCODE_BACK) return false;
          if (event.getAction() == android.view.KeyEvent.ACTION_UP) sheet.dismiss();
          // 按下与抬起都消费：返回只退一层抽屉，不落到宿主 Activity。
          return true;
        });
    handle.setOnClickListener(view -> sheet.dismiss());
    attachDrag(context, sheet, handle, container);
    dialog.show();
    if (dialog.getWindow() != null) {
      dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, -2);
      dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    return sheet;
  }

  /** 手柄：38x4dp 的拖动条；可点击关闭，也可向下拖动超过 96dp 关闭。 */
  private static View handle(Context context) {
    FrameLayout box = new FrameLayout(context);
    box.setMinimumHeight(dp(context, 40));
    box.setFocusable(true);
    box.setClickable(true);
    box.setContentDescription(UiText.choose("拖动或点击收起抽屉", "Drag down or tap to close the drawer"));
    View pill = new View(context);
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(context.getColor(R.color.shell_line_strong));
    shape.setCornerRadius(dp(context, 2));
    pill.setBackground(shape);
    pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    box.addView(
        pill, new FrameLayout.LayoutParams(dp(context, 38), dp(context, 4), Gravity.CENTER));
    return box;
  }

  private static void attachDrag(Context context, Sheet sheet, View handle, View container) {
    final float[] startY = {0f};
    final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    handle.setOnTouchListener(
        (view, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              startY[0] = event.getRawY();
              container.animate().cancel();
              return true;
            case MotionEvent.ACTION_MOVE:
              float dy = Math.max(0f, event.getRawY() - startY[0]);
              container.setTranslationY(dy);
              return true;
            case MotionEvent.ACTION_UP:
              float moved = Math.max(0f, event.getRawY() - startY[0]);
              if (moved < touchSlop) {
                sheet.dismiss();
              } else if (moved >= dp(context, 96)) {
                sheet.dismiss();
              } else {
                settle(container);
              }
              return true;
            case MotionEvent.ACTION_CANCEL:
              settle(container);
              return true;
            default:
              return false;
          }
        });
  }

  private static void settle(View container) {
    if (!UiMotion.animationsEnabled(container.getContext())) {
      container.setTranslationY(0f);
      return;
    }
    container
        .animate()
        .translationY(0f)
        .setDuration(180L)
        .setInterpolator(UiMotion.standard())
        .start();
  }

  /** 只有 MainActivity 的手机外壳有叠在内容上的悬浮胶囊；抽屉要让出一条高度。 */
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
}
