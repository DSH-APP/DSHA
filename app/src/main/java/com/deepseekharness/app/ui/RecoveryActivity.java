package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.recovery.*;
import com.deepseekharness.app.util.RecoveryStatusText;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** 无正式环境门禁的恢复入口；原生页面负责用户确认，应急网页没有确认能力。 */
public final class RecoveryActivity extends AppCompatActivity {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicBoolean reading = new AtomicBoolean();
  private final List<String> repairLabels = new ArrayList<>(),
      repairIds = new ArrayList<>(),
      repairActions = new ArrayList<>();
  private CardPage page;
  private TextView status, operation;
  private EditText temporaryKey;
  private Button start, open, stop, temporaryKeyToggle, toolsToggle;
  private LinearLayout temporaryKeyBox, toolsBox, proposals;
  private RecoveryRepairSheet repairSheet;
  private RecoveryController controller;
  private String lastState = "", lastPlans = "";
  private long lastPoll;
  private boolean applying, resumed, temporaryKeyOpen, toolsOpen;

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    controller = RecoveryController.get(this);
    String title = t("应急 DSH", "Emergency DSH");
    String summary =
        t(
            "维护失败时，从这里启动独立的应急 DSH。",
            "Start a separate emergency DSH when regular maintenance fails.");
    String detail =
        t(
            "正式环境维护失败时，可在这里启动独立的空白 DSH。它可以读取相关故障文件并向当前模型服务发送内容，提出修复方案；每次正式数据写入都需在此确认。",
            "Start a separate, empty DSH when the regular environment fails. It can read affected files and send their contents to your model service to propose repairs. Confirm each write to regular data here.");
    page = new CardPage(this, title, summary);
    setContentView(page.root);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    // 首屏只留一行结论；可读取故障文件、外发模型与逐次确认写入的边界收进 ⓘ。
    InfoHint.bind(page.subtitle, title, summary, detail);
    repairSheet =
        new RecoveryRepairSheet(
            this,
            new RecoveryRepairSheet.Listener() {
              public void onChoice(int index) {
                proposeRepair(index);
              }

              public void onConfirm(String proposalId) {
                confirmRepair(proposalId);
              }

              public void onReject(String proposalId) {
                rejectRepair(proposalId);
              }
            });

    LinearLayout startCard = page.card();
    status = page.text("", 15, R.color.text);
    status.setTextIsSelectable(true);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    startCard.addView(status);
    start =
        page.button(
            startCard,
            t("启动应急 DSH", "Start emergency DSH"),
            true,
            () -> {
              try {
                String key = temporaryKey.getText().toString();
                RecoveryService.start(this, key.isEmpty() ? null : key);
                temporaryKey.setText("");
                UiMotion.crossFadeText(status, t("正在准备应急服务…", "Preparing the emergency service…"));
              } catch (Exception error) {
                message(error);
              }
              main.postDelayed(refresh, 250);
            });
    open =
        page.button(
            startCard,
            t("进入空白应急 Web", "Open emergency Web"),
            true,
            () -> startActivity(new Intent(this, RecoveryWebActivity.class)));
    stop =
        page.button(
            startCard,
            t("停止应急 DSH", "Stop emergency DSH"),
            false,
            () -> {
              controller.stop();
              main.post(refresh);
            });
    temporaryKeyToggle =
        page.button(
            startCard,
            t("临时 API Key（仅本次会话）", "Temporary API key (optional, this emergency session only)"),
            false,
            this::toggleTemporaryKey);
    temporaryKeyBox = page.column();
    temporaryKeyBox.setVisibility(View.GONE);
    startCard.addView(temporaryKeyBox, new LinearLayout.LayoutParams(-1, -2));
    temporaryKey = new EditText(this);
    temporaryKey.setSingleLine(true);
    temporaryKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    temporaryKey.setSaveEnabled(false);
    temporaryKey.setTextSize(15);
    temporaryKey.setHint(t("留空时尝试已有可读凭据", "Leave blank to try an existing readable key"));
    temporaryKeyBox.addView(temporaryKey, new LinearLayout.LayoutParams(-1, -2));
    if (Build.VERSION.SDK_INT >= 26)
      temporaryKey.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    TextView keyNote =
        page.text(
            t(
                "更换密钥前请先停止服务",
                "To change the temporary key, stop emergency DSH, enter the key, then start it again."),
            14,
            R.color.text_secondary);
    keyNote.setPadding(0, page.dp(8), 0, 0);
    temporaryKeyBox.addView(keyNote);

    operation = page.text("", 14, R.color.text_secondary);
    operation.setTextIsSelectable(true);
    operation.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    page.content.addView(operation);

    LinearLayout repairCard = page.card();
    TextView repairTitle = page.text(t("修复提案", "Repair proposals"), 17, R.color.text);
    repairTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    repairCard.addView(repairTitle);
    proposals = page.column();
    repairCard.addView(proposals);
    renderPlans(new JSONArray());
    page.button(
        repairCard, t("查看无需模型的修复选项", "Review repairs without a model"), false, this::repairOptions);

    LinearLayout toolsCard = page.card();
    toolsToggle = page.button(toolsCard, t("更多", "More"), false, this::toggleTools);
    toolsBox = page.column();
    toolsBox.setVisibility(View.GONE);
    toolsCard.addView(toolsBox, new LinearLayout.LayoutParams(-1, -2));
    page.button(
        toolsBox,
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
    page.button(
        toolsBox,
        t("查看并下载错误日志", "View and export error logs"),
        false,
        () -> startActivity(DiagnosticActivity.downloadLogs(this)));
    page.button(
        toolsBox,
        t("备份与保留数据", "Backups and retained data"),
        false,
        () -> startActivity(new Intent(this, NativeDataActivity.class)));
    page.button(
        toolsBox,
        t("返回正式环境恢复页", "Return to regular environment recovery"),
        false,
        () -> startActivity(new Intent(this, ExtractActivity.class).putExtra("review_only", true)));
    page.button(page.footer, t("返回", "Back"), false, this::finish);
  }

  private void toggleTemporaryKey() {
    temporaryKeyOpen = !temporaryKeyOpen;
    temporaryKeyBox.setVisibility(temporaryKeyOpen ? View.VISIBLE : View.GONE);
    temporaryKeyToggle.setText(
        temporaryKeyOpen
            ? t("收起", "Collapse")
            : t("临时 API Key（仅本次会话）", "Temporary API key (optional, this emergency session only)"));
    if (temporaryKeyOpen) UiMotion.reveal(temporaryKeyBox);
  }

  private void toggleTools() {
    toolsOpen = !toolsOpen;
    toolsBox.setVisibility(toolsOpen ? View.VISIBLE : View.GONE);
    toolsToggle.setText(toolsOpen ? t("收起", "Collapse") : t("更多", "More"));
    if (toolsOpen) UiMotion.reveal(toolsBox);
  }

  private void ui(Runnable work) {
    runOnUiThread(
        () -> {
          if (!isFinishing() && !isDestroyed()) work.run();
        });
  }

  private void message(Throwable error) {
    ui(
        () -> {
          String text =
              SensitiveData.redact(
                  error.getMessage() == null
                      ? error.getClass().getSimpleName()
                      : error.getMessage());
          UiMotion.crossFadeText(operation, text);
        });
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

  /** 打开修复选择层；选择与预览共用一个对话框，返回只退一层。 */
  private void repairOptions() {
    RecoveryRepairBroker broker = controller.broker();
    if (broker == null) {
      UiMotion.crossFadeText(
          operation,
          t("请先启动应急服务；无需配置模型。", "Start the emergency service first. A model is not required."));
      return;
    }
    background(
        () -> {
          List<String> labels = new ArrayList<>(),
              ids = new ArrayList<>(),
              actions = new ArrayList<>();
          JSONArray targets = broker.targets();
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
                    case "isolate-plugin-journals" ->
                        t("隔离无法读取的插件日志", "Isolate unreadable plugin journals");
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
              () -> {
                if (broker != controller.broker()) return;
                repairLabels.clear();
                repairLabels.addAll(labels);
                repairIds.clear();
                repairIds.addAll(ids);
                repairActions.clear();
                repairActions.addAll(actions);
                if (labels.isEmpty()) {
                  UiMotion.crossFadeText(operation, t("没有可预览的修复。", "No repairs to preview."));
                  return;
                }
                repairSheet.showChoices(labels.toArray(new String[0]));
              });
        });
  }

  /** 选择一层修复：生成候选后在同一个人对话框里切到预览，不再叠第二层。 */
  private void proposeRepair(int index) {
    RecoveryRepairBroker broker = controller.broker();
    if (broker == null || index < 0 || index >= repairIds.size()) {
      UiMotion.crossFadeText(
          operation,
          t("请先启动应急服务；无需配置模型。", "Start the emergency service first. A model is not required."));
      if (repairSheet.isShowing()) repairSheet.showChoices(repairLabels.toArray(new String[0]));
      return;
    }
    String targetId = repairIds.get(index), action = repairActions.get(index);
    background(
        () -> {
          try {
            JSONObject source = broker.read(targetId);
            JSONObject request =
                new JSONObject()
                    .put("action", action)
                    .put("targetId", targetId)
                    .put("sourceSha256", source.getString("sourceSha256"))
                    .put("dataGeneration", source.getString("dataGeneration"));
            JSONObject proposal = broker.propose(request);
            String id = proposal.optString("id");
            JSONObject preview = broker.preview(id);
            List<String[]> rows = previewRows(preview);
            String blocked = blockedReason(preview);
            boolean pending = proposalPending(preview);
            ui(
                () -> {
                  if (broker != controller.broker()) return;
                  lastPlans = "";
                  if (repairSheet.isShowing())
                    repairSheet.showPreview(id, rows, blocked, pending, true);
                  main.post(refresh);
                });
          } catch (Exception error) {
            message(error);
            // 用户可能在等待期间关掉对话框；只有它还在显示时才重置选择层。
            ui(
                () -> {
                  if (repairSheet.isShowing())
                    repairSheet.showChoices(repairLabels.toArray(new String[0]));
                });
            main.post(refresh);
          }
        });
  }

  /** 从「修复提案」列表直接打开预览；返回关闭这一层，不回到选择层。 */
  private void previewPlan(String id) {
    RecoveryRepairBroker broker = controller.broker();
    if (broker == null || id.isEmpty()) return;
    background(
        () -> {
          try {
            JSONObject preview = broker.preview(id);
            List<String[]> rows = previewRows(preview);
            String blocked = blockedReason(preview);
            boolean pending = proposalPending(preview);
            ui(
                () -> {
                  if (broker == controller.broker())
                    repairSheet.showPreview(id, rows, blocked, pending, false);
                });
          } catch (Exception error) {
            message(error);
          }
        });
  }

  private static List<String[]> previewRows(JSONObject proposal) {
    List<String[]> rows = new ArrayList<>();
    rows.add(new String[] {t("操作", "Operation"), proposal.optString("description")});
    rows.add(new String[] {t("目标", "Target"), proposal.optString("targetId")});
    rows.add(
        new String[] {t("原件 SHA-256", "Original SHA-256"), proposal.optString("sourceSha256")});
    rows.add(new String[] {t("修改前", "Before"), proposal.optString("before")});
    rows.add(new String[] {t("候选修改后", "Proposed after"), proposal.optString("after")});
    return rows;
  }

  private static boolean proposalPending(JSONObject proposal) {
    return "PENDING".equals(proposal.optString("status"))
        && !proposal.optBoolean("writeBlocked")
        && !proposal.optString("id").isEmpty();
  }

  private static String blockedReason(JSONObject proposal) {
    return proposal.optBoolean("writeBlocked") ? proposal.optString("writeBlockedReason") : "";
  }

  private void confirmRepair(String id) {
    RecoveryRepairBroker broker = controller.broker();
    if (broker == null || id.isEmpty()) return;
    applying = true;
    UiMotion.crossFadeText(
        operation,
        t(
            "正在核验停止屏障、源摘要并执行修复…",
            "Verifying stopped processes and source hashes, then applying the repair…"));
    background(
        () -> {
          try {
            JSONObject result = broker.confirm(id);
            ui(() -> UiMotion.crossFadeText(operation, result.optString("message")));
          } finally {
            ui(
                () -> {
                  applying = false;
                  lastPlans = "";
                  main.post(refresh);
                });
          }
        });
  }

  private void rejectRepair(String id) {
    RecoveryRepairBroker broker = controller.broker();
    if (broker == null || id.isEmpty()) return;
    background(
        () -> {
          broker.reject(id);
          ui(() -> lastPlans = "");
        });
  }

  private void renderPlans(JSONArray rows) {
    proposals.removeAllViews();
    if (rows.length() == 0) {
      proposals.addView(
          page.text(
              t(
                  "提案在此确认；未确认不修改数据",
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
              () -> previewPlan(id));
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
            UiMotion.crossFadeText(
                status,
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
