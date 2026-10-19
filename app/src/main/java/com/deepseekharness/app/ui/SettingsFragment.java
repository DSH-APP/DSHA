package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.miuix.MiuixSettings;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;

/** Miuix 设置界面；业务入口、语言选择与省电服务沿用既有实现。 */
public class SettingsFragment extends Fragment {
  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    ConfigStore config = HarnessController.get(requireContext()).config();
    String current =
        "en".equals(config.getUiLanguage())
            ? "English"
            : UiText.choose("简体中文", "Simplified Chinese");
    String summary =
        UiLanguagePreference.followsSystem(config.getUiLanguagePreference())
            ? UiText.format("跟随系统 · %s", current)
            : current;
    return new MiuixSettings(
        requireContext(),
        com.deepseekharness.app.BuildConfig.VERSION_NAME,
        summary,
        config.isEcoMode(),
        this::open,
        checked -> {
          config.setEcoMode(checked);
          com.deepseekharness.app.HarnessService.refreshPowerMode(requireContext());
        });
  }

  void open(int id) {
    if (!isAdded() || getParentFragmentManager().isStateSaved()) return;
    if (id == R.id.settings_appearance) {
      selectLanguage();
      return;
    }
    if (id == R.id.settings_update || id == R.id.settings_selftest) {
      startActivity(
          new Intent(
              requireContext(),
              id == R.id.settings_update ? UpdateActivity.class : DiagnosticActivity.class));
      return;
    }
    Fragment page;
    if (id == R.id.settings_install) page = new InstallFragment();
    else if (id == R.id.settings_config) page = new ConfigFragment();
    else if (id == R.id.settings_data) page = new WorkspaceFragment();
    else if (id == R.id.settings_device) page = new DeviceGrantsFragment();
    else if (id == R.id.settings_overlay) page = new OverlayFragment();
    else if (id == R.id.settings_about) page = new AboutFragment();
    else return;
    UiMotion.page(requireContext(), getParentFragmentManager().beginTransaction())
        .replace(R.id.fragment_container, page)
        .addToBackStack("settings")
        .commit();
  }

  private void selectLanguage() {
    ConfigStore config = HarnessController.get(requireContext()).config();
    String preference = config.getUiLanguagePreference();
    String[] values = {
      UiLanguagePreference.SYSTEM, UiLanguagePreference.ZH, UiLanguagePreference.EN
    };
    String[] labels = {
      UiText.choose("跟随系统", "Follow system"), UiText.choose("简体中文", "Simplified Chinese"), "English"
    };
    int checked =
        UiLanguagePreference.followsSystem(preference) ? 0 : ("en".equals(preference) ? 2 : 1);
    // 先关闭弹窗再让 AppCompat 重建界面，后台终端和 Web 不参与语言切换。
    android.content.Context context = requireContext();
    new DshaDialogBuilder(context)
        .setTitle(UiText.choose("界面语言 / Interface language", "Interface language"))
        .setSingleChoiceItems(
            labels,
            checked,
            (dialog, which) -> {
              if (dialog instanceof android.app.Dialog)
                ((android.app.Dialog) dialog)
                    .setOnDismissListener(
                        ignored ->
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(() -> LanguageController.select(context, values[which])));
              dialog.dismiss();
            })
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .show();
  }
}
