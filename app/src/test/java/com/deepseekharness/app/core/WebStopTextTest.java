package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.UiStateText;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.WebStopDiagnostic;
import com.deepseekharness.app.util.WebStopException;
import java.io.IOException;
import org.junit.After;
import org.junit.Test;

public class WebStopTextTest {
  @After
  public void language() {
    UiText.setLanguage("zh");
  }

  @Test
  public void diagnosticKeepsCodesAndRawFactsWhileItsDisplayFollowsCurrentLanguage() {
    var diagnostic = WebStopDiagnostic.of(WebStopDiagnostic.Code.CHECK_FAILED, 42, "中文-errno %s");
    assertEquals(WebStopDiagnostic.Code.CHECK_FAILED, diagnostic.code());
    assertEquals(java.util.List.of("42", "中文-errno %s"), diagnostic.arguments());
    UiText.setLanguage("zh");
    String chinese = WebStopText.render(diagnostic);
    assertEquals("检查 Web 进程失败（PID 42，errno=中文-errno %s）", chinese);
    UiText.setLanguage("en");
    String english = WebStopText.render(diagnostic);
    assertEquals("Could not inspect the Web process (PID 42, errno=中文-errno %s)", english);
    assertEquals(english, UiStateText.render(chinese));
    assertEquals(java.util.List.of("42", "中文-errno %s"), diagnostic.arguments());
  }

  @Test
  public void unknownFailureIsOneRawParameterAndTypedNestedCauseKeepsItsMeaning() {
    String raw = "用户异常 /root/中文.txt %s\nprovider detail";
    UiText.setLanguage("en");
    assertEquals(
        "Web has not stopped: IOException:" + raw, WebStopText.describe(new IOException(raw)));
    var typed = new WebStopException(WebStopDiagnostic.Code.IDENTITY_CHANGED);
    assertTrue(typed.getMessage().startsWith("WEB_STOP_IDENTITY_CHANGED"));
    assertFalse(typed.getMessage().contains("身份"));
    assertEquals(
        "Web process identity changed. Other processes were not terminated. Retry.",
        WebStopText.describe(new IOException("outer message must not replace typed facts", typed)));
  }

  @Test
  public void coordinatedStopCannotLoseATypeOrMarkAnUnknownExitAsStopped() {
    var typed = new WebStopException(WebStopDiagnostic.Code.SIGNAL_DENIED);
    WebStopCoordinator.Result result =
        WebStopCoordinator.stop(
            new WebStopCoordinator.Ports() {
              public String stopGuest() throws IOException {
                throw typed;
              }

              public String stopOwnedLaunchers() {
                return "launcher still alive";
              }

              public boolean confirm() {
                return false;
              }
            });
    assertFalse(result.stopped());
    assertTrue(result.detail().contains("系统拒绝终止本次 Web，原环境保留"));
    assertTrue(result.detail().contains("launcher still alive"));
  }

  @Test
  public void coordinatedFactsKeepAllSourcesAndReRenderAfterLanguageChanges() {
    var denied = WebStopDiagnostic.of(WebStopDiagnostic.Code.SIGNAL_DENIED);
    var changed = WebStopDiagnostic.of(WebStopDiagnostic.Code.IDENTITY_CHANGED);
    var pending = WebStopDiagnostic.of(WebStopDiagnostic.Code.OWNED_LAUNCHER_PENDING);
    WebStopCoordinator.Result result =
        WebStopCoordinator.stop(
            new WebStopCoordinator.Ports() {
              public String stopGuest() {
                throw new AssertionError("typed producer must be used");
              }

              public java.util.List<WebStopDiagnostic> stopGuestFacts() {
                return java.util.List.of(denied, changed);
              }

              public String stopOwnedLaunchers() {
                throw new AssertionError("typed producer must be used");
              }

              public java.util.List<WebStopDiagnostic> stopOwnedLauncherFacts() {
                return java.util.List.of(pending);
              }

              public boolean confirm() {
                return false;
              }
            });
    assertEquals(java.util.List.of(denied, changed, pending), result.diagnostics());
    assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
    assertFalse(result.stopped());
    UiText.setLanguage("en");
    String english = result.detail();
    assertTrue(english.contains("The system denied stopping this Web process"));
    assertTrue(english.contains("identity changed"));
    assertTrue(english.contains("known Web launcher"));
    assertFalse(english.contains("WEB_STOP_"));
    UiText.setLanguage("zh");
    assertTrue(result.detail().contains("已知 Web 启动器未能确认退出"));
    assertEquals(java.util.List.of(denied, changed, pending), result.diagnostics());
  }

  @Test
  public void residualFactsAreShownWithPidAndCommandWhenPresent() {
    var guest =
        WebStopDiagnostic.of(
            WebStopDiagnostic.Code.OWNED_WEB_REMAINS, "PID=42", "命令摘要=node bin.js web");
    assertTrue(WebStopText.render(guest).contains("PID=42"));
    assertTrue(WebStopText.render(guest).contains("命令摘要=node bin.js web"));
    var launcher =
        WebStopDiagnostic.of(WebStopDiagnostic.Code.OWNED_LAUNCHER_PENDING, "PID=43", "旧容器启动器");
    assertTrue(WebStopText.render(launcher).contains("PID=43"));
    // 无参数保持原有短文案（维护页等旧调用）。
    assertEquals(
        "已知 Web 启动器未能确认退出",
        WebStopText.render(WebStopDiagnostic.of(WebStopDiagnostic.Code.OWNED_LAUNCHER_PENDING)));
  }

  @Test
  public void stopProgressNamesTheStageAndElapsedSeconds() {
    assertTrue(WebStopText.progress("stopping-guest", 1_200).contains("已等待 1 秒"));
    assertTrue(WebStopText.progress("stopping-launcher", 7_000).contains("停止旧容器启动器"));
    assertTrue(WebStopText.progress("confirming-exit", 0).contains("核验进程与启动器均已退出"));
    assertTrue(WebStopText.progress(null, -5).contains("正在停止"));
    UiText.setLanguage("en");
    String english = WebStopText.progress("stopping-guest", 3_000);
    assertTrue(english.contains("Stopping"));
    assertTrue(english.contains("3 s"));
  }
}
