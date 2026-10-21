package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.util.*;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.*;
import org.robolectric.util.ReflectionHelpers;

/** Real native lifecycle owner/Manager/authenticated Core/lease; only platform windows are synthetic. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = android.app.Application.class,
    shadows = VirtualScreenNativeControlsTest.FreshWindows.class)
public final class VirtualScreenNativeControlsTest {
  @org.junit.Before
  public void standardOnly() {
    org.junit.Assume.assumeFalse(com.deepseekharness.app.BuildConfig.LOW_ANDROID);
  }

  public static final String CORE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
      PKG = "com.example.calculator";
  public static final int DISPLAY = 51, WINDOW = 20;

  public static class OwnerActivity extends AppCompatActivity {
    @Override
    public void onCreate(android.os.Bundle saved) {
      setTheme(com.deepseekharness.app.R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
    }
  }

  @Implements(AccessibilityService.class)
  public static class FreshWindows extends ShadowAccessibilityService {
    static AccessibilityServiceInfo info;
    static SparseArray<List<AccessibilityWindowInfo>> originals = new SparseArray<>();
    static Runnable nextRead;

    @Implementation
    protected AccessibilityServiceInfo getServiceInfo() {
      return info;
    }

    @Implementation
    protected SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
      if (nextRead != null) {
        var callback = nextRead;
        nextRead = null;
        callback.run();
      }
      var result = new SparseArray<List<AccessibilityWindowInfo>>();
      for (int i = 0; i < originals.size(); i++) {
        var copies = new ArrayList<AccessibilityWindowInfo>();
        for (var window : originals.valueAt(i)) copies.add(AccessibilityWindowInfo.obtain(window));
        result.put(originals.keyAt(i), copies);
      }
      return result;
    }
  }

  static ShadowAccessibilityNodeInfo nodeShadow(AccessibilityNodeInfo node) {
    return Shadow.extract(node);
  }

  static ShadowAccessibilityWindowInfo windowShadow(AccessibilityWindowInfo window) {
    return Shadow.extract(window);
  }

  static void set(Object owner, String name, Object value) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  @SuppressWarnings("unchecked")
  static ScreenConnectionEpoch<DshaAccessibilityService> connection() throws Exception {
    var f = DshaAccessibilityService.class.getDeclaredField("CONNECTION");
    f.setAccessible(true);
    return (ScreenConnectionEpoch<DshaAccessibilityService>) f.get(null);
  }

  /** Shared fixture for independent tests; protocol, owner, claims and actions remain production. */
  public static final class Fixture implements AutoCloseable {
    final DshaAccessibilityService service = new DshaAccessibilityService();
    final AtomicInteger injected = new AtomicInteger(), actions = new AtomicInteger();
    final VirtualScreenManager manager =
        new VirtualScreenManager(
            null,
            () -> 100,
            new VirtualScreenManager.Visuals() {
              public void present() {}

              public void stopped(long fence) {}
            });
    final AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
    final AccessibilityNodeInfo root = node("layout", new Rect(0, 0, 1008, 1792), false);
    final AccessibilityNodeInfo button = node("5", new Rect(100, 100, 200, 200), true);
    VirtualScreenCore.HostControl core;
    ActivityController<OwnerActivity> activity;
    VirtualScreenDisplaySession display;
    VirtualScreenNativeControls controls;
    boolean connected, closed;

    public Fixture() throws Exception {
      this(false);
    }

    Fixture(boolean rejectCreate) throws Exception {
      try {
        var info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ReflectionHelpers.setField(
            info, "mCapabilities", AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
        FreshWindows.info = info;
        FreshWindows.nextRead = null;
        FreshWindows.originals = new SparseArray<>();
        var w = windowShadow(window);
        w.setId(WINDOW);
        w.setDisplayId(DISPLAY);
        w.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        w.setFocused(true);
        w.setActive(true);
        w.setBoundsInScreen(new Rect(0, 0, 1008, 1792));
        w.setRoot(root);
        nodeShadow(root).addChild(button);
        FreshWindows.originals.put(DISPLAY, List.of(window));
        connection().connect(service);
        connected = true;
        nodeShadow(button)
            .setOnPerformActionListener(
                (action, args) -> {
                  actions.incrementAndGet();
                  return true;
                });
        core =
            VirtualScreenCore.bindControlForHost(
                VirtualScreenCoreIdentity.parse(
                    "123:456:2000:11111111-2222-3333-4444-555555555555:" + CORE),
                (width, height) -> {
                  if (rejectCreate) return null;
                  display =
                      new VirtualScreenDisplaySession(
                          width,
                          height,
                          DISPLAY,
                          VirtualScreenInput.commandForTest(
                              DISPLAY,
                              (argv, timeout) -> {
                                injected.incrementAndGet();
                                return VirtualScreenInputResult.REJECTED;
                              }));
                  display.frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                  display.frame.eraseColor(0xff123456);
                  display.sequence = 237;
                  return display;
                },
                () -> PKG,
                android.os.SystemClock::elapsedRealtime,
                () -> {});
        var endpoint = core.handshake();
        JSONObject created =
            new VirtualScreenTransport()
                .request(endpoint, "/vscreen/create", "width=1008&height=1792");
        assertTrue(created.toString(), created.optBoolean("ok"));
        set(manager, "core", endpoint);
        set(manager, "port", endpoint.port);
        set(manager, "token", endpoint.token);
        set(manager, "generation", created.getString("generation"));
        set(manager, "activeEpoch", 0L);
        set(manager, "channel", "shizuku");
        set(manager, "lifecycleState", VirtualScreenLifecycleState.READY);
        activity = Robolectric.buildActivity(OwnerActivity.class);
        activity.create().start().resume();
        controls = manager.openNativeControls(activity.get());
      } catch (Exception | Error failure) {
        try {
          close();
        } catch (Exception | Error cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    AccessibilityNodeInfo node(String text, Rect bounds, boolean click) {
      var node = AccessibilityNodeInfo.obtain(new View(RuntimeEnvironment.getApplication()));
      node.setPackageName(PKG);
      node.setClassName(click ? "android.widget.Button" : "android.view.View");
      node.setText(text);
      node.setBoundsInScreen(bounds);
      node.setVisibleToUser(true);
      node.setEnabled(true);
      node.setClickable(click);
      if (click) node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
      nodeShadow(node).setAccessibilityWindowInfo(window);
      return node;
    }

    JSONObject buttonRow(JSONObject tree) throws Exception {
      assertTrue(tree.toString(), tree.optBoolean("ok"));
      for (int i = 0; i < tree.getJSONArray("nodes").length(); i++) {
        JSONObject row = tree.getJSONArray("nodes").getJSONObject(i);
        if (row.optBoolean("clickable")) return row;
      }
      throw new AssertionError("Missing button: " + tree);
    }

    String id() throws Exception {
      return buttonRow(controls.tree()).getString("nodeId");
    }

    public void close() throws Exception {
      if (closed) return;
      closed = true;
      var cleanup = new ArrayList<AutoCloseable>();
      if (controls != null) cleanup.add(controls::close);
      if (activity != null) {
        cleanup.add(() -> activity.pause());
        cleanup.add(() -> activity.stop());
        cleanup.add(() -> activity.destroy());
      }
      if (connected) cleanup.add(() -> connection().disconnect(service));
      cleanup.add(
          () -> {
            var worker = VirtualScreenManager.class.getDeclaredField("WORKER");
            worker.setAccessible(true);
            ((ScheduledExecutorService) worker.get(manager)).shutdownNow();
          });
      if (core != null) cleanup.add(core::close);
      cleanup.add(window::recycle);
      cleanup.add(root::recycle);
      cleanup.add(button::recycle);
      cleanup.add(
          () -> {
            FreshWindows.originals.clear();
            FreshWindows.nextRead = null;
          });
      Throwable failure = null;
      for (var action : cleanup)
        try {
          action.close();
        } catch (Exception | Error problem) {
          if (failure == null) failure = problem;
          else failure.addSuppressed(problem);
        }
      if (failure instanceof Exception exception) throw exception;
      if (failure instanceof Error error) throw error;
    }
  }

  @Test
  public void rejectedCreateDuringConstructorReleasesOwnCoreBeforeNextFixture() throws Exception {
    AssertionError rejected = assertThrows(AssertionError.class, () -> new Fixture(true));
    assertTrue(rejected.getMessage(), rejected.getMessage().contains("\"ok\":false"));
    try (var next = new Fixture()) {
      assertTrue(next.controls.tree().optBoolean("ok"));
      assertEquals(0, next.injected.get());
      assertEquals(0, next.actions.get());
    }
  }

  @Test
  public void nativeTreeAndOneUseClickRemainUsableWhenPixelsAdvance() throws Exception {
    try (var f = new Fixture()) {
      JSONObject tree = f.controls.tree(), row = f.buttonRow(tree);
      assertEquals(CORE, tree.getString("coreInstance"));
      assertEquals(DISPLAY, tree.getInt("displayId"));
      assertEquals(PKG, tree.getString("packageName"));
      assertEquals(WINDOW, tree.getInt("windowId"));
      assertEquals(30000, tree.getInt("treeTtlMs"));
      assertTrue(row.getBoolean("enabled"));
      String id = row.getString("nodeId");
      f.display.sequence = 238;
      JSONObject clicked = f.controls.node(id, "click", "");
      assertTrue(clicked.toString(), clicked.optBoolean("ok"));
      assertEquals(1, f.actions.get());
      assertEquals(0, f.injected.get());
      assertFalse(f.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(1, f.actions.get());
    }
  }

  @Test
  public void nativeSetTextPassesUnicodeExactlyOnceWithoutChangingModeOrCoordinates()
      throws Exception {
    try (var f = new Fixture()) {
      f.button.setEditable(true);
      f.button.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
      String text = "中文 %s & = 5";
      var received = new java.util.concurrent.atomic.AtomicReference<String>();
      nodeShadow(f.button)
          .setOnPerformActionListener(
              (action, args) -> {
                assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, action);
                f.actions.incrementAndGet();
                received.set(
                    args.getCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                        .toString());
                return true;
              });
      JSONObject row = f.buttonRow(f.controls.tree());
      assertTrue(row.getBoolean("setTextSupported"));
      assertTrue(f.controls.node(row.getString("nodeId"), "set_text", text).optBoolean("ok"));
      assertEquals(text, received.get());
      assertEquals(1, f.actions.get());
      assertEquals(0, f.injected.get());
    }
  }

  @Test
  public void refreshedTreeAndAnotherOwnerCannotConsumeOldIds() throws Exception {
    try (var f = new Fixture()) {
      String old = f.id();
      String fresh = f.id();
      assertFalse(f.controls.node(old, "click", "").optBoolean("ok"));
      try (var other = f.manager.openNativeControls(f.activity.get())) {
        other.tree();
        assertFalse(other.node(fresh, "click", "").optBoolean("ok"));
      }
      assertTrue(f.controls.node(fresh, "click", "").optBoolean("ok"));
      assertEquals(1, f.actions.get());
    }
  }

  @Test
  public void nativeTextTruncationMetadataUsesOriginalTextAt9991000And1001() throws Exception {
    for (int length : new int[] {999, 1000, 1001}) {
      try (var f = new Fixture()) {
        f.button.setEditable(true);
        f.button.setText("字".repeat(length));
        f.button.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
        JSONObject row = f.buttonRow(f.controls.tree());
        assertEquals(Math.min(length, 1000), row.getString("text").length());
        assertEquals(length > 1000, row.getBoolean("textTruncated"));
        assertTrue(row.getBoolean("setTextSupported"));
        assertEquals(0, f.actions.get());
        assertEquals(0, f.injected.get());
        JSONObject shared =
            new VirtualScreenAccessibility().run(f.service, DISPLAY, "tree", new JSONObject());
        for (int i = 0; i < shared.getJSONArray("nodes").length(); i++)
          assertFalse(shared.getJSONArray("nodes").getJSONObject(i).has("textTruncated"));
      }
    }
  }

  @Test
  public void passwordDoesNotExposeTextOrTruncationLength() throws Exception {
    try (var f = new Fixture()) {
      f.button.setEditable(true);
      f.button.setPassword(true);
      f.button.setText("private".repeat(500));
      JSONObject row = f.buttonRow(f.controls.tree());
      assertEquals("", row.getString("text"));
      assertFalse(row.getBoolean("textTruncated"));
      assertFalse(row.getBoolean("setTextSupported"));
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  public void pausePermanentlyRevokesOwnerEvenAfterResume() throws Exception {
    try (var f = new Fixture()) {
      String id = f.id();
      f.activity.pause().resume();
      assertFalse(f.controls.node(id, "click", "").optBoolean("ok"));
      assertFalse(f.controls.tree().optBoolean("ok"));
      assertEquals(0, f.actions.get());
      try (var fresh = f.manager.openNativeControls(f.activity.get())) {
        assertTrue(fresh.tree().optBoolean("ok"));
      }
    }
  }

  @Test
  public void closedOwnerCannotReadOrWriteAgain() throws Exception {
    try (var f = new Fixture()) {
      String id = f.id();
      f.controls.close();
      assertFalse(f.controls.node(id, "click", "").optBoolean("ok"));
      assertFalse(f.controls.tree().optBoolean("ok"));
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  public void samePortTokenWithChangedCoreUuidRevokesOwner() throws Exception {
    try (var f = new Fixture()) {
      String id = f.id();
      var old = f.core.handshake();
      var changed =
          new VirtualScreenHandshake(
              old.port,
              old.token,
              VirtualScreenCoreIdentity.parse(
                  "123:456:2000:11111111-2222-3333-4444-555555555555:ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee"));
      set(f.manager, "core", changed);
      assertFalse(f.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  public void changedGenerationRevokesOwner() throws Exception {
    try (var f = new Fixture()) {
      String id = f.id();
      set(f.manager, "generation", UUID.randomUUID().toString());
      assertFalse(f.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  public void reconnectedAccessibilityEpochRevokesOwner() throws Exception {
    try (var f = new Fixture()) {
      String id = f.id();
      connection().connect(f.service);
      assertFalse(f.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  public void changedWindowAndPackageRejectBeforeAction() throws Exception {
    try (var f = new Fixture()) {
      String id = f.id();
      f.root.setPackageName("com.example.other");
      assertFalse(f.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(0, f.actions.get());
    }
    try (var f = new Fixture()) {
      String id = f.id();
      windowShadow(f.window).setId(WINDOW + 1);
      assertFalse(f.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  public void expiredTreeNeverExecutes() throws Exception {
    try (var f = new Fixture()) {
      String id = f.id();
      ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(31));
      assertEquals("STALE_TREE", f.controls.node(id, "click", "").optString("error"));
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  public void exceptionAfterPlatformAdmissionIsUnknownAndNeverReplayed() throws Exception {
    try (var f = new Fixture()) {
      nodeShadow(f.button)
          .setOnPerformActionListener(
              (action, args) -> {
                f.actions.incrementAndGet();
                throw new IllegalStateException("unknown");
              });
      String id = f.id();
      JSONObject value = f.controls.node(id, "click", "");
      assertTrue(value.toString(), value.optBoolean("executionUnknown"));
      assertFalse(f.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(1, f.actions.get());
      assertEquals(0, f.injected.get());
    }
  }

  @Test
  public void closingInsidePlatformActionReturnsUnknownAndNoSecondDispatch() throws Exception {
    try (var f = new Fixture()) {
      nodeShadow(f.button)
          .setOnPerformActionListener(
              (action, args) -> {
                f.actions.incrementAndGet();
                f.controls.close();
                return true;
              });
      String id = f.id();
      JSONObject value = f.controls.node(id, "click", "");
      assertTrue(value.toString(), value.optBoolean("executionUnknown"));
      assertFalse(f.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(1, f.actions.get());
    }
  }

  @Test
  public void clickThatChangesWindowIsUnknownAndNeverReplayed() throws Exception {
    try (var f = new Fixture()) {
      nodeShadow(f.button)
          .setOnPerformActionListener(
              (action, args) -> {
                f.actions.incrementAndGet();
                windowShadow(f.window).setId(WINDOW + 1);
                return true;
              });
      String id = f.id();
      JSONObject value = f.controls.node(id, "click", "");
      assertTrue(value.toString(), value.optBoolean("executionUnknown"));
      assertFalse(f.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(1, f.actions.get());
      assertEquals(0, f.injected.get());
    }
  }

  @Test
  public void disabledOrPasswordEditorCannotWrite() throws Exception {
    try (var f = new Fixture()) {
      f.button.setEnabled(false);
      String id = f.id();
      assertFalse(f.controls.node(id, "click", "").optBoolean("ok"));
      assertEquals(0, f.actions.get());
    }
    try (var f = new Fixture()) {
      f.button.setEditable(true);
      f.button.setPassword(true);
      f.button.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
      JSONObject row = f.buttonRow(f.controls.tree());
      assertTrue(row.getBoolean("password"));
      assertFalse(row.getBoolean("setTextSupported"));
      assertFalse(f.controls.node(row.getString("nodeId"), "set_text", "private").optBoolean("ok"));
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  public void rawNullContextAndBridgeCannotBorrowNativeOwner() throws Exception {
    try (var f = new Fixture()) {
      assertEquals(
          "EXPLICIT_SCREEN_REQUEST_REQUIRED",
          new VirtualScreenSemanticAdapter()
              .control(null, null, () -> true, DISPLAY, "tree", new JSONObject())
              .optString("error"));
      assertEquals(
          "EXPLICIT_SCREEN_REQUEST_REQUIRED",
          new JSONObject(f.manager.bridge("/app/vscreen/tree", "")).optString("error"));
      assertTrue(f.controls.tree().optBoolean("ok"));
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  public void ownerOpenedWhileNotResumedIsPermanentlyRejected() throws Exception {
    try (var f = new Fixture()) {
      f.activity.pause();
      try (var rejected = f.manager.openNativeControls(f.activity.get())) {
        f.activity.resume();
        assertFalse(rejected.tree().optBoolean("ok"));
        assertEquals(0, f.actions.get());
      }
    }
  }
}
