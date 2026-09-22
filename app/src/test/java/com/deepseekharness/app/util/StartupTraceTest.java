package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;

public class StartupTraceTest {
    @Test public void stagesKeepTimingAndCanBeReadByRecreatedPage() {
        StartupTrace trace=new StartupTrace();trace.begin(3,1000,false);
        trace.stage(3,1200,"检查插件");trace.add(3,1300,"正在检查 plugin-a");trace.stage(3,1700,"加载服务端插件");
        StartupTrace.Snapshot s=trace.snapshot(2000);
        assertEquals("加载服务端插件",s.stage);assertEquals(300,s.stageElapsedMs);
        assertTrue(s.log.contains("plugin-a"));assertTrue(s.log.contains("耗时 0.5s"));
    }
    @Test public void oldGenerationCannotPolluteNewStartupOrIssues() {
        StartupTrace trace=new StartupTrace();trace.begin(1,0,false);trace.issue(1,5,"old","broken");
        trace.begin(2,100,true);trace.add(1,110,"late old output");trace.issue(1,110,"old","late");trace.browserReady(1,110);
        StartupTrace.Snapshot s=trace.snapshot(120);assertFalse(s.log.contains("late"));assertTrue(s.safe);assertFalse(s.browserReady);assertTrue(s.issues.isEmpty());
    }
    @Test public void logsAndFailuresAreBoundedAndRedacted() {
        StartupTrace trace=new StartupTrace();trace.begin(1,0,false);
        trace.issue(1,1,"plugin-a","Authorization: Bearer sk-123456789012345678901234567890");
        assertFalse(trace.snapshot(2).issues.get("plugin-a").contains("123456789"));
        for(int i=0;i<500;i++)trace.add(1,i,"x".repeat(1000));
        assertTrue(trace.snapshot(501).log.length()<=48*1024);assertTrue(trace.snapshot(501).log.split("\n").length<=300);
    }
    @Test public void repeatedBrowserReadyDoesNotGrowHistory() {
        StartupTrace trace=new StartupTrace();trace.begin(1,0,false);trace.browserReady(1,10);
        StartupTrace.Snapshot first=trace.snapshot(11);trace.browserReady(1,20);StartupTrace.Snapshot second=trace.snapshot(21);
        assertTrue(first.browserReady);assertEquals(first.revision,second.revision);assertEquals(first.log,second.log);
    }
}
