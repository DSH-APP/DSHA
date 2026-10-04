package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.UiStateText;
import com.deepseekharness.app.util.UiText;
import org.junit.After;
import org.junit.Test;

public class CoreUiStateContractTest {
  @After
  public void resetLanguage() {
    UiText.setLanguage("zh");
  }

  @Test
  public void cachedFailureChangesLanguageWhileItsRawExceptionParameterDoesNot() {
    String parameter = "用户异常 /root/中文-file.txt %s\nPlugin error: raw details";
    UiText.setLanguage("zh");
    String chinese = UiText.format("启动未完成：%s", parameter);
    assertEquals("启动未完成：" + parameter, chinese);
    UiText.setLanguage("en");
    assertEquals("Startup incomplete: " + parameter, UiStateText.render(chinese));
    String english = UiText.format("启动未完成：%s", parameter);
    UiText.setLanguage("zh");
    assertEquals(chinese, UiStateText.render(english));
  }

  @Test
  public void exitPhaseAndHttpStatusHaveCompleteBidirectionalTemplates() {
    UiText.setLanguage("zh");
    String exited = UiText.format("%s 进程退出，退出码 %s（鉴权前）", "proroot", 7);
    String rejected = UiText.format("Web 未接受登录凭据（HTTP %s），请重试或重新启动服务", 401);
    UiText.setLanguage("en");
    assertEquals(
        "proroot process exited with code 7 (before authentication)", UiStateText.render(exited));
    assertEquals(
        "Web did not accept the login credential (HTTP 401); retry or restart the service.",
        UiStateText.render(rejected));
    UiText.setLanguage("zh");
    assertEquals(
        exited, UiStateText.render("proroot process exited with code 7 (before authentication)"));
  }
}
