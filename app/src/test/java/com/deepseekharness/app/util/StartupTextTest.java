package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;

public class StartupTextTest {
    @Test public void historicalStagesAndObserverEventsFollowCurrentLanguage() {
        StartupTrace trace=new StartupTrace();trace.begin(1,0,false);
        trace.stage(1,1,"等待鉴权链接");trace.owned(1,2,"配置检查：plugin-a @ 1.0");
        trace.stage(1,3,"加载 DSH 和已启用插件");
        StartupTrace.Snapshot english=trace.snapshot(4,"en");
        assertEquals("Loading DSH and enabled plugins",english.stage);
        assertFalse(english.log.matches("(?s).*[\\p{IsHan}].*"));
        assertTrue(english.log.contains("Configuration check: plugin-a @ 1.0"));
        assertTrue(trace.snapshot(5,"zh").log.contains("配置检查：plugin-a"));
    }
    @Test public void rawOutputRemainsVerbatimAndEnglishEventsReturnToChinese() {
        StartupTrace trace=new StartupTrace();trace.begin(1,0,false);
        trace.add(1,1,"配置检查：这是用户命令输出");
        trace.owned(1,2,"Loading plugin: plugin-b");
        assertTrue(trace.snapshot(3,"en").log.contains("配置检查：这是用户命令输出"));
        assertTrue(trace.snapshot(3,"zh").log.contains("正在加载插件：plugin-b"));
    }
    @Test public void processExitStatusIsLocalizedWithoutChangingItsCode() {
        assertEquals("proot process exited with code 7 (before authentication)",StartupText.render("proot 进程退出，退出码 7（鉴权前）","en"));
        assertEquals("proroot 进程退出，退出码 127（鉴权后）",StartupText.render("proroot process exited with code 127(after authentication)","zh"));
    }
}
