package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Test;

public final class RussianUiLocalizationTest {
  @After
  public void restoreLanguage() {
    UiText.setLanguage("zh");
  }

  @Test
  public void recoveryDurationAndLoadingUseRussianAtDisplayBoundary() {
    UiText.setLanguage("ru");
    assertEquals(
        "Готово · ожидание входа", UiText.choose("已就绪 · 等待进入", "Ready · waiting to enter"));
    assertEquals("Готово через 32 с", UiText.format("第 %s 秒已就绪", 32));
    assertEquals("Проверка установленных плагинов в фоне…", UiText.text("正在后台检测已安装插件…"));
    assertEquals("Открытие диалога…", UiText.choose("正在进入对话…", "Opening conversation…"));
    assertEquals("user message 中文 English", UiText.raw("user message 中文 English"));
    assertEquals("Повторить установку?", UiText.choose("重试部署？", "Retry deployment?"));
    assertEquals("Сохранить как…", UiText.choose("另存为…", "Save as…"));
    assertEquals("Журналы для отзыва сохранены в выбранное место", UiText.text("反馈日志已保存到所选位置"));
    UiText.setLanguage("en");
    assertEquals("Opening conversation…", UiText.choose("正在进入对话…", "Opening conversation…"));
  }
}
