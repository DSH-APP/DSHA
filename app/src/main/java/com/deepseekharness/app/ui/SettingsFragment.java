package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;

import java.util.function.Supplier;

/**
 * 设置页：模块入口（安装/配置/数据与备份）+ 其他（更新/自检/重新解压/关于）。
 */
public class SettingsFragment extends Fragment {


    private static final TabOption[] TAB_OPTIONS = {
            new TabOption("安装", "安装与修复运行环境", InstallFragment::new),
            new TabOption("配置", "接口、显示与运行", ConfigFragment::new),
            new TabOption("数据与备份", "备份恢复 · 文件共享", WorkspaceFragment::new),
            new TabOption("设备能力授权", "Root · Shizuku · ADB · 权限", DeviceGrantsFragment::new),
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);

        LinearLayout tabs = v.findViewById(R.id.settings_tabs);
        for (int i = 0; i < TAB_OPTIONS.length; i++) {
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) spacing.topMargin = 0;
            tabs.addView(buildRow(i), spacing);
        }
        LinearLayout power = v.findViewById(R.id.settings_power);
        power.setOrientation(LinearLayout.HORIZONTAL);power.setGravity(Gravity.CENTER_VERTICAL);power.setPadding(dp(13),dp(6),dp(13),dp(6));power.setMinimumHeight(dp(60));
        LinearLayout words=new LinearLayout(requireContext());words.setOrientation(LinearLayout.VERTICAL);power.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        TextView heading=new TextView(requireContext());heading.setText(com.deepseekharness.app.util.UiText.text("省电模式"));heading.setTextSize(13);heading.setTextColor(requireContext().getColor(R.color.text));heading.setIncludeFontPadding(false);words.addView(heading);
        TextView powerHint=new TextView(requireContext());powerHint.setTextSize(11);powerHint.setTextColor(requireContext().getColor(R.color.text_muted));powerHint.setPadding(0,dp(4),dp(8),0);powerHint.setIncludeFontPadding(false);words.addView(powerHint);
        com.google.android.material.materialswitch.MaterialSwitch eco=new com.google.android.material.materialswitch.MaterialSwitch(requireContext());eco.setMinHeight(dp(48));eco.setContentDescription(com.deepseekharness.app.util.UiText.text("省电模式"));
        com.deepseekharness.app.core.ConfigStore config=HarnessController.get(requireContext()).config();eco.setChecked(config.isEcoMode());power.addView(eco);
        java.util.function.Consumer<Boolean> describe = enabled -> powerHint.setText(enabled
                ? com.deepseekharness.app.util.UiText.text("熄屏空闲 1 分钟后减少保活；有任务时继续运行。")
                : com.deepseekharness.app.util.UiText.text("持续保持运行，适合长时间任务。"));
        describe.accept(config.isEcoMode());
        eco.setOnCheckedChangeListener((button, checked) -> {
            config.setEcoMode(checked); com.deepseekharness.app.HarnessService.refreshPowerMode(); describe.accept(checked);
        });

        String version = "unknown";
        try {
            version = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView ver = v.findViewById(R.id.settings_ver);
        ver.setText(com.deepseekharness.app.util.UiText.text("DSHA v" + version + com.deepseekharness.app.util.UiText.choose(" · MIT 许可", " · MIT License")));
        TextView updateSub = v.findViewById(R.id.settings_update_sub);
        updateSub.setText(com.deepseekharness.app.util.UiText.text("稳定版与预览版更新"));

        v.findViewById(R.id.settings_about).setOnClickListener(x -> UiMotion.page(requireContext(),getParentFragmentManager().beginTransaction()).replace(R.id.fragment_container,new AboutFragment()).addToBackStack("settings").commit());
        v.findViewById(R.id.settings_update).setOnClickListener(x -> checkUpdate());
        v.findViewById(R.id.settings_selftest).setOnClickListener(x -> runSelftest());

        // 语言入口：标题在中文界面下也带英文「Language」（只写「语言 · 简体中文」时，
        // 非中文用户根本认不出这是语言开关）；摘要直接显示当前生效语言。
        // 放在「常用设置」分组最前面，不用滚动就能看到。
        String preference = config.getUiLanguagePreference();
        boolean followSystem = com.deepseekharness.app.util.UiLanguagePreference.followsSystem(preference);
        String effective = config.getUiLanguage();
        String[] optionValues = {
                com.deepseekharness.app.util.UiLanguagePreference.SYSTEM,
                com.deepseekharness.app.util.UiLanguagePreference.ZH,
                com.deepseekharness.app.util.UiLanguagePreference.EN};
        String[] optionLabels = {
                com.deepseekharness.app.util.UiText.choose("跟随系统", "Follow system"),
                com.deepseekharness.app.util.UiText.choose("简体中文", "Simplified Chinese"),
                "English"};
        int checked = followSystem ? 0 : ("en".equals(preference) ? 2 : 1);
        // 摘要始终显示当前**生效**语言的名字：跟随系统时补上来由，用户一眼能看出实际结果。
        String currentLabel = "en".equals(effective)
                ? com.deepseekharness.app.util.UiText.choose("English", "English")
                : com.deepseekharness.app.util.UiText.choose("简体中文", "Simplified Chinese");
        String summary = followSystem
                ? com.deepseekharness.app.util.UiText.choose("跟随系统 · ", "Follow system · ") + currentLabel
                : currentLabel;
        LinearLayout appearance=v.findViewById(R.id.settings_appearance);
        TextView languageSummary=(TextView)((LinearLayout)appearance.getChildAt(1)).getChildAt(1);
        languageSummary.setId(R.id.settings_language);languageSummary.setText(summary);
        View.OnClickListener openLanguageDialog = x -> new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                .setTitle(com.deepseekharness.app.util.UiText.choose("界面语言 / Interface language", "Interface language"))
                .setSingleChoiceItems(optionLabels, checked,
                        (dialog, which) -> {
                            String selected=optionValues[which];
                            if (dialog instanceof android.app.Dialog)
                                ((android.app.Dialog)dialog).setOnDismissListener(ignored -> new android.os.Handler(android.os.Looper.getMainLooper())
                                        .post(() -> LanguageController.select(requireContext(), selected)));
                            dialog.dismiss();
                        })
                .setNegativeButton(com.deepseekharness.app.util.UiText.choose("取消", "Cancel"), null).show();
        appearance.setOnClickListener(openLanguageDialog);
        languageSummary.setOnClickListener(openLanguageDialog);
        v.findViewById(R.id.settings_overlay).setOnClickListener(x->UiMotion.page(requireContext(),getParentFragmentManager().beginTransaction()).replace(R.id.fragment_container,new OverlayFragment()).addToBackStack("settings").commit());
        return v;
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
        row.setPadding(dp(13), dp(11), dp(13), dp(11));
        row.setMinimumHeight(dp(64)); row.setFocusable(true);
        // 用主题的 selectableItemBackground（Material ripple），不用 Holo 的黄色 list_selector
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(index==TAB_OPTIONS.length-1?R.drawable.bg_action_plain:R.drawable.bg_ui2_row);

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bodyParams.leftMargin = dp(12); bodyParams.rightMargin = dp(8); body.setLayoutParams(bodyParams);

        TextView title = new TextView(requireContext());
        title.setText(index==0?getString(R.string.ui2_install_environment):index==1?getString(R.string.ui2_runtime_config):com.deepseekharness.app.util.UiText.text(opt.title));
        title.setTextSize(13);
        title.setTextColor(requireContext().getColor(R.color.text));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        TextView sub = new TextView(requireContext());
        sub.setText(com.deepseekharness.app.util.UiText.text(opt.sub));
        sub.setTextSize(11);
        sub.setTextColor(requireContext().getColor(R.color.text_muted));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        sub.setLayoutParams(slp);

        body.addView(title);
        body.addView(sub);

        TextView chev = new TextView(requireContext());
        chev.setText(com.deepseekharness.app.util.UiText.text("›"));
        chev.setTextSize(18);
        chev.setTextColor(requireContext().getColor(R.color.text_muted));

        android.widget.ImageView icon = new android.widget.ImageView(requireContext());
        icon.setImageResource(index == 0 ? R.drawable.ic_ui2_box : index == 1 ? R.drawable.ic_settings
                : index == 2 ? R.drawable.ic_ui2_folder : R.drawable.ic_ui_shield);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.primary)));
        icon.setBackgroundResource(R.drawable.bg_logo); icon.setPadding(dp(7),dp(7),dp(7),dp(7));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon,new LinearLayout.LayoutParams(dp(32),dp(32)));
        row.addView(body);
        row.addView(chev);
        row.setOnClickListener(v -> UiMotion.page(requireContext(), getParentFragmentManager().beginTransaction())
                .replace(R.id.fragment_container, opt.factory.get())
                .addToBackStack("settings")
                .commit());
        return row;
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
