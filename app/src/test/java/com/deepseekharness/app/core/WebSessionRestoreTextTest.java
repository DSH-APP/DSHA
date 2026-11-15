package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.UiText;
import org.junit.After;
import org.junit.Test;

/** 恢复文案只跟随稳定原因码；未知码必须回退到通用的“待确认”，不能回显异常正文。 */
public final class WebSessionRestoreTextTest {
  @After
  public void language() {
    UiText.setLanguage("zh");
  }

  @Test
  public void unresolvedReasonsKeepDistinctActionableText() {
    String identity = WebSessionRestoreText.unresolved("IDENTITY_UNKNOWN");
    String auth = WebSessionRestoreText.unresolved("AUTH_UNAVAILABLE");
    String launcher = WebSessionRestoreText.unresolved("LAUNCHER_PENDING");
    String unknownLauncher = WebSessionRestoreText.unresolved("LAUNCHER_UNKNOWN");
    String retire = WebSessionRestoreText.unresolved("RETIRE_UNAVAILABLE");
    assertTrue(identity.contains("身份无法确认"));
    assertTrue(identity.contains("强制结束"));
    assertTrue(auth.contains("仍然存活"));
    assertTrue(launcher.contains("启动器"));
    assertTrue(unknownLauncher.contains("无法确认启动器状态"));
    assertNotEquals(launcher, unknownLauncher);
    assertTrue(retire.contains("清理失败"));
    assertNotEquals(identity, auth);
    assertNotEquals(launcher, retire);
    assertEquals(
        "原 Web 实例状态待确认，请查看诊断或正常停止后重试", WebSessionRestoreText.unresolved("UNRECOGNIZED_REASON"));
    assertEquals(WebSessionRestoreText.unresolved(null), WebSessionRestoreText.unresolved(""));
    assertFalse(WebSessionRestoreText.unresolved("fatal: token=secret").contains("secret"));
  }

  @Test
  public void progressIsBoundedAndFallsBackForUnknownStages() {
    assertEquals("正在恢复原 Web 连接…", WebSessionRestoreText.progress("AUTHENTICATING", 1));
    assertTrue(WebSessionRestoreText.progress("AUTH_UNAVAILABLE", 2).contains("2"));
    assertTrue(WebSessionRestoreText.progress("AUTH_UNAVAILABLE", 9).contains("3"));
    assertEquals("正在核对原 Web 实例…", WebSessionRestoreText.progress("READ", 0));
    assertEquals("正在核对原 Web 实例…", WebSessionRestoreText.progress(null, 0));
  }

  @Test
  public void deferredStartTextExplainsThatTheSameRequestWillContinue() {
    assertTrue(WebSessionRestoreText.deferred().contains("自动继续"));
    assertTrue(WebSessionRestoreText.continuingAfterExit().contains("继续这次启动"));
    assertTrue(WebSessionRestoreText.restoredNoStart().contains("无需重新启动"));
    assertTrue(WebSessionRestoreText.exited().contains("已确认"));
  }

  @Test
  public void displayFollowsCurrentLanguageWithoutChangingCodes() {
    String chinese = WebSessionRestoreText.unresolved("IDENTITY_UNKNOWN");
    UiText.setLanguage("en");
    String english = WebSessionRestoreText.unresolved("IDENTITY_UNKNOWN");
    assertTrue(english.contains("cannot be identified"));
    assertNotEquals(chinese, english);
    assertEquals(
        "Reconnecting to the existing Web instance…",
        WebSessionRestoreText.progress("AUTHENTICATING", 1));
  }

  @Test
  public void userStopReasonsNeverClaimVersionIncompatibility() {
    String exiting = WebSessionRestoreText.stopped("STOP_IN_PROGRESS");
    String unconfirmed = WebSessionRestoreText.stopped("USER_STOPPED_EXIT_UNCONFIRMED");
    assertTrue(exiting.contains("已停止"));
    assertTrue(exiting.contains("正在退出"));
    assertTrue(unconfirmed.contains("尚未确认"));
    assertFalse(exiting.contains("不兼容"));
    assertFalse(unconfirmed.contains("不兼容"));
    assertNotEquals(exiting, unconfirmed);
    // 短标题与下一步也各自可达，且不落到“版本不兼容”。
    assertEquals("已停止，旧实例退出中", WebSessionRestoreText.state("STOP_IN_PROGRESS"));
    assertEquals("已停止，退出待确认", WebSessionRestoreText.state("USER_STOPPED_EXIT_UNCONFIRMED"));
    assertFalse(
        WebSessionRestoreText.unresolved("USER_STOPPED_EXIT_UNCONFIRMED").contains("版本不兼容"));
    assertTrue(WebSessionRestoreText.continuingAfterStop().contains("继续这次启动"));
  }

  @Test
  public void stopReasonsFollowLanguage() {
    UiText.setLanguage("en");
    assertTrue(WebSessionRestoreText.stopped("STOP_IN_PROGRESS").contains("still exiting"));
    assertEquals(
        "Stopped; exit unconfirmed", WebSessionRestoreText.state("USER_STOPPED_EXIT_UNCONFIRMED"));
  }
}
