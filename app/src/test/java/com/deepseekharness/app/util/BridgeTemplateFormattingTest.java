package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BridgeTemplateFormattingTest {
  @Test
  public void actualCompleteEnglishTemplatePreservesCallerTextAndProtocolPrefix() {
    String prior = UiText.language();
    String raw = "原文 %s /data/local/tmp/an apostrophe's ; $name";
    try {
      UiText.setLanguage("en");
      String value = UiText.format("[ERR] 屏幕上找不到可点击的「%s」（先用 dump 看看实际文字）", raw);
      assertTrue(value.startsWith("[ERR] "));
      assertTrue(value.contains(raw));
      assertFalse(value.replace(raw, "").matches(".*[\\u4e00-\\u9fff].*"));
      assertTrue(UiText.format("[ERR] 截屏被系统拒绝（错误码 %d）", 126).contains("126"));
      assertFalse(UiText.format("[ERR] 截屏被系统拒绝（错误码 %d）", 126).contains("错误码"));
      UiText.setLanguage("zh");
      assertTrue(UiText.format("OK 已点击「%s」", raw).contains(raw));
    } finally {
      UiText.setLanguage(prior);
    }
  }
}
