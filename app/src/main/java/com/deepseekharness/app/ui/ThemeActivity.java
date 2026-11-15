package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.ThemeMotion;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.UiThemePreference;

/**
 * 设置 › 主题与外观：浅色 / 深色 / 跟随系统三块预览卡，点选即以圆形扩散切换；另含 Web 同步开关。
 *
 * <p>主题入口只在这里，不再暴露在主界面顶栏。预览卡用对应明暗的 shell_* 资源绘制，不写死色值。
 */
public final class ThemeActivity extends androidx.appcompat.app.AppCompatActivity {
  private final java.util.Map<String, Tile> tiles = new java.util.LinkedHashMap<>();
  private float touchX = -1f, touchY = -1f;

  static void open(Context context) {
    context.startActivity(new Intent(context, ThemeActivity.class));
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    CardPage page = new CardPage(this, UiText.text("主题与外观"), UiText.text("选择应用的明暗外观，切换会立即生效。"));
    setContentView(page.root);

    page.label(UiText.text("外观模式"));
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
    rowParams.bottomMargin = page.dp(14);
    page.content.addView(row, rowParams);
    for (String mode : UiThemePreference.ORDER) {
      Tile tile = new Tile(page, mode);
      tiles.put(mode, tile);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
      if (row.getChildCount() > 0) params.leftMargin = page.dp(10);
      row.addView(tile.root, params);
    }
    renderSelection();

    if (com.deepseekharness.app.util.UiThemePalette.selectable()) {
      page.label(UiText.text("配色"));
      LinearLayout palettes = page.card();
      LinearLayout swatches = new LinearLayout(this);
      swatches.setOrientation(LinearLayout.HORIZONTAL);
      swatches.setPadding(0, page.dp(6), 0, page.dp(2));
      for (String id : com.deepseekharness.app.util.UiThemePalette.REGISTERED)
        swatches.addView(swatch(page, id), new LinearLayout.LayoutParams(0, -2, 1f));
      palettes.addView(swatches);
      TextView note = page.text(UiText.text("配色只改原生界面，网页只同步明暗"), 12, R.color.shell_text_muted);
      note.setLineSpacing(page.dp(2), 1);
      note.setPadding(0, page.dp(10), 0, page.dp(4));
      palettes.addView(note);
    }

    page.label(UiText.text("切换动画"));
    LinearLayout styles = page.card();
    for (String style : ThemeMotion.STYLES) styles.addView(styleRow(page, styles, style));
    renderStyle(styles);

    page.label(UiText.text("同步"));
    LinearLayout sync = page.card();
    sync.addView(webSyncRow(page));

    // 发起切换的就是本页：重建后领取切换前的截图并扩散。
    ThemeReveal.consume(this);
  }

  /** 记下最近一次按下位置，作为扩散圆心（窗口坐标）。键盘 / 无障碍点按退回卡片中心。 */
  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      touchX = event.getRawX();
      touchY = event.getRawY();
    }
    return super.dispatchTouchEvent(event);
  }

  private void choose(String mode, View source) {
    String current = ThemeController.preference(this);
    if (mode.equals(current)) return;
    String message =
        UiThemePreference.SYSTEM.equals(mode)
            ? UiText.text("已跟随系统主题")
            : UiThemePreference.LIGHT.equals(mode)
                ? UiText.text("已切换白天模式")
                : UiText.text("已切换黑夜模式");
    if (!ThemeController.changesAppearance(this, mode)) {
      // 明暗不变：不会重建，只更新选中态。
      ThemeController.select(this, mode);
      renderSelection();
      source.announceForAccessibility(message);
      return;
    }
    float[] center = revealCenter(source);
    String style = new ConfigStore(this).getThemeTransition();
    ThemeReveal.capture(
        this, center[0], center[1], style, () -> ThemeController.select(this, mode));
  }

  /** 换调色板：与换明暗走同一套过渡；发起页立即重建，其余页回到前台时重建。 */
  private void choosePalette(String id, View source) {
    if (id.equals(ThemeController.palette(this))) return;
    float[] center = revealCenter(source);
    String style = new ConfigStore(this).getThemeTransition();
    ThemeReveal.capture(
        this,
        center[0],
        center[1],
        style,
        () -> {
          ThemeController.selectPalette(this, id);
          recreate();
        });
  }

  /** 一个配色色块：主色圆点 + 名称；选中时外圈描边。作为单选项暴露给无障碍。 */
  private View swatch(CardPage page, String id) {
    boolean selected = id.equals(ThemeController.palette(this));
    LinearLayout item = page.column();
    item.setGravity(Gravity.CENTER_HORIZONTAL);
    item.setPadding(page.dp(2), page.dp(8), page.dp(2), page.dp(8));
    item.setFocusable(true);
    item.setClickable(true);
    item.setBackgroundResource(R.drawable.bg_action_plain);
    View dot = new View(this);
    GradientDrawable fill = new GradientDrawable();
    fill.setShape(GradientDrawable.OVAL);
    fill.setColor(getColor(ThemeController.paletteSwatch(id)));
    // 选中：外圈留一道底色缝再描强调色，和参考稿的选中圆点一致。
    fill.setStroke(page.dp(selected ? 3 : 0), getColor(R.color.shell_surface));
    dot.setBackground(fill);
    android.widget.FrameLayout ring = new android.widget.FrameLayout(this);
    GradientDrawable outline = new GradientDrawable();
    outline.setShape(GradientDrawable.OVAL);
    outline.setStroke(
        page.dp(selected ? 2 : 1), getColor(selected ? R.color.shell_text : R.color.shell_line));
    ring.setBackground(outline);
    ring.setPadding(page.dp(2), page.dp(2), page.dp(2), page.dp(2));
    ring.addView(dot, new android.widget.FrameLayout.LayoutParams(-1, -1));
    item.addView(ring, new LinearLayout.LayoutParams(page.dp(40), page.dp(40)));
    TextView label =
        page.text(
            paletteName(id), 12, selected ? R.color.shell_text : R.color.shell_text_secondary);
    label.setGravity(Gravity.CENTER);
    label.setMaxLines(2);
    if (selected) label.setTypeface(null, android.graphics.Typeface.BOLD);
    label.setPadding(0, page.dp(8), 0, 0);
    item.addView(label, new LinearLayout.LayoutParams(-1, -2));
    item.setContentDescription(paletteName(id));
    item.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(android.widget.RadioButton.class.getName());
            info.setCheckable(true);
            info.setChecked(selected);
          }
        });
    item.setOnClickListener(v -> choosePalette(id, ring));
    return item;
  }

  static String paletteName(String id) {
    return switch (com.deepseekharness.app.util.UiThemePalette.normalize(id)) {
      case com.deepseekharness.app.util.UiThemePalette.AURORA -> UiText.text("极光");
      case com.deepseekharness.app.util.UiThemePalette.SAKURA -> UiText.text("樱花");
      case com.deepseekharness.app.util.UiThemePalette.CYBER -> UiText.text("赛博");
      case com.deepseekharness.app.util.UiThemePalette.AMBER -> UiText.text("琥珀");
      default -> UiText.text("经典蓝");
    };
  }

  /** 触摸落在来源卡片上时取触点，否则取卡片中心；均换算为窗口坐标。 */
  private float[] revealCenter(View source) {
    int[] window = new int[2];
    getWindow().getDecorView().getLocationOnScreen(window);
    int[] at = new int[2];
    source.getLocationOnScreen(at);
    boolean inside =
        touchX >= at[0]
            && touchX <= at[0] + source.getWidth()
            && touchY >= at[1]
            && touchY <= at[1] + source.getHeight();
    float x = inside ? touchX : at[0] + source.getWidth() / 2f;
    float y = inside ? touchY : at[1] + source.getHeight() / 2f;
    return new float[] {x - window[0], y - window[1]};
  }

  private void renderSelection() {
    String current = ThemeController.preference(this);
    for (Tile tile : tiles.values()) tile.setChecked(tile.mode.equals(current));
  }

  private View styleRow(CardPage page, LinearLayout group, String style) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(page.dp(56));
    row.setPadding(0, page.dp(10), 0, page.dp(10));
    row.setFocusable(true);
    row.setClickable(true);
    row.setBackgroundResource(R.drawable.bg_action_plain);
    row.setTag(style);
    LinearLayout words = page.column();
    TextView title = page.text(styleName(style), 14, R.color.shell_text);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    words.addView(title);
    TextView sub = page.text(styleHint(style), 12, R.color.shell_text_muted);
    sub.setLineSpacing(page.dp(2), 1);
    sub.setPadding(0, page.dp(4), 0, 0);
    words.addView(sub);
    LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1);
    wordsParams.rightMargin = page.dp(12);
    row.addView(words, wordsParams);
    android.widget.ImageView mark = new android.widget.ImageView(this);
    mark.setImageResource(R.drawable.ic_plugin_check);
    mark.setImageTintList(getColorStateList(R.color.shell_accent));
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(mark, new LinearLayout.LayoutParams(page.dp(20), page.dp(20)));
    row.setContentDescription(styleName(style));
    row.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(android.widget.RadioButton.class.getName());
            info.setCheckable(true);
            info.setChecked(style.equals(new ConfigStore(ThemeActivity.this).getThemeTransition()));
          }
        });
    row.setOnClickListener(
        v -> {
          new ConfigStore(this).setThemeTransition(style);
          renderStyle(group);
          v.announceForAccessibility(UiText.format("已选择 %s", styleName(style)));
        });
    return row;
  }

  private void renderStyle(LinearLayout group) {
    String current = new ConfigStore(this).getThemeTransition();
    for (int index = 0; index < group.getChildCount(); index++) {
      View row = group.getChildAt(index);
      boolean selected = current.equals(row.getTag());
      row.setSelected(selected);
      View mark = ((LinearLayout) row).getChildAt(1);
      if (mark != null) mark.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
    }
  }

  private static String styleName(String style) {
    return switch (ThemeMotion.normalizeStyle(style)) {
      case ThemeMotion.DIAGONAL -> UiText.text("对角擦除");
      case ThemeMotion.FADE -> UiText.text("淡入淡出");
      case ThemeMotion.NONE -> UiText.text("直接切换");
      default -> UiText.text("圆形扩散");
    };
  }

  private static String styleHint(String style) {
    return switch (ThemeMotion.normalizeStyle(style)) {
      case ThemeMotion.DIAGONAL -> UiText.text("从左上角柔和地斜扫到右下角");
      case ThemeMotion.FADE -> UiText.text("整屏新旧主题交叉淡化");
      case ThemeMotion.NONE -> UiText.text("不做过渡，立即换成新外观");
      default -> UiText.text("从点按处向外铺开，不回缩");
    };
  }

  private View webSyncRow(CardPage page) {
    ConfigStore config = new ConfigStore(this);
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(page.dp(56));
    row.setPadding(0, page.dp(8), 0, page.dp(8));
    LinearLayout words = page.column();
    TextView title = page.text(UiText.text("Web 界面跟随"), 14, R.color.shell_text);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    words.addView(title);
    TextView sub = page.text(UiText.text("切换主题会同步内嵌网页"), 12, R.color.shell_text_muted);
    sub.setLineSpacing(page.dp(2), 1);
    sub.setPadding(0, page.dp(4), 0, 0);
    words.addView(sub);
    LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1);
    wordsParams.rightMargin = page.dp(12);
    row.addView(words, wordsParams);
    // 主题里的 materialSwitchStyle 已指向 Widget.DSHA.Switch。
    var toggle = new com.google.android.material.materialswitch.MaterialSwitch(this);
    toggle.setChecked(config.isWebThemeSync());
    toggle.setShowText(false);
    toggle.setContentDescription(UiText.text("Web 界面跟随"));
    toggle.setOnCheckedChangeListener((button, checked) -> config.setWebThemeSync(checked));
    row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
    row.setOnClickListener(v -> toggle.toggle());
    row.setBackgroundResource(R.drawable.bg_action_plain);
    return row;
  }

  /** 一块模式卡：预览 + 名称；作为单选项暴露给无障碍。 */
  private final class Tile {
    final String mode;
    final LinearLayout root;
    private final GradientDrawable frame = new GradientDrawable();
    private final TextView label;
    private final CardPage page;
    private boolean checked;

    Tile(CardPage page, String mode) {
      this.page = page;
      this.mode = mode;
      root = page.column();
      root.setGravity(Gravity.CENTER_HORIZONTAL);
      root.setPadding(page.dp(6), page.dp(6), page.dp(6), page.dp(10));
      frame.setCornerRadius(page.dp(16));
      frame.setColor(getColor(R.color.shell_surface));
      root.setBackground(frame);
      root.setForeground(getDrawable(R.drawable.bg_action_plain));
      root.setFocusable(true);
      root.setClickable(true);

      Preview preview = new Preview(ThemeActivity.this, mode);
      preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      root.addView(preview, new LinearLayout.LayoutParams(-1, page.dp(96)));

      label = page.text(name(mode), 13, R.color.shell_text);
      label.setGravity(Gravity.CENTER);
      label.setMaxLines(2);
      label.setPadding(0, page.dp(10), 0, 0);
      label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      root.addView(label, new LinearLayout.LayoutParams(-1, -2));

      root.setContentDescription(name(mode));
      root.setAccessibilityDelegate(
          new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
              super.onInitializeAccessibilityNodeInfo(host, info);
              info.setClassName(android.widget.RadioButton.class.getName());
              info.setCheckable(true);
              info.setChecked(checked);
            }
          });
      root.setOnClickListener(v -> choose(mode, root));
    }

    void setChecked(boolean value) {
      checked = value;
      frame.setStroke(
          page.dp(value ? 2 : 1), getColor(value ? R.color.shell_accent : R.color.shell_line));
      label.setTypeface(
          null, value ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
      label.setTextColor(getColor(value ? R.color.shell_accent : R.color.shell_text));
      root.setSelected(value);
    }
  }

  private static String name(String mode) {
    return UiThemePreference.LIGHT.equals(mode)
        ? UiText.text("浅色")
        : UiThemePreference.DARK.equals(mode) ? UiText.text("深色") : UiText.text("跟随系统");
  }

  /**
   * 迷你界面示意：画布 + 顶栏 + 两张卡片。颜色取自对应明暗的资源；跟随系统左右各半。
   */
  private static final class Preview extends View {
    private final int[] light, dark;
    private final boolean split;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    Preview(Context context, String mode) {
      super(context);
      light = palette(context, false);
      dark = palette(context, true);
      split = UiThemePreference.SYSTEM.equals(mode);
      if (UiThemePreference.DARK.equals(mode)) System.arraycopy(dark, 0, light, 0, dark.length);
    }

    /** {bg, surface, accent, muted, line}，按指定明暗读取资源。 */
    private static int[] palette(Context context, boolean night) {
      Configuration config = new Configuration(context.getResources().getConfiguration());
      config.uiMode =
          (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
              | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
      // 配置上下文不带主题：套上应用主题与当前调色板，预览才是用户选的配色。
      Context themed = ThemeController.themed(context.createConfigurationContext(config));
      return new int[] {
        themed.getColor(R.color.shell_bg),
        themed.getColor(R.color.shell_surface),
        themed.getColor(R.color.shell_accent),
        themed.getColor(R.color.shell_text_muted),
        themed.getColor(R.color.shell_line)
      };
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth(), h = getHeight();
      float corner = 10 * getResources().getDisplayMetrics().density;
      android.graphics.Path clip = new android.graphics.Path();
      rect.set(0, 0, w, h);
      clip.addRoundRect(rect, corner, corner, android.graphics.Path.Direction.CW);
      int save = canvas.save();
      canvas.clipPath(clip);
      if (split) {
        canvas.save();
        canvas.clipRect(0, 0, w / 2f, h);
        drawMock(canvas, light, w, h);
        canvas.restore();
        canvas.save();
        canvas.clipRect(w / 2f, 0, w, h);
        drawMock(canvas, dark, w, h);
        canvas.restore();
      } else drawMock(canvas, light, w, h);
      canvas.restoreToCount(save);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(getResources().getDisplayMetrics().density);
      paint.setColor(light[4]);
      rect.inset(paint.getStrokeWidth() / 2f, paint.getStrokeWidth() / 2f);
      canvas.drawRoundRect(rect, corner, corner, paint);
      paint.setStyle(Paint.Style.FILL);
    }

    private void drawMock(Canvas canvas, int[] c, float w, float h) {
      float d = getResources().getDisplayMetrics().density;
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(c[0]);
      canvas.drawRect(0, 0, w, h, paint);
      float pad = 8 * d;
      // 顶栏：强调色圆点 + 一条标题线。
      paint.setColor(c[2]);
      canvas.drawCircle(pad + 4 * d, pad + 4 * d, 4 * d, paint);
      paint.setColor(c[3]);
      rect.set(pad + 12 * d, pad + 2.5f * d, w * 0.6f, pad + 5.5f * d);
      canvas.drawRoundRect(rect, 2 * d, 2 * d, paint);
      // 两张卡片。
      float top = pad + 16 * d;
      float cardHeight = (h - top - pad - 6 * d) / 2f;
      for (int index = 0; index < 2; index++) {
        float y = top + index * (cardHeight + 6 * d);
        paint.setColor(c[1]);
        rect.set(pad, y, w - pad, y + cardHeight);
        canvas.drawRoundRect(rect, 6 * d, 6 * d, paint);
        paint.setColor(index == 0 ? c[2] : c[3]);
        rect.set(
            pad + 6 * d, y + cardHeight / 2f - 1.5f * d, w * 0.55f, y + cardHeight / 2f + 1.5f * d);
        canvas.drawRoundRect(rect, 2 * d, 2 * d, paint);
      }
    }
  }
}
