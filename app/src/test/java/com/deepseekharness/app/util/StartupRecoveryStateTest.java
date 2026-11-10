package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.StartupRecoveryState.Failure;
import com.deepseekharness.app.util.StartupRecoveryState.Guard;
import com.deepseekharness.app.util.StartupRecoveryState.Kind;
import com.deepseekharness.app.util.StartupRecoveryState.Outcome;
import com.deepseekharness.app.util.StartupRecoveryState.Tone;
import org.junit.After;
import org.junit.Test;

public class StartupRecoveryStateTest {
  @After
  public void restoreLanguage() {
    UiText.setLanguage("zh");
  }

  // ---------------------------------------------------------------- 失败分类

  @Test
  public void portConflictFromReasonOrLogCarriesPort() {
    Failure f = StartupRecoveryState.classify("Web 端口被占用：3080", "", false);
    assertEquals(Kind.PORT, f.kind);
    assertEquals("3080", f.subject);
    Failure fromLog =
        StartupRecoveryState.classify(
            "启动配置或插件加载失败", "[1.0s] Web 端口被占用：8123\n[1.1s] EADDRINUSE", false);
    assertEquals(Kind.PORT, fromLog.kind);
    assertEquals("8123", fromLog.subject);
  }

  @Test
  public void exitIsRecognizedInEveryUiLanguage() {
    for (String language : UiLanguagePreference.LANGUAGES) {
      UiText.setLanguage(language);
      String reason = UiText.format("%s 进程退出，退出码 %s（鉴权前）", "proroot", 139);
      Failure f = StartupRecoveryState.classify(reason, "", false);
      assertEquals(language, Kind.EXIT, f.kind);
      assertEquals(language, "proroot", f.subject);
      assertEquals(language, "139", f.code);
    }
  }

  @Test
  public void healthAndCredentialReasonsAreRecognizedInEveryUiLanguage() {
    for (String language : UiLanguagePreference.LANGUAGES) {
      UiText.setLanguage(language);
      assertEquals(
          language,
          Kind.HEALTH,
          StartupRecoveryState.classify(UiText.text("服务连续三次健康检查未响应"), "", true).kind);
      assertEquals(
          language,
          Kind.CREDENTIAL,
          StartupRecoveryState.classify(
                  UiText.text("保存 API Key 的设备密钥已失效，请重新输入。原记录未自动删除。"), "", false)
              .kind);
    }
  }

  @Test
  public void pluginLineSplitsLoadFailureFromWebPageFailure() {
    String log = "[0.4s] 插件加载异常：file-picker-plus\n[0.5s] TypeError: x";
    Failure before = StartupRecoveryState.classify("启动配置或插件加载失败", log, false);
    assertEquals(Kind.PLUGIN, before.kind);
    assertEquals("file-picker-plus", before.subject);
    assertEquals(Kind.PAGE, StartupRecoveryState.classify("whatever", log, true).kind);
  }

  @Test
  public void configLineIsConfigFailure() {
    assertEquals(
        Kind.CONFIG,
        StartupRecoveryState.classify("启动配置或插件加载失败", "[0.1s] 启动配置检查失败：settings.yaml line 12", false)
            .kind);
  }

  @Test
  public void unknownReasonStaysUnclassifiedAndKeepsRawText() {
    Failure f = StartupRecoveryState.classify("启动失败：java.io.IOException: boom", "", false);
    assertEquals(Kind.FAILED, f.kind);
    assertEquals("启动失败：java.io.IOException: boom", f.raw);
    // 「启动配置或插件加载失败」本身不足以断定是配置还是插件：不猜。
    assertEquals(Kind.FAILED, StartupRecoveryState.classify("启动配置或插件加载失败", "", false).kind);
  }

  @Test
  public void pluginRegistrationFailuresAreActionableInsteadOfUnclassified() {
    Failure timeout =
        StartupRecoveryState.classify(
            "插件注册失败：BUILTIN_REGISTER_TIMEOUT: exit=-1 timedOut=true truncated=false"
                + " output=REGISTER_TIMING: {\"phase\":\"runtime-links-check\",\"elapsedMs\":479409}"
                + " hostElapsedMs=90012 GUEST_TIMING_MISMATCH",
            "",
            false);
    assertEquals(Kind.REGISTER, timeout.kind);
    assertEquals("PLUGIN_REGISTER_TIMEOUT", timeout.code);
    assertEquals(
        "启动失败：插件注册超时（可直接重试）",
        StartupRecoveryState.title(timeout.kind, timeout.subject, timeout.code));
    assertFalse(StartupRecoveryState.detail(Kind.REGISTER).contains("exit="));

    Failure exit =
        StartupRecoveryState.classify(
            "插件注册失败：BUILTIN_REGISTER_EXIT_1: exit=1 timedOut=false"
                + " output=BUILTIN_REGISTER_FAIL: 无法读取包清单",
            "",
            false);
    assertEquals(Kind.REGISTER, exit.kind);
    assertEquals("PLUGIN_REGISTER_FAILED", exit.code);
    assertEquals(
        "启动失败：插件注册未完成（可直接重试）", StartupRecoveryState.title(exit.kind, exit.subject, exit.code));

    Failure lock =
        StartupRecoveryState.classify(
            "STARTUP_PLUGIN_EXIT_1: exit=1 timedOut=false truncated=false"
                + " output=PLUGIN_RESULT: {\"status\":\"error\",\"message\":"
                + "\"PLUGIN_PROFILE_LOCK_STALE: 上一次插件写入未完成\"}",
            "",
            false);
    assertEquals(Kind.REGISTER, lock.kind);
    assertEquals("PLUGIN_REGISTER_LOCK", lock.code);
    assertEquals(
        "启动失败：插件配置有待确认的锁（可直接重试）", StartupRecoveryState.title(lock.kind, lock.subject, lock.code));
    assertFalse(StartupRecoveryState.detail(Kind.REGISTER).isEmpty());
  }

  // ---------------------------------------------------------------- 记录结论

  @Test
  public void eachRecordHasExactlyOneOutcome() {
    assertEquals(Outcome.READY, StartupRecoveryState.outcome("ready", false, 9000, false, false));
    assertEquals(
        Outcome.READY_SAFE, StartupRecoveryState.outcome("ready", true, 9000, false, false));
    // stopped 会覆盖 ready：曾就绪 = 成功后手动停止；未就绪 = 启动时取消。
    assertEquals(
        Outcome.READY_THEN_STOPPED,
        StartupRecoveryState.outcome("stopped", false, 9000, false, false));
    assertEquals(
        Outcome.CANCELLED, StartupRecoveryState.outcome("stopped", false, -1, false, false));
    assertEquals(Outcome.FAILED, StartupRecoveryState.outcome("failed", false, -1, false, false));
    assertEquals(
        Outcome.LOST_AFTER_READY,
        StartupRecoveryState.outcome("failed", false, 9000, false, false));
    // starting 只有属于当前正在进行的一代才算进行中，否则是中断。
    assertEquals(Outcome.RUNNING, StartupRecoveryState.outcome("starting", false, -1, true, true));
    assertEquals(
        Outcome.INTERRUPTED, StartupRecoveryState.outcome("starting", false, -1, true, false));
    assertEquals(
        Outcome.INTERRUPTED, StartupRecoveryState.outcome("starting", false, -1, false, true));
    assertEquals(
        Outcome.INTERRUPTED, StartupRecoveryState.outcome("interrupted", false, -1, false, false));
    // 已拿到鉴权链接但没写最终 ready/failed：不是“无结果”，旧代次应显示已就绪等待进入 / 被新启动取代。
    assertEquals(
        Outcome.READY_WAITING,
        StartupRecoveryState.outcome("starting", false, 7500, -1, false, true));
    assertEquals(
        Outcome.READY_WAITING,
        StartupRecoveryState.outcome("starting", false, 7500, -1, true, false));
    assertEquals(
        Outcome.RUNNING, StartupRecoveryState.outcome("starting", false, 7500, -1, true, true));
    assertEquals(
        Outcome.INTERRUPTED, StartupRecoveryState.outcome("starting", false, -1, -1, false, true));
  }

  @Test
  public void labelsNeverReuseTheOldAmbiguousText() {
    for (Outcome o : Outcome.values()) {
      String label = StartupRecoveryState.outcomeLabel(o, Kind.PORT);
      assertFalse(label, label.contains("／"));
      assertFalse(label.isEmpty());
    }
    assertEquals("失败 · 端口被占用", StartupRecoveryState.outcomeLabel(Outcome.FAILED, Kind.PORT));
    assertEquals("9 秒就绪", StartupRecoveryState.elapsedLabel(Outcome.READY, 12000, 9400));
    assertEquals("第 3 秒失败", StartupRecoveryState.elapsedLabel(Outcome.FAILED, 3200, -1));
    assertEquals("已用 12 秒", StartupRecoveryState.elapsedLabel(Outcome.RUNNING, 12500, -1));
    assertEquals(
        "已就绪 · 等待进入", StartupRecoveryState.outcomeLabel(Outcome.READY_WAITING, Kind.FAILED));
    assertEquals("第 12 秒已就绪", StartupRecoveryState.elapsedLabel(Outcome.READY_WAITING, 12500, -1));
  }

  @Test
  public void tonesMatchMeaning() {
    assertEquals(Tone.GOOD, Outcome.READY_THEN_STOPPED.tone);
    assertEquals(Tone.IDLE, Outcome.CANCELLED.tone);
    assertEquals(Tone.WARN, Outcome.INTERRUPTED.tone);
    assertEquals(Tone.INFO, Outcome.READY_WAITING.tone);
    assertEquals(Tone.BAD, Outcome.FAILED.tone);
    assertEquals(Tone.IDLE, Kind.IDLE.tone);
    assertEquals(Tone.IDLE, Kind.STOPPED.tone);
    assertEquals(Tone.WARN, Kind.PENDING.tone);
    for (Kind k : Kind.values())
      assertEquals(k.name(), k.tone == Tone.BAD || k.tone == Tone.WARN, !k.code.isEmpty());
  }

  // ---------------------------------------------------------------- 页面结论

  @Test
  public void manualOpenWithNothingWrongIsIdleNotAFailure() {
    StartupRecoveryState.Input in = new StartupRecoveryState.Input();
    assertEquals(Kind.IDLE, StartupRecoveryState.page(in));
    in.latest = Outcome.READY;
    assertEquals(Kind.IDLE, StartupRecoveryState.page(in));
    in.latest = Outcome.READY_THEN_STOPPED;
    assertEquals(Kind.STOPPED, StartupRecoveryState.page(in));
    // 已就绪但被新启动取代：不是故障，页面保持空闲态。
    in.latest = Outcome.READY_WAITING;
    assertEquals(Kind.IDLE, StartupRecoveryState.page(in));
  }

  @Test
  public void pagePriorityIsPendingBusyEnvironmentStartingRunningThenLatest() {
    StartupRecoveryState.Input in = new StartupRecoveryState.Input();
    in.latest = Outcome.FAILED;
    in.latestFailure = StartupRecoveryState.classify("Web 端口被占用：3080", "", false);
    assertEquals(Kind.PORT, StartupRecoveryState.page(in));
    in.running = true;
    assertEquals(Kind.RUNNING, StartupRecoveryState.page(in));
    in.starting = true;
    assertEquals(Kind.STARTING, StartupRecoveryState.page(in));
    in.environmentReady = false;
    assertEquals(Kind.ENVIRONMENT, StartupRecoveryState.page(in));
    in.busy = true;
    assertEquals(Kind.BUSY, StartupRecoveryState.page(in));
    in.pending = true;
    assertEquals(Kind.PENDING, StartupRecoveryState.page(in));
  }

  @Test
  public void storedReasonIsUsedOnlyWithoutRecords() {
    StartupRecoveryState.Input in = new StartupRecoveryState.Input();
    in.storedFailure = StartupRecoveryState.classify("服务连续三次健康检查未响应", "", true);
    assertEquals(Kind.HEALTH, StartupRecoveryState.page(in));
    in.latest = Outcome.INTERRUPTED;
    assertEquals(Kind.INTERRUPTED, StartupRecoveryState.page(in));
  }

  // ---------------------------------------------------------------- 不可用原因

  @Test
  public void blockedReasonsAreExplicit() {
    assertEquals("", StartupRecoveryState.blocked(Guard.START, Kind.PORT, false, true));
    assertEquals("已在运行", StartupRecoveryState.blocked(Guard.START, Kind.RUNNING, false, true));
    assertEquals(
        "运行环境未就绪", StartupRecoveryState.blocked(Guard.START, Kind.ENVIRONMENT, false, true));
    assertEquals(
        "先回滚未完成的修复", StartupRecoveryState.blocked(Guard.CHANGE, Kind.PENDING, false, true));
    assertEquals("", StartupRecoveryState.blocked(Guard.PENDING_ONLY, Kind.PENDING, false, true));
    assertEquals(
        "没有未完成的修复", StartupRecoveryState.blocked(Guard.PENDING_ONLY, Kind.IDLE, false, true));
    assertEquals(
        "没有可用的健康快照", StartupRecoveryState.blocked(Guard.RESTORE, Kind.CONFIG, false, false));
    assertEquals("", StartupRecoveryState.blocked(Guard.CHANGE, Kind.CONFIG, false, false));
    assertEquals("等待当前任务完成", StartupRecoveryState.blocked(Guard.START, Kind.IDLE, true, true));
    assertEquals("启动进行中", StartupRecoveryState.blocked(Guard.CHANGE, Kind.STARTING, false, true));
  }

  @Test
  public void everyKindHasTitleAndDetailInEveryLanguage() {
    for (String language : UiLanguagePreference.LANGUAGES) {
      UiText.setLanguage(language);
      for (Kind k : Kind.values()) {
        assertFalse(StartupRecoveryState.title(k, "x", "1").isEmpty());
        assertFalse(StartupRecoveryState.detail(k).isEmpty());
      }
    }
    UiText.setLanguage("en");
    assertEquals(
        "Start failed: port 3080 in use", StartupRecoveryState.title(Kind.PORT, "3080", ""));
    assertEquals(
        "Start failed: proroot exited with code 139",
        StartupRecoveryState.title(Kind.EXIT, "proroot", "139"));
  }

  @Test
  public void templatePatternIsLiteralExceptPlaceholders() {
    assertTrue(StartupRecoveryState.templatePattern("a.(%s)").matcher("a.(x)").matches());
    assertFalse(StartupRecoveryState.templatePattern("a.(%s)").matcher("ab(x)").matches());
  }
}
