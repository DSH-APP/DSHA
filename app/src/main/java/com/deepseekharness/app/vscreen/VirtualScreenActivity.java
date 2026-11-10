package com.deepseekharness.app.vscreen;

import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.VirtualScreenDisplayPreset;
import com.deepseekharness.app.util.VirtualScreenLifecycleState;
import java.util.concurrent.*;
import org.json.JSONObject;

/**
 * 虚拟识屏页：按真实状态切换「未开启 / 创建中 / 运行中 / 待清理」四种画面。预览为主，单指直接触控、双指缩放；
 * 操作收在预览下方（横屏在右侧）。生命周期事实仍由 VirtualScreenManager 持有，本页只读并渲染。
 */
public final class VirtualScreenActivity extends AppCompatActivity {
  /** 页面展示状态，只由 Manager 的生命周期事实与本页正在进行的创建推导。 */
  enum ViewState {
    UNSUPPORTED,
    STOPPED,
    STARTING,
    RUNNING,
    CLEANUP
  }

  private VirtualScreenManager manager;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  // 通道探测独立排队：不占用动作队列，也不会被误当成一次用户动作。
  private final ExecutorService probe = Executors.newSingleThreadExecutor();
  private TextView state, operationResult, detail;
  private VirtualScreenPreviewView preview;
  private EditText packageInput, textInput;
  private View packagePanel, textPanel;
  private ScrollView scroll;
  private View expandedPanel;
  private View stage, stageCard, stageProgress, createGroup, toolGroup;
  private View displaySpecRow;
  private TextView displaySpecTitle, displaySpecSub;
  private TextView stageTitle, stageMessage;
  private ImageView stageIcon;
  private ImageButton floatButton;
  private Button createPortrait, createLandscape, verifyClose;
  private Button[] actionButtons = new Button[0];
  private ViewState shown;
  private boolean busy, creating, updating, landscapeFrame;
  private volatile boolean visible;
  private long frameSeq = -1;
  private String generation = "";
  // 最近一次预览/创建失败的完整说明；VSCREEN_NOT_RUNNING 由说明卡本身表达，不重复写入。
  private String stageNote = "";
  // null = 尚未探测；"" = 当前没有可用通道。
  private String channelHint;

  /** 当前识屏模式（只约束助手）；null 表示尚未读取。只读展示，不影响本页操作。 */
  private com.deepseekharness.app.util.ScreenModePolicy.Mode assistantMode;

  // 最近一帧画面里的前台应用；旋转会重建显示，需要把它重新打开到新方向上。
  private String shownPackage = "";
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
      // 面板内的说明需要完整阅读，不沿用页面单行状态的截断规则。
      result.setSingleLine(false);
      result.setMaxLines(6);
      target.setSingleLine(false);
      target.setMaxLines(2);
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

  /** 纯推导，便于单测：待清理优先于一切，运行中优先于创建中。 */
  static ViewState viewState(
      boolean supported, VirtualScreenLifecycleState life, boolean live, boolean creating) {
    if (!supported) return ViewState.UNSUPPORTED;
    if (life != null && life.cleanupPending()) return ViewState.CLEANUP;
    if (live || life == VirtualScreenLifecycleState.READY) return ViewState.RUNNING;
    if (creating || life == VirtualScreenLifecycleState.STARTING) return ViewState.STARTING;
    return ViewState.STOPPED;
  }

  static String channelLabel(String id) {
    return switch (id == null ? "" : id) {
      case "root" -> "Root";
      case "shizuku" -> "Shizuku";
      case "adb" -> "ADB";
      default -> "";
    };
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    manager = VirtualScreenManager.from(this);
    setContentView(R.layout.activity_virtual_screen);
    scroll = findViewById(R.id.vscreen_scroll);
    state = findViewById(R.id.vscreen_state);
    state.setSingleLine(true);
    state.setEllipsize(android.text.TextUtils.TruncateAt.END);
    detail = findViewById(R.id.vscreen_detail);
    Button assistantOpen = findViewById(R.id.vscreen_assistant_open);
    if (assistantOpen != null) {
      assistantOpen.setText(t("识屏模式", "Screen mode"));
      assistantOpen.setContentDescription(
          t("打开设备能力授权页设置识屏模式", "Open device permissions to set the screen mode"));
      assistantOpen.setOnClickListener(
          v ->
              startActivity(
                  com.deepseekharness.app.ui.MainActivity.navigationIntent(this)
                      .putExtra(
                          com.deepseekharness.app.ui.MainActivity.EXTRA_OPEN_DEVICE_GRANTS, true)));
    }
    operationResult = findViewById(R.id.vscreen_result);
    expandable(operationResult);
    expandable(detail);
    stage = findViewById(R.id.vscreen_stage);
    stage.setClipToOutline(true);
    stageCard = findViewById(R.id.vscreen_stage_card);
    stageIcon = findViewById(R.id.vscreen_stage_icon);
    stageTitle = findViewById(R.id.vscreen_stage_title);
    stageMessage = findViewById(R.id.vscreen_stage_message);
    stageProgress = findViewById(R.id.vscreen_stage_progress);
    createGroup = findViewById(R.id.vscreen_create_group);
    toolGroup = findViewById(R.id.vscreen_tool_group);
    displaySpecRow = findViewById(R.id.vscreen_display_spec_row);
    displaySpecTitle = findViewById(R.id.vscreen_display_spec_title);
    displaySpecSub = findViewById(R.id.vscreen_display_spec_sub);
    displaySpecTitle.setText(t("显示规格", "Display size"));
    displaySpecRow.setOnClickListener(v -> chooseDisplayPreset());

    ((TextView) findViewById(R.id.vscreen_title)).setText(t("虚拟识屏", "Virtual screen"));
    ImageButton back = findViewById(R.id.vscreen_back);
    back.setContentDescription(t("返回", "Back"));
    back.setOnClickListener(v -> finish());
    ImageButton help = findViewById(R.id.vscreen_help);
    help.setContentDescription(t("使用说明", "How to use"));
    help.setOnClickListener(v -> showUsage());
    floatButton = findViewById(R.id.vscreen_float);
    floatButton.setOnClickListener(
        v -> {
          manager.overlay().toggle(this);
          render();
        });

    createPortrait =
        bind(R.id.vscreen_create_portrait, t("创建竖屏", "Create portrait"), () -> create("portrait"));
    createLandscape =
        bind(
            R.id.vscreen_create_landscape,
            t("创建横屏", "Create landscape"),
            () -> create("landscape"));
    Button app = bind(R.id.vscreen_tool_app, t("应用", "App"), () -> toggle(packagePanel));
    Button keyboard =
        bind(R.id.vscreen_tool_keyboard, t("键盘", "Keyboard"), () -> toggle(textPanel));
    Button nodes = bind(R.id.vscreen_tool_nodes, t("节点操作", "Node actions"), this::showNodes);
    Button rotate = bind(R.id.vscreen_tool_rotate, t("旋转", "Rotate"), this::rotate);
    rotate.setContentDescription(t("切换竖屏或横屏", "Switch portrait or landscape"));
    Button close =
        bind(R.id.vscreen_tool_close, t("关闭", "Close"), () -> run(() -> manager.close()));
    verifyClose =
        bind(
            R.id.vscreen_verify_close,
            t("核验关闭", "Verify close"),
            () -> {
              // 启动结果未知时“关闭”无法解除屏障（ADR0003）；改为查看本机启动记录，不派发任何设备命令。
              if (manager.lifecycleState() == VirtualScreenLifecycleState.START_UNKNOWN)
                showStartRecords();
              else run(() -> manager.close());
            });
    actionButtons = new Button[] {nodes, rotate, close};

    packagePanel = findViewById(R.id.vscreen_app_panel);
    packageInput = findViewById(R.id.vscreen_package);
    packageInput.setHint(t("选择应用后会自动填入包名", "Choose an app to fill its package"));
    bind(R.id.vscreen_package_choose, t("选择", "Choose"), this::chooseApp);
    bind(
        R.id.vscreen_package_launch,
        t("启动", "Launch"),
        () -> {
          String pkg = packageInput.getText().toString().trim();
          run(() -> manager.launch(pkg));
        });
    textPanel = findViewById(R.id.vscreen_text_panel);
    textInput = findViewById(R.id.vscreen_text);
    textInput.setHint(t("本机键盘输入（中文需无障碍）", "Keyboard input (accessibility for Unicode)"));
    bind(
        R.id.vscreen_text_send,
        t("发送", "Send"),
        () -> {
          String text = textInput.getText().toString(), gen = generation;
          long seq = frameSeq;
          run(() -> manager.type(gen, seq, text));
        });
    Button enter =
        bind(
            R.id.vscreen_text_enter,
            "↵",
            () -> {
              String gen = generation;
              long seq = frameSeq;
              run(() -> manager.action("key", gen, seq, "keycode=66"));
            });
    enter.setContentDescription(t("回车", "Enter"));

    preview = new VirtualScreenPreviewView(this);
    ((ViewGroup) stage).addView(preview, 0, new FrameLayout.LayoutParams(-1, -1));
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

    JSONObject previousStart = manager.lastStartOutcome();
    if (previousStart != null) operationResult.setText(outcomeText(previousStart));
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
    render();
  }

  private Button bind(int id, String text, Runnable click) {
    Button b = findViewById(id);
    b.setText(text);
    b.setAllCaps(false);
    b.setOnClickListener(v -> click.run());
    return b;
  }

  /** 固定行高的文字可能被截断：点按查看完整内容，不改变布局高度。 */
  private void expandable(TextView v) {
    v.setOnClickListener(
        view -> {
          if (v.getText().length() == 0) return;
          new com.deepseekharness.app.ui.DshaDialogBuilder(this)
              .setTitle(t("状态详情", "Status details"))
              .setMessage(v.getText())
              .setPositiveButton(t("关闭", "Close"), null)
              .show();
        });
  }

  /** 后台探测将使用的通道；与创建时的选择同一判据，只读不派发。 */
  private void probeChannel() {
    long current = callbackEpoch;
    android.content.Context app = getApplicationContext();
    try {
      probe.execute(
          () -> {
            String hint;
            try {
              VirtualScreenChannel selected = VirtualScreenChannels.choose(app);
              hint = selected == null ? "" : channelLabel(selected.id());
            } catch (RuntimeException unavailable) {
              hint = "";
            }
            String out = hint;
            com.deepseekharness.app.util.ScreenModePolicy.Mode assistant;
            try {
              assistant = com.deepseekharness.app.screen.ScreenMode.current(app);
            } catch (RuntimeException unavailable) {
              assistant = com.deepseekharness.app.util.ScreenModePolicy.Mode.OFF;
            }
            com.deepseekharness.app.util.ScreenModePolicy.Mode assistantOut = assistant;
            main.post(
                () -> {
                  if (isDestroyed() || current != callbackEpoch) return;
                  channelHint = out;
                  assistantMode = assistantOut;
                  render();
                });
          });
    } catch (RejectedExecutionException closed) {
      // 页面已销毁。
    }
  }

  /** 按当前事实重绘；只在状态切换时收起面板，同一状态内的轮询不打断用户操作。 */
  private void render() {
    if (stage == null || isDestroyed()) return;
    ViewState next =
        viewState(
            VirtualScreenManager.supported(this),
            manager.lifecycleState(),
            !manager.generation().isEmpty(),
            creating);
    boolean framed = next == ViewState.RUNNING && preview.hasFrame();
    stageCard.setVisibility(framed ? View.GONE : View.VISIBLE);
    stageProgress.setVisibility(
        next == ViewState.STARTING || next == ViewState.RUNNING && !framed
            ? View.VISIBLE
            : View.GONE);
    boolean showCreate = next == ViewState.STOPPED || next == ViewState.STARTING;
    createGroup.setVisibility(showCreate ? View.VISIBLE : View.GONE);
    displaySpecRow.setVisibility(showCreate ? View.VISIBLE : View.GONE);
    toolGroup.setVisibility(next == ViewState.RUNNING ? View.VISIBLE : View.GONE);
    verifyClose.setVisibility(next == ViewState.CLEANUP ? View.VISIBLE : View.GONE);
    renderDisplaySpec();
    boolean canCreate = !busy && next == ViewState.STOPPED && !"".equals(channelHint);
    createPortrait.setEnabled(canCreate);
    createLandscape.setEnabled(canCreate);
    verifyClose.setEnabled(!busy);
    for (Button b : actionButtons) b.setEnabled(!busy);
    if (next != shown) {
      if (next != ViewState.RUNNING) {
        packagePanel.setVisibility(View.GONE);
        textPanel.setVisibility(View.GONE);
        expandedPanel = null;
      }
      shown = next;
    }
    stageIcon.setImageResource(
        next == ViewState.CLEANUP ? R.drawable.ic_onb_alert : R.drawable.ic_vscreen_screen);
    boolean startUnknown = manager.lifecycleState() == VirtualScreenLifecycleState.START_UNKNOWN;
    verifyClose.setText(
        next == ViewState.CLEANUP && startUnknown
            ? t("查看启动记录", "View launch records")
            : t("核验关闭", "Verify close"));
    stageTitle.setText(stageTitle(next));
    String body = stageMessage(next);
    stageMessage.setText(body);
    // 运行中结果条常驻（触摸反馈不能挪动预览）；其余状态没有内容、或与说明卡重复时收起。
    String result = operationResult.getText().toString();
    boolean duplicate =
        next != ViewState.RUNNING
            && (next == ViewState.CLEANUP || !result.isEmpty() && body.contains(result));
    operationResult.setVisibility(
        next == ViewState.RUNNING || !result.isEmpty() && !duplicate ? View.VISIBLE : View.GONE);
    state.setText(stateLabel(next, framed));
    state.setTextColor(
        getColor(
            switch (next) {
              case RUNNING -> framed ? R.color.shell_good : R.color.shell_text_muted;
              case STARTING, CLEANUP -> R.color.shell_warn;
              default -> R.color.shell_text_muted;
            }));
    String original = channelLabel(manager.channel());
    if (next == ViewState.CLEANUP)
      detail.setText(
          original.isEmpty() ? "" : String.format(t("原通道：%s", "Original channel: %s"), original));
    else if (next != ViewState.RUNNING)
      detail.setText(
          (channelHint == null
              ? ""
              : channelHint.isEmpty()
                  ? t("没有可用的设备通道", "No device channel available")
                  : String.format(t("将使用通道：%s", "Channel: %s"), channelHint)));
    renderAssistant();
    boolean floating = manager.overlay().enabled();
    floatButton.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            getColor(floating ? R.color.shell_accent : R.color.shell_text_secondary)));
    floatButton.setContentDescription(
        floating
            ? t("关闭悬浮预览", "Turn off floating preview")
            : t("开启悬浮预览", "Turn on floating preview"));
  }

  private String stateLabel(ViewState next, boolean framed) {
    return switch (next) {
      case UNSUPPORTED -> t("不可用", "Unavailable");
      case STOPPED -> t("未开启", "Off");
      case STARTING -> t("正在创建…", "Creating…");
      case CLEANUP -> t("待清理", "Cleanup pending");
      case RUNNING -> {
        if (!framed) yield t("等待画面…", "Waiting for picture…");
        String channel = channelLabel(manager.channel());
        String base =
            channel.isEmpty()
                ? t("运行中", "Running")
                : String.format(t("运行中 · %s", "Running · %s"), channel);
        yield updating ? base + " · " + t("画面更新中…", "Updating picture…") : base;
      }
    };
  }

  private String stageTitle(ViewState next) {
    return switch (next) {
      case UNSUPPORTED -> t("此设备不支持虚拟屏", "Virtual screens are unavailable");
      case STOPPED -> t("虚拟屏未开启", "Virtual screen is off");
      case STARTING -> t("正在创建虚拟屏", "Creating virtual screen");
      case RUNNING -> t("等待第一帧画面", "Waiting for the first picture");
      case CLEANUP ->
          switch (manager.lifecycleState()) {
            case START_UNKNOWN -> t("上次启动结果未知", "Previous launch result unknown");
            case DISCONNECTED -> t("通道已断开", "Channel disconnected");
            default -> t("尚未确认关闭", "Close not yet confirmed");
          };
    };
  }

  /** 操作者面板与助手授权分开：这里只说明助手能否使用本屏，以及去哪里改。 */
  String assistantLine() {
    if (assistantMode == null) return "";
    return switch (assistantMode) {
      case VIRTUAL ->
          t("助手：可使用本屏（识屏模式 = 虚拟识屏）", "Assistant: may use this screen (screen mode = Virtual)");
      case PHYSICAL ->
          t("助手：不可使用本屏（识屏模式 = 实体识屏）", "Assistant: cannot use this screen (screen mode = Physical)");
      default -> t("助手：不可使用本屏（识屏模式 = 关闭）", "Assistant: cannot use this screen (screen mode = Off)");
    };
  }

  private void renderAssistant() {
    View row = findViewById(R.id.vscreen_assistant_row);
    if (row == null) return;
    String line = assistantLine();
    row.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    ((TextView) findViewById(R.id.vscreen_assistant)).setText(line);
  }

  private String stageMessage(ViewState next) {
    String note = stageNote.isEmpty() ? "" : "\n\n" + stageNote;
    return switch (next) {
      case UNSUPPORTED -> t("需要 Android 11 或更高版本。", "Requires Android 11 or later.");
      case STOPPED -> {
        if (channelHint == null) yield t("正在检查设备通道…", "Checking device channels…") + note;
        if (channelHint.isEmpty())
          yield t(
                  "没有可用的设备通道。请先到“设置 → 设备能力授权”连接 Root、Shizuku 或 ADB 其中一种。",
                  "No device channel is available. Connect Root, Shizuku or ADB in Settings →"
                      + " Device capability access first.")
              + note;
        yield String.format(
                t(
                    "将通过 %s 创建一块独立屏幕，应用在里面运行，不占用当前画面。",
                    "A separate screen will be created through %s. Apps run there without taking"
                        + " over this screen."),
                channelHint)
            + note;
      }
      case STARTING ->
          t(
              "通常几秒内完成。结果返回前不会重复创建。",
              "This usually takes a few seconds. Nothing is created twice while waiting.");
      case RUNNING ->
          stageNote.isEmpty()
              ? t("虚拟屏已创建，画面马上出现。", "The virtual screen is ready; the picture appears shortly.")
              : stageNote;
      case CLEANUP -> {
        String text =
            manager.lifecycleState() == VirtualScreenLifecycleState.START_UNKNOWN
                ? t(
                    "启动命令已发出，但没有收到可核验的回复，也无法确认它是否还在运行。为避免同时存在两块虚拟屏，本次开机内不再创建。重启设备后即可重新创建；反馈问题时可先查看启动记录。",
                    "The launch was sent but no verifiable reply arrived, so it cannot be confirmed whether it is still running. To avoid two virtual screens, nothing new is created until the next restart. Restart the device to create again; check the launch records when reporting the problem.")
                : message("VSCREEN_" + manager.lifecycleState().name(), "");
        yield text + diagnosticSuffix();
      }
    };
  }

  /** 只展示 Manager 已净化过的诊断码（safeStartOutcome），便于反馈时对照，不显示原始输出。 */
  private String diagnosticSuffix() {
    JSONObject last = manager.lastStartOutcome();
    String code = last == null ? "" : last.optString("diagnostic", last.optString("reason"));
    return code.isEmpty() ? "" : "\n\n" + t("诊断码：", "Diagnostic code: ") + code;
  }

  /** 从本机操作记录筛出虚拟屏启动相关行（写入时已脱敏），最新在前；不读其他功能的记录。 */
  static String startRecords(String history, int max) {
    java.util.ArrayList<String> lines = new java.util.ArrayList<>();
    for (String line : history.split("\n")) if (line.contains("[VSCREEN_")) lines.add(line.trim());
    java.util.Collections.reverse(lines);
    return String.join("\n\n", lines.subList(0, Math.min(max, lines.size())));
  }

  private void showStartRecords() {
    long current = callbackEpoch;
    android.content.Context app = getApplicationContext();
    try {
      probe.execute(
          () -> {
            String text;
            try {
              text =
                  startRecords(com.deepseekharness.app.core.DiagnosticLog.readExportTail(app), 8);
            } catch (Exception unavailable) {
              text = "";
            }
            String out =
                text.isEmpty()
                    ? t("本机没有虚拟屏启动记录。", "No virtual-screen launch records on this device.")
                    : text;
            main.post(
                () -> {
                  if (isDestroyed() || current != callbackEpoch) return;
                  new com.deepseekharness.app.ui.DshaDialogBuilder(this)
                      .setTitle(t("虚拟屏启动记录", "Virtual-screen launch records"))
                      .setMessage(out)
                      .setNeutralButton(
                          t("自检与诊断", "Diagnostics"),
                          (d, w) ->
                              startActivity(
                                  new android.content.Intent(
                                      this, com.deepseekharness.app.ui.DiagnosticActivity.class)))
                      .setPositiveButton(t("关闭", "Close"), null)
                      .show();
                });
          });
    } catch (RejectedExecutionException closed) {
      // 页面已销毁。
    }
  }

  private void showUsage() {
    new com.deepseekharness.app.ui.DshaDialogBuilder(this)
        .setTitle(t("虚拟识屏使用说明", "Using the virtual screen"))
        .setMessage(
            t(
                "1. 连接通道：在“设置 → 设备能力授权”连接 Root、Shizuku 或 ADB 任一种。仅打开系统“无线调试”还不够，要确认通道已连接。\n\n"
                    + "2. 创建：点“创建竖屏”或“创建横屏”，画面出现即可使用；运行中点“旋转”切换方向。\n\n"
                    + "3. 打开应用：点“应用 → 选择 → 启动”。预览里单指点击、滑动，双指缩放。\n\n"
                    + "4. 节点操作：需开启 DSHA 无障碍。核对目标后再选择点击或输入；每次动作后先看画面，不会自动重试或改用其他方式。\n\n"
                    + "5. 悬浮预览：点右上角悬浮按钮，离开本页后仍能看到画面；需要系统悬浮窗权限。\n\n"
                    + "6. 让助手操作时，按提示确认本轮读屏与操作授权，并把目标设为虚拟屏。\n\n"
                    + "7. 用完点“关闭”。连接断开或结果未知时页面显示“待清理”：先恢复原通道，再点“核验关闭”；不需要清除数据。仍失败请到“设置 → 自检与诊断”导出日志。\n\n"
                    + "Android 12 上 ADB/Shizuku 通常拿不到受信任显示权限，系统拒绝时请改用 Root。",
                "1. Connect a channel: in Settings → Device capability access, connect Root,"
                    + " Shizuku or ADB. Turning on Wireless debugging alone is not enough; make"
                    + " sure the channel is connected.\n\n"
                    + "2. Create: tap Create portrait or Create landscape and wait for the picture."
                    + " While running, tap Rotate to switch orientation.\n\n"
                    + "3. Open an app: tap App → Choose → Launch. Tap and swipe in the preview;"
                    + " pinch with two fingers to zoom.\n\n"
                    + "4. Node actions need DSHA accessibility. Check the target before choosing"
                    + " Click or Enter text, and observe the picture after each action. Nothing is"
                    + " retried or switched automatically.\n\n"
                    + "5. Floating preview: tap the floating button at the top right to keep"
                    + " watching after leaving this page. Requires the system overlay"
                    + " permission.\n\n"
                    + "6. For assistant control, confirm this run's screen access when prompted"
                    + " and choose the virtual screen as the target.\n\n"
                    + "7. Tap Close when finished. After a disconnection or unknown result the page"
                    + " shows Cleanup pending: restore the original channel, then tap Verify"
                    + " close. You do not need to clear data. If it still fails, export a log from"
                    + " Settings → Diagnostics.\n\n"
                    + "On Android 12, ADB/Shizuku usually lack the trusted-display permission; use"
                    + " Root if the system refuses."))
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
          var apps =
              new java.util.ArrayList<>(getPackageManager().queryIntentActivities(intent, 0));
          // 宿主自身不能启动到虚拟屏（会复用本进程 WebView，T76）：选择器不提供这个目标。
          String self = getPackageName();
          apps.removeIf(x -> x.activityInfo != null && self.equals(x.activityInfo.packageName));
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
    // 没有可选节点时收起列表区，不留一大块空白；节点到达后再展开。
    list.setVisibility(View.GONE);
    panel.nodes.setOnHierarchyChangeListener(
        new ViewGroup.OnHierarchyChangeListener() {
          public void onChildViewAdded(View parent, View child) {
            list.setVisibility(View.VISIBLE);
          }

          public void onChildViewRemoved(View parent, View child) {
            if (panel.nodes.getChildCount() <= 1) list.setVisibility(View.GONE);
          }
        });
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

  /** 显示规格行：摘要只来自白名单，界面不拼任意尺寸/dpi。 */
  private void renderDisplaySpec() {
    if (displaySpecSub == null) return;
    displaySpecSub.setText(displayPresetLine(VirtualScreenDisplaySettings.preset(this)));
  }

  private String displayPresetLine(VirtualScreenDisplayPreset.Preset preset) {
    String size =
        preset.width
            + "×"
            + preset.height
            + " @"
            + preset.density
            + " · "
            + preset.logicalWidth()
            + "×"
            + preset.logicalHeight()
            + " dp";
    if (VirtualScreenDisplayPreset.DEFAULT_ID.equals(preset.id))
      return t("默认 · ", "Default · ") + size;
    return t("兼容基线（360 dp）· ", "Compatibility baseline (360 dp) · ") + size;
  }

  /**
   * 选择显示规格：白名单单选，保存后只影响下一次创建或旋转重建；不会重建当前画面，也不改帧身份与输入门。
   * 白名单外的规格（例如 1440×2560）需要先调整观察预算，因此不在这里提供。
   */
  private void chooseDisplayPreset() {
    final java.util.List<VirtualScreenDisplayPreset.Preset> presets =
        VirtualScreenDisplayPreset.whitelist();
    VirtualScreenDisplayPreset.Preset current = VirtualScreenDisplaySettings.preset(this);
    CharSequence[] items = new CharSequence[presets.size()];
    int selected = 0;
    for (int i = 0; i < presets.size(); i++) {
      items[i] = displayPresetLine(presets.get(i));
      if (presets.get(i).id.equals(current.id)) selected = i;
    }
    new com.deepseekharness.app.ui.DshaDialogBuilder(this)
        .setTitle(t("虚拟屏显示规格", "Virtual screen display size"))
        .setMessage(
            t(
                "默认规格保持不变。选择只影响之后创建或旋转重建的显示，当前运行中的画面不会改变尺寸。白名单外（含 1440×2560）需要先调整观察预算，暂不提供。",
                "The default stays unchanged. The choice only applies to the next create or rotate; a running screen is not resized. Sizes outside the whitelist (including 1440×2560) need an observation-budget change first and are not offered."))
        .setSingleChoiceItems(
            items,
            selected,
            (dialog, which) -> {
              dialog.dismiss();
              VirtualScreenDisplaySettings.setPreset(this, presets.get(which).id);
              renderDisplaySpec();
              operationResult.setText(
                  t(
                      "显示规格已保存，下次创建或旋转生效",
                      "Display size saved; it applies on the next create or rotate"));
            })
        .setNegativeButton(t("取消", "Cancel"), null)
        .show();
  }

  /**
   * 旋转 = 按新方向重建显示（Core 释放旧 VirtualDisplay，应用不会自动迁移）。用户点旋转的意图是“同一应用换方向”，
   * 所以重建成功后在同一次动作里重新打开刚才显示的应用；这是本次用户动作的一部分，不是失败重试。
   */
  private void rotate() {
    String orientation = landscapeFrame ? "portrait" : "landscape", pkg = shownPackage;
    run(
        () -> {
          JSONObject created = manager.start(orientation);
          if (!created.optBoolean("ok") || pkg.isEmpty()) return created;
          return manager.launch(pkg);
        });
  }

  /**
   * 操作者侧创建：只受设备能力（{@link VirtualScreenChannels#choose}）与生命周期约束，不读也不改识屏模式。识屏模式只决定助手能用哪块屏
   * （ADR0003 决定六）；用户自己创建虚拟屏不会把助手授权切到虚拟屏。
   */
  private void create(String orientation) {
    if (busy) return;
    creating = true;
    stageNote = "";
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
    render();
    long current = callbackEpoch;
    long session = manager.epoch();
    worker.execute(
        () -> {
          JSONObject result;
          try {
            if (current != callbackEpoch || session != manager.epoch()) {
              main.post(
                  () -> {
                    busy = false;
                    creating = false;
                    render();
                  });
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
                boolean wasCreating = creating;
                creating = false;
                if (isDestroyed() || !visible || current != callbackEpoch) return;
                if (outcomeRevision == touchFeedback.revision()) {
                  operationResult.setText(outcomeText(out));
                  if (manager.generation().isEmpty()) preview.clear();
                  if (wasCreating && !out.optBoolean("ok")) stageNote = outcomeText(out);
                }
                render();
                probeChannel();
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
                    if (!preview.frame(frame.bitmap, frame.value)) return;
                    frame.adopted();
                    updating = false;
                    stageNote = "";
                    landscapeFrame = frame.value.optInt("width") > frame.value.optInt("height");
                    shownPackage = frame.value.optString("package");
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
                            + channelLabel(manager.channel()));
                    render();
                  } else if (!frame.value.optBoolean("ok")) {
                    String code = frame.value.optString("error");
                    boolean retained = preview.unavailable(code, manager.generation());
                    updating = retained;
                    // 未开启由说明卡本身表达；其他失败把完整原因留在卡片里。
                    if (!busy
                        && !retained
                        && !code.isEmpty()
                        && !"VSCREEN_NOT_RUNNING".equals(code)
                        // 首帧未到或编码间隙是正常的等待，不当作失败写进说明卡。
                        && !"FRAME_NOT_READY".equals(code)
                        && !"STALE_FRAME".equals(code))
                      stageNote =
                          message(code, frame.value.optString("reason")) + cause(frame.value);
                    if (!retained) detail.setText("");
                    generation = "";
                    touchFeedback.generation(generation);
                    frameSeq = -1;
                    render();
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
    if (code.equals("VSCREEN_SELF_LAUNCH_REFUSED"))
      return t(
          "不能把 DSHA 自己启动到虚拟屏：系统会复用正在运行的本进程，副屏上的 WebView 会崩溃。请选择其它应用。",
          "DSHA itself cannot be launched on the virtual screen: the system reuses this running"
              + " process and its WebView crashes on a secondary display. Choose another app.");
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
    // 本页已有大预览：悬浮预览暂时让位，离开本页后恢复。
    if (stage != null) {
      manager.overlay().suppress(true);
      render();
      probeChannel();
    }
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
    if (stage != null) manager.overlay().suppress(false);
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
    probe.shutdownNow();
    super.onDestroy();
  }
}
