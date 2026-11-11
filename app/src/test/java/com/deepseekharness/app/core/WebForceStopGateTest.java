package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.WebLifecycle;
import org.junit.Test;

public class WebForceStopGateTest {
  @Test
  public void previousBuildRetireOnlyWhenInstalledIdentityDiffersFromRecord() {
    assertTrue(WebLifecycleController.previousBuildInstance("pkg:1:0.1", "pkg:2:0.1"));
    assertFalse(WebLifecycleController.previousBuildInstance("pkg:1:0.1", "pkg:1:0.1"));
    assertFalse(WebLifecycleController.previousBuildInstance(null, "pkg:2:0.1"));
    assertFalse(WebLifecycleController.previousBuildInstance("pkg:1:0.1", ""));
    assertFalse(WebLifecycleController.previousBuildInstance("", "pkg:2:0.1"));
  }

  @Test
  public void forceStopNeedsARealUnconfirmedStopForTheSameGeneration() {
    WebLifecycle lifecycle = new WebLifecycle();
    long running = lifecycle.beginStart(false, false);
    var startPending =
        new WebStopCoordinator.Result(
            WebStopCoordinator.Status.UNCONFIRMED, "WEB_START_PENDING", "");
    var startStopFailed =
        new WebStopCoordinator.Result(
            WebStopCoordinator.Status.UNCONFIRMED, "WEB_START_STOP_UNCONFIRMED", "pending");
    var stopUnconfirmed =
        new WebStopCoordinator.Result(
            WebStopCoordinator.Status.UNCONFIRMED, "WEB_PROCESS_EXIT_UNCONFIRMED", "pending");
    var stopped =
        new WebStopCoordinator.Result(WebStopCoordinator.Status.STOPPED, "WEB_GUESTS_STOPPED", "");
    // 正常启动的占位结果不是失败停止事实：健康运行中的 Web 不得获得强停入口。
    assertFalse(WebLifecycleController.forceStopAllowed(lifecycle, startPending, running));
    assertFalse(WebLifecycleController.forceStopAllowed(lifecycle, startStopFailed, running));
    lifecycle.finishStart(running);
    assertFalse(WebLifecycleController.forceStopAllowed(lifecycle, startPending, running));
    // 启动前置停止失败与普通停止未确认同权；已确认停止不再允许强停。
    assertTrue(WebLifecycleController.forceStopAllowed(lifecycle, startStopFailed, running));
    assertTrue(WebLifecycleController.forceStopAllowed(lifecycle, stopUnconfirmed, running));
    assertFalse(WebLifecycleController.forceStopAllowed(lifecycle, stopped, running));
    long next = lifecycle.beginStart(false, true);
    assertTrue(next > running);
    assertFalse(WebLifecycleController.forceStopAllowed(lifecycle, startStopFailed, running));
    assertFalse(WebLifecycleController.forceStopAllowed(lifecycle, startStopFailed, next));
    lifecycle.finishStart(next);
    // 控制器在 beginStart 后清空上一代结论；新一代只接受本代重新记录的真实失败事实。
    assertFalse(WebLifecycleController.forceStopAllowed(lifecycle, startPending, next));
    assertTrue(WebLifecycleController.forceStopAllowed(lifecycle, startStopFailed, next));
  }
}
