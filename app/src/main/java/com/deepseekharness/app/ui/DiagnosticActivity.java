package com.deepseekharness.app.ui;

import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DiagnosticRepository;
import com.deepseekharness.app.core.TestPackageFeedbackExporter;
import com.deepseekharness.app.util.DiagnosticSummary;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.UiText;

public final class DiagnosticActivity extends AppCompatActivity {
  private static final String STATE_EXPANDED = "diagnostic_ok_expanded";
  private static final String STATE_TOOLS_TAB = "diagnostic_tools_tab";
  private DiagnosticRepository repository;
  private com.deepseekharness.app.core.ErrorLogRepository logs;
  private DiagnosticResultList resultList;
  private CardTabs toolsTabs;
  private PendingLogExport pendingLogExport;

  /** 测试包反馈导出只允许一次一个；按钮通过 card.enabled 与 alpha 反馈忙状态。 */
  private boolean feedbackBusy;

  /** 本页输入的补充说明：仅内存，不持久化，不随页面重建恢复。 */
  private String note = "";

  /** 本页是否发起过导出；用于在页面上显示保存结果（包括失败）。 */
  private boolean exportTouched;

  /** 状态卡上一轮图标的 drawable id；-1 表示尚未渲染，用于只给真正的档位变化做高亮。 */
  private int lastDiagnosticIcon = -1;

  private record PendingLogExport(boolean include, String note) {}

  public static android.content.Intent downloadLogs(android.content.Context context) {
    return new android.content.Intent(context, DiagnosticActivity.class)
        .putExtra("download_error_logs", true);
  }

  private final ActivityResultLauncher<String> logExporter =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument("application/gzip"),
          uri -> {
            PendingLogExport selected = pendingLogExport;
            pendingLogExport = null;
            if (uri != null && selected != null)
              logs.export(uri, selected.include(), selected.note());
          });
  private final ActivityResultLauncher<String> exporter =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument("text/plain"),
          uri -> {
            if (uri == null) return;
            final String report = completeReport();
            new Thread(
                    () -> {
                      String message;
                      try (java.io.OutputStream stream =
                          getContentResolver().openOutputStream(uri, "wt")) {
                        if (stream == null)
                          throw new java.io.IOException(
                              com.deepseekharness.app.util.UiText.text("无法写入所选位置"));
                        stream.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        message = com.deepseekharness.app.util.UiText.text("诊断报告已导出");
                      } catch (Exception error) {
                        message =
                            com.deepseekharness.app.util.UiText.format(
                                "导出失败：%s", error.getClass().getSimpleName());
                      }
                      String done = message;
                      runOnUiThread(
                          () ->
                              Toast.makeText(getApplicationContext(), done, Toast.LENGTH_LONG)
                                  .show());
                    },
                    "diagnostic-export")
                .start();
          });

  /** 测试包反馈日志：系统文件选择器只选位置；采集、打包与回读校验都在后台线程完成。 */
  private final ActivityResultLauncher<String> feedbackExporter =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument("application/gzip"),
          uri -> {
            if (uri != null) exportFeedback(uri);
          });

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    setContentView(R.layout.activity_diagnostics);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    bindToolTabs(saved);
    bindFeedbackExport();
    repository = new ViewModelProvider(this).get(DiagnosticRepository.class);
    logs = new ViewModelProvider(this).get(com.deepseekharness.app.core.ErrorLogRepository.class);
    resultList =
        new DiagnosticResultList(
            findViewById(R.id.diagnostic_results),
            findViewById(R.id.diagnostic_empty),
            item -> CardSheet.show(this, item.title, item.detail));
    if (saved != null) resultList.setExpanded(saved.getBoolean(STATE_EXPANDED, false));
    repository.results.observe(
        this,
        items -> {
          resultList.show(items);
          renderHero();
          // 复制以检查结果为准：早期失败不经 busy=true，结果到达时也要重算反馈按钮。
          renderActions();
        });
    repository.phase.observe(this, phase -> renderHero());
    repository.busy.observe(
        this,
        busy -> {
          resultList.setRunning(Boolean.TRUE.equals(busy));
          renderHero();
          renderActions();
        });
    repository.report.observe(
        this,
        text -> {
          ((TextView) findViewById(R.id.diagnostic_report)).setText(text);
          renderActions();
        });
    logs.state.observe(this, state -> renderLogs());

    findViewById(R.id.diagnostic_back).setOnClickListener(v -> finish());
    findViewById(R.id.diagnostic_refresh).setOnClickListener(v -> repository.generate());
    findViewById(R.id.diagnostic_repair).setOnClickListener(v -> repository.repairNetworkTools());
    findViewById(R.id.diagnostic_network).setOnClickListener(v -> NetworkHealthSheet.show(this));
    findViewById(R.id.diagnostic_plugins)
        .setOnClickListener(
            v -> startActivity(MainActivity.navigationIntent(this).putExtra("open_plugins", true)));
    findViewById(R.id.diagnostic_history).setOnClickListener(v -> showHistory());
    findViewById(R.id.diagnostic_recovery)
        .setOnClickListener(
            v -> startActivity(new android.content.Intent(this, StartupRecoveryActivity.class)));
    findViewById(R.id.diagnostic_copy).setOnClickListener(v -> copyReport());
    findViewById(R.id.diagnostic_export).setOnClickListener(v -> showExportSheet());
    findViewById(R.id.diagnostic_logs_status).setOnClickListener(v -> openSavedLog());
    if (saved == null) {
      if (getIntent().getBooleanExtra("download_error_logs", false)) {
        exportTouched = true;
        logs.download();
      }
      // 首页先展示检查范围，由用户发起耗时探针。
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    if (resultList != null) out.putBoolean(STATE_EXPANDED, resultList.expanded());
    if (toolsTabs != null) out.putString(STATE_TOOLS_TAB, toolsTabs.selectedKey());
  }

  /**
   * F3：诊断页工具卡内的页签。「启动与恢复」放启动记录与故障处理，「网络与插件」放网络健康、
   * 修复网络工具与插件管理；未选中的面板 GONE，读屏不会读到隐藏内容。
   */
  private void bindToolTabs(Bundle saved) {
    android.widget.LinearLayout tools = findViewById(R.id.diagnostic_tools);
    View startup = findViewById(R.id.diagnostic_tools_startup);
    View network = findViewById(R.id.diagnostic_tools_network);
    if (tools == null || startup == null || network == null) return;
    toolsTabs =
        CardTabs.bind(
            tools,
            java.util.List.of(
                new CardTabs.Tab(
                    "startup",
                    com.deepseekharness.app.util.UiText.choose("启动与恢复", "Startup and recovery"),
                    startup),
                new CardTabs.Tab(
                    "network",
                    com.deepseekharness.app.util.UiText.choose("网络与插件", "Network and plugins"),
                    network)),
            saved == null ? null : saved.getString(STATE_TOOLS_TAB));
  }

  // ---------- 测试包反馈导出：入口只在 TEST_PACKAGE 构建出现，正式包行为不变 ----------

  /**
   * 反馈日志卡由 build.gradle 的 TEST_PACKAGE 唯一决定：正式包与普通 debug 构建整行隐藏，
   * 不改变既有复制 / 故障包导出。测试包内标题与说明走应用语言，不新增 XML 文案资源。
   */
  private void bindFeedbackExport() {
    View card = findViewById(R.id.diagnostic_feedback_export);
    View divider = findViewById(R.id.diagnostic_feedback_export_divider);
    if (card == null || divider == null) return;
    if (!BuildConfig.TEST_PACKAGE) {
      card.setVisibility(View.GONE);
      divider.setVisibility(View.GONE);
      return;
    }
    card.setVisibility(View.VISIBLE);
    divider.setVisibility(View.VISIBLE);
    TextView title = findViewById(R.id.diagnostic_feedback_title);
    TextView subtitle = findViewById(R.id.diagnostic_feedback_sub);
    if (title != null)
      title.setText(UiText.choose("导出反馈日志（测试包）", "Export feedback logs (test build)"));
    if (subtitle != null)
      subtitle.setText(
          UiText.choose(
              "本应用与 DSH 日志、启动与维护记录、设备信息；有界、脱敏",
              "This app and DSH logs, startup and maintenance records, device info; bounded and redacted"));
    card.setOnClickListener(v -> showFeedbackSheet());
  }

  private void showFeedbackSheet() {
    if (feedbackBusy) return;
    CardPage page =
        new CardPage(
            this, UiText.choose("导出反馈日志（测试包）", "Export feedback logs (test build)"), "", true);
    var dialog = CardSheet.create(this, page);
    TextView scope =
        page.text(
            UiText.choose(
                "一次性采集本进程 logcat（仅自己的 pid）、本应用与 DSH 日志尾部、最近启动 / 维护 / 运行时健康 / 插件安装记录与设备环境信息。单文件 ≤256 KB、总量 ≤8 MB，所有文本按现有规则脱敏；不采集账号、设备标识或 API Key，不自动上传。日志仍可能包含个人信息，分享前请检查。",
                "Collects this process's logcat (own pid only), this app's and DSH log tails, recent startup, maintenance, runtime-health and plugin-install records, and device environment. Each file ≤256 KB and ≤8 MB in total; all text is redacted with existing rules. No account, device identifiers or API keys are collected and nothing is uploaded. Logs may still contain personal information; review before sharing."),
            12,
            R.color.shell_text_secondary);
    scope.setLineSpacing(page.dp(3), 1);
    scope.setPadding(0, 0, 0, page.dp(14));
    page.content.addView(scope);

    LinearLayout buttons = new LinearLayout(this);
    buttons.setOrientation(LinearLayout.HORIZONTAL);
    page.footer.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
    Button saveAs =
        page.button(
            buttons,
            UiText.choose("另存为…", "Save as…"),
            true,
            () -> {
              dialog.dismiss();
              try {
                RecentsPrivacyGuard.launch(
                    feedbackExporter, TestPackageFeedbackExporter.filename());
              } catch (RuntimeException error) {
                Toast.makeText(this, UiText.text("无法打开文件管理器，请稍后重试"), Toast.LENGTH_LONG).show();
              }
            });
    Button share =
        page.button(
            buttons,
            UiText.choose("分享…", "Share…"),
            false,
            () -> {
              dialog.dismiss();
              shareFeedback();
            });
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
    left.topMargin = page.dp(10);
    saveAs.setLayoutParams(left);
    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
    right.topMargin = page.dp(10);
    right.setMarginStart(page.dp(8));
    share.setLayoutParams(right);
    page.button(page.footer, UiText.choose("取消", "Cancel"), false, dialog::dismiss)
        .setBackgroundResource(android.R.color.transparent);
    CardSheet.show(dialog, this);
  }

  /** SAF 目标已选：后台采集、打包并回读校验；失败只更新卡片状态，不中断页面。 */
  private void exportFeedback(android.net.Uri uri) {
    if (feedbackBusy) return;
    setFeedbackState(UiText.text("正在收集并保存反馈日志…"), true);
    new Thread(
            () -> {
              String message;
              java.io.File staged = null;
              try {
                staged = TestPackageFeedbackExporter.stage(this);
                TestPackageFeedbackExporter.writeTo(this, uri, staged);
                message = UiText.text("反馈日志已保存到所选位置");
              } catch (Exception error) {
                message =
                    UiText.format(
                        "导出失败：%s", SensitiveData.redact(String.valueOf(error.getMessage())));
              } finally {
                if (staged != null) staged.delete();
              }
              String done = message;
              runOnUiThread(
                  () -> {
                    if (!isFinishing() && !isDestroyed()) setFeedbackState(done, false);
                  });
            },
            "feedback-export")
        .start();
  }

  /** 本地分享：生成缓存归档后交给系统分享目标；不经过网络、不自动上传。 */
  private void shareFeedback() {
    if (feedbackBusy) return;
    setFeedbackState(UiText.text("正在收集反馈日志…"), true);
    new Thread(
            () -> {
              String failure = "";
              android.content.Intent chooser = null;
              try {
                java.io.File staged = TestPackageFeedbackExporter.stage(this);
                chooser = TestPackageFeedbackExporter.shareIntent(this, staged);
              } catch (Exception error) {
                failure =
                    UiText.format(
                        "收集失败：%s", SensitiveData.redact(String.valueOf(error.getMessage())));
              }
              String message = failure;
              android.content.Intent share = chooser;
              runOnUiThread(
                  () -> {
                    if (isFinishing() || isDestroyed()) return;
                    setFeedbackState(message.isEmpty() ? UiText.text("请选择分享目标") : message, false);
                    if (share != null) {
                      try {
                        startActivity(share);
                      } catch (RuntimeException unavailable) {
                        setFeedbackState(UiText.text("没有可用的分享目标"), false);
                      }
                    }
                  });
            },
            "feedback-share")
        .start();
  }

  private void setFeedbackState(String message, boolean busy) {
    feedbackBusy = busy;
    View card = findViewById(R.id.diagnostic_feedback_export);
    if (card != null) {
      card.setEnabled(!busy);
      card.setAlpha(busy ? 0.55f : 1f);
    }
    TextView status = findViewById(R.id.diagnostic_feedback_status);
    if (status == null) return;
    boolean show = message != null && !message.isEmpty();
    status.setVisibility(show ? View.VISIBLE : View.GONE);
    if (show) status.setText(message);
  }

  // ---------- 状态卡：图标 + 一句结论 + 唯一主按钮 ----------

  private void renderHero() {
    java.util.List<DiagnosticRepository.Result> items = repository.results.getValue();
    if (items == null) items = java.util.List.of();
    java.util.List<com.deepseekharness.app.util.DiagnosticReport.Health> healths =
        new java.util.ArrayList<>();
    for (DiagnosticRepository.Result item : items) healths.add(item.health);
    DiagnosticRepository.Phase phase = repository.phase.getValue();
    boolean running =
        Boolean.TRUE.equals(repository.busy.getValue())
            || phase == DiagnosticRepository.Phase.RUNNING;
    DiagnosticSummary.Tone tone =
        DiagnosticSummary.tone(running, phase == DiagnosticRepository.Phase.FAILED, healths);
    TextView headline = findViewById(R.id.diagnostic_headline);
    TextView status = findViewById(R.id.diagnostic_status);
    ImageView badge = findViewById(R.id.diagnostic_badge);
    Button action = findViewById(R.id.diagnostic_refresh);
    int attention = DiagnosticSummary.count(healths, DiagnosticSummary.Group.ATTENTION);
    int unverified = DiagnosticSummary.count(healths, DiagnosticSummary.Group.UNVERIFIED);
    int icon = R.drawable.ic_onb_info,
        color = R.color.shell_text_muted,
        soft = R.color.shell_raised;
    String headlineText = headline.getText().toString();
    String statusText = status.getText().toString();
    switch (tone) {
      case RUNNING -> {
        headlineText = UiText.choose("正在检查", "Checking");
        statusText = UiText.text("正在检查环境…");
        icon = R.drawable.ic_recovery_refresh;
        color = R.color.shell_accent;
        soft = R.color.shell_accent_soft;
      }
      case GOOD -> {
        headlineText = UiText.choose("环境正常", "Everything looks fine");
        statusText = UiText.format("%d 项检查全部通过", healths.size());
        icon = R.drawable.ic_onb_check;
        color = R.color.shell_good;
        soft = R.color.shell_good_soft;
      }
      case ATTENTION -> {
        headlineText = UiText.format("%d 项需要处理", attention);
        statusText =
            UiText.choose("问题已排在结果顶部，点开查看原因", "Issues are listed first. Open one for details.");
        icon = R.drawable.ic_onb_alert;
        color = R.color.shell_warn;
        soft = R.color.shell_warn_soft;
      }
      case UNVERIFIED -> {
        headlineText = UiText.format("%d 项未核验", unverified);
        statusText =
            UiText.choose(
                "未核验不代表故障，通常是相关服务未运行",
                "Unverified is not a failure; the related service is usually not running");
      }
      case FAILED -> {
        headlineText = UiText.choose("检查未完成", "Check incomplete");
        statusText =
            UiText.choose(
                "请打开失败卡片查看原因，也可复制或导出脱敏报告。",
                "Open the failure card for details, or copy or export the redacted report.");
        icon = R.drawable.ic_onb_x;
        color = R.color.shell_bad;
        soft = R.color.shell_bad_soft;
      }
      case IDLE -> {
        headlineText = getString(R.string.diag248_idle_title);
        statusText = getString(R.string.diag248_idle_sub);
      }
    }
    // 结论、状态、按钮标签交叉淡入；图标/色块在档位变化时高亮一次（审计 F-02）。
    UiMotion.crossFadeText(headline, headlineText);
    UiMotion.crossFadeText(status, statusText);
    boolean iconChanged = lastDiagnosticIcon != icon;
    lastDiagnosticIcon = icon;
    badge.setImageResource(icon);
    badge.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(color)));
    badge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(soft)));
    if (iconChanged) UiMotion.flash(badge);
    View progress = findViewById(R.id.diagnostic_progress);
    boolean showProgress = tone == DiagnosticSummary.Tone.RUNNING;
    boolean progressWasHidden = progress.getVisibility() != View.VISIBLE;
    progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
    if (showProgress && progressWasHidden) UiMotion.reveal(progress);
    UiMotion.crossFadeText(
        action,
        tone == DiagnosticSummary.Tone.IDLE
            ? getString(R.string.diag248_start)
            : UiText.text("重新检查"));
    action.setEnabled(!running);
    // 全部正常时主按钮退为次级样式，让结论成为视觉重点；其余状态保持主按钮。
    boolean quiet = tone == DiagnosticSummary.Tone.GOOD;
    action.setBackgroundResource(quiet ? R.drawable.bg_btn : R.drawable.bg_btn_primary);
    action.setTextColor(
        getColorStateList(quiet ? R.color.button_text : R.color.button_primary_text));
  }

  // ---------- 反馈 / 工具：忙时禁用并降低透明度（子视图不会随父视图自动变灰） ----------

  private void renderActions() {
    boolean busy = Boolean.TRUE.equals(repository.busy.getValue());
    boolean hasReport = !resultsEmpty();
    enable(R.id.diagnostic_copy, !busy && hasReport);
    enable(R.id.diagnostic_repair, !busy);
    renderLogs();
  }

  private void renderLogs() {
    com.deepseekharness.app.core.ErrorLogRepository.State state = logs.state.getValue();
    boolean logsBusy = state != null && state.busy;
    enable(R.id.diagnostic_export, !logsBusy && !Boolean.TRUE.equals(repository.busy.getValue()));
    TextView status = findViewById(R.id.diagnostic_logs_status);
    // 初始说明已移入导出弹层；页面上只显示进行中、本次结果或上次保存的文件。
    boolean show = state != null && (state.busy || state.uri != null || exportTouched);
    boolean wasHidden = status.getVisibility() != View.VISIBLE;
    status.setVisibility(show ? View.VISIBLE : View.GONE);
    findViewById(R.id.diagnostic_logs_divider).setVisibility(show ? View.VISIBLE : View.GONE);
    if (state != null) {
      UiMotion.crossFadeText(
          status, com.deepseekharness.app.util.UiStateText.render(state.message));
      if (show && wasHidden) UiMotion.reveal(status);
    }
    status.setEnabled(!logsBusy && state != null && state.uri != null);
  }

  private void enable(int id, boolean enabled) {
    View view = findViewById(id);
    view.setEnabled(enabled);
    view.setAlpha(enabled ? 1f : 0.45f);
  }

  private boolean resultsEmpty() {
    java.util.List<DiagnosticRepository.Result> items = repository.results.getValue();
    return items == null || items.isEmpty();
  }

  private void copyReport() {
    try {
      ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
      if (clipboard == null) throw new IllegalStateException(UiText.text("剪贴板不可用"));
      clipboard.setPrimaryClip(
          com.deepseekharness.app.bridge.SensitiveClipboard.text(
              UiText.text("DSHA 诊断"), completeReport()));
      Toast.makeText(
              this,
              UiText.choose(
                  "报告已复制，请在公开分享前查看内容", "Report copied. Review its content before public sharing"),
              Toast.LENGTH_SHORT)
          .show();
    } catch (Exception error) {
      Toast.makeText(this, UiText.text("复制失败，可尝试导出报告"), Toast.LENGTH_LONG).show();
    }
  }

  private void openSavedLog() {
    com.deepseekharness.app.core.ErrorLogRepository.State state = logs.state.getValue();
    if (state == null || state.uri == null || state.busy) return;
    try {
      android.net.Uri uri = state.uri;
      if ("file".equals(uri.getScheme()))
        uri =
            androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".updates", new java.io.File(uri.getPath()));
      startActivity(
          new android.content.Intent(android.content.Intent.ACTION_VIEW)
              .setDataAndType(uri, logs.savedMimeType())
              .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION));
    } catch (RuntimeException error) {
      Toast.makeText(this, UiText.text("无法打开日志，请用文件管理器查看"), Toast.LENGTH_LONG).show();
    }
  }

  // ---------- 导出弹层：格式 + 补充复现步骤 + 下载 / 另存为 ----------

  /** 补充说明只属于本次导出或复制，不写入设置、不随页面重建恢复，也不开启自动业务记录。 */
  private void showExportSheet() {
    CardPage page = new CardPage(this, UiText.text("导出故障包"), "", true);
    var dialog = CardSheet.create(this, page);
    TextView scope =
        page.text(
            UiText.choose(
                "导出结构化状态、现有脱敏操作记录尾部及摘要。不主动采集业务正文，但自由格式错误可能包含业务内容，分享前请检查。可仅本次附加主动填写的说明。",
                "Export structured state, existing redacted operation tails and a summary. Business bodies are not actively collected, but free-form errors may contain business content; review before sharing. You may attach the context entered here for this export only."),
            12,
            R.color.shell_text_secondary);
    scope.setLineSpacing(page.dp(3), 1);
    scope.setPadding(0, 0, 0, page.dp(14));
    page.content.addView(scope);

    boolean hasReport = !resultsEmpty();
    boolean[] text = {false};
    View[] options = new View[2];
    Button[] download = new Button[1];
    Runnable sync =
        () -> {
          options[0].setSelected(!text[0]);
          options[1].setSelected(text[0]);
          // 文本报告只走系统文件选择器；下载到默认目录只用于故障包。
          if (download[0] != null) download[0].setVisibility(text[0] ? View.GONE : View.VISIBLE);
        };
    options[0] =
        option(
            page,
            UiText.choose("故障包 .tar.gz（推荐）", "Diagnostics package .tar.gz (recommended)"),
            UiText.choose(
                "结构化状态、脱敏操作记录尾部与摘要", "Structured state, redacted operation tails and a summary"),
            true,
            () -> {
              text[0] = false;
              sync.run();
            });
    options[1] =
        option(
            page,
            UiText.choose("文本报告 .txt", "Text report .txt"),
            hasReport
                ? UiText.choose("仅本次检查结果", "Results of this check only")
                : UiText.choose("需先完成一次检查", "Run a check first"),
            hasReport,
            () -> {
              text[0] = true;
              sync.run();
            });

    TextView label =
        page.text(getString(R.string.ui2_reproduction), 12, R.color.shell_text_secondary);
    label.setTypeface(null, android.graphics.Typeface.BOLD);
    label.setPadding(0, page.dp(14), 0, page.dp(4));
    page.content.addView(label);
    TextView hint =
        page.text(
            UiText.choose(
                "可选，仅附加到本次导出或复制，不会保存",
                "Optional. Attached only to this export or copy and never saved"),
            12,
            R.color.shell_text_muted);
    hint.setPadding(0, 0, 0, page.dp(8));
    page.content.addView(hint);
    EditText steps = new EditText(this);
    steps.setBackgroundResource(R.drawable.bg_shell_input);
    steps.setTextSize(14);
    steps.setTextColor(getColor(R.color.shell_text));
    steps.setHintTextColor(getColor(R.color.shell_text_muted));
    steps.setHint(getString(R.string.ui_m0182));
    steps.setGravity(android.view.Gravity.TOP);
    steps.setMinLines(3);
    steps.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    steps.setPadding(page.dp(14), page.dp(12), page.dp(14), page.dp(12));
    steps.setSaveEnabled(false);
    steps.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(2000)});
    steps.setText(note);
    steps.addTextChangedListener(
        new android.text.TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {}

          @Override
          public void afterTextChanged(android.text.Editable s) {
            note = s.toString();
          }
        });
    page.content.addView(steps, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout buttons = new LinearLayout(this);
    buttons.setOrientation(LinearLayout.HORIZONTAL);
    page.footer.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
    Button saveAs =
        page.button(
            buttons,
            getString(R.string.ui_m0080),
            false,
            () -> {
              dialog.dismiss();
              if (text[0]) exportText();
              else exportPackage(true);
            });
    download[0] =
        page.button(
            buttons,
            UiText.choose("保存到下载目录", "Save to Downloads"),
            true,
            () -> {
              dialog.dismiss();
              exportPackage(false);
            });
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
    left.topMargin = page.dp(10);
    saveAs.setLayoutParams(left);
    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
    right.topMargin = page.dp(10);
    right.setMarginStart(page.dp(8));
    download[0].setLayoutParams(right);
    page.button(page.footer, UiText.choose("取消", "Cancel"), false, dialog::dismiss)
        .setBackgroundResource(android.R.color.transparent);
    sync.run();
    CardSheet.show(dialog, this);
  }

  /** 单选卡片：整行可点，选中态用强调描边；不可用时保持说明可读但不可选。 */
  private View option(
      CardPage page, String title, String subtitle, boolean enabled, Runnable select) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setBackgroundResource(R.drawable.bg_plugin_card_selectable);
    row.setPadding(page.dp(14), page.dp(12), page.dp(14), page.dp(12));
    row.setMinimumHeight(page.dp(56));
    row.setFocusable(enabled);
    row.setClickable(enabled);
    row.setEnabled(enabled);
    row.setAlpha(enabled ? 1f : 0.5f);
    TextView heading = page.text(title, 14, R.color.shell_text);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    row.addView(heading);
    TextView detail = page.text(subtitle, 12, R.color.shell_text_secondary);
    detail.setPadding(0, page.dp(4), 0, 0);
    detail.setLineSpacing(page.dp(2), 1);
    row.addView(detail);
    row.setOnClickListener(v -> select.run());
    row.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(
              View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(android.widget.RadioButton.class.getName());
            info.setCheckable(true);
            info.setChecked(host.isSelected());
          }
        });
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = page.dp(8);
    page.content.addView(row, p);
    return row;
  }

  private void exportPackage(boolean saveAs) {
    exportTouched = true;
    String selected = note == null ? "" : note;
    boolean include = !selected.trim().isEmpty();
    if (!saveAs) {
      logs.export(null, include, include ? selected : "");
      return;
    }
    pendingLogExport = new PendingLogExport(include, include ? selected : "");
    try {
      RecentsPrivacyGuard.launch(
          logExporter, com.deepseekharness.app.core.ErrorLogRepository.filename());
    } catch (RuntimeException error) {
      pendingLogExport = null;
      Toast.makeText(this, UiText.text("无法打开文件管理器，请下载到默认目录"), Toast.LENGTH_LONG).show();
    }
  }

  private void exportText() {
    try {
      RecentsPrivacyGuard.launch(
          exporter,
          "DSHA-diagnostic-"
              + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                  .format(new java.util.Date())
              + ".txt");
    } catch (RuntimeException error) {
      Toast.makeText(this, UiText.text("无法打开保存位置，请复制报告"), Toast.LENGTH_LONG).show();
    }
  }

  @Override
  protected void onDestroy() {
    pendingLogExport = null;
    super.onDestroy();
  }

  private void showHistory() {
    new Thread(
            () -> {
              var entries =
                  com.deepseekharness.app.core.HarnessController.get(this)
                      .startupDiagnostics()
                      .history();
              runOnUiThread(
                  () -> {
                    if (isFinishing() || isDestroyed()) return;
                    CardPage page =
                        new CardPage(this, getString(R.string.ui134_recent_startups), "", true);
                    var dialog = CardSheet.create(this, page);
                    for (var entry : entries) {
                      String date =
                          java.text.DateFormat.getDateTimeInstance()
                              .format(new java.util.Date(entry.started));
                      page.entry(
                          page.content,
                          date,
                          entry.stage + " · " + entry.status,
                          R.drawable.ic_recovery_document,
                          () ->
                              CardSheet.show(
                                  this,
                                  date,
                                  entry.stage + "\n\n" + entry.reason + "\n\n" + entry.log));
                    }
                    if (entries.isEmpty())
                      page.content.addView(
                          page.text(
                              UiText.choose("还没有启动记录。", "No startup records yet."),
                              13,
                              R.color.text_secondary));
                    page.button(page.footer, UiText.choose("关闭", "Close"), false, dialog::dismiss);
                    CardSheet.show(dialog, this);
                  });
            },
            "diagnostic-history")
        .start();
  }

  private String completeReport() {
    String report = String.valueOf(repository.report.getValue());
    String extra = note == null ? "" : note.trim();
    // 只有用户在导出弹层主动填写时才附加；空说明不再追加空标题。
    if (!extra.isEmpty()) report += UiText.format("\n用户补充复现步骤：\n%s", extra);
    return SensitiveData.redact(report);
  }
}
