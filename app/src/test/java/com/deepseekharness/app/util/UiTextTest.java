package com.deepseekharness.app.util;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
public class UiTextTest {
    @After public void restore(){UiText.setLanguage("zh");}
    @Test public void englishCatalogContainsCompleteTranslations() {
        assertTrue(UiMessages.EN.size()>2000);
        for(var item:UiMessages.EN.entrySet()){
            assertNotNull(item.getValue());assertFalse(item.getValue().isEmpty());
            assertFalse(item.getKey(),item.getValue().matches("(?s).*[\\p{IsHan}].*"));
        }
    }
    @Test public void labelsSwitchAndActionArraysKeepTheirKeys() {
        String[] actions={"删除插件","复制插件名称"};UiText.setLanguage("en");
        String[] labels=UiText.text(actions);assertEquals("Delete plugin",labels[0]);assertEquals("删除插件",actions[0]);
        UiText.setLanguage("zh");assertEquals("删除插件",UiText.text(actions)[0]);
    }
    @Test public void toolStatusLeavesCommandPayloadAndUserTextUntouched() {
        UiText.setLanguage("en");String command="printf '这是用户自己的中文命令'";
        assertEquals("⚙ Running command\n"+command,UiText.toolStatus("⚙ 正在执行命令\n"+command));
        String text="用户的自定义内容，与应用文案不同";assertSame(text,UiText.text(text));assertSame(text,UiText.toolStatus(text));
    }
    @Test public void statusTranslationDoesNotChangeInternalStatusKeys() {
        UiText.setLanguage("en");String original="环境任务进行中：插件操作";
        assertEquals("Environment task running: Plugin operation",UiText.status(original));
        assertTrue(original.startsWith("环境任务进行中"));
    }
}
