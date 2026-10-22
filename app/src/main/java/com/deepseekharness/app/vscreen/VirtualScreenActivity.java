package com.deepseekharness.app.vscreen;

import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import java.util.concurrent.*;
import org.json.JSONObject;

/** 以预览为主的虚拟屏控制页：单指直接触控、双指缩放，其他操作折叠。 */
public final class VirtualScreenActivity extends AppCompatActivity {
  private VirtualScreenManager manager;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private TextView state, operationResult, detail;
  private VirtualScreenPreviewView preview;
  private EditText packageInput, textInput;
  private View packagePanel, textPanel;
  private ScrollView scroll;
  private View expandedPanel;
  private SeekBar size;
  private boolean busy;
  private volatile boolean visible;
  private long frameSeq = -1;
  private String generation = "";
  private VirtualScreenPreviews.Lease previewLease;
  private volatile long callbackEpoch;
  private final VirtualScreenTouchFeedback touchFeedback = new VirtualScreenTouchFeedback();
  private TouchAttempt activeTouch;
  private NodePanel nodePanel;

  private final class NodePanel {
    final VirtualScreenNativeControls controls;
    final long viewEpoch = callbackEpoch;
    final TextView target = label("", 12), result = label("", 12);
    final LinearLayout nodes = new LinearLayout(VirtualScreenActivity.this);
    androidx.appcompat.app.AlertDialog dialog, actionDialog;
    boolean busy, closed, revoked;
    long revision;
    String targetText = "";

    NodePanel(VirtualScreenNativeControls controls) {
      this.controls = controls;
      nodes.setOrientation(LinearLayout.VERTICAL);
    }

    boolean current() {
      return nodePanel == this
          && !closed
          && visible
          && viewEpoch == callbackEpoch
          && !isFinishing()
          && !isDestroyed();
    }
  }

  private static final class TouchAttempt {
    final VirtualScreenTouchFeedback.Ticket feedback;
    final VirtualScreenPreviewInputGate.Gesture admission;
    final VirtualScreenTouchFeedback.Dispatch dispatch = new VirtualScreenTouchFeedback.Dispatch();

    TouchAttempt(
        VirtualScreenTouchFeedback.Ticket feedback,
        VirtualScreenPreviewInputGate.Gesture admission) {
      this.feedback = feedback;
      this.admission = admission;
    }

    boolean matches(String gen, long seq, String stroke) {
      return feedback.sequence == seq
          && feedback.generation.equals(gen)
          && feedback.stroke.equals(stroke);
    }
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    manager = VirtualScreenManager.from(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(12), dp(10), dp(12), dp(12));
    root.setBackgroundResource(R.color.surface);
    LinearLayout navigation = new LinearLayout(this);
    navigation.addView(
        button(t("返回", "Back"), this::finish), new LinearLayout.LayoutParams(0, dp(40), 1));
    navigation.addView(
        button(t("使用说明", "How to use"), this::showUsage),
        new LinearLayout.LayoutParams(0, dp(40), 1));
    root.addView(navigation);
    state =
        label(
            t(
                "先连接设备通道，再点“创建竖屏”或“创建横屏”。",
                "Connect a device channel, then tap Create portrait or Create landscape."),
            14);
    root.addView(state);
    operationResult = label("", 12);
    root.addView(operationResult);
    JSONObject previousStart = manager.lastStartOutcome();
    if (previousStart != null) operationResult.setText(outcomeText(previousStart));
    detail = label("", 11);
    root.addView(detail);
    preview = new VirtualScreenPreviewView(this);
    preview.listener =
        new VirtualScreenPreviewView.Touch() {
          public void input(
              String gen, long seq, float x1, float y1, float x2, float y2, int ms, boolean tap) {
            run(
                () ->
                    manager.action(
                        tap ? "tap" : "swipe",
                        gen,
                        seq,
                        tap
                            ? "x=" + x2 + "&y=" + y2
                            : "x1=" + x1 + "&y1=" + y1 + "&x2=" + x2 + "&y2=" + y2 + "&ms=" + ms));
          }

          public boolean admit(JSONObject displayed, String stroke) {
            var admission = busy ? null : manager.previews().beginGesture(displayed);
            if (admission == null) {
              touchFeedback.invalidate();
              operationResult.setText(
                  t(
                      "预览或操作正在更新，本次触摸未发送。请看最新画面后重新操作。",
                      "The preview or an operation is updating. This touch was not sent. Observe"
                          + " the latest picture before trying again."));
              return false;
            }
            var ticket =
                touchFeedback.begin(
                    callbackEpoch,
                    manager.epoch(),
                    displayed.optString("generation"),
                    displayed.optLong("frameSeq", -1),
                    stroke);
            activeTouch = new TouchAttempt(ticket, admission);
            operationResult.setText(
                t("正在发送触摸，请等待结果…", "Sending touch. Please wait for the result…"));
            return true;
          }

          public void stream(String gen, long seq, String stroke, int action, float x, float y) {
            TouchAttempt attempt = activeTouch;
            if (attempt == null || !attempt.matches(gen, seq, stroke)) return;
            try {
              worker.execute(() -> dispatchTouch(attempt, action, x, y));
            } catch (RejectedExecutionException unavailable) {
              attempt.dispatch.abandon();
              attempt.admission.close();
              touchReceipt(attempt, action, unknownTouch());
            }
          }
        };
    int initial =
        Math.max(
            280,
            Math.min(
                720,
                (int)
                    (getResources().getDisplayMetrics().heightPixels
                        / getResources().getDisplayMetrics().density
                        * 0.62f)));
    FrameLayout frame = new FrameLayout(this);
    frame.setPadding(0, dp(4), 0, dp(4));
    frame.addView(preview, new FrameLayout.LayoutParams(-1, dp(initial)));
    root.addView(frame, new LinearLayout.LayoutParams(-1, dp(initial + 8)));
    size = new SeekBar(this);
    size.setMax(100);
    size.setProgress(Math.round((initial - 280) * 100f / 440));
    size.setContentDescription(t("调整预览大小", "Adjust preview size"));
    size.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar b, int v, boolean from) {
            int h = 280 + Math.round(v * 4.4f);
            frame.getLayoutParams().height = dp(h + 8);
            frame.requestLayout();
          }

          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}
        });
    root.addView(size, new LinearLayout.LayoutParams(-1, dp(32)));
    LinearLayout orientation = new LinearLayout(this);
    orientation.addView(
        button(t("创建竖屏", "Create portrait"), () -> create("portrait")),
        new LinearLayout.LayoutParams(0, dp(40), 1));
    orientation.addView(
        button(t("创建横屏", "Create landscape"), () -> create("landscape")),
        new LinearLayout.LayoutParams(0, dp(40), 1));
    root.addView(orientation);
    LinearLayout tools = new LinearLayout(this);
    tools.setGravity(Gravity.CENTER);
    tools.addView(
        button(t("应用", "App"), () -> toggle(packagePanel)),
        new LinearLayout.LayoutParams(0, dp(40), 1));
    tools.addView(
        button(t("键盘", "Keyboard"), () -> toggle(textPanel)),
        new LinearLayout.LayoutParams(0, dp(40), 1));
    tools.addView(
        button(t("节点操作", "Node actions"), this::showNodes),
        new LinearLayout.LayoutParams(0, dp(40), 1));
    tools.addView(
        button(t("关闭", "Close"), () -> run(() -> manager.close())),
        new LinearLayout.LayoutParams(0, dp(40), 1));
    root.addView(tools);
    LinearLayout apps = new LinearLayout(this);
    apps.setOrientation(LinearLayout.HORIZONTAL);
    packageInput = edit(t("选择应用后会自动填入包名", "Choose an app to fill its package"));
    apps.addView(packageInput, new LinearLayout.LayoutParams(0, dp(44), 1));
    apps.addView(
        button(t("选择", "Choose"), this::chooseApp), new LinearLayout.LayoutParams(dp(76), dp(44)));
    apps.addView(
        button(
            t("启动", "Launch"),
            () -> {
              String pkg = packageInput.getText().toString().trim();
              run(() -> manager.launch(pkg));
            }),
        new LinearLayout.LayoutParams(dp(76), dp(44)));
    packagePanel = apps;
    apps.setVisibility(View.GONE);
    root.addView(apps);
    LinearLayout keyboard = new LinearLayout(this);
    keyboard.setOrientation(LinearLayout.HORIZONTAL);
    textInput = edit(t("本机键盘输入（中文需无障碍）", "Keyboard input (accessibility for Unicode)"));
    keyboard.addView(textInput, new LinearLayout.LayoutParams(0, dp(44), 1));
    keyboard.addView(
        button(
            t("发送", "Send"),
            () -> {
              String text = textInput.getText().toString(), gen = generation;
              long seq = frameSeq;
              run(() -> manager.type(gen, seq, text));
            }),
        new LinearLayout.LayoutParams(dp(88), dp(44)));
    Button enter =
        button(
            "↵",
            () -> {
              String gen = generation;
              long seq = frameSeq;
              run(() -> manager.action("key", gen, seq, "keycode=66"));
            });
    keyboard.addView(enter, new LinearLayout.LayoutParams(dp(44), dp(44)));
    textPanel = keyboard;
    keyboard.setVisibility(View.GONE);
    root.addView(keyboard);
    if (!VirtualScreenManager.supported(this))
      state.setText(
          com.deepseekharness.app.BuildConfig.LOW_ANDROID
              ? t(
                  "兼容版暂不支持虚拟屏，请使用标准版",
                  "Virtual screen is not available in the Low build yet; use Standard")
              : t("虚拟屏需要 Android 11 及以上", "Requires Android 11 or later"));
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(root);
    scroll.addOnLayoutChangeListener(
        (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
            revealPanel(expandedPanel);
        });
    packageInput.setOnFocusChangeListener(
        (view, focused) -> {
          if (focused) {
            expandedPanel = packagePanel;
            revealPanel(packagePanel);
          }
        });
    textInput.setOnFocusChangeListener(
        (view, focused) -> {
          if (focused) {
            expandedPanel = textPanel;
            revealPanel(textPanel);
          }
        });
    setContentView(scroll);
  }

  private void showUsage() {
    new com.deepseekharness.app.ui.DshaDialogBuilder(this)
        .setTitle(t("虚拟屏开启与使用", "Start and use a virtual screen"))
        .setMessage(
            t(
                "1. 使用标准版，手机系统需为 Android 11 或更高。兼容版暂不支持虚拟屏。\n\n"
                    + "2. 打开“设置 → 设备能力授权”，连接 ADB、Shizuku 或 Root 中的一种通道即可。没有 Root"
                    + " 时，可在系统开发者选项开启“无线调试”，再按 DSHA 的 ADB 配对页提示完成配对与连接。仅打开无线调试开关还不够，请确认通道已连接。\n\n"
                    + "3. 回到“设备能力授权”，点“打开虚拟屏”。在本页点“创建竖屏”或“创建横屏”，等待画面出现。打开此页或开启无障碍，都不会自动创建虚拟屏。\n\n"
                    + "4. 点“应用 → 选择”，选中想操作的应用，再点“启动”，把它打开到虚拟屏。预览里可以点击、滑动；双指用于调整预览。\n\n"
                    + "5. 开启 DSHA"
                    + " 无障碍后，可点“节点操作”，核对实际应用与节点文字，再明确选择点击或输入。输入只在点“设置文字”后提交；每次动作后先看画面，需要继续时手动刷新节点。关闭面板或离开本页会撤销这次节点操作许可。让助手操作时，还要按应用提示确认本次读屏与操作授权，并将操作目标设为虚拟屏。\n\n"
                    + "VSCREEN_NOT_RUNNING 表示虚拟屏还没创建、已经关闭，或连接已断开。先检查设备通道，再点创建按钮；不需要清除数据或重新解压环境。\n\n"
                    + "创建仍失败：到“设置 → 自检与诊断”查看错误记录并导出日志。仅开启无障碍不能解决设备通道或系统兼容问题。\n\n"
                    + "画面持续变化时，预览触摸可能被最新帧检查拒绝；可自行打开“节点操作”，选择系统提供的可操作节点。触摸失败不会自动改用节点，也不会自动重发。系统没有提供节点时不能使用此方式。Android"
                    + " 12 上 ADB/Shizuku 通道通常拿不到系统的受信任显示权限，创建会按非受信任显示进行；若系统仍然拒绝，请改用 Root"
                    + " 通道或无障碍屏幕操作。\n\n"
                    + "不用时点本页“关闭”回收虚拟屏；连接断开或授权被撤销后，需要重新连接并创建。",
                "1. Use Standard on Android 11 or later. Low does not support virtual screens"
                    + " yet.\n\n"
                    + "2. Open Settings → Device capability access and connect one channel: ADB,"
                    + " Shizuku, or Root. Without Root, enable Wireless debugging in the system"
                    + " Developer options, then follow DSHA's ADB pairing page to pair and connect."
                    + " Enabling the system switch alone is not enough; confirm the channel is"
                    + " connected.\n\n"
                    + "3. In Device capability access, tap Open virtual screen. Tap Create portrait"
                    + " or Create landscape here and wait for a picture. Opening this page or"
                    + " enabling accessibility does not create a virtual screen automatically.\n\n"
                    + "4. Tap App → Choose, select an app, then tap Launch to open it on the"
                    + " virtual screen. Tap and swipe in the preview; use two fingers to adjust the"
                    + " preview.\n\n"
                    + "5. Enable DSHA accessibility, then open Node actions. Check the actual app"
                    + " and node label before choosing Click or Enter text. Text is submitted only"
                    + " when you tap Set text. Observe the picture after each action and refresh"
                    + " nodes yourself before continuing. Closing the panel or leaving this page"
                    + " revokes this node session. For assistant control, also confirm this run's"
                    + " screen access when prompted and choose the virtual screen as the action"
                    + " target.\n\n"
                    + "VSCREEN_NOT_RUNNING means no virtual screen has been created, it was closed,"
                    + " or its connection was lost. Check the device channel, then tap a create"
                    + " button. You do not need to clear data or extract the environment again.\n\n"
                    + "If creation still fails, open Settings → Diagnostics to inspect the error"
                    + " record and export logs. Accessibility alone cannot resolve a missing device"
                    + " channel or system compatibility problem.\n\n"
                    + "On a changing screen, preview touches may be refused by the latest-frame"
                    + " check. You can explicitly open Node actions and choose an actionable node"
                    + " supplied by the system. Failed touches never switch to nodes or resend"
                    + " automatically. This method is unavailable when the system provides no"
                    + " nodes. On Android 12 the ADB/Shizuku channel usually cannot hold the"
                    + " trusted-display permission, so creation falls back to a non-trusted"
                    + " display; if the system still refuses, use the Root channel or accessibility"
                    + " screen actions.\n\n"
                    + "Tap Close when finished. After a disconnection or revoked access, reconnect"
                    + " and create the virtual screen again."))
        .setPositiveButton(t("知道了", "Got it"), null)
        .show();
  }

  private void toggle(View view) {
    boolean opening = view.getVisibility() != View.VISIBLE;
    view.setVisibility(opening ? View.VISIBLE : View.GONE);
    if (opening) {
      expandedPanel = view;
      revealPanel(view);
    } else if (expandedPanel == view) expandedPanel = null;
  }

  /** Expanding a row or resizing for the IME must reveal its controls, never submit them. */
  private void revealPanel(View panel) {
    if (scroll == null || panel == null) return;
    scroll.post(
        () -> {
          if (isDestroyed() || panel.getVisibility() != View.VISIBLE || expandedPanel != panel)
            return;
          panel.requestRectangleOnScreen(
              new android.graphics.Rect(0, 0, panel.getWidth(), panel.getHeight()), true);
        });
  }

  private void chooseApp() {
    long current = callbackEpoch;
    worker.execute(
        () -> {
          var intent =
              new android.content.Intent(android.content.Intent.ACTION_MAIN)
                  .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
          var apps = getPackageManager().queryIntentActivities(intent, 0);
          apps.sort(
              java.util.Comparator.comparing(x -> x.loadLabel(getPackageManager()).toString()));
          String[] labels = new String[apps.size()];
          for (int i = 0; i < apps.size(); i++)
            labels[i] =
                apps.get(i).loadLabel(getPackageManager())
                    + "\n"
                    + apps.get(i).activityInfo.packageName;
          main.post(
              () -> {
                if (!visible || isDestroyed() || current != callbackEpoch) return;
                new com.deepseekharness.app.ui.DshaDialogBuilder(this)
                    .setTitle(t("选择已安装应用", "Choose installed app"))
                    .setItems(
                        labels,
                        (d, i) -> packageInput.setText(apps.get(i).activityInfo.packageName))
                    .setNegativeButton(t("取消", "Cancel"), null)
                    .show();
              });
        });
  }

  private void showNodes() {
    if (busy || !visible) return;
    preview.cancelStream();
    showNodePanel(manager.openNativeControls(this));
  }

  /** A user opens this separate owner explicitly; failed coordinate input never calls it. */
  private void showNodePanel(VirtualScreenNativeControls controls) {
    closeNodePanel();
    NodePanel panel = new NodePanel(controls);
    nodePanel = panel;
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(12), 0, dp(12), 0);
    body.addView(panel.target);
    body.addView(panel.result);
    ScrollView list = new ScrollView(this);
    list.addView(panel.nodes);
    int height = Math.min(dp(320), getResources().getDisplayMetrics().heightPixels * 2 / 5);
    body.addView(list, new LinearLayout.LayoutParams(-1, height));
    panel.dialog =
        new com.deepseekharness.app.ui.DshaDialogBuilder(this)
            .setTitle(t("节点操作", "Node actions"))
            .setView(body)
            .setNeutralButton(t("刷新节点", "Refresh nodes"), null)
            .setNegativeButton(t("关闭", "Close"), null)
            .create();
    panel.dialog.setOnDismissListener(ignored -> closeNodePanel(panel));
    panel.dialog.show();
    panel
        .dialog
        .getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
        .setOnClickListener(ignored -> refreshNodes(panel));
    refreshNodes(panel);
  }

  private void closeNodePanel() {
    closeNodePanel(nodePanel);
  }

  private void closeNodePanel(NodePanel panel) {
    if (panel == null || panel.closed) return;
    panel.closed = true;
    panel.revision++;
    if (nodePanel == panel) nodePanel = null;
    panel.controls.close();
    if (panel.actionDialog != null) panel.actionDialog.dismiss();
    if (panel.dialog != null) panel.dialog.dismiss();
  }

  private void nodeBusy(NodePanel panel, boolean busy) {
    panel.busy = busy;
    panel
        .dialog
        .getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
        .setEnabled(!busy && !panel.revoked);
  }

  private void refreshNodes(NodePanel panel) {
    if (!panel.current() || panel.busy || panel.revoked) return;
    if (panel.actionDialog != null) {
      panel.actionDialog.dismiss();
      panel.actionDialog = null;
    }
    long revision = ++panel.revision;
    panel.nodes.removeAllViews();
    panel.targetText = "";
    panel.target.setText("");
    panel.result.setText(t("正在读取节点…", "Reading nodes…"));
    nodeBusy(panel, true);
    try {
      worker.execute(
          () -> {
            JSONObject result;
            try {
              result = panel.controls.tree();
            } catch (Throwable unknown) {
              result = unknownTouch();
            }
            JSONObject out = result;
            main.post(
                () -> {
                  if (!panel.current() || revision != panel.revision) return;
                  nodeBusy(panel, false);
                  if (out == null || !out.optBoolean("ok") || unknownNodeResult(out)) {
                    nodeFailure(panel, out);
                    return;
                  }
                  // Display only the backend's verified target; packageInput is a launch draft.
                  if (out.optString("packageName").isBlank()
                      || out.optString("coreInstance").isBlank()
                      || out.optString("generation").isBlank()
                      || out.optInt("displayId", -1) <= 0
                      || out.optInt("windowId", -1) < 0) {
                    panel.result.setText(
                        t(
                            "当前节点目标无法核验，请关闭后检查虚拟屏。",
                            "The node target could not be verified. Close this panel and check the"
                                + " virtual screen."));
                    return;
                  }
                  panel.targetText =
                      out.optString("packageName")
                          + " · "
                          + t("虚拟显示 ", "Virtual display ")
                          + out.optInt("displayId");
                  panel.target.setText(panel.targetText);
                  var nodes = out.optJSONArray("nodes");
                  int count = 0;
                  if (nodes != null)
                    for (int i = 0; i < nodes.length(); i++) {
                      JSONObject node = nodes.optJSONObject(i);
                      if (node == null
                          || !node.optBoolean("enabled")
                          || node.optString("nodeId").isBlank()) continue;
                      boolean click = node.optBoolean("clickable");
                      boolean edit = editableNode(node);
                      if (!click && !edit) continue;
                      Button row =
                          button(
                              nodeLabel(node)
                                  + " · "
                                  + (click && edit
                                      ? t("点击 / 输入", "Click / text")
                                      : click ? t("点击", "Click") : t("输入", "Text")),
                              () -> chooseNode(panel, node, revision));
                      row.setSingleLine(false);
                      row.setMaxLines(3);
                      row.setMinHeight(dp(52));
                      panel.nodes.addView(row, new LinearLayout.LayoutParams(-1, -2));
                      count++;
                    }
                  panel.result.setText(
                      count == 0
                          ? t(
                              "系统未提供可点击或可编辑节点。",
                              "The system provided no clickable or editable nodes.")
                          : t(
                              "核对目标后选择节点；不会自动执行。",
                              "Check the target, then choose a node. No action runs"
                                  + " automatically."));
                });
          });
    } catch (RejectedExecutionException unavailable) {
      nodeBusy(panel, false);
      panel.result.setText(t("页面已关闭，请重新打开节点操作。", "This page closed. Open Node actions again."));
      panel.controls.close();
      panel.revoked = true;
      nodeBusy(panel, false);
    }
  }

  private static boolean editableNode(JSONObject node) {
    return node.optBoolean("editable")
        && node.optBoolean("setTextSupported")
        && !node.optBoolean("password");
  }

  private static String nodeLabel(JSONObject node) {
    String text = node.optString("text").trim(), description = node.optString("description").trim();
    if (text.isEmpty())
      return description.isEmpty() ? t("未标注控件", "Unlabelled control") : description;
    return description.isEmpty() || description.equals(text) ? text : text + " · " + description;
  }

  private void chooseNode(NodePanel panel, JSONObject node, long revision) {
    if (!panel.current()
        || panel.busy
        || panel.revoked
        || revision != panel.revision
        || panel.actionDialog != null && panel.actionDialog.isShowing()) return;
    var builder =
        new com.deepseekharness.app.ui.DshaDialogBuilder(this)
            .setTitle(nodeLabel(node))
            .setMessage(panel.targetText)
            .setNegativeButton(t("取消", "Cancel"), (dialog, which) -> closeNodePanel(panel));
    if (node.optBoolean("clickable"))
      builder.setPositiveButton(
          t("点击", "Click"), (dialog, which) -> submitNode(panel, node, revision, "click", ""));
    if (editableNode(node))
      builder.setNeutralButton(
          t("输入文字", "Enter text"), (dialog, which) -> editNode(panel, node, revision));
    panel.actionDialog = builder.create();
    panel.actionDialog.setOnCancelListener(ignored -> closeNodePanel(panel));
    panel.actionDialog.show();
  }

  private void editNode(NodePanel panel, JSONObject node, long revision) {
    if (!panel.current() || panel.busy || panel.revoked || revision != panel.revision) return;
    boolean truncated = node.optBoolean("textTruncated");
    EditText input = edit(t("设置此节点的文字", "Set this node's text"));
    input.setSingleLine(false);
    input.setMaxLines(4);
    input.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(16000)});
    input.setText(truncated ? "" : node.optString("text"));
    input.setSelectAllOnFocus(true);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(12), 0, dp(12), 0);
    TextView target = new TextView(this);
    target.setText(panel.targetText + "\n" + nodeLabel(node));
    body.addView(target);
    if (truncated) {
      TextView explanation = new TextView(this);
      explanation.setText(
          t(
              "原文字较长，未载入完整内容。请输入完整的新文字；提交会替换此字段的全部原文字。未输入时不能提交。",
              "The existing text is long and has not been loaded in full. Enter the complete replacement; submitting replaces all existing text in this field. Enter text before submitting."));
      body.addView(explanation);
      input.setHint(t("输入完整替换内容", "Enter the complete replacement"));
    }
    body.addView(input, new LinearLayout.LayoutParams(-1, -2));
    panel.actionDialog =
        new com.deepseekharness.app.ui.DshaDialogBuilder(this)
            .setTitle(t("输入到所选节点", "Enter text in selected node"))
            .setView(body)
            .setPositiveButton(
                truncated ? t("完整替换", "Replace all text") : t("设置文字", "Set text"),
                (dialog, which) -> {
                  if (!truncated || input.length() > 0)
                    submitNode(panel, node, revision, "set_text", input.getText().toString());
                })
            .setNegativeButton(t("取消", "Cancel"), (dialog, which) -> closeNodePanel(panel))
            .create();
    panel.actionDialog.setOnCancelListener(ignored -> closeNodePanel(panel));
    panel.actionDialog.show();
    if (truncated) {
      Button submit = panel.actionDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
      submit.setEnabled(false);
      input.addTextChangedListener(
          new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            public void afterTextChanged(android.text.Editable text) {
              submit.setEnabled(
                  text.length() > 0 && panel.current() && !panel.busy && !panel.revoked);
            }
          });
    }
    panel
        .actionDialog
        .getWindow()
        .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    input.requestFocus();
  }

  private void submitNode(
      NodePanel panel, JSONObject node, long revision, String action, String text) {
    if (!panel.current() || panel.busy || panel.revoked || revision != panel.revision) return;
    // Consume the rendered list before queuing. Neither double-clicks nor late callbacks replay it.
    ++panel.revision;
    panel.nodes.removeAllViews();
    nodeBusy(panel, true);
    panel.result.setText(t("正在操作所选节点…", "Acting on the selected node…"));
    try {
      worker.execute(
          () -> {
            JSONObject result;
            try {
              result = panel.controls.node(node.optString("nodeId"), action, text);
            } catch (Throwable unknown) {
              result = unknownTouch();
            }
            JSONObject out = result;
            main.post(
                () -> {
                  if (!panel.current()) return;
                  nodeBusy(panel, false);
                  if (out == null || !out.optBoolean("ok") || unknownNodeResult(out))
                    nodeFailure(panel, out);
                  else {
                    String message =
                        t(
                            "节点操作已提交，请确认实际画面。需要继续时手动刷新节点。",
                            "Node action submitted. Check the actual picture; refresh nodes"
                                + " yourself to continue.");
                    panel.result.setText(message);
                    operationResult.setText(message);
                  }
                });
          });
    } catch (RejectedExecutionException unavailable) {
      nodeBusy(panel, false);
      nodeFailure(panel, unknownTouch());
    }
  }

  private static boolean unknownNodeResult(JSONObject out) {
    return out == null
        || out.optBoolean("executionUnknown")
        || out.optString("error").contains("UNKNOWN");
  }

  private void nodeFailure(NodePanel panel, JSONObject out) {
    panel.nodes.removeAllViews();
    String message;
    if (unknownNodeResult(out)) {
      panel.revoked = true;
      panel.controls.close();
      nodeBusy(panel, false);
      message =
          t(
              "结果未知，请查看实际画面；需要继续时重新打开节点操作。不会自动重试。",
              "Result unknown. Check the actual picture; open Node actions again if needed. No"
                  + " automatic retry.");
    } else if ("ACCESSIBILITY_UNAVAILABLE".equals(out.optString("error"))) {
      panel.revoked = true;
      panel.controls.close();
      nodeBusy(panel, false);
      message =
          t(
              "DSHA 无障碍当前不可用；请检查授权和虚拟屏，恢复后关闭并重新打开节点操作。",
              "DSHA accessibility is unavailable. Check access and the virtual screen, then close"
                  + " and reopen Node actions after restoring access.");
    } else if ("VIRTUAL_WINDOW_UNAVAILABLE".equals(out.optString("error"))) {
      panel.revoked = true;
      panel.controls.close();
      nodeBusy(panel, false);
      message =
          t(
              "系统未提供当前虚拟屏窗口；请检查应用画面后关闭并重新打开节点操作。",
              "The system did not provide the virtual window. Check the app picture, then close and"
                  + " reopen Node actions.");
    } else if ("NATIVE_CONTROL_REVOKED".equals(out.optString("error"))
        || "SCREEN_TARGET_CHANGED".equals(out.optString("error"))) {
      panel.revoked = true;
      panel.controls.close();
      nodeBusy(panel, false);
      message =
          t(
              "节点操作许可已失效；请检查实际画面，需要继续时重新打开节点操作。",
              "This node session expired. Check the actual picture, then open Node actions again if"
                  + " needed.");
    } else if ("STALE_TREE".equals(out.optString("error"))
        || "STALE_NODE".equals(out.optString("error"))) {
      message =
          t(
              "节点列表或所选控件已变化；请核对实际画面，需要继续时手动刷新节点。不会自动重发。",
              "The node list or selected control changed. Check the actual picture and refresh"
                  + " nodes yourself if needed. No automatic resend.");
    } else if ("NATIVE_NODE_ACTION_UNAVAILABLE".equals(out.optString("error"))
        || "NODE_ACTION_REJECTED".equals(out.optString("error"))) {
      message =
          t(
              "此控件不支持或拒绝了当前操作。请检查实际画面，不会自动改用其他方式。",
              "This control does not support or refused this action. Check the actual picture. No"
                  + " automatic fallback.");
    } else message = outcomeText(out);
    if (out != null && !out.optString("error").isBlank()) {
      message += "\n" + out.optString("error");
      if (!out.optString("reason").isBlank()) message += " · " + out.optString("reason");
      if (!out.optString("detail").isBlank()) message += " · " + out.optString("detail");
    }
    panel.result.setText(message);
    operationResult.setText(message);
  }

  private EditText edit(String hint) {
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setTextSize(12);
    e.setHint(hint);
    e.setMinHeight(dp(44));
    e.setPadding(dp(8), 0, dp(8), 0);
    return e;
  }

  private TextView label(String text, int sp) {
    TextView v = new TextView(this);
    v.setText(text);
    v.setTextSize(sp);
    v.setTextColor(getColor(R.color.text));
    v.setPadding(0, dp(3), 0, dp(3));
    // Status changes cannot move the preview under the user's next finger coordinate.
    v.setLines(1);
    v.setEllipsize(android.text.TextUtils.TruncateAt.END);
    v.setOnClickListener(
        view -> {
          if (v.getText().length() == 0) return;
          new com.deepseekharness.app.ui.DshaDialogBuilder(this)
              .setTitle(t("状态详情", "Status details"))
              .setMessage(v.getText())
              .setPositiveButton(t("关闭", "Close"), null)
              .show();
        });
    return v;
  }

  private Button button(String text, Runnable click) {
    Button b = new androidx.appcompat.widget.AppCompatButton(this);
    b.setText(text);
    b.setAllCaps(false);
    b.setTextSize(12);
    b.setMinHeight(dp(40));
    b.setPadding(dp(5), 0, dp(5), 0);
    b.setBackgroundResource(R.drawable.bg_btn);
    b.setTextColor(getColorStateList(R.color.button_text));
    b.setOnClickListener(v -> click.run());
    return b;
  }

  private void create(String orientation) {
    run(() -> manager.start(orientation));
  }

  private interface Work {
    JSONObject get() throws Exception;
  }

  private static JSONObject unknownTouch() {
    JSONObject value = new JSONObject();
    try {
      value.put("error", "INPUT_RESULT_UNKNOWN").put("executionUnknown", true);
    } catch (Exception ignored) {
    }
    return value;
  }

  private void dispatchTouch(TouchAttempt attempt, int action, float x, float y) {
    var ticket = attempt.feedback;
    if (!attempt.dispatch.allowed()) return;
    if (ticket.runEpoch != manager.epoch()) {
      attempt.dispatch.abandon();
      attempt.admission.close();
      return;
    }
    JSONObject result;
    try {
      result = manager.touch(ticket.generation, ticket.sequence, ticket.stroke, action, x, y);
    } catch (Throwable unknown) {
      result = unknownTouch();
    }
    boolean ok =
        result != null && result.optBoolean("ok") && !result.optBoolean("executionUnknown");
    boolean cancel = attempt.dispatch.complete(action, ok);
    try {
      // Cleanup uses only the original stroke; neither UP nor a discrete tap is replayed.
      if (cancel && ticket.runEpoch == manager.epoch())
        try {
          manager.touch(
              ticket.generation, ticket.sequence, ticket.stroke, MotionEvent.ACTION_CANCEL, x, y);
        } catch (Throwable unknownCleanup) {
        }
    } finally {
      if (attempt.dispatch.done()) attempt.admission.close();
    }
    touchReceipt(attempt, action, result);
  }

  private void touchReceipt(TouchAttempt attempt, int action, JSONObject out) {
    main.post(
        () -> {
          var ticket = attempt.feedback;
          if (isDestroyed() || !visible || ticket.viewEpoch != callbackEpoch) return;
          boolean ok = out != null && out.optBoolean("ok") && !out.optBoolean("executionUnknown");
          var update =
              touchFeedback.receipt(
                  ticket,
                  callbackEpoch,
                  manager.epoch(),
                  generation,
                  ok,
                  action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL);
          if (update == VirtualScreenTouchFeedback.Update.FAILED)
            operationResult.setText(touchOutcomeText(out));
          else if (update == VirtualScreenTouchFeedback.Update.COMPLETED)
            operationResult.setText(
                action == MotionEvent.ACTION_CANCEL
                    ? t("触摸已取消，请确认实际画面", "Touch cancelled. Check the actual picture.")
                    : t("触摸已提交，请确认画面变化", "Touch submitted. Check the picture for its effect."));
        });
  }

  private void run(Work action) {
    if (busy) return;
    busy = true;
    preview.cancelStream();
    long outcomeRevision = touchFeedback.invalidate();
    operationResult.setText(t("正在处理，请等待结果…", "Working. Please wait for the result…"));
    long current = callbackEpoch;
    long session = manager.epoch();
    worker.execute(
        () -> {
          JSONObject result;
          try {
            if (current != callbackEpoch || session != manager.epoch()) {
              main.post(() -> busy = false);
              return;
            }
            result = action.get();
          } catch (Exception error) {
            result = new JSONObject();
            try {
              result.put("error", com.deepseekharness.app.util.VirtualScreenErrors.stable(error));
              result.put("cause", com.deepseekharness.app.util.VirtualScreenFailures.cause(error));
            } catch (Exception ignored) {
            }
          }
          JSONObject out = result;
          main.post(
              () -> {
                busy = false;
                if (isDestroyed()
                    || !visible
                    || current != callbackEpoch
                    || outcomeRevision != touchFeedback.revision()) return;
                operationResult.setText(outcomeText(out));
                if (manager.generation().isEmpty()) preview.clear();
              });
        });
  }

  private void beginPreview() {
    VirtualScreenPreviews.assertMain();
    if (previewLease != null) previewLease.close();
    long current = callbackEpoch;
    previewLease =
        manager
            .previews()
            .subscribe(
                this,
                frame -> {
                  if (isDestroyed() || !visible || current != callbackEpoch) {
                    frame.release();
                    return;
                  }
                  if (frame.bitmap != null) {
                    if (!busy) state.setText(t("虚拟屏已开启", "Virtual screen ready"));
                    if (!preview.frame(frame.bitmap, frame.value)) return;
                    frame.adopted();
                    generation = frame.value.optString("generation");
                    touchFeedback.generation(generation);
                    frameSeq = frame.value.optLong("frameSeq");
                    detail.setText(
                        frame.value.optString("package")
                            + " · "
                            + frame.value.optInt("width")
                            + "×"
                            + frame.value.optInt("height")
                            + " · "
                            + manager.channel());
                  } else if (!frame.value.optBoolean("ok")) {
                    String code = frame.value.optString("error");
                    boolean retained = preview.unavailable(code, manager.generation());
                    if (!busy && retained) state.setText(t("画面更新中…", "Updating picture…"));
                    else if (!busy && !code.isEmpty())
                      state.setText(
                          message(code, frame.value.optString("reason")) + cause(frame.value));
                    if (!retained) detail.setText("");
                    generation = "";
                    touchFeedback.generation(generation);
                    frameSeq = -1;
                  }
                });
  }

  private static String cause(JSONObject value) {
    String cause = value.optString("cause").trim();
    return cause.isEmpty() ? "" : " · " + cause;
  }

  static String touchOutcomeText(JSONObject value) {
    try {
      if (value == null) return message("INPUT_RESULT_UNKNOWN", "");
      JSONObject safe = VirtualScreenManager.safeStartOutcome(value);
      if (value != null && value.optBoolean("executionUnknown"))
        safe.put("ok", false).put("error", "INPUT_RESULT_UNKNOWN");
      String text = outcomeText(safe);
      if ("STALE_FRAME".equals(safe.optString("error")))
        text +=
            " · requested="
                + value.optLong("requestedFrameSeq", -1)
                + " current="
                + value.optLong("currentFrameSeq", -1)
                + " observed="
                + value.optLong("observedFrameSeq", -1);
      return text;
    } catch (Exception unavailable) {
      return message("INPUT_RESULT_UNKNOWN", "");
    }
  }

  /** 操作结果独立于轮询状态：后台的“未开启”不能抹掉本次失败原因。 */
  static String outcomeText(JSONObject value) {
    if (!value.optBoolean("ok")
        && value.optBoolean("cleanupPending")
        && value.has("cleanupIdentityAvailable")) {
      if (!value.optBoolean("cleanupIdentityAvailable"))
        return t(
            "启动结果未知，完整核心出生身份未记录，不能安全核验退出或重新创建。记录仍保留；若启动回包完全丢失，需真实重启手机后再核验。",
            "The launch result is unknown and its full core birth identity was not captured. Exit"
                + " cannot be safely verified and another screen cannot be created. The record is"
                + " retained; if the launch reply was lost, restart the phone before checking"
                + " again.");
      String channel =
          switch (value.optString("cleanupChannel")) {
            case "root" -> "Root";
            case "shizuku" -> "Shizuku";
            case "adb" -> "ADB";
            default -> t("原通道", "original channel");
          };
      if ("ORIGINAL_CHANNEL_UNAVAILABLE".equals(value.optString("cleanupProbeReason")))
        return t("已保存完整核心身份，但原通道 ", "The full core identity is retained, but ")
            + channel
            + t(
                " 当前不可用。恢复该通道后点“关闭”核验；尚未确认退出。",
                " is unavailable. Restore that channel and tap Close to verify exit; exit is still"
                    + " unconfirmed.");
      if ("VSCREEN_JOURNAL_UNAVAILABLE".equals(value.optString("reason")))
        return t(
            "已核验核心退出，但清理终态尚未安全保存。记录仍保留；恢复存储后点“关闭”重新核验。",
            "Core exit was verified, but its terminal cleanup record could not be saved. The record"
                + " is retained; restore storage and tap Close to verify again.");
    }
    return value.optBoolean("ok")
        ? t("操作完成", "Done")
        : message(value.optString("error"), value.optString("reason")) + cause(value);
  }

  /** 帧门拒绝会带 reason：同一个 STALE_FRAME 有四种成因，自救方式完全不同（issue #117）。 */
  private static String message(String code, String reason) {
    if (code.equals("VSCREEN_START_UNKNOWN"))
      return t(
          "上次启动结果未知，已保留记录，暂不重复启动。若启动回包完全丢失，需真实重启手机后再创建。",
          "The previous launch result is unknown and its record is retained. If the launch reply"
              + " was lost, restart the phone before creating another screen.");
    if (code.equals("VSCREEN_CLOSE_UNKNOWN") || code.equals("VSCREEN_CLOSING"))
      return t(
          "停止请求已发送，尚未确认核心退出。清理记录仍保留；可点“关闭”重新核验。",
          "Stop was requested, but core exit is unconfirmed. The cleanup record is retained; tap"
              + " Close to check again.");
    if (code.equals("VSCREEN_DISCONNECTED"))
      return t(
          "通道已断开，本轮操作许可已失效。重连原通道后点“关闭”核验退出，再创建。",
          "The channel disconnected and this run is invalid. Reconnect the original channel, then"
              + " tap Close to verify exit before creating a screen.");
    if (code.equals("VSCREEN_JOURNAL_UNAVAILABLE") || code.equals("VSCREEN_JOURNAL_INVALID"))
      return t(
          "本机清理记录无法保存或核验，已阻止启动。请保留记录并查看自检与诊断。",
          "The local cleanup record cannot be saved or verified. Launch is blocked; retain the"
              + " record and open diagnostics.");
    if (code.equals("VSCREEN_NOT_RUNNING"))
      return t(
          "虚拟屏尚未开启或已关闭。先连接设备通道，再点“创建竖屏”或“创建横屏”；开启无障碍不会自动创建。",
          "The virtual screen has not started or was closed. Connect a device channel, then tap"
              + " Create portrait or Create landscape. Accessibility does not create it"
              + " automatically.");
    if (code.equals("STALE_FRAME")) {
      if (reason.equals("FRAME_NOT_OBSERVED"))
        return t(
            "还没看过这一帧：先用 see 取一帧，再用它返回的 frameSeq 输入（status 的帧号不能用于输入）。",
            "This frame was never observed: call see first, then input with the frameSeq it returns"
                + " (the frameSeq from status cannot be used for input).");
      if (reason.equals("FRAME_EXPIRED"))
        return t(
            "画面已被新帧替换，触摸未完成。请看最新画面，也可自行打开“节点操作”选择系统提供的节点；不会自动转换或重发。",
            "A newer frame replaced the view and the touch did not complete. Observe the latest"
                + " picture, or explicitly open Node actions to choose a system-provided node. No"
                + " automatic switch or resend.");
      if (reason.equals("OBSERVATION_TIMEOUT"))
        return t(
            "距上次看画面已超过 30 秒，请重新 see 再输入。",
            "More than 30 seconds passed since the last observed frame; call see again.");
      return t("画面已变化，请看最新画面后再操作", "The view changed. Observe again.");
    }
    if (code.equals("INPUT_RESULT_UNKNOWN") || code.equals("VIRTUAL_INPUT_RESULT_UNKNOWN"))
      return t(
          "输入结果未知：动作可能已执行，请先观察实际画面，不要直接重试。",
          "The input result is unknown: the action may have executed. Observe the actual screen"
              + " before retrying.");
    if (code.equals("STALE_GENERATION"))
      return t("画面已变化，请看最新画面后再操作", "The view changed. Observe again.");
    if (code.equals("DEVICE_CHANNEL_UNAVAILABLE"))
      return t("请先在设备能力授权中连接通道", "Connect a device channel first");
    if (code.equals("ACCESSIBILITY_REQUIRED_FOR_UNICODE"))
      return t("中文输入需要开启无障碍服务", "Enable accessibility for Unicode input");
    if (code.equals("VSCREEN_TRUSTED_DISPLAY_DENIED"))
      return t(
          "这台设备拒绝了受信任显示：当前通道（ADB/Shizuku）没有 ADD_TRUSTED_DISPLAY。请改用 Root"
              + " 通道，或改用无障碍屏幕操作（读屏/点按/输入/截屏）。",
          "This device refused a trusted display: the current channel (ADB/Shizuku) has no"
              + " ADD_TRUSTED_DISPLAY. Use the Root channel, or use accessibility screen actions"
              + " (read, tap, input, screenshot).");
    if (code.equals("VSCREEN_DISPLAY_CREATE_FAILED"))
      return t(
          "系统拒绝了创建虚拟屏。可先用无障碍屏幕操作；仍失败请到“设置 → 自检与诊断”导出日志。",
          "The system refused to create the virtual screen. Use accessibility screen actions for"
              + " now; if it keeps failing, export a log from Settings → Self-check and"
              + " diagnostics.");
    if (code.equals("VSCREEN_PERMISSION_DENIED"))
      return t(
          "设备拒绝了这次虚拟屏操作（权限不足）。请改用 Root 通道，或改用无障碍屏幕操作。",
          "The device refused this virtual-screen operation (permission denied). Use the Root"
              + " channel, or use accessibility screen actions.");
    if (code.equals("VSCREEN_DISPLAY_SERVICE_UNAVAILABLE"))
      return t(
          "系统的显示服务中断了这次调用，请先确认虚拟屏状态再重试。",
          "The system display service interrupted this call; check the virtual screen state before"
              + " retrying.");
    return t("未完成：", "Not completed: ") + code;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  protected void onResume() {
    super.onResume();
    visible = true;
    callbackEpoch++;
    beginPreview();
  }

  @Override
  protected void onPause() {
    closeNodePanel();
    visible = false;
    callbackEpoch++;
    touchFeedback.invalidate();
    if (previewLease != null) {
      previewLease.close();
      previewLease = null;
    }
    if (preview != null) preview.cancelStream();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    closeNodePanel();
    visible = false;
    callbackEpoch++;
    touchFeedback.invalidate();
    if (previewLease != null) {
      previewLease.close();
      previewLease = null;
    }
    main.removeCallbacksAndMessages(null);
    if (preview != null) preview.clear();
    worker.shutdown();
    super.onDestroy();
  }
}
