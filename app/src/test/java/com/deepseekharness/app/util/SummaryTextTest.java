package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class SummaryTextTest {
  @Test
  public void chineseTextKeepsFirstSentenceAndFlagsRemainder() {
    SummaryText.Split split = SummaryText.split("第一句。第二句。");
    assertEquals("第一句。", split.summary);
    assertTrue(split.hasMore);
    assertEquals("第一句。第二句。", split.full);
  }

  @Test
  public void singleSentenceHasNothingMore() {
    SummaryText.Split split = SummaryText.split("  让工作台按你的习惯运行。  ");
    assertEquals("让工作台按你的习惯运行。", split.summary);
    assertFalse(split.hasMore);
  }

  @Test
  public void englishSplitsOnlyBeforeWhitespace() {
    SummaryText.Split split =
        SummaryText.split("Listens on 127.0.0.1 with v1.2 rules. Restart the Web UI to apply.");
    assertEquals("Listens on 127.0.0.1 with v1.2 rules.", split.summary);
    assertTrue(split.hasMore);
  }

  @Test
  public void trailingPeriodWithoutRemainderIsWholeText() {
    SummaryText.Split split = SummaryText.split("Stable and preview updates.");
    assertEquals("Stable and preview updates.", split.summary);
    assertFalse(split.hasMore);
  }

  @Test
  public void newlineAndClosingQuoteAreRespected() {
    assertEquals("第一行", SummaryText.split("第一行\n第二行").summary);
    SummaryText.Split quoted = SummaryText.split("点「保存」后生效。」其余说明。");
    assertEquals("点「保存」后生效。」", quoted.summary);
    assertTrue(quoted.hasMore);
  }

  @Test
  public void arabicQuestionMarkAndNullAreHandled() {
    assertTrue(SummaryText.split("هل تريد المتابعة؟ سيتم الحفظ.").hasMore);
    SummaryText.Split empty = SummaryText.split(null);
    assertEquals("", empty.summary);
    assertFalse(empty.hasMore);
  }
}
