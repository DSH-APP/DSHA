package com.deepseekharness.app.ui;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.UiText;

/** 自动计划、本机副本与原有恢复预检共用同一条数据路径。 */
public final class AutomaticBackupActivity extends AppCompatActivity {
  private CardPage page;
  private TextView state;
  private LinearLayout copies;
  private boolean reading;
  private FiniteSkeleton skeleton;
  private boolean skeletonPlayed;

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  static String stage(String value) {
    return switch (value) {
      case "PREPARING" -> t("准备数据", "Preparing data");
      case "CAPTURING" -> t("保存并核验文件", "Saving and verifying files");
      case "WRITING" -> t("写入并验证无密码副本", "Writing and verifying an unencrypted copy");
      case "ENCRYPTING" -> t("加密并验证副本", "Encrypting and verifying the copy");
      case "EXPORTING" -> t("完成副本记录", "Finishing the copy record");
      default -> t("正在处理", "Working");
    };
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    render();
    NativeBackupJobs.get(this)
        .changes()
        .observe(
            this,
            value -> {
              if (state == null) return;
              if (value.busy)
                FadeSwap.text(
                    state, UiText.format("备份任务进行中：%s · %s 项", stage(value.stage), value.entries));
              else refresh();
            });
  }

  private void render() {
    page =
        new CardPage(
            this,
            t("自动备份", "Automatic backups"),
            t(
                "默认开启。备份对话、附件、配置和插件；不重复备份 Ubuntu。个人项目请使用手动备份。",
                "On by default. Saves conversations, attachments, settings and plugins, without copying Ubuntu. Use manual backup for personal projects."));
    UiNavigation.addHeader(this, page.root, t("自动备份", "Automatic backups"), this::finish);
    // 页首只留「默认开启」这一句；备份范围、不重复 Ubuntu 与手动备份建议收进 ⓘ。
    InfoHint.attach(page.subtitle, t("自动备份", "Automatic backups"));
    var prefs = AutomaticBackups.prefs(this);
    LinearLayout settings = page.card();
    var enabled = new com.google.android.material.materialswitch.MaterialSwitch(this);
    enabled.setText(t("自动备份", "Automatic backups"));
    enabled.setChecked(AutomaticBackups.enabled(this));
    settings.addView(enabled);
    enabled.setOnCheckedChangeListener(
        (button, value) -> {
          prefs.edit().putBoolean("enabled", value).commit();
          AutomaticBackups.schedule(this);
          refresh();
        });
    String mode = prefs.getString("mode", "daily");
    String[] names = {
      t("每天指定时间", "Daily at a chosen time"),
      t("每隔一段时间", "At an interval"),
      t("停止 DSH 后", "After stopping DSH")
    };
    page.entry(
        settings,
        t("备份时机", "Backup timing"),
        names["interval".equals(mode) ? 1 : "stop".equals(mode) ? 2 : 0],
        R.drawable.ic_ui2_box,
        () ->
            CardSheet.choices(
                this,
                t("备份时机", "Backup timing"),
                () -> java.util.Arrays.asList(names),
                index -> {
                  prefs
                      .edit()
                      .putString("mode", new String[] {"daily", "interval", "stop"}[index])
                      .putLong("anchor", System.currentTimeMillis())
                      .commit();
                  render();
                }));
    if ("daily".equals(mode)) {
      int minute = prefs.getInt("minute", 180);
      page.entry(
          settings,
          t("每天时间", "Daily time"),
          String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60),
          R.drawable.ic_ui2_box,
          () ->
              new TimePickerDialog(
                      this,
                      (picker, h, m) -> {
                        prefs.edit().putInt("minute", h * 60 + m).commit();
                        render();
                      },
                      minute / 60,
                      minute % 60,
                      true)
                  .show());
    } else if ("interval".equals(mode)) {
      page.entry(
          settings,
          t("间隔小时", "Interval in hours"),
          String.valueOf(prefs.getInt("hours", 24)),
          R.drawable.ic_ui2_box,
          () -> {
            EditText hours = new EditText(this);
            hours.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            hours.setText(String.valueOf(prefs.getInt("hours", 24)));
            var dialog =
                new DshaDialogBuilder(this)
                    .setTitle(t("间隔 1–168 小时", "Interval: 1–168 hours"))
                    .setView(hours)
                    .setPositiveButton(t("保存", "Save"), null)
                    .setNegativeButton(t("取消", "Cancel"), null)
                    .create();
            dialog.setOnShowListener(
                d ->
                    dialog
                        .getButton(-1)
                        .setOnClickListener(
                            v -> {
                              try {
                                int value = Integer.parseInt(hours.getText().toString());
                                if (value < 1 || value > 168) throw new NumberFormatException();
                                prefs.edit().putInt("hours", value).commit();
                                dialog.dismiss();
                                render();
                              } catch (NumberFormatException invalid) {
                                hours.setError(t("请输入 1–168", "Enter 1–168"));
                              }
                            }));
            dialog.show();
          });
    }
    TextView timingHint =
        page.text(
            t(
                "到期时仍在运行则延后，不会停止对话或终端。时间由系统调度，可能略有延迟。保留最近 3 份已验证自动副本。关闭后取消后续任务，已有副本保留。",
                "If DSH or a terminal is running, backup waits. Android scheduling may delay the selected time. Keeps the latest 3 verified automatic copies. Turning off cancels future jobs and preserves existing copies."),
            12,
            R.color.text_secondary);
    // 行内只留「到期仍在运行则延后」；调度延迟与保留策略收进 ⓘ。
    InfoHint.attach(timingHint, t("备份时机", "Backup timing"));
    settings.addView(timingHint);
    TextView localHint =
        page.text(
            t(
                "自动副本与本机密钥在卸载时会清除。换机或卸载前，请导出无需密码的 tar.gz 并保存到应用外。",
                "Local copies and their device key are removed when the app is uninstalled. Export a tar.gz without a password and save it outside the app before moving or uninstalling."),
            11,
            R.color.text_muted);
    localHint.setPadding(0, page.dp(10), 0, 0);
    // 行内只留卸载会清除；换机前导出无密码备份的做法收进 ⓘ。
    InfoHint.attach(localHint, t("本机副本 · 保留最近 3 份", "Local copies · latest 3 kept"));
    settings.addView(localHint);
    state = page.text("", 12, R.color.text_secondary);
    page.content.addView(state);
    page.label(t("本机副本 · 保留最近 3 份", "Local copies · latest 3 kept"));
    copies = page.card();
    page.button(
        page.footer,
        t("现在创建自动备份", "Create automatic backup now"),
        true,
        () -> {
          if (NativeBackupJobs.get(this).state().busy) {
            state.setText(t("备份任务进行中，请等待完成。", "A backup task is running. Wait for it to finish."));
            return;
          }
          if (!AutomaticBackups.enabled(this)) {
            state.setText(t("请先打开自动备份", "Enable automatic backups first"));
            return;
          }
          AutomaticBackups.request(this, true);
          state.setText(
              t(
                  "已安排，稍后查看结果",
                  "Scheduled. DSH and terminals must be stopped. Refresh shortly to view the result."));
        });
    page.button(page.footer, t("刷新备份记录", "Refresh backup records"), false, this::refresh);
    setContentView(page.root);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    refresh();
  }

  private void openCopy(String extra, String copyId) {
    startActivity(new Intent(this, NativeDataActivity.class).putExtra(extra, copyId));
  }

  /**
   * G1 详情 / 动作抽屉：只读展示副本时间、大小、编号与结果；动作跳到 NativeData 的既有预检 / 导出
   * 流程。点遮罩或返回键只关闭抽屉，不会执行任何动作。
   */
  private void showCopyDrawer(VerifiedBackupCopy copy, String title, String detail) {
    CardPage sheet = new CardPage(this, t("备份详情", "Backup details"), title, true);
    LinearLayout facts = sheet.card();
    sheet.kv(
        facts,
        t("创建时间", "Created"),
        android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", copy.created).toString());
    sheet.kv(facts, t("大小", "Size"), com.deepseekharness.app.util.Fmt.bytes(copy.bytes));
    sheet.kv(facts, t("编号", "ID"), copy.id);
    if (!detail.isEmpty()) sheet.kv(facts, t("摘要", "Summary"), detail);
    String result = copy.result(true);
    if (!result.isEmpty()) sheet.kv(facts, t("结果", "Result"), result);
    TextView note =
        sheet.text(
            t(
                "预检与导出都会再次核对私有副本；原副本保留，不覆盖已有备份。",
                "Inspect and export verify the private copy again. The original is kept and existing backups are not overwritten."),
            12,
            R.color.text_secondary);
    note.setPadding(0, sheet.dp(4), 0, sheet.dp(8));
    sheet.content.addView(note);
    final BottomSheetCard.Sheet[] drawer = new BottomSheetCard.Sheet[1];
    sheet.button(
        sheet.footer,
        t("预检恢复", "Inspect for restore"),
        true,
        () -> {
          if (drawer[0] != null) drawer[0].dismiss();
          openCopy("auto_restore_id", copy.id);
        });
    sheet.button(
        sheet.footer,
        t("导出无密码备份", "Export backup without a password"),
        false,
        () -> {
          if (drawer[0] != null) drawer[0].dismiss();
          openCopy("auto_export_id", copy.id);
        });
    sheet.button(
        sheet.footer,
        t("关闭", "Close"),
        false,
        () -> {
          if (drawer[0] != null) drawer[0].dismiss();
        });
    drawer[0] = BottomSheetCard.show(this, sheet);
  }

  private void refresh() {
    if (reading) return;
    reading = true;
    FadeSwap.text(state, t("正在读取记录…", "Reading records…"));
    showFirstLoadSkeleton();
    new Thread(
            () -> {
              try {
                var found = NativeBackupJobs.get(this).verifiedCopies();
                var prefs = AutomaticBackups.prefs(this);
                long last = prefs.getLong("last", 0);
                String error = prefs.getString("error", "");
                runOnUiThread(
                    () -> {
                      reading = false;
                      if (isFinishing() || isDestroyed()) return;
                      stopSkeleton();
                      copies.removeAllViews();
                      String status =
                          AutomaticBackups.enabled(this)
                              ? t(
                                  "已开启 · 等待到期且 DSH 停止",
                                  "On · waiting for the scheduled time and DSH to stop")
                              : t("已关闭", "Off");
                      if (last > 0)
                        status +=
                            "\n"
                                + t("上次完成：", "Last completed: ")
                                + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", last);
                      if (!error.isEmpty()) status += "\n" + t("待处理：", "Needs attention: ") + error;
                      var job = NativeBackupJobs.get(this).state();
                      if (job.busy)
                        status =
                            t("备份任务进行中：", "Backup in progress: ")
                                + stage(job.stage)
                                + " · "
                                + job.entries
                                + t(" 项", " entries");
                      if ("DATA_SAVED_PLUGIN_WARNINGS".equals(job.result))
                        status +=
                            "\n"
                                + t(
                                    "数据已保存；部分插件依赖需在恢复预检中核对。",
                                    "Data saved; some plugin dependencies need review before restore.");
                      FadeSwap.text(state, status);
                      for (var copy : found.valid)
                        if (Boolean.TRUE.equals(copy.metadata.get("automatic"))) {
                          String title =
                              android.text.format.DateFormat.format("MM-dd HH:mm", copy.created)
                                  .toString();
                          String detail =
                              com.deepseekharness.app.util.Fmt.bytes(copy.bytes)
                                  + ("DATA_SAVED_PLUGIN_WARNINGS".equals(copy.result(true))
                                      ? t(" · 插件依赖待核对", " · review plugin dependencies")
                                      : "");
                          View copyRow =
                              page.entry(
                                  copies,
                                  title,
                                  detail,
                                  R.drawable.ic_recovery_document,
                                  () -> showCopyDrawer(copy, title, detail));
                          // F2：左滑露出「预检恢复 / 导出」；滑动只露出动作，点按才生效。
                          SwipeAction.attach(
                              copyRow,
                              SwipeAction.Model.of(
                                  SwipeAction.Action.of(
                                      t("预检恢复", "Inspect for restore"),
                                      R.drawable.ic_recovery_refresh,
                                      () -> openCopy("auto_restore_id", copy.id)),
                                  SwipeAction.Action.of(
                                      t("导出", "Export"),
                                      t("导出无密码备份", "Export backup without a password"),
                                      R.drawable.ic_ui_export,
                                      () -> openCopy("auto_export_id", copy.id))));
                          // F1：备份记录列表首次出现前 8 条错峰，刷新重排不重放。
                          StaggerList.revealRow(copyRow, "auto-backup-copies", copy.id);
                        }
                      if (copies.getChildCount() == 0)
                        copies.addView(
                            page.text(
                                t(
                                    "还没有自动备份",
                                    "No automatic backup yet. The first copy is created when due, with DSH and terminals stopped."),
                                12,
                                R.color.text_muted));
                    });
              } catch (Exception error) {
                runOnUiThread(
                    () -> {
                      reading = false;
                      FadeSwap.text(state, NativeBackupJobs.code(error));
                    });
              }
            },
            "auto-backup-records")
        .start();
  }

  /**
   * E4：首次读取记录时用 2 行骨架占位，数据到达立即收掉；上限 1.5 秒后骨架自己停，
   * 页面保留静态「正在读取记录…」，不做无限 shimmer。
   */
  private void showFirstLoadSkeleton() {
    if (skeletonPlayed || copies.getChildCount() > 0) return;
    skeletonPlayed = true;
    LinearLayout host = new LinearLayout(this);
    host.setOrientation(LinearLayout.VERTICAL);
    copies.addView(host, new LinearLayout.LayoutParams(-1, -2));
    skeleton =
        FiniteSkeleton.attach(host, 2, com.deepseekharness.app.util.SkeletonTiming.DEFAULT_MS);
    skeleton.start();
  }

  private void stopSkeleton() {
    if (skeleton != null) {
      skeleton.stop();
      skeleton = null;
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (state != null) refresh();
  }
}
