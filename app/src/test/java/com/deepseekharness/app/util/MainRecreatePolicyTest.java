package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MainRecreatePolicyTest {
  private static final String PROCESS = "8a6f2c1e-0f5b-4b7e-9d0a-3c2b1a000001";

  @Test
  public void sameProcessRecreateSkipsGate() {
    assertTrue(MainRecreatePolicy.skipEntryGate(PROCESS, PROCESS, true));
  }

  @Test
  public void processDeathRunsFullGate() {
    // 进程被回收后令牌不同：必须按冷启动重新检查维护记录。
    assertFalse(
        MainRecreatePolicy.skipEntryGate("8a6f2c1e-0f5b-4b7e-9d0a-3c2b1a000002", PROCESS, true));
  }

  @Test
  public void freshLaunchOrUnadmittedStateRunsFullGate() {
    assertFalse(MainRecreatePolicy.skipEntryGate(null, PROCESS, false));
    assertFalse(MainRecreatePolicy.skipEntryGate(PROCESS, PROCESS, false));
    // 门禁未放行前旋转：保存态里没有令牌。
    assertFalse(MainRecreatePolicy.skipEntryGate(null, PROCESS, true));
    assertFalse(MainRecreatePolicy.skipEntryGate("", PROCESS, true));
    assertFalse(MainRecreatePolicy.skipEntryGate(PROCESS, null, true));
  }
}
