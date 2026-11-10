package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.ScreenModeFeedback.Kind;
import com.deepseekharness.app.util.ScreenModeFeedback.Result;
import com.deepseekharness.app.util.ScreenModePolicy.Mode;
import com.deepseekharness.app.util.ScreenModePolicy.Switch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** 识屏模式切换的确认判据与结果文案：与设置页、虚拟识屏页共用同一套说法。 */
public final class ScreenModeFeedbackTest {
  private String previousLanguage;

  @Before
  public void chinese() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
  }

  @After
  public void restoreLanguage() {
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void onlyNewScreenGrantsNeedConfirmation() {
    assertTrue(ScreenModeFeedback.needsConfirmation(Mode.OFF, Mode.PHYSICAL));
    assertTrue(ScreenModeFeedback.needsConfirmation(Mode.OFF, Mode.VIRTUAL));
    assertTrue(ScreenModeFeedback.needsConfirmation(Mode.PHYSICAL, Mode.VIRTUAL));
    assertTrue(ScreenModeFeedback.needsConfirmation(Mode.VIRTUAL, Mode.PHYSICAL));
    assertFalse(ScreenModeFeedback.needsConfirmation(Mode.PHYSICAL, Mode.PHYSICAL));
    assertFalse(ScreenModeFeedback.needsConfirmation(Mode.VIRTUAL, Mode.VIRTUAL));
    // 关闭不需要二次确认：撤销永远比授权安全。
    assertFalse(ScreenModeFeedback.needsConfirmation(Mode.PHYSICAL, Mode.OFF));
    assertFalse(ScreenModeFeedback.needsConfirmation(Mode.VIRTUAL, Mode.OFF));
  }

  @Test
  public void confirmationExplainsScopeAndRevocation() {
    String physical = ScreenModeFeedback.confirmMessage(Mode.PHYSICAL);
    assertTrue(physical, physical.contains("读取主屏"));
    assertTrue(physical, physical.contains("切回「关闭」"));
    assertTrue(physical, physical.contains("撤销无障碍"));
    assertTrue(ScreenModeFeedback.confirmTitle(Mode.PHYSICAL).contains("实体识屏"));

    String virtual = ScreenModeFeedback.confirmMessage(Mode.VIRTUAL);
    assertTrue(virtual, virtual.contains("独立的虚拟屏"));
    assertTrue(virtual, virtual.contains("不会直接操作你的主屏"));
    assertTrue(virtual, virtual.contains("切回「关闭」"));
    assertTrue(ScreenModeFeedback.confirmTitle(Mode.VIRTUAL).contains("虚拟识屏"));
  }

  @Test
  public void successCarriesModeStatusAndToast() {
    Result physical = ScreenModeFeedback.result(Mode.PHYSICAL, Mode.OFF, Switch.ALLOWED, "");
    assertEquals(Kind.SUCCESS, physical.kind);
    assertEquals("实体识屏 · 已切换", physical.status);
    assertTrue(physical.toast, physical.toast.contains("已切换到实体识屏"));

    Result virtual = ScreenModeFeedback.result(Mode.VIRTUAL, Mode.OFF, Switch.ALLOWED, "");
    assertEquals(Kind.SUCCESS, virtual.kind);
    assertTrue(virtual.toast, virtual.toast.contains("已切换到虚拟识屏"));

    Result off = ScreenModeFeedback.result(Mode.OFF, Mode.PHYSICAL, Switch.ALLOWED, "");
    assertEquals(Kind.SUCCESS, off.kind);
    assertEquals("关闭 · 已切换", off.status);
    assertTrue(off.toast, off.toast.contains("已关闭识屏"));
  }

  @Test
  public void unchangedSelectionStillReportsCurrentMode() {
    Result same = ScreenModeFeedback.result(Mode.PHYSICAL, Mode.PHYSICAL, Switch.UNCHANGED, "");
    assertEquals(Kind.UNCHANGED, same.kind);
    assertEquals("实体识屏", same.status);
    assertTrue(same.toast, same.toast.contains("当前已是实体识屏"));
  }

  @Test
  public void failuresAreNeverSilent() {
    Result crashed = ScreenModeFeedback.result(Mode.PHYSICAL, Mode.OFF, null, "");
    assertEquals(Kind.FAILURE, crashed.kind);
    assertEquals("关闭 · 切换未完成", crashed.status);
    assertTrue(crashed.toast, crashed.toast.contains("切换失败"));

    Result denied =
        ScreenModeFeedback.result(Mode.PHYSICAL, Mode.OFF, Switch.NOT_ADMITTED, "NOT_ADMITTED");
    assertEquals(Kind.FAILURE, denied.kind);
    assertTrue(denied.toast, denied.toast.contains("条件未满足"));
    assertTrue(denied.toast, denied.toast.contains("实体识屏"));

    Result startFailed =
        ScreenModeFeedback.result(Mode.VIRTUAL, Mode.OFF, Switch.ALLOWED, "VSCREEN_NO_CORE");
    assertEquals(Kind.FAILURE, startFailed.kind);
    assertEquals("虚拟识屏 · 虚拟屏未创建", startFailed.status);
    assertTrue(startFailed.toast, startFailed.toast.contains("VSCREEN_NO_CORE"));
  }

  /** 待清理与虚拟识屏页共用「待清理 / 核验关闭」的说法，用户能在同一处收尾。 */
  @Test
  public void cleanupPendingPointsToVirtualScreenPage() {
    Result pending =
        ScreenModeFeedback.result(
            Mode.OFF, Mode.VIRTUAL, Switch.CLEANUP_PENDING, "VSCREEN_CLOSING");
    assertEquals(Kind.FAILURE, pending.kind);
    assertEquals("虚拟识屏 · 待清理", pending.status);
    assertTrue(pending.toast, pending.toast.contains("待清理"));
    assertTrue(pending.toast, pending.toast.contains("核验关闭"));
    assertTrue(pending.toast, pending.toast.contains("虚拟识屏页"));
    assertEquals(ScreenModeFeedback.cleanupPendingToast(), pending.toast);
  }

  @Test
  public void englishSelectionIsExplicit() {
    UiText.setLanguage("en");
    Result physical = ScreenModeFeedback.result(Mode.PHYSICAL, Mode.OFF, Switch.ALLOWED, "");
    assertEquals("Physical screen · switched", physical.status);
    assertTrue(physical.toast, physical.toast.contains("Physical screen is on"));
    assertTrue(ScreenModeFeedback.confirmMessage(Mode.PHYSICAL).contains("switch back to Off"));
    assertTrue(
        ScreenModeFeedback.result(Mode.VIRTUAL, Mode.VIRTUAL, Switch.CLEANUP_PENDING, "x")
            .toast
            .contains("cleaned up"));
  }
}
