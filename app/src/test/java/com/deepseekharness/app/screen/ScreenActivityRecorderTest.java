package com.deepseekharness.app.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import com.deepseekharness.app.control.ControlTaskAuthority;
import com.deepseekharness.app.util.ScreenTarget;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 回执包装与 history 回执的 JSON 契约：每次回执只带一次最近活动，别的任务读不到本任务事件。 */
@RunWith(ScreenActivityRecorderTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class ScreenActivityRecorderTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  private static final ControlTaskAuthority.Binding TASK =
      new ControlTaskAuthority.Binding(
          ControlTaskAuthority.Owner.LOCAL_DSH, "connection", "session-history", "1");
  private static final ScreenTarget TARGET = new ScreenTarget(0, 7, "com.example.app");

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    ScreenActivityRecorder.clear();
  }

  @After
  public void tearDown() {
    ScreenActivityRecorder.clear();
  }

  @Test
  public void recentActivityIsAttachedOncePerReceipt() throws Exception {
    ScreenActivityRecorder.observeTask(context, TASK);
    ScreenActivityRecorder.assistantGesture("tap", TARGET, 10, 20, 10, 20);

    String first = ScreenActivityRecorder.attach("{\"ok\":true}", TASK);
    JSONArray activity = new JSONObject(first).getJSONArray("recentActivity");
    assertEquals(1, activity.length());
    JSONObject event = activity.getJSONObject(0);
    assertEquals("ASSISTANT_ACTION", event.getString("kind"));
    assertEquals("tap", event.getString("action"));
    assertEquals("com.example.app", event.getString("target"));
    assertEquals(10, event.getJSONArray("at").getInt(0));
    assertEquals(20, event.getJSONArray("at").getInt(1));

    String second = ScreenActivityRecorder.attach("{\"ok\":true}", TASK);
    assertEquals(0, new JSONObject(second).getJSONArray("recentActivity").length());
  }

  @Test
  public void aTaskEndClearsTheHistoryAndAnotherTaskCannotReadIt() throws Exception {
    ScreenActivityRecorder.observeTask(context, TASK);
    ScreenActivityRecorder.assistantKey("home", TARGET);
    assertEquals(1, events(ScreenActivityRecorder.history("0", "20", TASK.identity())).length());

    JSONObject other = new JSONObject(ScreenActivityRecorder.history("0", "20", "other-task"));
    assertFalse(other.getBoolean("recording"));
    assertEquals(0, other.getJSONArray("events").length());

    ScreenActivityRecorder.clearIfTask("other-task");
    assertEquals(1, events(ScreenActivityRecorder.history("0", "20", TASK.identity())).length());

    ScreenActivityRecorder.clearIfTask(TASK.identity());
    JSONObject ended = new JSONObject(ScreenActivityRecorder.history("0", "20", TASK.identity()));
    assertFalse(ended.getBoolean("recording"));
    assertEquals(0, ended.getJSONArray("events").length());
  }

  @Test
  public void historyReportsTheDocumentedMetadata() throws Exception {
    ScreenActivityRecorder.observeTask(context, TASK);
    ScreenActivityRecorder.assistantGesture("swipe", TARGET, 5, 6, 7, 8);
    JSONObject history = new JSONObject(ScreenActivityRecorder.history("0", "20", TASK.identity()));
    assertTrue(history.getBoolean("ok"));
    assertEquals("dsha-screen-activity-v1", history.getString("kind"));
    assertTrue(history.getBoolean("recording"));
    assertEquals(0, history.getInt("since"));
    assertEquals(20, history.getInt("limit"));
    assertTrue(history.getLong("nextSince") > 0);
    assertFalse(history.getBoolean("truncated"));
    assertEquals(0, history.getJSONArray("frames").length());
    assertFalse(history.getBoolean("framesAvailable"));
    JSONObject event = history.getJSONArray("events").getJSONObject(0);
    assertEquals(history.getLong("nextSince"), event.getLong("seq"));
    assertEquals("ASSISTANT_ACTION", event.getString("kind"));
    assertEquals("swipe", event.getString("action"));
    assertTrue(event.has("t"));
  }

  @Test
  public void historyParameterErrorsAreNotExecuted() throws Exception {
    JSONObject since = new JSONObject(ScreenActivityRecorder.history("-1", "20", TASK.identity()));
    assertFalse(since.getBoolean("ok"));
    assertEquals("NOT_SENT", since.getString("state"));
    assertEquals("SCREEN_HISTORY_INVALID_SINCE", since.getString("error"));

    JSONObject limit = new JSONObject(ScreenActivityRecorder.history("0", "101", TASK.identity()));
    assertFalse(limit.getBoolean("ok"));
    assertEquals("SCREEN_HISTORY_INVALID_LIMIT", limit.getString("error"));
    assertEquals("NOT_SENT", limit.getString("state"));
  }

  @Test
  public void nonJsonReceiptsAreLeftUntouched() {
    assertEquals(
        "[ERR] SCREEN_RUN_CHANGED",
        ScreenActivityRecorder.attach("[ERR] SCREEN_RUN_CHANGED", TASK));
    assertEquals("OK", ScreenActivityRecorder.attach("OK", TASK));
    assertEquals("", ScreenActivityRecorder.attach("", TASK));
  }

  private static JSONArray events(String reply) throws Exception {
    return new JSONObject(reply).getJSONArray("events");
  }
}
