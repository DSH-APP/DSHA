package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.AppSearch;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 屏幕确认规则的应用选择器：列出可启动应用（图标 + 名称 + 包名），支持搜索；不授予任何权限。 */
final class ScreenAppPicker {
  /** 一个可选应用；icon 可能为空（读取失败时只显示文字）。 */
  record App(String packageName, String label, Drawable icon) {}

  private ScreenAppPicker() {}

  /** 读取单个应用的名称和图标；未安装时退回包名。 */
  static App describe(Context ctx, String packageName) {
    PackageManager pm = ctx.getPackageManager();
    try {
      var info = pm.getApplicationInfo(packageName, 0);
      return new App(
          packageName, String.valueOf(pm.getApplicationLabel(info)), pm.getApplicationIcon(info));
    } catch (PackageManager.NameNotFoundException | RuntimeException missing) {
      return new App(packageName, packageName, null);
    }
  }

  /** 后台读取所有带启动入口的应用（同一包只保留一次）。 */
  private static List<App> loadLaunchable(Context ctx) {
    PackageManager pm = ctx.getPackageManager();
    Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    Map<String, App> apps = new LinkedHashMap<>();
    List<ResolveInfo> found;
    try {
      found = pm.queryIntentActivities(launcher, 0);
    } catch (RuntimeException failed) {
      found = List.of();
    }
    for (ResolveInfo info : found) {
      if (info.activityInfo == null) continue;
      String pkg = info.activityInfo.packageName;
      if (apps.containsKey(pkg)) continue;
      Drawable icon;
      try {
        icon = info.activityInfo.applicationInfo.loadIcon(pm);
      } catch (RuntimeException failed) {
        icon = null;
      }
      String label = String.valueOf(info.activityInfo.applicationInfo.loadLabel(pm));
      apps.put(pkg, new App(pkg, label, icon));
    }
    return new ArrayList<>(apps.values());
  }

  /**
   * 弹出选择器。点选应用回调 onPick；「手动输入包名」回调 onManual。已配置规则的应用排在前面，并标出当前规则。
   */
  static void show(
      Context ctx,
      Map<String, String> configuredBadges,
      Consumer<String> onPick,
      Runnable onManual) {
    float density = ctx.getResources().getDisplayMetrics().density;
    java.util.function.IntUnaryOperator dp = v -> Math.round(v * density);
    LinearLayout content = new LinearLayout(ctx);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp.applyAsInt(20), dp.applyAsInt(4), dp.applyAsInt(20), 0);
    var search = new androidx.appcompat.widget.AppCompatEditText(ctx);
    search.setSingleLine(true);
    search.setBackgroundResource(R.drawable.bg_shell_input);
    search.setPadding(dp.applyAsInt(14), dp.applyAsInt(10), dp.applyAsInt(14), dp.applyAsInt(10));
    search.setTextSize(14);
    search.setHint(UiText.choose("搜索应用名称或包名", "Search app name or package"));
    search.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_ui_search, 0, 0, 0);
    search.setCompoundDrawablePadding(dp.applyAsInt(8));
    content.addView(search, new LinearLayout.LayoutParams(-1, -2));
    TextView state = new TextView(ctx);
    state.setTextSize(13);
    state.setTextColor(ctx.getColor(R.color.shell_text_secondary));
    state.setPadding(0, dp.applyAsInt(12), 0, dp.applyAsInt(4));
    state.setText(UiText.choose("正在读取已安装应用…", "Loading installed apps…"));
    content.addView(state, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout list = new LinearLayout(ctx);
    list.setOrientation(LinearLayout.VERTICAL);
    ScrollView scroll = new ScrollView(ctx);
    scroll.addView(list);
    // 列表高度跟屏幕走：短屏和大字体下仍能看到搜索框与底部按钮。
    int height = Math.round(ctx.getResources().getDisplayMetrics().heightPixels * 0.45f);
    content.addView(scroll, new LinearLayout.LayoutParams(-1, height));
    androidx.appcompat.app.AlertDialog dialog =
        new DshaDialogBuilder(ctx)
            .setTitle(UiText.choose("选择应用", "Choose app"))
            .setView(content)
            .setNegativeButton(
                UiText.choose("手动输入包名", "Enter package name"), (d, which) -> onManual.run())
            .setPositiveButton(UiText.text("取消"), null)
            .create();
    dialog.show();
    List<App>[] loaded = new List[] {null};
    Runnable render =
        () -> {
          if (loaded[0] == null || !dialog.isShowing()) return;
          String query = String.valueOf(search.getText());
          List<App> shown =
              AppSearch.filterAndSort(
                  loaded[0],
                  query,
                  App::label,
                  App::packageName,
                  app -> configuredBadges.containsKey(app.packageName()));
          list.removeAllViews();
          state.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
          state.setText(UiText.choose("没有匹配的应用", "No matching apps"));
          for (App app : shown)
            list.addView(
                row(ctx, app, configuredBadges.get(app.packageName()), dp),
                new LinearLayout.LayoutParams(-1, -2));
          for (int i = 0; i < list.getChildCount(); i++) {
            App app = shown.get(i);
            list.getChildAt(i)
                .setOnClickListener(
                    v -> {
                      dialog.dismiss();
                      onPick.accept(app.packageName());
                    });
          }
        };
    search.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {}

          @Override
          public void afterTextChanged(Editable s) {
            render.run();
          }
        });
    Context app = ctx.getApplicationContext();
    Handler main = new Handler(Looper.getMainLooper());
    new Thread(
            () -> {
              List<App> apps = loadLaunchable(app);
              main.post(
                  () -> {
                    loaded[0] = apps;
                    render.run();
                  });
            },
            "screen-app-picker")
        .start();
  }

  /** 应用行：图标 + 名称 + 包名，已配置时右侧标出当前规则。 */
  static View row(Context ctx, App app, String badge, java.util.function.IntUnaryOperator dp) {
    LinearLayout row = new LinearLayout(ctx);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp.applyAsInt(56));
    row.setPadding(dp.applyAsInt(4), dp.applyAsInt(8), dp.applyAsInt(4), dp.applyAsInt(8));
    row.setClickable(true);
    row.setFocusable(true);
    row.setBackgroundResource(R.drawable.bg_action_plain);
    ImageView icon = new ImageView(ctx);
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    if (app.icon() != null) icon.setImageDrawable(app.icon());
    else icon.setImageResource(R.drawable.ic_ui2_box);
    LinearLayout.LayoutParams iconParams =
        new LinearLayout.LayoutParams(dp.applyAsInt(36), dp.applyAsInt(36));
    iconParams.setMarginEnd(dp.applyAsInt(12));
    row.addView(icon, iconParams);
    LinearLayout copy = new LinearLayout(ctx);
    copy.setOrientation(LinearLayout.VERTICAL);
    TextView label = new TextView(ctx);
    label.setText(UiText.raw(app.label()));
    label.setTextSize(15);
    label.setTextColor(ctx.getColor(R.color.shell_text));
    label.setSingleLine(true);
    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
    copy.addView(label);
    TextView pkg = new TextView(ctx);
    pkg.setText(UiText.raw(app.packageName()));
    pkg.setTextSize(12);
    pkg.setTextColor(ctx.getColor(R.color.shell_text_muted));
    pkg.setSingleLine(true);
    pkg.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    copy.addView(pkg);
    row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
    String spoken = app.label() + ", " + app.packageName();
    if (badge != null && !badge.isEmpty()) {
      TextView tag = new TextView(ctx);
      tag.setText(badge);
      tag.setTextSize(11);
      tag.setTextColor(ctx.getColor(R.color.shell_text_secondary));
      tag.setBackgroundResource(R.drawable.bg_shell_status);
      tag.setPadding(dp.applyAsInt(8), dp.applyAsInt(2), dp.applyAsInt(8), dp.applyAsInt(2));
      LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(-2, -2);
      tagParams.setMarginStart(dp.applyAsInt(8));
      row.addView(tag, tagParams);
      spoken += ", " + badge;
    }
    row.setContentDescription(UiText.raw(spoken));
    return row;
  }
}
