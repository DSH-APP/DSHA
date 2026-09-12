package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;

/** 环境未完成时仍可进入主界面，明确列出可用的原生恢复入口。 */
public final class EnvironmentRecoveryFragment extends Fragment {
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(requireContext());
        title.setText(com.deepseekharness.app.util.UiText.text("运行环境需要恢复")); title.setTextSize(20);
        title.setTextColor(requireContext().getColor(R.color.text)); content.addView(title);
        TextView hint = new TextView(requireContext());
        hint.setText(com.deepseekharness.app.util.UiText.text("可以在设置中修改配置、查看数据维护记录和下载日志。环境恢复完成后，再使用插件和终端。个人数据与原环境继续保留。\n"));
        hint.setTextSize(15); hint.setTextColor(requireContext().getColor(R.color.text_secondary));
        content.addView(hint);
        Button repair = new Button(requireContext()); repair.setText(com.deepseekharness.app.util.UiText.text("查看维护进度与恢复选项"));
        repair.setOnClickListener(v -> startActivity(new Intent(requireContext(), ExtractActivity.class).putExtra("review_only", true)));
        content.addView(repair, new LinearLayout.LayoutParams(-1, -2));
        Button logs = new Button(requireContext()); logs.setText(com.deepseekharness.app.util.UiText.text("查看并下载诊断日志"));
        logs.setOnClickListener(v -> startActivity(DiagnosticActivity.downloadLogs(requireContext())));
        content.addView(logs, new LinearLayout.LayoutParams(-1, -2));
        return content;
    }
}
