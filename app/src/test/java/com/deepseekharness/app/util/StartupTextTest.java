package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class StartupTextTest {
  @Test
  public void historicalStagesAndObserverEventsFollowCurrentLanguage() {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    trace.stage(1, 1, "等待鉴权链接");
    trace.owned(1, 2, "配置检查：plugin-a @ 1.0");
    trace.stage(1, 3, "加载 DSH 和已启用插件");
    StartupTrace.Snapshot english = trace.snapshot(4, "en");
    assertEquals("Loading DSH and enabled plugins", english.stage);
    assertFalse(english.log.matches("(?s).*[\\p{IsHan}].*"));
    assertTrue(english.log.contains("Configuration check: plugin-a @ 1.0"));
    assertTrue(trace.snapshot(5, "zh").log.contains("配置检查：plugin-a"));
  }

  @Test
  public void rawOutputRemainsVerbatimAndEnglishEventsReturnToChinese() {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    trace.add(1, 1, "配置检查：这是用户命令输出");
    trace.owned(1, 2, "Loading plugin: plugin-b");
    assertTrue(trace.snapshot(3, "en").log.contains("配置检查：这是用户命令输出"));
    assertTrue(trace.snapshot(3, "zh").log.contains("正在加载插件：plugin-b"));
  }

  @Test
  public void processExitStatusIsLocalizedWithoutChangingItsCode() {
    assertEquals(
        "proot process exited with code 7 (before authentication)",
        StartupText.render("proot 进程退出，退出码 7（鉴权前）", "en"));
    assertEquals(
        "proroot 进程退出，退出码 127（鉴权后）",
        StartupText.render("proroot process exited with code 127(after authentication)", "zh"));
  }

  @Test
  public void registerFailureShowsOnlyTheActionableConclusion() {
    String raw =
        "插件注册失败：BUILTIN_REGISTER_TIMEOUT: exit=-1 timedOut=true truncated=false"
            + " output=REGISTER_TIMING: {\"phase\":\"runtime-links-check\",\"elapsedMs\":479409}"
            + " hostElapsedMs=90012 lastGuestPhase=runtime-links-check GUEST_TIMING_MISMATCH";
    String zh = StartupText.render(raw, "zh");
    assertEquals("插件注册失败：插件注册超时，可直接重试。", zh);
    assertFalse(zh.contains("exit="));
    assertFalse(zh.contains("timedOut="));
    assertFalse(zh.contains("elapsedMs"));
    assertEquals(
        "Plugin registration failed: plugin registration timed out; retry directly.",
        StartupText.render(raw, "en"));
    assertEquals(
        "插件注册失败：插件配置有待确认的锁，可直接重试。",
        StartupText.render(
            "插件注册失败：BUILTIN_REGISTER_EXIT_1: exit=1 output=PLUGIN_PROFILE_LOCK_STALE: dead lock",
            "zh"));
    assertEquals(
        "插件注册失败：插件注册未完成，可直接重试。",
        StartupText.render("插件注册失败：BUILTIN_REGISTER_RECEIPT_MISSING", "zh"));
    // 不是注册失败的应用事件仍走原有逐段渲染，不能被吞掉。
    assertEquals("启动失败：配置检查未通过", StartupText.render("启动失败：配置检查未通过", "zh"));
  }
}
