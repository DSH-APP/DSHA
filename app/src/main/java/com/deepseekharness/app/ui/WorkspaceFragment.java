package com.deepseekharness.app.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.AutomaticBackups;
import com.deepseekharness.app.backup.OneClickBackup;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.BackupHealth;
import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.util.SummaryText;
import com.deepseekharness.app.util.UiText;

/**
 * 数据与备份首页：状态卡（最近备份 / 自动备份 / 保存位置 + 主操作）与三组入口。
 *
 * <p>本页只编排入口与展示；备份、恢复、导出、清理仍由原有作业和原页面执行与确认。 状态分级见
 * {@link BackupHealth}，不从文件名或版本号推断备份是否完整。
 */
public class WorkspaceFragment extends Fragment {

  private final Handler main = new Handler(Looper.getMainLooper());
  private HarnessController controller;
  private com.deepseekharness.app.core.BackupTask task;
  private AlertDialog previewDialog;
  private OneClickBackup oneClick;

  /** 状态卡展开状态：旋转/返回后保留，用户折叠后不再被异常默认展开覆盖。 */
  private DisclosureCard.Memory disclosure = new DisclosureCard.Memory();

  private View autoDot;
  private Button backupButton;

  private final androidx.activity.result.ActivityResultLauncher<android.net.Uri> backupDirectory =
      registerForActivityResult(
          new SystemDocumentPicker.TreeContract(),
          uri -> {
            if (oneClick != null) oneClick.directoryResult(uri);
          });

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View v = inflater.inflate(R.layout.fragment_workspace, container, false);
    controller = HarnessController.get(requireContext());
    task = com.deepseekharness.app.core.BackupTask.get(requireContext());
    oneClick = new androidx.lifecycle.ViewModelProvider(this).get(OneClickBackup.class);
    if (savedInstanceState != null)
      disclosure = DisclosureCard.Memory.restore(savedInstanceState, "disclosure");

    // ---- 状态卡 ----
    label(v, R.id.workspace_stat_manual_label, UiText.choose("最近手动备份", "Last manual backup"));
    label(v, R.id.workspace_stat_auto_label, UiText.text("自动备份"));
    label(v, R.id.workspace_stat_place_label, UiText.text("保存位置"));
    label(v, R.id.workspace_stat_place_value, UiText.choose("下载/DSHA", "Download/DSHA"));
    ((Button) v.findViewById(R.id.workspace_restore)).setText(UiText.text("从备份恢复"));
    ((Button) v.findViewById(android.R.id.button2)).setText(UiText.text("取消备份"));
    v.findViewById(android.R.id.button2).setOnClickListener(x -> oneClick.cancel());
    backupButton = v.findViewById(R.id.workspace_backup);
    v.findViewById(R.id.workspace_backup).setOnClickListener(x -> primaryAction());
    v.findViewById(R.id.workspace_restore).setOnClickListener(x -> secondaryAction());
    v.findViewById(R.id.workspace_stat_manual).setOnClickListener(x -> showHistory());
    v.findViewById(R.id.workspace_stat_auto)
        .setOnClickListener(x -> open(AutomaticBackupActivity.class));
    // H1：状态卡是页面主卡（elevated）；自动备份状态在标签前加状态点（H2）。
    CardTokens.tier(v.findViewById(R.id.workspace_hero), CardTokens.Tier.ELEVATED);
    TextView autoLabel = v.findViewById(R.id.workspace_stat_auto_label);
    if (autoLabel != null && autoLabel.getParent() instanceof LinearLayout autoParent) {
      int index = autoParent.indexOfChild(autoLabel);
      LinearLayout labelRow = new LinearLayout(requireContext());
      labelRow.setOrientation(LinearLayout.HORIZONTAL);
      labelRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
      autoDot = CardTokens.dot(requireContext(), CardTokens.Tone.ACCENT);
      LinearLayout.LayoutParams dotParams =
          new LinearLayout.LayoutParams(
              Math.round(8 * getResources().getDisplayMetrics().density),
              Math.round(8 * getResources().getDisplayMetrics().density));
      dotParams.setMarginEnd(Math.round(5 * getResources().getDisplayMetrics().density));
      labelRow.addView(autoDot, dotParams);
      autoParent.removeView(autoLabel);
      labelRow.addView(autoLabel, new LinearLayout.LayoutParams(0, -2, 1f));
      autoParent.addView(labelRow, index, new LinearLayout.LayoutParams(-1, -2));
    }
    // 手机竖屏三格横排会把英文标签挤成多行；窄屏改为纵向堆叠。
    if (getResources().getConfiguration().screenWidthDp < 600) stackStats(v);

    // ---- 备份选项 ----
    label(v, R.id.workspace_section_backup, UiText.choose("备份选项", "Backup options"));
    entry(
        v,
        R.id.workspace_auto,
        R.drawable.ic_recovery_clock,
        UiText.text("自动备份"),
        UiText.choose("备份时机与保留策略", "Schedule · keeps the latest 3 verified copies on this device"),
        () -> open(AutomaticBackupActivity.class));
    entry(
        v,
        R.id.workspace_export,
        R.drawable.ic_ui_export,
        UiText.choose("自定义导出", "Custom export"),
        UiText.choose("选择备份范围", "Choose scope · project folders · API key"),
        () -> openData("export"));
    entry(
        v,
        R.id.workspace_history,
        R.drawable.ic_recovery_document,
        UiText.text("备份与维护记录"),
        UiText.choose("手动备份、自动副本和维护结果", "Manual backups, automatic copies and maintenance"),
        this::showHistory);

    // ---- 存储与文件 ----
    label(v, R.id.workspace_section_files, getString(R.string.ui2_storage_files));
    entry(
        v,
        R.id.workspace_storage,
        R.drawable.ic_recovery_database,
        getString(R.string.ui2_storage_files),
        getString(R.string.ui2_storage_files_sub),
        () -> open(StorageActivity.class));
    entry(
        v,
        R.id.workspace_files,
        R.drawable.ic_ui2_folder,
        UiText.text("作品与文件导出"),
        UiText.text("浏览工作区 · 保存到手机 · 分享"),
        () -> open(WorkspaceFilesActivity.class));
    entry(
        v,
        R.id.workspace_skills,
        R.drawable.ic_entry_book,
        UiText.text("技能（Skills）"),
        UiText.text("查看技能 · 导入 SKILL.md"),
        // 设置子页：平板双栏时留在右侧详情窗格内，手机整页推进。
        () -> SettingsPanes.open(this, new SkillsFragment(), "skills"));

    // ---- 维护与恢复 ----
    entry(
        v,
        R.id.workspace_guide,
        R.drawable.ic_entry_book,
        UiText.text("储存指南"),
        UiText.text("数据位置与备份"),
        () -> open(StorageHelpActivity.class));
    entry(
        v,
        R.id.workspace_retained,
        R.drawable.ic_recovery_document,
        UiText.choose("保留副本与旧环境", "Retained copies and old environments"),
        UiText.choose(
            "旧环境、插件原件和隔离项，不自动删除",
            "Old environments, original and quarantined plugins; never deleted automatically"),
        () -> open(RetainedDataActivity.class));
    entry(
        v,
        R.id.workspace_location,
        R.drawable.ic_recovery_wrench,
        getString(R.string.ui2_recovery_tools),
        UiText.choose(
            "救援、恢复与回退", "Read-only rescue export · interrupted commits · runtime rollback"),
        () -> open(NativeDataActivity.class));
    entry(
        v,
        R.id.workspace_reset,
        R.drawable.ic_recovery_refresh,
        UiText.text("重置配置"),
        UiText.choose(
            "保留原件，插件、对话和凭据不受影响",
            "Originals retained; plugins, conversations and credentials are kept"),
        this::confirmReset);

    refreshBackupStatus(v);
    return v;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    oneClick.status.observe(getViewLifecycleOwner(), value -> refreshBackupStatus(view));
    oneClick.busy.observe(getViewLifecycleOwner(), value -> refreshBackupStatus(view));
    oneClick.failure.observe(getViewLifecycleOwner(), value -> refreshBackupStatus(view));
    oneClick.chooseDirectory.observe(
        getViewLifecycleOwner(),
        needed -> {
          if (Boolean.TRUE.equals(needed)) pickBackupDirectory();
        });
  }

  // ===================== 操作 =====================

  /** 主按钮：维护未完成时引导恢复维护，否则先确认一键备份的范围。 */
  private void primaryAction() {
    if (EnvironmentUiStatus.get(requireContext()).recovery) {
      startActivity(
          new Intent(requireContext(), ExtractActivity.class).putExtra("review_only", true));
      return;
    }
    confirmBackup();
  }

  /**
   * 副按钮：维护未完成时常规恢复不可用，改为只读救援导出（后端在待恢复时仍允许 rescue，
   * 不停止写入、不写就绪标记）；否则照旧从备份恢复。
   */
  private void secondaryAction() {
    if (EnvironmentUiStatus.get(requireContext()).recovery)
      openData(NativeDataActivity.MODE_RESCUE);
    else openData("restore");
  }

  /** 一键备份前的确认：列出包含 / 不包含与保存位置。真正开始仍走 {@link OneClickBackup#start}。 */
  private void confirmBackup() {
    if (!isAdded()) return;
    CardPage page =
        new CardPage(
            requireContext(),
            UiText.text("一键备份"),
            UiText.choose(
                "无需密码，保存为可在新设备恢复的 tar.gz。",
                "No password. Saves a tar.gz you can restore on a new device."),
            true);
    LinearLayout included = page.card();
    sheetLine(page, included, UiText.choose("会包含", "Included"), true);
    sheetLine(
        page,
        included,
        UiText.choose(
            "对话与附件、运行配置、已安装插件、当前应用内工作区",
            "Conversations and attachments, settings, installed plugins, the current app workspace"),
        false);
    LinearLayout excluded = page.card();
    sheetLine(page, excluded, UiText.choose("不包含", "Not included"), true);
    sheetLine(
        page,
        excluded,
        UiText.choose(
            "原生 API Key（可在「自定义导出」中勾选）；外置工作区，请另行备份",
            "Native API key (available in Custom export); external workspaces, back them up separately"),
        false);
    LinearLayout place = page.card();
    page.kv(place, UiText.text("保存位置"), UiText.choose("下载/DSHA", "Download/DSHA"));
    TextView notice =
        page.text(
            UiText.choose(
                "备份时会安全停止 DSH 和运行任务，完成后不会自动重新启动。",
                "Backup safely stops DSH and running tasks; they are not restarted automatically."),
            12,
            R.color.shell_text_secondary);
    notice.setLineSpacing(page.dp(3), 1);
    page.content.addView(notice);
    AlertDialog dialog = CardSheet.create(requireContext(), page);
    page.button(
        page.footer,
        UiText.text("开始备份"),
        true,
        () -> {
          dialog.dismiss();
          if (!oneClick.start()) {
            toast(
                UiText.choose(
                    "已有备份或保存位置选择正在进行。",
                    "A backup or destination selection is already in progress."));
          } else if (backupButton != null) {
            // A2：真实任务开始时才出现在线转圈，任务结束后由状态刷新恢复按钮。
            main.post(
                () -> {
                  if (Boolean.TRUE.equals(oneClick.busy.getValue()))
                    LoadingAction.start(backupButton);
                });
          }
        });
    page.button(page.footer, UiText.text("取消"), false, dialog::dismiss);
    CardSheet.show(dialog, requireContext());
  }

  private void sheetLine(CardPage page, LinearLayout card, String value, boolean heading) {
    TextView line =
        page.text(
            value, heading ? 13 : 14, heading ? R.color.shell_text_secondary : R.color.shell_text);
    if (heading) line.setTypeface(null, android.graphics.Typeface.BOLD);
    line.setLineSpacing(page.dp(4), 1);
    line.setPadding(0, page.dp(heading ? 4 : 8), 0, page.dp(heading ? 0 : 4));
    card.addView(line);
  }

  private void showHistory() {
    if (!isAdded()) return;
    CardSheet.show(requireContext(), UiText.text("备份与维护记录"), historyText());
  }

  private void confirmReset() {
    if (task.busy() || task.pendingMaintenance()) {
      taskRejected();
      return;
    }
    new DshaDialogBuilder(requireContext(), true)
        .setTitle(UiText.text("重置配置？"))
        .setMessage(
            UiText.choose(
                "重置当前配置并读回。插件、对话与凭据保留；原配置可恢复，无法确认时保留原件。",
                "Stop Web, terminals and writers. Reset ordinary settings of the web profile in an isolated service, read them back, then commit together with the workspace .env. Plugin composition, conversations and credentials are retained. Failures preserve originals and report the reason."))
        .setPositiveButton(
            UiText.choose("保留原件并重置", "Retain originals and reset"),
            (d, w) -> {
              if (!task.resetConfig()) taskRejected();
            })
        .setNegativeButton(UiText.text("取消"), null)
        .show();
  }

  private void openData(String mode) {
    startActivity(
        new Intent(requireContext(), NativeDataActivity.class).putExtra("data_mode", mode));
  }

  private void open(Class<?> page) {
    startActivity(new Intent(requireContext(), page));
  }

  private void pickBackupDirectory() {
    if (isResumed() && oneClick != null && oneClick.claimDirectoryPicker())
      RecentsPrivacyGuard.launch(backupDirectory, null);
  }

  private void taskRejected() {
    toast(task.pendingMaintenance() ? UiText.text("请先恢复中断维护") : UiText.text("已有任务进行中，请稍后重试"));
  }

  // ===================== 渲染 =====================

  private final Runnable refreshTask =
      new Runnable() {
        @Override
        public void run() {
          if (getView() == null || task == null) return;
          refreshBackupStatus(getView());
          main.postDelayed(this, 1_000);
        }
      };

  private void refreshBackupStatus(View view) {
    if (!isAdded()) return;
    long now = System.currentTimeMillis();
    long success = controller.config().getLastBackupSuccess();
    BackupTaskState.Snapshot s = task.snapshot();
    boolean nativeBusy = Boolean.TRUE.equals(oneClick.busy.getValue());
    if (!nativeBusy) LoadingAction.stop(backupButton);
    boolean busy = task.busy() || nativeBusy;
    boolean pending = EnvironmentUiStatus.get(requireContext()).recovery;
    String nativeFailure = oneClick.failure.getValue();
    String failure =
        nativeFailure != null && !nativeFailure.isEmpty()
            ? nativeFailure
            : controller.config().getLastBackupError();
    BackupHealth.Level level = BackupHealth.evaluate(success, failure, busy, pending, now);
    String nativeStatus = oneClick.status.getValue();
    nativeStatus = nativeStatus == null ? "" : nativeStatus;

    String title, detail;
    int icon = R.drawable.ic_recovery_shield;
    switch (level) {
      case RUNNING -> {
        icon = R.drawable.ic_ui_export;
        title =
            nativeBusy
                ? UiText.choose("正在备份", "Backing up")
                : UiText.choose("数据任务进行中", "Data task in progress");
        detail =
            !nativeStatus.isEmpty()
                ? nativeStatus
                : s.id != 0
                    ? com.deepseekharness.app.util.UiStateText.render(s.kind)
                        + "\n"
                        + com.deepseekharness.app.util.MaintenanceErrorText.render(s.detail)
                    : UiText.choose("请勿关闭应用。", "Keep the app open.");
      }
      case PENDING -> {
        icon = R.drawable.ic_recovery_wrench;
        title = UiText.choose("有未完成的环境维护", "Environment maintenance is unfinished");
        detail = UiText.text("原环境尚未确认，常规备份和恢复暂不可用。可先导出可读副本保存数据，再恢复中断维护。");
      }
      case FAILED -> {
        title = UiText.choose("上次备份未完成", "The last backup did not finish");
        detail = UiText.format("原数据和已有备份已保留。原因：%s", failure);
      }
      case NEVER -> {
        title = UiText.choose("还没有导出过备份", "No backup exported yet");
        detail =
            UiText.choose(
                "对话、配置和插件目前只存在应用内。建议先做一次一键备份。",
                "Conversations, settings and plugins exist only inside the app. Make a one-click backup first.");
      }
      case STALE -> {
        title = UiText.format("已有 %s 天没有手动备份", String.valueOf(BackupHealth.ageDays(success, now)));
        detail =
            UiText.choose(
                "自动副本只保存在本机私有目录。换机或卸载前，请导出一份到手机存储。",
                "Automatic copies stay in private app storage. Export one to device storage before switching devices or uninstalling.");
      }
      default -> {
        title = UiText.choose("数据已保护", "Your data is protected");
        detail =
            UiText.choose(
                "最近 7 天内有手动导出的备份；自动副本另存于本机私有目录。",
                "A manual backup was exported within the last 7 days. Automatic copies are kept separately in private app storage.");
      }
    }
    // 完成 / 取消后的一键备份结果（含保存路径）附在说明后，不吞掉原有提示。
    // NEVER：旧回执与「还没有导出过备份」矛盾，不叠加；成功回执本应先刷新 lastBackupSuccess。
    if (level != BackupHealth.Level.RUNNING
        && level != BackupHealth.Level.NEVER
        && !nativeStatus.isEmpty()) detail = detail + "\n\n" + nativeStatus;

    // 收起只留首句；展开区放首句之后的剩余文字，避免展开后整段重复（B1/B4）。
    TextView heroTitle = view.findViewById(R.id.workspace_hero_title);
    TextView heroDetail = view.findViewById(R.id.workspace_backup_status);
    heroDetail.setTextIsSelectable(false);
    FadeSwap.text(heroTitle, title);
    SummaryText.Split split = SummaryText.split(detail);
    FadeSwap.text(heroDetail, split.summary);
    DisclosureCard.Model model = new DisclosureCard.Model();
    model.key = "backup-status";
    model.detail = split.hasMore ? split.full.substring(split.summary.length()).trim() : "";
    model.defaultExpanded = level == BackupHealth.Level.FAILED || level == BackupHealth.Level.STALE;
    DisclosureCard.bind(heroDetail, model, disclosure, null);
    tintHero(view, level, icon);
    view.findViewById(R.id.workspace_hero_progress)
        .setVisibility(level == BackupHealth.Level.RUNNING ? View.VISIBLE : View.GONE);
    view.findViewById(R.id.workspace_stats)
        .setVisibility(level == BackupHealth.Level.RUNNING ? View.GONE : View.VISIBLE);
    view.findViewById(R.id.workspace_hero_actions)
        .setVisibility(level == BackupHealth.Level.RUNNING ? View.GONE : View.VISIBLE);
    view.findViewById(android.R.id.button2).setVisibility(nativeBusy ? View.VISIBLE : View.GONE);

    label(
        view,
        R.id.workspace_stat_manual_value,
        success == 0 ? UiText.choose("从未", "Never") : shortTime(success));
    label(view, R.id.workspace_stat_auto_value, automaticSummary());
    CardTokens.setDotTone(
        autoDot,
        AutomaticBackups.enabled(requireContext())
            ? CardTokens.Tone.GOOD
            : CardTokens.Tone.NEUTRAL);
    entryValue(
        view,
        R.id.workspace_auto,
        AutomaticBackups.enabled(requireContext())
            ? UiText.choose("已开启", "On")
            : UiText.text("已关闭"));

    Button primary = view.findViewById(R.id.workspace_backup);
    primary.setText(pending ? UiText.text("恢复中断维护") : UiText.choose("立即备份", "Back up now"));
    primary.setEnabled(!busy);
    // 维护未完成时副按钮改为只读救援导出；「环境修复与数据救援」正是此状态的出口，不再置灰。
    Button secondary = view.findViewById(R.id.workspace_restore);
    secondary.setText(pending ? UiText.text("导出可读副本") : UiText.text("从备份恢复"));
    enable(secondary, !busy);
    enable(view.findViewById(R.id.workspace_location), !busy);
    for (int id : new int[] {R.id.workspace_export, R.id.workspace_reset})
      enable(view.findViewById(id), !busy && !pending);

    if (s.status == BackupTaskState.Status.PREVIEW && previewDialog == null && isResumed()) {
      previewDialog =
          new DshaDialogBuilder(requireContext())
              .setTitle(UiText.text("恢复预览"))
              .setMessage(com.deepseekharness.app.util.UiStateText.render(s.detail))
              .setPositiveButton(UiText.text("恢复此备份"), (d, w) -> task.decide(s.id, true))
              .setNegativeButton(UiText.text("取消"), (d, w) -> task.decide(s.id, false))
              .setOnCancelListener(d -> task.decide(s.id, false))
              .create();
      previewDialog.setOnDismissListener(d -> previewDialog = null);
      previewDialog.show();
    }
  }

  /** 记录弹层沿用原状态块内容：最近手动 / 自动副本 / 上次未完成 / 当前任务 / 维护提示。 */
  private String historyText() {
    long time = controller.config().getLastBackupSuccess();
    StringBuilder summary =
        new StringBuilder(
            time == 0
                ? UiText.choose("还没有手动导出备份", "No manually exported backup yet")
                : UiText.format(
                    "最近手动备份：%s\n%s",
                    java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(time)),
                    controller.config().getLastBackupName()));
    long automatic = AutomaticBackups.prefs(requireContext()).getLong("last", 0);
    if (automatic > 0)
      summary.append(
          UiText.format(
              "\n最近自动副本：%s",
              java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(automatic))));
    String failure = controller.config().getLastBackupError();
    if (!failure.isEmpty()) summary.append(UiText.format("\n上次未完成：%s", failure));
    BackupTaskState.Snapshot s = task.snapshot();
    if (s.id != 0)
      summary
          .append("\n\n")
          .append(com.deepseekharness.app.util.UiStateText.render(s.kind))
          .append("\n")
          .append(com.deepseekharness.app.util.MaintenanceErrorText.render(s.detail));
    if (EnvironmentUiStatus.get(requireContext()).recovery)
      summary.append(UiText.text("\n\n有未完成的环境维护，请恢复原环境后再继续。"));
    String nativeStatus = oneClick.status.getValue();
    if (nativeStatus != null && !nativeStatus.isEmpty())
      summary.append("\n\n").append(nativeStatus);
    return summary.toString();
  }

  private String automaticSummary() {
    if (!AutomaticBackups.enabled(requireContext())) return UiText.text("已关闭");
    var p = AutomaticBackups.prefs(requireContext());
    return switch (p.getString("mode", "daily")) {
      case "interval" -> UiText.format("每 %s 小时", String.valueOf(p.getInt("hours", 24)));
      case "stop" -> UiText.text("停止 DSH 后");
      default -> {
        int minute = Math.max(0, Math.min(24 * 60 - 1, p.getInt("minute", 180)));
        yield UiText.format(
            "每天 %s", String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60));
      }
    };
  }

  private static String shortTime(long time) {
    return java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(new java.util.Date(time));
  }

  private void tintHero(View view, BackupHealth.Level level, int icon) {
    int fg, bg;
    if (level == BackupHealth.Level.RUNNING) {
      fg = R.color.shell_accent;
      bg = R.color.shell_accent_soft;
    } else
      switch (BackupHealth.severity(level)) {
        case 2 -> {
          fg = R.color.shell_bad;
          bg = R.color.shell_bad_soft;
        }
        case 1 -> {
          fg = R.color.shell_warn;
          bg = R.color.shell_warn_soft;
        }
        default -> {
          fg = R.color.shell_good;
          bg = R.color.shell_good_soft;
        }
      }
    view.findViewById(R.id.workspace_hero_tile)
        .setBackgroundTintList(ColorStateList.valueOf(requireContext().getColor(bg)));
    ImageView mark = view.findViewById(R.id.workspace_hero_icon);
    mark.setImageResource(icon);
    mark.setImageTintList(ColorStateList.valueOf(requireContext().getColor(fg)));
  }

  private void stackStats(View view) {
    LinearLayout stats = view.findViewById(R.id.workspace_stats);
    stats.setOrientation(LinearLayout.VERTICAL);
    int gap = Math.round(8 * getResources().getDisplayMetrics().density);
    for (int i = 0; i < stats.getChildCount(); i++) {
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      if (i > 0) p.topMargin = gap;
      stats.getChildAt(i).setLayoutParams(p);
    }
  }

  private static void entry(
      View root, int id, int icon, String title, String subtitle, Runnable action) {
    View row = root.findViewById(id);
    ((ImageView) row.findViewById(R.id.entry_icon)).setImageResource(icon);
    ((TextView) row.findViewById(R.id.entry_title)).setText(title);
    ((TextView) row.findViewById(R.id.entry_sub)).setText(subtitle);
    row.setOnClickListener(x -> action.run());
  }

  private static void entryValue(View root, int id, String value) {
    TextView text = root.findViewById(id).findViewById(R.id.entry_value);
    text.setText(value);
    text.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
  }

  /** LinearLayout 行禁用不会自动变灰；同时降低透明度，读屏仍按 enabled 报告。 */
  private static void enable(View view, boolean enabled) {
    view.setEnabled(enabled);
    view.setAlpha(enabled ? 1f : 0.45f);
  }

  private static void label(View root, int id, CharSequence value) {
    ((TextView) root.findViewById(id)).setText(value);
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    disclosure.save(outState, "disclosure");
  }

  @Override
  public void onResume() {
    super.onResume();
    main.removeCallbacks(refreshTask);
    main.post(refreshTask);
    pickBackupDirectory();
  }

  @Override
  public void onPause() {
    main.removeCallbacks(refreshTask);
    if (previewDialog != null) {
      previewDialog.dismiss();
      previewDialog = null;
    }
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    main.removeCallbacks(refreshTask);
    super.onDestroyView();
  }

  private void toast(String s) {
    Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
  }
}
