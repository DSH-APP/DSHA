package com.deepseekharness.app.util;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryStatusTextTest {
  private static final String READY_ZH = "独立应急 DSH 已就绪；正式环境保持原状。";
  private static final String READY_EN =
      "Independent recovery DSH is ready. The main environment is preserved.";
  private static final String READ_ONLY_ZH = "系统限制进程身份读取；应急对话与只读诊断可用，正式环境写入仍被阻止。";
  private static final String READ_ONLY_EN =
      "The system restricts process identity access. Recovery chat and read-only diagnostics are available; writes to the main environment remain blocked.";
  private static final String NO_KEY_ZH = " 尚未配置模型凭据，可返回原生应急页面填写本次临时密钥。";
  private static final String NO_KEY_EN =
      " No model credential is configured. Enter a temporary key on the native recovery page.";
  private static final String UNREADABLE_ZH = " 原生凭据暂不可读取，已保留原密文；可填写本次临时密钥。";
  private static final String UNREADABLE_EN =
      " The saved credential is unavailable and has been preserved. You can enter a temporary key.";

  @After
  public void reset() {
    UiText.setLanguage("zh");
  }

  @Test
  public void readyAndCredentialNoticesFollowLanguage() {
    UiText.setLanguage("zh");
    assertEquals(READY_ZH, RecoveryStatusText.render("READY", READY_EN));
    assertEquals(READY_ZH + NO_KEY_ZH, RecoveryStatusText.render("READY", READY_EN + NO_KEY_EN));
    assertEquals(
        READY_ZH + UNREADABLE_ZH, RecoveryStatusText.render("READY", READY_EN + UNREADABLE_EN));
    UiText.setLanguage("en");
    assertEquals(READY_EN, RecoveryStatusText.render("READY", READY_ZH));
    assertEquals(READY_EN + NO_KEY_EN, RecoveryStatusText.render("READY", READY_ZH + NO_KEY_ZH));
    assertEquals(
        READY_EN + UNREADABLE_EN, RecoveryStatusText.render("READY", READY_ZH + UNREADABLE_ZH));
  }

  @Test
  public void readOnlyRetainsTheWriteBoundaryAndCredentialNotice() {
    UiText.setLanguage("zh");
    assertEquals(READ_ONLY_ZH, RecoveryStatusText.render("READY_READ_ONLY", READ_ONLY_EN));
    assertEquals(
        READ_ONLY_ZH + NO_KEY_ZH,
        RecoveryStatusText.render("READY_READ_ONLY", READ_ONLY_EN + NO_KEY_EN));
    UiText.setLanguage("en");
    assertEquals(READ_ONLY_EN, RecoveryStatusText.render("READY_READ_ONLY", READ_ONLY_ZH));
    assertEquals(
        READ_ONLY_EN + UNREADABLE_EN,
        RecoveryStatusText.render("READY_READ_ONLY", READ_ONLY_ZH + UNREADABLE_ZH));
  }

  @Test
  public void UnknownOutputAndFailuresStayVerbatim() {
    String raw = READY_EN + " plugin 用户异常 /root/下载";
    UiText.setLanguage("zh");
    assertEquals(raw, RecoveryStatusText.render("READY", raw));
    assertEquals(
        "RECOVERY_PROCESS_EXITED: 原始输出",
        RecoveryStatusText.render("FAILED", "RECOVERY_PROCESS_EXITED: 原始输出"));
  }
}
