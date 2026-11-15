package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.SystemLanguage;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import java.util.List;
import java.util.Locale;

/**
 * 界面语言选择（方案 A）：「跟随系统」作为第一格并写明系统当前解析的语言，其后十种语言；平板四列、手机两列，
 * 每格是母语名 + 当前界面语言里的名称。点选即生效，与原单选列表行为一致（关闭弹窗后切换）。
 */
final class LanguagePicker {
  private LanguagePicker() {}

  static void show(Context ctx, String preference) {
    float density = ctx.getResources().getDisplayMetrics().density;
    java.util.function.IntUnaryOperator dp = v -> Math.round(v * density);
    boolean followSystem = UiLanguagePreference.followsSystem(preference);
    String systemLanguage = UiLanguagePreference.resolveLanguage(SystemLanguage.tag());
    String uiLanguage = UiText.language();
    LinearLayout body = new LinearLayout(ctx);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp.applyAsInt(20), dp.applyAsInt(4), dp.applyAsInt(20), dp.applyAsInt(4));
    androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
    java.util.function.Consumer<String> pick =
        value -> {
          androidx.appcompat.app.AlertDialog dialog = holder[0];
          if (dialog == null) return;
          boolean same =
              UiLanguagePreference.followsSystem(value)
                  ? followSystem
                  : !followSystem && value.equals(preference);
          // 与原实现一致：先关弹窗，再在下一帧切换语言（切换会重建界面）。
          if (!same)
            dialog.setOnDismissListener(
                ignored ->
                    new Handler(Looper.getMainLooper())
                        .post(() -> LanguageController.select(ctx, value)));
          dialog.dismiss();
        };

    // 「跟随系统」作为网格第一格。平板四列（3 行），常见手机（≥360dp）三列（4 行），更窄两列；
    // 手机用紧凑格子，小屏也不必滚动。
    List<String> languages = UiLanguagePreference.LANGUAGES;
    int count = languages.size() + 1;
    // 列数按「宽度 ÷ 字体缩放」计：手机大字体时三列格子放不下「跟随系统」，自动退到两列。
    android.content.res.Configuration config = ctx.getResources().getConfiguration();
    float widthDp = config.screenWidthDp / Math.max(1f, config.fontScale);
    int columns = widthDp >= 600 ? 4 : widthDp >= 360 ? 3 : 2;
    boolean compact = columns < 4;
    LinearLayout row = null;
    for (int i = 0; i < count; i++) {
      if (i % columns == 0) {
        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        if (i > 0) rowParams.topMargin = dp.applyAsInt(compact ? 6 : 8);
        body.addView(row, rowParams);
      }
      View cell;
      if (i == 0) {
        cell =
            tile(
                ctx,
                dp,
                compact,
                UiText.choose("跟随系统", "Follow system"),
                // 副标题只写系统当前解析出的语言名，避免窄格里折行。
                UiLanguagePreference.nativeName(systemLanguage),
                followSystem);
        cell.setOnClickListener(v -> pick.accept(UiLanguagePreference.SYSTEM));
      } else {
        String language = languages.get(i - 1);
        String nativeName = UiLanguagePreference.nativeName(language);
        String localized = localizedName(language, uiLanguage);
        cell =
            tile(
                ctx,
                dp,
                compact,
                nativeName,
                localized.equalsIgnoreCase(nativeName) ? "" : localized,
                !followSystem && language.equals(preference));
        cell.setOnClickListener(v -> pick.accept(language));
      }
      cell.setTag(i == 0 ? UiLanguagePreference.SYSTEM : languages.get(i - 1));
      LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, -1, 1f);
      if (i % columns > 0) cellParams.setMarginStart(dp.applyAsInt(compact ? 6 : 8));
      row.addView(cell, cellParams);
    }
    // 末行补齐空位，保持格子等宽（高度跟随同行格子）。
    int remainder = count % columns;
    if (row != null && remainder > 0)
      for (int i = remainder; i < columns; i++) {
        View spacer = new View(ctx);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, -1, 1f);
        spacerParams.setMarginStart(dp.applyAsInt(compact ? 6 : 8));
        row.addView(spacer, spacerParams);
      }

    ScrollView scroll = new ScrollView(ctx);
    // 大字体或极矮屏仍可能超出：滚动条常显、边缘渐隐，提示下面还有选项。
    scroll.setScrollbarFadingEnabled(false);
    scroll.setVerticalFadingEdgeEnabled(true);
    scroll.setFadingEdgeLength(dp.applyAsInt(24));
    scroll.addView(body);
    holder[0] =
        new DshaDialogBuilder(ctx)
            .setTitle(UiText.choose("界面语言 / Interface language", "Interface language"))
            .setView(scroll)
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .show();
  }

  /** 语言在当前界面语言里的名称（系统 ICU 提供），如中文界面下的 English → 英语。 */
  private static String localizedName(String language, String uiLanguage) {
    // 中文界面里「简体中文 / 繁體中文」本身就能读懂，副标题「中文（简体中文）」只是重复。
    boolean chineseUi =
        UiLanguagePreference.ZH.equals(uiLanguage)
            || UiLanguagePreference.ZH_HANT.equals(uiLanguage);
    boolean chineseOption =
        UiLanguagePreference.ZH.equals(language) || UiLanguagePreference.ZH_HANT.equals(language);
    if (chineseUi && chineseOption) return "";
    try {
      String tag = UiLanguagePreference.ZH.equals(language) ? "zh-Hans" : language;
      String display = UiLanguagePreference.ZH.equals(uiLanguage) ? "zh-Hans" : uiLanguage;
      Locale displayLocale = Locale.forLanguageTag(display);
      String name = Locale.forLanguageTag(tag).getDisplayName(displayLocale);
      if (name == null || name.isEmpty() || name.equalsIgnoreCase(tag)) return "";
      return name.substring(0, 1).toUpperCase(displayLocale) + name.substring(1);
    } catch (RuntimeException unavailable) {
      return "";
    }
  }

  /** 选项格：主名称 + 副标题，选中时强调色描边、浅底与对勾。 */
  private static View tile(
      Context ctx,
      java.util.function.IntUnaryOperator dp,
      boolean compact,
      String title,
      String subtitle,
      boolean selected) {
    SelectableCard.Model model = new SelectableCard.Model();
    model.title = UiText.raw(title);
    model.subtitle = subtitle.isEmpty() ? null : UiText.raw(subtitle);
    model.selected = selected;
    View tile = SelectableCard.grid(ctx, model, null);
    tile.setMinimumHeight(dp.applyAsInt(compact ? 50 : 56));
    tile.setContentDescription(UiText.raw(subtitle.isEmpty() ? title : title + ", " + subtitle));
    return tile;
  }
}
