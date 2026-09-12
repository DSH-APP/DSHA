package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import java.io.InputStream;

/** 原生下载反馈；SAF 只在完整下载之后打开，取消不会留下假成功文件。 */
public final class WebDownloads {
    private final AppCompatActivity activity;
    final WebDownloadModel model;
    private final ActivityResultLauncher<Intent> picker;
    private AlertDialog dialog;
    public WebDownloads(AppCompatActivity activity, Bundle saved) {
        this.activity = activity;
        model = new ViewModelProvider(activity).get(WebDownloadModel.class);
        model.restoreState(saved);
        picker = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result ->
                model.save(result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null ? result.getData().getData() : null));
        model.state.observe(activity, state -> {
            if (activity.isFinishing()) return;
            boolean working = state.phase().equals("downloading") || state.phase().equals("saving");
            if (working) {
                if (dialog == null) dialog = new com.deepseekharness.app.ui.DshaDialogBuilder(activity).setTitle(com.deepseekharness.app.util.UiText.text("保存网页文件"))
                        .setMessage(com.deepseekharness.app.util.UiText.text("")).setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), (d,w) -> model.cancel()).setCancelable(false).create();
                String count = String.format(java.util.Locale.ROOT, "\n%.1f MiB", state.bytes()/1048576.0);
                if (state.total() > 0) count += String.format(java.util.Locale.ROOT, " / %.1f MiB",state.total()/1048576.0);
                dialog.setMessage(com.deepseekharness.app.util.UiStateText.render(state.message()) + count); dialog.show();
            } else {
                dismiss();
                if (state.phase().equals("ready") && !model.pickerOpen) {
                    model.pickerOpen = true;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, model.fileName());
                    try { picker.launch(intent); }
                    catch (RuntimeException error) { model.pickerOpen = false; model.acknowledge();
                        Toast.makeText(activity,com.deepseekharness.app.util.UiText.text("无法打开保存位置选择器，请启用系统文件应用后重试"),Toast.LENGTH_LONG).show(); }
                } else if (state.phase().equals("done") || state.phase().equals("error")) {
                    if (state.phase().equals("error") && model.canRetrySave()) {
                        dialog = new com.deepseekharness.app.ui.DshaDialogBuilder(activity).setTitle(com.deepseekharness.app.util.UiText.text("文件尚未保存")).setMessage(com.deepseekharness.app.util.UiStateText.render(state.message()))
                                .setPositiveButton(com.deepseekharness.app.util.UiText.text("更换位置重试"),(d,w) -> model.retrySave())
                                .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"),(d,w) -> model.acknowledge()).setCancelable(false).create(); dialog.show();
                    } else Toast.makeText(activity,state.message(),Toast.LENGTH_LONG).show();
                }
            }
        });
    }
    public void start(String base, String url, String cookie, String name, long size, InputStream body) {
        if (!model.download(base,url,cookie,name,size,body) && model.isBusy())
            Toast.makeText(activity,com.deepseekharness.app.util.UiText.text("请先完成或取消当前文件下载"),Toast.LENGTH_SHORT).show();
    }
    public void dismiss() { if (dialog != null) { dialog.dismiss(); dialog = null; } }
}
