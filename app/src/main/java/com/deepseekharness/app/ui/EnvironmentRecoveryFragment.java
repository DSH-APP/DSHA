package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;

/** 环境未完成时仍可进入主界面，明确列出可用的原生恢复入口。 */
public final class EnvironmentRecoveryFragment extends Fragment {
  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    CardPage ui =
        new CardPage(
            requireContext(),
            com.deepseekharness.app.util.UiText.text("运行环境需要恢复"),
            com.deepseekharness.app.util.UiText.text(
                "可以在设置中修改配置、查看数据维护记录和下载日志。环境恢复完成后，再使用插件和终端。个人数据与原环境继续保留。"));
    // 页副标题只留首句（还能在设置里做什么）；恢复后才能用的组件与数据保留在 ⓘ 里。
    InfoHint.attach(ui.subtitle, com.deepseekharness.app.util.UiText.text("运行环境需要恢复"));
    ui.button(
        ui.content,
        com.deepseekharness.app.util.UiText.choose("启动应急 DSH", "Start emergency DSH"),
        true,
        () -> startActivity(new Intent(requireContext(), RecoveryActivity.class)));
    ui.button(
        ui.content,
        com.deepseekharness.app.util.UiText.text("查看维护进度与恢复选项"),
        false,
        () ->
            startActivity(
                new Intent(requireContext(), ExtractActivity.class).putExtra("review_only", true)));
    ui.button(
        ui.content,
        com.deepseekharness.app.util.UiText.text("查看并下载诊断日志"),
        false,
        () -> startActivity(DiagnosticActivity.downloadLogs(requireContext())));
    return ui.root;
  }
}
