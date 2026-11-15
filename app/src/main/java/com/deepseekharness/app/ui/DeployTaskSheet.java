package com.deepseekharness.app.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.DeployStages;
import com.deepseekharness.app.util.UiText;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 点开部署胶囊后的任务面板：手机为贴底面板（CardSheet 同款），宽 ≥ 600dp 为居中对话框（≤ 540dp）。
 * 内容整体可滚动（短屏、1.3 倍字体）；打开期间订阅 {@link DeployMonitor}，关闭时解除。重试只经
 * {@link RuntimeMaintenanceStarter#confirmRetry} 的用户确认执行；面板本身不启动任何任务。
 */
final class DeployTaskSheet {
  private static final java.util.Map<android.app.Activity, AlertDialog> SHOWING =
      new java.util.WeakHashMap<>();

  private final AppCompatActivity activity;
  private final DeployMonitor monitor;
  private final boolean inOnboarding;
  private final Runnable onLimitedEntry;
  private final OnboardingKit kit;
  private final LinearLayout content;
  private AlertDialog dialog;

  private DeployTaskSheet(
      AppCompatActivity activity, DeployMonitor monitor, boolean inOnboarding, Runnable limited) {
    this.activity = activity;
    this.monitor = monitor;
    this.inOnboarding = inOnboarding;
    this.onLimitedEntry = limited;
    kit = new OnboardingKit(activity);
    content = kit.column();
  }

  static void show(
      AppCompatActivity activity,
      DeployMonitor monitor,
      boolean inOnboarding,
      Runnable onLimitedEntry) {
    if (activity == null || monitor == null || activity.isFinishing() || activity.isDestroyed())
      return;
    AlertDialog open = SHOWING.get(activity);
    if (open != null && open.isShowing()) return;
    if (!monitor.current().visible()) return;
    new DeployTaskSheet(activity, monitor, inOnboarding, onLimitedEntry).open();
  }

  private void open() {
    boolean wide = kit.wide();
    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(false);
    scroll.setClipToPadding(false);
    content.setPadding(
        kit.dp(wide ? 24 : 20), kit.dp(wide ? 20 : 8), kit.dp(wide ? 24 : 20), kit.dp(18));
    scroll.addView(content);
    final float heightFraction = wide ? 0.84f : 0.88f;
    final int maxWidth = kit.dp(540);
    FrameLayout container =
        new FrameLayout(activity) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int limit =
                Math.round(getResources().getDisplayMetrics().heightPixels * heightFraction);
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED)
              limit = Math.min(limit, MeasureSpec.getSize(heightSpec));
            if (wide && MeasureSpec.getSize(widthSpec) > maxWidth)
              widthSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
          }
        };
    container.setBackgroundResource(wide ? R.drawable.bg_shell_card : R.drawable.bg_shell_sheet);
    container.addView(scroll, new FrameLayout.LayoutParams(-1, -2));
    container.setAccessibilityPaneTitle(monitor.current().title());
    FrameLayout host = new FrameLayout(activity);
    host.addView(
        container,
        new FrameLayout.LayoutParams(
            wide ? FrameLayout.LayoutParams.WRAP_CONTENT : -1, -2, Gravity.CENTER_HORIZONTAL));
    dialog = new DshaDialogBuilder(activity).setView(host).create();
    if (dialog.getWindow() != null) {
      dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      if (!wide) {
        // 贴底与半屏动效必须在第一次 show() 前设置，避免首帧出现在屏幕中央。
        dialog.getWindow().setGravity(Gravity.BOTTOM);
        dialog.getWindow().setLayout(-1, -2);
        dialog.getWindow().setWindowAnimations(R.style.Animation_DSHA_Sheet);
      }
    }
    Consumer<DeployStages.View> listener =
        view -> {
          if (dialog == null || !dialog.isShowing()) return;
          if (!view.visible()) {
            dialog.dismiss();
            return;
          }
          render(view);
        };
    dialog.setOnDismissListener(
        d -> {
          monitor.unsubscribe(listener);
          if (SHOWING.get(activity) == dialog) SHOWING.remove(activity);
        });
    render(monitor.current());
    SHOWING.put(activity, dialog);
    monitor.subscribe(listener);
    dialog.show();
    if (wide && dialog.getWindow() != null)
      dialog.getWindow().setLayout(Math.min(maxWidth, screenWidth() - kit.dp(32)), -2);
  }

  private int screenWidth() {
    return activity.getResources().getDisplayMetrics().widthPixels;
  }

  private void render(DeployStages.View view) {
    content.removeAllViews();
    if (!kit.wide()) {
      View grab = new View(activity);
      grab.setBackgroundResource(R.drawable.bg_onb_seg_todo);
      grab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams grabParams = new LinearLayout.LayoutParams(kit.dp(38), kit.dp(4));
      grabParams.gravity = Gravity.CENTER_HORIZONTAL;
      grabParams.bottomMargin = kit.dp(12);
      grabParams.topMargin = kit.dp(2);
      content.addView(grab, grabParams);
    }
    OnboardingKit.Tone tone = OnboardingKit.tone(view.phase);
    LinearLayout head = kit.row();
    TextView title = kit.bold(view.title(), 18, R.color.shell_text);
    title.setAccessibilityHeading(true);
    head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(-2, -2);
    pillParams.leftMargin = kit.dp(10);
    head.addView(kit.pill(DeployCard.pillText(view), tone), pillParams);
    content.addView(head, OnboardingKit.matchWrap());
    kit.add(
        content,
        kit.text(description(view.mode, inOnboarding), 13.5f, R.color.shell_text_secondary),
        8);
    kit.add(content, kit.bold(DeployCard.line(view), 14, R.color.shell_text), 10);
    if (view.total() > 0) {
      kit.add(content, kit.segmentBar(view), 10);
      kit.add(content, kit.stageList(view), 10);
    }
    String message = message(view);
    if (!message.isEmpty())
      kit.add(
          content,
          kit.messageBox(
              message,
              view.phase == DeployStages.Phase.UNREADY
                  ? OnboardingKit.Tone.WARN
                  : OnboardingKit.Tone.BAD),
          12);
    if (view.phase == DeployStages.Phase.RUNNING) kit.add(content, notificationNote(), 10);
    kit.add(content, buttons(view), 16);
  }

  /** 维护页行内摘要：只描述本次动作，完整原因与影响仍用 {@link #description} 收进 ⓘ。 */
  static String summary(DeployStages.Mode mode) {
    switch (mode == null ? DeployStages.Mode.GENERIC : mode) {
      case UPDATE:
        return UiText.choose(
            "只更新 dsh 与内置插件，个人数据保留", "Updates only dsh and built-in plugins; personal data stays");
      case INSTALL:
        return UiText.choose("部署内置 Ubuntu 与运行组件", "Deploys the bundled Ubuntu and runtime");
      case REBUILD:
        return UiText.choose(
            "先保护个人数据，再重建内置环境", "Protects personal data, then rebuilds the environment");
      default:
        return UiText.choose("环境维护在后台进行", "Environment maintenance runs in the background");
    }
  }

  /** 说明：首次部署 / 运行时更新 / 重建三种，原文见设计稿 taskSheet 的 desc。 */
  static String description(DeployStages.Mode mode, boolean inOnboarding) {
    switch (mode == null ? DeployStages.Mode.GENERIC : mode) {
      case UPDATE:
        return UiText.choose(
            "相同基础环境只更新 dsh 与内置插件，个人目录、会话和配置保持原位；验证失败回切原运行时。",
            "With the same base environment, only dsh and built-in plugins are updated; personal folders, sessions and settings stay in place. If verification fails, the original runtime is restored.");
      case INSTALL:
        return inOnboarding
            ? UiText.choose(
                "内置 Ubuntu 与运行组件部署到应用私有目录。可以继续完成引导，部署期间屏幕保持常亮。",
                "The bundled Ubuntu and runtime components are deployed to the app's private storage. You can keep going through setup; the screen stays on while deploying.")
            : UiText.choose(
                "内置 Ubuntu 与运行组件部署到应用私有目录，部署在后台进行，期间屏幕保持常亮。",
                "The bundled Ubuntu and runtime components are deployed to the app's private storage in the background; the screen stays on while deploying.");
      case REBUILD:
        return UiText.choose(
            "先保护对话、配置、插件与个人项目，再重建内置环境；数据保护失败不切换环境，重建失败保留原环境。",
            "Conversations, settings, plugins and personal projects are protected first, then the bundled environment is rebuilt. If protection fails nothing is switched; if the rebuild fails the original environment is kept.");
      default:
        return UiText.choose(
            "环境维护在后台进行；完成并通过就绪检查后，Web、终端与插件才会恢复。",
            "Environment maintenance runs in the background. Web, terminals and plugins return after it finishes and passes the readiness check.");
    }
  }

  /** 失败：最后记录阶段 + 原因；未通过：就绪检查说明；待恢复：先恢复中断维护。 */
  static String message(DeployStages.View view) {
    switch (view.phase) {
      case FAILED:
        String stage =
            view.current >= 0 && view.current < view.total()
                ? view.stages.get(view.current).title()
                : view.lastStage.isEmpty()
                    ? UiText.choose(
                        "旧记录未提供；请查看维护日志",
                        "Not available in the old record; inspect maintenance logs")
                    : com.deepseekharness.app.util.LogCompactor.compactReason(
                        com.deepseekharness.app.util.UiStateText.render(view.lastStage), 240);
        return String.format(
            Locale.ROOT,
            UiText.choose("最后记录阶段：%1$s\n\n%2$s", "Last recorded stage: %1$s\n\n%2$s"),
            stage,
            com.deepseekharness.app.util.MaintenanceErrorText.render(view.detail));
      case UNREADY:
        return UiText.choose(
            "维护步骤已结束，但环境就绪检查未通过。已停止自动跳转，请查看原因或进入受限主界面。",
            "Maintenance ended, but readiness checks did not pass. Automatic navigation stopped. Review the reason or enter the limited interface.");
      case PENDING:
        return UiText.choose(
            "检测到未完成的维护事务；请先选择「恢复中断维护」，确认原环境回切后再继续。",
            "An interrupted maintenance transaction was found. Recover interrupted maintenance and confirm the original environment before continuing.");
      default:
        return "";
    }
  }

  /** 运行中提示通知；未开通知时给一个“允许通知”入口（打开系统应用通知设置）。 */
  private View notificationNote() {
    boolean enabled =
        androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled();
    if (enabled)
      return kit.fine(
          UiText.choose("通知栏会同步显示进度。", "Progress is also shown in the notification shade."));
    LinearLayout row = kit.row();
    TextView note =
        kit.fine(
            UiText.choose(
                "开启通知后，可在通知栏查看进度。",
                "Allow notifications to follow progress in the notification shade."));
    row.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
    View allow =
        kit.ghost(
            UiText.choose("允许通知", "Allow notifications"),
            v -> {
              try {
                activity.startActivity(
                    new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
              } catch (RuntimeException unavailable) {
                android.widget.Toast.makeText(
                        activity,
                        UiText.choose("无法打开系统通知设置", "Could not open notification settings"),
                        android.widget.Toast.LENGTH_SHORT)
                    .show();
              }
            });
    LinearLayout.LayoutParams allowParams = new LinearLayout.LayoutParams(-2, -2);
    allowParams.leftMargin = kit.dp(8);
    row.addView(allow, allowParams);
    return row;
  }

  private View buttons(DeployStages.View view) {
    View logs =
        kit.secondary(
            UiText.choose("查看本次维护记录", "View maintenance record"),
            v -> activity.startActivity(DiagnosticActivity.downloadLogs(activity)));
    View emergency =
        kit.ghost(
            UiText.choose("启动应急 DSH", "Start emergency DSH"),
            v -> activity.startActivity(new Intent(activity, RecoveryActivity.class)));
    View limited =
        inOnboarding && onLimitedEntry != null
            ? kit.ghost(
                UiText.choose("进入受限主界面 · 查看日志与配置", "Enter limited interface · logs and settings"),
                v -> {
                  dismiss();
                  onLimitedEntry.run();
                })
            : null;
    switch (view.phase) {
      case FAILED:
      case PENDING:
        RuntimeMaintenanceStarter.Retry kind = retryKind(view);
        View retry =
            kit.primary(
                RuntimeMaintenanceStarter.retryLabel(kind, true),
                v ->
                    RuntimeMaintenanceStarter.confirmRetry(
                        activity,
                        () -> {
                          monitor.refreshNow();
                          render(monitor.current());
                        },
                        view.mode == DeployStages.Mode.REBUILD));
        return kit.buttonBar(retry, logs, emergency, limited);
      case UNREADY:
        View primaryLogs =
            kit.primary(
                UiText.choose("查看本次维护记录", "View maintenance record"),
                v -> activity.startActivity(DiagnosticActivity.downloadLogs(activity)));
        View limitedSecondary =
            inOnboarding && onLimitedEntry != null
                ? kit.secondary(
                    UiText.choose(
                        "进入受限主界面 · 查看日志与配置", "Enter limited interface · logs and settings"),
                    v -> {
                      dismiss();
                      onLimitedEntry.run();
                    })
                : null;
        return kit.buttonBar(primaryLogs, limitedSecondary, emergency);
      default:
        View close = kit.primary(UiText.choose("收起", "Hide"), v -> dismiss());
        View ghostLogs =
            kit.ghost(
                UiText.choose("查看本次维护记录", "View maintenance record"),
                v -> activity.startActivity(DiagnosticActivity.downloadLogs(activity)));
        return kit.buttonBar(close, ghostLogs);
    }
  }

  /** 重试按钮文案用与确认对话框相同的判定（待恢复优先），磁盘检查已由监视器在后台完成。 */
  private RuntimeMaintenanceStarter.Retry retryKind(DeployStages.View view) {
    // 未关闭的试运行现场同样要先恢复：再次更新会以 TRIAL_RECOVERY_REQUIRED 拒绝。
    if (view.phase == DeployStages.Phase.PENDING
        || monitor.pendingMaintenance()
        || RuntimeMaintenanceStarter.pendingTrial(activity))
      return RuntimeMaintenanceStarter.Retry.RECOVER;
    switch (view.mode) {
      case INSTALL:
        return RuntimeMaintenanceStarter.Retry.DEPLOY;
      case REBUILD:
        return RuntimeMaintenanceStarter.Retry.REBUILD;
      default:
        return RuntimeMaintenanceStarter.Retry.UPDATE;
    }
  }

  private void dismiss() {
    if (dialog != null && dialog.isShowing()) dialog.dismiss();
  }
}
