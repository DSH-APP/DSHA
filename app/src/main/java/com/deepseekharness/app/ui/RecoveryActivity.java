package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.recovery.*;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.RecoveryStatusText;
import com.deepseekharness.app.util.SensitiveData;
import org.json.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 无正式环境门禁的恢复入口；原生页面负责用户确认，应急网页没有确认能力。 */
public final class RecoveryActivity extends AppCompatActivity {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicBoolean reading = new AtomicBoolean();
  private CardPage page;
  private TextView status, operation;
  private EditText temporaryKey;
  private Button start, open, stop;
  private LinearLayout proposals;
  private RecoveryController controller;
  private String lastState = "", lastPlans = "";
  private long lastPoll;
  private boolean applying, resumed;

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    controller = RecoveryController.get(this);
    page = new CardPage(this, t("应急 DSH", "Emergency DSH"), "");
    setContentView(page.root);
    LinearLayout intro = page.card();
    intro.addView(
        page.text(
            t(
                "正式环境维护失败时，可在这里启动独立的空白 DSH。它可以读取相关故障文件并向当前模型服务发送内容，提出修复方案；每次正式数据写入都需在此确认。",
                "Start a separate, empty DSH when the regular environment fails. It can read affected files and send their contents to your model service to propose repairs. Confirm each write to regular data here."),
            14,
            R.color.text_secondary));
    status = page.text("", 15, R.color.text);
    status.setTextIsSelectable(true);
    intro.addView(status);
    LinearLayout actions = page.card();
    actions.addView(
        page.text(
            t(
                "临时 API Key（可选，仅本次应急会话使用）",
                "Temporary API key (optional, this emergency session only)"),
            14,
            R.color.text));
    temporaryKey = new EditText(this);
    temporaryKey.setSingleLine(true);
    temporaryKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    temporaryKey.setSaveEnabled(false);
    temporaryKey.setTextSize(15);
    temporaryKey.setHint(t("留空时尝试已有可读凭据", "Leave blank to try an existing readable key"));
    actions.addView(temporaryKey, new LinearLayout.LayoutParams(-1, -2));
    if (Build.VERSION.SDK_INT >= 26)
      temporaryKey.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    start =
        page.button(
            actions,
            t("启动应急 DSH", "Start emergency DSH"),
            true,
            () -> {
              try {
                String key = temporaryKey.getText().toString();
                RecoveryService.start(this, key.isEmpty() ? null : key);
                temporaryKey.setText("");
                status.setText(t("正在准备应急服务…", "Preparing the emergency service…"));
              } catch (Exception error) {
                message(error);
              }
              main.postDelayed(refresh, 250);
            });
    open =
        page.button(
            actions,
            t("进入空白应急 Web", "Open emergency Web"),
            true,
            () -> startActivity(new Intent(this, RecoveryWebActivity.class)));
    stop =
        page.button(
            actions,
            t("停止应急 DSH", "Stop emergency DSH"),
            false,
            () -> {
              controller.stop();
              main.post(refresh);
            });
    actions.addView(
        page.text(
            t(
                "需要更换临时密钥时，请先停止应急 DSH，再填写并重新启动。",
                "To change the temporary key, stop emergency DSH, enter the key, then start it again."),
            14,
            R.color.text_secondary));
    page.button(
        actions,
        t("查看原生诊断", "View native diagnostics"),
        false,
        () ->
            background(
                () -> {
                  RecoveryRepairBroker broker = controller.broker();
                  if (broker == null)
                    throw new java.io.IOException(
                        t(
                            "应急服务尚未启动；可先查看错误日志。",
                            "The emergency service has not started. You can view error logs first."));
                  String data = broker.diagnostics().toString(2);
                  ui(() -> showText(t("诊断结果", "Diagnostics"), data));
                }));
    operation = page.text("", 14, R.color.text_secondary);
    operation.setTextIsSelectable(true);
    page.content.addView(operation);
    page.button(
        actions, t("查看无需模型的修复选项", "Review repairs without a model"), false, this::repairOptions);
    proposals = page.card();
    renderPlans(new JSONArray());
    LinearLayout tools = page.card();
    page.button(
        tools,
        t("查看并下载错误日志", "View and export error logs"),
        false,
        () -> startActivity(DiagnosticActivity.downloadLogs(this)));
    page.button(
        tools,
        t("备份与保留数据", "Backups and retained data"),
        false,
        () -> startActivity(new Intent(this, NativeDataActivity.class)));
    page.button(
        tools,
        t("返回正式环境恢复页", "Return to regular environment recovery"),
        false,
        () -> startActivity(new Intent(this, ExtractActivity.class).putExtra("review_only", true)));
    page.button(page.footer, t("返回", "Back"), false, this::finish);
  }

  private void ui(Runnable work) {
    runOnUiThread(
        () -> {
          if (!isFinishing() && !isDestroyed()) work.run();
        });
  }

  private void message(Throwable error) {
    ui(
        () ->
            operation.setText(
                SensitiveData.redact(
                    error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getMessage())));
  }

  private interface Work {
    void run() throws Exception;
  }

  private void background(Work work) {
    new Thread(
            () -> {
              try {
                work.run();
              } catch (Exception error) {
                message(error);
              }
            },
            "emergency-native-review")
        .start();
  }

  private void showText(String title, String value) {
    ScrollView scroll = new ScrollView(this);
    TextView text = page.text(value, 14, R.color.text);
    text.setPadding(page.dp(18), page.dp(12), page.dp(18), page.dp(12));
    text.setTextIsSelectable(true);
    scroll.addView(text);
    new DshaDialogBuilder(this)
        .setTitle(title)
        .setView(scroll)
        .setPositiveButton(t("关闭", "Close"), null)
        .show();
  }

  private void repairOptions() {
    RecoveryRepairBroker broker = controller.broker();
    if (broker == null) {
      operation.setText(
          t("请先启动应急服务；无需配置模型。", "Start the emergency service first. A model is not required."));
      return;
    }
    background(
        () -> {
          JSONArray targets = broker.targets();
          java.util.ArrayList<String> labels = new java.util.ArrayList<>(),
              ids = new java.util.ArrayList<>(),
              actions = new java.util.ArrayList<>();
          for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.getJSONObject(i);
            JSONArray choices = target.optJSONArray("actions");
            if (choices == null) continue;
            for (int j = 0; j < choices.length(); j++) {
              String action = choices.getString(j);
              if ("profile-settings".equals(action)) continue;
              String label =
                  switch (action) {
                    case "recover-maintenance" -> t("恢复中断维护", "Recover interrupted maintenance");
                    case "repair-runtime" -> t("修复受管运行时", "Repair managed runtime");
                    case "repair-cli-dependencies" ->
                        t("修复 CLI/Node 依赖", "Repair CLI/Node dependencies");
                    case "new-web-profile" ->
                        t(
                            "重建 Web 基础配置（原件留存）",
                            "Rebuild basic Web configuration (retain originals)");
                    case "new-global-patch" ->
                        t("重建全局配置补丁（影响所有 Profile）", "Rebuild global patch (all profiles)");
                    default -> action;
                  };
              labels.add(label);
              ids.add(target.getString("id"));
              actions.add(action);
            }
          }
          ui(
              () ->
                  new DshaDialogBuilder(this)
                      .setTitle(t("选择要预览的修复", "Choose a repair to preview"))
                      .setItems(
                          labels.toArray(new String[0]),
                          (dialog, index) ->
                              background(
                                  () -> {
                                    JSONObject source = broker.read(ids.get(index));
                                    JSONObject request =
                                        new JSONObject()
                                            .put("action", actions.get(index))
                                            .put("targetId", ids.get(index))
                                            .put("sourceSha256", source.getString("sourceSha256"))
                                            .put(
                                                "dataGeneration",
                                                source.getString("dataGeneration"));
                                    JSONObject proposal = broker.propose(request);
                                    ui(
                                        () -> {
                                          lastPlans = "";
                                          preview(proposal.optString("id"));
                                        });
                                  }))
                      .setNegativeButton(t("返回", "Back"), null)
                      .show());
        });
  }

  private void preview(String id) {
    RecoveryRepairBroker broker = controller.broker();
    if (broker == null) return;
    background(
        () -> {
          JSONObject proposal = broker.preview(id);
          ui(
              () -> {
                ScrollView scroll = new ScrollView(this);
                LinearLayout content = page.column();
                content.setPadding(page.dp(18), page.dp(12), page.dp(18), page.dp(12));
                scroll.addView(content);
                for (String[] row :
                    new String[][] {
                      {t("操作", "Operation"), proposal.optString("description")},
                      {t("目标", "Target"), proposal.optString("targetId")},
                      {t("原件 SHA-256", "Original SHA-256"), proposal.optString("sourceSha256")},
                      {t("修改前", "Before"), proposal.optString("before")},
                      {t("候选修改后", "Proposed after"), proposal.optString("after")}
                    }) {
                  TextView heading = page.text(row[0], 15, R.color.text);
                  heading.setTypeface(null, android.graphics.Typeface.BOLD);
                  content.addView(heading);
                  TextView value = page.text(row[1], 14, R.color.text_secondary);
                  value.setTextIsSelectable(true);
                  value.setPadding(0, page.dp(6), 0, page.dp(16));
                  content.addView(value);
                }
                var dialog =
                    new DshaDialogBuilder(this)
                        .setTitle(t("确认这一次修复", "Confirm this repair"))
                        .setView(scroll)
                        .setNegativeButton(t("返回", "Back"), null)
                        .create();
                if (proposal.optBoolean("writeBlocked"))
                  content.addView(
                      page.text(proposal.optString("writeBlockedReason"), 14, R.color.err));
                if ("PENDING".equals(proposal.optString("status"))
                    && !proposal.optBoolean("writeBlocked")) {
                  // 决议按钮放进同一个滚动内容，避免短屏/大字体下三行对话框按钮挤出屏幕。
                  page.button(
                      content,
                      t("保存原件并执行", "Save original and apply"),
                      true,
                      () -> {
                        dialog.dismiss();
                        applying = true;
                        operation.setText(
                            t(
                                "正在核验停止屏障、源摘要并执行修复…",
                                "Verifying stopped processes and source hashes, then applying the repair…"));
                        background(
                            () -> {
                              try {
                                JSONObject result = broker.confirm(id);
                                ui(() -> operation.setText(result.optString("message")));
                              } finally {
                                ui(
                                    () -> {
                                      applying = false;
                                      lastPlans = "";
                                      main.post(refresh);
                                    });
                              }
                            });
                      });
                  page.button(
                      content,
                      t("拒绝提案", "Reject"),
                      false,
                      () -> {
                        dialog.dismiss();
                        background(
                            () -> {
                              broker.reject(id);
                              ui(() -> lastPlans = "");
                            });
                      });
                }
                dialog.show();
              });
        });
  }

  private void renderPlans(JSONArray rows) {
    proposals.removeAllViews();
    TextView title = page.text(t("修复提案", "Repair proposals"), 17, R.color.text);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    proposals.addView(title);
    if (rows.length() == 0) {
      proposals.addView(
          page.text(
              t(
                  "应急 DSH 提出方案后在此查看和确认。尚未确认的提案不会修改正式数据。",
                  "Review and confirm proposals here after emergency DSH creates them. Unconfirmed proposals do not change regular data."),
              14,
              R.color.text_secondary));
      return;
    }
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.optJSONObject(i);
      if (row == null) continue;
      String id = row.optString("id");
      Button button =
          page.button(
              proposals,
              row.optString("kind")
                  + " · "
                  + row.optString("targetId")
                  + "\n"
                  + statusLabel(row.optString("status")),
              false,
              () -> preview(id));
      button.setEnabled(!applying);
      String reason = row.optString("reason");
      if (!reason.isEmpty()) proposals.addView(page.text(reason, 14, R.color.text_secondary));
    }
  }

  private String statusLabel(String value) {
    return switch (value) {
      case "PENDING" -> t("待确认", "Awaiting confirmation");
      case "APPLYING" -> t("正在执行", "Applying");
      case "APPLIED" -> t("已执行并核验", "Applied and verified");
      case "REJECTED" -> t("已拒绝", "Rejected");
      case "EXPIRED" -> t("已失效", "Expired");
      case "FAILED" -> t("未完成", "Incomplete");
      default -> value;
    };
  }

  private final Runnable refresh =
      new Runnable() {
        public void run() {
          if (!resumed || isFinishing() || isDestroyed()) return;
          var state = controller.snapshot();
          boolean nativeRepair = RecoveryRepairBroker.activeNativeRepairs() > 0;
          String key =
              state.state
                  + ":"
                  + state.detail
                  + ":"
                  + state.generation
                  + ":"
                  + applying
                  + ":"
                  + nativeRepair
                  + ":"
                  + UiText.language();
          if (!key.equals(lastState)) {
            lastState = key;
            status.setText(
                UiText.format(
                    "%s%s",
                    RecoveryStatusText.render(state.state, state.detail),
                    state.errorCode.isEmpty() ? "" : "\n" + state.errorCode));
            start.setEnabled(!state.busy && !state.ready && !applying && !nativeRepair);
            open.setEnabled(state.ready);
            stop.setEnabled((state.ready || state.busy) && !applying && !nativeRepair);
            temporaryKey.setEnabled(!state.busy && !state.ready && !nativeRepair);
          }
          RecoveryRepairBroker broker = controller.broker();
          long now = SystemClock.elapsedRealtime();
          if (broker == null && !lastPlans.isEmpty()) {
            lastPlans = "";
            renderPlans(new JSONArray());
          }
          if (broker != null && now - lastPoll > 2000 && reading.compareAndSet(false, true)) {
            lastPoll = now;
            new Thread(
                    () -> {
                      try {
                        JSONArray rows = broker.plans();
                        String serial = rows.toString();
                        ui(
                            () -> {
                              if (broker == controller.broker() && !serial.equals(lastPlans)) {
                                lastPlans = serial;
                                renderPlans(rows);
                              }
                            });
                      } catch (Exception error) {
                        message(error);
                      } finally {
                        reading.set(false);
                      }
                    },
                    "emergency-proposal-list")
                .start();
          }
          main.removeCallbacks(this);
          main.postDelayed(this, 700);
        }
      };

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    main.post(refresh);
  }

  @Override
  protected void onPause() {
    resumed = false;
    main.removeCallbacks(refresh);
    super.onPause();
  }
}
