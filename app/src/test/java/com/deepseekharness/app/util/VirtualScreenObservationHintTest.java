package com.deepseekharness.app.util;

import static com.deepseekharness.app.util.VirtualScreenObservationHint.Accessibility.CONNECTED;
import static com.deepseekharness.app.util.VirtualScreenObservationHint.Accessibility.ENABLED_NOT_CONNECTED;
import static com.deepseekharness.app.util.VirtualScreenObservationHint.Accessibility.NOT_ENABLED;
import static com.deepseekharness.app.util.VirtualScreenObservationHint.Accessibility.UNKNOWN_NOT_CONNECTED;
import static org.junit.Assert.*;

import com.deepseekharness.app.util.VirtualScreenObservationHint.Hint;
import java.util.List;
import org.junit.Test;

public class VirtualScreenObservationHintTest {
  private static Hint frame(
      VirtualScreenObservationHint.Accessibility a, String diagnostic, String pkg, long since) {
    return VirtualScreenObservationHint.frameNotReady("FRAME_NOT_READY", a, diagnostic, pkg, since);
  }

  @Test
  public void accessibilityStateKeepsUnknownSeparateFromDisabled() {
    assertEquals(CONNECTED, VirtualScreenObservationHint.Accessibility.of(true, "NO"));
    assertEquals(NOT_ENABLED, VirtualScreenObservationHint.Accessibility.of(false, "NO"));
    assertEquals(
        ENABLED_NOT_CONNECTED, VirtualScreenObservationHint.Accessibility.of(false, "YES"));
    assertEquals(
        UNKNOWN_NOT_CONNECTED, VirtualScreenObservationHint.Accessibility.of(false, "UNKNOWN"));
  }

  @Test
  public void onlyFrameNotReadyIsRefined() {
    assertNull(
        VirtualScreenObservationHint.frameNotReady("STALE_FRAME", CONNECTED, "NO_WINDOWS", "", 1));
    assertNull(VirtualScreenObservationHint.frameNotReady("", CONNECTED, "NO_WINDOWS", "", 1));
  }

  @Test
  public void emptyDisplayAsksToLaunchFirstEvenLongAfterCreate() {
    // 现场：READY、无窗口、Core 也报不出前台包名。时长不影响判断：空屏不会自己出帧。
    for (long since : new long[] {-1, 0, 500, 60_000}) {
      Hint hint = frame(CONNECTED, "NO_WINDOWS", "", since);
      assertEquals("VSCREEN_NO_WINDOW", hint.error);
      assertEquals("NONE", hint.windowState);
      assertEquals("ACCESSIBILITY", hint.windowEvidence);
      assertEquals("LAUNCH_APP_FIRST", hint.nextAction);
      assertTrue(hint.retryable);
    }
  }

  @Test
  public void justLaunchedAppWaitsForFirstFrame() {
    Hint pending = frame(CONNECTED, "READABLE", "com.android.settings", 800);
    assertEquals("FRAME_PENDING", pending.error);
    assertEquals("PRESENT", pending.windowState);
    assertEquals("WAIT_AND_SEE_AGAIN", pending.nextAction);
    // 窗口列表尚空但 Core 已看到任务：仍是首帧等待，而不是空屏。
    Hint registering = frame(CONNECTED, "NO_WINDOWS", "com.android.settings", 300);
    assertEquals("FRAME_PENDING", registering.error);
    assertEquals("PRESENT", registering.windowState);
    // 时长未知（例如进程刚重建）按等待处理，不贸然判停滞。
    assertEquals("FRAME_PENDING", frame(CONNECTED, "READABLE", "", -1).error);
  }

  @Test
  public void windowWithoutFramesPastGraceIsStalled() {
    long late = VirtualScreenObservationHint.FRAME_PENDING_MS;
    Hint hint = frame(CONNECTED, "READABLE", "com.android.settings", late);
    assertEquals("FRAME_STALLED", hint.error);
    assertEquals("REPORT_TO_USER", hint.nextAction);
    assertTrue(hint.retryable);
    assertEquals(
        "FRAME_PENDING", frame(CONNECTED, "READABLE", "com.android.settings", late - 1).error);
    // 不可读但存在的窗口同样算有窗口。
    assertEquals("FRAME_STALLED", frame(CONNECTED, "ROOT_UNAVAILABLE", "", late).error);
    assertEquals("FRAME_STALLED", frame(CONNECTED, "NO_UNIQUE_APPLICATION_WINDOW", "", late).error);
  }

  @Test
  public void withoutAccessibilityOnlyCoreTasksDecide() {
    // 无障碍关闭时，窗口诊断不可信：有前台包名按等待/停滞，没有则承认无法区分。
    Hint unknown = frame(NOT_ENABLED, "ACCESSIBILITY_UNAVAILABLE", "", 20_000);
    assertEquals("FRAME_NOT_READY", unknown.error);
    assertEquals("UNKNOWN", unknown.windowState);
    assertEquals("CORE_TASKS", unknown.windowEvidence);
    assertEquals("LAUNCH_APP_OR_WAIT", unknown.nextAction);
    // 断开时残留的 NO_WINDOWS 诊断不能被当成空屏证据。
    assertEquals("UNKNOWN", frame(ENABLED_NOT_CONNECTED, "NO_WINDOWS", "", 1).windowState);
    Hint pending = frame(NOT_ENABLED, "ACCESSIBILITY_UNAVAILABLE", "com.android.settings", 100);
    assertEquals("FRAME_PENDING", pending.error);
    assertEquals("CORE_TASKS", pending.windowEvidence);
  }

  @Test
  public void anchorReasonsAreSplit() {
    assertEquals(
        "ANCHOR_ACCESSIBILITY_NOT_ENABLED",
        VirtualScreenObservationHint.anchorUnavailable(NOT_ENABLED, "X", true, false, false));
    assertEquals(
        "ANCHOR_ACCESSIBILITY_NOT_CONNECTED",
        VirtualScreenObservationHint.anchorUnavailable(
            ENABLED_NOT_CONNECTED, "X", true, false, false));
    assertEquals(
        "ANCHOR_ACCESSIBILITY_NOT_CONNECTED",
        VirtualScreenObservationHint.anchorUnavailable(
            UNKNOWN_NOT_CONNECTED, "X", true, false, false));
    assertEquals(
        "ANCHOR_REQUEST_EVIDENCE_REQUIRED",
        VirtualScreenObservationHint.anchorUnavailable(CONNECTED, "READABLE", false, false, false));
    assertEquals(
        "ANCHOR_NO_WINDOW",
        VirtualScreenObservationHint.anchorUnavailable(
            CONNECTED, "NO_WINDOWS", true, false, false));
    assertEquals(
        "ANCHOR_TARGET_EVIDENCE_UNAVAILABLE",
        VirtualScreenObservationHint.anchorUnavailable(
            CONNECTED, "ROOT_UNAVAILABLE", true, false, false));
    assertEquals(
        "ANCHOR_CONSENT_EVIDENCE_UNAVAILABLE",
        VirtualScreenObservationHint.anchorUnavailable(CONNECTED, "READABLE", true, true, false));
  }

  @Test
  public void semanticFailuresNameTheirNextStep() {
    assertEquals(
        "ENABLE_ACCESSIBILITY",
        VirtualScreenObservationHint.semanticNextAction(NOT_ENABLED, "ACCESSIBILITY_UNAVAILABLE"));
    assertEquals(
        "ACCESSIBILITY_NOT_ENABLED",
        VirtualScreenObservationHint.semanticReason(NOT_ENABLED, "ACCESSIBILITY_UNAVAILABLE"));
    assertEquals(
        "WAIT_ACCESSIBILITY_RECONNECT",
        VirtualScreenObservationHint.semanticNextAction(ENABLED_NOT_CONNECTED, "NO_WINDOWS"));
    assertEquals(
        "ACCESSIBILITY_NOT_CONNECTED",
        VirtualScreenObservationHint.semanticReason(UNKNOWN_NOT_CONNECTED, "NO_WINDOWS"));
    assertEquals(
        "LAUNCH_APP_FIRST",
        VirtualScreenObservationHint.semanticNextAction(CONNECTED, "NO_WINDOWS"));
    assertEquals(
        "VSCREEN_NO_WINDOW", VirtualScreenObservationHint.semanticReason(CONNECTED, "NO_WINDOWS"));
    assertEquals(
        "WAIT_AND_SEE_AGAIN",
        VirtualScreenObservationHint.semanticNextAction(CONNECTED, "ROOT_UNAVAILABLE"));
    assertEquals(
        "SEE_AGAIN",
        VirtualScreenObservationHint.semanticNextAction(CONNECTED, "NO_UNIQUE_APPLICATION_WINDOW"));
    assertEquals(
        "VSCREEN_WINDOW_UNREADABLE",
        VirtualScreenObservationHint.semanticReason(CONNECTED, "WINDOW_ID_MISMATCH"));
  }

  @Test
  public void ambiguousOrEmptyApplicationWindowsGetDistinctNextSteps() {
    var ambiguous =
        VirtualScreenWindowEvidence.of(
            8,
            List.of(
                new VirtualScreenWindowEvidence.Window(
                    8,
                    2,
                    VirtualScreenWindowPolicy.TYPE_APPLICATION,
                    1,
                    0,
                    0,
                    100,
                    100,
                    true,
                    false,
                    "A"),
                new VirtualScreenWindowEvidence.Window(
                    8,
                    3,
                    VirtualScreenWindowPolicy.TYPE_APPLICATION,
                    1,
                    0,
                    0,
                    100,
                    100,
                    true,
                    false,
                    "B")));
    assertEquals(
        "ASK_USER_TO_CLOSE_SPLIT_SCREEN",
        VirtualScreenObservationHint.semanticNextAction(
            CONNECTED, "NO_UNIQUE_APPLICATION_WINDOW", ambiguous));
    assertEquals(
        "VSCREEN_WINDOW_AMBIGUOUS",
        VirtualScreenObservationHint.semanticReason(
            CONNECTED, "NO_UNIQUE_APPLICATION_WINDOW", ambiguous));
    var onlyIme =
        VirtualScreenWindowEvidence.of(
            8,
            List.of(
                new VirtualScreenWindowEvidence.Window(
                    8,
                    4,
                    VirtualScreenWindowPolicy.TYPE_INPUT_METHOD,
                    1,
                    0,
                    0,
                    100,
                    100,
                    true,
                    true,
                    "Keyboard")));
    assertEquals(
        "LAUNCH_APP_FIRST",
        VirtualScreenObservationHint.semanticNextAction(
            CONNECTED, "NO_UNIQUE_APPLICATION_WINDOW", onlyIme));
    assertEquals(
        "VSCREEN_NO_WINDOW",
        VirtualScreenObservationHint.semanticReason(
            CONNECTED, "NO_UNIQUE_APPLICATION_WINDOW", onlyIme));
    // 没有证据时保持旧的「重新观察」，不会凭空让用户去关分屏。
    assertEquals(
        "SEE_AGAIN",
        VirtualScreenObservationHint.semanticNextAction(
            CONNECTED, "NO_UNIQUE_APPLICATION_WINDOW", null));
    // 窗口证据不改变其它诊断的行为。
    assertEquals(
        "SEE_AGAIN",
        VirtualScreenObservationHint.semanticNextAction(
            CONNECTED, "WINDOW_ID_MISMATCH", ambiguous));
    assertEquals(
        "ACCESSIBILITY", VirtualScreenObservationHint.windowEvidence(CONNECTED, "READABLE"));
    assertEquals(
        "CORE_TASKS",
        VirtualScreenObservationHint.windowEvidence(CONNECTED, "ACCESSIBILITY_UNAVAILABLE"));
  }

  @Test
  public void pendingCleanupAsksUserToCloseInsteadOfSeeingAgain() {
    // 现场：CLOSE_UNKNOWN / cleanupPending 时 see 与 tree 都不能靠重看解决，也不能自动重建。
    for (String code :
        new String[] {
          "VSCREEN_CLOSE_UNKNOWN",
          "VSCREEN_START_UNKNOWN",
          "VSCREEN_DISCONNECTED",
          "VSCREEN_CLOSING"
        })
      assertEquals("ASK_USER_TO_CLOSE", VirtualScreenObservationHint.statusNextAction(code, true));
    assertEquals(
        "CREATE_FIRST",
        VirtualScreenObservationHint.statusNextAction("VSCREEN_NOT_RUNNING", false));
    assertEquals(
        "WAIT_AND_SEE_AGAIN",
        VirtualScreenObservationHint.statusNextAction("VSCREEN_START_IN_PROGRESS", false));
    assertEquals(
        "CHECK_STATUS",
        VirtualScreenObservationHint.statusNextAction("VSCREEN_TRANSPORT_UNAVAILABLE", false));
    // 现场：旋转后管理器未采用新代次，status 持续 STALE_GENERATION；再查 status 只会原地打转。
    assertEquals(
        "ASK_USER_TO_CLOSE",
        VirtualScreenObservationHint.statusNextAction("STALE_GENERATION", false));
  }

  @Test
  public void identityRejectionsPointToStatusOrFreshObservation() {
    assertEquals(
        "CHECK_STATUS",
        VirtualScreenObservationHint.identityNextAction("VSCREEN_CORE_IDENTITY_CHANGED"));
    assertEquals(
        "CHECK_STATUS",
        VirtualScreenObservationHint.identityNextAction("VSCREEN_CORE_IDENTITY_REQUIRED"));
    assertEquals(
        "SEE_AGAIN", VirtualScreenObservationHint.identityNextAction("VSCREEN_GENERATION_CHANGED"));
  }

  @Test
  public void missingDisplayIsNotReportedAsUnreadableWindow() {
    // 现场：tree 在没有可用显示时曾回 VSCREEN_WINDOW_UNREADABLE + SEE_AGAIN，与 see 的拒绝形成循环。
    for (VirtualScreenObservationHint.Accessibility a :
        VirtualScreenObservationHint.Accessibility.values()) {
      assertEquals(
          "CHECK_STATUS",
          VirtualScreenObservationHint.semanticNextAction(a, "DISPLAY_UNAVAILABLE"));
      assertEquals(
          "VSCREEN_DISPLAY_UNAVAILABLE",
          VirtualScreenObservationHint.semanticReason(a, "DISPLAY_UNAVAILABLE"));
    }
  }
}
