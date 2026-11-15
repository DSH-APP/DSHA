package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.StorageMaintenance;
import com.deepseekharness.app.util.UiText;

public final class StorageActivity extends AppCompatActivity {
  private CardPage page;
  private LinearLayout rows, usage;
  private TextView total;

  /** 分布条色板：主题 token（含夜间），不另造硬编码颜色。 */
  private static final int[] PALETTE = {
    R.color.shell_accent, R.color.shell_good, R.color.shell_warn,
    R.color.shell_bad, R.color.primary_dark, R.color.shell_text_muted
  };

  private TextView state;
  private Button clean, refresh;
  private boolean busy;

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    page =
        new CardPage(
            this, t("存储与文件", "Storage and files"), t("空间占用与清理", "Storage usage and cleanup"));
    // 摘要进页首，完整说明点开 ⓘ（审计 D-06）。
    InfoHint.bind(
        page.subtitle,
        t("存储与文件", "Storage and files"),
        t("空间占用与清理", "Storage usage and cleanup"),
        t(
            "完整 Linux 环境约占 1 GB。历史环境和回退副本另行保留；它们不等于聊天数据。以下为文件大小估算。",
            "The full Linux environment uses about 1 GB. Older environments and rollback copies are retained separately from chats. Sizes below are estimates."));
    UiNavigation.addHeader(this, page.root, t("存储与文件", "Storage and files"), this::finish);
    var files = page.card();
    page.kv(
        files,
        t("工作目录", "Workspace"),
        com.deepseekharness.app.core.HarnessController.get(this).config().getWorkdir());
    page.entry(
        files,
        t("文件共享", "File sharing"),
        t("通过系统文件选择器或 MT 管理器访问 DSHA", "Access DSHA from the system file picker or MT Manager"),
        R.drawable.ic_ui2_box,
        () ->
            CardSheet.show(
                this,
                t("文件共享", "File sharing"),
                t(
                    "在文件管理器中添加本地存储，选择 DocumentsProvider → DSHA。找不到 DSHA 时，先打开本应用后重试。",
                    "Add local storage in your file manager and select DocumentsProvider → DSHA. Open this app first if DSHA is missing.")));
    LinearLayout overview = page.card();
    total = page.text("", 22, R.color.shell_text);
    total.setTypeface(null, android.graphics.Typeface.BOLD);
    total.setPadding(0, page.dp(6), 0, 0);
    overview.addView(total);
    usage = new LinearLayout(this);
    usage.setOrientation(LinearLayout.HORIZONTAL);
    usage.setBackgroundResource(R.drawable.bg_data_status_tile);
    usage.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(getColor(R.color.shell_raised)));
    usage.setClipToOutline(true);
    LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, page.dp(14));
    bar.topMargin = page.dp(12);
    bar.bottomMargin = page.dp(6);
    overview.addView(usage, bar);
    usage.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    rows = overview;
    state = page.text("", 12, R.color.text_secondary);
    page.content.addView(state);
    clean =
        page.button(
            page.footer,
            t("清理可再生缓存与多余运行时副本", "Clean reproducible caches and excess runtime copies"),
            true,
            () ->
                new DshaDialogBuilder(this, true)
                    .setTitle(t("清理存储空间", "Clean storage"))
                    .setMessage(
                        t(
                            "清理前会安全停止 DSH 和终端，再清理历史验收缓存，以及超过保留数量且已核验的受管回退副本。对话、附件、设置、插件、手动备份和无法核验的原件保留。",
                            "DSH and terminals are stopped safely before cleanup. Removes old verification caches and verified excess managed rollback copies. Keeps chats, attachments, settings, plugins, manual backups and unverified originals."))
                    .setPositiveButton(t("开始清理", "Clean"), (d, w) -> load(true))
                    .setNegativeButton(t("取消", "Cancel"), null)
                    .show());
    refresh = page.button(page.footer, t("重新统计", "Refresh sizes"), false, () -> load(false));
    setContentView(page.root);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    load(false);
  }

  private void load(boolean remove) {
    if (busy) return;
    busy = true;
    clean.setEnabled(false);
    refresh.setEnabled(false);
    state.setText(t("正在处理…", "Working…"));
    new Thread(
            () -> {
              try {
                long reclaimed = remove ? StorageMaintenance.clean(this) : 0;
                var sizes = StorageMaintenance.inspect(this);
                runOnUiThread(
                    () -> {
                      if (isFinishing() || isDestroyed()) return;
                      renderUsage(sizes);
                      state.setText(
                          remove
                              ? UiText.format(
                                  "已释放 %s", com.deepseekharness.app.util.Fmt.bytes(reclaimed))
                              : t(
                                  "统计完成；清理只删可再生缓存",
                                  "Sizes updated. Cleanup removes only reproducible caches and verified excess runtime copies. Personal data and backups are retained."));
                      done();
                    });
              } catch (Exception error) {
                runOnUiThread(
                    () -> {
                      if (isFinishing() || isDestroyed()) return;
                      state.setText(
                          com.deepseekharness.app.util.MaintenanceErrorText.render(
                              com.deepseekharness.app.backup.NativeBackupJobs.code(error)));
                      done();
                    });
              }
            },
            "storage-maintenance")
        .start();
  }

  /** 总量 + 分布条 + 图例行；图例保留原有「部分不可读」提示，色块与条形一一对应。 */
  private void renderUsage(java.util.Map<String, StorageMaintenance.Size> sizes) {
    java.util.Map<String, Long> bytes = new java.util.LinkedHashMap<>();
    for (var entry : sizes.entrySet()) bytes.put(entry.getKey(), entry.getValue().bytes);
    RollingNumber.bytes(total, com.deepseekharness.app.util.StorageUsage.total(bytes));
    usage.removeAllViews();
    while (rows.getChildCount() > 2) rows.removeViewAt(2);
    var segments = com.deepseekharness.app.util.StorageUsage.segments(bytes, PALETTE.length);
    java.util.Map<String, Integer> colors = new java.util.HashMap<>();
    for (int i = 0; i < segments.size(); i++) {
      var segment = segments.get(i);
      int color = getColor(PALETTE[i]);
      if (segment.key != null) colors.put(segment.key, color);
      android.view.View part = new android.view.View(this);
      part.setBackgroundColor(color);
      usage.addView(
          part, new LinearLayout.LayoutParams(0, -1, (float) Math.max(segment.fraction, 0.004)));
    }
    UiMotion.reveal(usage);
    int other =
        segments.isEmpty() || segments.get(segments.size() - 1).key != null
            ? 0
            : getColor(PALETTE[segments.size() - 1]);
    for (var entry : sizes.entrySet()) {
      int before = rows.getChildCount();
      page.kv(
          rows,
          com.deepseekharness.app.util.StorageCategoryLabels.label(entry.getKey()),
          com.deepseekharness.app.util.Fmt.bytes(entry.getValue().bytes)
              + (entry.getValue().unreadable > 0 ? t(" + 部分不可读", " + unreadable entries") : ""));
      if (rows.getChildCount() == before) continue;
      Integer color = colors.get(entry.getKey());
      if (color == null && entry.getValue().bytes > 0 && other != 0) color = other;
      TextView label =
          (TextView) ((LinearLayout) rows.getChildAt(rows.getChildCount() - 1)).getChildAt(0);
      android.graphics.drawable.GradientDrawable dot =
          new android.graphics.drawable.GradientDrawable();
      dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
      dot.setColor(color == null ? getColor(R.color.shell_raised) : color);
      int size = page.dp(10);
      dot.setSize(size, size);
      label.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
      label.setCompoundDrawablePadding(page.dp(8));
    }
  }

  private void done() {
    busy = false;
    clean.setEnabled(true);
    refresh.setEnabled(true);
  }
}
