package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

/**
 * Actual Activity/dialog clicks -> native owner -> Manager -> authenticated Core -> node action.
 * Only platform accessibility windows use the shared synthetic fixture; raw input is counted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = VirtualScreenActivityNodeControlsTest.App.class,
    qualifiers = "w384dp-h853dp-mdpi",
    shadows = VirtualScreenNativeControlsTest.FreshWindows.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class VirtualScreenActivityNodeControlsTest {
  public static final class App extends DshaApp {
    VirtualScreenManager manager;

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

  private static final class Pending extends AbstractExecutorService {
    final ArrayList<Runnable> queued = new ArrayList<>();
    boolean stopped;

    public void execute(Runnable work) {
      queued.add(work);
    }

    public void shutdown() {
      stopped = true;
    }

    public List<Runnable> shutdownNow() {
      stopped = true;
      return List.copyOf(queued);
    }

    public boolean isShutdown() {
      return stopped;
    }

    public boolean isTerminated() {
      return stopped;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return stopped;
    }

    void one() {
      assertFalse("No pending UI operation", queued.isEmpty());
      queued.remove(0).run();
    }
  }

  private ActivityController<VirtualScreenActivity> controller;
  private VirtualScreenActivity activity;
  private VirtualScreenNativeControlsTest.Fixture fixture;
  private Pending pending;
  private boolean resumed;

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

  private static <T extends View> T find(View root, Class<T> type, String label) {
    if (type.isInstance(root)
        && (label == null || root instanceof TextView text && label.contentEquals(text.getText())))
      return type.cast(root);
    if (root instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        T found = find(group.getChildAt(i), type, label);
        if (found != null) return found;
      }
    return null;
  }

  private Button button(String label) {
    return find(activity.findViewById(android.R.id.content), Button.class, label);
  }

  private static void drain() {
    ShadowLooper main = Shadow.extract(Looper.getMainLooper());
    main.idleFor(Duration.ofMillis(10));
  }

  private Object panel() throws Exception {
    return field(activity, "nodePanel");
  }

  private AlertDialog dialog() throws Exception {
    return (AlertDialog) field(panel(), "dialog");
  }

  private AlertDialog actionDialog() throws Exception {
    return (AlertDialog) field(panel(), "actionDialog");
  }

  private LinearLayout nodes() throws Exception {
    return (LinearLayout) field(panel(), "nodes");
  }

  private Button row() throws Exception {
    assertEquals(1, nodes().getChildCount());
    return (Button) nodes().getChildAt(0);
  }

  private void open() throws Exception {
    assertNotNull(button("节点操作"));
    assertNull(button("控件树"));
    button("节点操作").performClick();
    assertEquals("Only the explicit initial tree read is queued", 1, pending.queued.size());
    pending.one();
    drain();
    assertTrue(dialog().isShowing());
    assertEquals(
        VirtualScreenNativeControlsTest.PKG + " · 虚拟显示 " + VirtualScreenNativeControlsTest.DISPLAY,
        ((TextView) field(panel(), "target")).getText().toString());
  }

  private void chooseClick() throws Exception {
    row().performClick();
    assertTrue(actionDialog().isShowing());
    actionDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
    drain();
  }

  @Before
  public void createActualResumedActivityAndAuthenticatedCore() throws Exception {
    org.junit.Assume.assumeFalse(com.deepseekharness.app.BuildConfig.LOW_ANDROID);
    com.deepseekharness.app.util.UiText.setLanguage("zh");
    fixture = new VirtualScreenNativeControlsTest.Fixture();
    controller = Robolectric.buildActivity(VirtualScreenActivity.class);
    activity = controller.get();
    activity.setTheme(R.style.Theme_DeepseekHarness);
    controller.create().start().resume().visible();
    resumed = true;
    field(activity, "manager", fixture.manager);
    ((ExecutorService) field(activity, "worker")).shutdown();
    pending = new Pending();
    field(activity, "worker", pending);
  }

  @After
  public void closeOwnersAndHostCore() throws Exception {
    if (controller != null) {
      if (resumed) controller.pause();
      controller.stop().destroy();
    }
    if (fixture != null) fixture.close();
    var original = ((App) RuntimeEnvironment.getApplication()).manager;
    if (original != null) ((ScheduledExecutorService) field(original, "WORKER")).shutdownNow();
  }

  @Test
  public void entryShowsVerifiedActualTargetAndReadableNodeWithoutActing() throws Exception {
    ((EditText) field(activity, "packageInput")).setText("com.example.unsubmitted.draft");
    open();
    assertTrue(row().getText().toString().contains("5"));
    assertEquals(0, fixture.actions.get());
    assertEquals(0, fixture.injected.get());
    assertTrue(pending.queued.isEmpty());
  }

  @Test
  public void selectedClickUsesNodeWhenPixelsAdvanceAndDoubleClickNeverReplays() throws Exception {
    open();
    Button oldRow = row();
    oldRow.performClick();
    assertEquals(0, fixture.actions.get());
    AlertDialog confirm = actionDialog();
    Button click = confirm.getButton(DialogInterface.BUTTON_POSITIVE);
    click.performClick();
    click.performClick();
    drain();
    assertEquals(1, pending.queued.size());
    fixture.display.sequence = 238;
    pending.one();
    drain();
    assertEquals(1, fixture.actions.get());
    assertEquals(0, fixture.injected.get());
    assertEquals(0, nodes().getChildCount());
    assertTrue(pending.queued.isEmpty());
    oldRow.performClick();
    click.performClick();
    assertTrue(pending.queued.isEmpty());
    assertTrue(((TextView) field(panel(), "result")).getText().toString().contains("手动刷新"));
  }

  @Test
  public void onlyExplicitRefreshMakesAnotherActionAvailable() throws Exception {
    open();
    chooseClick();
    pending.one();
    drain();
    assertEquals(1, fixture.actions.get());
    assertEquals(0, nodes().getChildCount());
    dialog().getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
    assertEquals(1, pending.queued.size());
    pending.one();
    drain();
    chooseClick();
    pending.one();
    drain();
    assertEquals(2, fixture.actions.get());
    assertEquals(0, fixture.injected.get());
    assertTrue(pending.queued.isEmpty());
  }

  @Test
  public void selectedEditorWaitsForExplicitSetTextAndPassesUnicodeExactlyOnce() throws Exception {
    fixture.button.setEditable(true);
    fixture.button.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
    AtomicReference<String> received = new AtomicReference<>();
    VirtualScreenNativeControlsTest.nodeShadow(fixture.button)
        .setOnPerformActionListener(
            (action, args) -> {
              assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, action);
              fixture.actions.incrementAndGet();
              received.set(
                  args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                      .toString());
              return true;
            });
    open();
    row().performClick();
    actionDialog().getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
    drain();
    AlertDialog edit = actionDialog();
    EditText input = find(edit.getWindow().getDecorView(), EditText.class, null);
    assertNotNull(input);
    assertEquals("5", input.getText().toString());
    input.setText("中文 %s & = 5");
    assertEquals(0, fixture.actions.get());
    assertTrue(pending.queued.isEmpty());
    assertEquals(
        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        edit.getWindow().getAttributes().softInputMode
            & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
    Button submit = edit.getButton(DialogInterface.BUTTON_POSITIVE);
    submit.performClick();
    submit.performClick();
    drain();
    assertEquals(1, pending.queued.size());
    pending.one();
    drain();
    assertEquals("中文 %s & = 5", received.get());
    assertEquals(1, fixture.actions.get());
    assertEquals(0, fixture.injected.get());
  }

  private AlertDialog openEditorWithText(String original, AtomicReference<String> received)
      throws Exception {
    fixture.button.setEditable(true);
    fixture.button.setText(original);
    fixture.button.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
    VirtualScreenNativeControlsTest.nodeShadow(fixture.button)
        .setOnPerformActionListener(
            (action, args) -> {
              assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, action);
              fixture.actions.incrementAndGet();
              received.set(
                  args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                      .toString());
              return true;
            });
    open();
    row().performClick();
    actionDialog().getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
    drain();
    return actionDialog();
  }

  @Test
  public void truncatedEditorNeverPrefillsPrefixOrSubmitsUnenteredOrErasedReplacement()
      throws Exception {
    AtomicReference<String> received = new AtomicReference<>();
    AlertDialog editor = openEditorWithText("长".repeat(1266), received);
    EditText input = find(editor.getWindow().getDecorView(), EditText.class, null);
    Button submit = editor.getButton(DialogInterface.BUTTON_POSITIVE);
    assertEquals("", input.getText().toString());
    assertFalse(submit.isEnabled());
    assertEquals("完整替换", submit.getText().toString());
    assertNotNull(
        find(
            editor.getWindow().getDecorView(),
            TextView.class,
            "原文字较长，未载入完整内容。请输入完整的新文字；提交会替换此字段的全部原文字。未输入时不能提交。"));
    assertTrue(pending.queued.isEmpty());
    assertEquals(0, fixture.actions.get());
    input.setText("explicit replacement");
    assertTrue(submit.isEnabled());
    input.setText("");
    assertFalse(submit.isEnabled());
    submit.performClick();
    drain();
    assertTrue(pending.queued.isEmpty());
    assertNull(received.get());
    assertEquals(0, fixture.actions.get());
    assertEquals(0, fixture.injected.get());
  }

  @Test
  public void truncatedEditorExplicitCompleteReplacementIsSubmittedExactlyOnce() throws Exception {
    AtomicReference<String> received = new AtomicReference<>();
    AlertDialog editor = openEditorWithText("old".repeat(500), received);
    EditText input = find(editor.getWindow().getDecorView(), EditText.class, null);
    String replacement = "完整替换".repeat(400) + " Unicode %s & = tail";
    assertEquals("", input.getText().toString());
    input.setText(replacement);
    Button submit = editor.getButton(DialogInterface.BUTTON_POSITIVE);
    assertTrue(submit.isEnabled());
    submit.performClick();
    submit.performClick();
    drain();
    assertEquals(1, pending.queued.size());
    pending.one();
    drain();
    assertEquals(replacement, received.get());
    assertEquals(1, fixture.actions.get());
    assertEquals(0, fixture.injected.get());
    assertTrue(pending.queued.isEmpty());
  }

  @Test
  public void full1000UnitEditorKeepsCompletePrefillAndExistingSetTextBehavior() throws Exception {
    String original = "界".repeat(1000);
    AtomicReference<String> received = new AtomicReference<>();
    AlertDialog editor = openEditorWithText(original, received);
    EditText input = find(editor.getWindow().getDecorView(), EditText.class, null);
    assertEquals(original, input.getText().toString());
    Button submit = editor.getButton(DialogInterface.BUTTON_POSITIVE);
    assertTrue(submit.isEnabled());
    assertEquals("设置文字", submit.getText().toString());
    submit.performClick();
    drain();
    assertEquals(1, pending.queued.size());
    pending.one();
    drain();
    assertEquals(original, received.get());
    assertEquals(1, fixture.actions.get());
    assertEquals(0, fixture.injected.get());
  }

  @Test
  public void disabledAndPasswordEditorsCannotOfferTextSubmission() throws Exception {
    fixture.button.setEnabled(false);
    open();
    assertEquals(0, nodes().getChildCount());
    fixture.button.setEnabled(true);
    fixture.button.setEditable(true);
    fixture.button.setPassword(true);
    fixture.button.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
    dialog().getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
    pending.one();
    drain();
    row().performClick();
    assertEquals(
        View.GONE, actionDialog().getButton(DialogInterface.BUTTON_NEUTRAL).getVisibility());
    assertEquals(0, fixture.actions.get());
  }

  @Test
  public void unknownAfterPlatformAdmissionRevokesPanelAndNeverRefreshesOrReplays()
      throws Exception {
    VirtualScreenNativeControlsTest.nodeShadow(fixture.button)
        .setOnPerformActionListener(
            (action, args) -> {
              fixture.actions.incrementAndGet();
              throw new IllegalStateException("platform outcome unknown");
            });
    open();
    chooseClick();
    pending.one();
    drain();
    assertEquals(1, fixture.actions.get());
    assertEquals(0, fixture.injected.get());
    assertEquals(0, nodes().getChildCount());
    assertFalse(dialog().getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled());
    assertFalse(((VirtualScreenNativeControls) field(panel(), "controls")).current());
    assertTrue(((TextView) field(panel(), "result")).getText().toString().contains("结果未知"));
    dialog().getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
    assertTrue(pending.queued.isEmpty());
    assertEquals(1, fixture.actions.get());
  }

  @Test
  public void changedTargetAfterUserSelectionCannotExecuteOrFallback() throws Exception {
    open();
    row().performClick();
    fixture.root.setPackageName("com.example.changed");
    actionDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
    drain();
    pending.one();
    drain();
    assertEquals(0, fixture.actions.get());
    assertEquals(0, fixture.injected.get());
    assertEquals(0, nodes().getChildCount());
    assertTrue(pending.queued.isEmpty());
  }

  @Test
  public void platformRefusalExplainsUnsupportedActionAndKeepsDiagnosticCode() throws Exception {
    VirtualScreenNativeControlsTest.nodeShadow(fixture.button)
        .setOnPerformActionListener(
            (action, args) -> {
              fixture.actions.incrementAndGet();
              return false;
            });
    open();
    chooseClick();
    pending.one();
    drain();
    String result = ((TextView) field(panel(), "result")).getText().toString();
    assertTrue(result.contains("不支持或拒绝"));
    assertTrue(result.contains("NODE_ACTION_REJECTED"));
    assertEquals(1, fixture.actions.get());
    assertEquals(0, fixture.injected.get());
    assertEquals(0, nodes().getChildCount());
    assertTrue(pending.queued.isEmpty());
  }

  @Test
  public void revokedConnectionExplainsReopeningAndCannotRefreshOldOwner() throws Exception {
    open();
    row().performClick();
    VirtualScreenNativeControlsTest.connection().disconnect(fixture.service);
    actionDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
    drain();
    pending.one();
    drain();
    String result = ((TextView) field(panel(), "result")).getText().toString();
    assertTrue(result.contains("重新打开"));
    assertTrue(result.contains("NATIVE_CONTROL_REVOKED"));
    assertFalse(dialog().getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled());
    assertEquals(0, fixture.actions.get());
    assertEquals(0, fixture.injected.get());
  }

  @Test
  public void expiredNodeIsRefusedWithoutAutomaticallyRefreshingOrActing() throws Exception {
    open();
    ShadowSystemClock.advanceBy(Duration.ofSeconds(31));
    chooseClick();
    pending.one();
    drain();
    assertEquals(0, fixture.actions.get());
    assertEquals(0, fixture.injected.get());
    assertEquals(0, nodes().getChildCount());
    assertTrue(pending.queued.isEmpty());
    assertTrue(((TextView) field(panel(), "result")).getText().toString().contains("STALE_TREE"));
  }

  @Test
  public void mainDialogCancellationRevokesItsActualOwner() throws Exception {
    open();
    var controls = (VirtualScreenNativeControls) field(panel(), "controls");
    dialog().cancel();
    drain();
    assertNull(panel());
    assertEquals("NATIVE_CONTROL_REVOKED", controls.tree().optString("error"));
    assertEquals(0, fixture.actions.get());
    assertTrue(pending.queued.isEmpty());
  }

  @Test
  public void targetDialogCancellationRevokesWholeNodeSession() throws Exception {
    open();
    var controls = (VirtualScreenNativeControls) field(panel(), "controls");
    row().performClick();
    actionDialog().cancel();
    drain();
    assertNull(panel());
    assertEquals("NATIVE_CONTROL_REVOKED", controls.tree().optString("error"));
    assertEquals(0, fixture.actions.get());
  }

  @Test
  public void pauseBeforeQueuedActionExecutesRevokesAndResumeDoesNotResend() throws Exception {
    open();
    chooseClick();
    assertEquals(1, pending.queued.size());
    controller.pause();
    resumed = false;
    assertNull(panel());
    pending.one();
    drain();
    assertEquals(0, fixture.actions.get());
    controller.resume();
    resumed = true;
    drain();
    assertNull(panel());
    assertTrue(pending.queued.isEmpty());
    assertEquals(0, fixture.injected.get());
  }

  @Test
  public void lateTreeCallbackCannotReopenPanelAfterLeaving() throws Exception {
    button("节点操作").performClick();
    assertEquals(1, pending.queued.size());
    controller.pause();
    resumed = false;
    pending.one();
    drain();
    assertNull(panel());
    assertEquals(0, fixture.actions.get());
    controller.resume();
    resumed = true;
    drain();
    assertNull(panel());
  }
}
