package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.TransferListModel;
import com.google.android.material.button.MaterialButton;

/** 下载记录页的视图工厂：颜色只取 shell_* token，尺寸按 dp / sp 统一。 */
final class TransferUi {
  private TransferUi() {}

  enum ButtonStyle {
    FILLED,
    TONAL,
    OUTLINE,
    PLAIN
  }

  static int dp(Context context, float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  static int color(Context context, int id) {
    return context.getColor(id);
  }

  static int toneColor(Context context, TransferText.Tone tone) {
    return context.getColor(
        switch (tone) {
          case ACCENT -> R.color.shell_accent;
          case WARN -> R.color.shell_warn;
          case BAD -> R.color.shell_bad;
          case GOOD -> R.color.shell_good;
          case MUTED -> R.color.shell_text_muted;
        });
  }

  static int toneSoft(Context context, TransferText.Tone tone) {
    return context.getColor(
        switch (tone) {
          case ACCENT -> R.color.shell_accent_tonal;
          case WARN -> R.color.shell_warn_soft;
          case BAD -> R.color.shell_bad_soft;
          case GOOD -> R.color.shell_good_soft;
          case MUTED -> R.color.shell_raised;
        });
  }

  static TextView text(Context context, CharSequence value, float sp, int colorId, boolean bold) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    view.setTextColor(context.getColor(colorId));
    view.setIncludeFontPadding(false);
    view.setLineSpacing(dp(context, 3), 1f);
    if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return view;
  }

  static TextView sectionLabel(Context context, CharSequence value) {
    TextView label = text(context, value, 12.5f, R.color.shell_text_muted, true);
    label.setLetterSpacing(0.02f);
    label.setAccessibilityHeading(true);
    return label;
  }

  static LinearLayout vertical(Context context) {
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    return layout;
  }

  static LinearLayout horizontal(Context context) {
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.HORIZONTAL);
    layout.setGravity(Gravity.CENTER_VERTICAL);
    return layout;
  }

  /** 白底细边圆角卡片。 */
  static LinearLayout card(Context context, int padDp) {
    LinearLayout card = vertical(context);
    card.setBackgroundResource(R.drawable.bg_shell_card);
    int pad = dp(context, padDp);
    card.setPadding(pad, pad, pad, pad);
    card.setClipToOutline(true);
    return card;
  }

  static LinearLayout.LayoutParams match(Context context, int topDp) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(context, topDp);
    return params;
  }

  static ImageView icon(Context context, int drawable, int colorId, int sizeDp) {
    ImageView view = new ImageView(context);
    view.setImageResource(drawable);
    view.setImageTintList(ColorStateList.valueOf(context.getColor(colorId)));
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    view.setLayoutParams(new LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp)));
    return view;
  }

  /** 文件类型方块：类型色浅底 + 类型色图标。 */
  static ImageView tile(Context context, String name, int sizeDp) {
    TransferListModel.Kind kind = TransferListModel.kind(name);
    int drawable =
        switch (kind) {
          case CODE -> R.drawable.ic_transfer_code;
          case IMAGE -> R.drawable.ic_transfer_image;
          case DOCUMENT -> R.drawable.ic_transfer_document;
          case ARCHIVE -> R.drawable.ic_transfer_archive;
          case TEXT -> R.drawable.ic_transfer_text;
        };
    TransferText.Tone tone =
        switch (kind) {
          case CODE -> TransferText.Tone.ACCENT;
          case IMAGE -> TransferText.Tone.GOOD;
          case DOCUMENT -> TransferText.Tone.BAD;
          case ARCHIVE -> TransferText.Tone.WARN;
          case TEXT -> TransferText.Tone.MUTED;
        };
    ImageView view = new ImageView(context);
    view.setImageResource(drawable);
    view.setImageTintList(
        ColorStateList.valueOf(
            tone == TransferText.Tone.MUTED
                ? context.getColor(R.color.shell_text_secondary)
                : toneColor(context, tone)));
    view.setBackgroundResource(R.drawable.bg_transfer_tile);
    view.setBackgroundTintList(ColorStateList.valueOf(toneSoft(context, tone)));
    int pad = Math.round(sizeDp * 0.25f);
    view.setPadding(dp(context, pad), dp(context, pad), dp(context, pad), dp(context, pad));
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    view.setLayoutParams(new LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp)));
    return view;
  }

  /** 统一的药丸按钮；label 为 null 时是 44dp 圆形图标按钮，contentDescription 必填。 */
  static MaterialButton button(
      Context context,
      ButtonStyle style,
      CharSequence label,
      int iconRes,
      CharSequence description,
      int heightDp) {
    MaterialButton button = new MaterialButton(context);
    boolean iconOnly = label == null;
    int accent = context.getColor(R.color.shell_accent);
    int foreground =
        switch (style) {
          case FILLED -> context.getColor(R.color.shell_on_accent);
          case OUTLINE -> context.getColor(R.color.shell_text);
          default -> accent;
        };
    int background =
        switch (style) {
          case FILLED -> accent;
          case TONAL -> context.getColor(R.color.shell_accent_tonal);
          case OUTLINE -> context.getColor(R.color.shell_surface);
          case PLAIN -> android.graphics.Color.TRANSPARENT;
        };
    button.setBackgroundTintList(ColorStateList.valueOf(background));
    button.setTextColor(foreground);
    button.setRippleColor(ColorStateList.valueOf(context.getColor(R.color.shell_nav_ripple)));
    if (style == ButtonStyle.OUTLINE) {
      button.setStrokeColor(ColorStateList.valueOf(context.getColor(R.color.shell_line_strong)));
      button.setStrokeWidth(dp(context, 1));
    }
    button.setInsetTop(0);
    button.setInsetBottom(0);
    button.setMinimumHeight(0);
    button.setMinHeight(dp(context, heightDp));
    button.setMinimumWidth(0);
    button.setMinWidth(0);
    button.setAllCaps(false);
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    button.setIncludeFontPadding(false);
    button.setSingleLine(true);
    button.setEllipsize(TextUtils.TruncateAt.END);
    button.setCornerRadius(dp(context, heightDp / 2f));
    if (iconRes != 0) {
      button.setIconResource(iconRes);
      button.setIconTint(ColorStateList.valueOf(foreground));
      button.setIconSize(dp(context, iconOnly ? 20 : 18));
      button.setIconPadding(iconOnly ? 0 : dp(context, 6));
      // 图标按钮与带字按钮都让图标随文字居中；纯图标时文字为空即整体居中。
      button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
    }
    if (iconOnly) {
      button.setText("");
      button.setPadding(0, 0, 0, 0);
      button.setGravity(Gravity.CENTER);
      button.setLayoutParams(
          new LinearLayout.LayoutParams(dp(context, heightDp), dp(context, heightDp)));
      button.setContentDescription(description);
      if (android.os.Build.VERSION.SDK_INT >= 26) button.setTooltipText(description);
    } else {
      button.setText(label);
      button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
      if (description != null) button.setContentDescription(description);
    }
    return button;
  }

  static int iconFor(TransferListModel.Action action) {
    return switch (action) {
      case OPEN -> R.drawable.ic_onb_external;
      case SHARE -> R.drawable.ic_transfer_share;
      case PAUSE -> R.drawable.ic_transfer_pause;
      case RESUME -> R.drawable.ic_onb_play;
      case RETRY -> R.drawable.ic_plugin_restart;
      case CHOOSE -> R.drawable.ic_onb_folder;
      case CANCEL -> R.drawable.ic_onb_x;
      case REMOVE -> R.drawable.ic_transfer_remove;
    };
  }

  /** 1dp 分隔线，左缩进对齐行内文字。 */
  static View divider(Context context, int startDp) {
    View line = new View(context);
    line.setBackgroundColor(context.getColor(R.color.shell_line));
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(-1, Math.max(1, dp(context, 1)));
    params.setMarginStart(dp(context, startDp));
    line.setLayoutParams(params);
    line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return line;
  }
}
