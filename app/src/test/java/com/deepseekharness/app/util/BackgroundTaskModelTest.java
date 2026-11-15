package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BackgroundTaskModelTest {
  @Test
  public void knownKindsMapToAPageAndUnknownStayReadOnly() {
    assertEquals(BackgroundTaskModel.Category.TERMINAL, BackgroundTaskModel.classify("终端"));
    assertEquals(
        BackgroundTaskModel.Destination.TERMINAL,
        BackgroundTaskModel.destination(BackgroundTaskModel.Category.TERMINAL));
    assertEquals(BackgroundTaskModel.Category.PLUGIN, BackgroundTaskModel.classify("插件管理"));
    assertEquals(
        BackgroundTaskModel.Destination.PLUGINS,
        BackgroundTaskModel.destination(BackgroundTaskModel.Category.PLUGIN));
    assertEquals(BackgroundTaskModel.Category.MAINTENANCE, BackgroundTaskModel.classify("数据维护"));
    assertEquals(
        BackgroundTaskModel.Destination.MAINTENANCE_DETAIL,
        BackgroundTaskModel.destination(BackgroundTaskModel.Category.MAINTENANCE));
    assertEquals(
        BackgroundTaskModel.Destination.DATA,
        BackgroundTaskModel.destination(BackgroundTaskModel.Category.BACKUP));
    assertEquals(BackgroundTaskModel.Category.CONTAINER, BackgroundTaskModel.classify("容器命令"));
    assertEquals(BackgroundTaskModel.Category.CONTAINER, BackgroundTaskModel.classify("后台容器进程"));
    assertEquals(
        BackgroundTaskModel.Destination.NONE,
        BackgroundTaskModel.destination(BackgroundTaskModel.Category.CONTAINER));
    assertEquals(BackgroundTaskModel.Category.SKILL, BackgroundTaskModel.classify("技能读取"));
    assertEquals(BackgroundTaskModel.Category.WORKS, BackgroundTaskModel.classify("作品文件浏览"));
    assertEquals(BackgroundTaskModel.Category.TOOLS, BackgroundTaskModel.classify("基础工具安装"));
    assertEquals(
        BackgroundTaskModel.Destination.INSTALL,
        BackgroundTaskModel.destination(BackgroundTaskModel.Category.TOOLS));
    assertEquals(BackgroundTaskModel.Category.OTHER, BackgroundTaskModel.classify("未知任务"));
    assertEquals(BackgroundTaskModel.Category.OTHER, BackgroundTaskModel.classify(null));
    assertEquals(
        BackgroundTaskModel.Destination.NONE,
        BackgroundTaskModel.destination(BackgroundTaskModel.Category.OTHER));
    assertEquals(BackgroundTaskModel.Destination.NONE, BackgroundTaskModel.destination(null));
  }

  @Test
  public void englishKindsMatchTheSameCategory() {
    assertEquals(BackgroundTaskModel.Category.TERMINAL, BackgroundTaskModel.classify("Terminal"));
    assertEquals(
        BackgroundTaskModel.Category.PLUGIN, BackgroundTaskModel.classify("Plugin management"));
    assertEquals(
        BackgroundTaskModel.Category.MAINTENANCE, BackgroundTaskModel.classify("Data maintenance"));
  }

  @Test
  public void elapsedUsesMinutesThenHours() {
    assertEquals("0 秒", BackgroundTaskModel.elapsed(-1, "zh"));
    assertEquals("0 seconds", BackgroundTaskModel.elapsed(0, "en"));
    assertEquals("59 秒", BackgroundTaskModel.elapsed(59_000, "zh"));
    assertEquals("1 分 0 秒", BackgroundTaskModel.elapsed(60_000, "zh"));
    assertEquals("24 min 53 s", BackgroundTaskModel.elapsed(1_493_000, "en"));
    assertEquals("24 分 53 秒", BackgroundTaskModel.elapsed(1_493_000, "zh"));
    assertEquals("1 时 0 分", BackgroundTaskModel.elapsed(3_600_000, "zh"));
    assertEquals("2 h 5 min", BackgroundTaskModel.elapsed(7_500_000, "en"));
  }
}
