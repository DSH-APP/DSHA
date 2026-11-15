package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.ShizukuChannelStatus.Step;
import org.junit.Test;

public final class ShizukuChannelStatusTest {
  @Test
  public void userIdFollowsAndroidUidRanges() {
    assertEquals(0, ShizukuChannelStatus.userIdOf(10314));
    assertEquals(900, ShizukuChannelStatus.userIdOf(90010314));
    assertEquals(901, ShizukuChannelStatus.userIdOf(90110314));
    assertEquals(0, ShizukuChannelStatus.userIdOf(-1));
    assertFalse(ShizukuChannelStatus.isProfileUser(10314));
    assertTrue(ShizukuChannelStatus.isProfileUser(90110314));
  }

  @Test
  public void noBinderDependsOnManagerVisibilityAndUser() {
    assertEquals(Step.CONNECT, ShizukuChannelStatus.step(false, false, false, false, true, false));
    assertEquals(Step.CONNECT, ShizukuChannelStatus.step(false, false, false, false, true, true));
    assertEquals(Step.INSTALL, ShizukuChannelStatus.step(false, false, false, false, false, false));
    assertEquals(
        Step.PROFILE_NO_SERVICE,
        ShizukuChannelStatus.step(false, false, false, false, false, true));
    // 无 Binder 时缓存的授权/绑定标志不能让卡片显示已就绪。
    assertEquals(Step.CONNECT, ShizukuChannelStatus.step(false, true, true, false, true, false));
  }

  @Test
  public void cloneProfileWithoutManagerCannotRequestInPlace() {
    // 真机：分身 user 901 收到 Stellar 推送的 Binder，但 Stellar 只装在 user 0。
    Step step = ShizukuChannelStatus.step(true, false, false, false, false, true);
    assertEquals(Step.AUTHORIZE_IN_OWNER, step);
    assertFalse(ShizukuChannelStatus.canRequestPermission(step));
    assertFalse(ShizukuChannelStatus.shouldEnsureBound(step));
  }

  @Test
  public void ownerWithVisibleManagerRequestsOnlyOnClick() {
    Step step = ShizukuChannelStatus.step(true, false, false, false, true, false);
    assertEquals(Step.REQUEST, step);
    assertTrue(ShizukuChannelStatus.canRequestPermission(step));
    assertFalse(ShizukuChannelStatus.shouldEnsureBound(step));
  }

  @Test
  public void grantedStatesBindWithoutRequesting() {
    Step binding = ShizukuChannelStatus.step(true, true, false, false, true, false);
    assertEquals(Step.BINDING, binding);
    assertTrue(ShizukuChannelStatus.shouldEnsureBound(binding));
    assertFalse(ShizukuChannelStatus.canRequestPermission(binding));
    Step failed = ShizukuChannelStatus.step(true, true, false, true, false, true);
    assertEquals(Step.BIND_FAILED, failed);
    assertTrue(ShizukuChannelStatus.shouldEnsureBound(failed));
    Step ready = ShizukuChannelStatus.step(true, true, true, true, false, true);
    assertEquals(Step.READY, ready);
    assertFalse(ShizukuChannelStatus.shouldEnsureBound(ready));
    assertFalse(ShizukuChannelStatus.canRequestPermission(ready));
  }
}
