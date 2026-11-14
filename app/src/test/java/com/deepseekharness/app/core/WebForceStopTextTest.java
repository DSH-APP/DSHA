package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.WebStopDiagnostic;
import org.junit.After;
import org.junit.Test;

/** 强停降级文案：三要素、已核验残留、可执行的重试入口；不改任何信号条件。 */
public final class WebForceStopTextTest {
  @After
  public void language() {
    UiText.setLanguage("zh");
  }

  @Test
  public void unavailableExplainsThreeElementsAndKeepsVerifiedResidual() {
    WebStopDiagnostic diagnostic =
        WebStopDiagnostic.of(
            WebStopDiagnostic.Code.FORCE_EVIDENCE_UNCONFIRMED,
            "PID=42",
            "命令摘要=node bin.js web",
            "未通过=出生身份与记录不符");
    String text = WebForceStopText.unavailable(diagnostic);
    assertTrue(text.contains("发生了什么"));
    assertTrue(text.contains("影响"));
    assertTrue(text.contains("下一步"));
    assertTrue(text.contains("PID=42"));
    assertTrue(text.contains("命令摘要=node bin.js web"));
    assertTrue(text.contains("出生身份与记录不符"));
    assertTrue(text.contains("没有发送任何强制结束信号"));
    assertTrue(text.contains(String.valueOf(WebForceStopText.retrySeconds())));
    assertEquals(15, WebForceStopText.retrySeconds());
  }

  @Test
  public void unavailableWithoutFactsSaysSoInsteadOfInventingAPid() {
    String text =
        WebForceStopText.unavailable(
            WebStopDiagnostic.of(WebStopDiagnostic.Code.FORCE_EVIDENCE_UNCONFIRMED));
    assertTrue(text.contains("未取得可核验的残留 PID"));
    assertFalse(text.contains("PID="));
    assertTrue(text.contains("证据不足"));
    assertTrue(text.contains("发生了什么"));
  }

  @Test
  public void noCandidateStillGivesAConcreteNextStep() {
    String text = WebForceStopText.noCandidate();
    assertTrue(text.contains("没有可强制结束的旧实例"));
    assertTrue(text.contains("发生了什么"));
    assertTrue(text.contains("影响"));
    assertTrue(text.contains("下一步"));
    assertTrue(text.contains("没有发送任何信号"));
  }

  @Test
  public void retryEntriesShareTheSameBoundedWait() {
    assertTrue(WebForceStopText.waitBeforeRetry().contains("15"));
    assertTrue(WebForceStopText.retryButton().contains("15"));
  }

  @Test
  public void displayFollowsLanguageButKeepsRawFacts() {
    WebStopDiagnostic diagnostic =
        WebStopDiagnostic.of(
            WebStopDiagnostic.Code.FORCE_EVIDENCE_UNCONFIRMED,
            "PID=42",
            "命令摘要=node bin.js web",
            "未通过=出生身份与记录不符");
    String chinese = WebForceStopText.unavailable(diagnostic);
    UiText.setLanguage("en");
    String english = WebForceStopText.unavailable(diagnostic);
    assertNotEquals(chinese, english);
    assertTrue(english.contains("What happened"));
    assertTrue(english.contains("Impact"));
    assertTrue(english.contains("Next"));
    assertTrue(english.contains("PID=42"));
    assertTrue(english.contains("命令摘要=node bin.js web"));
  }
}
