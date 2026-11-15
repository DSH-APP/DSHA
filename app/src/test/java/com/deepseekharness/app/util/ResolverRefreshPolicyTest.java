package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class ResolverRefreshPolicyTest {
  @Test
  public void refreshOnlyOnNewNonEmptyDns() {
    List<String> wifi = List.of("172.19.0.2"), cellular = List.of("10.0.0.53");
    assertTrue(ResolverRefreshPolicy.shouldRefresh(null, wifi));
    assertTrue(ResolverRefreshPolicy.shouldRefresh(List.of(), wifi));
    assertTrue(ResolverRefreshPolicy.shouldRefresh(wifi, cellular));
    assertFalse(ResolverRefreshPolicy.shouldRefresh(wifi, wifi));
    // 断网瞬间不写：保留上次有效的托管段
    assertFalse(ResolverRefreshPolicy.shouldRefresh(wifi, List.of()));
    assertFalse(ResolverRefreshPolicy.shouldRefresh(wifi, null));
    // 顺序变化也是不同的 resolv.conf
    assertTrue(
        ResolverRefreshPolicy.shouldRefresh(
            List.of("10.0.0.53", "10.0.0.54"), List.of("10.0.0.54", "10.0.0.53")));
  }
}
