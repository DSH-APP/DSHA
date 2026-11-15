package com.deepseekharness.app.util;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class UiStateTextTest {
  @Test
  public void everyRegisteredTemplateRoundTripsRawUnicodePercentAndNewlines() {
    assertTrue(UiMessages.FORMATS.length > 100);
    for (String[] template : UiMessages.FORMATS) {
      java.util.List<Object> arguments = new java.util.ArrayList<>();
      for (int index = 0; index < template[0].length(); index++) {
        if (template[0].charAt(index) != '%') continue;
        char type = template[0].charAt(++index);
        if (type == 's') arguments.add("开始配对 用户原文 100% %d %s\n第二行:" + arguments.size());
        else if (type == 'd') arguments.add(-31);
      }
      String zh = String.format(java.util.Locale.ROOT, template[0], arguments.toArray());
      String en = String.format(java.util.Locale.ROOT, template[1], arguments.toArray());
      UiText.setLanguage("en");
      assertEquals(template[0], en, UiStateText.render(zh));
      UiText.setLanguage("zh");
      assertEquals(template[0], zh, UiStateText.render(en));
    }
  }

  @After
  public void reset() {
    UiText.setLanguage("zh");
  }

  @Test
  public void cachedPluginResultFollowsBothLanguageChanges() {
    String zh = "插件检测完成；新检测到的插件可开启开关加入 Web，变更后重启 Web 生效";
    UiText.setLanguage("en");
    String cached = UiText.text(zh);
    assertNotEquals(zh, cached);
    UiText.setLanguage("zh");
    assertEquals(zh, UiStateText.render(cached));
    UiText.setLanguage("en");
    assertEquals(cached, UiStateText.render(zh));
  }

  @Test
  public void cachedRegisteredStatusTranslatesAcrossEveryLanguageWithoutChangingPayload() {
    String source = "正在启用 %s", payload = "用户外掛 العربية /root/原件 100%";
    for (String cachedLanguage : UiLanguagePreference.LANGUAGES) {
      String cached =
          String.format(
              java.util.Locale.ROOT, UiMessages.forLanguage(cachedLanguage).get(source), payload);
      for (String target : UiLanguagePreference.LANGUAGES) {
        UiText.setLanguage(target);
        String expected =
            String.format(
                java.util.Locale.ROOT, UiMessages.forLanguage(target).get(source), payload);
        assertEquals(cachedLanguage + " -> " + target, expected, UiStateText.render(cached));
      }
    }
  }

  @Test
  public void cachedPluginManagementStatesFollowEveryLanguageAndKeepEvidenceVerbatim() {
    String[] templates = {
      "当前 Web：加载状态未确认",
      "配置：已启用",
      "配置：已禁用",
      "正在确认提交",
      "%s\n上次激活记录：%s",
      "当前 Web：待核验；上次状态：%s",
      "当前 Web：%s"
    };
    for (String template : templates) {
      Object[] values = new Object[template.split("%s", -1).length - 1];
      for (int index = 0; index < values.length; index++)
        values[index] = "plugin-原文 العربية 100% /root/配置\n证据:" + index;
      for (String source : UiLanguagePreference.LANGUAGES) {
        String sourceTemplate = UiMessages.forLanguage(source).get(template);
        assertNotNull(source + ": " + template, sourceTemplate);
        String cached = String.format(java.util.Locale.ROOT, sourceTemplate, values);
        for (String target : UiLanguagePreference.LANGUAGES) {
          UiText.setLanguage(target);
          String targetTemplate = UiMessages.forLanguage(target).get(template);
          assertNotNull(target + ": " + template, targetTemplate);
          assertEquals(
              template + ": " + source + " -> " + target,
              String.format(java.util.Locale.ROOT, targetTemplate, values),
              UiStateText.render(cached));
        }
      }
    }
  }

  @Test
  public void dynamicStateKeepsPluginNamesPathsAndErrorsVerbatim() {
    UiText.setLanguage("en");
    assertEquals("Enabling 中文-plugin", UiStateText.render("正在启用 中文-plugin"));
    assertEquals(
        "Operation failed: 用户异常: 中文路径 /root/下载", UiStateText.render("操作失败：用户异常: 中文路径 /root/下载"));
    UiText.setLanguage("zh");
    assertEquals("正在启用 English-plugin", UiStateText.render("Enabling English-plugin"));
  }

  @Test
  public void arbitraryTextAndCommandOutputAreNotRewritten() {
    String raw = "printf '正在启用 插件'\nPlugin detection complete. 自定义说明";
    UiText.setLanguage("en");
    assertEquals(raw, UiStateText.render(raw));
    assertEquals("这是用户的插件 description", UiStateText.render("这是用户的插件 description"));
  }

  @Test
  public void templatesHaveMatchingParameterCounts() {
    for (String[] pair : UiMessages.FORMATS)
      assertEquals(pair[0], pair[0].split("%s", -1).length, pair[1].split("%s", -1).length);
  }

  @Test
  public void cachedNativeResetResultChangesLanguageWithoutTranslatingItsPath() {
    String path = "/data/user/0/com.dsh.client/files/原件 English";
    String zh = "配置已重置，对话及原生凭据保留。重置前配置原件：\n" + path;
    String en =
        "Configuration reset; conversations and native credentials retained. Original configuration:\n"
            + path;
    UiText.setLanguage("en");
    assertEquals(en, UiStateText.render(zh));
    UiText.setLanguage("zh");
    assertEquals(zh, UiStateText.render(en));
    assertEquals(
        "proroot 已确认退出，正在使用 proot 进行一次兼容验证…",
        UiStateText.render("proroot has stopped; verifying once with proot compatibility mode…"));
  }

  @Test
  public void retainedInspectionLinesTranslateWithoutChangingSourceIdentity() {
    String key = "ENVIRONMENT:abc:previous-linux-data";
    UiText.setLanguage("en");
    assertEquals(
        key + " · Checksum matches the verification record",
        UiStateText.render(key + " · 摘要与验证记录一致"));
    assertEquals(
        "Inspection passed: 2; failed or incomplete: 1", UiStateText.render("检查通过：2；未通过或未完整读取：1"));
    UiText.setLanguage("zh");
    assertEquals(
        "检查通过：2；未通过或未完整读取：1", UiStateText.render("Inspection passed: 2; failed or incomplete: 1"));
  }
}
