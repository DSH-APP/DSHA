package com.deepseekharness.app.ui;

import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import java.util.*;

/** 原生数据入口不等待 Ubuntu 或网页；界面只传递参数并观察应用级作业。 */
public final class NativeDataActivity extends AppCompatActivity {
  public static final class Pending extends ViewModel {
    char[] password;
    NativeDataLocations.Selection selection;
    String filename;
    boolean rescue, exportPrepared;
    int scopeIndex;
    boolean includeKey;
    String reexportId;
    // 恢复向导：步骤与本向导拥有的作业号随 ViewModel 跨旋转保留；密码不保留。
    int restoreStep;
    String restoreJob = "", restoreUri, restoreCopy, restoreName = "";
    boolean restoreNeedsOriginal, restoreAutomatic;

    /** 预检（PREVIEW）时记下的总量：恢复进度用真实已复制字节 / 条目算百分比。 */
    long restoreTotalEntries, restoreTotalBytes;

    final List<BackupSource> projects = new ArrayList<>();
    final androidx.lifecycle.MutableLiveData<BackupPreview.Report> preview =
        new androidx.lifecycle.MutableLiveData<>();
    final androidx.lifecycle.MutableLiveData<String> previewError =
        new androidx.lifecycle.MutableLiveData<>();
    volatile boolean previewBusy, protectionBusy;
    BackupControl previewControl, protectionControl;

    void clear() {
      if (password != null) Arrays.fill(password, '\0');
      password = null;
      exportPrepared = false;
    }

    @Override
    protected void onCleared() {
      clear();
      if (previewControl != null) previewControl.cancel();
      if (protectionControl != null) protectionControl.cancel();
    }
  }

  private Pending pending;
  private NativeBackupJobs jobs;
  private CardPage page;
  private LinearLayout statusCard, stepper, wizardCard, wizard;

  /** 4 个步骤容器（选择/选项/进度/确认/结果，STEP_* 索引）只建一次；输入步骤保留已填内容。 */
  private final LinearLayout[] stepViews = new LinearLayout[5];

  private final boolean[] stepBuilt = new boolean[5];

  /** 当前正在构建的步骤容器；步骤方法写它，不再整块清空 wizard。 */
  private LinearLayout stepTarget;

  /** 恢复进度复用同一个组件：每次状态刷新只改数值，不重启动画、不丢取消按钮状态。 */
  private DeterminateProgress restoreProgress, restoreOutcome;

  private TextView status, project;

  /** 结果卡三段（审计 E-02）：结论、计数/字节、错误（错误收起为一句，展开看原因与下一步）。 */
  private TextView resultConclusion, resultMeta, resultError;

  private final DisclosureCard.Memory disclosure = new DisclosureCard.Memory();
  private final DisclosureCard.Model failureDisclosure = new DisclosureCard.Model();
  private Button cancelOperation;
  private List<BackupSource> projects;
  private EditText password;
  private CheckBox includeRestoreKey;
  private RadioGroup projectDestination;
  private boolean alwaysWizard;
  private int renderedStep = -1;

  /**
   * data_mode=rescue：维护页与「数据与备份」的「导出可读副本」直达入口。页面同默认救援工具页，
   * 首次创建时直接弹出现有「救援导出」确认框；旋转重建不重复弹出。
   */
  static final String MODE_RESCUE = "rescue";

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  private final ActivityResultLauncher<String> destination =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument(
              com.deepseekharness.app.util.BackupFileNames.MIME_TYPE),
          uri -> {
            if (pending.reexportId != null) {
              String source = pending.reexportId;
              pending.reexportId = null;
              try {
                if (uri != null && !jobs.reexport(source, pending.password, uri, pending.filename))
                  status.setText(
                      t(
                          "副本无法重新导出，请检查记录或当前作业。",
                          "The copy cannot be exported. Review its record or the current operation."));
              } finally {
                pending.clear();
              }
              return;
            }
            if (uri == null) {
              pending.clear();
              return;
            }
            if (!pending.exportPrepared || pending.selection == null || pending.filename == null) {
              pending.clear();
              status.setText(
                  t(
                      "应用已重启，请重新发起导出",
                      "The app restarted while choosing a destination. Start the export again."));
              return;
            }
            pending.exportPrepared = false;
            if (!jobs.export(pending.selection, uri, pending.filename, pending.rescue))
              status.setText(
                  t(
                      "已有任务或无法创建私有作业，请检查状态。",
                      "A task is already running or private storage is unavailable."));
            pending.clear();
          });
  private final ActivityResultLauncher<Uri> projectPicker =
      registerForActivityResult(
          new SystemDocumentPicker.TreeContract(),
          uri -> {
            if (uri == null) return;
            try {
              getContentResolver()
                  .takePersistableUriPermission(
                      uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
              projects.add(
                  new SafBackupSource(
                      this,
                      uri,
                      "project-" + NativeDataLocations.hash(uri.toString()),
                      "projects",
                      ""));
              if (project != null)
                project.setText(
                    com.deepseekharness.app.util.UiText.format("已选择项目目录：%s", projects.size()));
            } catch (Exception error) {
              status.setText(t("无法读取所选目录，请重新授权。", "Cannot read the folder. Grant access again."));
            }
          });
  private final ActivityResultLauncher<String[]> restorePicker =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri != null) restorePassword(uri);
          });

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    pending = new ViewModelProvider(this).get(Pending.class);
    projects = pending.projects;
    jobs = NativeBackupJobs.get(this);
    String mode = getIntent().getStringExtra("data_mode");
    boolean exportOnly = "export".equals(mode),
        restoreOnly = "restore".equals(mode),
        backupMode = "backup".equals(mode);
    String title =
        backupMode
            ? t("备份与恢复", "Backup and restore")
            : exportOnly
                ? t("自定义导出", "Custom export")
                : restoreOnly
                    ? t("从备份恢复", "Restore a backup")
                    : t("应用数据与救援", "Application data and recovery");
    page =
        new CardPage(
            this,
            title,
            backupMode
                ? t("导出、导入和自动备份集中在这里。", "Export, import and automatic backups in one place.")
                : exportOnly
                    ? t(
                        "选择范围和项目后，导出无需密码的 tar.gz 备份。",
                        "Choose data and projects, then export a tar.gz backup without a password.")
                    : restoreOnly
                        ? t(
                            "先验证备份内容，再确认恢复范围。",
                            "Verify backup contents before confirming what to restore.")
                        : t(
                            "运行环境不可用时，也能检查并保护可读取的数据。",
                            "Inspect and protect readable data even when the runtime is unavailable."));
    UiNavigation.addHeader(this, page.root, title, this::finish);

    // 恢复向导：恢复页常驻；其他页在选中备份后出现，与原弹窗流程共用同一组作业。
    stepper = new LinearLayout(this);
    stepper.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams stepperParams = new LinearLayout.LayoutParams(-1, -2);
    stepperParams.bottomMargin = dp(14);
    wizardCard = page.card();
    wizardCard.setVisibility(View.GONE);
    wizard = page.column();
    wizardCard.addView(wizard);
    page.content.addView(stepper, page.content.indexOfChild(wizardCard), stepperParams);
    stepper.setVisibility(View.GONE);
    alwaysWizard = restoreOnly;

    statusCard = page.card();
    status = page.text("", 13, R.color.shell_text_secondary);
    status.setTextIsSelectable(true);
    status.setLineSpacing(dp(3), 1);
    statusCard.addView(status);
    // 结果卡拆成三段：结论 / 计数与字节 / 错误（错误默认 1 行 + ⓘ，⑬）。其余提示仍走 status。
    resultConclusion = page.text("", 13, R.color.shell_text);
    resultConclusion.setTypeface(null, android.graphics.Typeface.BOLD);
    resultConclusion.setLineSpacing(dp(3), 1);
    statusCard.addView(resultConclusion);
    resultMeta = page.text("", 12, R.color.shell_text_muted);
    statusCard.addView(resultMeta);
    resultError = page.text("", 12, R.color.shell_bad);
    resultError.setLineSpacing(dp(2), 1);
    statusCard.addView(resultError);
    android.text.TextWatcher statusVisibility =
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {}

          public void afterTextChanged(android.text.Editable s) {
            refreshStatusCardVisibility();
          }
        };
    for (TextView field : new TextView[] {status, resultConclusion, resultMeta, resultError}) {
      field.addTextChangedListener(statusVisibility);
      field.setVisibility(View.GONE);
    }
    statusCard.setVisibility(View.GONE);

    if (exportOnly) exportPage();
    else if (!restoreOnly) actionsPage(backupMode);
    cancelOperation =
        page.button(
            page.footer, t("取消当前作业", "Cancel current operation"), false, () -> jobs.cancel());
    cancelOperation.setVisibility(View.GONE);
    setContentView(page.root);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    renderWizard();

    jobs.changes().observe(this, this::render);
    pending.preview.observe(
        this,
        value -> {
          if (value != null) showScope(value);
        });
    pending.previewError.observe(
        this,
        value -> {
          if (value != null && !value.isEmpty()) status.setText(errorText(value));
        });
    if (saved == null
        && (getIntent().hasExtra("auto_restore_id") || getIntent().hasExtra("auto_export_id")))
      page.root.post(this::automaticCopy);
    if (saved == null && getIntent().hasExtra("retained_key")) page.root.post(this::retainedAction);
    if (saved == null && MODE_RESCUE.equals(mode))
      page.root.post(
          () -> {
            if (!isFinishing() && !isDestroyed()) chooseExport(true, null);
          });
    if (saved == null && getIntent().hasExtra("restore_uri")) {
      Uri input = Uri.parse(getIntent().getStringExtra("restore_uri"));
      if ("content".equals(input.getScheme()))
        new android.os.Handler(android.os.Looper.getMainLooper())
            .post(() -> restorePassword(input));
    }
  }

  // ===================== 页面骨架 =====================

  /** 自定义导出：范围（单选）→ 项目目录 → 敏感信息 → 选择位置。 */
  private void exportPage() {
    page.label(t("选择数据范围", "Choose data scope"));
    scopeCard();
    page.label(t("导出选项", "Export options"));
    optionsCard();
    page.button(
        page.footer,
        t("选择位置并导出", "Choose a location and export"),
        true,
        () -> {
          if (exportReady()) chooseExport(false, null);
        });
    page.button(
        page.footer, t("查看数据范围与位置", "Review data scope and locations"), false, this::locations);
  }

  /** 救援与维护工具（默认）及「备份与恢复」：分组入口，操作仍走原确认弹窗。 */
  private void actionsPage(boolean backupMode) {
    LinearLayout actions = page.card();
    page.entry(
        actions,
        t("导出应用数据", "Export application data"),
        t(
            "会先停止任务并建立快照",
            "Web, terminals and write tasks stop before the snapshot. The tar.gz backup needs no password."),
        R.drawable.ic_ui_export,
        () -> {
          if (exportReady()) chooseExport(false, null);
        });
    if (!backupMode)
      page.entry(
          actions,
          t("只读救援导出", "Read-only rescue export"),
          t(
              "救援不停止写入，结果可能不完整；不能据此删除原件",
              "Rescue does not stop current writers. The result is best-effort or partial and cannot justify deleting originals. The tar.gz export needs no password."),
          R.drawable.ic_recovery_shield,
          () -> {
            if (exportReady()) chooseExport(true, null);
          });
    page.entry(
        actions,
        t("导入备份", "Import backup"),
        t("先验证备份内容，再确认恢复范围。", "Verify backup contents before confirming what to restore."),
        R.drawable.ic_ui_import,
        this::pickRestore);
    if (!backupMode)
      page.entry(
          actions,
          t("重新导出已验证副本", "Export a verified copy again"),
          "",
          R.drawable.ic_recovery_document,
          this::verifiedCopies);
    else
      page.entry(
          actions,
          t("自动备份与记录", "Automatic backups and history"),
          "",
          R.drawable.ic_recovery_clock,
          () -> startActivity(new android.content.Intent(this, AutomaticBackupActivity.class)));

    page.label(t("导出选项", "Export options"));
    scopeCard();
    optionsCard();
    if (backupMode) return;

    page.label(t("保留数据", "Retained data"));
    LinearLayout retained = page.card();
    page.entry(
        retained,
        t("保留副本与旧树", "Retained copies and old trees"),
        t(
            "查看保留副本与旧环境",
            "Browse retained copies, old environments, and original plugins. Inspection and restoration are separate actions."),
        R.drawable.ic_recovery_database,
        () -> startActivity(new android.content.Intent(this, RetainedDataActivity.class)));

    page.label(t("恢复与维护", "Recovery and maintenance"));
    LinearLayout maintenance = page.card();
    page.entry(
        maintenance,
        t("恢复中断的数据提交", "Recover interrupted data commit"),
        t(
            "先停止写任务，再从原数据恢复一致状态。",
            "Stop writers first, then restore consistency from the host journal. The old Python and backup password are not required. Originals are retained."),
        R.drawable.ic_recovery_refresh,
        this::recoverPending);
    page.entry(
        maintenance,
        t("选择已有数据目录", "Choose existing data directory"),
        "",
        R.drawable.ic_recovery_database,
        this::chooseDataHome);
    page.entry(
        maintenance,
        t("回退兼容运行时", "Roll back compatible runtime"),
        "",
        R.drawable.ic_recovery_wrench,
        () -> RuntimeRecoveryUi.show(this));
  }

  private void recoverPending() {
    new DshaDialogBuilder(this, true)
        .setTitle(t("恢复中断的数据提交", "Recover interrupted data commit"))
        .setMessage(
            t(
                "先停止写任务，再从原数据恢复一致状态。",
                "Stop writers first, then restore consistency from the host journal. The old Python and backup password are not required. Originals are retained."))
        .setPositiveButton(
            t("继续", "Continue"),
            (d, w) -> {
              if (!jobs.recoverPending())
                status.setText(
                    t(
                        "没有可自动处理的提交，或需要先检查损坏/冲突的日志。",
                        "No automatically recoverable commit, or damaged/conflicting records need review."));
            })
        .setNegativeButton(t("取消", "Cancel"), null)
        .show();
  }

  private void pickRestore() {
    RecentsPrivacyGuard.launch(
        restorePicker,
        new String[] {
          com.deepseekharness.app.util.BackupFileNames.MIME_TYPE,
          "application/octet-stream",
          "application/gzip",
          "*/*"
        });
  }

  /** 「所选项目文件」范围必须先选目录，否则导出为空。 */
  private boolean exportReady() {
    if (pending.scopeIndex == 4 && projects.isEmpty()) {
      status.setText(t("请先选择项目目录", "Choose a project folder first"));
      return false;
    }
    return true;
  }

  private static final String[][] SCOPES = {
    {
      "应用数据",
      "Application data",
      "对话、附件、配置和用户插件，不含 Ubuntu",
      "Conversations, attachments, settings and user plugins, without Ubuntu"
    },
    {
      "对话与附件",
      "Conversations and attachments",
      "只导出会话记录和附件",
      "Only conversation records and attachments"
    },
    {"设置", "Settings", "profile 设置与工作目录配置", "Profile settings and workspace configuration"},
    {
      "插件",
      "Plugins",
      "用户插件；系统插件按当前 APK 重建",
      "User plugins; system plugins are rebuilt from this APK"
    },
    {"所选项目文件", "Selected project files", "只导出下方选择的项目目录", "Only the project folders chosen below"}
  };

  private void scopeCard() {
    LinearLayout card = page.card();
    scopeGroup(card);
  }

  private LinearLayout scopeGroup(LinearLayout parent) {
    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    java.util.List<View> cards = new java.util.ArrayList<>();
    for (int i = 0; i < SCOPES.length; i++) {
      SelectableCard.Model model = new SelectableCard.Model();
      model.title = t(SCOPES[i][0], SCOPES[i][1]);
      model.subtitle = t(SCOPES[i][2], SCOPES[i][3]);
      model.selected = i == pending.scopeIndex;
      final int index = i;
      View card =
          SelectableCard.single(
              this,
              model,
              () -> {
                pending.scopeIndex = index;
                for (int k = 0; k < cards.size(); k++)
                  SelectableCard.setSelected(cards.get(k), k == index);
              });
      cards.add(card);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
      if (i > 0) params.topMargin = dp(6);
      list.addView(card, params);
    }
    parent.addView(list);
    return list;
  }

  private CharSequence twoLine(String title, String subtitle) {
    android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(title);
    int start = text.length();
    text.append('\n').append(subtitle);
    text.setSpan(
        new android.text.style.RelativeSizeSpan(0.86f),
        start,
        text.length(),
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    text.setSpan(
        new android.text.style.ForegroundColorSpan(getColor(R.color.shell_text_muted)),
        start,
        text.length(),
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return text;
  }

  /** 项目目录与原生 API Key：导出页和工具页共用。 */
  private void optionsCard() {
    LinearLayout card = page.card();
    project =
        page.text(
            projects.isEmpty()
                ? t(
                    "项目文件需单独选目录",
                    "Choose project folders explicitly; the app does not scan the whole device.")
                : com.deepseekharness.app.util.UiText.format("已选择项目目录：%s", projects.size()),
            13,
            R.color.shell_text_secondary);
    project.setLineSpacing(dp(3), 1);
    project.setPadding(0, dp(6), 0, 0);
    card.addView(project);
    page.button(
        card,
        t("选择项目目录", "Choose project folder"),
        false,
        () -> RecentsPrivacyGuard.launch(projectPicker, null));
    CheckBox key = new CheckBox(this);
    key.setText(t("包含原生 API Key（默认不包含）", "Include native API key (excluded by default)"));
    key.setTextColor(getColor(R.color.shell_text));
    key.setMinimumHeight(dp(48));
    key.setChecked(pending.includeKey);
    LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(-1, -2);
    keyParams.topMargin = dp(8);
    card.addView(key, keyParams);
    TextView warning =
        page.text(
            t(
                "导出文件不加密，拿到文件者可读取 API Key",
                "The export is not encrypted; anyone with the file can read the API key."),
            12,
            R.color.shell_warn);
    warning.setPadding(0, dp(4), 0, dp(6));
    warning.setVisibility(pending.includeKey ? View.VISIBLE : View.GONE);
    card.addView(warning);
    key.setOnCheckedChangeListener(
        (button, checked) -> {
          pending.includeKey = checked;
          warning.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
  }

  // ===================== 恢复向导 =====================

  private static final int STEP_PICK = 0, STEP_OPTIONS = 1, STEP_CHECK = 2, STEP_CONFIRM = 3;
  private static final int STEP_DONE = 4;

  private void renderStepper() {
    stepper.removeAllViews();
    String[] names = {
      t("选择文件", "Choose file"),
      t("范围与选项", "Scope and options"),
      t("预检核验", "Inspection"),
      t("确认恢复", "Confirm restore")
    };
    int current = Math.min(pending.restoreStep, STEP_CONFIRM);
    boolean finished = pending.restoreStep == STEP_DONE;
    for (int i = 0; i < names.length; i++) {
      LinearLayout cell = page.column();
      View bar = new View(this);
      bar.setBackgroundResource(R.drawable.bg_data_status_tile);
      bar.setBackgroundTintList(
          android.content.res.ColorStateList.valueOf(
              getColor(i <= current || finished ? R.color.shell_accent : R.color.shell_raised)));
      cell.addView(bar, new LinearLayout.LayoutParams(-1, dp(4)));
      TextView label =
          page.text(
              names[i],
              12,
              i == current && !finished ? R.color.shell_accent : R.color.shell_text_muted);
      if (i == current && !finished) label.setTypeface(null, android.graphics.Typeface.BOLD);
      label.setPadding(0, dp(6), 0, 0);
      label.setMaxLines(2);
      cell.addView(label);
      LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, -2, 1);
      if (i > 0) cellParams.leftMargin = dp(6);
      stepper.addView(cell, cellParams);
    }
    stepper.setContentDescription(
        com.deepseekharness.app.util.UiText.format(
            "第 %s 步，共 %s 步：%s",
            String.valueOf(current + 1), String.valueOf(names.length), names[current]));
  }

  private void renderWizard() {
    boolean visible = alwaysWizard || pending.restoreStep > STEP_PICK;
    wizardCard.setVisibility(visible ? View.VISIBLE : View.GONE);
    stepper.setVisibility(visible ? View.VISIBLE : View.GONE);
    if (!visible) {
      renderedStep = -1;
      return;
    }
    renderStepper();
    int step = Math.max(0, Math.min(pending.restoreStep, STEP_DONE));
    ensureStepViews();
    // 回到「选择文件」说明上一次流程已结束：清掉后续步骤缓存，避免带回旧选项与密码。
    if (step == STEP_PICK) invalidateStepsAfterPick();
    // 选择/选项步骤只建一次（返回上一步保留已填内容）；进度/结果只更新各自容器，
    // 不再整块 removeAllViews，滚动与焦点也不再被重置（审计 E-01）。
    boolean rebuild = step >= STEP_CHECK || !stepBuilt[step];
    if (!rebuild) {
      showStep(step);
      return;
    }
    renderedStep = step;
    LinearLayout target = stepViews[step];
    target.removeAllViews();
    stepTarget = target;
    switch (step) {
      case STEP_OPTIONS -> optionsStep();
      case STEP_CHECK -> progressStep(t("正在核验备份", "Inspecting the backup"));
      case STEP_CONFIRM -> confirmStep();
      case STEP_DONE -> resultStep();
      default -> pickStep();
    }
    stepTarget = null;
    stepBuilt[step] = true;
    showStep(step);
  }

  /** 5 个步骤容器一次加入 wizard，之后只切可见性。 */
  private void ensureStepViews() {
    if (stepViews[0] != null) return;
    for (int i = 0; i < stepViews.length; i++) {
      LinearLayout container = page.column();
      container.setVisibility(View.GONE);
      wizard.addView(container);
      stepViews[i] = container;
    }
  }

  private void showStep(int step) {
    for (int i = 0; i < stepViews.length; i++)
      stepViews[i].setVisibility(i == step ? View.VISIBLE : View.GONE);
  }

  /** 回到选择文件后，选项/进度/结果下次重新构建；已填内容不跨流程保留。 */
  private void invalidateStepsAfterPick() {
    restoreProgress = null;
    restoreOutcome = null;
    for (int i = STEP_OPTIONS; i < stepBuilt.length; i++) {
      if (!stepBuilt[i]) continue;
      stepBuilt[i] = false;
      if (stepViews[i] != null) stepViews[i].removeAllViews();
    }
  }

  private void pickStep() {
    TextView hint =
        page.text(
            t(
                "无需密码；验证后才覆盖数据",
                "No password entry is required. Contents and actual scope are verified privately before you confirm any change."),
            13,
            R.color.shell_text_secondary);
    hint.setLineSpacing(dp(3), 1);
    hint.setPadding(0, dp(6), 0, 0);
    stepTarget.addView(hint);
    page.button(stepTarget, t("选择备份文件", "Choose a backup file"), true, this::pickRestore);
    page.button(
        stepTarget,
        t("查看本机自动备份", "View local automatic backups"),
        false,
        () -> startActivity(new android.content.Intent(this, AutomaticBackupActivity.class)));
  }

  private void optionsStep() {
    page.kv(stepTarget, t("备份来源", "Backup source"), pending.restoreName);
    TextView description =
        page.text(
            pending.restoreNeedsOriginal
                ? t(
                    "旧备份需要原密码；确认前不会覆盖数据",
                    "This older backup was encrypted and needs its original password to be read. Verification uses private staging and does not overwrite current data before confirmation.")
                : t(
                    "无需密码；验证后才覆盖数据",
                    "No password entry is required. Contents and actual scope are verified privately before you confirm any change."),
            13,
            R.color.shell_text_secondary);
    description.setLineSpacing(dp(3), 1);
    description.setPadding(0, dp(4), 0, dp(8));
    stepTarget.addView(description);
    password = null;
    if (pending.restoreNeedsOriginal) {
      password = new EditText(this);
      password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      password.setSaveEnabled(false);
      password.setHint(t("旧备份原密码", "Original password of the old backup"));
      password.setBackgroundResource(R.drawable.bg_shell_input);
      password.setTextColor(getColor(R.color.shell_text));
      password.setMinHeight(dp(48));
      password.setPadding(dp(14), dp(10), dp(14), dp(10));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.bottomMargin = dp(10);
      stepTarget.addView(password, p);
    }
    TextView scopeLabel = page.text(t("恢复范围", "Restore scope"), 12, R.color.shell_text_secondary);
    scopeLabel.setTypeface(null, android.graphics.Typeface.BOLD);
    scopeLabel.setPadding(0, dp(6), 0, dp(2));
    stepTarget.addView(scopeLabel);
    scopeGroup(stepTarget);
    includeRestoreKey = new CheckBox(this);
    includeRestoreKey.setText(
        t("恢复原生 API Key", "Restore native API key (keep local credentials by default)"));
    includeRestoreKey.setTextColor(getColor(R.color.shell_text));
    includeRestoreKey.setMinimumHeight(dp(48));
    stepTarget.addView(includeRestoreKey);
    TextView destinationLabel =
        page.text(
            t("项目恢复位置（创建新的独立目录）", "Project destination (creates a new separate directory)"),
            12,
            R.color.shell_text_secondary);
    destinationLabel.setTypeface(null, android.graphics.Typeface.BOLD);
    destinationLabel.setPadding(0, dp(8), 0, dp(2));
    stepTarget.addView(destinationLabel);
    projectDestination = new RadioGroup(this);
    String[] places = {
      t("应用私有项目区", "App-private projects"), t("容器主目录 /root", "Container home /root")
    };
    for (int i = 0; i < places.length; i++) {
      RadioButton option = new RadioButton(this);
      option.setId(View.generateViewId());
      option.setText(places[i]);
      option.setTextColor(getColor(R.color.shell_text));
      option.setMinHeight(dp(44));
      projectDestination.addView(option);
      if (i == 0) option.setChecked(true);
    }
    stepTarget.addView(projectDestination);
    page.button(stepTarget, t("开始预检", "Inspect"), true, this::startInspection);
    page.button(
        stepTarget,
        t("重新选择", "Choose again"),
        false,
        () -> {
          clearRestore();
          if (alwaysWizard) renderWizard();
          else {
            renderWizard();
            pickRestore();
          }
        });
  }

  private void startInspection() {
    if (jobs.state().busy) {
      status.setText(t("已有任务，完成后再试。", "Another task is in progress."));
      return;
    }
    if (password != null && password.length() == 0) {
      password.setError(t("请输入旧副本原来的密码", "Enter the original password of the old copy"));
      return;
    }
    String location =
        projectDestination.indexOfChild(
                    projectDestination.findViewById(projectDestination.getCheckedRadioButtonId()))
                == 1
            ? "GUEST_HOME"
            : "PRIVATE";
    Set<String> selected = Set.of(selection().scope);
    boolean includeKey = includeRestoreKey.isChecked();
    String copy = pending.restoreCopy;
    if (pending.restoreAutomatic) {
      new Thread(
              () -> {
                char[] secret = null;
                try {
                  secret = AutomaticBackups.password(this);
                  boolean started =
                      jobs.prepareRestoreCopy(copy, secret, selected, includeKey, location);
                  String id = jobs.state().id;
                  runOnUiThread(
                      () -> {
                        if (started) restoreStarted(id);
                        else status.setText(t("已有任务，完成后再试。", "Another task is in progress."));
                      });
                } catch (Exception error) {
                  runOnUiThread(() -> status.setText(errorText(BackupErrorCode.from(error))));
                } finally {
                  if (secret != null) Arrays.fill(secret, '\0');
                }
              },
              "automatic-copy-restore")
          .start();
      return;
    }
    char[] chars = password == null ? null : password.getText().toString().toCharArray();
    if (password != null) password.getText().clear();
    try {
      Uri uri = pending.restoreUri == null ? null : Uri.parse(pending.restoreUri);
      boolean started =
          copy == null
              ? jobs.prepareRestore(uri, chars, selected, includeKey, location)
              : jobs.prepareRestoreCopy(copy, chars, selected, includeKey, location);
      if (started) restoreStarted(jobs.state().id);
      else status.setText(t("已有任务，完成后再试。", "Another task is in progress."));
    } finally {
      if (chars != null) Arrays.fill(chars, '\0');
    }
  }

  /** 作业已切到 busy：记下本向导拥有的作业号，之后只按它推进步骤。 */
  private void restoreStarted(String id) {
    pending.restoreJob = id;
    restoreProgress = null;
    restoreOutcome = null;
    NativeBackupJobs.State now = jobs.state();
    // 作业可能在回到主线程前就已结束（例如立即失败）；此时直接显示结果，不停在进度步骤。
    pending.restoreStep =
        now.id.equals(id) && !now.busy && !now.stage.equals("PREVIEW")
            ? STEP_DONE
            : now.stage.equals("PREVIEW") ? STEP_CONFIRM : STEP_CHECK;
    renderWizard();
    reveal();
  }

  /**
   * E1：恢复 / 备份向导的进度。总量来自预检（PREVIEW 时的真实条目与字节），之后按后端累计的
   * 已复制量算百分比；预检之前或导出（无总量）保持不确定态，不拿时间猜进度。取消与完成态由
   * {@link DeterminateProgress} 统一收口，结束时立即停。
   */
  private void progressStep(String heading) {
    NativeBackupJobs.State value = jobs.state();
    if (restoreProgress == null) {
      restoreProgress = new DeterminateProgress(this);
      restoreProgress.setCancelAction(t("取消当前作业", "Cancel current operation"), () -> jobs.cancel());
    }
    DeterminateProgress progress = restoreProgress;
    if (progress.getParent() instanceof LinearLayout parent) parent.removeView(progress);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(6);
    stepTarget.addView(progress, params);
    progress.setLabel(heading);
    progress.setDetail(
        com.deepseekharness.app.util.UiText.format(
            "%s · %s 项", stage(value.stage), String.valueOf(value.entries)));
    int percent =
        com.deepseekharness.app.util.ProgressFraction.restorePercent(
            value.entries, value.bytes, pending.restoreTotalEntries, pending.restoreTotalBytes);
    if (percent >= 0) progress.setPercent(percent);
    else progress.setIndeterminate();
  }

  private void confirmStep() {
    NativeBackupJobs.State value = jobs.state();
    if (!value.stage.equals("PREVIEW")) {
      progressStep(t("正在恢复数据", "Restoring data"));
      return;
    }
    String info = previewText(value.id);
    TextView title = page.text(t("确认恢复范围", "Confirm restore scope"), 16, R.color.shell_text);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    title.setPadding(0, dp(6), 0, dp(8));
    stepTarget.addView(title);
    TextView body =
        page.text(
            info == null
                ? t(
                    "预检记录不可读取，请重新选择备份。",
                    "The inspection record is unavailable. Select the backup again.")
                : info,
            13,
            R.color.shell_text_secondary);
    body.setLineSpacing(dp(4), 1);
    body.setTextIsSelectable(true);
    stepTarget.addView(body);
    Button confirm = page.button(stepTarget, t("确认并恢复", "Confirm and restore"), true, () -> {});
    Button cancel = page.button(stepTarget, t("取消", "Cancel"), false, () -> {});
    confirm.setEnabled(info != null);
    confirm.setOnClickListener(
        v -> {
          confirm.setEnabled(false);
          cancel.setEnabled(false);
          jobs.decide(value.id, true);
        });
    cancel.setOnClickListener(
        v -> {
          confirm.setEnabled(false);
          cancel.setEnabled(false);
          jobs.decide(value.id, false);
        });
  }

  private void resultStep() {
    NativeBackupJobs.State value = jobs.state();
    boolean mine = value.id.equals(pending.restoreJob);
    boolean cancelled = mine && value.stage.equals("CANCELLED");
    boolean ok = mine && value.error.isEmpty() && !cancelled;
    if (mine) {
      // E1 完成态：真实百分比停在结果上，成功 / 失败 / 取消都收起取消按钮并停止动画。
      if (restoreOutcome == null) {
        restoreOutcome = new DeterminateProgress(this);
        int percent =
            com.deepseekharness.app.util.ProgressFraction.restorePercent(
                value.entries, value.bytes, pending.restoreTotalEntries, pending.restoreTotalBytes);
        if (percent >= 0) restoreOutcome.setPercent(percent);
        if (cancelled) restoreOutcome.cancelled(t("恢复已取消", "Restore cancelled"));
        else if (ok) restoreOutcome.complete(t("恢复完成", "Restore finished"));
        else restoreOutcome.fail(t("恢复未完成", "Restore did not finish"));
      }
      DeterminateProgress outcome = restoreOutcome;
      if (outcome.getParent() instanceof LinearLayout parent) parent.removeView(outcome);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
      params.topMargin = dp(6);
      params.bottomMargin = dp(8);
      stepTarget.addView(outcome, params);
    } else {
      TextView title =
          page.text(
              ok ? t("恢复完成", "Restore finished") : t("恢复未完成", "Restore did not finish"),
              16,
              R.color.shell_text);
      title.setTypeface(null, android.graphics.Typeface.BOLD);
      title.setPadding(0, dp(6), 0, dp(8));
      stepTarget.addView(title);
    }
    if (mine) {
      TextView body =
          page.text(
              resultText(value) + (value.error.isEmpty() ? "" : "\n" + errorText(value.error)),
              13,
              R.color.shell_text_secondary);
      body.setLineSpacing(dp(4), 1);
      body.setTextIsSelectable(true);
      stepTarget.addView(body);
      if (value.result.contains("QUARANTINED") || value.result.contains("SETTINGS_REVIEW"))
        page.button(
            wizard,
            t("保留副本与旧树", "Retained copies and old trees"),
            false,
            () -> startActivity(new android.content.Intent(this, RetainedDataActivity.class)));
    }
    page.button(
        stepTarget,
        t("完成", "Done"),
        true,
        () -> {
          clearRestore();
          renderWizard();
        });
  }

  private void clearRestore() {
    pending.restoreStep = STEP_PICK;
    pending.restoreJob = "";
    pending.restoreUri = null;
    pending.restoreCopy = null;
    pending.restoreName = "";
    pending.restoreNeedsOriginal = false;
    pending.restoreAutomatic = false;
    renderedStep = -1;
    invalidateStepsAfterPick();
    if (password != null) password.getText().clear();
    password = null;
  }

  private void reveal() {
    if (wizardCard.getVisibility() != View.VISIBLE) return;
    View host = (View) page.content.getParent();
    if (host instanceof ScrollView)
      host.post(() -> ((ScrollView) host).smoothScrollTo(0, Math.max(0, stepper.getTop() - dp(8))));
  }

  private void automaticCopy() {
    boolean restore = getIntent().hasExtra("auto_restore_id");
    String id = getIntent().getStringExtra(restore ? "auto_restore_id" : "auto_export_id");
    new Thread(
            () -> {
              char[] secret = null;
              try {
                var copy =
                    VerifiedBackupCopy.inspect(
                        new AndroidBackupFileSystem(),
                        new java.io.File(
                            getFilesDir().getCanonicalFile(), "host-backup-operations"),
                        id);
                if (!Boolean.TRUE.equals(copy.metadata.get("automatic")))
                  throw new java.io.IOException("NOT_AUTOMATIC_COPY");
                if (restore) {
                  secret = AutomaticBackups.password(this);
                  if (!jobs.prepareRestoreCopy(id, secret, Set.of("application"), false, "PRIVATE"))
                    throw new java.io.IOException("RESTORE_BUSY");
                  String job = jobs.state().id;
                  runOnUiThread(
                      () -> {
                        if (!isFinishing() && !isDestroyed()) restoreStarted(job);
                      });
                } else
                  runOnUiThread(
                      () -> {
                        if (!isFinishing() && !isDestroyed()) chooseReexport(copy);
                      });
              } catch (Exception error) {
                runOnUiThread(() -> status.setText(errorText(BackupErrorCode.from(error))));
              } finally {
                if (secret != null) Arrays.fill(secret, '\0');
              }
            },
            "automatic-backup-copy")
        .start();
  }

  private void chooseDataHome() {
    String[] names = {
      t("原容器数据目录", "Original container data directory"),
      t("宿主持久数据目录", "Persistent host data directory")
    };
    new DshaDialogBuilder(this)
        .setTitle(t("选择要使用的数据目录", "Choose the data directory to use"))
        .setItems(
            names,
            (d, index) -> {
              new DshaDialogBuilder(this)
                  .setTitle(names[index])
                  .setMessage(
                      t(
                          "会先停止 Web 和终端，再选择已有目录。两份数据都保留，不合并或清空。所选目录必须存在且可读取。",
                          "Stop Web and terminals, then select an existing directory. Both data sets remain; neither is merged or cleared. The chosen directory must exist and be readable."))
                  .setPositiveButton(
                      t("继续", "Continue"),
                      (dialog, which) ->
                          com.deepseekharness.app.core.BackupTask.get(this)
                              .selectDataHome(
                                  index == 0
                                      ? UserDataLayout.Home.LEGACY
                                      : UserDataLayout.Home.STABLE))
                  .setNegativeButton(t("取消", "Cancel"), null)
                  .show();
            })
        .setNegativeButton(t("取消", "Cancel"), null)
        .show();
  }

  private void verifiedCopies() {
    status.setText(t("正在读取私有副本记录…", "Reading private copy records…"));
    new Thread(
            () -> {
              try {
                NativeBackupJobs.Copies found = jobs.verifiedCopies();
                List<VerifiedBackupCopy> copies = found.valid;
                runOnUiThread(
                    () -> {
                      if (isFinishing() || isDestroyed()) return;
                      String unavailable =
                          found.unreadable.isEmpty()
                              ? ""
                              : found.unreadable.size()
                                  + t(
                                      " 份记录暂不可用，原件已保留；其余副本仍可选择。",
                                      " records are unavailable and retained; other copies can still be selected.");
                      if (copies.isEmpty()) {
                        status.setText(UiText.format("还没有可重新导出的私有验证副本。\n%s", unavailable));
                        return;
                      }
                      if (!unavailable.isEmpty()) status.setText(unavailable);
                      String[] labels = new String[copies.size()];
                      for (int i = 0; i < labels.length; i++) {
                        var copy = copies.get(i);
                        labels[i] =
                            new java.text.SimpleDateFormat(
                                        "yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                    .format(new java.util.Date(copy.created))
                                + " · "
                                + com.deepseekharness.app.util.Fmt.bytes(copy.bytes)
                                + " · "
                                + copy.id.substring(0, 8);
                      }
                      new DshaDialogBuilder(this)
                          .setTitle(t("选择已验证的备份副本", "Choose a verified backup copy"))
                          .setItems(
                              labels,
                              (d, index) -> {
                                new DshaDialogBuilder(this)
                                    .setTitle(t("重新导出副本", "Export the copy again"))
                                    .setMessage(
                                        t(
                                            "将核对私有副本并导出无需密码的 tar.gz。原副本保留，不重新读取工作目录，也不覆盖已有备份。",
                                            "Verify the private copy and export a tar.gz without a password. The original is retained, the workspace is not read again, and existing backups are preserved."))
                                    .setPositiveButton(
                                        t("选择保存位置", "Choose destination"),
                                        (dialog, which) -> chooseReexport(copies.get(index)))
                                    .setNegativeButton(t("取消", "Cancel"), null)
                                    .show();
                              })
                          .setNegativeButton(t("取消", "Cancel"), null)
                          .show();
                    });
              } catch (Exception error) {
                runOnUiThread(
                    () -> {
                      if (!isFinishing() && !isDestroyed())
                        status.setText(errorText(BackupErrorCode.from(error)));
                    });
              }
            },
            "verified-backup-records")
        .start();
  }

  private void retainedAction() {
    try {
      var fs = new AndroidBackupFileSystem();
      java.io.File files = getFilesDir().getCanonicalFile();
      var catalogue = new RetainedCatalogue(fs, files, new UserDataLayout(fs, files).current());
      var entry = catalogue.resolve(getIntent().getStringExtra("retained_key"));
      String action = getIntent().getStringExtra("retained_action");
      int index =
          List.of("application", "sessions", "settings", "plugins", "projects")
              .indexOf(entry.scope);
      if (index >= 0) pending.scopeIndex = index;
      if ("reexport".equals(action) && entry.part.equals("encrypted"))
        chooseReexport(
            VerifiedBackupCopy.inspect(
                fs, new java.io.File(files, "host-backup-operations"), entry.id));
      else if ("restore-copy".equals(action) && entry.part.equals("encrypted"))
        restorePassword(null, entry.id);
      else if ("export-tree".equals(action)) {
        NativeDataLocations.Selection selected = new NativeDataLocations.Selection();
        selected.scope = entry.scope;
        selected.retainedKey = entry.key();
        chooseExport(true, selected);
      } else if ("restore-tree".equals(action)) {
        new DshaDialogBuilder(this, true)
            .setTitle(t("预检保留原件", "Inspect retained original"))
            .setMessage(
                t(
                    "先只读扫描所选原件并建立私有验证副本，不执行其中的程序。预检完成后还需确认目标；项目原件会恢复到新的独立目录。",
                    "Read the selected original into private verified staging without running its programs. Confirm the targets after inspection. Project originals restore into a new separate directory."))
            .setPositiveButton(
                t("开始预检", "Inspect"),
                (dialog, which) -> {
                  if (jobs.prepareRestoreTree(entry.key(), Set.of(entry.scope), "PRIVATE"))
                    restoreStarted(jobs.state().id);
                  else
                    status.setText(
                        t(
                            "无法开始预检，请检查记录或当前任务。",
                            "Inspection could not start. Review the record or current operation."));
                })
            .setNegativeButton(t("取消", "Cancel"), null)
            .show();
      }
    } catch (Exception error) {
      status.setText(errorText(BackupErrorCode.from(error)));
    }
  }

  private NativeDataLocations.Selection selection() {
    NativeDataLocations.Selection selected = new NativeDataLocations.Selection();
    selected.scope =
        new String[] {"application", "sessions", "settings", "plugins", "projects"}
            [Math.max(0, Math.min(4, pending.scopeIndex))];
    selected.includeApiKey = pending.includeKey;
    selected.documentProjects.addAll(projects);
    return selected;
  }

  private void locations() {
    if (pending.previewBusy) return;
    pending.previewBusy = true;
    pending.previewControl = new BackupControl(null);
    var control = pending.previewControl;
    NativeDataLocations.Selection selected = selection();
    var owner = pending;
    android.content.Context app = getApplicationContext();
    status.setText(t("正在统计所选范围、大小与排除项…", "Reviewing selected scope, sizes and exclusions…"));
    new Thread(
            () -> {
              try {
                var locations = new NativeDataLocations(app);
                var value = locations.locate(selected, control);
                owner.preview.postValue(
                    BackupPreview.inspect(
                        value.sources, app.getFilesDir().getUsableSpace(), control));
              } catch (Exception error) {
                owner.previewError.postValue(BackupErrorCode.from(error));
              } finally {
                owner.previewBusy = false;
              }
            },
            "data-scope-preview")
        .start();
  }

  private void showScope(BackupPreview.Report value) {
    StringBuilder text = new StringBuilder();
    text.append(value.files)
        .append(t(" 个文件 · 已知大小 ", " files · known size "))
        .append(com.deepseekharness.app.util.Fmt.bytes(value.bytes));
    text.append(t("\n可用空间：", "\nFree space: "))
        .append(com.deepseekharness.app.util.Fmt.bytes(value.freeBytes));
    if (value.unknown > 0)
      text.append(t("\n尺寸未知的文件：", "\nFiles with unknown size: ")).append(value.unknown);
    if (value.unreadable > 0)
      text.append(t("\n缺失或不可读取项：", "\nMissing or unreadable entries: ")).append(value.unreadable);
    text.append(
        t(
            "\n这是读取时的预估；实际导出仍会检查变化和空间。\n",
            "\nThese are read-time estimates; export checks changes and space again.\n"));
    for (var root : value.roots) {
      text.append('\n')
          .append(root.id.equals("native-settings") ? t("原生设置", "Native settings") : root.name)
          .append(" · ")
          .append(scopeText(root.scope));
      text.append('\n')
          .append(root.files)
          .append(t(" 项 · ", " items · "))
          .append(com.deepseekharness.app.util.Fmt.bytes(root.bytes));
      if (!root.location.isEmpty()) text.append('\n').append(root.location);
      text.append('\n');
    }
    if (!value.exclusions.isEmpty()) {
      text.append(t("\n排除项：", "\nExclusions: "));
      for (var entry : value.exclusions.entrySet())
        text.append('\n').append(entry.getKey()).append(" · ").append(entry.getValue());
    }
    new DshaDialogBuilder(this)
        .setTitle(t("数据范围与大小", "Data scope and size"))
        .setMessage(text.toString())
        .setPositiveButton(t("关闭", "Close"), null)
        .show();
  }

  private String scopeText(String scope) {
    switch (scope) {
      case "application":
        return t("应用数据", "Application data");
      case "sessions":
        return t("对话与附件", "Conversations and attachments");
      case "settings":
        return t("设置", "Settings");
      case "plugins":
        return t("插件", "Plugins");
      case "projects":
        return t("项目文件", "Project files");
      default:
        return t("待确认范围", "Scope needs review");
    }
  }

  private void chooseExport(boolean rescue, NativeDataLocations.Selection retained) {
    if (jobs.state().busy) return;
    String message =
        rescue
            ? t(
                "救援不停止写入，结果可能不完整；不能据此删除原件",
                "Rescue does not stop current writers. The result is best-effort or partial and cannot justify deleting originals. The tar.gz export needs no password.")
            : t(
                "会先停止任务并建立快照",
                "Web, terminals and write tasks stop before the snapshot. The tar.gz backup needs no password.");
    new DshaDialogBuilder(this, true)
        .setTitle(rescue ? t("救援导出", "Rescue export") : t("数据导出", "Data export"))
        .setMessage(message)
        .setPositiveButton(
            t("选择保存位置", "Choose destination"),
            (dialog, which) -> {
              pending.clear();
              pending.selection = retained == null ? selection() : retained;
              pending.rescue = rescue;
              pending.filename = com.deepseekharness.app.util.BackupFileNames.portableName();
              pending.exportPrepared = true;
              RecentsPrivacyGuard.launch(destination, pending.filename);
            })
        .setNegativeButton(t("取消", "Cancel"), null)
        .show();
  }

  private void chooseReexport(VerifiedBackupCopy copy) {
    if (jobs.state().busy) return;
    boolean needsOriginal = false;
    try {
      needsOriginal =
          copy.passwordProtected(new AndroidBackupFileSystem())
              && !Boolean.TRUE.equals(copy.metadata.get("automatic"));
    } catch (java.io.IOException error) {
      status.setText(errorText(BackupErrorCode.from(error)));
      return;
    }
    if (!needsOriginal) {
      pending.clear();
      pending.reexportId = copy.id;
      pending.filename = com.deepseekharness.app.util.BackupFileNames.portableName();
      RecentsPrivacyGuard.launch(destination, pending.filename);
      return;
    }
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), 0, dp(20), 0);
    TextView description = new TextView(this);
    description.setText(
        t(
            "这是以前用密码加密的副本。原密码只用于读取旧副本；新导出的 tar.gz 无需密码，原加密副本保留。",
            "This older copy was encrypted. Its original password is only used to read it. The new tar.gz needs no password, and the encrypted original is retained."));
    content.addView(description);
    EditText original = new EditText(this);
    original.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    original.setSaveEnabled(false);
    original.setHint(t("旧副本原密码", "Original password of the old copy"));
    content.addView(original);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(content);
    var dialog =
        new DshaDialogBuilder(this)
            .setTitle(t("读取旧加密副本", "Read an older encrypted copy"))
            .setView(scroll)
            .setPositiveButton(t("选择保存位置", "Choose destination"), null)
            .setNegativeButton(t("取消", "Cancel"), null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog
                .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(
                    view -> {
                      if (original.length() < 12 || original.length() > 1024) {
                        original.setError(
                            t("请输入旧副本原来的密码", "Enter the original password of the old copy"));
                        return;
                      }
                      pending.clear();
                      pending.password = original.getText().toString().toCharArray();
                      original.getText().clear();
                      pending.reexportId = copy.id;
                      pending.filename =
                          com.deepseekharness.app.util.BackupFileNames.portableName();
                      dialog.dismiss();
                      RecentsPrivacyGuard.launch(destination, pending.filename);
                    }));
    dialog.setOnDismissListener(ignored -> original.getText().clear());
    dialog.show();
  }

  private void restorePassword(Uri uri) {
    restorePassword(uri, null);
  }

  private void restorePassword(Uri uri, String copy) {
    if (jobs.state().busy || pending.protectionBusy) return;
    pending.protectionBusy = true;
    pending.protectionControl = new BackupControl(null);
    var probe = pending.protectionControl;
    var owner = pending;
    status.setText(t("正在识别备份格式…", "Identifying backup format…"));
    new Thread(
            () -> {
              try {
                boolean protectedInput, automatic = false;
                if (copy != null) {
                  var fs = new AndroidBackupFileSystem();
                  var retained =
                      VerifiedBackupCopy.inspect(
                          fs,
                          new java.io.File(
                              getFilesDir().getCanonicalFile(), "host-backup-operations"),
                          copy);
                  protectedInput = retained.passwordProtected(fs);
                  automatic = Boolean.TRUE.equals(retained.metadata.get("automatic"));
                } else
                  try (java.io.InputStream input =
                      DocumentStreams.input(getContentResolver(), uri, probe)) {
                    protectedInput = PortableBackupEnvelope.passwordProtected(input, probe);
                  }
                boolean needsOriginal = protectedInput && !automatic, localAutomatic = automatic;
                runOnUiThread(
                    () -> {
                      if (!isFinishing() && !isDestroyed())
                        enterRestoreOptions(uri, copy, needsOriginal, localAutomatic);
                    });
              } catch (Exception error) {
                runOnUiThread(
                    () -> {
                      if (!isFinishing() && !isDestroyed())
                        status.setText(errorText(BackupErrorCode.from(error)));
                    });
              } finally {
                owner.protectionBusy = false;
                if (owner.protectionControl == probe) owner.protectionControl = null;
              }
            },
            "backup-protection-probe")
        .start();
  }

  private void enterRestoreOptions(Uri uri, String copy, boolean needsOriginal, boolean automatic) {
    if (jobs.state().busy) return;
    pending.restoreUri = uri == null ? null : uri.toString();
    pending.restoreCopy = copy;
    pending.restoreNeedsOriginal = needsOriginal;
    pending.restoreAutomatic = automatic;
    pending.restoreName =
        copy != null
            ? com.deepseekharness.app.util.UiText.format(
                "本机副本 %s", copy.substring(0, Math.min(8, copy.length())))
            : displayName(uri);
    pending.restoreStep = STEP_OPTIONS;
    renderedStep = -1;
    status.setText("");
    renderWizard();
    reveal();
  }

  private String displayName(Uri uri) {
    if (uri == null) return "";
    try (android.database.Cursor cursor =
        getContentResolver()
            .query(
                uri,
                new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
      if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
    } catch (Exception ignored) {
      // 名称只用于显示；读不到时退回 URI 末段。
    }
    String last = uri.getLastPathSegment();
    return last == null ? t("所选文件", "Selected file") : last;
  }

  /** 预检完成后的说明；读不到记录时返回 null，由调用方提示重新选择。 */
  private String previewText(String id) {
    try {
      Map<String, Object> preview = jobs.preview(id);
      String info =
          t("归档已通过完整预检。\n文件/目录：", "Archive inspection passed.\nFiles/directories: ")
              + preview.get("entries")
              + t("\n同名文件冲突：", "\nExisting-file conflicts: ")
              + preview.get("conflicts")
              + t(
                  "\n确认后将停止 Web、终端和写任务，合并所选范围并替换归档中同名文件。未提及的文件保留。插件在独立目录隔离导入，项目恢复到新的独立目录。",
                  "\nConfirmation stops Web, terminals and writers, merges selected roots and replaces matching files from the archive. Unmentioned files remain. Plugins are quarantined and projects use new folders.");
      if (Boolean.TRUE.equals(preview.get("legacyConfirmationRequired")))
        info +=
            t(
                "\n这是历史格式备份；摘要验证不代表来源身份认证。",
                "\nThis is a legacy backup; checksum validation does not authenticate its sender.");
      if ("UNENCRYPTED".equals(preview.get("sensitivePolicy")))
        info +=
            t(
                "\n这是未加密的 v5 备份；摘要能校验文件完整性，但不能提供保密或来源认证。",
                "\nThis is an unencrypted v5 backup; the digest verifies file integrity but provides no confidentiality or sender authentication.");
      if (preview.get("warnings") instanceof List
          && ((List<?>) preview.get("warnings")).contains("LINK_METADATA_RETAINED"))
        info +=
            t(
                "\n部分链接无法安全还原，不会创建；确认恢复后，链接信息会保存在应用私有恢复记录中。",
                "\nSome links cannot be restored safely and will not be created. If you confirm, their metadata will be retained in the app's private restore record.");
      if (preview.get("warnings") instanceof List && !((List<?>) preview.get("warnings")).isEmpty())
        info +=
            t(
                "\n该来源含未确认的格式或恢复提示；不承诺可用于所有历史运行时。原件继续保留。",
                "\nThis source has unconfirmed format or restore warnings. Compatibility with every historical runtime is not established. Originals remain retained.");
      info +=
          t("\n项目位置：", "\nProject destination: ")
              + ("GUEST_HOME".equals(preview.get("projectDestination"))
                  ? t("容器主目录的新目录", "A new directory in container home")
                  : t("应用私有项目区的新目录", "A new directory in app-private projects"));
      return info;
    } catch (Exception error) {
      return null;
    }
  }

  private void render(NativeBackupJobs.State value) {
    if (value == null) return;
    boolean mine = !value.id.isEmpty() && value.id.equals(pending.restoreJob);
    // 预检停在 PREVIEW 时由向导承接确认；进程重建后也认领现有预检，与原确认弹窗一致。
    if (value.stage.equals("PREVIEW")) {
      if (!mine) pending.restoreJob = value.id;
      if (value.id.equals(pending.restoreJob)) {
        pending.restoreTotalEntries = value.entries;
        pending.restoreTotalBytes = value.bytes;
      }
      pending.restoreStep = STEP_CONFIRM;
      cancelOperation.setVisibility(View.GONE);
      clearStatus();
      renderWizard();
      reveal();
      return;
    }
    if (mine && pending.restoreStep >= STEP_CHECK) {
      pending.restoreStep = value.busy ? pending.restoreStep : STEP_DONE;
      renderWizard();
      // 向导已显示进度与结果，状态卡不再重复同一段文字。
      cancelOperation.setVisibility(View.GONE);
      clearStatus();
      return;
    }
    cancelOperation.setVisibility(value.busy && !mine ? View.VISIBLE : View.GONE);
    String projection = com.deepseekharness.app.data.PortableSettings.errorCode(this);
    // 尚无作业时不显示「0 项 · 0 B」空状态卡；有投影错误仍照常提示。
    if (value.id.isEmpty() && !value.busy && value.error.isEmpty() && projection.isEmpty()) {
      clearStatus();
      return;
    }
    // 结论 / 计数 / 错误三段各自刷新（审计 E-02）：数字直接落值，文案变化才淡入；
    // 错误默认只显示 1 行短结论，全文（原因、下一步、错误码）点开 ⓘ 再看（UI 基准 ⑬）。
    clearStatus();
    String conclusion = resultText(value);
    String meta =
        com.deepseekharness.app.util.NativeResultParts.meta(
            value.entries, com.deepseekharness.app.util.Fmt.bytes(value.bytes));
    String problems =
        com.deepseekharness.app.util.NativeResultParts.problems(
            value.error.isEmpty() ? "" : errorText(value.error),
            projection.isEmpty() ? "" : errorText(projection));
    boolean wasHidden = statusCard.getVisibility() != View.VISIBLE;
    UiMotion.crossFadeText(resultConclusion, conclusion);
    UiMotion.crossFadeTimerText(resultMeta, meta);
    boolean hasProblems = !problems.isEmpty();
    resultError.setText(hasProblems ? UiText.text("失败") : "");
    failureDisclosure.key = "result-error";
    failureDisclosure.detail = problems;
    DisclosureCard.bind(resultError, failureDisclosure, disclosure, null);
    if (wasHidden) UiMotion.reveal(statusCard);
  }

  /** 清空结果卡四个字段；其余提示写入 status 前也先清结果，避免上一轮结果残留。 */
  private void clearStatus() {
    status.setText("");
    resultConclusion.setText("");
    resultMeta.setText("");
    failureDisclosure.detail = "";
    DisclosureCard.bind(resultError, failureDisclosure, disclosure, null);
  }

  /** 结果卡可见性：任一字段非空即显示；各字段只在自身非空时占位。 */
  private void refreshStatusCardVisibility() {
    if (statusCard == null || resultConclusion == null) return;
    status.setVisibility(shown(status));
    resultConclusion.setVisibility(shown(resultConclusion));
    resultMeta.setVisibility(shown(resultMeta));
    resultError.setVisibility(shown(resultError));
    boolean visible =
        status.getText().length() > 0
            || resultConclusion.getText().length() > 0
            || resultMeta.getText().length() > 0
            || resultError.getText().length() > 0;
    statusCard.setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  private static int shown(TextView view) {
    return view.getText().length() == 0 ? View.GONE : View.VISIBLE;
  }

  private String resultText(NativeBackupJobs.State value) {
    return value.result.equals("COMPLETE")
        ? t("导出完成，私有产物与目标均已校验。", "Export complete; private artifact and destination verified.")
        : value.result.equals("WRITTEN_UNVERIFIED")
            ? t(
                "已写入，未完成目标读回校验；私有验证副本保留。",
                "Written, but destination readback was not verified. The private verified copy is retained.")
            : value.result.equals("PARTIAL_RESCUE")
                ? t(
                    "部分救援：缺失或变化的内容已记录，不能视为完整备份。",
                    "Partial rescue: missing or changed content was recorded; this is not a complete backup.")
                : value.result.equals("BEST_EFFORT_RESCUE")
                    ? t(
                        "尽力救援完成，未承诺应用级一致性。",
                        "Best-effort rescue completed without an application-consistency guarantee.")
                    : value.result.startsWith("DATA_RESTORED_SETTINGS_REVIEW")
                        ? t(
                                "所选数据已保存。Profile 设置尚未应用：请进入「保留副本与旧树」逐个 profile 检查差异、选择字段并读回；插件和代码仍待审阅。",
                                "Selected data saved. Profile settings are pending: open Retained copies and old trees to inspect each profile, select fields and verify readback. Plugins and code still require review.")
                            + (value.result.contains("API_KEY")
                                ? t(
                                    "\n原生 API Key 未恢复，请在 Web UI 中确认模型凭据。",
                                    "\nThe native API key was not restored; verify model credentials in Web UI.")
                                : "")
                        : value.result.equals("DATA_RESTORED_API_KEY_MISSING")
                            ? t(
                                "数据已恢复，但备份中的 API Key 无法读取；请在 Web UI 中重新配置模型凭据。",
                                "Data restored, but the selected API key could not be read. Configure model credentials again in Web UI.")
                            : value.result.equals("DATA_RESTORED_API_KEY_OMITTED")
                                ? t(
                                    "数据已恢复；本次备份未包含 API Key，请在 Web UI 中配置模型凭据。",
                                    "Data restored; this backup did not include an API key. Configure model credentials in Web UI.")
                                : value.result.equals("DATA_RESTORED_PLUGINS_QUARANTINED")
                                    ? t(
                                        "数据已恢复，插件、可执行配置与待确认的自定义数据已隔离保存，尚未启用。",
                                        "Data restored; plugins, executable configuration and unclassified custom data are quarantined and not enabled.")
                                    : value.result.startsWith("DATA_RESTORED")
                                        ? t(
                                            "数据已恢复；运行兼容性仍需检查。",
                                            "Data restored; runtime compatibility still needs verification.")
                                        : value.result.equals("DATA_SAVED_PLUGIN_WARNINGS")
                                            ? t(
                                                "数据已保存，插件依赖有缺失或未确认项。",
                                                "Data saved with missing or unverified plugin dependencies.")
                                            : value.result.equals("RECOVERED_INTERRUPTED_COMMIT")
                                                ? t(
                                                    "中断的提交已恢复一致状态，原件保留。",
                                                    "The interrupted commit is consistent again; originals were retained.")
                                                : stage(value.stage);
  }

  private String errorText(String code) {
    String message;
    switch (code) {
      case "ORIGINAL_BACKUP_PASSWORD_REQUIRED":
        message =
            t(
                "旧加密副本需要原密码才能读取。原件已保留，可重新选择并输入原密码。",
                "The old encrypted copy needs its original password. The original is retained; select it again and enter the original password.");
        break;
      case "AUTOMATIC_KEY_MISSING":
      case "AUTOMATIC_KEY_UNAVAILABLE":
        message =
            t(
                "本机自动副本的解密密钥不可用，原件已保留。可选择其他可读备份。",
                "The local automatic-copy decryption key is unavailable. Originals are retained; choose another readable backup.");
        break;
      case "AUTHENTICATION_FAILED":
        message =
            t(
                "密码不匹配，或备份已损坏、被修改。当前数据保持原位。",
                "The password does not match, or the backup is damaged or modified. Current data stayed in place.");
        break;
      case "NO_SPACE":
        message =
            t(
                "可用空间不足。请释放空间后重试，原件与已验证副本保留。",
                "Not enough free space. Free up space and retry; originals and verified copies are retained.");
        break;
      case "CREDENTIAL_TEMPORARILY_UNAVAILABLE":
        message =
            com.deepseekharness.app.core.ConfigStore.credentialMessage(
                com.deepseekharness.app.util.CredentialRead.failed(
                    com.deepseekharness.app.util.CredentialRead.Reason.TRANSIENT_STORE));
        break;
      case "CREDENTIAL_NEEDS_ATTENTION":
        message =
            com.deepseekharness.app.core.ConfigStore.credentialMessage(
                com.deepseekharness.app.util.CredentialRead.failed(
                    com.deepseekharness.app.util.CredentialRead.Reason.UNREADABLE));
        break;
      case "CREDENTIAL_UNAVAILABLE":
        message =
            t(
                "原设备的密钥不可用。可先不包含原生 API Key 导出其他数据，再重新填写凭据。",
                "The original device key is unavailable. Export other data without the native API key, then re-enter the credential.");
        break;
      case "PERMISSION_DENIED":
      case "SAF_PERMISSION_REVOKED":
        message =
            t(
                "数据访问权限不可用。请重新选择目录或授予访问权限。",
                "Data access is unavailable. Select the folder again or restore its permission.");
        break;
      case "CRYPTO_HEADER":
      case "ARCHIVE_VERSION":
      case "UNSUPPORTED_LEGACY_VERSION":
        message =
            t(
                "此备份格式尚不支持，当前数据未覆盖。",
                "This backup format is unsupported. Current data was not overwritten.");
        break;
      case "TARGET_CHANGED":
        message =
            t(
                "目标在核验期间发生变化，操作已停止并保留原件。如果正在恢复，请先停止 Web、终端及其他写入任务，再重新选择备份并核对恢复预览。",
                "The target changed during verification. The operation stopped and originals were retained. If restoring, stop Web, terminals and other writers, then select the backup again and review the new restore preview.");
        break;
      case "SOURCE_CHANGED":
      case "INPUT_CHANGED":
        message =
            t(
                "数据在核验期间发生变化，已停止操作并保留原件。",
                "Data changed during verification. The operation stopped and originals were retained.");
        break;
      case "DESTINATION_CHECKSUM":
        message =
            t(
                "目标读回校验失败，私有验证副本保留，可另选位置导出。",
                "Destination readback failed verification. The private verified copy is retained for export elsewhere.");
        break;
      case "VERIFIED_COPY_CHANGED":
      case "VERIFIED_COPY_FORMAT":
      case "VERIFIED_COPY_RECORD":
      case "VERIFIED_COPY_SCOPE":
        message =
            t(
                "所选私有副本或记录未通过复核，已停止复制并保留原件。可选择另一份副本。",
                "The private copy or its record failed verification. Copying stopped and originals were retained. Choose another copy.");
        break;
      case "CANCELLED":
        message =
            t(
                "操作已取消；若已进入提交阶段，会先恢复一致状态。",
                "Operation cancelled; a commit already in progress converges before releasing protection.");
        break;
      default:
        message =
            t(
                "数据操作未完成。请查看错误代码和保留记录后重试。",
                "The data operation did not complete. Review its error code and retained records before retrying.");
    }
    return message + "\n" + t("错误代码：", "Error code: ") + code;
  }

  private String stage(String stage) {
    switch (stage) {
      case "IDLE":
        return t("等待操作", "Ready");
      case "PREPARING":
      case "COPYING_INPUT":
        return t("准备与读取数据", "Preparing and reading data");
      case "CAPTURING":
      case "ARCHIVING":
        return t("生成私有快照", "Creating private snapshot");
      case "PACKAGING":
        return t("打包 tar.gz 备份", "Packaging tar.gz backup");
      case "WRITING":
        return t("写入并验证无密码副本", "Writing and verifying an unencrypted copy");
      case "ENCRYPTING":
        return t("加密备份", "Encrypting backup");
      case "AUTHENTICATING":
      case "VERIFYING":
        return t("验证完整性与认证", "Verifying integrity and authentication");
      case "EXPORTING":
        return t("写入所选位置", "Writing to destination");
      case "COMMITTING":
        return t("提交数据，正在保持一致性", "Committing data and maintaining consistency");
      case "STOPPING_WRITERS":
      case "PREPARING_RESTORE":
        return t("准备恢复并停止写入", "Preparing restore and stopping writers");
      case "INTERRUPTED":
        return t(
            "上次作业中断，原件与私有副本保留",
            "Previous operation interrupted; originals and private copies retained");
      case "CANCELLED":
        return t("已取消", "Cancelled");
      case "FAILED":
      case "FAILED_RETAINED":
        return t("作业未完成，原件保留", "Operation incomplete; originals retained");
      default:
        return t("处理数据中", "Processing data");
    }
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }
}
