package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import java.util.ArrayList;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

/** Real Activity creation, Android widgets/layout and click listeners; no device transport. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = VirtualScreenActivityLayoutTest.App.class,
    qualifiers = "w384dp-h853dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class VirtualScreenActivityLayoutTest {
  public static final class App extends DshaApp {
    private VirtualScreenManager manager;

    @Override
    public void onCreate() {}

    @Override
    public VirtualScreenManager virtualScreenManager() {
      if (manager == null)
        manager =
            new VirtualScreenManager(
                null,
                () -> 100L,
                new VirtualScreenManager.Visuals() {
                  public void present() {}

                  public void stopped(long fence) {}
                });
      return manager;
    }
  }

  private ActivityController<VirtualScreenActivity> controller;
  private VirtualScreenActivity activity;
  private ScrollView scroll;
  private boolean created;

  private static Object field(Object owner, String name) throws Exception {
    var f = owner.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(owner);
  }

  private static void field(Object owner, String name, Object value) throws Exception {
    var f = owner.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(owner, value);
  }

  private Button button(View root, String label) {
    if (root instanceof Button b && label.contentEquals(b.getText())) return b;
    if (root instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = button(group.getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  private void layout(int width, int height) {
    // Model the viewport available below system UI/IME; normal root traversals retain it.
    scroll.getLayoutParams().width = width;
    scroll.getLayoutParams().height = height;
    forceLayout(scroll);
    scroll.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    scroll.layout(0, 0, width, height);
  }

  private void forceLayout(View view) {
    view.forceLayout();
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) forceLayout(group.getChildAt(i));
  }

  private void drain() {
    org.robolectric.shadows.ShadowLooper main =
        org.robolectric.shadow.api.Shadow.extract(Looper.getMainLooper());
    main.idleFor(java.time.Duration.ofSeconds(1));
  }

  private String frameError() throws Exception {
    var message =
        VirtualScreenActivity.class.getDeclaredMethod("message", String.class, String.class);
    message.setAccessible(true);
    return message.invoke(null, "STALE_FRAME", "FRAME_EXPIRED")
        + " · requested=584 current=585 observed=584";
  }

  @Before
  public void createActualActivity() {
    com.deepseekharness.app.util.UiText.setLanguage("zh");
    controller = Robolectric.buildActivity(VirtualScreenActivity.class);
    activity = controller.get();
    activity.setTheme(R.style.Theme_DeepseekHarness);
    controller.create();
    created = true;
    controller.visible();
    ViewGroup content = activity.findViewById(android.R.id.content);
    scroll = (ScrollView) content.getChildAt(0);
    layout(384, 780);
    drain();
  }

  @After
  public void finish() throws Exception {
    if (created) controller.destroy();
    var manager = ((App) RuntimeEnvironment.getApplication()).manager;
    if (manager != null)
      ((java.util.concurrent.ScheduledExecutorService) field(manager, "WORKER")).shutdownNow();
  }

  @Test
  public void touchFailureCannotMovePreviewAndFullDetailsRemainReadable() throws Exception {
    TextView outcome = (TextView) field(activity, "operationResult");
    View preview = (View) field(activity, "preview");
    outcome.setText("操作完成");
    layout(384, 780);
    int before = preview.getTop();
    int heightBefore = preview.getHeight();
    int parentBefore = ((View) preview.getParent()).getTop();
    String failure = frameError();
    outcome.setText(failure);
    layout(384, 780);
    assertEquals(
        "Failure text must not move the preview container",
        parentBefore,
        ((View) preview.getParent()).getTop());
    assertEquals(before, preview.getTop());
    assertEquals("Failure text must not resize the preview", heightBefore, preview.getHeight());
    assertTrue("Result strip is bounded to two lines", outcome.getLineCount() <= 2);
    assertTrue("Truncated status must open its full detail", outcome.performClick());
    drain();
    var dialog = ShadowDialog.getLatestDialog();
    assertNotNull(dialog);
    TextView details = dialog.findViewById(android.R.id.message);
    assertNotNull(details);
    assertEquals(failure, details.getText().toString());
  }

  @Test
  public void stateAndPackageDetailTextCannotMovePreviewInNarrowLayout() throws Exception {
    View preview = (View) field(activity, "preview");
    layout(320, 600);
    int before = ((View) preview.getParent()).getTop();
    int height = preview.getHeight();
    ((TextView) field(activity, "state")).setText("状态说明".repeat(80));
    ((TextView) field(activity, "detail")).setText("com.example.longpackage.".repeat(30));
    layout(320, 600);
    assertEquals(before, ((View) preview.getParent()).getTop());
    assertEquals(height, preview.getHeight());
    assertEquals(1, ((TextView) field(activity, "state")).getLineCount());
    assertEquals(1, ((TextView) field(activity, "detail")).getLineCount());
  }

  private void assertPanelVisible(View panel) {
    Rect box = new Rect(0, 0, panel.getWidth(), panel.getHeight());
    scroll.offsetDescendantRectToMyCoords(panel, box);
    assertTrue("Panel top is above viewport: " + box, box.top >= scroll.getScrollY());
    assertTrue(
        "Panel bottom is below viewport: "
            + box
            + " scroll="
            + scroll.getScrollY()
            + " height="
            + scroll.getHeight(),
        box.bottom <= scroll.getScrollY() + scroll.getHeight() - scroll.getPaddingBottom());
  }

  @Test
  public void openingKeyboardBringsTheWholeInputAndSendRowIntoView() throws Exception {
    button(scroll, "键盘").performClick();
    layout(384, 780);
    drain();
    View panel = (View) field(activity, "textPanel");
    assertEquals(View.VISIBLE, panel.getVisibility());
    assertPanelVisible(panel);
  }

  @Test
  public void keyboardRowRemainsVisibleAfterImeResizeAndLandscapeLayout() throws Exception {
    button(scroll, "键盘").performClick();
    layout(384, 780);
    drain();
    ((View) field(activity, "textInput")).requestFocus();
    layout(384, 420);
    drain();
    assertPanelVisible((View) field(activity, "textPanel"));
    layout(740, 310);
    drain();
    assertPanelVisible((View) field(activity, "textPanel"));
  }

  @Test
  public void openingApplicationPanelRevealsItsInputAndLaunchControls() throws Exception {
    button(scroll, "应用").performClick();
    layout(384, 520);
    drain();
    assertPanelVisible((View) field(activity, "packagePanel"));
  }

  @Test
  public void systemBarsCutoutAndImeInsetsRemainOwnedByTheExistingWindowPolicy() throws Exception {
    new com.deepseekharness.app.ui.ModernAndroidUi().onActivityPostCreated(activity, null);
    ViewGroup content = activity.findViewById(android.R.id.content);
    var insets =
        new androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
                androidx.core.graphics.Insets.of(8, 24, 8, 20))
            .setInsets(
                androidx.core.view.WindowInsetsCompat.Type.ime(),
                androidx.core.graphics.Insets.of(0, 0, 0, 300))
            .build();
    androidx.core.view.ViewCompat.dispatchApplyWindowInsets(content, insets);
    assertEquals(8, content.getPaddingLeft());
    assertEquals(24, content.getPaddingTop());
    assertEquals(8, content.getPaddingRight());
    assertEquals(300, content.getPaddingBottom());
    button(scroll, "键盘").performClick();
    scroll.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
    scroll.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
    forceLayout(content);
    content.measure(
        View.MeasureSpec.makeMeasureSpec(384, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY));
    content.layout(0, 0, 384, 780);
    drain();
    assertPanelVisible((View) field(activity, "textPanel"));
    assertEquals(
        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        activity.getWindow().getAttributes().softInputMode
            & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
  }

  @Test
  public void stoppedPageShowsCreateActionsAndHidesRunningTools() throws Exception {
    assertEquals(View.VISIBLE, activity.findViewById(R.id.vscreen_create_group).getVisibility());
    assertEquals(View.GONE, activity.findViewById(R.id.vscreen_tool_group).getVisibility());
    assertEquals(View.GONE, activity.findViewById(R.id.vscreen_verify_close).getVisibility());
    assertEquals(View.VISIBLE, activity.findViewById(R.id.vscreen_stage_card).getVisibility());
    assertEquals("未开启", ((TextView) field(activity, "state")).getText().toString());
  }

  @Test
  public void operatorCreateNeverReadsOrWritesTheAssistantScreenMode() throws Exception {
    var prefs =
        activity.getSharedPreferences(
            com.deepseekharness.app.util.Constants.PREFS, android.content.Context.MODE_PRIVATE);
    prefs.edit().putString(com.deepseekharness.app.screen.ScreenMode.KEY, "off").commit();
    var create = VirtualScreenActivity.class.getDeclaredMethod("create", String.class);
    create.setAccessible(true);
    create.invoke(activity, "portrait");
    for (int i = 0; i < 40 && (boolean) field(activity, "busy"); i++) {
      Thread.sleep(50);
      drain();
    }
    assertEquals(false, field(activity, "busy"));
    // 操作者自己创建虚拟屏不能顺带把助手授权切到虚拟屏（ADR0003 决定六）。
    assertEquals("off", prefs.getString(com.deepseekharness.app.screen.ScreenMode.KEY, null));
  }

  @Test
  public void pageExplainsWhetherTheAssistantMayUseThisScreen() throws Exception {
    var line = VirtualScreenActivity.class.getDeclaredMethod("assistantLine");
    line.setAccessible(true);
    var render = VirtualScreenActivity.class.getDeclaredMethod("render");
    render.setAccessible(true);
    field(activity, "assistantMode", null);
    assertEquals("", line.invoke(activity));
    field(activity, "assistantMode", com.deepseekharness.app.util.ScreenModePolicy.Mode.OFF);
    assertEquals("助手：不可使用本屏（识屏模式 = 关闭）", line.invoke(activity));
    field(activity, "assistantMode", com.deepseekharness.app.util.ScreenModePolicy.Mode.PHYSICAL);
    assertEquals("助手：不可使用本屏（识屏模式 = 实体识屏）", line.invoke(activity));
    field(activity, "assistantMode", com.deepseekharness.app.util.ScreenModePolicy.Mode.VIRTUAL);
    assertEquals("助手：可使用本屏（识屏模式 = 虚拟识屏）", line.invoke(activity));
    render.invoke(activity);
    assertEquals(View.VISIBLE, activity.findViewById(R.id.vscreen_assistant_row).getVisibility());
    assertEquals(
        "识屏模式", ((Button) activity.findViewById(R.id.vscreen_assistant_open)).getText().toString());
  }

  @Test
  public void startUnknownOffersDiagnosticsAndDoesNotRepeatTheCardMessage() throws Exception {
    var manager = (VirtualScreenManager) field(activity, "manager");
    var life = VirtualScreenManager.class.getDeclaredField("lifecycleState");
    life.setAccessible(true);
    life.set(manager, com.deepseekharness.app.util.VirtualScreenLifecycleState.START_UNKNOWN);
    TextView outcome = (TextView) field(activity, "operationResult");
    outcome.setText("上次启动结果未知");
    var render = VirtualScreenActivity.class.getDeclaredMethod("render");
    render.setAccessible(true);
    render.invoke(activity);
    Button verify = activity.findViewById(R.id.vscreen_verify_close);
    assertEquals(View.VISIBLE, verify.getVisibility());
    assertEquals("查看启动记录", verify.getText().toString());
    assertEquals(View.GONE, outcome.getVisibility());
    assertEquals(View.GONE, activity.findViewById(R.id.vscreen_create_group).getVisibility());
    assertEquals("待清理", ((TextView) field(activity, "state")).getText().toString());
    life.set(manager, com.deepseekharness.app.util.VirtualScreenLifecycleState.STOPPED);
  }

  @Test
  public void startRecordsKeepOnlyVirtualScreenLinesNewestFirst() {
    String history =
        "11-03 06:15:01 [WEB_START] ok\n"
            + "11-03 06:15:02 [VSCREEN_START] VSCREEN_START_RESULT_UNKNOWN\n"
            + "11-03 06:15:03 [RUNTIME_TRIAL] IO_FAILURE\n"
            + "11-03 06:15:04 [VSCREEN_START_RESULT] epoch=1 {\"error\":\"VSCREEN_START_UNKNOWN\"}\n";
    String out = VirtualScreenActivity.startRecords(history, 8);
    assertFalse(out.contains("WEB_START"));
    assertFalse(out.contains("RUNTIME_TRIAL"));
    assertTrue(out.indexOf("06:15:04") < out.indexOf("06:15:02"));
    assertEquals("", VirtualScreenActivity.startRecords("x [OTHER] y", 8));
  }

  @Test
  public void viewStateFollowsLifecycleFactsWithCleanupFirst() {
    var S = com.deepseekharness.app.util.VirtualScreenLifecycleState.class;
    assertEquals(
        VirtualScreenActivity.ViewState.UNSUPPORTED,
        VirtualScreenActivity.viewState(false, null, true, true));
    assertEquals(
        VirtualScreenActivity.ViewState.STOPPED,
        VirtualScreenActivity.viewState(
            true, com.deepseekharness.app.util.VirtualScreenLifecycleState.STOPPED, false, false));
    assertEquals(
        VirtualScreenActivity.ViewState.STARTING,
        VirtualScreenActivity.viewState(
            true, com.deepseekharness.app.util.VirtualScreenLifecycleState.STOPPED, false, true));
    assertEquals(
        VirtualScreenActivity.ViewState.RUNNING,
        VirtualScreenActivity.viewState(
            true, com.deepseekharness.app.util.VirtualScreenLifecycleState.READY, true, true));
    for (var life : S.getEnumConstants())
      if (life.cleanupPending())
        assertEquals(
            VirtualScreenActivity.ViewState.CLEANUP,
            VirtualScreenActivity.viewState(true, life, true, true));
  }

  private static final class PendingExecutor extends AbstractExecutorService {
    final ArrayList<Runnable> queued = new ArrayList<>();
    boolean stopped;

    public void execute(Runnable work) {
      queued.add(work);
    }

    public void shutdown() {
      stopped = true;
    }

    public java.util.List<Runnable> shutdownNow() {
      stopped = true;
      return java.util.List.copyOf(queued);
    }

    public boolean isShutdown() {
      return stopped;
    }

    public boolean isTerminated() {
      return stopped;
    }

    public boolean awaitTermination(long t, TimeUnit unit) {
      return stopped;
    }
  }

  @Test
  public void openingAndRevealingKeyboardNeverSendsAndBusySendQueuesOnce() throws Exception {
    var old = (java.util.concurrent.ExecutorService) field(activity, "worker");
    old.shutdown();
    var pending = new PendingExecutor();
    field(activity, "worker", pending);
    button(scroll, "键盘").performClick();
    layout(384, 420);
    drain();
    assertTrue(pending.queued.isEmpty());
    Button send = button(scroll, "发送");
    assertNotNull(send);
    send.performClick();
    send.performClick();
    assertEquals("A second busy click must not enqueue a replay", 1, pending.queued.size());
  }

  @Test
  public void displaySizeRowShowsTheWhitelistSelectionAndAppliesOnNextCreate() throws Exception {
    var render = VirtualScreenActivity.class.getDeclaredMethod("render");
    render.setAccessible(true);
    VirtualScreenDisplaySettings.setPreset(
        activity, com.deepseekharness.app.util.VirtualScreenDisplayPreset.DEFAULT_ID);
    render.invoke(activity);
    drain();
    View row = activity.findViewById(R.id.vscreen_display_spec_row);
    TextView sub = (TextView) activity.findViewById(R.id.vscreen_display_spec_sub);
    assertNotNull(row);
    assertNotNull(sub);
    assertEquals(View.VISIBLE, row.getVisibility());
    assertTrue(sub.getText().toString(), sub.getText().toString().contains("1008×1792 @320"));
    assertTrue("默认也要显示逻辑 dp", sub.getText().toString().contains("504×896 dp"));

    VirtualScreenDisplaySettings.setPreset(activity, "compat-720");
    render.invoke(activity);
    drain();
    assertTrue(sub.getText().toString(), sub.getText().toString().contains("720×1600 @320"));
    assertTrue(sub.getText().toString(), sub.getText().toString().contains("360×800 dp"));
    assertEquals("compat-720", VirtualScreenDisplaySettings.preset(activity).id);
  }
}
