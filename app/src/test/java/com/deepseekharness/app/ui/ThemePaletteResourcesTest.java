package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiThemePalette;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 调色板色经主题属性解析：每套调色板 × 日夜，真实主题加叠加层后取到的 R.color.* 等于生成表里的值。
 *
 * <p>属性解析失败时 Android 回退为品红；这里同时确认没有任何调色板色解析成品红或透明。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = ThemePaletteResourcesTest.App.class)
public final class ThemePaletteResourcesTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  /** 调色板色名：生成表 pal_standard_* 的后缀。 */
  private static List<String> names() throws Exception {
    List<String> names = new ArrayList<>();
    for (Field field : R.color.class.getFields())
      if (field.getName().startsWith("pal_standard_"))
        names.add(field.getName().substring("pal_standard_".length()));
    return names;
  }

  private static int id(String name) throws Exception {
    return R.color.class.getField(name).getInt(null);
  }

  private static Context themed(boolean night, String palette) {
    Context base = RuntimeEnvironment.getApplication();
    Configuration config = new Configuration(base.getResources().getConfiguration());
    config.uiMode =
        (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
    ContextThemeWrapper context =
        new ContextThemeWrapper(
            base.createConfigurationContext(config), R.style.Theme_DeepseekHarness);
    ThemeController.applyPalette(context.getTheme(), palette);
    return context;
  }

  @Test
  public void everyPaletteResolvesItsGeneratedColorsInBothModes() throws Exception {
    List<String> names = names();
    assertEquals(33, names.size());
    for (String palette : UiThemePalette.REGISTERED) {
      for (boolean night : new boolean[] {false, true}) {
        Context context = themed(night, palette);
        for (String name : names) {
          int actual = context.getColor(id(name));
          int expected = context.getColor(id("pal_" + palette + "_" + name));
          String where = palette + (night ? "/night/" : "/day/") + name;
          assertEquals(where, expected, actual);
          assertNotEquals(where + " 解析失败成品红", Color.MAGENTA, actual);
        }
      }
    }
  }

  @Test
  public void palettesActuallyDifferAndStandardKeepsTheOriginalBlue() {
    int standard = themed(false, UiThemePalette.STANDARD).getColor(R.color.primary);
    assertEquals(0xFF1E50D0, standard);
    assertEquals(0xFF6AA4FF, themed(true, UiThemePalette.STANDARD).getColor(R.color.primary));
    for (String palette : UiThemePalette.REGISTERED) {
      if (UiThemePalette.STANDARD.equals(palette)) continue;
      assertNotEquals(palette, standard, themed(false, palette).getColor(R.color.primary));
      assertNotEquals(0, ThemeController.paletteOverlay(palette));
    }
    assertEquals(0, ThemeController.paletteOverlay(UiThemePalette.STANDARD));
  }

  @Test
  public void handWrittenSelectorsFollowThePaletteInsteadOfFreezing() {
    // 选中态取强调色：嵌套 @color 引用会在首次加载时被缓存，改为 ?attr 后每套调色板各自解析。
    for (String palette : UiThemePalette.REGISTERED) {
      Context context = themed(false, palette);
      ColorStateList tint = context.getColorStateList(R.color.shell_nav_item_tint);
      int selected = tint.getColorForState(new int[] {android.R.attr.state_checked}, 0);
      int accent = context.getColor(R.color.shell_accent);
      assertNotEquals(palette, Color.MAGENTA, selected);
      assertEquals(palette, accent, selected);
      assertNotEquals(palette, Color.MAGENTA, tint.getDefaultColor());
    }
  }

  @Test
  public void drawablesAndUnthemedContextsGetThePaletteThroughThemed() {
    Context app = RuntimeEnvironment.getApplication();
    Context wrapped = ThemeController.themed(app);
    assertNotNull(wrapped.getDrawable(R.drawable.bg_shell_card));
    assertNotEquals(Color.MAGENTA, wrapped.getColor(R.color.primary));
    assertNotEquals(Color.MAGENTA, wrapped.getColor(R.color.shell_surface));
  }
}
