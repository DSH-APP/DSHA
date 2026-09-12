package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/** 关于对话框：GitHub 仓库 / QQ 交流群入口（欢迎页 + 设置页 + 顶栏共用）。 */
public final class AboutDialog {

    public static final String GITHUB_URL = "https://github.com/qiannianhuanxiang/DSHA";
    public static final String QQ_GROUP = "975836806";

    private AboutDialog() {
    }

    public static AlertDialog show(Context ctx) {
        String version = "unknown";
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        android.view.View content=android.view.LayoutInflater.from(ctx).inflate(com.deepseekharness.app.R.layout.dialog_about,null);
        ((android.widget.TextView)content.findViewById(com.deepseekharness.app.R.id.about_version)).setText("DSHA v"+version);
        android.widget.TextView repository=content.findViewById(com.deepseekharness.app.R.id.about_repository);
        repository.setText(Uri.parse(GITHUB_URL).getPath().substring(1));
        android.widget.TextView community=content.findViewById(com.deepseekharness.app.R.id.about_community);
        community.setText(QQ_GROUP);
        AlertDialog dialog=new DshaDialogBuilder(ctx).setView(content).create();
        content.findViewById(com.deepseekharness.app.R.id.about_close).setOnClickListener(v->dialog.dismiss());
        content.findViewById(com.deepseekharness.app.R.id.about_github).setOnClickListener(v->openBrowser(ctx,GITHUB_URL));
        content.findViewById(com.deepseekharness.app.R.id.about_qq).setOnClickListener(v->openQQGroup(ctx));
        dialog.show();return dialog;
    }

    public static void openBrowser(Context ctx, String url) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(ctx, com.deepseekharness.app.util.UiText.text("打不开，请手动访问：") + url, Toast.LENGTH_SHORT).show();
        }
    }

    public static void openQQGroup(Context ctx) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "mqqapi://card/show_pslcard?src_type=internal&version=1"
                            + "&uin=" + QQ_GROUP + "&card_type=group"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(ctx, com.deepseekharness.app.util.UiText.text("打不开 QQ，请手动搜索群号：") + QQ_GROUP, Toast.LENGTH_SHORT).show();
        }
    }
}
