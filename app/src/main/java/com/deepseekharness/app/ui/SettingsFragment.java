package com.deepseekharness.app.ui;

import android.app.AlertDialog;
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
            new TabOption("配置", "接口、行为与权限", ConfigFragment::new),
            new TabOption("数据与备份", "备份恢复 · 文件共享", WorkspaceFragment::new),
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);

        LinearLayout tabs = v.findViewById(R.id.settings_tabs);
        for (int i = 0; i < TAB_OPTIONS.length; i++) {
            if (i > 0) {
                View divider = new View(requireContext());
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
                divider.setBackgroundColor(requireContext().getColor(R.color.line));
                tabs.addView(divider);
            }
            tabs.addView(buildRow(i));
        }
        LinearLayout power = v.findViewById(R.id.settings_power);
        com.google.android.material.materialswitch.MaterialSwitch eco = new com.google.android.material.materialswitch.MaterialSwitch(requireContext());
        eco.setText("省电模式"); eco.setTextSize(15); eco.setMinHeight(dp(48)); eco.setPadding(dp(16),dp(8),dp(16),dp(4));
        com.deepseekharness.app.core.ConfigStore config = HarnessController.get(requireContext()).config();
        eco.setChecked(config.isEcoMode()); power.addView(eco);
        TextView powerHint = new TextView(requireContext());
        powerHint.setTextSize(13); powerHint.setTextColor(requireContext().getColor(R.color.text_muted));
        powerHint.setPadding(dp(16),0,dp(16),dp(12)); power.addView(powerHint);
        java.util.function.Consumer<Boolean> describe = enabled -> powerHint.setText(enabled
                ? "熄屏空闲 1 分钟后减少保活；有任务时继续运行。"
                : "持续保持运行，适合长时间任务。");
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
        ver.setText("DSHA v" + version + " · MIT License");
        TextView updateSub = v.findViewById(R.id.settings_update_sub);
        updateSub.setText("稳定版与预览版更新");

        v.findViewById(R.id.settings_about).setOnClickListener(x -> AboutDialog.show(requireContext()));
        v.findViewById(R.id.settings_update).setOnClickListener(x -> checkUpdate());
        v.findViewById(R.id.settings_selftest).setOnClickListener(x -> runSelftest());
        v.findViewById(R.id.settings_reextract).setOnClickListener(x -> confirmReextract());

        return v;
    }

    private void confirmReextract() {
        com.deepseekharness.app.core.BackupTask task = com.deepseekharness.app.core.BackupTask.get(requireContext());
        if (task.busy()) {
            Toast.makeText(requireContext(), "已有数据任务进行中，可到数据与备份页查看。", Toast.LENGTH_LONG).show();
            return;
        }
        boolean recovery = task.pendingMaintenance();
        new AlertDialog.Builder(requireContext())
                .setTitle(recovery ? "恢复中断维护" : "备份并重建内置环境")
                .setMessage(recovery ? "先停止 Web，再回切原环境；安全备份和失败的新环境均保留。"
                        : "先停止 Web 并等待退出（会中断正在执行的任务），完整备份并校验配置、会话与本地插件，再解压并恢复。\n\n"
                        + "备份失败不会切换环境；解压或恢复失败会回切。旧环境和安全备份留在私有目录，需要额外空间。\n"
                        + "原生配置和 API Key 保持原位；额外安装的系统软件留在旧环境中。")
                .setPositiveButton(recovery ? "恢复原环境" : "备份并重建", (d, w) -> {
                    try {
                        if (!(recovery ? task.recoverMaintenance() : task.rebuild())) {
                            Toast.makeText(requireContext(), "已有任务或未完成维护，请到数据与备份页查看。", Toast.LENGTH_LONG).show();
                            return;
                        }
                        Intent i = new Intent(requireContext(), ExtractActivity.class);
                        i.putExtra("data_task_id", task.snapshot().id);
                        startActivity(i);
                    } catch (Throwable t) {
                        Toast.makeText(requireContext(), "打不开解压页：" + t.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("算了", null)
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
        row.setBackgroundResource(tv.resourceId);

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bodyParams.leftMargin = dp(12); bodyParams.rightMargin = dp(8); body.setLayoutParams(bodyParams);

        TextView title = new TextView(requireContext());
        title.setText(opt.title);
        title.setTextSize(15);
        title.setTextColor(requireContext().getColor(R.color.text));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        TextView sub = new TextView(requireContext());
        sub.setText(opt.sub);
        sub.setTextSize(13);
        sub.setTextColor(requireContext().getColor(R.color.text_muted));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        sub.setLayoutParams(slp);

        body.addView(title);
        body.addView(sub);

        TextView chev = new TextView(requireContext());
        chev.setText("›");
        chev.setTextSize(18);
        chev.setTextColor(requireContext().getColor(R.color.text_muted));

        android.widget.ImageView icon = new android.widget.ImageView(requireContext());
        icon.setImageResource(index == 0 ? R.drawable.ic_terminal : index == 1 ? R.drawable.ic_settings : R.drawable.ic_plugins);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.text_secondary)));
        icon.setBackgroundResource(R.drawable.bg_chip); icon.setPadding(dp(7),dp(7),dp(7),dp(7));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon,new LinearLayout.LayoutParams(dp(32),dp(32)));
        row.addView(body);
        row.addView(chev);
        row.setOnClickListener(v -> getParentFragmentManager().beginTransaction()
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
