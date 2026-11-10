package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平板形态左栏（UI v5）：80dp 竖向导航。
 *
 * <p>只负责外观与选中态，不复制导航语义：点击通过 {@link OnItemSelectedListener} 交回
 * {@link MainActivity}，由 Activity 调用 {@code bottom_nav.setSelectedItemId(int)} 复用现有
 * 路由、门禁与终端动作。手机布局不包含本视图，因此 Activity 必须按可空处理。
 *
 * <p>XML 里的 {@code android:visibility}（gone/visible）不影响内部点击与选中同步：
 * 可见性只决定是否参与绘制，路由仍由隐藏的 BottomNavigationView 承担。
 */
public final class ShellRail extends LinearLayout {

  /** 左栏点击回传；参数是 menu/bottom_nav 共用的 nav_* id。 */
  public interface OnItemSelectedListener {
    void onItemSelected(int itemId);
  }

  private static final int[] ITEM_IDS = {
    R.id.nav_launch, R.id.nav_plugins, R.id.nav_settings, R.id.nav_terminal
  };
  private static final int[] ITEM_ICONS = {
    R.drawable.ic_launch, R.drawable.ic_plugins, R.drawable.ic_settings, R.drawable.ic_terminal
  };

  private final Map<Integer, View> items = new LinkedHashMap<>();
  @Nullable private OnItemSelectedListener listener;
  private int selectedId = R.id.nav_launch;

  public ShellRail(Context context) {
    this(context, null);
  }

  public ShellRail(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public ShellRail(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    setOrientation(VERTICAL);
    setGravity(Gravity.CENTER_HORIZONTAL);
    setPadding(0, dp(14), 0, dp(8));
    build(context);
  }

  /** 设置点击回调；只回传 id，不在这里切页。 */
  public void setOnItemSelectedListener(@Nullable OnItemSelectedListener listener) {
    this.listener = listener;
  }

  /** 同步选中外观；只改绘制状态，不回调监听器，避免与 BottomNavigationView 互相递归。 */
  public void setSelectedItemId(int itemId) {
    selectedId = itemId;
    for (Map.Entry<Integer, View> entry : items.entrySet())
      entry.getValue().setSelected(entry.getKey() == itemId);
  }

  public int getSelectedItemId() {
    return selectedId;
  }

  private void build(Context context) {
    String[] titles = {
      UiText.choose("启动", "Launch"),
      UiText.choose("插件", "Plugins"),
      UiText.choose("设置", "Settings"),
      UiText.choose("终端", "Terminal")
    };
    ScrollView scroll = new ScrollView(context);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setClipToPadding(false);
    LinearLayout column = new LinearLayout(context);
    column.setOrientation(VERTICAL);
    column.setGravity(Gravity.CENTER_HORIZONTAL);
    int side = dimen(context, R.dimen.shell_rail_padding_horizontal);
    column.setPadding(side, 0, side, 0);
    for (int index = 0; index < ITEM_IDS.length; index++) {
      View item = createItem(context, ITEM_IDS[index], ITEM_ICONS[index], titles[index]);
      LayoutParams params =
          new LayoutParams(
              dimen(context, R.dimen.shell_rail_item_width), LayoutParams.WRAP_CONTENT);
      if (index < ITEM_IDS.length - 1)
        params.bottomMargin = dimen(context, R.dimen.shell_rail_item_gap);
      column.addView(item, params);
      items.put(ITEM_IDS[index], item);
    }
    scroll.addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

    TextView foot = new TextView(context);
    foot.setText(UiText.raw("LOCAL FIRST"));
    foot.setTextSize(
        TypedValue.COMPLEX_UNIT_PX, context.getResources().getDimension(R.dimen.shell_rail_foot));
    foot.setTextColor(context.getColor(R.color.shell_text_secondary));
    foot.setLetterSpacing(0.08f);
    foot.setGravity(Gravity.CENTER);
    foot.setSingleLine(true);
    foot.setIncludeFontPadding(false);
    LayoutParams footParams =
        new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    footParams.topMargin = dimen(context, R.dimen.shell_rail_item_gap);
    addView(foot, footParams);
    setSelectedItemId(selectedId);
  }

  private View createItem(Context context, int itemId, int iconRes, String title) {
    LinearLayout item = new LinearLayout(context);
    item.setId(itemId);
    item.setOrientation(VERTICAL);
    item.setGravity(Gravity.CENTER_HORIZONTAL);
    item.setClickable(true);
    item.setFocusable(true);
    item.setMinimumHeight(dimen(context, R.dimen.shell_rail_item_height));
    item.setBackgroundResource(R.drawable.bg_shell_rail_item);
    item.setContentDescription(title);
    item.setPadding(dp(3), dp(10), dp(3), dp(9));

    ImageView icon = new ImageView(context);
    icon.setImageResource(iconRes);
    icon.setImageTintList(context.getColorStateList(R.color.shell_rail_item_tint));
    icon.setDuplicateParentStateEnabled(true);
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    item.addView(
        icon,
        new LayoutParams(
            dimen(context, R.dimen.shell_rail_icon_size),
            dimen(context, R.dimen.shell_rail_icon_size)));

    TextView label = new TextView(context);
    label.setText(title);
    label.setTextSize(
        TypedValue.COMPLEX_UNIT_PX, context.getResources().getDimension(R.dimen.shell_rail_label));
    label.setTextColor(context.getColorStateList(R.color.shell_rail_item_tint));
    label.setDuplicateParentStateEnabled(true);
    label.setGravity(Gravity.CENTER);
    label.setSingleLine(true);
    label.setIncludeFontPadding(false);
    label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LayoutParams labelParams =
        new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    labelParams.topMargin = dp(5);
    item.addView(label, labelParams);

    item.setOnClickListener(
        v -> {
          if (listener != null) listener.onItemSelected(itemId);
          else setSelectedItemId(itemId);
        });
    return item;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static int dimen(Context context, int resource) {
    return context.getResources().getDimensionPixelSize(resource);
  }
}
