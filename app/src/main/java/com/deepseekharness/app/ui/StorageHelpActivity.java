package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/** 存储操作的只读说明；只打开既有页面，不启动维护、扫描或删除。 */
public final class StorageHelpActivity extends AppCompatActivity {
  private CardPage page;

  public static void open(Context context) {
    Intent intent = new Intent(context, StorageHelpActivity.class);
    if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    page =
        new CardPage(
            this, UiText.text("存储、备份与重建说明"), UiText.text("先看保留范围，再选择操作。打开本页不会修改文件或停止 DSH。"));
    UiNavigation.addHeader(this, page.root, UiText.text("存储、备份与重建说明"), this::finish);
    page.content.removeViewAt(0);
    page.root.removeView(page.footer);

    LinearLayout start = section("只想腾空间，从这里开始");
    paragraph(
        start,
        "先到「存储与文件」查看占用。完整 Ubuntu、Node 和插件依赖属于应用数据，通常比 Android 缓存大得多。Android「清缓存」可能只有几十 MB，清完不会移除这套运行环境。");
    paragraph(
        start, "应用内清理只移除可再生缓存和已核验的多余运行时副本。对话、附件、设置、插件、手动备份和无法核验的原件保留。清理前仍会在原页面确认，并安全停止 DSH 和终端。");
    page.entry(
        start,
        UiText.text("查看占用与清理选项"),
        UiText.text("打开存储页后才统计详细大小；清理仍需原页面确认。"),
        R.drawable.ic_ui2_box,
        () -> startActivity(new Intent(this, StorageActivity.class)));

    LinearLayout export = section("重要数据，先导出再维护");
    paragraph(
        export, "导出选择「应用数据」可保存对话、附件、配置和用户插件，不会复制完整 Ubuntu。个人项目要另选目录；原生 API Key 默认不包含，需要时在导出页主动勾选。");
    paragraph(
        export,
        "新导出文件为 DSHA-data-v5-….tar.gz，无需备份密码。请用系统保存窗口存到 App 外的文档、下载或云盘，并另留一份。导出完成后可先做恢复预检，确认范围和文件可读；预检不会立即覆盖当前数据。");
    paragraph(export, "本机自动备份和重建保护副本仍在 App 内，不保证清除全部存储或卸载后可用。历史加密备份仍需保存原密码。");
    page.button(export, UiText.text("去导出重要数据"), true, () -> data("export"));
    page.entry(
        export,
        UiText.text("选择备份做恢复预检"),
        UiText.text("先检查文件，再由原页面确认是否恢复。"),
        R.drawable.ic_ui2_box,
        () -> data("restore"));

    LinearLayout rebuild = section("备份并重建环境");
    paragraph(
        rebuild,
        "适合：Ubuntu 或运行组件损坏、普通修复无效。它会保护并核验对话、附件、配置、用户插件和个人项目，再重建运行环境并恢复数据；原生 API Key 保留在本机。");
    paragraph(
        rebuild,
        "影响：会停止 DSH、终端和正在执行的任务，需要时间和额外空间。额外安装的系统软件留在旧环境中，可能要在新环境重新安装。保护副本和旧环境按核验规则保留，不能保证重建后立刻腾出空间。");
    paragraph(rebuild, "保护失败时不切换环境，后续失败会尝试回切原环境。建议先把重要数据导出到 App 外；只想腾空间时先用存储页的清理选项。");
    page.entry(
        rebuild,
        UiText.text("打开安装与环境"),
        UiText.text("查看原有修复和重建入口，打开页面不会开始重建。"),
        R.drawable.ic_ui2_box,
        this::environment);

    LinearLayout reset = section("重置配置（保留对话）");
    paragraph(reset, "适合：网页设置错误。保留对话、附件、用户插件、个人文件和原生保存的 API Key，不重建 Ubuntu，也不移除额外安装的软件。");
    paragraph(
        reset,
        "影响：先停止 DSH、终端和写入任务，再重置当前网页的普通设置和工作目录配置。原配置保留以便恢复；原生 API Key 不会因此清空。入口在「设置 → 数据与备份 → 重置配置」，仍需原来的确认。");

    LinearLayout cache = section("Android 系统「清缓存」");
    paragraph(
        cache,
        "通常只删除 Android 标记的临时缓存，之后使用会重新生成。Ubuntu、插件依赖、对话和本机备份存放在应用数据中，不会靠清缓存释放。系统显示缓存只有几十 MB，而应用数据很大，是两种不同的占用。");

    LinearLayout erase = section("Android 系统「清除全部存储／清除数据」");
    paragraph(
        erase, "会删除 App 私有目录中的对话、附件、插件、配置、API Key、本机备份和 Ubuntu 环境。下次打开需要重新解压和设置。卸载应用也会删除这些私有数据。");
    paragraph(
        erase,
        "本机副本会随私有数据一起删除，不能依赖它救回数据。只有事先存到 App 外且可读的导出文件，才可在重新初始化后手动导入其中的数据范围。未导出的私有数据，App 无法恢复。");

    LinearLayout format = section("应用内「完整格式化」");
    paragraph(
        format,
        "用于确实要从头开始。原有确认和停止检查通过后，会删除运行环境、对话、插件、配置、API Key 等核心私有数据，并回到欢迎页。它不是保留对话的配置重置；操作前先导出重要数据到 App 外。");

    LinearLayout growth = section("为什么占用会增长");
    paragraph(
        growth,
        "首次解压完整 Ubuntu、安装软件或插件依赖、保存附件和项目、生成备份都会占空间。自动备份正常保留 3 份；手动备份、未知记录和受损原件不会自动删除。更新也会保留可用的回退副本，方便出现问题时恢复。");
    paragraph(
        growth,
        "存储页会按规则清理已核验的多余副本；被修改、含额外文件或无法确认的原件继续保留。需要查看这些内容时，可打开保留副本清单；不要为了腾空间直接手动删除 Ubuntu 或依赖目录。");
    page.entry(
        growth,
        UiText.text("查看本机自动备份"),
        UiText.text("查看计划和已保留的自动副本。"),
        R.drawable.ic_ui2_box,
        () -> startActivity(new Intent(this, AutomaticBackupActivity.class)));
    page.entry(
        growth,
        UiText.text("查看保留副本与旧环境"),
        UiText.text("只读查看清单，检查、导出与恢复仍由原页面处理。"),
        R.drawable.ic_ui2_box,
        () -> startActivity(new Intent(this, RetainedDataActivity.class)));

    setContentView(page.root);
  }

  private LinearLayout section(String title) {
    LinearLayout card = page.card();
    TextView heading = page.text(UiText.text(title), 16, R.color.text);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    card.addView(heading);
    return card;
  }

  private void paragraph(LinearLayout card, String content) {
    TextView text = page.text(UiText.text(content), 14, R.color.text_secondary);
    text.setPadding(0, page.dp(10), 0, 0);
    text.setLineSpacing(page.dp(3), 1);
    text.setTextIsSelectable(true);
    card.addView(text);
  }

  private void data(String mode) {
    startActivity(new Intent(this, NativeDataActivity.class).putExtra("data_mode", mode));
  }

  private void environment() {
    startActivity(
        new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("open_install", true));
    finish();
  }
}
