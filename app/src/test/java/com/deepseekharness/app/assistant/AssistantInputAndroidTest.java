package com.deepseekharness.app.assistant;

import static org.junit.Assert.*;
import android.content.*;
import android.graphics.Rect;
import android.os.Looper;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.control.PhoneControlCoordinator;
import com.deepseekharness.app.util.OverlayTouchGuard;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.core.HarnessController;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.*;

/** Actual Android Button/Activity/EditText/IME flow; no guest, model, or permission expansion. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = AssistantInputAndroidTest.App.class,
    shadows = AssistantInputAndroidTest.NoRuntime.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AssistantInputAndroidTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static class NoRuntime {
    @Implementation
    protected static HarnessController get(Context context) {
      return Shadow.newInstanceOf(HarnessController.class);
    }

    @Implementation
    protected void addReadyWebPageListener(Runnable listener) {}

    @Implementation
    protected void removeReadyWebPageListener(Runnable listener) {}
  }

  private final AtomicInteger cancellations = new AtomicInteger();
  private AssistantSessionController session;
  private AssistantWindow window;
  private App app;

  @Before
  public void create() throws Exception {
    app = (App) RuntimeEnvironment.getApplication();
    ShadowSettings.setCanDrawOverlays(true);
    PhoneControlCoordinator.get(app).resume();
    JSONArray history = new JSONArray();
    for (int turn = 0; turn < 8; turn++) {
      StringBuilder text = new StringBuilder();
      for (int line = 0; line < 40; line++)
        text.append("History turn ").append(turn).append(" line ").append(line).append('\n');
      history.put(
          new JSONObject()
              .put("role", turn % 2 == 0 ? "user" : "assistant")
              .put("text", text.toString()));
    }
    AssistantBackend backend =
        new AssistantBackend() {
          public boolean current() {
            return true;
          }

          public JSONObject call(String method, JSONObject args) throws java.io.IOException {
            if (method.equals("sessions.cancel")) cancellations.incrementAndGet();
            try {
              return new JSONObject()
                  .put("sessionId", "full-session-input")
                  .put("hostInstanceId", "host-input")
                  .put("running", false)
                  .put("turnId", "7")
                  .put("taskId", "full-session-input")
                  .put("messages", history)
                  .put("accepted", true);
            } catch (org.json.JSONException failure) {
              throw new java.io.IOException(failure);
            }
          }

          public void returnConversation(AssistantBinding binding) {}
        };
    session =
        new AssistantSessionController(
            app,
            new AssistantBinding("host-input", "full-session-input", "input-epoch", 1),
            backend);
    drain();
  }

  private static Object field(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private void drain() throws Exception {
    ((ScheduledExecutorService) field(session, "worker")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
  }

  /** 越过展开/改宽后的防误触静置期。 */
  private static void settle() {
    Shadow.<ShadowLooper>extract(Looper.getMainLooper())
        .idleFor(java.time.Duration.ofMillis(OverlayTouchGuard.SETTLE_MILLIS + 50));
  }

  private Button windowButton(String name) throws Exception {
    return (Button) field(window, name);
  }

  @Test
  public void closeHidesTheWindowButKeepsTheTaskUsableAndShowRestoresTheCard() throws Exception {
    window = new AssistantWindow(app, session);
    window.show();
    session.expand();
    drain();
    settle();
    assertTrue(windowButton("closeButton").performClick());
    drain();
    assertFalse(window.visible());
    assertTrue(window.userHidden());
    assertTrue("隐藏后任务界面仍算可用（通知栏有停止与回复）", window.taskUiAvailable());
    assertFalse(PhoneControlCoordinator.get(app).snapshot().paused());
    assertEquals(0, cancellations.get());
    // 读屏前收走窗口的逻辑在用户隐藏时不再报错，也不会把窗口变回来。
    assertFalse(window.hideForScreen());
    window.showByUser();
    drain();
    assertTrue(window.visible());
    assertFalse(window.userHidden());
    assertTrue(session.snapshot().expanded());
  }

  @Test
  public void typingInsideTheCardMarksComposingAndBecomesFocusableOnlyWhileTyping()
      throws Exception {
    window = new AssistantWindow(app, session);
    window.show();
    session.expand();
    drain();
    settle();
    var params = (WindowManager.LayoutParams) field(window, "params");
    assertNotEquals(0, params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    EditText field = (EditText) field(window, "input");
    touch(field, MotionEvent.ACTION_DOWN, 10, 10);
    touch(field, MotionEvent.ACTION_UP, 10, 10);
    drain();
    assertTrue(session.composing());
    assertEquals(0, params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    assertFalse(PhoneControlCoordinator.get(app).snapshot().paused());
    field.setText("顺便把字体调大");
    assertEquals("顺便把字体调大", session.snapshot().draft());
    // 点到窗外：交还焦点，放开等待，草稿保留。
    View root = (View) field(window, "root");
    touch(root, MotionEvent.ACTION_OUTSIDE, -5, -5);
    drain();
    assertFalse(session.composing());
    assertNotEquals(0, params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    assertEquals("顺便把字体调大", session.snapshot().draft());
    assertEquals(0, cancellations.get());
  }

  @Test
  public void layoutSettleSwallowsTheTapThatExpandedThePanel() throws Exception {
    window = new AssistantWindow(app, session);
    window.show();
    settle();
    TextView status = (TextView) field(window, "status");
    assertTrue(status.performClick());
    drain();
    assertTrue(session.snapshot().expanded());
    // 同一根手指的后续点按落在刚出现的按钮上：静置期内全部忽略。
    assertTrue(windowButton("closeButton").performClick());
    assertTrue(windowButton("stopButton").performClick());
    drain();
    assertTrue(session.snapshot().expanded());
    assertEquals(0, cancellations.get());
    // 再点状态行不会收起：收起只走 ✕。
    settle();
    assertTrue(status.performClick());
    drain();
    assertTrue(session.snapshot().expanded());
    assertTrue(windowButton("minimizeButton").performClick());
    drain();
    assertFalse(session.snapshot().expanded());
    assertEquals(View.GONE, windowButton("closeButton").getVisibility());
    assertTrue(window.visible());
    assertFalse(PhoneControlCoordinator.get(app).snapshot().paused());
    assertEquals(0, cancellations.get());
  }

  private static void touch(View view, int action, float x, float y) {
    MotionEvent event =
        MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), action, x, y, 0);
    view.dispatchTouchEvent(event);
    event.recycle();
  }

  private static void resetSavedPosition() throws Exception {
    for (String name : new String[] {"savedX", "savedY"}) {
      Field field = AssistantWindow.class.getDeclaredField(name);
      field.setAccessible(true);
      field.setInt(null, -1);
    }
  }

  @Test
  public void draggingTheStatusMovesTheWindowWithoutExpandingOrPausing() throws Exception {
    resetSavedPosition();
    try {
      window = new AssistantWindow(app, session);
      window.show();
      drain();
      settle();
      TextView status = (TextView) field(window, "status");
      var params = (WindowManager.LayoutParams) field(window, "params");
      int startX = params.x, startY = params.y;
      // 向左上拖 300px：右下锚点的偏移增大。
      touch(status, MotionEvent.ACTION_DOWN, 20, 20);
      touch(status, MotionEvent.ACTION_MOVE, -100, -60);
      touch(status, MotionEvent.ACTION_MOVE, -280, -280);
      touch(status, MotionEvent.ACTION_UP, -280, -280);
      drain();
      assertTrue(params.x > startX);
      assertTrue(params.y > startY);
      assertFalse("A drag is not a tap", session.snapshot().expanded());
      assertFalse(PhoneControlCoordinator.get(app).snapshot().paused());
      assertEquals(0, cancellations.get());
      // 位置在下次出现时沿用。
      int movedX = params.x, movedY = params.y;
      window.close();
      window = new AssistantWindow(app, session);
      window.show();
      var reopened = (WindowManager.LayoutParams) field(window, "params");
      assertEquals(movedX, reopened.x);
      assertEquals(movedY, reopened.y);
    } finally {
      resetSavedPosition();
    }
  }

  @Test
  public void tinyMovementOnStatusIsStillATapThatExpands() throws Exception {
    resetSavedPosition();
    window = new AssistantWindow(app, session);
    window.show();
    drain();
    settle();
    TextView status = (TextView) field(window, "status");
    var params = (WindowManager.LayoutParams) field(window, "params");
    int startX = params.x, startY = params.y;
    touch(status, MotionEvent.ACTION_DOWN, 20, 20);
    touch(status, MotionEvent.ACTION_MOVE, 21, 21);
    touch(status, MotionEvent.ACTION_UP, 21, 21);
    drain();
    assertEquals(startX, params.x);
    assertEquals(startY, params.y);
    assertTrue(session.snapshot().expanded());
  }

  @Test
  public void stopNeedsTwoTapsAndOtherButtonsDisarmIt() throws Exception {
    window = new AssistantWindow(app, session);
    window.show();
    session.expand();
    drain();
    settle();
    Button stop = windowButton("stopButton");
    assertTrue(stop.performClick());
    drain();
    assertEquals(0, cancellations.get());
    assertNotEquals(AssistantSessionState.Phase.STOPPING, session.snapshot().phase());
    assertEquals(UiText.choose("再点停止", "Tap again"), stop.getText().toString());
    // 中间点了别的按钮（— 收起）：撤销待确认。
    assertTrue(windowButton("minimizeButton").performClick());
    drain();
    assertEquals(UiText.choose("停止", "Stop"), stop.getText().toString());
    session.expand();
    drain();
    settle();
    assertTrue(stop.performClick());
    // 超时自动撤销。
    Shadow.<ShadowLooper>extract(Looper.getMainLooper())
        .idleFor(java.time.Duration.ofMillis(OverlayTouchGuard.CONFIRM_MILLIS + 100));
    assertEquals(UiText.choose("停止", "Stop"), stop.getText().toString());
    assertTrue(stop.performClick());
    assertTrue(stop.performClick());
    drain();
    var phase = session.snapshot().phase();
    assertTrue(
        phase.toString(),
        phase == AssistantSessionState.Phase.STOPPING
            || phase == AssistantSessionState.Phase.STOPPED);
  }

  @Test
  public void returnConversationCollapsesWithoutPausingOrCancelling() throws Exception {
    window = new AssistantWindow(app, session);
    window.show();
    session.expand();
    drain();
    session.returnConversation();
    drain();
    assertFalse(session.snapshot().expanded());
    assertFalse(PhoneControlCoordinator.get(app).snapshot().paused());
    assertEquals(0, cancellations.get());
  }

  @Test
  public void composingNeverPausesOrCancelsAndOnlyItsOwnSurfaceReleasesIt() throws Exception {
    var coordinator = PhoneControlCoordinator.get(app);
    session.beginComposing("voice");
    drain();
    assertTrue(session.composing());
    assertFalse("输入时不暂停手机控制", coordinator.snapshot().paused());
    session.beginComposing("editor");
    session.endComposing("voice");
    assertTrue("语音交还编辑器后，旧来源的离开不能放开", session.composing());
    session.endComposing("editor");
    assertFalse(session.composing());
    assertEquals("Composing never cancels the conversation", 0, cancellations.get());
  }

  @Test
  public void sendingWhileComposingWakesTheWaitingActionWithTheNewMessage() throws Exception {
    var gate = PhoneControlCoordinator.get(app).compose();
    session.beginComposing("overlay");
    var waiting = Executors.newSingleThreadExecutor().submit(() -> gate.await(5_000, () -> true));
    Thread.sleep(50);
    assertFalse(waiting.isDone());
    // 本轮已结束（fixture 的 status 为 running=false）：走普通发送；接收后同样唤醒等待者。
    assertTrue(session.send("顺便把字体调大"));
    drain();
    assertEquals(
        com.deepseekharness.app.util.ComposeGate.Outcome.MESSAGE_ARRIVED,
        waiting.get(2, TimeUnit.SECONDS));
    assertFalse(session.composing());
    assertEquals(0, cancellations.get());
  }

  private static Rect boundsWithin(ViewGroup viewport, View child) {
    Rect bounds = new Rect(0, 0, child.getWidth(), child.getHeight());
    viewport.offsetDescendantRectToMyCoords(child, bounds);
    return bounds;
  }

  private static void layoutViewport(ViewGroup viewport, int heightDp) {
    float density = viewport.getResources().getDisplayMetrics().density;
    int width = Math.round(360 * density);
    int height = Math.round(heightDp * density);
    viewport.forceLayout();
    viewport.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    viewport.layout(0, 0, width, height);
  }

  @After
  public void close() {
    if (window != null) window.close();
    if (session != null) session.close();
  }

  @Test
  public void expandedOverlayInputHandsOffToBoundEditorAndReleasesWindowFocus() throws Exception {
    window = new AssistantWindow(app, session);
    window.show();
    session.expand();
    drain();
    // 展开只改界面：不暂停手机操作，也不取消对话。
    assertFalse(PhoneControlCoordinator.get(app).snapshot().paused());
    assertEquals(0, cancellations.get());
    settle();
    int before = cancellations.get();
    Button editor = windowButton("editorButton");
    assertTrue(editor.performClick());
    drain();
    Intent started = Shadow.<ShadowApplication>extract(app).getNextStartedActivity();
    assertEquals(AssistantActivity.class.getName(), started.getComponent().getClassName());
    assertTrue(started.getBooleanExtra("bound", false));
    assertTrue(started.getBooleanExtra("focus_input", false));
    View root = (View) field(window, "root");
    assertEquals(View.GONE, root.getVisibility());
    WindowManager.LayoutParams flags = (WindowManager.LayoutParams) field(window, "params");
    assertNotEquals(0, flags.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    // 其他应用的输入法不能推动底部悬浮助手。
    assertEquals(0, flags.getFitInsetsTypes() & WindowInsets.Type.ime());
    assertNotEquals(
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        flags.softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
    assertFalse(session.snapshot().expanded());
    // 打开编辑器：标记用户正在输入，手机动作原地等待；不暂停、不取消。
    assertFalse(PhoneControlCoordinator.get(app).snapshot().paused());
    assertTrue(session.composing());
    assertEquals(
        "Opening the editor must not issue another background cancellation",
        before,
        cancellations.get());
    TextView status = (TextView) field(window, "status");
    assertEquals(1, status.getMaxLines());
  }

  @Test
  public void activityComposerOwnsImeSharedDraftAndSurvivesPeriodicRenderThenReleases()
      throws Exception {
    Intent intent = AssistantWindow.editorIntent(app);
    var host = Robolectric.buildActivity(AssistantActivity.class, intent);
    host.get().setTheme(R.style.Theme_DeepseekHarness);
    try {
      host.setup().visible().windowFocusChanged(true);
      drain();
      AssistantActivity activity = host.get();
      EditText input = (EditText) field(activity, "input");
      assertTrue("The actual EditText must own focus", input.hasFocus());
      ViewGroup viewport = activity.findViewById(android.R.id.content);
      TextView history = (TextView) field(activity, "messages");
      ScrollView historyScroll = (ScrollView) field(activity, "pageScroll");
      Button send = (Button) field(activity, "sendButton");
      assertTrue(history.getText().toString().contains("History turn 7 line 39"));
      // Traverse the actual Android layout first at full height, then with the
      // smaller viewport delivered by adjustResize when a keyboard is visible.
      for (int height : new int[] {640, 360}) {
        layoutViewport(viewport, height);
        Rect composer = boundsWithin(viewport, input);
        Rect sendAction = boundsWithin(viewport, send);
        Rect transcript = boundsWithin(viewport, historyScroll);
        assertTrue("The composer must remain attached", input.isAttachedToWindow());
        assertTrue(
            "The history must genuinely exceed several screens",
            history.getHeight() > 3 * historyScroll.getHeight());
        assertTrue(historyScroll.getHeight() > 0);
        assertTrue(
            "The composer must have usable measured bounds",
            composer.width() > 0 && composer.height() > 0);
        assertTrue(
            "Long history must not push the composer below the viewport",
            composer.top >= 0 && composer.bottom <= viewport.getHeight());
        assertTrue(
            "The send action must also remain reachable",
            sendAction.height() > 0 && sendAction.bottom <= viewport.getHeight());
        assertTrue("History must end above the composer", transcript.bottom <= composer.top);
        historyScroll.scrollTo(0, history.getHeight());
        assertTrue("The history must actually scroll", historyScroll.getScrollY() > 0);
        assertEquals(
            "History scrolling must not move the composer",
            composer,
            boundsWithin(viewport, input));
        assertTrue(input.hasFocus());
      }
      var ime = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
      assertTrue(
          "An explicit editor tap must request the real Android IME API",
          Shadow.<ShadowInputMethodManager>extract(ime).isSoftInputVisible());
      input.setText("PINGOVERLAY196");
      drain();
      assertEquals("PINGOVERLAY196", session.snapshot().draft());
      var render = AssistantActivity.class.getDeclaredMethod("renderBound");
      render.setAccessible(true);
      render.invoke(activity);
      drain();
      assertSame(input, field(activity, "input"));
      assertTrue(input.hasFocus());
      assertEquals("PINGOVERLAY196", input.getText().toString());
      assertFalse(((TextView) field(activity, "messages")).isFocusable());
      assertEquals(
          "Editor admission does not cancel an idle or running model task", 0, cancellations.get());
      // Queue another real IME request just before leaving. A late callback must
      // neither restore the editor's focus nor show a keyboard after onPause.
      var pending = AssistantActivity.class.getDeclaredField("pendingInput");
      pending.setAccessible(true);
      pending.setBoolean(activity, true);
      var focus = AssistantActivity.class.getDeclaredMethod("focusComposer");
      focus.setAccessible(true);
      focus.invoke(activity);
      host.pause().stop();
      drain();
      assertFalse(input.hasFocus());
      assertFalse(input.isFocusable());
      assertEquals(
          ViewGroup.FOCUS_BLOCK_DESCENDANTS,
          ((ViewGroup) field(activity, "content")).getDescendantFocusability());
      assertFalse(Shadow.<ShadowInputMethodManager>extract(ime).isSoftInputVisible());
      // 未发送就离开编辑器：放回手机操作，但下一步必须先重新观察；对话从未被取消。
      var authority = PhoneControlCoordinator.get(app).snapshot();
      assertFalse(authority.paused());
      assertTrue(authority.needsObservation());
      assertEquals(0, cancellations.get());
    } finally {
      host.destroy();
    }
  }
}
