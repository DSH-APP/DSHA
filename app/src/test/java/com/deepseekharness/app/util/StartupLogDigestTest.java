package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class StartupLogDigestTest {
  private static StartupTrace sample() {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    trace.stage(1, 100, "检查旧插件文件链接");
    trace.add(
        1,
        600,
        "CAS_TIMING: {\"phase\":\"scan\",\"elapsedMs\":122.07,\"entries\":16,\"directories\":1}");
    trace.stage(1, 700, "注册插件");
    trace.add(
        1,
        900,
        "STARTUP_PREP_TIMING: phase=prepare-plugin-helpers durationMs=110 elapsedMs=836 outcome=returned");
    trace.add(1, 1600, "RUNTIME_LINKS_CACHED: 共享依赖未变化，复用已检查链接");
    trace.stage(1, 1700, "启动 Web 服务");
    return trace;
  }

  @Test
  public void stagesFoldStartAndDoneLinesAndKeepTimingAsDetails() {
    StartupTrace.Snapshot s = sample().snapshot(2000, "zh");
    StartupLogDigest.Result d =
        StartupLogDigest.digest(s.entries, StartupLogDigest.Outcome.RUNNING, s.elapsedMs);
    assertEquals(4, d.stages.size());
    // 开头的隐式阶段从完成行拿到名字。
    assertEquals("排队启动", d.stages.get(0).name);
    StartupLogDigest.Stage links = d.stages.get(1);
    assertEquals("检查旧插件文件链接", links.name);
    assertEquals(600, links.durationMs);
    assertEquals(StartupLogDigest.State.GOOD, links.state);
    StartupLogDigest.Item cas = links.items.get(0);
    assertEquals(StartupLogDigest.ItemKind.TIMING, cas.kind);
    assertEquals("cas scan", cas.label);
    assertEquals(122, cas.valueMs);
    assertEquals(16, cas.count);
    StartupLogDigest.Stage plugins = d.stages.get(2);
    assertEquals(110, plugins.items.get(0).valueMs);
    // 普通行时间相对阶段开始，标签去掉大写前缀。
    assertEquals(900, plugins.items.get(1).valueMs);
    assertEquals("共享依赖未变化，复用已检查链接", plugins.items.get(1).label);
    StartupLogDigest.Stage web = d.stages.get(3);
    assertEquals(StartupLogDigest.State.RUNNING, web.state);
    assertEquals(300, web.durationMs);
    assertEquals(2, d.timingCount);
    assertEquals(0, d.issueCount);
    assertEquals(s.entries.size(), d.lineClasses.size());
    assertEquals(s.log.split("\n").length, s.entries.size());
  }

  @Test
  public void entriesMatchRenderedLogExactly() {
    StartupTrace.Snapshot s = sample().snapshot(2000, "en");
    StringBuilder joined = new StringBuilder();
    for (StartupTrace.Entry e : s.entries) {
      if (joined.length() > 0) joined.append('\n');
      joined.append(e.line);
    }
    assertEquals(s.log, joined.toString());
    // 英文阶段名同样按 kind 分组，不依赖中文文案。
    StartupLogDigest.Result d =
        StartupLogDigest.digest(s.entries, StartupLogDigest.Outcome.RUNNING, s.elapsedMs);
    assertEquals(4, d.stages.size());
  }

  @Test
  public void issuesFloatUpAndFailureMarksLastStage() {
    StartupTrace trace = sample();
    trace.issue(1, 2400, "dsh-example-tools", "入口文件不存在（lib/index.js）");
    trace.completed(1, 2500);
    StartupTrace.Snapshot s = trace.snapshot(3000, "zh");
    StartupLogDigest.Result warn =
        StartupLogDigest.digest(s.entries, StartupLogDigest.Outcome.RUNNING, s.elapsedMs);
    assertEquals(2, warn.issueCount);
    assertEquals(StartupLogDigest.Severity.WARN, warn.worst);
    assertEquals(StartupLogDigest.State.RUNNING, warn.stages.get(3).state);
    StartupLogDigest.Result failed =
        StartupLogDigest.digest(s.entries, StartupLogDigest.Outcome.FAILED, s.elapsedMs);
    assertEquals(StartupLogDigest.Severity.BAD, failed.worst);
    assertEquals(StartupLogDigest.State.BAD, failed.stages.get(3).state);
    assertEquals(2500, failed.stages.get(3).durationMs + failed.stages.get(3).startMs);
    List<StartupLogDigest.LineClass> classes = failed.lineClasses;
    assertEquals(StartupLogDigest.LineClass.BAD, classes.get(classes.size() - 1));
  }

  @Test
  public void malformedTimingIsKeptAsNoteAndThrownTimingIsAnIssue() {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    trace.add(1, 10, "CAS_TIMING: {broken");
    trace.add(1, 20, "STARTUP_PREP_TIMING: phase=register-builtins durationMs=683 outcome=threw");
    trace.add(1, 30, "共有 0 errors");
    StartupTrace.Snapshot s = trace.snapshot(40, "zh");
    StartupLogDigest.Result d =
        StartupLogDigest.digest(s.entries, StartupLogDigest.Outcome.RUNNING, s.elapsedMs);
    List<StartupLogDigest.Item> items = d.stages.get(0).items;
    assertEquals(4, items.size());
    assertEquals(StartupLogDigest.ItemKind.NOTE, items.get(1).kind);
    assertEquals(StartupLogDigest.ItemKind.TIMING, items.get(2).kind);
    assertEquals(StartupLogDigest.Severity.BAD, items.get(2).severity);
    assertEquals(StartupLogDigest.Severity.NONE, items.get(3).severity);
    assertEquals(1, d.issueCount);
    assertEquals(StartupLogDigest.State.BAD, d.stages.get(0).state);
  }

  @Test
  public void readyMilestoneHasNoDurationAndAuthLineUsesTagLabel() {
    StartupTrace trace = sample();
    trace.backendReady(1, 3400);
    trace.browserReady(1, 3900);
    StartupTrace.Snapshot s = trace.snapshot(9000, "zh");
    StartupLogDigest.Outcome outcome = StartupLogDigest.Outcome.READY;
    StartupLogDigest.Result d =
        StartupLogDigest.digest(s.entries, outcome, StartupLogDigest.endMs(s, outcome));
    StartupLogDigest.Stage last = d.stages.get(d.stages.size() - 1);
    assertEquals("网页已就绪", last.name);
    assertEquals(-1, last.durationMs);
    assertEquals(StartupLogDigest.State.GOOD, last.state);
    StartupLogDigest.Stage web = d.stages.get(d.stages.size() - 2);
    assertEquals(2200, web.durationMs);
    assertEquals("BACKEND_AUTH_READY", web.items.get(0).label);
  }

  @Test
  public void emptyAndStoppedTracesStayNeutral() {
    StartupLogDigest.Result empty = StartupLogDigest.digest(null, StartupLogDigest.Outcome.IDLE, 0);
    assertTrue(empty.stages.isEmpty());
    StartupTrace.Snapshot s = sample().snapshot(2000, "zh");
    StartupLogDigest.Result stopped =
        StartupLogDigest.digest(s.entries, StartupLogDigest.Outcome.STOPPED, s.elapsedMs);
    assertEquals(StartupLogDigest.State.MUTED, stopped.stages.get(stopped.stages.size() - 1).state);
  }

  @Test
  public void runtimeErrorsAfterReadyAreProblems() {
    StartupTrace trace = sample();
    trace.browserReady(1, 3000);
    trace.issue(1, 4000, "plugin-a", "crashed");
    StartupTrace.Snapshot s = trace.snapshot(5000, "zh");
    StartupLogDigest.Result d =
        StartupLogDigest.digest(
            s.entries,
            StartupLogDigest.Outcome.READY,
            StartupLogDigest.endMs(s, StartupLogDigest.Outcome.READY));
    assertEquals(1, d.issueCount);
    assertEquals(StartupLogDigest.State.WARN, d.stages.get(d.stages.size() - 1).state);
  }
}
