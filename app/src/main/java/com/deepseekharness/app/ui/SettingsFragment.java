package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.OverlayController;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.UpdateEngine;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.UpdatePolicy;

/**
 * 设置首页：运行 / 界面 / 数据与设备 / 支持四组入口。
 */
public class SettingsFragment extends Fragment {

  private static final String[] PERMISSION_VALUES = {
    "danger-full-access", "workspace-write", "read-only"
  };

  private TextView installStatus;
  private TextView networkSub;
  private TextView permissionSub;
  private TextView webSub;
  private TextView overlaySub;
  private TextView versionView;
  private TextView updateSub;
  private TextView powerHint;
  private ConfigStore config;

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View v = inflater.inflate(R.layout.fragment_settings, container, false);

    config = HarnessController.get(requireContext()).config();
    buildGroups(v);
    bindKeepAlive(v.findViewById(R.id.settings_power));

    versionView = v.findViewById(R.id.settings_ver);
    updateSub = v.findViewById(R.id.settings_update_sub);
    overlaySub = v.findViewById(R.id.settings_overlay_sub);
    bindLanguage(v);
    refreshDynamicRows();
    v.postDelayed(this::refreshInstallStatus, 400);
    v.postDelayed(this::refreshInstallStatus, 2800);

    v.findViewById(R.id.settings_about)
        .setOnClickListener(
            x ->
                UiMotion.page(requireContext(), getParentFragmentManager().beginTransaction())
                    .replace(R.id.fragment_container, new AboutFragment())
                    .addToBackStack("settings")
                    .commit());
    v.findViewById(R.id.settings_update).setOnClickListener(x -> checkUpdate());
    v.findViewById(R.id.settings_selftest).setOnClickListener(x -> runSelftest());
    v.findViewById(R.id.settings_overlay)
        .setOnClickListener(
            x ->
                UiMotion.page(requireContext(), getParentFragmentManager().beginTransaction())
                    .replace(R.id.fragment_container, new OverlayFragment())
                    .addToBackStack("settings")
                    .commit());
    return v;
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshDynamicRows();
  }

  private void bindKeepAlive(LinearLayout power) {
    LinearLayout words = new LinearLayout(requireContext());
    words.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(0, -2, 1);
    wordParams.setMarginStart(dp(12));
    wordParams.setMarginEnd(dp(8));
    power.addView(words, wordParams);
    TextView heading = new TextView(requireContext());
    heading.setText(getString(R.string.ui2_keep_alive));
    heading.setTextSize(15);
    heading.setTextColor(requireContext().getColor(R.color.text));
    heading.setIncludeFontPadding(false);
    heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
    words.addView(heading);
    powerHint = new TextView(requireContext());
    powerHint.setTextSize(13);
    powerHint.setTextColor(requireContext().getColor(R.color.text_secondary));
    powerHint.setIncludeFontPadding(false);
    LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-2, -2);
    hintParams.topMargin = dp(4);
    powerHint.setLayoutParams(hintParams);
    words.addView(powerHint);
    com.google.android.material.materialswitch.MaterialSwitch eco =
        new com.google.android.material.materialswitch.MaterialSwitch(requireContext());
    eco.setMinHeight(dp(48));
    eco.setContentDescription(getString(R.string.ui2_keep_alive));
    boolean keepAlive = !config.isEcoMode();
    eco.setChecked(keepAlive);
    power.addView(eco);
    describeKeepAlive(!keepAlive);
    eco.setOnCheckedChangeListener(
        (button, checked) -> {
          config.setEcoMode(!checked);
          com.deepseekharness.app.HarnessService.refreshPowerMode(requireContext());
          describeKeepAlive(!checked);
        });
  }

  private void describeKeepAlive(boolean ecoEnabled) {
    if (powerHint == null) return;
    powerHint.setText(
        ecoEnabled
            ? getString(R.string.ui2_keep_alive_off)
            : getString(R.string.ui2_keep_alive_on));
  }

  private void bindLanguage(View v) {
    String preference = config.getUiLanguagePreference();
    boolean followSystem = UiLanguagePreference.followsSystem(preference);
    String effective = config.getUiLanguage();
    String[] optionValues = {
      UiLanguagePreference.SYSTEM, UiLanguagePreference.ZH, UiLanguagePreference.EN
    };
    String[] optionLabels = {
      UiText.choose("跟随系统", "Follow system"), UiText.choose("简体中文", "Simplified Chinese"), "English"
    };
    int checked = followSystem ? 0 : ("en".equals(preference) ? 2 : 1);
    String currentLabel =
        "en".equals(effective)
            ? UiText.choose("English", "English")
            : UiText.choose("简体中文", "Simplified Chinese");
    String summary = followSystem ? UiText.format("跟随系统 · %s", currentLabel) : currentLabel;
    LinearLayout appearance = v.findViewById(R.id.settings_appearance);
    TextView languageSummary = v.findViewById(R.id.settings_language);
    languageSummary.setText(summary);
    View.OnClickListener openLanguageDialog =
        x ->
            new DshaDialogBuilder(requireContext())
                .setTitle(UiText.choose("界面语言 / Interface language", "Interface language"))
                .setSingleChoiceItems(
                    optionLabels,
                    checked,
                    (dialog, which) -> {
                      String selected = optionValues[which];
                      if (dialog instanceof android.app.Dialog)
                        ((android.app.Dialog) dialog)
                            .setOnDismissListener(
                                ignored ->
                                    new android.os.Handler(android.os.Looper.getMainLooper())
                                        .post(
                                            () ->
                                                LanguageController.select(
                                                    requireContext(), selected)));
                      dialog.dismiss();
                    })
                .setNegativeButton(UiText.choose("取消", "Cancel"), null)
                .show();
    appearance.setOnClickListener(openLanguageDialog);
    languageSummary.setOnClickListener(openLanguageDialog);
  }

  private void refreshDynamicRows() {
    if (!isAdded()) return;
    refreshInstallStatus();
    refreshNetworkRow();
    refreshPermissionRow();
    refreshWebRow();
    refreshOverlayStatus();
    refreshVersionRow();
  }

  private void refreshInstallStatus() {
    if (!isAdded() || installStatus == null) return;
    EnvironmentUiStatus.Snapshot snap = EnvironmentUiStatus.get(requireContext());
    if (!snap.known) return;
    if (snap.ready) {
      installStatus.setText(getString(R.string.ui2_status_ok));
      installStatus.setTextColor(requireContext().getColor(R.color.ok));
    } else {
      installStatus.setText(getString(R.string.ui2_status_needs_recovery));
      installStatus.setTextColor(requireContext().getColor(R.color.warn));
    }
  }

  private void refreshOverlayStatus() {
    if (overlaySub == null) return;
    boolean on = OverlayController.enabled(requireContext());
    if (!on) {
      overlaySub.setText(getString(R.string.ui2_overlay_off));
      return;
    }
    int alpha =
        requireContext()
            .getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, 0)
            .getInt(OverlayController.K_ALPHA, OverlayController.DEF_ALPHA);
    if (alpha < 20) alpha = 20;
    if (alpha > 100) alpha = 100;
    overlaySub.setText(getString(R.string.ui2_overlay_on_fmt, alpha));
  }

  private void refreshVersionRow() {
    if (versionView == null || updateSub == null) return;
    String version = "unknown";
    try {
      version =
          requireContext()
              .getPackageManager()
              .getPackageInfo(requireContext().getPackageName(), 0)
              .versionName;
    } catch (Exception ignored) {
    }
    versionView.setText(version);
    UpdatePolicy.Release notice = UpdateEngine.get(requireContext()).startupNotice();
    if (notice != null && notice.version != null && !notice.version.isEmpty()) {
      updateSub.setText(getString(R.string.ui2_update_available));
      updateSub.setTextColor(requireContext().getColor(R.color.primary));
      updateSub.setVisibility(View.VISIBLE);
    } else {
      updateSub.setText("");
      updateSub.setVisibility(View.GONE);
    }
  }

  private void runSelftest() {
    startActivity(new Intent(requireContext(), DiagnosticActivity.class));
  }

  private void checkUpdate() {
    startActivity(new Intent(requireContext(), UpdateActivity.class));
  }

  private void buildGroups(View v) {
    LinearLayout runtime = v.findViewById(R.id.settings_tabs);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);

    LinearLayout model =
        buildRow(
            R.drawable.ic_settings,
            UiText.choose("模型配置", "Model setup"),
            UiText.choose("服务商、密钥与模型目录", "Providers, keys and models"),
            false,
            x -> startActivity(new Intent(requireContext(), ModelSetupActivity.class)));
    runtime.addView(model, lp);

    LinearLayout network =
        buildRow(
            R.drawable.ic_ui_link,
            UiText.choose("网络与端口", "Network and ports"),
            "",
            false,
            x -> open(new NetworkFragment()));
    networkSub = subOf(network);
    runtime.addView(network, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout env =
        buildRow(
            R.drawable.ic_ui2_box,
            UiText.choose("运行环境", "Runtime environment"),
            "",
            false,
            x -> open(new InstallFragment()));
    installStatus = subOf(env);
    installStatus.setText(UiText.choose("安装与修复运行环境", "Install and repair runtime"));
    runtime.addView(env, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout permission =
        buildRow(
            R.drawable.ic_ui_shield,
            getString(R.string.permission_mode_title),
            "",
            true,
            x -> showPermissionDialog());
    permissionSub = subOf(permission);
    runtime.addView(permission, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout web =
        buildRow(
            R.drawable.ic_launch,
            UiText.choose("网页显示", "Web display"),
            "",
            true,
            x -> open(new WebDisplayFragment()));
    webSub = subOf(web);
    ((LinearLayout) v.findViewById(R.id.settings_web_host))
        .addView(web, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout data = v.findViewById(R.id.settings_data_host);
    data.addView(
        buildRow(
            R.drawable.ic_ui2_folder,
            getString(R.string.ui2_data_title),
            UiText.choose("备份恢复 · 文件共享", "Backup and restore · file sharing"),
            false,
            x -> open(new WorkspaceFragment())),
        new LinearLayout.LayoutParams(-1, -2));
    data.addView(
        buildRow(
            R.drawable.ic_recovery_shield,
            getString(R.string.ui2_device_grants),
            UiText.choose("Root · Shizuku · ADB · 权限", "Root · Shizuku · ADB · permissions"),
            true,
            x -> open(new DeviceGrantsFragment())),
        new LinearLayout.LayoutParams(-1, -2));

    ((LinearLayout) v.findViewById(R.id.settings_tasks_host))
        .addView(
            buildRow(
                R.drawable.ic_ui_tasks,
                UiText.choose("后台任务", "Background tasks"),
                UiText.choose("查看运行中与已完成的任务", "Running and finished tasks"),
                true,
                x -> BackgroundTasksActivity.open(requireContext())),
            new LinearLayout.LayoutParams(-1, -2));
  }

  private void open(Fragment target) {
    UiMotion.page(requireContext(), getParentFragmentManager().beginTransaction())
        .replace(R.id.fragment_container, target)
        .addToBackStack("settings")
        .commit();
  }

  private void refreshNetworkRow() {
    if (networkSub == null) return;
    networkSub.setText(
        getString(
            config.isLanMode() ? R.string.ui2_web_port_lan_on : R.string.ui2_web_port_lan_off,
            config.getPort()));
  }

  private String permissionLabel(String mode) {
    if ("workspace-write".equals(mode)) return getString(R.string.permission_mode_workspace);
    if ("read-only".equals(mode)) return getString(R.string.permission_mode_readonly);
    return getString(R.string.permission_mode_full);
  }

  private void refreshPermissionRow() {
    if (permissionSub == null) return;
    permissionSub.setText(
        permissionLabel(config.getPermissionMode())
            + " · "
            + getString(R.string.permission_mode_scope));
  }

  private void showPermissionDialog() {
    String current = config.getPermissionMode();
    int checked = java.util.Arrays.asList(PERMISSION_VALUES).indexOf(current);
    String[] labels = {
      getString(R.string.permission_mode_full),
      getString(R.string.permission_mode_workspace),
      getString(R.string.permission_mode_readonly)
    };
    new DshaDialogBuilder(requireContext())
        // AlertDialog 同时设置 message 与单选列表时列表不显示；范围说明放在设置行副标题。
        .setTitle(getString(R.string.permission_mode_title))
        .setSingleChoiceItems(
            labels,
            Math.max(checked, 0),
            (dialog, which) -> {
              config.setPermissionMode(PERMISSION_VALUES[which]);
              refreshPermissionRow();
              dialog.dismiss();
            })
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .show();
  }

  private void refreshWebRow() {
    if (webSub == null) return;
    String engine;
    if (BuildConfig.LOW_ANDROID) {
      engine = config.isGeckoCore() ? "Gecko" : UiText.choose("自动", "Automatic");
    } else {
      engine = "System WebView";
    }
    webSub.setText(
        engine
            + " · "
            + (config.isDesktopMode()
                ? UiText.choose("桌面布局", "Desktop layout")
                : UiText.choose("移动布局", "Mobile layout")));
  }

  private static TextView subOf(LinearLayout row) {
    return (TextView) row.getTag();
  }

  /** 通用设置行：图标 + 标题 + 副标题 + 箭头；副标题 TextView 放在 tag 里。 */
  private LinearLayout buildRow(
      int iconRes, String titleText, String subtitle, boolean last, View.OnClickListener onClick) {
    LinearLayout row = new LinearLayout(requireContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(13), dp(12), dp(13), dp(12));
    row.setMinimumHeight(dp(64));
    row.setClickable(true);
    row.setFocusable(true);
    row.setBackgroundResource(last ? R.drawable.bg_action_plain : R.drawable.bg_ui2_row);

    LinearLayout body = new LinearLayout(requireContext());
    body.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams bodyParams =
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    bodyParams.setMarginStart(dp(12));
    bodyParams.setMarginEnd(dp(8));
    body.setLayoutParams(bodyParams);

    TextView title = new TextView(requireContext());
    title.setText(titleText);
    title.setTextSize(15);
    title.setTextColor(requireContext().getColor(R.color.text));
    title.setIncludeFontPadding(false);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

    TextView sub = new TextView(requireContext());
    sub.setTextSize(13);
    sub.setTextColor(requireContext().getColor(R.color.text_secondary));
    sub.setIncludeFontPadding(false);
    sub.setText(subtitle);
    LinearLayout.LayoutParams slp =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    slp.topMargin = dp(4);
    sub.setLayoutParams(slp);

    body.addView(title);
    body.addView(sub);

    ImageView chev = new ImageView(requireContext());
    chev.setImageResource(R.drawable.ic_ui2_chevron);
    chev.setImageTintList(
        android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.text_muted)));
    chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

    ImageView icon = new ImageView(requireContext());
    icon.setImageResource(iconRes);
    icon.setImageTintList(
        android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.primary)));
    icon.setBackgroundResource(R.drawable.bg_icon_tile);
    icon.setPadding(dp(8), dp(8), dp(8), dp(8));
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
    row.addView(body);
    row.addView(chev, new LinearLayout.LayoutParams(dp(16), dp(16)));
    row.setOnClickListener(onClick);
    row.setTag(sub);
    return row;
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }
}
