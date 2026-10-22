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
    int parentBefore = ((View) preview.getParent()).getTop();
    String failure = frameError();
    outcome.setText(failure);
    layout(384, 780);
    assertEquals(
        "Failure text must not move the preview container",
        parentBefore,
        ((View) preview.getParent()).getTop());
    assertEquals(before, preview.getTop());
    assertEquals(1, outcome.getLineCount());
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
    ((TextView) field(activity, "state")).setText("状态说明".repeat(80));
    ((TextView) field(activity, "detail")).setText("com.example.longpackage.".repeat(30));
    layout(320, 600);
    assertEquals(before, ((View) preview.getParent()).getTop());
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
}
