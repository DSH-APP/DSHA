package com.deepseekharness.app.vscreen;

import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import org.json.JSONObject;
import java.util.concurrent.*;

/** 以预览为主的虚拟屏控制页：单指直接触控、双指缩放，其他操作折叠。 */
public final class VirtualScreenActivity extends AppCompatActivity {
  private VirtualScreenManager manager;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private TextView state, detail;
  private VirtualScreenPreviewView preview;
  private EditText packageInput, textInput;
  private View packagePanel, textPanel;
  private SeekBar size;
  private boolean busy;
  private volatile boolean visible;
  private long frameSeq = -1;
  private String generation = "";
  private VirtualScreenPreviews.Lease previewLease;
  private volatile long callbackEpoch;

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

          public void stream(String gen, long seq, String stroke, int action, float x, float y) {
            long session = manager.epoch();
            worker.execute(
                () -> {
                  if (session == manager.epoch()) manager.touch(gen, seq, stroke, action, x, y);
                });
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
        button(t("控件树", "AI tree"), this::showTree), new LinearLayout.LayoutParams(0, dp(40), 1));
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
    ScrollView scroll = new ScrollView(this);
    scroll.addView(root);
    setContentView(scroll);
  }

  private void showUsage() {
    new com.deepseekharness.app.ui.DshaDialogBuilder(this)
        .setTitle(t("虚拟屏开启与使用", "Start and use a virtual screen"))
        .setMessage(
            t(
                "1. 使用标准版，手机系统需为 Android 11 或更高。兼容版暂不支持虚拟屏。\n\n"
                    + "2. 打开“设置 → 设备能力授权”，连接 ADB、Shizuku 或 Root 中的一种通道即可。没有 Root 时，可在系统开发者选项开启“无线调试”，再按 DSHA 的 ADB 配对页提示完成配对与连接。仅打开无线调试开关还不够，请确认通道已连接。\n\n"
                    + "3. 回到“设备能力授权”，点“打开虚拟屏”。在本页点“创建竖屏”或“创建横屏”，等待画面出现。打开此页或开启无障碍，都不会自动创建虚拟屏。\n\n"
                    + "4. 点“应用 → 选择”，选中想操作的应用，再点“启动”，把它打开到虚拟屏。预览里可以点击、滑动；双指用于调整预览。\n\n"
                    + "5. 读控件树和中文输入需要开启 DSHA 无障碍。让助手操作时，还要按应用提示确认本次读屏与操作授权，并将操作目标设为虚拟屏。\n\n"
                    + "VSCREEN_NOT_RUNNING 表示虚拟屏还没创建、已经关闭，或连接已断开。先检查设备通道，再点创建按钮；不需要清除数据或重新解压环境。\n\n"
                    + "创建仍失败：到“设置 → 自检与诊断”查看错误记录并导出日志。仅开启无障碍不能解决设备通道或系统兼容问题。\n\n"
                    + "不用时点本页“关闭”回收虚拟屏；连接断开或授权被撤销后，需要重新连接并创建。",
                "1. Use Standard on Android 11 or later. Low does not support virtual screens yet.\n\n"
                    + "2. Open Settings → Device capability access and connect one channel: ADB, Shizuku, or Root. Without Root, enable Wireless debugging in the system Developer options, then follow DSHA's ADB pairing page to pair and connect. Enabling the system switch alone is not enough; confirm the channel is connected.\n\n"
                    + "3. In Device capability access, tap Open virtual screen. Tap Create portrait or Create landscape here and wait for a picture. Opening this page or enabling accessibility does not create a virtual screen automatically.\n\n"
                    + "4. Tap App → Choose, select an app, then tap Launch to open it on the virtual screen. Tap and swipe in the preview; use two fingers to adjust the preview.\n\n"
                    + "5. Enable DSHA accessibility for the control tree and Unicode input. For assistant control, also confirm this run's screen access when prompted and choose the virtual screen as the action target.\n\n"
                    + "VSCREEN_NOT_RUNNING means no virtual screen has been created, it was closed, or its connection was lost. Check the device channel, then tap a create button. You do not need to clear data or extract the environment again.\n\n"
                    + "If creation still fails, open Settings → Diagnostics to inspect the error record and export logs. Accessibility alone cannot resolve a missing device channel or system compatibility problem.\n\n"
                    + "Tap Close when finished. After a disconnection or revoked access, reconnect and create the virtual screen again."))
        .setPositiveButton(t("知道了", "Got it"), null)
        .show();
  }

  private void toggle(View view) {
    view.setVisibility(view.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
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

  private void showTree() {
    long current = callbackEpoch;
    worker.execute(
        () -> {
          JSONObject r = manager.tree();
          main.post(
              () -> {
                if (!visible || isDestroyed() || current != callbackEpoch) return;
                new com.deepseekharness.app.ui.DshaDialogBuilder(this)
                    .setTitle(t("控件树（给 AI 使用）", "Accessibility tree for AI"))
                    .setMessage(r.toString())
                    .setPositiveButton(t("关闭", "Close"), null)
                    .show();
              });
        });
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

  private void run(Work action) {
    if (busy) return;
    busy = true;
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
              result.put("error", error.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
          }
          JSONObject out = result;
          main.post(
              () -> {
                busy = false;
                if (isDestroyed() || !visible || current != callbackEpoch) return;
                state.setText(
                    out.optBoolean("ok") ? t("操作完成", "Done") : message(out.optString("error")));
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
                    preview.frame(frame.bitmap, frame.value);
                    generation = frame.value.optString("generation");
                    frameSeq = frame.value.optLong("frameSeq");
                    detail.setText(
                        frame.value.optString("package")
                            + " · "
                            + frame.value.optInt("width")
                            + "×"
                            + frame.value.optInt("height")
                            + " · "
                            + manager.channel());
                  } else if (!busy && !frame.value.optBoolean("ok")) {
                    String code = frame.value.optString("error");
                    if (!code.isEmpty()) state.setText(message(code));
                    preview.clear();
                    detail.setText("");
                    generation = "";
                    frameSeq = -1;
                  }
                });
  }

  private String message(String code) {
    if (code.equals("VSCREEN_NOT_RUNNING"))
      return t(
          "虚拟屏尚未开启或已关闭。先连接设备通道，再点“创建竖屏”或“创建横屏”；开启无障碍不会自动创建。",
          "The virtual screen has not started or was closed. Connect a device channel, then tap Create portrait or Create landscape. Accessibility does not create it automatically.");
    if (code.equals("STALE_FRAME") || code.equals("STALE_GENERATION"))
      return t("画面已变化，请看最新画面后再操作", "The view changed. Observe again.");
    if (code.equals("DEVICE_CHANNEL_UNAVAILABLE"))
      return t("请先在设备能力授权中连接通道", "Connect a device channel first");
    if (code.equals("ACCESSIBILITY_REQUIRED_FOR_UNICODE"))
      return t("中文输入需要开启无障碍服务", "Enable accessibility for Unicode input");
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
    visible = false;
    callbackEpoch++;
    if (previewLease != null) {
      previewLease.close();
      previewLease = null;
    }
    if (preview != null) preview.cancelStream();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    visible = false;
    callbackEpoch++;
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
