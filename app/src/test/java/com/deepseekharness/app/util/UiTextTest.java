package com.deepseekharness.app.util;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class UiTextTest {
  @After
  public void restore() {
    UiText.setLanguage("zh");
  }

  @Test
  public void englishCatalogContainsCompleteTranslations() {
    assertTrue(UiMessages.EN.size() > 2000);
    for (var item : UiMessages.EN.entrySet()) {
      assertNotNull(item.getValue());
      assertFalse(item.getValue().isEmpty());
      assertFalse(item.getKey(), item.getValue().matches("(?s).*[\\p{IsHan}].*"));
    }
  }

  @Test
  public void labelsSwitchAndActionArraysKeepTheirKeys() {
    String[] actions = {"删除插件", "复制插件名称"};
    UiText.setLanguage("en");
    String[] labels = UiText.text(actions);
    assertEquals("Delete plugin", labels[0]);
    assertEquals("删除插件", actions[0]);
    UiText.setLanguage("zh");
    assertEquals("删除插件", UiText.text(actions)[0]);
  }

  @Test
  public void toolStatusLeavesCommandPayloadAndUserTextUntouched() {
    UiText.setLanguage("en");
    String command = "printf '这是用户自己的中文命令'";
    assertEquals("⚙ Running command\n" + command, UiText.toolStatus("⚙ 正在执行命令\n" + command));
    String text = "用户的自定义内容，与应用文案不同";
    assertSame(text, UiText.text(text));
    assertSame(text, UiText.toolStatus(text));
  }

  @Test
  public void statusTranslationDoesNotChangeInternalStatusKeys() {
    UiText.setLanguage("en");
    String original = "环境任务进行中：插件操作";
    assertEquals("Environment task running: Plugin operation", UiText.status(original));
    assertTrue(original.startsWith("环境任务进行中"));
  }

  @Test
  public void externalTextEqualToAKeyAndProtocolValuesRemainVerbatim() {
    UiText.setLanguage("en");
    String external = "开始配对";
    assertNotEquals(external, UiText.text(external));
    assertSame(external, UiText.raw(external));
    for (String value : new String[] {"READY", "UNAUTHORIZED", "LOADED:插件原文", "用户通知：开始配对"})
      assertSame(value, UiText.raw(value));
    UiText.setLanguage("zh");
    assertSame(external, UiText.raw(external));
  }

  @Test
  public void fullTemplateTranslatesWithoutTranslatingArguments() {
    UiText.setLanguage("en");
    String parameter = "开始配对";
    assertEquals("Web alert (" + parameter + ")", UiText.format("网页提示（%s）", parameter));
    assertEquals(
        "Opacity 60% · Lines 1 · Text size 13sp",
        UiText.format("不透明度 %s · 行数 %s · 字号 %s", "60%", 1, "13sp"));
  }
}
