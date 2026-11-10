package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

/** 顶栏「+」气泡的可见项：启动页四项，其它根页只保留全局的下载记录与关于。 */
public final class QuickActionsTest {

  @Test
  public void launchPageOffersFourActionsInOrder() {
    assertEquals(
        List.of(
            QuickActions.Action.DOWNLOADS,
            QuickActions.Action.ABOUT,
            QuickActions.Action.ENVIRONMENT,
            QuickActions.Action.ADDRESS),
        QuickActions.items(true));
  }

  @Test
  public void otherRootPagesKeepOnlyGlobalActions() {
    assertEquals(
        List.of(QuickActions.Action.DOWNLOADS, QuickActions.Action.ABOUT),
        QuickActions.items(false));
  }
}
