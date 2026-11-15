package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.SummaryText;
import com.deepseekharness.app.util.UiText;

/**
 * UI v5 方案 A：行内只保留一句摘要，完整功能说明收进 ⓘ 弹窗。
 *
 * <p>文案来源仍是原有已翻译的字符串资源或 UiText，不新增翻译键；只在显示层拆句。只有一句时
 * 原样显示、不加图标、不可点，避免给短说明制造多余入口。
 *
 * <p>动态状态文本（例如启动恢复结论）会在长文与短句之间来回切换：第一次挂 ⓘ 时记录原样式，
 * 回到短句时恢复，避免背景、内边距、折行数残留成按钮样。
 */
public final class InfoHint {
  private InfoHint() {}

  /** 被 {@link #bind} 改动前的样式；只在动态文本第一次从短变长时记录一次。 */
  private static final class Original {
    final int maxLines;
    final TextUtils.TruncateAt ellipsize;
    final Drawable background;
    final int compoundPadding;
    final int minHeight;
    final int gravity;
    final int layoutWidth;
    final CharSequence contentDescription;

    Original(TextView view) {
      maxLines = view.getMaxLines();
      ellipsize = view.getEllipsize();
      background = view.getBackground();
      compoundPadding = view.getCompoundDrawablePadding();
      minHeight = view.getMinHeight();
      gravity = view.getGravity();
      ViewGroup.LayoutParams params = view.getLayoutParams();
      layoutWidth = params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.width;
      contentDescription = view.getContentDescription();
    }

    void restore(TextView view) {
      view.setMaxLines(maxLines);
      view.setEllipsize(ellipsize);
      view.setBackground(background);
      view.setCompoundDrawablePadding(compoundPadding);
      view.setMinHeight(minHeight);
      view.setGravity(gravity);
      ViewGroup.LayoutParams params = view.getLayoutParams();
      if (params != null) {
        params.width = layoutWidth;
        view.setLayoutParams(params);
      }
      view.setContentDescription(contentDescription);
    }
  }

  private static final java.util.Map<TextView, Original> ORIGINALS = new java.util.WeakHashMap<>();

  /** 用 TextView 现有文字（通常来自布局里的 @string）作为完整说明。 */
  public static void attach(TextView view, @Nullable CharSequence title) {
    if (view == null) return;
    bind(view, title, view.getText());
  }

  public static void bind(
      TextView view, @Nullable CharSequence title, @Nullable CharSequence full) {
    if (view == null) return;
    SummaryText.Split split = SummaryText.split(full == null ? "" : full.toString());
    apply(view, title, split.summary, split.full, split.hasMore);
  }

  /**
   * 显式摘要 + 完整说明：摘要取既有或更短的文案（不新增翻译键），全文更长时挂 ⓘ。
   *
   * <p>给单句长文用——自动拆句找不到句号时会把整段原样留在页面上。
   */
  public static void bind(
      TextView view,
      @Nullable CharSequence title,
      @Nullable CharSequence summary,
      @Nullable CharSequence full) {
    if (view == null) return;
    String fullText = full == null ? "" : full.toString().trim();
    String summaryText = summary == null ? "" : summary.toString().trim();
    if (summaryText.isEmpty()) {
      bind(view, title, fullText);
      return;
    }
    apply(view, title, summaryText, fullText, fullText.length() > summaryText.length());
  }

  private static void apply(
      TextView view, CharSequence title, String summary, String full, boolean hasMore) {
    apply(view, title, summary, full, hasMore, false);
  }

  /**
   * 已有语义色容器（错误条 / 状态卡）上的 ⓘ：保留调用方背景、内边距与对齐，只把默认可见文字
   * 收成摘要并发 ⓘ。用于 UI 基准 ⑬——首屏只留短结论，三要素明细点开再看。
   */
  public static void bindOnSurface(
      TextView view,
      @Nullable CharSequence title,
      @Nullable CharSequence summary,
      @Nullable CharSequence full) {
    if (view == null) return;
    String fullText = full == null ? "" : full.toString().trim();
    String summaryText = summary == null ? "" : summary.toString().trim();
    if (summaryText.isEmpty()) {
      SummaryText.Split split = SummaryText.split(fullText);
      apply(view, title, split.summary, split.full, split.hasMore, true);
      return;
    }
    apply(view, title, summaryText, fullText, fullText.length() > summaryText.length(), true);
  }

  private static void apply(
      TextView view,
      CharSequence title,
      String summary,
      String full,
      boolean hasMore,
      boolean keepSurface) {
    if (!hasMore) {
      restore(view);
      view.setText(summary);
      view.setCompoundDrawablesRelative(null, null, null, null);
      view.setOnClickListener(null);
      view.setClickable(false);
      view.setContentDescription(null);
      return;
    }
    remember(view);
    Context context = view.getContext();
    view.setText(summary);
    view.setMaxLines(2);
    view.setEllipsize(TextUtils.TruncateAt.END);
    view.setCompoundDrawablesRelative(null, null, icon(context, view), null);
    if (!keepSurface) {
      ViewGroup.LayoutParams params = view.getLayoutParams();
      if (params != null && params.width == ViewGroup.LayoutParams.MATCH_PARENT) {
        params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        view.setLayoutParams(params);
      }
      view.setBackgroundResource(R.drawable.bg_action_plain);
      view.setMinHeight(dp(context, 32));
      view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    }
    view.setCompoundDrawablePadding(dp(context, 6));
    String tapHint = UiText.choose("（点按查看完整说明）", "(tap for details)");
    view.setContentDescription(UiText.format("%s %s", summary, tapHint));
    view.setOnClickListener(v -> show(context, v, title, full));
  }

  private static void remember(TextView view) {
    if (!ORIGINALS.containsKey(view)) ORIGINALS.put(view, new Original(view));
  }

  /** 动态文本回到短句时还原首次挂 ⓘ 前的样式；没有记录则什么都不做。 */
  private static void restore(TextView view) {
    Original original = ORIGINALS.remove(view);
    if (original != null) original.restore(view);
  }

  /** 无锚点调用（程序直接打开）：宽屏用收紧的居中弹窗，手机仍按窗口选底部面板 / 紧凑弹窗。 */
  public static void show(Context context, @Nullable CharSequence title, CharSequence full) {
    show(context, null, title, full);
  }

  /** 宽屏有锚点时说明贴着 ⓘ 出现；形态规则见 {@link com.deepseekharness.app.util.InfoSurfacePolicy}。 */
  public static void show(
      Context context, @Nullable View anchor, @Nullable CharSequence title, CharSequence full) {
    InfoSurface.show(
        context,
        anchor,
        title == null || title.length() == 0 ? UiText.text("说明") : title,
        full == null ? "" : full);
  }

  private static Drawable icon(Context context, TextView view) {
    Drawable icon = context.getDrawable(R.drawable.ic_onb_info);
    if (icon == null) return null;
    icon = icon.mutate();
    int size = Math.max(dp(context, 14), Math.round(view.getTextSize() * 1.1f));
    icon.setBounds(0, 0, size, size);
    icon.setTintList(ColorStateList.valueOf(context.getColor(R.color.shell_accent)));
    return icon;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
