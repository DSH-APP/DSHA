package com.deepseekharness.app.ui;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * 设置页：模块入口（安装/配置/数据与备份）+ 其他（更新/自检/重新解压/关于）。
 */
public class SettingsFragment extends Fragment {

    private final Handler main = new Handler(Looper.getMainLooper());

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
            if (i > 0) spacing.topMargin = dp(10);
            tabs.addView(buildRow(i), spacing);
        }
        LinearLayout power = v.findViewById(R.id.settings_power);
        com.google.android.material.materialswitch.MaterialSwitch eco = new com.google.android.material.materialswitch.MaterialSwitch(requireContext());
        eco.setText(com.deepseekharness.app.util.UiText.text("省电模式")); eco.setTextSize(15); eco.setMinHeight(dp(48)); eco.setPadding(dp(16),dp(8),dp(16),dp(4));
        com.deepseekharness.app.core.ConfigStore config = HarnessController.get(requireContext()).config();
        eco.setChecked(config.isEcoMode()); power.addView(eco);
        TextView powerHint = new TextView(requireContext());
        powerHint.setTextSize(13); powerHint.setTextColor(requireContext().getColor(R.color.text_muted));
        powerHint.setPadding(dp(16),0,dp(16),dp(12)); power.addView(powerHint);
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

        v.findViewById(R.id.settings_about).setOnClickListener(x -> AboutDialog.show(requireContext()));
        v.findViewById(R.id.settings_update).setOnClickListener(x -> checkUpdate());
        v.findViewById(R.id.settings_selftest).setOnClickListener(x -> runSelftest());
        v.findViewById(R.id.settings_reextract).setOnClickListener(x -> confirmReextract());

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
        LinearLayout languageRow = buildLanguageRow(
                com.deepseekharness.app.util.UiText.choose("语言 / Language", "Language"), summary);
        LinearLayout.LayoutParams languageLayout = new LinearLayout.LayoutParams(-1, -2);
        languageLayout.topMargin = dp(10);
        // 位置保持在 4 个模块行【之后】（index 4）：这是既有验收契约 ——
        // LayoutAuditInstrumentation 按 getChildAt(1) 找「配置」、UiMotionAudit 遍历
        // getChildAt(0..3) 点四个模块，插到最前面会让它们全部错位。
        // 可发现性靠标题带英文「Language」解决，不靠挪位置。
        tabs.addView(languageRow, languageLayout);
        View.OnClickListener openLanguageDialog = x -> new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                .setTitle(com.deepseekharness.app.util.UiText.choose("界面语言 / Interface language", "Interface language"))
                .setSingleChoiceItems(optionLabels, checked,
                        (dialog, which) -> { dialog.dismiss(); LanguageController.select(requireContext(), optionValues[which]); })
                .setNegativeButton(com.deepseekharness.app.util.UiText.choose("取消", "Cancel"), null).show();
        languageRow.setOnClickListener(openLanguageDialog);
        // 自动化验收与无障碍点击落在摘要（带 settings_language id）上，它必须自己可点。
        View languageValue = languageRow.findViewById(R.id.settings_language);
        if (languageValue != null) {
            languageValue.setClickable(true);
            languageValue.setFocusable(true);
            languageValue.setOnClickListener(openLanguageDialog);
        }

        return v;
    }

    private void confirmReextract() {
        com.deepseekharness.app.core.BackupTask task = com.deepseekharness.app.core.BackupTask.get(requireContext());
        if (task.busy()) {
            Toast.makeText(requireContext(), com.deepseekharness.app.util.UiText.text("已有数据任务进行中，可到数据与备份页查看。"), Toast.LENGTH_LONG).show();
            return;
        }
        boolean recovery = task.pendingMaintenance();
        new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                .setTitle(recovery ? com.deepseekharness.app.util.UiText.text("恢复中断维护") : com.deepseekharness.app.util.UiText.text("备份并重建内置环境"))
                .setMessage(recovery ? com.deepseekharness.app.util.UiText.text("先停止 Web，再回切原环境；安全备份和失败的新环境均保留。")
                        : com.deepseekharness.app.util.UiText.text("先停止 Web 并等待退出（会中断正在执行的任务），完整备份并校验配置、会话与本地插件，再解压并恢复。\n\n")
                        + com.deepseekharness.app.util.UiText.text("备份失败不会切换环境；解压或恢复失败会回切。旧环境和安全备份留在私有目录，需要额外空间。\n")
                        + com.deepseekharness.app.util.UiText.text("原生配置和 API Key 保持原位；额外安装的系统软件留在旧环境中。"))
                .setPositiveButton(recovery ? com.deepseekharness.app.util.UiText.text("恢复原环境") : com.deepseekharness.app.util.UiText.text("备份并重建"), (d, w) -> {
                    try {
                        if (!(recovery ? task.recoverMaintenance() : task.rebuild())) {
                            Toast.makeText(requireContext(), com.deepseekharness.app.util.UiText.text("已有任务或未完成维护，请到数据与备份页查看。"), Toast.LENGTH_LONG).show();
                            return;
                        }
                        Intent i = new Intent(requireContext(), ExtractActivity.class);
                        i.putExtra("data_task_id", task.snapshot().id);
                        startActivity(i);
                    } catch (Throwable t) {
                        Toast.makeText(requireContext(), com.deepseekharness.app.util.UiText.text("打不开解压页：") + t.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(com.deepseekharness.app.util.UiText.text("算了"), null)
                .show();
    }

    private final Runnable refreshMaintenance = new Runnable() {
        @Override public void run() {
            if (getView() == null) return;
            boolean busy = com.deepseekharness.app.core.BackupTask.get(requireContext()).busy();
            View action = getView().findViewById(R.id.settings_reextract);
            action.setEnabled(!busy); action.setAlpha(busy ? 0.5f : 1f);
            main.postDelayed(this, 500);
        }
    };
    @Override public void onResume() { super.onResume(); main.post(refreshMaintenance); }
    @Override public void onPause() { main.removeCallbacks(refreshMaintenance); super.onPause(); }

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
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(64)); row.setFocusable(true);
        // 用主题的 selectableItemBackground（Material ripple），不用 Holo 的黄色 list_selector
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(R.drawable.bg_polished_action);

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bodyParams.leftMargin = dp(12); bodyParams.rightMargin = dp(8); body.setLayoutParams(bodyParams);

        TextView title = new TextView(requireContext());
        title.setText(com.deepseekharness.app.util.UiText.text(opt.title));
        title.setTextSize(16);
        title.setTextColor(requireContext().getColor(R.color.text));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        TextView sub = new TextView(requireContext());
        sub.setText(com.deepseekharness.app.util.UiText.text(opt.sub));
        sub.setTextSize(13);
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
        icon.setImageResource(index == 0 ? R.drawable.ic_terminal : index == 1 ? R.drawable.ic_settings
                : index == 2 ? R.drawable.ic_plugins : R.drawable.ic_ui_shield);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.primary)));
        icon.setBackgroundResource(R.drawable.bg_logo); icon.setPadding(dp(10),dp(10),dp(10),dp(10));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon,new LinearLayout.LayoutParams(dp(40),dp(40)));
        row.addView(body);
        row.addView(chev);
        row.setOnClickListener(v -> UiMotion.page(requireContext(), getParentFragmentManager().beginTransaction())
                .replace(R.id.fragment_container, opt.factory.get())
                .addToBackStack("settings")
                .commit());
        return row;
    }

    /**
     * 语言行：与设置页其它行一致的卡片样式 + 右侧「›」，表明可点。
     *
     * <p>文档 id 落在摘要 TextView 上（{@code settings_language}），这样自动化验收点它
     * 就能拿到当前语言；整行、标题和摘要都挂了同一个点击监听，点哪都能打开选择框。
     */
    private LinearLayout buildLanguageRow(String title, String summary) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(64));
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.bg_polished_action);

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bodyParams.leftMargin = dp(12);
        bodyParams.rightMargin = dp(8);
        body.setLayoutParams(bodyParams);

        TextView head = new TextView(requireContext());
        head.setText(title);
        head.setTextSize(16);
        head.setTextColor(requireContext().getColor(R.color.text));
        head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);

        TextView sub = new TextView(requireContext());
        sub.setText(summary);
        sub.setTextSize(13);
        sub.setTextColor(requireContext().getColor(R.color.text_muted));
        sub.setId(R.id.settings_language);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        sub.setLayoutParams(slp);

        body.addView(head);
        body.addView(sub);

        TextView chev = new TextView(requireContext());
        chev.setText(com.deepseekharness.app.util.UiText.text("›"));
        chev.setTextSize(18);
        chev.setTextColor(requireContext().getColor(R.color.text_muted));

        row.addView(body);
        row.addView(chev);
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
