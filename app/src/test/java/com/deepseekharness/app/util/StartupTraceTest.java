package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class StartupTraceTest {
  @Test
  public void stagesKeepTimingAndCanBeReadByRecreatedPage() {
    StartupTrace trace = new StartupTrace();
    trace.begin(3, 1000, false);
    trace.stage(3, 1200, "检查插件");
    trace.add(3, 1300, "正在检查 plugin-a");
    trace.stage(3, 1700, "加载服务端插件");
    StartupTrace.Snapshot s = trace.snapshot(2000);
    assertEquals("加载服务端插件", s.stage);
    assertEquals(300, s.stageElapsedMs);
    assertTrue(s.log.contains("plugin-a"));
    assertTrue(s.log.contains("耗时 0.5s"));
  }

  @Test
  public void oldGenerationCannotPolluteNewStartupOrIssues() {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    trace.issue(1, 5, "old", "broken");
    trace.begin(2, 100, true);
    trace.add(1, 110, "late old output");
    trace.issue(1, 110, "old", "late");
    trace.browserReady(1, 110);
    StartupTrace.Snapshot s = trace.snapshot(120);
    assertFalse(s.log.contains("late"));
    assertTrue(s.safe);
    assertFalse(s.browserReady);
    assertTrue(s.issues.isEmpty());
  }

  @Test
  public void logsAndFailuresAreBoundedAndRedacted() {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    trace.issue(1, 1, "plugin-a", "Authorization: Bearer sk-123456789012345678901234567890");
    assertFalse(trace.snapshot(2).issues.get("plugin-a").contains("123456789"));
    for (int i = 0; i < 500; i++) trace.add(1, i, "x".repeat(1000));
    assertTrue(trace.snapshot(501).log.length() <= 48 * 1024);
    assertTrue(trace.snapshot(501).log.split("\n").length <= 300);
  }

  @Test
  public void repeatedBrowserReadyDoesNotGrowHistory() {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    trace.browserReady(1, 10);
    StartupTrace.Snapshot first = trace.snapshot(11);
    trace.browserReady(1, 20);
    StartupTrace.Snapshot second = trace.snapshot(21);
    assertTrue(first.browserReady);
    assertEquals(first.revision, second.revision);
    assertEquals(first.log, second.log);
  }

  @Test
  public void backendAndBrowserMilestonesFreezeStartupWhileRuntimeTimestampsContinue() {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 1000, false);
    trace.backendReady(1, 3000);
    trace.browserReady(1, 6000);
    trace.add(1, 16_000, "account returned HTTP 403");
    trace.issue(1, 16_001, "account", "runtime-only failure");
    StartupTrace.Snapshot snapshot = trace.snapshot(17_000, "en");
    assertTrue(snapshot.backendReady);
    assertEquals(2000, snapshot.backendReadyMs);
    assertEquals(5000, snapshot.browserReadyMs);
    assertEquals(5000, snapshot.elapsedMs);
    assertEquals(0, snapshot.stageElapsedMs);
    assertEquals(16_000, snapshot.runElapsedMs);
    assertTrue(snapshot.log.contains("BACKEND_AUTH_READY: 2.0s"));
    assertTrue(snapshot.log.contains("BROWSER_READY: 5.0s"));
    assertTrue(
        snapshot.log.contains(
            "[Runtime +10.0s / since startup record 15.0s] account returned HTTP 403"));
    assertTrue(snapshot.issues.isEmpty());
    trace.stage(1, 18_000, "late observer stage");
    assertEquals(snapshot.stage, trace.snapshot(19_000, "en").stage);
  }

  @Test
  public void phaseChangeBeforeAuthCannotCompleteAuthenticationAndMilestonesResetByGeneration() {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 1000, false);
    trace.stage(1, 2000, "等待鉴权链接");
    trace.stage(1, 3000, "检查启动配置");
    assertFalse(trace.snapshot(4000).backendReady);
    assertFalse(trace.snapshot(4000).log.contains("完成「等待鉴权链接」"));
    assertTrue(
        trace
            .snapshot(4000, "en")
            .log
            .contains("Left stage “Wait for authentication link” after 1.0s"));
    trace.backendReady(1, 5000);
    trace.backendReady(1, 6000);
    assertEquals(4000, trace.snapshot(7000).backendReadyMs);
    trace.begin(2, 8000, false);
    trace.backendReady(1, 9000);
    assertFalse(trace.snapshot(10_000).backendReady);
    assertEquals(-1, trace.snapshot(10_000).browserReadyMs);
  }

  @Test
  public void exitedBeforeReadyFreezesDurationAndRetainsOutput() {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 1000, false);
    trace.stage(1, 2000, "等待鉴权链接");
    trace.completed(1, 4000);
    trace.add(1, 5000, "final output");
    assertEquals(3000, trace.snapshot(20_000).elapsedMs);
    assertEquals(2000, trace.snapshot(20_000).stageElapsedMs);
    assertEquals(3000, trace.snapshot(20_000).runElapsedMs);
    assertTrue(trace.snapshot(20_000).log.contains("final output"));
  }
}
