package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DiagnosticRepository;
import com.deepseekharness.app.util.SensitiveData;

public final class DiagnosticActivity extends AppCompatActivity {
  private DiagnosticRepository repository;
  private com.deepseekharness.app.core.ErrorLogRepository logs;
  private EditText steps;
  private PendingLogExport pendingLogExport;

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

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    setContentView(R.layout.activity_diagnostics);
    repository = new ViewModelProvider(this).get(DiagnosticRepository.class);
    repository.results.observe(
        this,
        items -> {
          android.widget.LinearLayout rows = findViewById(R.id.diagnostic_results);
          rows.removeAllViews();
          CardPage ui = new CardPage(this, "", "");
          for (var item : items)
            ui.entry(
                rows,
                item.title,
                item.status,
                R.drawable.ic_ui2_box,
                () -> CardSheet.show(this, item.title, item.detail));
        });
    findViewById(R.id.diagnostic_history).setOnClickListener(v -> showHistory());
    findViewById(R.id.diagnostic_recovery)
        .setOnClickListener(
            v -> startActivity(new android.content.Intent(this, StartupRecoveryActivity.class)));
    logs = new ViewModelProvider(this).get(com.deepseekharness.app.core.ErrorLogRepository.class);
    findViewById(R.id.diagnostic_logs_download).setOnClickListener(v -> chooseLogExport(false));
    findViewById(R.id.diagnostic_logs_save_as).setOnClickListener(v -> chooseLogExport(true));
    logs.state.observe(
        this,
        state -> {
          ((TextView) findViewById(R.id.diagnostic_logs_status))
              .setText(com.deepseekharness.app.util.UiStateText.render(state.message));
          findViewById(R.id.diagnostic_logs_download).setEnabled(!state.busy);
          findViewById(R.id.diagnostic_logs_save_as).setEnabled(!state.busy);
          findViewById(R.id.diagnostic_logs_status).setEnabled(!state.busy && state.uri != null);
        });
    findViewById(R.id.diagnostic_logs_status)
        .setOnClickListener(
            v -> {
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
                Toast.makeText(
                        this,
                        com.deepseekharness.app.util.UiText.text(
                            "无法打开日志，可在文件管理器的 Download/DSHA 中查看，或重新下载"),
                        Toast.LENGTH_LONG)
                    .show();
              }
            });
    steps = findViewById(R.id.diagnostic_steps);
    steps.setSaveEnabled(false);
    steps.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(32768)});
    findViewById(R.id.diagnostic_back).setOnClickListener(v -> finish());
    findViewById(R.id.diagnostic_refresh).setOnClickListener(v -> repository.generate());
    findViewById(R.id.diagnostic_repair).setOnClickListener(v -> repository.repairNetworkTools());
    findViewById(R.id.diagnostic_plugins)
        .setOnClickListener(
            v ->
                startActivity(
                    new android.content.Intent(this, MainActivity.class)
                        .putExtra("open_plugins", true)));
    findViewById(R.id.diagnostic_copy)
        .setOnClickListener(
            v -> {
              try {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard == null)
                  throw new IllegalStateException(
                      com.deepseekharness.app.util.UiText.text("剪贴板不可用"));
                clipboard.setPrimaryClip(
                    com.deepseekharness.app.bridge.SensitiveClipboard.text(
                        com.deepseekharness.app.util.UiText.text("DSHA 诊断"), completeReport()));
                Toast.makeText(
                        this,
                        com.deepseekharness.app.util.UiText.choose(
                            "报告已复制，请在公开分享前查看内容",
                            "Report copied. Review its content before public sharing"),
                        Toast.LENGTH_SHORT)
                    .show();
              } catch (Exception error) {
                Toast.makeText(
                        this,
                        com.deepseekharness.app.util.UiText.text("复制失败，可尝试导出报告"),
                        Toast.LENGTH_LONG)
                    .show();
              }
            });
    findViewById(R.id.diagnostic_export)
        .setOnClickListener(
            v -> {
              try {
                exporter.launch(
                    "DSHA-diagnostic-"
                        + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                            .format(new java.util.Date())
                        + ".txt");
              } catch (RuntimeException error) {
                Toast.makeText(
                        this,
                        com.deepseekharness.app.util.UiText.text("无法打开保存位置，请使用复制报告或检查系统文件管理器"),
                        Toast.LENGTH_LONG)
                    .show();
              }
            });
    repository.report.observe(
        this, text -> ((TextView) findViewById(R.id.diagnostic_report)).setText(text));
    repository.phase.observe(
        this,
        phase -> {
          TextView headline = findViewById(R.id.diagnostic_headline);
          TextView status = findViewById(R.id.diagnostic_status);
          if (phase == DiagnosticRepository.Phase.RUNNING) {
            headline.setText(com.deepseekharness.app.util.UiText.choose("正在检查", "Checking"));
            status.setText(com.deepseekharness.app.util.UiText.text("正在检查环境…"));
          } else if (phase == DiagnosticRepository.Phase.FAILED) {
            headline.setText(
                com.deepseekharness.app.util.UiText.choose("检查未完成", "Check incomplete"));
            status.setText(
                com.deepseekharness.app.util.UiText.choose(
                    "请打开失败卡片查看原因，也可复制或导出脱敏报告。",
                    "Open the failure card for details, or copy or export the redacted report."));
          } else {
            headline.setText(
                phase == DiagnosticRepository.Phase.SUCCEEDED
                    ? com.deepseekharness.app.util.UiText.choose("检查已完成", "Checks completed")
                    : com.deepseekharness.app.util.UiText.choose("准备检查", "Ready to check"));
            status.setText(com.deepseekharness.app.util.UiText.text("报告保留在本机，复制或导出后可用于反馈"));
          }
        });
    repository.busy.observe(
        this,
        busy -> {
          for (int id :
              new int[] {
                R.id.diagnostic_refresh,
                R.id.diagnostic_repair,
                R.id.diagnostic_copy,
                R.id.diagnostic_export
              }) findViewById(id).setEnabled(!busy);
        });
    if (saved == null) {
      if (getIntent().getBooleanExtra("download_error_logs", false)) logs.download();
      // 首页先展示检查范围，由用户发起耗时探针。
    }
  }

  /** Additional context is an explicit choice for this action and is never persisted. */
  private void chooseLogExport(boolean saveAs) {
    pendingLogExport = null;
    android.widget.CheckBox include = new android.widget.CheckBox(this);
    include.setChecked(false);
    include.setText(
        com.deepseekharness.app.util.UiText.choose(
            "仅本次附加本页主动填写的说明", "Attach the context entered on this page for this export only"));
    new DshaDialogBuilder(this)
        .setTitle(com.deepseekharness.app.util.UiText.choose("导出故障包", "Export diagnostics"))
        .setMessage(
            com.deepseekharness.app.util.UiText.choose(
                "包含结构化状态、现有脱敏操作记录尾部和摘要。原始进程输出、完整异常链及未采集的历史不会补入。附加说明默认不选，仅包含本页输入，不开启自动业务记录。",
                "Includes structured state, existing redacted operation tails and a summary. Raw process output, full exception chains and uncollected history are unavailable. Additional context is off by default and contains only this page's input; it enables no automatic business capture."))
        .setView(include)
        .setNegativeButton(
            com.deepseekharness.app.util.UiText.choose("取消", "Cancel"),
            (dialog, which) -> pendingLogExport = null)
        .setOnCancelListener(dialog -> pendingLogExport = null)
        .setPositiveButton(
            com.deepseekharness.app.util.UiText.choose("继续", "Continue"),
            (dialog, which) -> {
              boolean selected = include.isChecked();
              String note = selected && steps != null ? steps.getText().toString() : "";
              if (!saveAs) {
                logs.export(null, selected, note);
                return;
              }
              pendingLogExport = new PendingLogExport(selected, note);
              try {
                logExporter.launch(com.deepseekharness.app.core.ErrorLogRepository.filename());
              } catch (RuntimeException error) {
                pendingLogExport = null;
                Toast.makeText(
                        this,
                        com.deepseekharness.app.util.UiText.text("无法打开文件管理器，请尝试下载到默认目录"),
                        Toast.LENGTH_LONG)
                    .show();
              }
            })
        .show();
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
                        new CardPage(this, getString(R.string.ui134_recent_startups), "");
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
                              com.deepseekharness.app.util.UiText.choose(
                                  "还没有启动记录。", "No startup records yet."),
                              13,
                              R.color.text_secondary));
                    page.button(
                        page.footer,
                        com.deepseekharness.app.util.UiText.choose("关闭", "Close"),
                        false,
                        dialog::dismiss);
                    CardSheet.show(dialog, this);
                  });
            },
            "diagnostic-history")
        .start();
  }

  private String completeReport() {
    return SensitiveData.redact(
        String.valueOf(repository.report.getValue())
            + com.deepseekharness.app.util.UiText.format(
                "\n用户补充复现步骤：\n%s", (steps == null ? "" : steps.getText().toString())));
  }
}
