package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.*;
import com.deepseekharness.app.util.StartupRecoveryState.Failure;
import com.deepseekharness.app.util.StartupRecoveryState.Guard;
import com.deepseekharness.app.util.StartupRecoveryState.Kind;
import com.deepseekharness.app.util.StartupRecoveryState.Outcome;
import com.deepseekharness.app.util.StartupRecoveryState.Tone;
import java.util.*;
import org.json.*;

/**
 * Web 无法加载时仍可使用的原生恢复工作台（方案 A）。顶部状态卡只给一个结论（StartupRecoveryState），
 * 对症主操作在卡内；其余操作每行只有动作，效果说明收进行尾 ⓘ，不可用时写明原因。所有写操作仍经
 * BackupTask 与既有确认。
 */
public final class StartupRecoveryActivity extends AppCompatActivity {
  private HarnessController controller;
  private StartupRecoveryModel model;
  private StartupRecoveryLayout layout;

  /** 操作结果横幅；StartupRecoveryLifecycleTest 不经 onCreate 直接注入此字段。 */
  private TextView progress;

  private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
  private String previousTask = "";
  private String shownReadError = "";
  private String renderedKey = "";
  private boolean wasBusy, environmentReady = true, showAllAttempts;
  private Kind lastKind;
  private JSONObject contents;

  /** 一个页面动作：文案、图标、效果说明、可用性守卫与执行。 */
  private static final class Act {
    final String label, effect;
    final int icon;
    final Guard guard;
    final Runnable run;

    Act(String label, int icon, String effect, Guard guard, Runnable run) {
      this.label = label;
      this.icon = icon;
      this.effect = effect;
      this.guard = guard;
      this.run = run;
    }
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    controller = HarnessController.get(this);
    if (!controller.config().isWelcomed()) {
      startActivity(new Intent(this, OnboardingActivity.class));
      finish();
      return;
    }
    layout = new StartupRecoveryLayout(this, this::finish, () -> model.load());
    setContentView(layout.root);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    progress = layout.progress;
    layout.attemptsToggle.setOnClickListener(
        v -> {
          showAllAttempts = !showAllAttempts;
          renderedKey = "";
          renderPage();
        });
    environmentReady = controller.isEnvironmentReady() && controller.proot().hasBash();
    model = new ViewModelProvider(this).get(StartupRecoveryModel.class);
    BackupTaskState.Snapshot initial = BackupTask.get(this).snapshot();
    previousTask = initial.id + ":" + initial.status + ":" + initial.detail;
    model.contents.observe(
        this,
        value -> {
          if (value == null) return;
          contents = value;
          environmentReady = controller.isEnvironmentReady() && controller.proot().hasBash();
          renderedKey = "";
          renderPage();
        });
    model.error.observe(
        this,
        value -> {
          if (value != null && !value.isEmpty()) {
            shownReadError = readable(value);
            status(Tone.BAD, shownReadError);
          } else if (value != null && !shownReadError.isEmpty()) {
            // 之后的任务结果有自己的横幅；这里只清掉本次读取留下的旧错误。
            if (shownReadError.contentEquals(progress.getText())) status(Tone.IDLE, "");
            shownReadError = "";
          }
          renderedKey = "";
          renderPage();
        });
    renderPage();
    if (model.contents.getValue() == null) model.load();
  }

  private void status(Tone tone, String text) {
    if (layout != null) layout.banner(tone, text);
    else {
      progress.setText(text);
      progress.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }
  }

  private void status(String text) {
    status(Tone.BAD, text);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static JSONObject request(String command) {
    JSONObject value = new JSONObject();
    try {
      value.put("command", command);
    } catch (JSONException ignored) {
    }
    return value;
  }

  // ---------------------------------------------------------------- 状态判定

  private StartupHistoryStore.Entry latest() {
    return model.attempts.isEmpty() ? null : model.attempts.get(0);
  }

  private Outcome outcome(StartupHistoryStore.Entry entry) {
    boolean current = entry.id.equals(controller.startupDiagnostics().recordId());
    return StartupRecoveryState.outcome(
        entry.status,
        entry.safe,
        entry.backendReadyMs,
        entry.browserReadyMs,
        current,
        controller.isStarting());
  }

  private static Failure failure(StartupHistoryStore.Entry entry) {
    return StartupRecoveryState.classify(entry.reason, entry.zhLog, entry.backendReadyMs >= 0);
  }

  private boolean pending() {
    return StartupRepairs.pending(this) || (contents != null && contents.optBoolean("pending"));
  }

  /** 最新一条健康且未损坏的快照；没有则返回 null。 */
  private JSONObject latestHealthy() {
    JSONArray records = contents == null ? null : contents.optJSONArray("snapshots");
    JSONObject best = null;
    if (records != null)
      for (int i = 0; i < records.length(); i++) {
        JSONObject record = records.optJSONObject(i);
        if (record == null || record.optBoolean("invalid") || !healthy(record)) continue;
        if (best == null || record.optLong("created") > best.optLong("created")) best = record;
      }
    return best;
  }

  private static boolean healthy(JSONObject record) {
    return record.optBoolean("healthy", record.optString("slot").startsWith("healthy"));
  }

  /** 插件清单里可卸载的那个出错插件；不在清单里就不提供卸载。 */
  private boolean removable(String name) {
    JSONArray items = contents == null ? null : contents.optJSONArray("items");
    if (items == null || name.isEmpty() || BuiltinPlugins.internal(name)) return false;
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item != null && item.optBoolean("deletable") && name.equals(item.optString("name")))
        return true;
    }
    return false;
  }

  private Kind kind(Failure[] failure) {
    StartupRecoveryState.Input in = new StartupRecoveryState.Input();
    in.pending = pending();
    in.busy = BackupTask.get(this).maintenanceBusy();
    in.environmentReady = environmentReady;
    in.starting = controller.isStarting();
    in.running = controller.isWebRunning();
    StartupHistoryStore.Entry entry = latest();
    if (entry != null) {
      in.latest = outcome(entry);
      if (in.latest == Outcome.FAILED || in.latest == Outcome.LOST_AFTER_READY)
        in.latestFailure = failure(entry);
    } else if (!controller.config().getWebFailureReason().isEmpty())
      in.storedFailure =
          StartupRecoveryState.classify(controller.config().getWebFailureReason(), "", false);
    failure[0] = in.latestFailure != null ? in.latestFailure : in.storedFailure;
    return StartupRecoveryState.page(in);
  }

  // ---------------------------------------------------------------- 渲染

  /** 只在结论、任务忙碌、快照或记录变化时重建，700ms 刷新不造成闪烁。 */
  private void renderPage() {
    if (layout == null || model == null) return;
    Failure[] holder = new Failure[1];
    Kind kind = kind(holder);
    Failure failure = holder[0];
    boolean taskBusy = BackupTask.get(this).busy();
    JSONObject healthy = latestHealthy();
    if (lastKind == Kind.STARTING && kind != Kind.STARTING) model.load();
    lastKind = kind;
    String stage = kind == Kind.STARTING ? controller.startupDiagnostics().snapshot().stage : "";
    String key =
        StartupRecoveryState.key(kind, failure, taskBusy, healthy != null)
            + "|"
            + stage
            + "|"
            + System.identityHashCode(model.attempts)
            + "|"
            + System.identityHashCode(contents)
            + "|"
            + showAllAttempts;
    if (key.equals(renderedKey)) return;
    renderedKey = key;
    int scrollY = layout.root.getScrollY();
    renderStatus(kind, failure, taskBusy, healthy, stage);
    renderActions(kind, failure, taskBusy, healthy);
    renderAttempts();
    renderSnapshots(kind, taskBusy);
    // 列表整块重建时给 150ms 淡入并恢复滚动位置，避免状态刷新把页面顶回顶部（审计 B-04）。
    UiMotion.reveal(layout.recovery);
    UiMotion.reveal(layout.tools);
    UiMotion.reveal(layout.attempts);
    UiMotion.reveal(layout.snapshots);
    if (layout.root.getScrollY() != scrollY)
      layout.root.post(() -> layout.root.scrollTo(0, scrollY));
  }

  private String meta(Kind kind, StartupHistoryStore.Entry entry, String stage) {
    List<String> parts = new ArrayList<>();
    if (kind == Kind.STARTING && !stage.isEmpty()) parts.add(t("当前阶段：", "Current stage: ") + stage);
    else if (kind.tone == Tone.BAD && entry != null) {
      if (controller.config().isStartupRecoveryRequested())
        parts.add(t("启动失败后自动打开", "Opened automatically after a failed start"));
      if (!entry.stage.isEmpty()) parts.add(t("失败阶段：", "Failed stage: ") + entry.stage);
    } else if (kind == Kind.IDLE || kind == Kind.STOPPED || kind == Kind.RUNNING) {
      for (StartupHistoryStore.Entry e : model.attempts) {
        Outcome o = outcome(e);
        if (o.tone != Tone.GOOD) continue;
        parts.add(
            t("最近成功：", "Last success: ")
                + when(e.started)
                + " · "
                + StartupRecoveryState.elapsedLabel(o, e.elapsed, e.browserReadyMs));
        break;
      }
    }
    return String.join(" · ", parts);
  }

  private void renderStatus(
      Kind kind, Failure failure, boolean taskBusy, JSONObject healthy, String stage) {
    String subject = failure == null ? "" : failure.subject;
    String detail = StartupRecoveryState.detail(kind);
    if (kind == Kind.FAILED && failure != null && !failure.raw.isEmpty())
      detail += "\n" + StartupText.render(failure.raw);
    String code = kind.code;
    LinearLayout buttons =
        layout.statusCard(
            kind.tone,
            StartupRecoveryState.title(kind, subject, failure == null ? "" : failure.code),
            code,
            detail,
            meta(kind, latest(), stage));
    boolean first = true;
    for (Act act : primary(kind, failure, healthy)) {
      Button button = layout.statusButton(buttons, act.label, first, act.run);
      String why = blocked(act, kind, taskBusy, healthy);
      if (!why.isEmpty()) {
        button.setEnabled(false);
        button.setAlpha(0.6f);
        button.setContentDescription(
            act.label + UiText.text("，") + t("不可用：", "Unavailable: ") + why);
      }
      first = false;
    }
  }

  private String blocked(Act act, Kind kind, boolean taskBusy, JSONObject healthy) {
    return act.guard == null
        ? ""
        : StartupRecoveryState.blocked(act.guard, kind, taskBusy, healthy != null);
  }

  private void renderActions(Kind kind, Failure failure, boolean taskBusy, JSONObject healthy) {
    Set<String> shown = new HashSet<>();
    for (Act act : primary(kind, failure, healthy)) shown.add(act.label);
    layout.recovery.removeAllViews();
    List<Act> recovery = new ArrayList<>();
    if (kind == Kind.PENDING) recovery.add(recover());
    if (kind != Kind.IDLE && kind != Kind.STOPPED) recovery.add(retry());
    else recovery.add(start());
    recovery.add(safe());
    recovery.add(newConfiguration());
    recovery.add(restoreLatest(healthy));
    recovery.add(
        new Act(
            t("启动独立应急 DSH", "Start independent emergency DSH"),
            R.drawable.ic_recovery_shield,
            t(
                "独立运行根，不读取正式环境，可让 AI 协助排查。",
                "Separate runtime root that does not read the main environment; AI can help investigate."),
            null,
            () -> startActivity(new Intent(this, RecoveryActivity.class))));
    for (Act act : recovery) row(layout.recovery, act, kind, taskBusy, healthy, shown);

    layout.tools.removeAllViews();
    List<Act> tools = new ArrayList<>();
    tools.add(logs());
    tools.add(hostBackup());
    tools.add(rollback());
    tools.add(
        new Act(
            t("环境安装与修复", "Environment installation and repair"),
            R.drawable.ic_recovery_gear,
            "",
            null,
            this::openEnvironment));
    for (Act act : tools) row(layout.tools, act, kind, taskBusy, healthy, shown);
  }

  private void row(
      LinearLayout parent,
      Act act,
      Kind kind,
      boolean taskBusy,
      JSONObject healthy,
      Set<String> shown) {
    if (shown.contains(act.label)) return;
    String why = blocked(act, kind, taskBusy, healthy);
    // 「回滚未完成的修复」只在确有待处理修复时出现，不再常驻。
    if (act.guard == Guard.PENDING_ONLY && kind != Kind.PENDING) return;
    layout.action(parent, act.label, act.effect, why, !why.isEmpty(), act.icon, act.run, true);
  }

  // ---------------------------------------------------------------- 动作

  private List<Act> primary(Kind kind, Failure failure, JSONObject healthy) {
    String subject = failure == null ? "" : failure.subject;
    switch (kind) {
      case IDLE:
      case STOPPED:
        return List.of(start());
      case RUNNING:
        return List.of(
            new Act(
                t("返回 Web", "Back to Web"),
                R.drawable.ic_recovery_right,
                "",
                null,
                () -> {
                  startActivity(MainActivity.homeIntent(this).putExtra("open_web", true));
                  finish();
                }));
      case STARTING:
        return List.of(logs());
      case PORT:
        return List.of(openConfig(t("修改 Web 端口", "Change Web port")), retry());
      case PLUGIN:
        return removable(subject) ? List.of(uninstall(subject), safe()) : List.of(safe(), logs());
      case REGISTER:
        return List.of(retry(), logs());
      case PAGE:
      case FAILED:
        return List.of(safe(), logs());
      case CONFIG:
        return healthy != null
            ? List.of(restoreLatest(healthy), newConfiguration())
            : List.of(newConfiguration(), safe());
      case EXIT:
        return List.of(logs(), rollback());
      case CREDENTIAL:
        return List.of(openConfig(t("重新输入 API Key", "Re-enter API key")), retry());
      case HEALTH:
      case INTERRUPTED:
        return List.of(retry(), logs());
      case ENVIRONMENT:
        return List.of(
            new Act(
                t("修复运行环境", "Repair runtime"),
                R.drawable.ic_recovery_gear,
                "",
                null,
                this::openEnvironment),
            hostBackup());
      case PENDING:
        return List.of(recover());
      default:
        return List.of();
    }
  }

  private Act start() {
    return new Act(
        t("启动 DSH", "Start DSH"),
        R.drawable.ic_recovery_refresh,
        t("正常启动，加载全部已启用插件。", "Start normally with all enabled plugins."),
        Guard.START,
        () -> retry(false));
  }

  private Act retry() {
    return new Act(
        t("重试启动", "Retry startup"),
        R.drawable.ic_recovery_refresh,
        t("按原配置重新启动一次。", "Start once more with the current configuration."),
        Guard.START,
        () -> retry(false));
  }

  private Act safe() {
    return new Act(
        t("安全启动", "Safe start"),
        R.drawable.ic_recovery_shield,
        t(
            "只加载官方基础界面；插件开关与配置原样保留。",
            "Loads only the official basic interface; plugin switches and configuration stay as they are."),
        Guard.START,
        () -> retry(true));
  }

  private Act newConfiguration() {
    return new Act(
        t("新建配置文件", "Create configuration file"),
        R.drawable.ic_recovery_new_file,
        t(
            "以默认内容替换所选配置，原文件先存为修复前快照。",
            "Replaces the selected configuration with defaults after saving the original as a pre-repair snapshot."),
        Guard.CHANGE,
        this::newConfigurationDialog);
  }

  private Act restoreLatest(JSONObject healthy) {
    return new Act(
        t("恢复最近健康快照", "Restore latest healthy snapshot"),
        R.drawable.ic_recovery_clock,
        t(
            "只覆盖配置文件；会话、工作区和插件文件不动。",
            "Overwrites configuration files only; sessions, workspaces and plugin files are untouched."),
        Guard.RESTORE,
        () -> {
          JSONObject record = latestHealthy();
          if (record != null) confirmRestore(record, snapshotLabel(record));
        });
  }

  private Act recover() {
    return new Act(
        t("回滚未完成的修复", "Roll back the unfinished repair"),
        R.drawable.ic_recovery_wrench,
        t(
            "用修复前快照撤回写了一半的配置。",
            "Uses the pre-repair snapshot to undo the half-written configuration."),
        Guard.PENDING_ONLY,
        () ->
            confirm(
                t("回滚未完成的修复", "Roll back the unfinished repair"),
                t(
                    "将使用修复前的快照回退中断的配置写入，然后才能重新启动。",
                    "Roll back the interrupted configuration changes using the saved pre-repair copy before restarting."),
                () -> repair(request("recover"))));
  }

  private Act uninstall(String name) {
    return new Act(
        t("卸载出错插件", "Remove failing plugin"),
        R.drawable.ic_recovery_wrench,
        "",
        Guard.CHANGE,
        () -> confirmUninstall(name));
  }

  private Act logs() {
    return new Act(
        t("查看并下载日志", "View and download logs"),
        R.drawable.ic_recovery_document,
        "",
        null,
        () -> startActivity(DiagnosticActivity.downloadLogs(this)));
  }

  private Act hostBackup() {
    return new Act(
        t("宿主备份与只读救援", "Host backup and read-only rescue"),
        R.drawable.ic_recovery_database,
        "",
        null,
        () -> startActivity(new Intent(this, NativeDataActivity.class)));
  }

  private Act rollback() {
    return new Act(
        t("回退兼容运行时", "Roll back compatible runtime"),
        R.drawable.ic_recovery_clock,
        "",
        Guard.CHANGE,
        () -> RuntimeRecoveryUi.show(this));
  }

  /** 复用配置页（端口、API Key 都在其中），不新建页面。 */
  private Act openConfig(String label) {
    return new Act(
        label,
        R.drawable.ic_recovery_gear,
        "",
        null,
        () -> {
          startActivity(MainActivity.navigationIntent(this).putExtra("open_config", true));
          finish();
        });
  }

  private void openEnvironment() {
    startActivity(new Intent(this, ExtractActivity.class).putExtra("review_only", true));
  }

  private void confirm(String title, String detail, Runnable work) {
    new DshaDialogBuilder(this, true)
        .setTitle(title)
        .setMessage(
            detail
                + t(
                    "\n\n操作会停止 Web 和终端，并保留修复前配置。",
                    "\n\nThis stops Web and terminal sessions and saves the configuration before making changes."))
        .setNegativeButton(t("取消", "Cancel"), null)
        .setPositiveButton(t("继续", "Continue"), (d, w) -> work.run())
        .show();
  }

  private void confirmUninstall(String name) {
    confirm(
        t("卸载插件", "Remove plugin"),
        name
            + t(
                "\n仅卸载所选插件，使用现有插件管理器核验结果。",
                "\nRemove only the selected plugin and verify the result with the plugin manager."),
        () -> {
          if (!BackupTask.get(this).removeStartupPlugin(name)) status(readable("RECOVERY_BUSY"));
        });
  }

  private void confirmRestore(JSONObject record, String label) {
    JSONObject restore = request("restore");
    try {
      restore.put("slot", record.getString("slot")).put("id", record.getString("id"));
    } catch (JSONException ignored) {
    }
    String missing =
        record.optJSONArray("missing") != null && record.optJSONArray("missing").length() > 0
            ? t("\n快照中原本不存在，将恢复为缺失：", "\nAbsent in this snapshot; restore to absence: ")
                + record.optJSONArray("missing")
            : "";
    confirm(
        t("恢复所选配置快照", "Restore selected configuration snapshot"),
        label
            + "\n"
            + record.optJSONArray("files")
            + missing
            + t(
                "\n只恢复这些配置文件；已卸载的插件不会自动重新安装。",
                "\nRestore only these configuration files. Removed plugins are not automatically reinstalled."),
        () -> repair(restore));
  }

  /** 正常快照：恢复为主操作，删除为次要操作并单独二次确认。 */
  private void snapshotActions(JSONObject record, String label) {
    // 选择本身不是安全确认；所选动作再各自进入既有确认，全局确认策略不会替用户选“恢复”。
    String[] choices = {t("恢复此快照", "Restore this snapshot"), t("删除此快照", "Delete this snapshot")};
    new DshaDialogBuilder(this)
        .setTitle(label)
        .setItems(
            choices,
            (d, index) -> {
              if (index == 0) confirmRestore(record, label);
              else confirmDiscard(label, discardRequest(record));
            })
        .setNegativeButton(t("取消", "Cancel"), null)
        .show();
  }

  private static JSONObject discardRequest(JSONObject record) {
    JSONObject value = request("discard");
    try {
      value.put("slot", record.optString("slot"));
      if (!record.optString("id").isEmpty()) value.put("id", record.optString("id"));
      if (!record.optString("fingerprint").isEmpty())
        value.put("fingerprint", record.optString("fingerprint"));
    } catch (JSONException ignored) {
    }
    return value;
  }

  private static JSONObject discardRetainedRequest() {
    JSONObject value = request("discard-retained");
    try {
      value.put("target", "all");
    } catch (JSONException ignored) {
    }
    return value;
  }

  /** 删除只移除快照副本，不改动当前配置；需要再次确认。 */
  private void confirmDiscard(String detail, JSONObject request) {
    new DshaDialogBuilder(this, true)
        .setTitle(t("删除配置快照", "Delete configuration snapshot"))
        .setMessage(
            detail
                + t(
                    "\n\n只删除这份快照副本，当前配置、会话与插件不受影响。删除后无法用它恢复。",
                    "\n\nOnly this snapshot copy is deleted. Current configuration, sessions, and plugins are unaffected. It cannot be used for restore afterwards."))
        .setNegativeButton(t("取消", "Cancel"), null)
        .setPositiveButton(t("删除", "Delete"), (d, w) -> repair(request))
        .show();
  }

  private void repair(JSONObject request) {
    if (!BackupTask.get(this).repairStartup(request))
      Toast.makeText(
              this,
              t("已有任务进行中，请完成后重试。", "Another task is in progress. Retry when it finishes."),
              Toast.LENGTH_LONG)
          .show();
    refresh.run();
  }

  private void newConfigurationDialog() {
    String[] names = {
      t("Web 启动配置", "Web startup configuration"),
      t("共享设置（settings.yaml）", "Shared settings (settings.yaml)"),
      t("共享补丁（cordis.patch.yml）", "Shared patch (cordis.patch.yml)")
    };
    String[] targets = {"web", "settings.yaml", "cordis.patch.yml"};
    new DshaDialogBuilder(this)
        .setTitle(t("选择新建的配置文件", "Choose the configuration to create"))
        .setItems(
            names,
            (d, index) -> {
              JSONObject request = request("new");
              try {
                request.put("target", targets[index]);
              } catch (JSONException ignored) {
              }
              confirm(
                  names[index],
                  t(
                      "将以默认内容替换所选配置。原文件保存到修复前快照，插件文件、会话和原生 API Key 保留。",
                      "Replace the selected configuration with defaults. Save the original files in a pre-repair snapshot and retain plugin files, sessions, and the native API key."),
                  () -> repair(request));
            })
        .setNegativeButton(t("取消", "Cancel"), null)
        .show();
  }

  private void retry(boolean safe) {
    if (!controller.isEnvironmentReady()) {
      status(
          t(
              "环境未就绪，可先修复或导出数据",
              "The runtime is not ready. Repair configuration or export data, then open installation and repair."));
      return;
    }
    if (StartupRepairs.pending(this)) {
      status(readable("RECOVERY_PENDING"));
      return;
    }
    if (!FirstRunWebStart.enqueueVisible(this, safe, true, this::status)) return;
    startActivity(MainActivity.homeIntent(this));
    finish();
  }

  // ---------------------------------------------------------------- 记录与快照

  private void renderAttempts() {
    LinearLayout attempts = layout.attempts;
    attempts.removeAllViews();
    List<StartupHistoryStore.Entry> all = model.attempts;
    int unsuccessful = 0;
    for (StartupHistoryStore.Entry entry : all) {
      Tone tone = outcome(entry).tone;
      if (tone == Tone.BAD || tone == Tone.WARN) unsuccessful++;
    }
    layout.attemptsSummary.setText(
        all.isEmpty()
            ? ""
            : unsuccessful == 0
                ? t("全部成功", "All succeeded")
                : UiText.format("%s 次未成功", unsuccessful));
    int limit = layout.wide || showAllAttempts ? all.size() : Math.min(3, all.size());
    layout.attemptsToggle.setVisibility(!layout.wide && all.size() > 3 ? View.VISIBLE : View.GONE);
    layout.attemptsToggle.setText(
        showAllAttempts ? UiText.text("收起") : UiText.format("全部 %s 条", all.size()));
    for (int i = 0; i < limit; i++) {
      StartupHistoryStore.Entry entry = all.get(i);
      Outcome o = outcome(entry);
      Failure f = o == Outcome.FAILED || o == Outcome.LOST_AFTER_READY ? failure(entry) : null;
      String label = StartupRecoveryState.outcomeLabel(o, f == null ? Kind.FAILED : f.kind);
      layout.statusAction(
          attempts,
          o.tone,
          label,
          when(entry.started),
          StartupRecoveryState.elapsedLabel(o, entry.elapsed, entry.browserReadyMs),
          () -> showAttempt(entry, label, f));
    }
    if (all.isEmpty())
      layout.note(
          attempts,
          controller.startupDiagnostics().historyError().isEmpty()
              ? t(
                  "还没有启动记录；下次启动自动记录",
                  "No startup records yet. The next start will be recorded automatically.")
              : t("启动记录暂不可用：", "Startup records are currently unavailable: ")
                  + controller.startupDiagnostics().historyError());
  }

  private void showAttempt(StartupHistoryStore.Entry entry, String label, Failure failure) {
    ScrollView scroll = new ScrollView(this);
    TextView text = new TextView(this);
    text.setPadding(dp(16), dp(12), dp(16), dp(12));
    text.setTextIsSelectable(true);
    StringBuilder body = new StringBuilder(label);
    if (failure != null && !failure.kind.code.isEmpty())
      body.append("\n")
          .append(failure.kind.code)
          .append(failure.code.isEmpty() ? "" : " · " + failure.code);
    if (!entry.stage.isEmpty()) body.append("\n\n").append(entry.stage);
    if (!entry.reason.isEmpty()) body.append("\n\n").append(StartupText.render(entry.reason));
    body.append("\n\n").append(entry.log);
    text.setText(body.toString());
    scroll.addView(text);
    new DshaDialogBuilder(this)
        .setTitle(date(entry.started))
        .setView(scroll)
        .setPositiveButton(t("关闭", "Close"), null)
        .show();
  }

  private String snapshotLabel(JSONObject record) {
    return (healthy(record) ? t("健康启动", "Healthy start") : t("修复之前", "Before repair"))
        + " · "
        + date(record.optLong("created"));
  }

  /** 快照按时间倒序；健康=绿、修复前=蓝、损坏=红且只能删除。 */
  private void renderSnapshots(Kind kind, boolean taskBusy) {
    layout.snapshots.removeAllViews();
    layout.plugins.removeAllViews();
    if (contents == null) return;
    String blockedReason = StartupRecoveryState.blocked(Guard.CHANGE, kind, taskBusy, true);
    JSONArray records = contents.optJSONArray("snapshots");
    List<JSONObject> sorted = new ArrayList<>();
    if (records != null)
      for (int i = 0; i < records.length(); i++)
        if (records.optJSONObject(i) != null) sorted.add(records.optJSONObject(i));
    sorted.sort((a, b) -> Long.compare(b.optLong("created"), a.optLong("created")));
    for (JSONObject record : sorted) {
      if (record.optBoolean("invalid")) {
        // 损坏快照禁止恢复，但必须能删除；否则它会一直占着槽位与列表。
        String damaged =
            t("快照损坏，已禁止恢复：", "Snapshot is damaged and cannot be restored: ")
                + record.optString("slot")
                + (record.optString("error").isEmpty() ? "" : " · " + record.optString("error"));
        snapshotRow(
            Tone.BAD,
            t("已损坏 · 禁止恢复", "Damaged · restore blocked"),
            record.optLong("created") > 0
                ? when(record.optLong("created"))
                : record.optString("slot"),
            blockedReason,
            () ->
                confirmDiscard(
                    damaged
                        + (record.optString("id").isEmpty() ? "" : "\n" + record.optString("id")),
                    discardRequest(record)));
        continue;
      }
      boolean good = healthy(record);
      snapshotRow(
          good ? Tone.GOOD : Tone.INFO,
          good ? t("健康启动", "Healthy start") : t("修复之前", "Before repair"),
          when(record.optLong("created")),
          blockedReason,
          () -> snapshotActions(record, snapshotLabel(record)));
    }
    JSONObject retained = contents.optJSONObject("retained");
    int retainedCount = retained == null ? 0 : retained.optInt("count");
    if (retainedCount > 0) {
      String summary = UiText.format("已隔离 %s 份无法校验的异常快照（不参与恢复与轮换）", retainedCount);
      snapshotRow(
          Tone.WARN,
          UiText.format("已隔离 %s 份", retainedCount),
          t("删除", "Delete"),
          blockedReason,
          () ->
              confirmDiscard(
                  summary
                      + t(
                          "\n这些是旧版本写入中断或校验失败留下的副本，无法用于恢复。",
                          "\nThese copies were left by interrupted writes or failed checks in older versions and cannot be restored."),
                  discardRetainedRequest()));
    }
    if (sorted.isEmpty() && retainedCount == 0)
      layout.note(
          layout.snapshots,
          t(
              "还没有快照；下次启动自动保存",
              "No snapshots yet. The next error-free start saves one automatically."));
    renderPlugins(kind, taskBusy);
  }

  private void snapshotRow(Tone tone, String label, String time, String blocked, Runnable click) {
    View row = layout.statusAction(layout.snapshots, tone, label, time, "", click);
    if (!blocked.isEmpty()) StartupRecoveryLayout.enabled(row, false);
  }

  private void renderPlugins(Kind kind, boolean taskBusy) {
    JSONArray items = contents.optJSONArray("items");
    String failing = "";
    StartupHistoryStore.Entry entry = latest();
    if (entry != null && (kind == Kind.PLUGIN || kind == Kind.PAGE))
      failing = failure(entry).subject;
    String why = StartupRecoveryState.blocked(Guard.CHANGE, kind, taskBusy, true);
    int count = 0;
    if (items != null)
      for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (item == null || !item.optBoolean("deletable")) continue;
        String name = item.optString("name");
        if (BuiltinPlugins.internal(name)) continue;
        count++;
        // 行文字保持「名称 · 卸载」：StartupRecoveryAudit 按此查找可点行。
        String note =
            !why.isEmpty() ? why : name.equals(failing) ? t("本次启动报错", "Failed in this start") : "";
        layout.action(
            layout.plugins,
            name + " · " + t("卸载", "Remove"),
            "",
            note,
            !why.isEmpty(),
            0,
            () -> confirmUninstall(name),
            true);
      }
    layout.pluginCard.setVisibility(
        count > 0 || !contents.optString("pluginError").isEmpty() ? View.VISIBLE : View.GONE);
    if (!contents.optString("pluginError").isEmpty())
      layout.note(
          layout.plugins,
          t(
                  "插件清单暂不可读；可恢复或新建配置",
                  "Cannot read the plugin list. Restore or create configuration first.\n")
              + readable(contents.optString("pluginError")));
  }

  private String date(long time) {
    return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .format(new Date(time));
  }

  /** 列表时间：当天写「今天 HH:mm」，其余写「MM-dd HH:mm」；完整时间在详情里。 */
  private String when(long time) {
    Calendar now = Calendar.getInstance(), at = Calendar.getInstance();
    at.setTimeInMillis(time);
    boolean today =
        now.get(Calendar.YEAR) == at.get(Calendar.YEAR)
            && now.get(Calendar.DAY_OF_YEAR) == at.get(Calendar.DAY_OF_YEAR);
    String clock =
        new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(time));
    return today
        ? UiText.format("今天 %s", clock)
        : new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(time));
  }

  private String readable(String value) {
    if (value.contains("RECOVERY_PENDING"))
      return t(
          "配置修复未完成，请使用「回滚未完成的修复」。",
          "Configuration repair is incomplete. Select Roll back the unfinished repair.");
    if (value.contains("RECOVERY_BUSY"))
      return t("环境任务正在执行，完成后刷新即可。", "An environment task is running. Refresh when it finishes.");
    if (value.contains("RECOVERY_LINK"))
      return t(
          "配置文件使用软链接，已保留原文件。请手动检查链接目标。",
          "A configuration file uses a symbolic link. The original was retained. Check the link target manually.");
    if (value.contains("RECOVERY_CHECKSUM") || value.contains("RECOVERY_FORMAT"))
      return t(
          "配置快照校验失败，未应用修复。",
          "Configuration snapshot validation failed. The repair was not applied.");
    if (value.contains("RECOVERY_CHANGED"))
      return t(
          "配置或快照已变化，请刷新后重新选择。",
          "The configuration or snapshot changed. Refresh and select it again.");
    if (value.contains("RECOVERY_RETENTION_LIMIT"))
      return t(
          "配置快照目录已满且无法自动整理，请删除损坏或隔离的快照后重试。",
          "The configuration snapshot directory is full and could not be tidied. Delete damaged or quarantined snapshots, then retry.");
    if (value.contains("RECOVERY_CATALOG"))
      return t(
          "快照目录索引无法读取，已停止整理以免误删；原文件保留，可导出日志排查。",
          "The snapshot index cannot be read. Tidying stopped to avoid deleting anything; originals were retained. Export logs to investigate.");
    if (value.contains("RECOVERY_SLOT"))
      return t("所选快照不能删除或恢复。", "The selected snapshot cannot be deleted or restored.");
    if (value.contains("RECOVERY_SIZE"))
      return t(
          "配置文件超过快照大小限制或不是普通文件，原文件保留。",
          "A configuration file exceeds the snapshot size limit or is not a regular file. The original was retained.");
    return StartupText.render(value);
  }

  /** 任务结果的语义色：成功绿、失败红、进行中蓝、取消灰、中断琥珀。 */
  private static Tone tone(BackupTaskState.Status status) {
    switch (status) {
      case SUCCEEDED:
        return Tone.GOOD;
      case FAILED:
        return Tone.BAD;
      case RUNNING:
      case PREVIEW:
        return Tone.INFO;
      case INTERRUPTED:
        return Tone.WARN;
      default:
        return Tone.IDLE;
    }
  }

  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          if (isFinishing()) return;
          BackupTask task = BackupTask.get(StartupRecoveryActivity.this);
          BackupTaskState.Snapshot state = task.snapshot();
          String key = state.id + ":" + state.status + ":" + state.detail;
          if (!key.equals(previousTask)) {
            previousTask = key;
            if (state.id > 0) status(tone(state.status), readable(state.detail));
          }
          boolean maintenance = task.maintenanceBusy();
          if (wasBusy && !maintenance) model.load();
          wasBusy = maintenance;
          model.retryWhenIdle();
          renderPage();
          ui.removeCallbacks(this);
          ui.postDelayed(this, 700);
        }
      };

  @Override
  protected void onResume() {
    super.onResume();
    ui.post(refresh);
  }

  @Override
  protected void onPause() {
    ui.removeCallbacks(refresh);
    super.onPause();
  }
}
