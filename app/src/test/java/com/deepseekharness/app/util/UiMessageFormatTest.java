package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class UiMessageFormatTest {
  @Test
  public void integersPercentAndRawUnicodeNewlinesRoundTripWithoutTranslation() {
    var zh = new UiMessageFormat("进度 %d%% · 文件 %s\n%s");
    var en = new UiMessageFormat("Progress %d%% · File %s\n%s");
    String original = "进度 -12% · 文件 开始配对\n用户 100% 中文\n[EXIT=125]";
    String translated = "Progress -12% · File 开始配对\n用户 100% 中文\n[EXIT=125]";
    assertEquals(translated, zh.reformat(original, en));
    assertEquals(original, en.reformat(translated, zh));
  }

  @Test
  public void integerSlotsRejectNonNumericPartialAndUnrelatedText() {
    var zh = new UiMessageFormat("点按 (%d,%d)");
    var en = new UiMessageFormat("Tap (%d,%d)");
    assertEquals("Tap (+12,-7)", zh.reformat("点按 (+12,-7)", en));
    assertNull(zh.reformat("点按 (x,7)", en));
    assertNull(zh.reformat("prefix 点按 (12,7)", en));
    assertNull(zh.reformat("点按 (12,7) suffix", en));
    assertNull(zh.reformat("点按 (12%,7)", en));
  }

  @Test
  public void unsupportedConversionsAndChangedArgumentRolesAreRejected() {
    for (String invalid : new String[] {"value %", "value %f", "value %1$s", "value %n"}) {
      try {
        new UiMessageFormat(invalid);
        fail(invalid);
      } catch (IllegalArgumentException expected) {
        assertTrue(expected.getMessage().startsWith("UI_FORMAT_"));
      }
    }
    try {
      new UiMessageFormat("%s:%d").reformat("value:7", new UiMessageFormat("%d:%s"));
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals("UI_FORMAT_ARGUMENT_MISMATCH", expected.getMessage());
    }
  }

  @Test
  public void stringsContainingFormatSyntaxRemainRawArguments() {
    var zh = new UiMessageFormat("错误：%s");
    var en = new UiMessageFormat("Error: %s");
    assertEquals(
        "Error: %d %s 100% 中文\nsecond line", zh.reformat("错误：%d %s 100% 中文\nsecond line", en));
  }
}
