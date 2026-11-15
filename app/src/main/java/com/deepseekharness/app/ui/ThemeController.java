package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import androidx.appcompat.app.AppCompatDelegate;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.UiThemePalette;
import com.deepseekharness.app.util.UiThemePreference;

/**
 * 统一保存和应用外观选择。
 *
 * <ul>
 *   <li>日 / 夜：交给 AppCompat 夜间模式，明暗改变时由它重建所有 Activity；
 *   <li>调色板：与日夜正交。强调色与几层底色经 {@code ?attr/dsha_pal_*} 引用，每个 Activity 创建前叠加对应
 *       {@code ThemeOverlay.DSHA.Palette.*}（{@link PaletteHook}）。切换后发起页立即重建，其余页回到前台时重建。
 * </ul>
 *
 * <p>没有 Activity 主题的上下文（Application、Service、配置上下文）必须经 {@link #themed} 包装后再取调色板色，
 * 否则属性无从解析。
 */
public final class ThemeController {
  private ThemeController() {}

  public static void apply(Context context) {
    String mode = new ConfigStore(context).getUiTheme();
    int night =
        UiThemePreference.DARK.equals(mode)
            ? AppCompatDelegate.MODE_NIGHT_YES
            : UiThemePreference.LIGHT.equals(mode)
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    AppCompatDelegate.setDefaultNightMode(night);
  }

  /** 是否会让界面明暗改变（即 AppCompat 是否会重建 Activity）。 */
  public static boolean changesAppearance(Context context, String mode) {
    return UiThemePreference.changesAppearance(preference(context), mode, systemDark());
  }

  public static void select(Context context, String mode) {
    new ConfigStore(context).setUiTheme(mode);
    apply(context);
  }

  public static boolean isDark(Context context) {
    return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES;
  }

  /** 系统本身的深浅色：不受 AppCompat 本地夜间模式影响。 */
  public static boolean systemDark() {
    return (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES;
  }

  public static String preference(Context context) {
    return new ConfigStore(context).getUiTheme();
  }

  public static String palette(Context context) {
    return new ConfigStore(context).getUiPalette();
  }

  /** 保存调色板；页面重建由调用方（主题页）发起，其余页由 {@link PaletteHook} 在回到前台时重建。 */
  public static void selectPalette(Context context, String palette) {
    new ConfigStore(context).setUiPalette(palette);
  }

  /** 调色板 → ThemeOverlay；标准色为 0（主题默认值即标准色）。与生成器 PALETTES 一一对应。 */
  static int paletteOverlay(String palette) {
    return switch (UiThemePalette.normalize(palette)) {
      case UiThemePalette.AURORA -> R.style.ThemeOverlay_DSHA_Palette_Aurora;
      case UiThemePalette.SAKURA -> R.style.ThemeOverlay_DSHA_Palette_Sakura;
      case UiThemePalette.CYBER -> R.style.ThemeOverlay_DSHA_Palette_Cyber;
      case UiThemePalette.AMBER -> R.style.ThemeOverlay_DSHA_Palette_Amber;
      default -> 0;
    };
  }

  /** 调色板主色（具体颜色资源，日夜自动取对应值），供主题页色块使用。 */
  static int paletteSwatch(String palette) {
    return switch (UiThemePalette.normalize(palette)) {
      case UiThemePalette.AURORA -> R.color.pal_aurora_primary;
      case UiThemePalette.SAKURA -> R.color.pal_sakura_primary;
      case UiThemePalette.CYBER -> R.color.pal_cyber_primary;
      case UiThemePalette.AMBER -> R.color.pal_amber_primary;
      default -> R.color.pal_standard_primary;
    };
  }

  /** 给定上下文叠加应用主题与当前调色板。 */
  static void applyPalette(Resources.Theme theme, String palette) {
    int overlay = paletteOverlay(palette);
    if (overlay != 0) theme.applyStyle(overlay, true);
  }

  /**
   * 为 Service、配置上下文等没有 Activity 主题的上下文套上应用主题与当前调色板。
   *
   * <p>悬浮窗、配置上下文直接取 {@code R.color.primary} 等调色板色时必须先经过这里。
   */
  public static Context themed(Context base) {
    ContextThemeWrapper wrapper = new ContextThemeWrapper(base, R.style.Theme_DeepseekHarness);
    applyPalette(wrapper.getTheme(), palette(base));
    return wrapper;
  }

  /**
   * 每个 Activity 在 onCreate 前叠加调色板，并记下所用调色板；调色板变化后，旧页面回到前台时重建。
   *
   * <p>不重建后台页：后台 recreate 依赖系统时序；回到前台再重建与切语言、切明暗的观感一致。
   */
  public static final class PaletteHook implements Application.ActivityLifecycleCallbacks {
    private final java.util.WeakHashMap<Activity, String> applied = new java.util.WeakHashMap<>();

    @Override
    public void onActivityPreCreated(Activity activity, Bundle saved) {
      String palette = palette(activity);
      applied.put(activity, palette);
      applyPalette(activity.getTheme(), palette);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle saved) {}

    @Override
    public void onActivityStarted(Activity activity) {}

    @Override
    public void onActivityResumed(Activity activity) {
      String used = applied.get(activity);
      if (used != null
          && UiThemePalette.stale(used, palette(activity))
          && !activity.isFinishing()
          && !activity.isChangingConfigurations()) activity.recreate();
    }

    @Override
    public void onActivityPaused(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle out) {}

    @Override
    public void onActivityDestroyed(Activity activity) {
      applied.remove(activity);
    }
  }
}
