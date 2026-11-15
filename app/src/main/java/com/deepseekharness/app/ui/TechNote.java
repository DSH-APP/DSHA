package com.deepseekharness.app.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/**
 * 页尾技术说明块（B3）：一行摘要「技术说明」+ 展开后的事实明细。
 *
 * <p>沿用 {@link DisclosureCard} 的折叠语义，所以整页始终只有一种披露方式：不新建 ⓘ，也不
 * 把说明塞进隐藏子视图；收起时摘要仍在，展开后才创建明细与动作。内容必须来自真实状态
 * （运行方式、dsh 版本、包名、系统版本等），不写推测值。
 */
final class TechNote {
  /** 技术说明的数据；{@code detail} 为空时视为无需披露，不创建折叠。 */
  static final class Model {
    String key = "tech-note";

    /** 收起态一行；默认「技术说明」。 */
    CharSequence summary;

    /** 展开后的事实明细（可多行）。 */
    CharSequence detail;

    /** 摘要样式；页尾默认 {@code R.style.ShellFooter}，启动页可传自己的 meta 样式。 */
    int style = R.style.ShellFooter;
  }

  private TechNote() {}

  static CharSequence summary() {
    return UiText.text("技术说明");
  }

  /**
   * 在页尾容器末尾创建技术说明并绑定折叠。
   *
   * @return 摘要视图；明细为空时也返回该视图（只是没有折叠）
   */
  static TextView attach(
      LinearLayout parent, Model model, DisclosureCard.State state, Runnable onToggle) {
    if (parent == null || model == null) return null;
    TextView view = new TextView(parent.getContext(), null, 0, model.style);
    view.setText(model.summary == null ? summary() : model.summary);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(parent.getContext(), 22);
    parent.addView(view, params);
    return bind(view, model, state, onToggle);
  }

  /** 绑定已有摘要视图（页面已用 XML 定义页尾时使用）。 */
  static TextView bind(TextView view, Model model, DisclosureCard.State state, Runnable onToggle) {
    if (view == null || model == null) return view;
    DisclosureCard.Model disclosure = new DisclosureCard.Model();
    disclosure.key = model.key;
    disclosure.detail = model.detail;
    DisclosureCard.bind(view, disclosure, state, onToggle);
    return view;
  }

  /** 是否需要披露：只有真实明细才值得折叠，空明细保持一行摘要。 */
  static boolean hasDetail(Model model) {
    return model != null && model.detail != null && model.detail.length() > 0;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
