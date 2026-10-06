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

import com.deepseekharness.app.OverlayController;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.UpdateEngine;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.UpdatePolicy;

import java.util.function.Supplier;

/**
 * 设置页：运行 / 设备与数据入口 + 偏好 + 支持。
 */
public class SettingsFragment extends Fragment {

  private static final TabOption[] TAB_OPTIONS = {
    new TabOption("安装", "安装与修复运行环境", InstallFragment::new),
    new TabOption("配置", "接口、显示与运行", ConfigFragment::new),
    new TabOption("数据与备份", "备份恢复 · 文件共享", WorkspaceFragment::new),
    new TabOption("设备能力授权", "Root · Shizuku · ADB · 权限", DeviceGrantsFragment::new),
  };

  private TextView installStatus;
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
    LinearLayout tabs = v.findViewById(R.id.settings_tabs);
    for (int i = 0; i < TAB_OPTIONS.length; i++) {
      tabs.addView(buildRow(i), new LinearLayout.LayoutParams(-1, -2));
    }
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

  private LinearLayout buildRow(final int index) {
    TabOption opt = TAB_OPTIONS[index];
    LinearLayout row = new LinearLayout(requireContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(13), dp(12), dp(13), dp(12));
    row.setMinimumHeight(dp(64));
    row.setClickable(true);
    row.setFocusable(true);
    row.setBackgroundResource(
        index == TAB_OPTIONS.length - 1 ? R.drawable.bg_action_plain : R.drawable.bg_ui2_row);

    LinearLayout body = new LinearLayout(requireContext());
    body.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams bodyParams =
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    bodyParams.setMarginStart(dp(12));
    bodyParams.setMarginEnd(dp(8));
    body.setLayoutParams(bodyParams);

    TextView title = new TextView(requireContext());
    title.setText(rowTitle(index, opt));
    title.setTextSize(15);
    title.setTextColor(requireContext().getColor(R.color.text));
    title.setIncludeFontPadding(false);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

    TextView sub = new TextView(requireContext());
    sub.setTextSize(13);
    sub.setTextColor(requireContext().getColor(R.color.text_secondary));
    sub.setIncludeFontPadding(false);
    sub.setText(rowSubtitle(index, opt, sub));
    LinearLayout.LayoutParams slp =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    slp.topMargin = dp(4);
    sub.setLayoutParams(slp);
    if (index == 0) installStatus = sub;

    body.addView(title);
    body.addView(sub);

    ImageView chev = new ImageView(requireContext());
    chev.setImageResource(R.drawable.ic_ui2_chevron);
    chev.setImageTintList(
        android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.text_muted)));
    chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

    ImageView icon = new ImageView(requireContext());
    icon.setImageResource(
        index == 0
            ? R.drawable.ic_ui2_box
            : index == 1
                ? R.drawable.ic_settings
                : index == 2 ? R.drawable.ic_ui2_folder : R.drawable.ic_ui_shield);
    icon.setImageTintList(
        android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.primary)));
    icon.setBackgroundResource(R.drawable.bg_icon_tile);
    icon.setPadding(dp(8), dp(8), dp(8), dp(8));
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
    row.addView(body);
    row.addView(chev, new LinearLayout.LayoutParams(dp(16), dp(16)));
    row.setOnClickListener(
        v ->
            UiMotion.page(requireContext(), getParentFragmentManager().beginTransaction())
                .replace(R.id.fragment_container, opt.factory.get())
                .addToBackStack("settings")
                .commit());
    return row;
  }

  private String rowTitle(int index, TabOption opt) {
    if (index == 0) return getString(R.string.ui2_install_environment);
    if (index == 1) return getString(R.string.ui2_ports);
    if (index == 2) return getString(R.string.ui2_data_title);
    if (index == 3) return getString(R.string.ui2_device_grants);
    return UiText.text(opt.title);
  }

  private String rowSubtitle(int index, TabOption opt, TextView sub) {
    if (index == 0) {
      EnvironmentUiStatus.Snapshot snap = EnvironmentUiStatus.get(requireContext());
      if (snap.known) {
        if (snap.ready) {
          sub.setTextColor(requireContext().getColor(R.color.ok));
          return getString(R.string.ui2_status_ok);
        }
        sub.setTextColor(requireContext().getColor(R.color.warn));
        return getString(R.string.ui2_status_needs_recovery);
      }
      return UiText.text(opt.sub);
    }
    if (index == 1) {
      String port = config.getPort();
      return getString(
          config.isLanMode() ? R.string.ui2_web_port_lan_on : R.string.ui2_web_port_lan_off, port);
    }
    return UiText.text(opt.sub);
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  private static final class TabOption {
    final String title;
    final String sub;
    final Supplier<Fragment> factory;

    TabOption(String title, String sub, Supplier<Fragment> factory) {
      this.title = title;
      this.sub = sub;
      this.factory = factory;
    }
  }
}
