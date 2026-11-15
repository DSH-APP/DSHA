package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import org.junit.Test;

public class WebPageScriptsTest {
  @Test
  public void focusQuestionFindsTheCardAndFieldWithoutForcingFullscreen() {
    String script = WebPageScripts.focusQuestion();
    assertTrue(script.contains("Mbwy4a_frame"));
    assertTrue(script.contains("Mbwy4a_fieldInput"));
    assertTrue(script.contains("scrollIntoView"));
    assertTrue(script.contains(".focus("));
    // 全屏回答必须由用户手动点卡片上的按钮，通知入口不能替用户展开。
    assertFalse(script.contains("question-expand"));
    assertFalse(script.contains("dataset.mobileNavQuestionFull"));
  }

  @Test
  public void focusQuestionChecksOptionRowsInsteadOfTheOptionsContainer() {
    String script = WebPageScripts.focusQuestion();
    // Mbwy4a_options 是宿主为每张提问卡无条件渲染的选项容器，查询它恒为真，
    // 会让 focus() 永远不执行；判据必须是真实的选项行 Mbwy4a_optionRow。
    assertTrue(script.contains("Mbwy4a_optionRow"));
    assertFalse(script.contains("Mbwy4a_options"));
    // 聚焦仍受“没有选项行”保护，且发生在滚进可视区之后。
    int scroll = script.indexOf("scrollIntoView");
    int guard = script.indexOf("Mbwy4a_optionRow");
    int focus = script.indexOf(".focus(");
    assertTrue(scroll >= 0 && guard > scroll && focus > guard);
  }
}
