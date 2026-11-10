package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.UpdateActionPolicy.Action;
import com.deepseekharness.app.util.UpdateActionPolicy.Command;
import com.deepseekharness.app.util.UpdateActionPolicy.Label;
import com.deepseekharness.app.util.UpdateActionPolicy.Mode;
import com.deepseekharness.app.util.UpdateActionPolicy.Stage;
import com.deepseekharness.app.util.UpdateActionPolicy.Tone;
import org.junit.Test;

/** 覆盖交互原型里的 12 个场景：同一个按钮原位切换，进行中不可点、不提供取消。 */
public final class UpdateActionPolicyTest {
  private static final long MIB = 1048576L;
  private static final long TOTAL = 187 * MIB;

  private static Action idle(boolean release, boolean apk, long downloaded) {
    return UpdateActionPolicy.resolve(
        Stage.IDLE, release, apk, downloaded, release ? TOTAL : 0, false, false, false);
  }

  private static void assertAction(
      Action action, Mode mode, Command command, Label label, int percent) {
    assertEquals(mode, action.mode);
    assertEquals(command, action.command);
    assertEquals(label, action.label);
    assertEquals(percent, action.percent);
  }

  @Test
  public void neverCheckedAndCheckFailureOfferCheck() {
    Action action = idle(false, false, 0);
    assertAction(action, Mode.IDLE, Command.CHECK, Label.CHECK, -1);
    assertTrue(action.clickable());
    assertTrue(action.channelEnabled);
    assertFalse("没有候选时由主按钮检查，不再重复显示链接", action.recheck);
    assertEquals(Tone.MUTED, action.tone);
  }

  @Test
  public void checkingSweepsInPlaceAndKeepsChannelSwitchable() {
    Action action =
        UpdateActionPolicy.resolve(Stage.CHECKING, false, false, 0, 0, false, false, false);
    assertAction(action, Mode.SWEEP, Command.NONE, Label.CHECKING, -1);
    assertFalse(action.clickable());
    assertTrue("检查中切换通道会作废当前检查并重查，不能被卡住", action.channelEnabled);
    assertFalse(action.recheck);
    assertFalse(action.showsBytes());
    assertEquals(Tone.ACCENT, action.tone);
  }

  @Test
  public void checkingWithCachedPartialDownloadDoesNotReuseBytes() {
    Action action =
        UpdateActionPolicy.resolve(
            Stage.CHECKING, true, false, TOTAL / 2, TOTAL, false, false, false);
    assertEquals(Mode.SWEEP, action.mode);
    assertEquals(-1, action.percent);
    assertFalse(action.showsBytes());
  }

  @Test
  public void foundCandidateOffersDownloadAndRecheckLink() {
    Action action = idle(true, false, 0);
    assertAction(action, Mode.IDLE, Command.DOWNLOAD, Label.DOWNLOAD, -1);
    assertTrue(action.recheck);
    assertTrue(action.channelEnabled);
    assertEquals(Tone.ACCENT, action.tone);
  }

  @Test
  public void downloadingFillsFromLeftWithFlooredPercent() {
    Action action =
        UpdateActionPolicy.resolve(
            Stage.DOWNLOADING, true, false, TOTAL * 46 / 100 + 1, TOTAL, false, false, false);
    assertAction(action, Mode.FILL, Command.NONE, Label.DOWNLOADING, 46);
    assertTrue(action.showsBytes());
    assertFalse(action.clickable());
    assertFalse("页面不提供取消，也不能在下载中重新检查", action.recheck);
    assertFalse(action.channelEnabled);
  }

  @Test
  public void downloadingWithUnknownTotalFallsBackToSweep() {
    Action action =
        UpdateActionPolicy.resolve(Stage.DOWNLOADING, true, false, 5 * MIB, 0, false, false, false);
    assertAction(action, Mode.SWEEP, Command.NONE, Label.DOWNLOADING, -1);
    assertFalse(action.showsBytes());
  }

  @Test
  public void cancelledFromNotificationResumesWithKeptProgress() {
    Action action = idle(true, false, TOTAL * 38 / 100 + 1);
    assertAction(action, Mode.IDLE, Command.DOWNLOAD, Label.RESUME, 38);
    assertTrue(action.recheck);
    assertFalse("暂停时不冒充传输进度", action.showsBytes());
  }

  @Test
  public void verifyingAfterDownloadStaysFull() {
    Action action =
        UpdateActionPolicy.resolve(Stage.VERIFYING, true, false, TOTAL, TOTAL, false, false, false);
    assertAction(action, Mode.FULL, Command.NONE, Label.VERIFYING, -1);
    assertFalse(action.showsBytes());
  }

  @Test
  public void verifiedApkOffersInstall() {
    Action action = idle(true, true, TOTAL);
    assertAction(action, Mode.IDLE, Command.INSTALL, Label.INSTALL, -1);
    assertTrue(action.recheck);
    assertEquals(Tone.GOOD, action.tone);
  }

  @Test
  public void installReverifyAndPendingHandoffLockEverything() {
    Action verifying =
        UpdateActionPolicy.resolve(Stage.IDLE, true, true, TOTAL, TOTAL, true, false, false);
    assertAction(verifying, Mode.FULL, Command.NONE, Label.REVERIFYING, -1);
    assertFalse(verifying.recheck);
    assertFalse(verifying.channelEnabled);
    Action pending =
        UpdateActionPolicy.resolve(Stage.IDLE, true, true, TOTAL, TOTAL, false, true, false);
    assertAction(pending, Mode.WAIT, Command.NONE, Label.OPENING_INSTALLER, -1);
    assertFalse("待领取安装结果不能重复开放按钮", pending.clickable());
    assertFalse(pending.channelEnabled);
  }

  @Test
  public void installHandoffWinsOverEngineStage() {
    Action action =
        UpdateActionPolicy.resolve(Stage.CHECKING, true, true, TOTAL, TOTAL, true, false, false);
    assertEquals(Label.REVERIFYING, action.label);
    assertFalse("安装交接期间即使引擎在检查也锁定通道", action.channelEnabled);
  }

  @Test
  public void installErrorKeepsInstallAvailableWithBadTone() {
    Action action =
        UpdateActionPolicy.resolve(Stage.IDLE, true, true, TOTAL, TOTAL, false, false, true);
    assertAction(action, Mode.IDLE, Command.INSTALL, Label.INSTALL, -1);
    assertEquals(Tone.BAD, action.tone);
    assertTrue(action.channelEnabled);
  }

  @Test
  public void nullStageIsIdle() {
    assertEquals(
        Command.CHECK,
        UpdateActionPolicy.resolve(null, false, false, 0, 0, false, false, false).command);
  }

  @Test
  public void ratioClampsAndFloors() {
    assertEquals(-1, UpdateActionPolicy.ratio(10, 0));
    assertEquals(0, UpdateActionPolicy.ratio(-5, 100));
    assertEquals(99, UpdateActionPolicy.ratio(999, 1000));
    assertEquals(100, UpdateActionPolicy.ratio(2000, 1000));
  }

  @Test
  public void stageNamesMirrorTheRepositoryEnum() {
    // UpdateUi 按名称映射 UpdateRepository.Stage；两边必须同名同序。
    String[] expected = {"IDLE", "CHECKING", "DOWNLOADING", "VERIFYING"};
    Stage[] values = Stage.values();
    assertEquals(expected.length, values.length);
    for (int i = 0; i < expected.length; i++) assertEquals(expected[i], values[i].name());
  }
}
