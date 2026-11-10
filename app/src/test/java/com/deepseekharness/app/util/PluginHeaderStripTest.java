package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.PluginHeaderStrip.Action;
import com.deepseekharness.app.util.PluginHeaderStrip.Icon;
import com.deepseekharness.app.util.PluginHeaderStrip.Tone;
import org.junit.Test;

public final class PluginHeaderStripTest {
  private static PluginHeaderStrip.Input in(boolean market) {
    PluginHeaderStrip.Input input = new PluginHeaderStrip.Input();
    input.market = market;
    return input;
  }

  @Test
  public void marketCollapsesWhenNothingToSay() {
    PluginHeaderStrip.View idle = PluginHeaderStrip.project(in(true));
    assertFalse(idle.visible);
    assertEquals(Action.NONE, idle.action);
    assertFalse(idle.showCount);
    PluginHeaderStrip.Input message = in(true);
    message.hasMessage = true;
    PluginHeaderStrip.View shown = PluginHeaderStrip.project(message);
    assertTrue(shown.visible);
    assertTrue("市场页没有动作时，消息可点开", shown.chevron);
  }

  @Test
  public void managementAlwaysShowsCountAndCheckUpdatesWhenIdle() {
    PluginHeaderStrip.View idle = PluginHeaderStrip.project(in(false));
    assertTrue(idle.visible);
    assertTrue(idle.showCount);
    assertEquals(Action.CHECK_UPDATES, idle.action);
    assertTrue(idle.actionEnabled);
    assertFalse(idle.chevron);
    assertEquals(Tone.MUTED, idle.tone);
    assertEquals(Icon.INFO, idle.icon);
  }

  @Test
  public void busyReplacesActionWithCancelAndHidesCount() {
    for (boolean market : new boolean[] {true, false}) {
      PluginHeaderStrip.Input busy = in(market);
      busy.busy = true;
      busy.cancellable = true;
      busy.result = PluginHeaderStrip.RESULT_FAILURE;
      PluginHeaderStrip.View view = PluginHeaderStrip.project(busy);
      assertTrue(view.visible);
      assertEquals("忙碌优先于上一次结果", Tone.ACCENT, view.tone);
      assertEquals(Icon.SPINNER, view.icon);
      assertEquals(Action.CANCEL, view.action);
      assertTrue(view.actionEnabled);
      assertFalse(view.showCount);
      busy.cancellable = false;
      assertFalse("不可取消阶段置灰", PluginHeaderStrip.project(busy).actionEnabled);
      assertEquals(Action.CANCEL, PluginHeaderStrip.project(busy).action);
    }
  }

  @Test
  public void resultsOutrankCatalogStateWhichOutranksIdle() {
    PluginHeaderStrip.Input input = in(false);
    input.catalogError = true;
    input.catalogLoading = true;
    assertEquals(Tone.WARN, PluginHeaderStrip.project(input).tone);
    input.result = PluginHeaderStrip.RESULT_SUCCESS;
    assertEquals(Tone.GOOD, PluginHeaderStrip.project(input).tone);
    assertEquals(Icon.CHECK, PluginHeaderStrip.project(input).icon);
    input.result = PluginHeaderStrip.RESULT_FAILURE;
    assertEquals(Tone.BAD, PluginHeaderStrip.project(input).tone);
    assertEquals(Icon.ERROR, PluginHeaderStrip.project(input).icon);
    PluginHeaderStrip.Input loading = in(false);
    loading.catalogLoading = true;
    PluginHeaderStrip.View view = PluginHeaderStrip.project(loading);
    assertEquals(Tone.ACCENT, view.tone);
    assertEquals(Icon.SPINNER, view.icon);
    assertEquals("目录检测不是可取消任务", Action.CHECK_UPDATES, view.action);
  }
}
