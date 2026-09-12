package com.deepseekharness.app.util;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
public class UiStateTextTest {
    @After public void reset(){UiText.setLanguage("zh");}
    @Test public void cachedPluginResultFollowsBothLanguageChanges() {
        String zh="插件检测完成；新检测到的插件可开启开关加入 Web，变更后重启 Web 生效";
        UiText.setLanguage("en");String cached=UiText.text(zh);assertNotEquals(zh,cached);
        UiText.setLanguage("zh");assertEquals(zh,UiStateText.render(cached));
        UiText.setLanguage("en");assertEquals(cached,UiStateText.render(zh));
    }
    @Test public void dynamicStateKeepsPluginNamesPathsAndErrorsVerbatim() {
        UiText.setLanguage("en");
        assertEquals("Enabling 中文-plugin",UiStateText.render("正在启用 中文-plugin"));
        assertEquals("Operation failed: 用户异常: 中文路径 /root/下载",UiStateText.render("操作失败：用户异常: 中文路径 /root/下载"));
        UiText.setLanguage("zh");
        assertEquals("正在启用 English-plugin",UiStateText.render("Enabling English-plugin"));
    }
    @Test public void arbitraryTextAndCommandOutputAreNotRewritten() {
        String raw="printf '正在启用 插件'\nPlugin detection complete. 自定义说明";
        UiText.setLanguage("en");assertEquals(raw,UiStateText.render(raw));
        assertEquals("这是用户的插件 description",UiStateText.render("这是用户的插件 description"));
    }
    @Test public void templatesHaveMatchingParameterCounts() {
        for(String[] pair:UiMessages.FORMATS)
            assertEquals(pair[0],pair[0].split("%s",-1).length,pair[1].split("%s",-1).length);
    }
}
